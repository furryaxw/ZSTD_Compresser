package top.furryaxw.zstd_compresser;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZstdBatchDecoder extends ByteToMessageDecoder {

    private static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

    private volatile boolean ready;
    private ScheduledFuture<?> statsFuture;

    private int framesRx;
    private int bytesIn;
    private int bytesOut;
    private int packetsOut;
    private ZstdConfig config;
    private ZstdChannelManager mgr;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdConfig.INSTANCE;
        ready = true;
        mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr == null) Zstd_compresser.LOGGER.warn("[Zstd] decoder: ZstdChannelManager not found on channel");
        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!ready) return;
        if (!in.isReadable()) return;

        int dataLength = ZstdChannelManager.tryReadVarInt(in);
        if (dataLength == -1) return;
        if (dataLength == -2) return;

        int remaining = in.readableBytes();
        if (remaining <= 0) return;
        if (dataLength == 0) {
            bytesIn += remaining; bytesOut += remaining;
            ZstdStatsData.addRxBatch(remaining, remaining);
            ByteBuf rawData = in.readRetainedSlice(remaining);
            splitBatch(rawData, out);
            rawData.release();
            return;
        }
        if (dataLength > MAX_DECOMPRESSED_SIZE) return;

        ByteBuf payload = in.readRetainedSlice(remaining);
        byte[] compressed = new byte[remaining];
        payload.readBytes(compressed);
        payload.release();
        bytesIn += remaining;

        try {
            byte[] decompressed;
            ZstdDecompressCtx dctx = mgr != null ? mgr.getDecompressCtx() : null;
            decompressed = dctx != null ? dctx.decompress(compressed, dataLength)
                    : Zstd.decompress(compressed, dataLength);
            ByteBuf workBuf = ctx.alloc().buffer(decompressed.length);
            workBuf.writeBytes(decompressed);
            bytesOut += decompressed.length;
            ZstdStatsData.addRxBatch(remaining, decompressed.length);
            splitBatch(workBuf, out);
            workBuf.release();
        } catch (Exception e) {
            Zstd_compresser.LOGGER.warn("[Zstd] decompress failed: {}", e.toString());
        }
    }

    private void splitBatch(ByteBuf workBuf, List<Object> out) {
        int count = 0;
        while (workBuf.readableBytes() >= 1) {
            int len = ZstdChannelManager.tryReadVarInt(workBuf);
            if (len < 0 || len == 0 || len > workBuf.readableBytes()) break;
            out.add(workBuf.readRetainedSlice(len));
            count++;
        }
        framesRx++; packetsOut += count;
    }

    private void printStats() {
        if (framesRx == 0) return;
        Zstd_compresser.LOGGER.info("[Zstd] RX: {} frames, {}B in, {}B out, {} packets",
                framesRx, bytesIn, bytesOut, packetsOut);
        ZstdStatsData.updateRx(framesRx, 0, bytesIn, bytesOut, packetsOut);
        framesRx = 0; bytesIn = 0; bytesOut = 0; packetsOut = 0;
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ready = false;
        if (statsFuture != null) { statsFuture.cancel(false); statsFuture = null; }
        super.channelInactive(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ready = false; if (statsFuture != null) statsFuture.cancel(false);
        ctx.close();
    }
}
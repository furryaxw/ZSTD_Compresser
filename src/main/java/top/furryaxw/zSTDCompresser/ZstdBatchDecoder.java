package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZstdBatchDecoder extends ByteToMessageDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");
    private static final int MAX_DECOMPRESSED_SIZE = 8 * 1024 * 1024;

    private volatile boolean ready;
    private ScheduledFuture<?> statsFuture;

    private int framesRx;
    private int bytesIn;
    private int bytesOut;
    private int packetsOut;
    private ZstdVelocityConfig config;
    private ZstdChannelManager mgr;
    private boolean firstFrameLogged;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdVelocityConfig.INSTANCE;
        ready = true;
        mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr == null) LOGGER.warn("[Zstd] VELOCITY decoder: ZstdChannelManager not found on channel");
        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!ready) return;
        if (!in.isReadable()) return;

        int savedIdx = in.readerIndex();
        int dataLength = ZstdChannelManager.tryReadVarInt(in);
        if (dataLength == -1) return;
        if (dataLength == -2) return;

        if (dataLength == ZstdChannelManager.BATCH_SIGNAL) {
            ZstdChannelManager.TransportMode mode = ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).get();
            if (mode != ZstdChannelManager.TransportMode.BATCH) {
                ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).set(ZstdChannelManager.TransportMode.BATCH);
                LOGGER.info("[Zstd] VELOCITY Received BATCH signal from client — activating BATCH encoder");
            }
            return;
        }

        int remaining = in.readableBytes();
        if (remaining <= 0) return;
        if (dataLength == 0) {
            bytesIn += remaining; bytesOut += remaining;
            ByteBuf rawData = in.readRetainedSlice(remaining);
            int count = splitBatch(rawData, out);
            rawData.release();
            if (count > 1) detectBatch(ctx.channel());
            zstd_compresser$logFrame(ctx, "raw", count);
            return;
        }
        if (dataLength > MAX_DECOMPRESSED_SIZE) return;

        byte[] compressed = new byte[remaining];
        in.getBytes(in.readerIndex(), compressed);
        bytesIn += remaining;

        try {
            byte[] decompressed;
            ZstdDecompressCtx dctx = mgr != null ? mgr.getDecompressCtx() : null;
            decompressed = dctx != null ? dctx.decompress(compressed, dataLength)
                    : Zstd.decompress(compressed, dataLength);
            in.skipBytes(remaining);
            ByteBuf workBuf = ctx.alloc().buffer(decompressed.length);
            workBuf.writeBytes(decompressed);
            bytesOut += decompressed.length;
            int count = splitBatch(workBuf, out);
            workBuf.release();
            if (count > 1) detectBatch(ctx.channel());
            zstd_compresser$logFrame(ctx, "zstd", count);
        } catch (Exception e) {
            LOGGER.warn("[Zstd] VELOCITY decompress failed, passing raw: dataLength={} remaining={} error={}",
                    dataLength, remaining, e.toString());
            // Restore reader to before VarInt, pass complete frame through
            in.readerIndex(savedIdx);
            out.add(in.readRetainedSlice(in.readableBytes()));
            framesRx++;
        }
    }

    private int splitBatch(ByteBuf workBuf, List<Object> out) {
        int count = 0;
        while (workBuf.readableBytes() >= 1) {
            int len = ZstdChannelManager.tryReadVarInt(workBuf);
            if (len < 0 || len == 0 || len > workBuf.readableBytes()) break;
            int start = workBuf.readerIndex();
            out.add(workBuf.readRetainedSlice(len));
            samplePacket(workBuf, start, len);
            count++;
        }
        framesRx++; packetsOut += count;
        return count;
    }

    private void samplePacket(ByteBuf workBuf, int start, int length) {
        try {
            byte[] sample = new byte[length];
            workBuf.getBytes(start, sample, 0, length);
            ZstdSampleTrainer.submitDecoderSample(sample);
        } catch (Exception ignored) {}
    }

    private void detectBatch(io.netty.channel.Channel channel) {
        ZstdChannelManager.TransportMode mode = channel.attr(ZstdChannelManager.ZSTD_MODE).get();
        if (mode != ZstdChannelManager.TransportMode.BATCH) {
            channel.attr(ZstdChannelManager.ZSTD_MODE).set(ZstdChannelManager.TransportMode.BATCH);
            LOGGER.debug("[Zstd] VELOCITY Decoder detected BATCH mode from client — switching encoder to BATCH");
        }
    }

    private void zstd_compresser$logFrame(ChannelHandlerContext ctx, String type, int count) {
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            ZstdChannelManager.TransportMode mode = ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).get();
            LOGGER.debug("[Zstd] VELOCITY Decoder active: type={} count={} mode={}",
                    type, count, mode);
        }
    }

    private void printStats() {
        if (framesRx == 0) return;
        LOGGER.info("[Zstd] VELOCITY RX: {} frames, {}B in, {}B out, {} packets",
                framesRx, bytesIn, bytesOut, packetsOut);
        framesRx = 0; bytesIn = 0; bytesOut = 0; packetsOut = 0;
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ready = false; if (statsFuture != null) statsFuture.cancel(false);
        super.channelInactive(ctx);
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ready = false; if (statsFuture != null) statsFuture.cancel(false);
        if (ctx.channel().isOpen()) {
            ctx.close();
        }
    }
}
package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZstdBatchEncoder extends ChannelOutboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private ScheduledFuture<?> statsFuture;
    private ZstdCompressCtx compressCtx;
    private ZstdVelocityConfig config;

    private int framesSent;
    private int bytesRaw;
    private int bytesCompressed;
    private int skippedCount;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdVelocityConfig.INSTANCE;
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null) compressCtx = mgr.getCompressCtx();
        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf packet)) { ctx.write(msg, promise); return; }
        try {
            flushPassthrough(ctx, packet, packet.readableBytes());
            promise.setSuccess();
        } finally { io.netty.util.ReferenceCountUtil.release(packet); }
    }

    private void flushPassthrough(ChannelHandlerContext ctx, ByteBuf packet, int pktSize) {
        int fullSize = ZstdChannelManager.varIntLength(pktSize) + pktSize;
        byte[] data = new byte[fullSize];
        ByteBuf wrap = ctx.alloc().buffer(fullSize);
        try { ZstdChannelManager.writeVarInt(wrap, pktSize); wrap.writeBytes(packet); wrap.readBytes(data); }
        finally { wrap.release(); }
        byte[] compressed = compressCtx.compress(data);
        if (compressed.length >= fullSize) {
            skippedCount++;
            int payloadLength = 1 + fullSize;
            ByteBuf frame = ctx.alloc().buffer(5 + payloadLength);
            ZstdChannelManager.writeVarInt(frame, payloadLength);
            ZstdChannelManager.writeVarInt(frame, 0);
            frame.writeBytes(data);
            ctx.write(frame, ctx.voidPromise());
            bytesCompressed += fullSize;
            framesSent++; bytesRaw += fullSize;
        } else {
            int innerSize = ZstdChannelManager.varIntLength(fullSize);
            int payloadLength = innerSize + compressed.length;
            ByteBuf frame = ctx.alloc().buffer(5 + payloadLength);
            ZstdChannelManager.writeVarInt(frame, payloadLength);
            ZstdChannelManager.writeVarInt(frame, fullSize);
            frame.writeBytes(compressed);
            ctx.write(frame, ctx.voidPromise());
            bytesCompressed += compressed.length;
            framesSent++; bytesRaw += fullSize;
        }
        ctx.flush();
        samplePacket(packet, pktSize);
    }

    @Override public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        if (statsFuture != null) statsFuture.cancel(false);
        super.handlerRemoved(ctx);
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (statsFuture != null) statsFuture.cancel(false);
        ctx.fireExceptionCaught(cause);
    }

    private void printStats() {
        if (framesSent == 0) return;
        LOGGER.info("[Zstd] VELOCITY TX: {} frames, {}B raw, {}B out, {}% ratio, {} skipped",
                framesSent, bytesRaw, bytesCompressed,
                bytesRaw > 0 ? String.format("%.1f", 100.0 * bytesCompressed / bytesRaw) : "0", skippedCount);
        framesSent = 0; bytesRaw = 0; bytesCompressed = 0; skippedCount = 0;
    }

    private void samplePacket(ByteBuf packet, int size) {
        try {
            byte[] sample = new byte[size];
            packet.getBytes(packet.readerIndex(), sample);
            ZstdSampleTrainer.submitEncoderSample(sample);
        } catch (Exception ignored) {}
    }
}

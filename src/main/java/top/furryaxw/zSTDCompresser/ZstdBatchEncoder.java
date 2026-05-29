package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZstdBatchEncoder extends ChannelOutboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private ByteBuf accumulator;
    private ScheduledFuture<?> flushFuture;
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

        ZstdChannelManager.TransportMode mode = ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).get();
        if (mode == ZstdChannelManager.TransportMode.BATCH) {
            accumulator = ctx.alloc().directBuffer(config.batchMaxBytes);
            scheduleFlush(ctx);
            LOGGER.debug("[Zstd] VELOCITY Encoder started in BATCH mode");
        }

        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf packet)) { ctx.write(msg, promise); return; }

        ZstdChannelManager.TransportMode mode = ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).get();
        if (mode == ZstdChannelManager.TransportMode.BATCH) {
            try {
                int pktSize = packet.readableBytes();
                int prefixSize = ZstdChannelManager.varIntLength(pktSize);
                if (accumulator == null) {
                    accumulator = ctx.alloc().directBuffer(config.batchMaxBytes);
                    scheduleFlush(ctx);
                }
                if (prefixSize + pktSize > accumulator.maxWritableBytes()) {
                    flushBatch(ctx);
                    if (prefixSize + pktSize > accumulator.maxWritableBytes()) {
                        accumulator.ensureWritable(prefixSize + pktSize);
                    }
                }
                ZstdChannelManager.writeVarInt(accumulator, pktSize);
                accumulator.writeBytes(packet);
                samplePacket(packet, pktSize);
                promise.setSuccess();
            } finally {
                ReferenceCountUtil.release(packet);
            }
        } else {
            try {
                flushPassthrough(ctx, packet, packet.readableBytes());
                promise.setSuccess();
            } finally { ReferenceCountUtil.release(packet); }
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        ZstdChannelManager.TransportMode mode = ctx.channel().attr(ZstdChannelManager.ZSTD_MODE).get();
        if (mode == ZstdChannelManager.TransportMode.BATCH) {
            flushBatch(ctx);
        }
        ctx.flush();
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

    private void flushBatch(ChannelHandlerContext ctx) {
        cancelFlush();
        if (accumulator == null || accumulator.readableBytes() <= 0 || compressCtx == null) {
            scheduleFlush(ctx);
            return;
        }
        int rawSize = accumulator.readableBytes();
        byte[] data = new byte[rawSize];
        accumulator.readBytes(data);
        byte[] compressed = compressCtx.compress(data);

        if (compressed.length >= rawSize) {
            skippedCount++;
            int payloadLength = ZstdChannelManager.varIntLength(0) + rawSize;
            ByteBuf frame = ctx.alloc().buffer(5 + payloadLength);
            ZstdChannelManager.writeVarInt(frame, payloadLength);
            ZstdChannelManager.writeVarInt(frame, 0);
            frame.writeBytes(data);
            ctx.write(frame, ctx.voidPromise());
            bytesCompressed += rawSize;
            framesSent++; bytesRaw += rawSize;
        } else {
            int innerSize = ZstdChannelManager.varIntLength(rawSize);
            int payloadLength = innerSize + compressed.length;
            ByteBuf frame = ctx.alloc().buffer(5 + payloadLength);
            ZstdChannelManager.writeVarInt(frame, payloadLength);
            ZstdChannelManager.writeVarInt(frame, rawSize);
            frame.writeBytes(compressed);
            ctx.write(frame, ctx.voidPromise());
            bytesCompressed += compressed.length;
            framesSent++; bytesRaw += rawSize;
        }
        accumulator.clear();
        scheduleFlush(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        cancelFlush();
        if (statsFuture != null) statsFuture.cancel(false);
        if (accumulator != null && accumulator.refCnt() > 0) {
            accumulator.release();
            accumulator = null;
        }
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cancelFlush();
        if (statsFuture != null) statsFuture.cancel(false);
        if (accumulator != null && accumulator.refCnt() > 0) {
            accumulator.release();
            accumulator = null;
        }
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

    private void scheduleFlush(ChannelHandlerContext ctx) {
        cancelFlush();
        flushFuture = ctx.executor().schedule(() -> {
            flushBatch(ctx);
            ctx.flush();
        }, config.flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void cancelFlush() {
        if (flushFuture != null && !flushFuture.isDone()) {
            flushFuture.cancel(false);
            flushFuture = null;
        }
    }
}

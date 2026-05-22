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
    private boolean firstFrame = true;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdVelocityConfig.INSTANCE;
        accumulator = ctx.alloc().directBuffer(config.batchMaxBytes);
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null) {
            compressCtx = mgr.getCompressCtx();
        }
        scheduleFlush(ctx);
        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf packet)) {
            ctx.write(msg, promise);
            return;
        }
        try {
            int packetSize = packet.readableBytes();
            int prefixSize = ZstdChannelManager.varIntLength(packetSize);

            if (prefixSize + packetSize > accumulator.maxWritableBytes()) {
                flushBatch(ctx);
                if (prefixSize + packetSize > accumulator.maxWritableBytes()) {
                    accumulator.ensureWritable(prefixSize + packetSize);
                }
            }
            ZstdChannelManager.writeVarInt(accumulator, packetSize);
            accumulator.writeBytes(packet);
            promise.setSuccess();
            samplePacket(packet, packetSize);
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        flushBatch(ctx);
        ctx.flush();
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

    private void flushBatch(ChannelHandlerContext ctx) {
        cancelFlush();
        if (accumulator != null && accumulator.readableBytes() > 0 && compressCtx != null) {
            int rawSize = accumulator.readableBytes();
            byte[] data = new byte[rawSize];
            accumulator.readBytes(data);
            byte[] compressed = compressCtx.compress(data);

            ByteBuf frame;
            if (compressed.length >= rawSize && !firstFrame) {
                frame = ctx.alloc().buffer(6 + rawSize);
                ZstdChannelManager.writeVarInt(frame, rawSize + 1);
                frame.writeByte(0);
                frame.writeBytes(data);
                skippedCount++;
                bytesCompressed += frame.readableBytes();
            } else {
                firstFrame = false;
                frame = ctx.alloc().buffer(5 + compressed.length);
                ZstdChannelManager.writeVarInt(frame, compressed.length);
                frame.writeBytes(compressed);
                bytesCompressed += frame.readableBytes();
            }
            ctx.write(frame, ctx.voidPromise());
            accumulator.clear();

            framesSent++;
            bytesRaw += rawSize;
        }
        scheduleFlush(ctx);
    }

    private void printStats() {
        if (framesSent == 0) return;
        LOGGER.info("[Zstd] VELOCITY TX: {} frames, {}B raw, {}B out, {}% ratio, {} skipped",
                framesSent, bytesRaw, bytesCompressed,
                bytesRaw > 0 ? String.format("%.1f", 100.0 * bytesCompressed / bytesRaw) : "0", skippedCount);
        framesSent = 0;
        bytesRaw = 0;
        bytesCompressed = 0;
        skippedCount = 0;
    }

    private void scheduleFlush(ChannelHandlerContext ctx) {
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

    private void samplePacket(ByteBuf packet, int size) {
        try {
            byte[] sample = new byte[size];
            packet.getBytes(packet.readerIndex(), sample);
            ZstdSampleTrainer.submitEncoderSample(sample);
        } catch (Exception ignored) {
        }
    }
}

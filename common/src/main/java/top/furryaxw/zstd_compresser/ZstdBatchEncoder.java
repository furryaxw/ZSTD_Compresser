package top.furryaxw.zstd_compresser;

import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ZstdBatchEncoder extends ChannelOutboundHandlerAdapter {

    private ByteBuf accumulator;
    private ScheduledFuture<?> flushFuture;
    private ScheduledFuture<?> statsFuture;
    private ZstdCompressCtx compressCtx;
    private ZstdConfig config;

    private int framesSent;
    private int bytesRaw;
    private int bytesCompressed;
    private int skippedCount;
    private boolean firstFrame = true;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdConfig.INSTANCE;
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
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
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
        } finally {
            ReferenceCountUtil.release(packet);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
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

            int sentSize;
            if (compressed.length >= rawSize && !firstFrame) {
                ByteBuf raw = ctx.alloc().buffer(1 + rawSize);
                raw.writeByte(0);
                raw.writeBytes(data);
                ctx.write(raw, ctx.voidPromise());
                skippedCount++;
                sentSize = rawSize + 1;
            } else {
                firstFrame = false;
                ctx.write(Unpooled.wrappedBuffer(compressed), ctx.voidPromise());
                sentSize = compressed.length;
            }
            accumulator.clear();
            bytesCompressed += sentSize;

            framesSent++;
            bytesRaw += rawSize;
            ZstdStatsData.addTxBatch(rawSize, sentSize);
        }
        scheduleFlush(ctx);
    }

    private void printStats() {
        if (framesSent == 0) return;
        Zstd_compresser.LOGGER.info(
                "[Zstd] TX: {} frames, {}B raw, {}B out, {}% ratio, {} skipped",
                framesSent, bytesRaw, bytesCompressed,
                bytesRaw > 0 ? String.format("%.1f", 100.0 * bytesCompressed / bytesRaw) : "0", skippedCount);
        ZstdStatsData.updateTx(framesSent, bytesRaw, bytesCompressed, skippedCount);
        ZstdStatsData.statsIntervalSec = config.statsIntervalSec;
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
}

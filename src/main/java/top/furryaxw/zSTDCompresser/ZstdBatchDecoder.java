package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
    private static final int ZSTD_MAGIC = 0x28B52FFD;

    private ZstdDecompressCtx decompressCtx;
    private volatile boolean ready;
    private ScheduledFuture<?> statsFuture;

    private int framesRx;
    private int bytesIn;
    private int bytesOut;
    private int packetsOut;
    private int rawFrames;
    private ZstdVelocityConfig config;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        config = ZstdVelocityConfig.INSTANCE;
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null) {
            decompressCtx = mgr.getDecompressCtx();
            ready = true;
        }
        if (config.statsEnabled) {
            statsFuture = ctx.executor().scheduleAtFixedRate(
                    () -> printStats(), config.statsIntervalSec * 1000L, config.statsIntervalSec * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!ready) {
            ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
            if (mgr != null) {
                decompressCtx = mgr.getDecompressCtx();
                ready = true;
            } else {
                return;
            }
        }
        if (!in.isReadable()) return;

        int frameLen = in.readableBytes();
        boolean isZstd = frameLen >= 4 && in.getInt(in.readerIndex()) == ZSTD_MAGIC;

        ByteBuf workBuf;
        if (isZstd) {
            byte[] compressed = new byte[frameLen];
            in.readBytes(compressed);
            long decompressedSize = Zstd.decompressedSize(compressed);
            if (decompressedSize <= 0 || decompressedSize > MAX_DECOMPRESSED_SIZE) {
                return;
            }
            byte[] decompressed = decompressCtx.decompress(compressed, (int) decompressedSize);
            workBuf = Unpooled.wrappedBuffer(decompressed);
            bytesIn += frameLen;
            bytesOut += (int) decompressedSize;
        } else {
            if (in.getUnsignedByte(in.readerIndex()) == 0) {
                in.readByte();
            }
            int dataLen = in.readableBytes();
            workBuf = in.readRetainedSlice(dataLen);
            bytesIn += frameLen;
            bytesOut += dataLen;
            rawFrames++;
        }

        int count = 0;
        while (workBuf.isReadable()) {
            int packetStart = workBuf.readerIndex();
            int packetLength = ZstdChannelManager.readVarInt(workBuf);
            if (packetLength <= 0 || packetLength > workBuf.readableBytes()) break;
            ByteBuf singlePacket = workBuf.readRetainedSlice(packetLength);
            out.add(singlePacket);
            samplePacket(workBuf, packetStart, packetLength);
            count++;
        }
        workBuf.release();
        framesRx++;
        packetsOut += count;
    }

    private void printStats() {
        if (framesRx == 0) return;
        LOGGER.info("[Zstd] VELOCITY RX: {} frames ({} raw), {}B in, {}B out, {} packets",
                framesRx, rawFrames, bytesIn, bytesOut, packetsOut);
        framesRx = 0;
        rawFrames = 0;
        bytesIn = 0;
        bytesOut = 0;
        packetsOut = 0;
    }

    private void samplePacket(ByteBuf workBuf, int packetStart, int packetLength) {
        try {
            byte[] sample = new byte[packetLength];
            workBuf.getBytes(packetStart, sample, 0, packetLength);
            ZstdSampleTrainer.submitDecoderSample(sample);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        ready = false;
        if (statsFuture != null) statsFuture.cancel(false);
        super.channelInactive(ctx);
    }
}

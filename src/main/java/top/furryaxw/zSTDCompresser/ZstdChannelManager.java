package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstdChannelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    public static final AttributeKey<Boolean> ZSTD_ENABLED =
            AttributeKey.valueOf("zstd:enabled");
    public static final AttributeKey<ZstdChannelManager> KEY =
            AttributeKey.valueOf("zstd:manager");

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private Long dictId;
    private byte[] dictBytes;
    private long expectedDictId;
    private boolean finishConfigPending;
    private boolean decoderInstalled;
    private boolean encoderInstalled;
    private boolean setCompressionSeen;
    private boolean replaced;
    private Object heldFinishConfig;

    public ZstdChannelManager() {
        ZstdVelocityConfig cfg = ZstdVelocityConfig.INSTANCE;
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(cfg.level);
        this.compressCtx.setWindowLog(cfg.windowLog);
        this.decompressCtx = new ZstdDecompressCtx();
        loadTrainerDicts();
    }

    private void loadTrainerDicts() {
        ZstdSampleTrainer encoder = ZstdSampleTrainer.getEncoder();
        ZstdSampleTrainer decoder = ZstdSampleTrainer.getDecoder();
        if (encoder != null && encoder.getCurrentDict() != null) {
            ZstdDictCompress c = new ZstdDictCompress(encoder.getCurrentDict(), 9);
            compressCtx.loadDict(c);
            LOGGER.info("[Zstd] VELOCITY Loaded encoder dict {} ({}B) -> compressCtx",
                    encoder.getCurrentDictId(), encoder.getCurrentDict().length);
        }
        if (decoder != null && decoder.getCurrentDict() != null) {
            ZstdDictDecompress d = new ZstdDictDecompress(decoder.getCurrentDict());
            decompressCtx.loadDict(d);
            LOGGER.info("[Zstd] VELOCITY Loaded decoder dict {} ({}B) -> decompressCtx",
                    decoder.getCurrentDictId(), decoder.getCurrentDict().length);
        }
    }

    public ZstdCompressCtx getCompressCtx() {
        return compressCtx;
    }

    public ZstdDecompressCtx getDecompressCtx() {
        return decompressCtx;
    }

    public Long getDictId() {
        return dictId;
    }

    public byte[] getDictBytes() {
        return dictBytes;
    }

    public void loadDict(byte[] dictBytes, long dictId) {
        this.dictBytes = dictBytes;
        this.dictId = dictId;
        ZstdDictCompress dictCompress = new ZstdDictCompress(dictBytes, 9);
        compressCtx.loadDict(dictCompress);
        ZstdDictDecompress dictDecompress = new ZstdDictDecompress(dictBytes);
        decompressCtx.loadDict(dictDecompress);
    }

    public long getExpectedDictId() {
        return expectedDictId;
    }

    public void setExpectedDictId(long expectedDictId) {
        this.expectedDictId = expectedDictId;
    }

    public boolean isFinishConfigPending() {
        return finishConfigPending;
    }

    public void setFinishConfigPending(boolean pending) {
        this.finishConfigPending = pending;
    }

    public boolean isSetCompressionSeen() {
        return setCompressionSeen;
    }

    public void setSetCompressionSeen(boolean v) {
        this.setCompressionSeen = v;
    }

    public boolean isReplaced() {
        return replaced;
    }

    public void setReplaced(boolean v) {
        this.replaced = v;
    }

    public void setHeldFinishConfig(Object msg) {
        this.heldFinishConfig = msg;
    }

    public void releaseHeld() {
        if (heldFinishConfig instanceof io.netty.buffer.ByteBuf
                && ((io.netty.buffer.ByteBuf) heldFinishConfig).refCnt() > 0) {
            ((io.netty.buffer.ByteBuf) heldFinishConfig).release();
        }
        heldFinishConfig = null;
    }

    public void releaseHeldAndSend(Channel channel) {
        if (heldFinishConfig == null) return;
        Object msg = heldFinishConfig;
        heldFinishConfig = null;
        channel.pipeline().writeAndFlush(msg, channel.voidPromise());
    }

    public boolean isDecoderInstalled() {
        return decoderInstalled;
    }

    public boolean isEncoderInstalled() {
        return encoderInstalled;
    }

    public void installEncoder(Channel channel) {
        if (channel == null || encoderInstalled) return;
        if (channel.pipeline().get("compression-encoder") != null) {
            channel.pipeline().replace("compression-encoder", "zstd_encoder", new ZstdBatchEncoder());
            encoderInstalled = true;
        } else if (channel.pipeline().get("zstd_encoder") == null) {
            channel.pipeline().addBefore("minecraft-encoder", "zstd_encoder", new ZstdBatchEncoder());
            encoderInstalled = true;
        }
    }

    public void installDecoder(Channel channel) {
        if (channel == null || decoderInstalled) return;
        ChannelPipeline p = channel.pipeline();
        if (p.get("zstd_decoder") != null) return;

        if (p.get("compression-decoder") != null) {
            p.replace("compression-decoder", "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("decompress") != null) {
            p.replace("decompress", "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("minecraft-decoder") != null) {
            p.addBefore("minecraft-decoder", "zstd_decoder", new ZstdBatchDecoder());
        } else {
            p.addLast("zstd_decoder", new ZstdBatchDecoder());
        }
        decoderInstalled = true;
    }

    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }

    public static int readVarInt(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            b = buf.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return result;
    }

    public static int varIntLength(int value) {
        for (int i = 1; i < 5; i++) {
            if ((value & (0xFFFFFFFF << (7 * i))) == 0) {
                return i;
            }
        }
        return 5;
    }
}

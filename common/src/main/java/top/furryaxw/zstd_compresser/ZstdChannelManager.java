package top.furryaxw.zstd_compresser;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;

public class ZstdChannelManager {

    public enum TransportState {
        PLAIN,
        NEGOTIATING,
        ZSTD_ACTIVE
    }

    public enum TransportMode {
        PASSTHROUGH
    }

    public static final AttributeKey<TransportState> ZSTD_STATE =
            AttributeKey.valueOf("zstd:state");
    public static final AttributeKey<TransportMode> ZSTD_MODE =
            AttributeKey.valueOf("zstd:mode");
    public static final AttributeKey<Boolean> ZSTD_ENABLED =
            AttributeKey.valueOf("zstd:enabled");
    public static final AttributeKey<ZstdChannelManager> KEY =
            AttributeKey.valueOf("zstd:manager");
    public static final AttributeKey<Boolean> CLIENT_ZSTD_READY =
            AttributeKey.valueOf("zstd:client_ready");

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private Long dictId;
    private byte[] dictBytes;
    private long expectedDictId;
    private boolean finishConfigPending;
    private boolean decoderInstalled;
    private boolean encoderInstalled;
    private byte[] compressScratch = new byte[65536];
    private byte[] decompressScratch = new byte[65536];

    public ZstdChannelManager() {
        ZstdConfig cfg = ZstdConfig.INSTANCE;
        this.compressCtx = new ZstdCompressCtx();
        this.compressCtx.setLevel(cfg.level);
        this.compressCtx.setWindowLog(cfg.windowLog);
        this.decompressCtx = new ZstdDecompressCtx();
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

    public void loadEncoderDict(byte[] dictBytes, long dictId) {
        ZstdDictDecompress dictDecompress = new ZstdDictDecompress(dictBytes);
        decompressCtx.loadDict(dictDecompress);
    }

    public void loadDecoderDict(byte[] dictBytes, long dictId) {
        ZstdDictCompress dictCompress = new ZstdDictCompress(dictBytes, 9);
        compressCtx.loadDict(dictCompress);
    }

    private long expectedEncoderDictId;
    private long expectedDecoderDictId;

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

    public boolean isDecoderInstalled() {
        return decoderInstalled;
    }

    public boolean isEncoderInstalled() {
        return encoderInstalled;
    }

    public byte[] getCompressScratch(int minSize) {
        if (compressScratch.length < minSize) {
            compressScratch = new byte[minSize];
        }
        return compressScratch;
    }

    public byte[] getDecompressScratch(int minSize) {
        if (decompressScratch.length < minSize) {
            decompressScratch = new byte[minSize];
        }
        return decompressScratch;
    }

    public void installEncoder(Channel channel) {
        if (channel == null || encoderInstalled) return;
        ChannelPipeline p = channel.pipeline();
        if (p.get("zstd_encoder") != null) return;

        if (p.get("compress") != null) {
            p.replace("compress", "zstd_encoder", new ZstdBatchEncoder());
        } else if (p.get("compression-encoder") != null) {
            p.replace("compression-encoder", "zstd_encoder", new ZstdBatchEncoder());
        } else if (p.get("encoder") != null) {
            p.addBefore("encoder", "zstd_encoder", new ZstdBatchEncoder());
        } else {
            p.addLast("zstd_encoder", new ZstdBatchEncoder());
        }
        encoderInstalled = true;
    }

    public void installDecoder(Channel channel) {
        if (channel == null || decoderInstalled) return;
        ChannelPipeline p = channel.pipeline();
        if (p.get("zstd_decoder") != null) return;

        if (p.get("decompress") != null) {
            p.replace("decompress", "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("compression-decoder") != null) {
            p.replace("compression-decoder", "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("decoder") != null) {
            p.addBefore("decoder", "zstd_decoder", new ZstdBatchDecoder());
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

    public static final int MAX_COMPRESSED_FRAME_SIZE = 8 * 1024 * 1024;
    public static final int PROTOCOL_VERSION = 1;

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

    public static int tryReadVarInt(ByteBuf buf) {
        buf.markReaderIndex();
        int result = 0;
        int shift = 0;
        int read = 0;
        while (read < 5) {
            if (!buf.isReadable()) {
                buf.resetReaderIndex();
                return -1;
            }
            byte b = buf.readByte();
            read++;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (result < 0 || result > MAX_COMPRESSED_FRAME_SIZE) {
                    buf.resetReaderIndex();
                    return -2;
                }
                return result;
            }
            shift += 7;
            if (shift > 35) {
                buf.resetReaderIndex();
                return -2;
            }
        }
        buf.resetReaderIndex();
        return -2;
    }

    public static int tryReadFrameLength(ByteBuf buf) {
        buf.markReaderIndex();
        int value = 0;
        int shift = 0;
        int read = 0;
        while (read < 5) {
            if (!buf.isReadable()) {
                buf.resetReaderIndex();
                return -1;
            }
            byte b = buf.readByte();
            read++;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (value < 0 || value > MAX_COMPRESSED_FRAME_SIZE) {
                    buf.resetReaderIndex();
                    return -2;
                }
                if (buf.readableBytes() < value) {
                    buf.resetReaderIndex();
                    return -1;
                }
                return value;
            }
            shift += 7;
            if (shift > 35) {
                buf.resetReaderIndex();
                return -2;
            }
        }
        buf.resetReaderIndex();
        return -2;
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
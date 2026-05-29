package top.furryaxw.zstd_compresser;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;

public class ZstdChannelManager {

    public enum TransportState {
        PLAIN,
        NEGOTIATING,
        ZSTD_ACTIVE
    }

    public enum TransportMode {
        PASSTHROUGH,
        BATCH
    }

    public static final AttributeKey<TransportState> ZSTD_STATE =
            AttributeKey.valueOf("zstd:state");
    public static final AttributeKey<TransportMode> ZSTD_MODE =
            AttributeKey.valueOf("zstd:mode");
    public static final AttributeKey<ZstdChannelManager> KEY =
            AttributeKey.valueOf("zstd:manager");

    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;
    private long expectedDictId;
    private boolean finishConfigPending;

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

    public void loadEncoderDict(byte[] dictBytes, long dictId) {
        ZstdDictDecompress dictDecompress = new ZstdDictDecompress(dictBytes);
        decompressCtx.loadDict(dictDecompress);
    }

    public void loadDecoderDict(byte[] dictBytes, long dictId) {
        ZstdDictCompress dictCompress = new ZstdDictCompress(dictBytes, 9);
        compressCtx.loadDict(dictCompress);
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
    public static final int BATCH_SIGNAL = 0x7FFFFFFF;
    public static final int PROTOCOL_VERSION = 1;

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
                if (result < 0 || (result > MAX_COMPRESSED_FRAME_SIZE && result != BATCH_SIGNAL)) {
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

    public static int varIntLength(int value) {
        for (int i = 1; i < 5; i++) {
            if ((value & (0xFFFFFFFF << (7 * i))) == 0) {
                return i;
            }
        }
        return 5;
    }
}

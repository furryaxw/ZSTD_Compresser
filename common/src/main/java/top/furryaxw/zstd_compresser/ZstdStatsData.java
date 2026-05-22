package top.furryaxw.zstd_compresser;

public class ZstdStatsData {

    public static volatile long txRawBytesPerSec;
    public static volatile long txCompressedBytesPerSec;
    public static volatile int txFramesPerSec;
    public static volatile double txRatio;

    public static volatile long rxInBytesPerSec;
    public static volatile long rxOutBytesPerSec;
    public static volatile int rxFramesPerSec;
    public static volatile int rxPacketsPerSec;
    public static volatile int rxRawFramesPerSec;

    public static volatile int statsIntervalSec = 10;

    private static long accumTxRaw;
    private static long accumTxCompressed;
    private static long accumRxIn;
    private static long accumRxOut;

    private static long lastCaptureTime;
    private static long lastTxRaw;
    private static long lastTxCompressed;
    private static long lastRxIn;
    private static long lastRxOut;
    private static int tabFrames;

    private static final int RING = 10;
    private static final long[] ringTxRawPerSec = new long[RING];
    private static final long[] ringTxCmpPerSec = new long[RING];
    private static final long[] ringRxInPerSec = new long[RING];
    private static final long[] ringRxOutPerSec = new long[RING];
    private static int ringIdx;
    private static int ringCount;

    public static void addTxBatch(int rawBytes, int compressedBytes) {
        synchronized (ZstdStatsData.class) {
            accumTxRaw += rawBytes;
            accumTxCompressed += compressedBytes;
        }
    }

    public static void addRxBatch(int inBytes, int outBytes) {
        synchronized (ZstdStatsData.class) {
            accumRxIn += inBytes;
            accumRxOut += outBytes;
        }
    }

    public static String captureTabStats(int tabIntervalSec) {
        synchronized (ZstdStatsData.class) {
            long now = System.currentTimeMillis();
            long elapsed = lastCaptureTime > 0 ? now - lastCaptureTime : tabIntervalSec * 1000L;

            long curTxRaw = accumTxRaw;
            long curTxCmp = accumTxCompressed;
            long curRxIn = accumRxIn;
            long curRxOut = accumRxOut;

            long dTxRaw = curTxRaw - lastTxRaw;
            long dTxCmp = curTxCmp - lastTxCompressed;
            long dRxIn = curRxIn - lastRxIn;
            long dRxOut = curRxOut - lastRxOut;
            tabFrames++;

            lastCaptureTime = now;
            lastTxRaw = curTxRaw;
            lastTxCompressed = curTxCmp;
            lastRxIn = curRxIn;
            lastRxOut = curRxOut;

            double sec = elapsed / 1000.0;
            long txRawPerSec = (long) (dTxRaw / sec);
            long txCmpPerSec = (long) (dTxCmp / sec);
            long rxInPerSec = (long) (dRxIn / sec);
            long rxOutPerSec = (long) (dRxOut / sec);

            ringTxRawPerSec[ringIdx] = txRawPerSec;
            ringTxCmpPerSec[ringIdx] = txCmpPerSec;
            ringRxInPerSec[ringIdx] = rxInPerSec;
            ringRxOutPerSec[ringIdx] = rxOutPerSec;
            ringIdx = (ringIdx + 1) % RING;
            if (ringCount < RING) ringCount++;

            if (tabFrames < 3) return "Zstd: initializing...";

            long avgTxRaw = avg(ringTxRawPerSec);
            long avgTxCmp = avg(ringTxCmpPerSec);
            long avgRxIn = avg(ringRxInPerSec);
            long avgRxOut = avg(ringRxOutPerSec);
            if (avgTxRaw == 0 && avgRxIn == 0) return null;

            double ratio = avgTxRaw > 0 ? 100.0 * avgTxCmp / avgTxRaw : 100;
            double rxRatio = avgRxIn > 0 ? 100.0 * avgRxIn / avgRxOut : 100;

            return String.format(
                    "§lTX:§r %s → %s %s  |  §lRX:§r %s → %s %s",
                    fmtBytes(avgTxRaw), fmtBytes(avgTxCmp),
                    colorRatio(ratio), fmtBytes(avgRxIn), fmtBytes(avgRxOut),
                    colorRatio(rxRatio));
        }
    }

    public static void updateTx(int frames, int rawBytes, int compressedBytes, int skipped) {
        txFramesPerSec = frames;
        txRawBytesPerSec = rawBytes;
        txCompressedBytesPerSec = compressedBytes;
        txRatio = rawBytes > 0 ? 100.0 * compressedBytes / rawBytes : 100;
    }

    public static void updateRx(int frames, int rawFrames, int inBytes, int outBytes, int packets) {
        rxFramesPerSec = frames;
        rxRawFramesPerSec = rawFrames;
        rxInBytesPerSec = inBytes;
        rxOutBytesPerSec = outBytes;
        rxPacketsPerSec = packets;
    }

    private static String fmtBytes(long bps) {
        if (bps < 1000) return String.format("%4dB/s", bps);
        if (bps < 1_000_000) return String.format("%4.1fKB/s", bps / 1000.0);
        return String.format("%4.1fMB/s", bps / 1_000_000.0);
    }

    private static String colorRatio(double ratio) {
        String s = String.format("%5.1f%%", ratio);
        if (ratio < 30) return "§a" + s;
        if (ratio < 80) return "§e" + s;
        return "§c" + s;
    }

    private static long avg(long[] ring) {
        long sum = 0;
        for (int i = 0; i < ringCount; i++) sum += ring[i];
        return ringCount > 0 ? sum / ringCount : 0;
    }
}

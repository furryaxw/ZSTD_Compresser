package top.furryaxw.zSTDCompresser;

import com.github.luben.zstd.ZstdDictTrainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class ZstdSampleTrainer {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private final String name;
    private final Path dictDir;
    private final List<byte[]> sampleRing = new ArrayList<>(10_000);
    private long lastTrainTime;
    private byte[] currentDict;
    private long currentDictId;
    private int sampleBytes;

    private final int maxSamples;
    private final int minSamples;
    private final long trainCooldownMs;
    private final long fallbackTimeoutMs;
    private final int dictSize;
    private final int sampleSize;
    private final int maxHistorySamples;
    private final double adoptRatioImprovement;
    private final int pruneMinPayload;

    private static volatile ZstdSampleTrainer encoderInstance;
    private static volatile ZstdSampleTrainer decoderInstance;
    private static ScheduledExecutorService autoSaveExecutor;

    public static void init(Path dataDir) {
        Path dir = dataDir.resolve("zstd_dicts");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        ZstdVelocityConfig cfg = ZstdVelocityConfig.INSTANCE;
        encoderInstance = new ZstdSampleTrainer("encoder", dir, cfg);
        decoderInstance = new ZstdSampleTrainer("decoder", dir, cfg);
        encoderInstance.loadFromDisk();
        decoderInstance.loadFromDisk();
        LOGGER.info("[Zstd] Trainers initialized. encoderDictId={} decoderDictId={}",
                encoderInstance.currentDictId, decoderInstance.currentDictId);
        autoSaveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "zstd-autosave");
            t.setDaemon(true);
            return t;
        });
        autoSaveExecutor.scheduleWithFixedDelay(ZstdSampleTrainer::saveAll, 5, 5, TimeUnit.MINUTES);
    }

    public static void shutdown() {
        if (autoSaveExecutor != null) {
            autoSaveExecutor.shutdown();
        }
        saveAll();
    }

    public static void submitEncoderSample(byte[] packetBytes) {
        ZstdSampleTrainer t = encoderInstance;
        if (t != null) t.addSample(packetBytes);
    }

    public static void submitDecoderSample(byte[] packetBytes) {
        ZstdSampleTrainer t = decoderInstance;
        if (t != null) t.addSample(packetBytes);
    }

    public static ZstdSampleTrainer getEncoder() {
        return encoderInstance;
    }

    public static ZstdSampleTrainer getDecoder() {
        return decoderInstance;
    }

    private ZstdSampleTrainer(String name, Path dictDir, ZstdVelocityConfig cfg) {
        this.name = name;
        this.dictDir = dictDir;
        this.maxSamples = cfg.trainerMaxSamples;
        this.minSamples = cfg.trainerMinSamples;
        this.trainCooldownMs = cfg.trainerCooldownMs;
        this.fallbackTimeoutMs = cfg.trainerFallbackTimeoutMs;
        this.dictSize = cfg.trainerDictMaxBytes;
        this.sampleSize = cfg.trainerSampleTargetBytes;
        this.maxHistorySamples = cfg.trainerMaxHistorySamples;
        this.adoptRatioImprovement = cfg.trainerAdoptionThreshold;
        this.pruneMinPayload = cfg.trainerPruneMinPayload;
    }

    public String getName() {
        return name;
    }

    public byte[] getCurrentDict() {
        return currentDict;
    }

    public long getCurrentDictId() {
        return currentDictId;
    }

    public int getSampleCount() {
        synchronized (this) {
            return sampleRing.size();
        }
    }

    public int getSampleBytes() {
        return sampleBytes;
    }

    public long getLastTrainTime() {
        return lastTrainTime;
    }

    public static void saveAll() {
        if (encoderInstance != null) encoderInstance.saveSamples();
        if (decoderInstance != null) decoderInstance.saveSamples();
    }

    private void saveSamples() {
        synchronized (this) {
            if (sampleRing.isEmpty()) return;
            try {
                List<byte[]> all = new ArrayList<>(loadHistory());
                all.addAll(sampleRing);
                while (all.size() > maxHistorySamples) all.remove(0);
                writeSamples(all);
                LOGGER.debug("[Zstd] {} auto-saved {} samples", name, all.size());
            } catch (IOException e) {
                LOGGER.warn("[Zstd] {} auto-save failed", name, e);
            }
        }
    }

    private void addSample(byte[] packetBytes) {
        if (!shouldKeep(packetBytes)) return;
        if (sampleBytes >= sampleSize) return;

        synchronized (this) {
            sampleRing.add(packetBytes);
            sampleBytes += packetBytes.length;
        }
        tryTrain();
    }

    public void tryTrain() {
        synchronized (this) {
            if (sampleRing.size() < minSamples) return;
            long now = System.currentTimeMillis();
            if (now - lastTrainTime < trainCooldownMs) return;
            boolean full = sampleRing.size() >= maxSamples;
            boolean timedOut = (now - lastTrainTime) >= fallbackTimeoutMs;
            if (!full && !timedOut) return;
            lastTrainTime = now;
        }

        byte[][] samples;
        synchronized (this) {
            samples = sampleRing.toArray(new byte[0][]);
        }

        LOGGER.info("[Zstd] {} starting training with {} samples ({} bytes)",
                name, samples.length, sampleBytes);

        byte[] newDict = train(samples);
        if (newDict == null || newDict.length == 0) {
            LOGGER.warn("[Zstd] {} training produced empty dict", name);
            return;
        }

        Checksum crc = new CRC32();
        crc.update(newDict, 0, newDict.length);
        long dictId = crc.getValue();

        if (currentDict != null && currentDictId == dictId) {
            LOGGER.info("[Zstd] {} dict unchanged, skipping", name);
            return;
        }

        double improvement = evaluate(samples, newDict);
        if (improvement < adoptRatioImprovement) {
            LOGGER.info("[Zstd] {} dict insufficient improvement: {}%", name,
                    String.format("%.1f", improvement * 100));
            return;
        }

        synchronized (this) {
            currentDict = newDict;
            currentDictId = dictId;
        }

        persist();
        LOGGER.info("[Zstd] {} new dict adopted: id={} size={} improvement={}%",
                name, dictId, newDict.length, String.format("%.1f", improvement * 100));
    }

    private byte[] train(byte[][] samples) {
        try {
            ZstdDictTrainer trainer = new ZstdDictTrainer(sampleSize, dictSize);
            for (byte[] s : samples) {
                if (!trainer.addSample(s)) break;
            }
            return trainer.trainSamples();
        } catch (Exception e) {
            LOGGER.error("[Zstd] {} training failed", name, e);
            return null;
        }
    }

    private double evaluate(byte[][] samples, byte[] newDict) {
        try {
            com.github.luben.zstd.ZstdCompressCtx oldCtx = new com.github.luben.zstd.ZstdCompressCtx();
            oldCtx.setLevel(9);
            if (currentDict != null && currentDict.length > 0) {
                oldCtx.loadDict(new com.github.luben.zstd.ZstdDictCompress(currentDict, 9));
            }

            com.github.luben.zstd.ZstdCompressCtx newCtx = new com.github.luben.zstd.ZstdCompressCtx();
            newCtx.setLevel(9);
            if (newDict.length > 0) {
                newCtx.loadDict(new com.github.luben.zstd.ZstdDictCompress(newDict, 9));
            }

            long oldTotal = 0, newTotal = 0;
            int testCount = Math.min(samples.length, 500);
            for (int i = 0; i < testCount; i++) {
                oldTotal += oldCtx.compress(samples[i]).length;
                newTotal += newCtx.compress(samples[i]).length;
            }
            oldCtx.close();
            newCtx.close();

            if (oldTotal == 0) return 0;
            return 1.0 - (double) newTotal / oldTotal;
        } catch (Exception e) {
            LOGGER.warn("[Zstd] {} evaluation failed", name, e);
            return 0;
        }
    }

    private boolean shouldKeep(byte[] packetBytes) {
        int idLen = 0;
        while (idLen < packetBytes.length && idLen < 5) {
            byte b = packetBytes[idLen];
            idLen++;
            if ((b & 0x80) == 0) break;
        }
        int payloadLen = packetBytes.length - idLen;
        if (payloadLen <= 2) return false;
        if (payloadLen == 8) return false;
        return payloadLen >= pruneMinPayload;
    }

    private void persist() {
        if (currentDict == null || currentDict.length == 0) return;
        try {
            Files.write(dictDir.resolve(name + "_dict_" + currentDictId + ".bin"), currentDict);
            List<byte[]> history = loadHistory();
            history.addAll(sampleRing);
            while (history.size() > maxHistorySamples) history.remove(0);
            writeSamples(history);
            LOGGER.info("[Zstd] {} persisted dict={} samples={}", name, currentDictId, history.size());
        } catch (IOException e) {
            LOGGER.error("[Zstd] {} persist failed", name, e);
        }
    }

    private void loadFromDisk() {
        try {
            Path[] files = Files.list(dictDir).filter(p -> p.getFileName().toString()
                    .startsWith(name + "_dict_")).sorted().toArray(Path[]::new);
            if (files.length > 0) {
                Path latest = files[files.length - 1];
                currentDict = Files.readAllBytes(latest);
                Checksum crc = new CRC32();
                crc.update(currentDict, 0, currentDict.length);
                currentDictId = crc.getValue();
                LOGGER.info("[Zstd] {} loaded dict from disk: id={} size={}", name, currentDictId, currentDict.length);
            }

            List<byte[]> history = loadHistory();
            if (!history.isEmpty()) {
                synchronized (this) {
                    sampleRing.addAll(history);
                    for (byte[] s : history) sampleBytes += s.length;
                }
                LOGGER.info("[Zstd] {} loaded {} history samples from disk", name, history.size());
            }
        } catch (IOException e) {
            LOGGER.warn("[Zstd] {} failed to load from disk", name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<byte[]> loadHistory() throws IOException {
        Path samplesFile = dictDir.resolve(name + "_samples.dat");
        if (!Files.exists(samplesFile)) return new ArrayList<>();
        byte[] data = Files.readAllBytes(samplesFile);
        List<byte[]> result = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.remaining() >= 4) {
            int len = buf.getInt();
            if (len <= 0 || len > buf.remaining()) break;
            byte[] sample = new byte[len];
            buf.get(sample);
            result.add(sample);
        }
        return result;
    }

    private void writeSamples(List<byte[]> samples) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(samples.stream().mapToInt(s -> s.length + 4).sum());
        for (byte[] s : samples) {
            buf.putInt(s.length);
            buf.put(s);
        }
        Files.write(dictDir.resolve(name + "_samples.dat"), buf.array());
    }
}

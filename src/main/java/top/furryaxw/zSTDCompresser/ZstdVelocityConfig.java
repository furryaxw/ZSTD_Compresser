package top.furryaxw.zSTDCompresser;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ZstdVelocityConfig {

    public static volatile ZstdVelocityConfig INSTANCE = new ZstdVelocityConfig(Map.of());
    private static Path lastConfigPath;

    public final int level;
    public final int windowLog;
    public final boolean allowBatch;
    public final int batchMaxBytes;
    public final int flushIntervalMs;
    public final int statsIntervalSec;
    public final boolean statsEnabled;
    public final boolean debug;

    public final int trainerMaxSamples;
    public final int trainerMinSamples;
    public final long trainerCooldownMs;
    public final long trainerFallbackTimeoutMs;
    public final int trainerDictMaxBytes;
    public final int trainerSampleTargetBytes;
    public final int trainerMaxHistorySamples;
    public final double trainerAdoptionThreshold;
    public final int trainerPruneMinPayload;

    private ZstdVelocityConfig(Map<String, Object> map) {
        Map<String, Object> c = getMap(map, "compression");
        this.level = getInt(c, "level", 9);
        this.windowLog = getInt(c, "window_log", 25);
        this.allowBatch = getBool(c, "allow_batch", true);
        this.batchMaxBytes = getInt(c, "batch_max_bytes", 65536);
        this.flushIntervalMs = getInt(c, "flush_interval_ms", 10);

        Map<String, Object> t = getMap(map, "trainer");
        this.trainerMaxSamples = getInt(t, "max_samples", 10000);
        this.trainerMinSamples = getInt(t, "min_samples", 2000);
        this.trainerCooldownMs = getLong(t, "cooldown_ms", 300000);
        this.trainerFallbackTimeoutMs = getLong(t, "fallback_timeout_ms", 600000);
        this.trainerDictMaxBytes = getInt(t, "dict_max_bytes", 131072);
        this.trainerSampleTargetBytes = getInt(t, "sample_target_bytes", 1048576);
        this.trainerMaxHistorySamples = getInt(t, "max_history_samples", 50000);
        this.trainerAdoptionThreshold = getDouble(t, "adoption_threshold", 0.03);
        this.trainerPruneMinPayload = getInt(t, "prune_min_payload", 16);

        Map<String, Object> l = getMap(map, "logging");
        this.statsEnabled = getBool(l, "stats_enabled");
        this.statsIntervalSec = getInt(l, "stats_interval_sec", 10);
        this.debug = getBool(l, "debug");
    }

    public static void load(Path configPath) {
        lastConfigPath = configPath;
        ZstdVelocityConfig cfg;
        try {
            Map<String, Object> existing = Map.of();
            if (Files.exists(configPath)) {
                try (Reader r = Files.newBufferedReader(configPath)) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> raw = yaml.load(r);
                    if (raw != null) existing = raw;
                }
            }
            Map<String, Object> defaults = buildDefaults();
            Map<String, Object> merged = deepMerge(defaults, existing);
            cfg = new ZstdVelocityConfig(merged);
            writeConfig(configPath, merged);
        } catch (Exception e) {
            System.err.println("[Zstd] Failed to load config: " + e);
            cfg = new ZstdVelocityConfig(Map.of());
        }
        INSTANCE = cfg;
    }

    public static void reload() {
        if (lastConfigPath != null) load(lastConfigPath);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(Map<String, Object> d, Map<String, Object> e) {
        Map<String, Object> r = new LinkedHashMap<>(d);
        for (Map.Entry<String, Object> entry : e.entrySet()) {
            if (entry.getValue() instanceof Map && r.get(entry.getKey()) instanceof Map) {
                r.put(entry.getKey(), deepMerge(
                        (Map<String, Object>) r.get(entry.getKey()),
                        (Map<String, Object>) entry.getValue()));
            } else {
                r.put(entry.getKey(), entry.getValue());
            }
        }
        return r;
    }

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("level", 9);
        c.put("window_log", 25);
        c.put("allow_batch", true);
        c.put("batch_max_bytes", 65536);
        c.put("flush_interval_ms", 10);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("max_samples", 10000);
        t.put("min_samples", 2000);
        t.put("cooldown_ms", 300000);
        t.put("fallback_timeout_ms", 600000);
        t.put("dict_max_bytes", 131072);
        t.put("sample_target_bytes", 1048576);
        t.put("max_history_samples", 50000);
        t.put("adoption_threshold", 0.03);
        t.put("prune_min_payload", 16);

        Map<String, Object> l = new LinkedHashMap<>();
        l.put("stats_enabled", false);
        l.put("stats_interval_sec", 10);
        l.put("debug", false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compression", c);
        root.put("trainer", t);
        root.put("logging", l);
        return root;
    }

    private static void writeConfig(Path configPath, Map<String, Object> map) {
        try {
            Files.createDirectories(configPath.getParent());
            Map<String, Object> c = getMap(map, "compression");
            Map<String, Object> t = getMap(map, "trainer");
            Map<String, Object> l = getMap(map, "logging");
            String yaml =
                    "# Zstd Compresser Velocity Plugin Configuration\n"
                            + "# This file is auto-generated. Edit and restart to apply changes.\n"
                            + "# New fields from plugin updates are automatically merged with defaults.\n"
                            + "\n"
                            + "# ── Compression ──\n"
                            +                     "# level: Zstd compression level (1-22). Higher = better compression but slower.\n"
                            + "# window_log: Sliding window size as 2^N bytes (25 = 32MB).\n"
                            + "# allow_batch: Accumulate small packets into batches before compression.\n"
                            + "# batch_max_bytes: Max accumulator size before forced flush.\n"
                            + "# flush_interval_ms: Max time window for packet batching (ms).\n"
                            + "compression:\n"
                            + "  level: " + getInt(c, "level", 9) + "\n"
                            + "  window_log: " + getInt(c, "window_log", 25) + "\n"
                            + "  allow_batch: " + getBool(c, "allow_batch", true) + "\n"
                            + "  batch_max_bytes: " + getInt(c, "batch_max_bytes", 65536) + "\n"
                            + "  flush_interval_ms: " + getInt(c, "flush_interval_ms", 10) + "\n"
                            + "\n"
                            + "# ── Trainer ──\n"
                            + "# max_samples: Sample ring buffer capacity. Triggers training when full.\n"
                            + "# min_samples: Minimum samples required for training (used with time fallback).\n"
                            + "# cooldown_ms: Minimum interval between training runs (ms).\n"
                            + "# fallback_timeout_ms: Force training after this duration if >= min_samples (ms).\n"
                            + "# dict_max_bytes: Maximum dictionary size in bytes (default 128KB).\n"
                            + "# sample_target_bytes: Target sample data size for Zstd trainer.\n"
                            + "# max_history_samples: Max persisted historical samples across restarts.\n"
                            + "# adoption_threshold: Minimum compression ratio improvement (0.03 = 3%) to adopt.\n"
                            + "# prune_min_payload: Drop packets with payload smaller than this (bytes).\n"
                            + "trainer:\n"
                            + "  max_samples: " + getInt(t, "max_samples", 10000) + "\n"
                            + "  min_samples: " + getInt(t, "min_samples", 2000) + "\n"
                            + "  cooldown_ms: " + getLong(t, "cooldown_ms", 300000L) + "\n"
                            + "  fallback_timeout_ms: " + getLong(t, "fallback_timeout_ms", 600000L) + "\n"
                            + "  dict_max_bytes: " + getInt(t, "dict_max_bytes", 131072) + "\n"
                            + "  sample_target_bytes: " + getInt(t, "sample_target_bytes", 1048576) + "\n"
                            + "  max_history_samples: " + getInt(t, "max_history_samples", 50000) + "\n"
                            + "  adoption_threshold: " + getDouble(t, "adoption_threshold", 0.03) + "\n"
                            + "  prune_min_payload: " + getInt(t, "prune_min_payload", 16) + "\n"
                            + "\n"
                            + "# ── Logging ──\n"
                            + "# stats_enabled: Enable per-second TX/RX compression statistics.\n"
                            + "# stats_interval_sec: Statistics output interval in seconds.\n"
                            + "# debug: Enable verbose debug logging.\n"
                            + "logging:\n"
                            + "  stats_enabled: " + getBool(l, "stats_enabled") + "\n"
                            + "  stats_interval_sec: " + getInt(l, "stats_interval_sec", 10) + "\n"
                            + "  debug: " + getBool(l, "debug") + "\n";
            Files.writeString(configPath, yaml);
        } catch (IOException e) {
            System.err.println("[Zstd] Failed to write config: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> parent, String key) {
        Object o = parent.get(key);
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object o = map.get(key);
        if (o instanceof Number) return ((Number) o).intValue();
        return def;
    }

    private static long getLong(Map<String, Object> map, String key, long def) {
        Object o = map.get(key);
        if (o instanceof Number) return ((Number) o).longValue();
        return def;
    }

    private static double getDouble(Map<String, Object> map, String key, double def) {
        Object o = map.get(key);
        if (o instanceof Number) return ((Number) o).doubleValue();
        return def;
    }

    private static boolean getBool(Map<String, Object> map, String key) {
        Object o = map.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        return false;
    }

    private static boolean getBool(Map<String, Object> map, String key, boolean def) {
        Object o = map.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        return def;
    }
}

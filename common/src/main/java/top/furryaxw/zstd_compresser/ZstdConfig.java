package top.furryaxw.zstd_compresser;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ZstdConfig {

    public static volatile ZstdConfig INSTANCE = new ZstdConfig(Map.of());

    public final int level;
    public final int windowLog;
    public final int batchMaxBytes;
    public final int flushIntervalMs;
    public final int statsIntervalSec;
    public final boolean statsEnabled;
    public final boolean debug;
    public final boolean hudEnabled;

    public static volatile boolean hudEnabledRuntime;

    private ZstdConfig(Map<String, Object> map) {
        Map<String, Object> c = getMap(map, "compression");
        this.level = getInt(c, "level", 9);
        this.windowLog = getInt(c, "window_log", 25);
        this.batchMaxBytes = getInt(c, "batch_max_bytes", 65536);
        this.flushIntervalMs = getInt(c, "flush_interval_ms", 10);

        Map<String, Object> l = getMap(map, "logging");
        this.statsEnabled = getBool(l, "stats_enabled");
        this.statsIntervalSec = getInt(l, "stats_interval_sec", 10);
        this.debug = getBool(l, "debug");

        Map<String, Object> d = getMap(map, "display");
        this.hudEnabled = getBool(d, "hud_enabled");
        hudEnabledRuntime = this.hudEnabled;
    }

    public static void load(Path configPath) {
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
            ZstdConfig cfg = new ZstdConfig(merged);
            writeConfig(configPath, merged);
            INSTANCE = cfg;
        } catch (Exception e) {
            Zstd_compresser.LOGGER.warn("[Zstd] Failed to load config, using defaults", e);
            INSTANCE = new ZstdConfig(Map.of());
        }
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
        c.put("batch_max_bytes", 65536);
        c.put("flush_interval_ms", 10);

        Map<String, Object> l = new LinkedHashMap<>();
        l.put("stats_enabled", false);
        l.put("stats_interval_sec", 10);
        l.put("debug", false);

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("hud_enabled", false);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("compression", c);
        root.put("logging", l);
        root.put("display", d);
        return root;
    }

    private static void writeConfig(Path configPath, Map<String, Object> map) {
        try {
            Files.createDirectories(configPath.getParent());
            Map<String, Object> c = getMap(map, "compression");
            Map<String, Object> l = getMap(map, "logging");
            Map<String, Object> d = getMap(map, "display");
            String yaml =
                    "# Zstd Compresser Configuration\n"
                            + "# This file is auto-generated. Edit and restart to apply changes.\n"
                            + "# New fields from mod updates are automatically merged with defaults.\n"
                            + "\n"
                            + "# ── Compression ──\n"
                            + "# level: Zstd compression level (1-22). Higher = better compression but slower.\n"
                            + "# window_log: Sliding window size as 2^N bytes (25 = 32MB).\n"
                            + "# batch_max_bytes: Max batch accumulator size before forced flush.\n"
                            + "# flush_interval_ms: Max time window for packet batching.\n"
                            + "compression:\n"
                            + "  level: " + getInt(c, "level", 9) + "\n"
                            + "  window_log: " + getInt(c, "window_log", 25) + "\n"
                            + "  batch_max_bytes: " + getInt(c, "batch_max_bytes", 65536) + "\n"
                            + "  flush_interval_ms: " + getInt(c, "flush_interval_ms", 10) + "\n"
                            + "\n"
                            + "# ── Logging ──\n"
                            + "# stats_enabled: Enable per-second TX/RX compression statistics.\n"
                            + "# stats_interval_sec: Statistics output interval in seconds.\n"
                            + "# debug: Enable verbose debug logging (packet-level).\n"
                            + "logging:\n"
                            + "  stats_enabled: " + getBool(l, "stats_enabled") + "\n"
                            + "  stats_interval_sec: " + getInt(l, "stats_interval_sec", 10) + "\n"
                            + "  debug: " + getBool(l, "debug") + "\n"
                            + "\n"
                            + "# ── Display ──\n"
                            + "# hud_enabled: Show TX/RX compression HUD (F8 to toggle at runtime).\n"
                            + "display:\n"
                            + "  hud_enabled: " + getBool(d, "hud_enabled") + "\n";
            Files.writeString(configPath, yaml);
        } catch (IOException e) {
            Zstd_compresser.LOGGER.warn("[Zstd] Failed to write config", e);
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

    private static boolean getBool(Map<String, Object> map, String key) {
        Object o = map.get(key);
        if (o instanceof Boolean) return (Boolean) o;
        return false;
    }
}

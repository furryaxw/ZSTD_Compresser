package top.furryaxw.zstd_compresser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Zstd_compresser {
    public static final String MOD_ID = "zstd_compresser";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void init() {
        Path configPath = Paths.get("config", MOD_ID + ".yml");
        ZstdConfig.load(configPath);
    }
}

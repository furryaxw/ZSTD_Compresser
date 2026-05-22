package top.furryaxw.zstd_compresser.fabric;

import net.fabricmc.api.ModInitializer;
import top.furryaxw.zstd_compresser.Zstd_compresser;

public final class Zstd_compresserFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        Zstd_compresser.init();
    }
}

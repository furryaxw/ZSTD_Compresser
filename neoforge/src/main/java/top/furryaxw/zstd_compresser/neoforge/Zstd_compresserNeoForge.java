package top.furryaxw.zstd_compresser.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import top.furryaxw.zstd_compresser.ZstdKeyMappings;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mod(Zstd_compresser.MOD_ID)
public final class Zstd_compresserNeoForge {
    public Zstd_compresserNeoForge(IEventBus modEventBus) {
        Zstd_compresser.init();
        modEventBus.addListener(this::registerKeyMappings);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ZstdKeyMappings.TOGGLE_HUD);
    }
}

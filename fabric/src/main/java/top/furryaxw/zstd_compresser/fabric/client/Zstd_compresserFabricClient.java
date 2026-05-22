package top.furryaxw.zstd_compresser.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import top.furryaxw.zstd_compresser.ZstdKeyMappings;

public final class Zstd_compresserFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(ZstdKeyMappings.TOGGLE_HUD);
    }
}

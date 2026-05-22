package top.furryaxw.zstd_compresser.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdConfig;
import top.furryaxw.zstd_compresser.ZstdKeyMappings;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {

    @Inject(method = "keyPress", at = @At("HEAD"))
    private void onKeyPress(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (ZstdKeyMappings.TOGGLE_HUD.matches(key, scancode) && action == 1) {
            ZstdConfig.hudEnabledRuntime = !ZstdConfig.hudEnabledRuntime;
        }
    }
}

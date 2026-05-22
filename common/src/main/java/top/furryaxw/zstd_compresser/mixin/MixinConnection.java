package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(Connection.class)
public class MixinConnection {

    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("HEAD"))
    private void onSetupCompression(int threshold, boolean validateDecompression, CallbackInfo ci) {
        if (channel == null) return;

        Boolean enabled = channel.attr(ZstdChannelManager.ZSTD_ENABLED).get();
        Zstd_compresser.LOGGER.debug("[Zstd] setupCompression(threshold={}) zstdEnabled={}", threshold, enabled);
        if (!Boolean.TRUE.equals(enabled)) return;
        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) {
            mgr = new ZstdChannelManager();
            channel.attr(ZstdChannelManager.KEY).set(mgr);
        }
        mgr.setExpectedDictId(0);
    }
}

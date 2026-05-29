package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.ZstdConfig;
import top.furryaxw.zstd_compresser.ZstdStatsData;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(ClientPacketListener.class)
public class MixinClientPacketListenerImpl {

    @Final
    @Shadow
    private Connection connection;

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void onLoginSuccess(ClientboundLoginPacket packet, CallbackInfo ci) {
        if (connection == null) return;
        Channel channel = ((ConnectionAccessor) connection).getChannel();
        if (channel == null) return;
        zstd_compresser$tryActivateBatch(channel);
    }

    @Unique
    private static void zstd_compresser$tryActivateBatch(Channel channel) {
        ZstdConfig cfg = ZstdConfig.INSTANCE;
        if (!cfg.allowBatch) return;
        channel.eventLoop().execute(() -> {
            ZstdChannelManager.TransportMode current = channel.attr(ZstdChannelManager.ZSTD_MODE).get();
            if (current == ZstdChannelManager.TransportMode.BATCH) return;
            channel.attr(ZstdChannelManager.ZSTD_MODE).set(ZstdChannelManager.TransportMode.BATCH);
            ZstdStatsData.batchActive = true;
            Zstd_compresser.LOGGER.debug("[Zstd] PLAY phase — switching encoder to BATCH mode");
        });
    }
}

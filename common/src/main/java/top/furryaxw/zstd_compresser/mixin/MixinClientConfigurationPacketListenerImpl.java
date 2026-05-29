package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.ZstdConfig;
import top.furryaxw.zstd_compresser.ZstdStatsData;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public class MixinClientConfigurationPacketListenerImpl {

    @Inject(
            method = "handleConfigurationFinished",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onConfigurationFinished(ClientboundFinishConfigurationPacket packet, CallbackInfo ci) {
        Connection connection = ((ClientCommonAccessor) this).getConnection();
        if (connection == null) return;
        Channel channel = ((ConnectionAccessor) connection).getChannel();

        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        ZstdChannelManager.TransportState state = channel.attr(ZstdChannelManager.ZSTD_STATE).get();

        Zstd_compresser.LOGGER.debug("[Zstd] handleConfigurationFinished mgr={} state={}",
                mgr != null, state);

        if (mgr == null) return;
        if (state != ZstdChannelManager.TransportState.ZSTD_ACTIVE) return;

        long expectedDictId = mgr.getExpectedDictId();
        if (expectedDictId != 0) {
            ci.cancel();
            mgr.setFinishConfigPending(true);
            Zstd_compresser.LOGGER.info("[Zstd] Held FinishConfiguration, waiting dict id={}", expectedDictId);
            return;
        }

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
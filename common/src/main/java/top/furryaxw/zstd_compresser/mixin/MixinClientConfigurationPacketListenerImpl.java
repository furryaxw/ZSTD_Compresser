package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdBatchEncoder;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.ZstdInboundDetector;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public class MixinClientConfigurationPacketListenerImpl {

    @Unique
    private static final String[] COMPRESS_NAMES = {"compress", "compression-encoder"};

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
        Boolean enabled = channel.attr(ZstdChannelManager.ZSTD_ENABLED).get();

        Zstd_compresser.LOGGER.debug("[Zstd] handleConfigurationFinished mgr={} zstdEnabled={}",
                mgr != null, enabled);

        if (mgr == null) return;
        if (!Boolean.TRUE.equals(enabled)) return;

        long expectedDictId = mgr.getExpectedDictId();
        if (expectedDictId != 0) {
            ci.cancel();
            mgr.setFinishConfigPending(true);
            Zstd_compresser.LOGGER.info("[Zstd] Held FinishConfiguration, waiting dict id={}", expectedDictId);
            return;
        }

        ChannelPipeline p = channel.pipeline();
        Zstd_compresser.LOGGER.debug("[Zstd] Replacing compression handlers. Before: {}", p.names());

        boolean replaced = false;
        for (String name : COMPRESS_NAMES) {
            if (p.get(name) != null) {
                p.replace(name, "zstd_encoder", new ZstdBatchEncoder());
                Zstd_compresser.LOGGER.debug("[Zstd] Replaced {} with zstd_encoder", name);
                replaced = true;
                break;
            }
        }
        if (!replaced && p.get("zstd_encoder") == null) {
            p.addBefore("prepender", "zstd_encoder", new ZstdBatchEncoder());
            Zstd_compresser.LOGGER.debug("[Zstd] Added zstd_encoder before prepender (no compress handler)");
        }

        zstd_compresser$injectInboundDetector(channel);
        Zstd_compresser.LOGGER.debug("[Zstd] After replace: {}", p.names());
    }

    @Unique
    private void zstd_compresser$injectInboundDetector(Channel channel) {
        if (channel.pipeline().get("zstd_inbound_spy") != null) return;
        try {
            if (channel.pipeline().get("splitter") != null) {
                channel.pipeline().addAfter("splitter", "zstd_inbound_spy", new ZstdInboundDetector());
            } else if (channel.pipeline().get("timeout") != null) {
                channel.pipeline().addAfter("timeout", "zstd_inbound_spy", new ZstdInboundDetector());
            } else {
                channel.pipeline().addFirst("zstd_inbound_spy", new ZstdInboundDetector());
            }
            Zstd_compresser.LOGGER.info("[Zstd] Inbound detector injected");
        } catch (Exception e) {
            Zstd_compresser.LOGGER.warn("[Zstd] Failed to inject inbound detector", e);
        }
    }
}

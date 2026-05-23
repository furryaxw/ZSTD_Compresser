package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdBatchEncoder;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.ZstdInboundDetector;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(Connection.class)
public class MixinConnection {

    @Shadow
    private Channel channel;

    @Unique
    private static final String[] COMPRESS_NAMES = {"compress", "compression-encoder"};

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

        zstd_compresser$injectZstdPipeline(channel);
    }

    @Unique
    private void zstd_compresser$injectZstdPipeline(Channel ch) {
        ChannelPipeline p = ch.pipeline();
        Zstd_compresser.LOGGER.debug("[Zstd] Injecting Zstd pipeline. Before: {}", p.names());

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
            Zstd_compresser.LOGGER.debug("[Zstd] Added zstd_encoder before prepender");
        }

        if (ch.pipeline().get("zstd_inbound_spy") != null) return;
        try {
            if (ch.pipeline().get("splitter") != null) {
                ch.pipeline().addAfter("splitter", "zstd_inbound_spy", new ZstdInboundDetector());
            } else if (ch.pipeline().get("timeout") != null) {
                ch.pipeline().addAfter("timeout", "zstd_inbound_spy", new ZstdInboundDetector());
            } else {
                ch.pipeline().addFirst("zstd_inbound_spy", new ZstdInboundDetector());
            }
            Zstd_compresser.LOGGER.info("[Zstd] Inbound detector injected");
        } catch (Exception e) {
            Zstd_compresser.LOGGER.warn("[Zstd] Failed to inject inbound detector", e);
        }
        Zstd_compresser.LOGGER.debug("[Zstd] After inject: {}", p.names());
    }
}

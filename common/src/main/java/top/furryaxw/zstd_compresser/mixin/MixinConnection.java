package top.furryaxw.zstd_compresser.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdBatchDecoder;
import top.furryaxw.zstd_compresser.ZstdBatchEncoder;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(Connection.class)
public class MixinConnection {

    @Shadow
    private Channel channel;

    @Inject(method = "setupCompression", at = @At("TAIL"))
    private void onSetupCompression(int threshold, boolean validateDecompression, CallbackInfo ci) {
        if (channel == null) return;

        ZstdChannelManager.TransportState state = channel.attr(ZstdChannelManager.ZSTD_STATE).get();
        if (state != ZstdChannelManager.TransportState.NEGOTIATING) return;

        Zstd_compresser.LOGGER.debug("[Zstd] setupCompression TAIL — activating zstd pipeline");

        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        boolean wasNew = false;
        if (mgr == null) {
            mgr = new ZstdChannelManager();
            channel.attr(ZstdChannelManager.KEY).set(mgr);
            wasNew = true;
        }
        if (wasNew) {
            final ZstdChannelManager existingMgr = mgr;
            channel.closeFuture().addListener(f -> existingMgr.close());
        }

        ChannelPipeline p = channel.pipeline();
        Zstd_compresser.LOGGER.debug("[Zstd] pipeline: {}", p.names());

        if (p.get("compress") != null && p.get("zstd_encoder") != null) {
            p.remove("compress");
            Zstd_compresser.LOGGER.debug("[Zstd] Removed stale vanilla compress handler");
        }

        if (p.get("zstd_encoder") == null) {
            if (p.get("compress") != null) {
                p.replace("compress", "zstd_encoder", new ZstdBatchEncoder());
            } else if (p.get("compression-encoder") != null) {
                p.replace("compression-encoder", "zstd_encoder", new ZstdBatchEncoder());
            } else if (p.get("prepender") != null) {
                p.addBefore("prepender", "zstd_encoder", new ZstdBatchEncoder());
            } else {
                p.addLast("zstd_encoder", new ZstdBatchEncoder());
            }
            Zstd_compresser.LOGGER.debug("[Zstd] zstd_encoder installed");
        }

        if (p.get("zstd_decoder") == null) {
            if (p.get("decompress") != null) {
                p.replace("decompress", "zstd_decoder", new ZstdBatchDecoder());
            } else if (p.get("compression-decoder") != null) {
                p.replace("compression-decoder", "zstd_decoder", new ZstdBatchDecoder());
            } else if (p.get("decoder") != null) {
                p.addBefore("decoder", "zstd_decoder", new ZstdBatchDecoder());
            }
            Zstd_compresser.LOGGER.debug("[Zstd] zstd_decoder installed");
        }

        channel.attr(ZstdChannelManager.ZSTD_STATE).set(ZstdChannelManager.TransportState.ZSTD_ACTIVE);
        channel.attr(ZstdChannelManager.ZSTD_MODE).set(ZstdChannelManager.TransportMode.PASSTHROUGH);
        Zstd_compresser.LOGGER.debug("[Zstd] Transport state -> ZSTD_ACTIVE (mode=PASSTHROUGH)");
        Zstd_compresser.LOGGER.debug("[Zstd] Pipeline after: {}", p.names());
    }
}
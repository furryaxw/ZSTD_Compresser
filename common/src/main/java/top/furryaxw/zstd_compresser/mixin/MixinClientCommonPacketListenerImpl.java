package top.furryaxw.zstd_compresser.mixin;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.DictCache;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.Zstd_compresser;

import java.util.zip.CRC32;

@Mixin(ClientCommonPacketListenerImpl.class)
public class MixinClientCommonPacketListenerImpl {

    @Final
    @Shadow
    protected Connection connection;

    @Final
    @Shadow
    protected Minecraft minecraft;

    @Inject(
            method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
            at = @At("TAIL")
    )
    private void onCustomPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (connection == null) return;

        ResourceLocation id = packet.payload().type().id();
        if (!"zstd".equals(id.getNamespace()) || !"dict".equals(id.getPath())) {
            Zstd_compresser.LOGGER.debug("[Zstd] CustomPayload: {} (ignored)", id);
            return;
        }

        Zstd_compresser.LOGGER.info("[Zstd] Processing zstd:dict payload");

        Channel channel = ((ConnectionAccessor) connection).getChannel();
        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) return;

        try {
            FriendlyByteBuf serialized;
            if (minecraft != null && minecraft.level != null) {
                serialized = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        minecraft.level.registryAccess());
                ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(
                        (RegistryFriendlyByteBuf) serialized, packet);
            } else {
                serialized = new FriendlyByteBuf(Unpooled.buffer());
                ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC.encode(serialized, packet);
            }
            serialized.readResourceLocation();
            long dictId = serialized.readLong();
            byte dictType = serialized.readByte();
            int expectedCrc = serialized.readInt();
            int dictLength = serialized.readInt();
            byte[] dictData = new byte[dictLength];
            serialized.readBytes(dictData);
            serialized.release();

            CRC32 crc = new CRC32();
            crc.update(dictData);
            int actualCrc = (int) crc.getValue();
            if (actualCrc != expectedCrc) {
                Zstd_compresser.LOGGER.error("[Zstd] Dict CRC mismatch: expected={}, actual={}", expectedCrc, actualCrc);
                return;
            }

            DictCache.put(dictId, dictData);
            if (dictType == 0) {
                mgr.loadDecoderDict(dictData, dictId);
                Zstd_compresser.LOGGER.info("[Zstd] Decoder dict loaded (compressCtx), id={}, size={}", dictId, dictLength);
            } else {
                mgr.loadEncoderDict(dictData, dictId);
                Zstd_compresser.LOGGER.info("[Zstd] Encoder dict loaded (decompressCtx), id={}, size={}", dictId, dictLength);
            }

            if (mgr.isFinishConfigPending()) {
                connection.send(ServerboundFinishConfigurationPacket.INSTANCE);
                mgr.setFinishConfigPending(false);
                Zstd_compresser.LOGGER.info("[Zstd] Released FinishConfiguration");
            }
        } catch (Exception e) {
            Zstd_compresser.LOGGER.error("[Zstd] Failed to process dictionary", e);
        }
    }
}

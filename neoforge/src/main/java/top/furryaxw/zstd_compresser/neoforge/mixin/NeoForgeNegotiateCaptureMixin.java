package top.furryaxw.zstd_compresser.neoforge.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.NeoForgeNegotiateHolder;

@Mixin(ClientboundCustomQueryPacket.class)
public class NeoForgeNegotiateCaptureMixin {

    @Unique
    private static final Logger zstd_compresser$LOGGER = LoggerFactory.getLogger("zstd_compresser");

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"))
    private static void onConstruct(FriendlyByteBuf buf, CallbackInfo ci) {
        int saved = buf.readerIndex();
        int readable = buf.readableBytes();
        if (readable < 3) {
            buf.readerIndex(saved);
            return;
        }
        int txId = zstd_compresser$readVarInt(buf);
        ResourceLocation id = zstd_compresser$readResourceLocation(buf);
        if ("zstd".equals(id.getNamespace()) && "negotiate".equals(id.getPath())) {
            byte[] data = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), data);
            NeoForgeNegotiateHolder.CAPTURED_DATA.set(data);
            zstd_compresser$LOGGER.debug("[NeoForge] captured negotiate raw {} bytes", data.length);
        }
        buf.readerIndex(saved);
    }

    @Unique
    private static int zstd_compresser$readVarInt(FriendlyByteBuf buf) {
        int result = 0, shift = 0;
        while (true) {
            byte b = buf.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift > 35) throw new RuntimeException("VarInt too big");
        }
    }

    @Unique
    private static ResourceLocation zstd_compresser$readResourceLocation(FriendlyByteBuf buf) {
        return buf.readResourceLocation();
    }
}

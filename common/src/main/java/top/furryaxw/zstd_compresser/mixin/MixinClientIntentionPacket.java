package top.furryaxw.zstd_compresser.mixin;

import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientIntentionPacket.class)
public class MixinClientIntentionPacket {

    @ModifyArg(
            method = "write",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;writeUtf(Ljava/lang/String;)Lnet/minecraft/network/FriendlyByteBuf;"
            )
    )
    private String zstd$appendMarker(String host) {
        if (!host.contains("\0ZSTD\0")) {
            return host + "\0ZSTD\0";
        }
        return host;
    }
}

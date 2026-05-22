package top.furryaxw.zstd_compresser.mixin;

import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ClientIntentionPacket.class)
public class MixinClientIntentionPacket {

    @ModifyVariable(
            method = "<init>(ILjava/lang/String;ILnet/minecraft/network/protocol/handshake/ClientIntent;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static String appendZstdMarker(String hostName) {
        return hostName + "\0ZSTD\0";
    }
}

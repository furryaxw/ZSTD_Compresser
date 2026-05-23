package top.furryaxw.zstd_compresser.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundCustomQueryPacket.class)
public interface ClientboundCustomQueryPacketAccessor {

    @Accessor("transactionId")
    int getTransactionId();

    @Accessor("identifier")
    ResourceLocation getIdentifier();

    @Accessor("data")
    FriendlyByteBuf getData();
}

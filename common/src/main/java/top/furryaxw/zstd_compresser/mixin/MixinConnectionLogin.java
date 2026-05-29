package top.furryaxw.zstd_compresser.mixin;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.DictCache;
import top.furryaxw.zstd_compresser.NeoForgeNegotiateHolder;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mixin(Connection.class)
public class MixinConnectionLogin {

    @Shadow
    private Channel channel;

    @Shadow
    public void send(Packet<?> packet) {
        throw new AssertionError();
    }

    @Inject(
            method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onChannelRead0(ChannelHandlerContext ctx, Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundCustomQueryPacket(int transactionId, CustomQueryPayload payload))) return;

        ResourceLocation id = payload.id();

        if (!"zstd".equals(id.getNamespace()) || !"negotiate".equals(id.getPath())) return;

        ci.cancel();

        FriendlyByteBuf temp = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(temp);

        if (temp.readableBytes() < 21) {
            byte[] raw = NeoForgeNegotiateHolder.CAPTURED_DATA.get();
            NeoForgeNegotiateHolder.CAPTURED_DATA.remove();
            if (raw == null) {
                raw = channel.attr(AttributeKey.<byte[]>valueOf("zstd:negotiate_raw")).getAndSet(null);
            }
            if (raw != null) {
                NeoForgeNegotiateHolder.CAPTURED_DATA.remove();
                temp.release();
                temp = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
            } else {
                Zstd_compresser.LOGGER.warn("[Zstd] negotiate payload too short: {} bytes", temp.readableBytes());
                return;
            }
        }

        int protocolVersion = temp.readInt();
        if (protocolVersion != ZstdChannelManager.PROTOCOL_VERSION) {
            Zstd_compresser.LOGGER.error("[Zstd] Protocol version mismatch: server={}, client={}",
                    protocolVersion, ZstdChannelManager.PROTOCOL_VERSION);
            return;
        }

        long encoderDictId = temp.readLong();
        long decoderDictId = temp.readLong();
        byte flags = temp.readByte();

        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) {
            mgr = new ZstdChannelManager();
            channel.attr(ZstdChannelManager.KEY).set(mgr);
            final ZstdChannelManager finalMgr = mgr;
            channel.closeFuture().addListener(f -> finalMgr.close());
        }
        channel.attr(ZstdChannelManager.ZSTD_STATE).set(ZstdChannelManager.TransportState.NEGOTIATING);

        byte encoderStatus = zstd_compresser$resolveDictEmbedded(mgr, encoderDictId, temp, flags, true);
        byte decoderStatus = zstd_compresser$resolveDictEmbedded(mgr, decoderDictId, temp, flags, false);

        CustomQueryAnswerPayload answer = buf -> {
            buf.writeByte(encoderStatus);
            buf.writeByte(decoderStatus);
        };
        ServerboundCustomQueryAnswerPacket response = new ServerboundCustomQueryAnswerPacket(transactionId, answer);
        send(response);

        Zstd_compresser.LOGGER.info("[Zstd] LoginPlugin negotiated: encId={} encStatus={} decId={} decStatus={}",
                encoderDictId, encoderStatus, decoderDictId, decoderStatus);
    }

    @Unique
    private byte zstd_compresser$resolveDictEmbedded(ZstdChannelManager mgr, long dictId, FriendlyByteBuf temp, byte flags, boolean isEncoder) {
        if (dictId == 0) return 2;

        int flagBit = isEncoder ? 1 : 2;
        boolean embedded = (flags & flagBit) != 0;

        if (DictCache.contains(dictId)) {
            byte[] cached = DictCache.get(dictId);
            if (isEncoder) mgr.loadEncoderDict(cached, dictId);
            else mgr.loadDecoderDict(cached, dictId);
            if (embedded) zstd_compresser$skipEmbeddedDict(temp);
            return 0;
        }

        if (embedded) {
            try {
                int crc = temp.readInt();
                int len = temp.readInt();
                byte[] data = new byte[len];
                temp.readBytes(data);

                java.util.zip.CRC32 crcCheck = new java.util.zip.CRC32();
                crcCheck.update(data);
                if ((int) crcCheck.getValue() != crc) {
                    Zstd_compresser.LOGGER.warn("[Zstd] Embedded dict CRC mismatch for id={}", dictId);
                    return 2;
                }
                DictCache.put(dictId, data);
                if (isEncoder) mgr.loadEncoderDict(data, dictId);
                else mgr.loadDecoderDict(data, dictId);
                return 0;
            } catch (Exception e) {
                Zstd_compresser.LOGGER.warn("[Zstd] Failed to read embedded dict id={}", dictId, e);
                return 2;
            }
        }

        return 2;
    }

    @Unique
    private void zstd_compresser$skipEmbeddedDict(FriendlyByteBuf temp) {
        try {
            temp.readInt();
            int len = temp.readInt();
            if (len > 0 && len < 1024 * 1024) temp.skipBytes(len);
        } catch (Exception ignored) {
        }
    }
}
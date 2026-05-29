package top.furryaxw.zstd_compresser.mixin;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.DictCache;
import top.furryaxw.zstd_compresser.ZstdChannelManager;
import top.furryaxw.zstd_compresser.Zstd_compresser;

import java.util.zip.CRC32;

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
        zstd_compresser$handleLoginNegotiate(packet, ci);
        zstd_compresser$handleGameDict(packet, ci);
    }

    @Unique
    private void zstd_compresser$handleLoginNegotiate(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundCustomQueryPacket query)) return;

        ResourceLocation id = ((ClientboundCustomQueryPacketAccessor) query).getIdentifier();

        if (!"zstd".equals(id.getNamespace()) || !"negotiate".equals(id.getPath())) return;

        ci.cancel();

        FriendlyByteBuf source = ((ClientboundCustomQueryPacketAccessor) query).getData();
        int len = source.readableBytes();
        byte[] raw = new byte[len];
        source.getBytes(source.readerIndex(), raw);
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));

        int protocolVersion = data.readInt();
        if (protocolVersion != ZstdChannelManager.PROTOCOL_VERSION) {
            Zstd_compresser.LOGGER.error("[Zstd] Protocol version mismatch: server={}, client={}",
                    protocolVersion, ZstdChannelManager.PROTOCOL_VERSION);
            return;
        }

        long encoderDictId = data.readLong();
        long decoderDictId = data.readLong();
        byte flags = data.readByte();

        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) {
            mgr = new ZstdChannelManager();
            channel.attr(ZstdChannelManager.KEY).set(mgr);
            final ZstdChannelManager finalMgr = mgr;
            channel.closeFuture().addListener(f -> finalMgr.close());
        }
        channel.attr(ZstdChannelManager.ZSTD_STATE).set(ZstdChannelManager.TransportState.NEGOTIATING);

        byte encoderStatus = zstd_compresser$resolveDictEmbedded(mgr, encoderDictId, data, flags, true);
        byte decoderStatus = zstd_compresser$resolveDictEmbedded(mgr, decoderDictId, data, flags, false);

        FriendlyByteBuf answerBuf = new FriendlyByteBuf(Unpooled.buffer());
        answerBuf.writeByte(encoderStatus);
        answerBuf.writeByte(decoderStatus);
        ServerboundCustomQueryPacket response = new ServerboundCustomQueryPacket(
                ((ClientboundCustomQueryPacketAccessor) query).getTransactionId(), answerBuf);
        send(response);

        Zstd_compresser.LOGGER.info("[Zstd] LoginPlugin negotiated: encId={} encStatus={} decId={} decStatus={}",
                encoderDictId, encoderStatus, decoderDictId, decoderStatus);
    }

    @Unique
    private void zstd_compresser$handleGameDict(Packet<?> packet, CallbackInfo ci) {
        if (channel == null) return;
        if (!(packet instanceof ClientboundCustomPayloadPacket custom)) return;

        ResourceLocation id = ((ClientboundCustomPayloadPacketAccessor) custom).getIdentifier();
        if (!"zstd".equals(id.getNamespace()) || !"dict".equals(id.getPath())) {
            return;
        }

        ci.cancel();

        Zstd_compresser.LOGGER.info("[Zstd] Processing zstd:dict payload");

        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) return;

        try {
            FriendlyByteBuf source = ((ClientboundCustomPayloadPacketAccessor) custom).getData();
            int len = source.readableBytes();
            if (len < 13) return;
            byte[] raw = new byte[len];
            source.getBytes(source.readerIndex(), raw);
            FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));

            long dictId = data.readLong();
            byte dictType = data.readByte();
            int expectedCrc = data.readInt();
            int dictLength = data.readInt();
            if (dictLength < 0 || dictLength > len - 13) {
                Zstd_compresser.LOGGER.error("[Zstd] Invalid dict length: {}", dictLength);
                return;
            }
            byte[] dictData = new byte[dictLength];
            data.readBytes(dictData);

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
                Zstd_compresser.LOGGER.info("[Zstd] Decoder dict loaded, id={}, size={}", dictId, dictLength);
            } else {
                mgr.loadEncoderDict(dictData, dictId);
                Zstd_compresser.LOGGER.info("[Zstd] Encoder dict loaded, id={}, size={}", dictId, dictLength);
            }
        } catch (Exception e) {
            Zstd_compresser.LOGGER.error("[Zstd] Failed to process dictionary", e);
        }
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

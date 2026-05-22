package top.furryaxw.zSTDCompresser;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class ZstdHijacker extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private boolean negotiationSent;
    private boolean firstWriteSeen;

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!firstWriteSeen) {
            firstWriteSeen = true;
            ctx.executor().execute(() -> sendNegotiate(ctx));
        }
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null && mgr.isReplaced()) {
            super.write(ctx, msg, promise);
            return;
        }

        String className = msg.getClass().getSimpleName();

        if (mgr != null && !mgr.isSetCompressionSeen() && "SetCompressionPacket".equals(className)) {
            mgr.setSetCompressionSeen(true);
            LOGGER.debug("[Zstd] SetCompression detected");
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null) {
            mgr.releaseHeld();
        }
        super.handlerRemoved(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null) {
            mgr.releaseHeld();
        }
        ctx.fireExceptionCaught(cause);
    }

    private void sendNegotiate(ChannelHandlerContext ctx) {
        if (negotiationSent) return;
        negotiationSent = true;

        try {
            ChannelHandlerContext writeCtx = ctx.pipeline().context("minecraft-encoder");
            if (writeCtx == null) {
                LOGGER.warn("[Zstd] minecraft-encoder not found, cannot send negotiate");
                return;
            }

            int txId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            String channel = "zstd:negotiate";

            long encId = ZstdSampleTrainer.getEncoder() != null
                    ? ZstdSampleTrainer.getEncoder().getCurrentDictId() : 0;
            long decId = ZstdSampleTrainer.getDecoder() != null
                    ? ZstdSampleTrainer.getDecoder().getCurrentDictId() : 0;

            ByteBuf buf = ctx.alloc().buffer();
            writeVarInt(buf, 0x04);
            writeVarInt(buf, txId);
            writeString(buf, channel);
            buf.writeLong(encId);
            buf.writeLong(decId);
            byte flags = 0;
            if (encId != 0) flags |= 1;
            if (decId != 0) flags |= 2;
            buf.writeByte(flags);

            writeDictBytes(buf, ZstdSampleTrainer.getEncoder(), encId);
            writeDictBytes(buf, ZstdSampleTrainer.getDecoder(), decId);

            writeCtx.writeAndFlush(buf);
            LOGGER.info("[Zstd] Sent zstd:negotiate txId={}", txId);
        } catch (Exception e) {
            LOGGER.error("[Zstd] Failed to send negotiate", e);
        }
    }

    private static void writeDictBytes(ByteBuf buf, ZstdSampleTrainer trainer, long dictId) {
        if (trainer == null || trainer.getCurrentDict() == null || dictId == 0) return;
        byte[] dict = trainer.getCurrentDict();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(dict);
        buf.writeInt((int) crc.getValue());
        buf.writeInt(dict.length);
        buf.writeBytes(dict);
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }

    private void sendDictIfAvailable(ChannelHandlerContext ctx) {
        ZstdSampleTrainer encoder = ZstdSampleTrainer.getEncoder();
        ZstdSampleTrainer decoder = ZstdSampleTrainer.getDecoder();
        if (encoder == null && decoder == null) return;

        ChannelHandlerContext writeCtx = ctx.pipeline().context("compression-encoder");
        if (writeCtx == null) writeCtx = ctx.pipeline().context("minecraft-encoder");
        if (writeCtx == null) return;

        if (encoder != null && encoder.getCurrentDict() != null) {
            sendDictPacket(writeCtx, encoder);
        }
        if (decoder != null && decoder.getCurrentDict() != null) {
            sendDictPacket(writeCtx, decoder);
        }
    }

    private void sendDictPacket(ChannelHandlerContext writeCtx, ZstdSampleTrainer trainer) {
        try {
            byte[] dict = trainer.getCurrentDict();
            long dictId = trainer.getCurrentDictId();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(dict);
            int crcVal = (int) crc.getValue();

            ByteBuf payload = writeCtx.alloc().buffer();
            writeVarInt(payload, 0x01);
            writeString(payload, "zstd:dict");
            payload.writeLong(dictId);
            payload.writeInt(crcVal);
            payload.writeInt(dict.length);
            payload.writeBytes(dict);

            int payloadLen = payload.readableBytes();
            ByteBuf frame = writeCtx.alloc().buffer(10 + 5 + payloadLen);
            writeVarInt(frame, ZstdChannelManager.varIntLength(payloadLen) + payloadLen);
            ZstdChannelManager.writeVarInt(frame, payloadLen);
            frame.writeBytes(payload);
            payload.release();

            writeCtx.writeAndFlush(frame);
            LOGGER.info("[Zstd] Sent {} dict: id={} size={}", trainer.getName(), dictId, dict.length);
        } catch (Exception e) {
            LOGGER.error("[Zstd] Failed to send dict {}", trainer.getName(), e);
        }
    }

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}

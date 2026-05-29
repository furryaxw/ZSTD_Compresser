package top.furryaxw.zSTDCompresser;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

public class ZstdHijacker extends ChannelDuplexHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private static final String COMPRESSION_DECODER = "compression-decoder";
    private static final String COMPRESSION_ENCODER = "compression-encoder";
    private static final String MINECRAFT_DECODER = "minecraft-decoder";
    private static final String MINECRAFT_ENCODER = "minecraft-encoder";
    private static final String FRAME_ENCODER = "frame-encoder";
    private static final String FRAME_DECODER = "frame-decoder";

    private boolean negotiationSent;
    private boolean pipelineActivated;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!negotiationSent) {
            negotiationSent = true;
            sendNegotiate(ctx);
        }

        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr != null && mgr.isReplaced()) {
            super.write(ctx, msg, promise);
            return;
        }

        String className = msg.getClass().getSimpleName();
        if (mgr != null && !pipelineActivated && "SetCompressionPacket".equals(className)) {
            pipelineActivated = true;
            LOGGER.debug("[Zstd] SetCompression detected — writing vanilla, deferring zstd activation");
            super.write(ctx, msg, promise);
            ctx.channel().eventLoop().execute(() -> {
                if (ctx.channel().isActive()) {
                    activateZstd(ctx);
                }
            });
            return;
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

    private void activateZstd(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null || mgr.isReplaced()) return;

        ChannelPipeline p = channel.pipeline();
        LOGGER.debug("[Zstd] VELOCITY activateZstd | channel={} remote={} active={} pipeline={}",
                channel.getClass().getSimpleName(),
                channel.remoteAddress(),
                channel.isActive(),
                p.names());

        if (p.get(MINECRAFT_DECODER) == null || p.get(MINECRAFT_ENCODER) == null) {
            LOGGER.warn("[Zstd] VELOCITY Cannot activate zstd — not a frontend Minecraft channel");
            return;
        }

        mgr.setReplaced(true);
        channel.attr(ZstdChannelManager.ZSTD_STATE).set(ZstdChannelManager.TransportState.ZSTD_ACTIVE);
        channel.attr(ZstdChannelManager.ZSTD_MODE).set(ZstdChannelManager.TransportMode.PASSTHROUGH);

        LOGGER.debug("[Zstd] VELOCITY Activating zstd pipeline. Before: {}", p.names());

        if (p.get(COMPRESSION_DECODER) != null) {
            p.replace(COMPRESSION_DECODER, "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("zstd_decoder") == null) {
            p.addBefore(MINECRAFT_DECODER, "zstd_decoder", new ZstdBatchDecoder());
        }

        if (p.get(COMPRESSION_ENCODER) != null) {
            p.remove(COMPRESSION_ENCODER);
        }
        if (p.get("zstd_encoder") == null) {
            if (p.get("cipher-encoder") != null) {
                p.addAfter("cipher-encoder", "zstd_encoder", new ZstdBatchEncoder());
            } else {
                p.addBefore(MINECRAFT_ENCODER, "zstd_encoder", new ZstdBatchEncoder());
            }
        }

        LOGGER.debug("[Zstd] VELOCITY Zstd transport activated. Pipeline: {}", p.names());
        LOGGER.debug("[Zstd] VELOCITY zstd_outbound_spy present: {}", p.get("zstd_outbound_spy") != null);

        mgr.releaseHeldAndSend(channel);
    }

    private void sendNegotiate(ChannelHandlerContext ctx) {
        try {
            ChannelHandlerContext writeCtx = ctx.pipeline().context("minecraft-encoder");
            if (writeCtx == null) {
                LOGGER.warn("[Zstd] minecraft-encoder not found, cannot send negotiate");
                return;
            }

            int txId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            String channelName = "zstd:negotiate";

            long encId = ZstdSampleTrainer.getEncoder() != null
                    ? ZstdSampleTrainer.getEncoder().getCurrentDictId() : 0;
            long decId = ZstdSampleTrainer.getDecoder() != null
                    ? ZstdSampleTrainer.getDecoder().getCurrentDictId() : 0;

            ByteBuf buf = ctx.alloc().buffer();
            try {
                writeVarInt(buf, 0x04);
                writeVarInt(buf, txId);
                writeString(buf, channelName);
                buf.writeInt(ZstdChannelManager.PROTOCOL_VERSION);
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
            } catch (Throwable t) {
                if (buf.refCnt() > 0) buf.release();
                throw t;
            }
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

    private static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }
}
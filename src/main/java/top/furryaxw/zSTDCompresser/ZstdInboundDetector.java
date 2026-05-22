package top.furryaxw.zSTDCompresser;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZstdInboundDetector extends ChannelInboundHandlerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("zstd_velocity");

    private static final int ZSTD_MAGIC = 0x28B52FFD;

    private static final String COMPRESSION_DECODER = "compression-decoder";
    private static final String COMPRESSION_ENCODER = "compression-encoder";
    private static final String MINECRAFT_DECODER = "minecraft-decoder";
    private static final String MINECRAFT_ENCODER = "minecraft-encoder";
    private static final String FRAME_ENCODER = "frame-encoder";

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr == null || mgr.isReplaced()) {
            super.channelRead(ctx, msg);
            return;
        }

        if (msg instanceof ByteBuf buf) {
            int len = buf.readableBytes();
            if (len >= 4) {
                int magic = buf.getInt(buf.readerIndex());
                if (magic == ZSTD_MAGIC) {
                    LOGGER.info("[Zstd] VELOCITY Inbound detector: Zstd Magic found! len={}", len);
                    buf.retain();
                    ctx.channel().eventLoop().execute(() -> replacePipeline(ctx, buf));
                    return;
                }
                if ((len & 0xFF) < 0x30) {
                    LOGGER.debug("[Zstd] VELOCITY Inbound detector: pass len={} firstBytes={}",
                            len, String.format("%08x", magic));
                }
            }
        }
        super.channelRead(ctx, msg);
    }

    private void replacePipeline(ChannelHandlerContext spyCtx, ByteBuf firstZstdFrame) {
        Channel channel = spyCtx.channel();
        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null || mgr.isReplaced()) {
            firstZstdFrame.release();
            return;
        }
        mgr.setReplaced(true);

        ChannelPipeline p = channel.pipeline();
        LOGGER.debug("[Zstd] VELOCITY Replacing pipeline. Before: {}", p.names());

        if (p.get(COMPRESSION_DECODER) != null) {
            p.replace(COMPRESSION_DECODER, "zstd_decoder", new ZstdBatchDecoder());
        } else if (p.get("zstd_decoder") == null) {
            p.addBefore(MINECRAFT_DECODER, "zstd_decoder", new ZstdBatchDecoder());
        }

        if (p.get(COMPRESSION_ENCODER) != null) {
            p.replace(COMPRESSION_ENCODER, "zstd_encoder", new ZstdBatchEncoder());
        } else if (p.get("zstd_encoder") == null) {
            p.addBefore(MINECRAFT_ENCODER, "zstd_encoder", new ZstdBatchEncoder());
            if (p.get(FRAME_ENCODER) != null) {
                p.remove(FRAME_ENCODER);
            }
        }

        LOGGER.debug("[Zstd] VELOCITY Pipeline replaced. After: {}", p.names());

        spyCtx.fireChannelRead(firstZstdFrame);

        mgr.releaseHeldAndSend(channel);

        try {
            if (p.get("zstd_outbound_spy") != null) p.remove("zstd_outbound_spy");
            if (p.get("zstd_inbound_spy") != null) p.remove("zstd_inbound_spy");
            LOGGER.info("[Zstd] VELOCITY Spies removed");
        } catch (Exception ignored) {
        }
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value & 0x7F);
    }
}

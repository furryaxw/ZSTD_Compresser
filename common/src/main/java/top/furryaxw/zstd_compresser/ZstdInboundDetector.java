package top.furryaxw.zstd_compresser;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class ZstdInboundDetector extends ChannelInboundHandlerAdapter {

    private static final int ZSTD_MAGIC = 0x28B52FFD;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ZstdChannelManager mgr = ctx.channel().attr(ZstdChannelManager.KEY).get();
        if (mgr == null || mgr.isDecoderInstalled()) {
            super.channelRead(ctx, msg);
            return;
        }

        if (msg instanceof ByteBuf buf) {
            int len = buf.readableBytes();
            if (len >= 4) {
                int magic = buf.getInt(buf.readerIndex());
                if (magic == ZSTD_MAGIC) {
                    Zstd_compresser.LOGGER.info("[Zstd] CLIENT Inbound detector: Zstd Magic found! len={}", len);
                    buf.retain();
                    ctx.channel().eventLoop().execute(() -> replaceAndReplay(ctx, buf));
                    return;
                }
            }
        }
        super.channelRead(ctx, msg);
    }

    private void replaceAndReplay(ChannelHandlerContext spyCtx, ByteBuf firstZstdFrame) {
        Channel channel = spyCtx.channel();
        ZstdChannelManager mgr = channel.attr(ZstdChannelManager.KEY).get();
        if (mgr == null) return;

        Zstd_compresser.LOGGER.debug("[Zstd] CLIENT Installing decoder, pipeline before: {}",
                channel.pipeline().names());
        mgr.installDecoder(channel);
        Zstd_compresser.LOGGER.debug("[Zstd] CLIENT Decoder installed. Pipeline: {}",
                channel.pipeline().names());
        spyCtx.fireChannelRead(firstZstdFrame);

        try {
            if (channel.pipeline().get("zstd_inbound_spy") != null) {
                channel.pipeline().remove("zstd_inbound_spy");
                Zstd_compresser.LOGGER.info("[Zstd] CLIENT Inbound detector removed self");
            }
        } catch (Exception ignored) {
        }
    }
}

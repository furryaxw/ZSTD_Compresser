package top.furryaxw.zstd_compresser.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.furryaxw.zstd_compresser.ZstdConfig;
import top.furryaxw.zstd_compresser.ZstdStatsData;

@Mixin(Gui.class)
public class MixinGuiHud {

    @Unique
    private static final int REFRESH_MS = 100;

    @Unique
    private long zstd_compresser$lastUpdateMs;
    @Unique
    private String zstd_compresser$lastTxLine;
    @Unique
    private String zstd_compresser$lastRxLine;
    @Unique
    private String zstd_compresser$lastBatchLine;

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!ZstdConfig.hudEnabledRuntime) return;
        if (Minecraft.getInstance().player == null) return;

        long now = System.currentTimeMillis();
        if (now - zstd_compresser$lastUpdateMs < REFRESH_MS) {
            zstd_compresser$drawHud(guiGraphics, zstd_compresser$lastTxLine, zstd_compresser$lastRxLine);
            return;
        }
        zstd_compresser$lastUpdateMs = now;

        String stats = ZstdStatsData.captureTabStats(1);
        String txLine = null;
        String rxLine = null;

        if (stats != null) {
            String[] parts = stats.split("\\|");
            txLine = parts.length > 0 ? parts[0].trim() : "";
            rxLine = parts.length > 1 ? parts[1].trim() : "";

            if (zstd_compresser$lastTxLine != null) {
                txLine = zstd_compresser$averageLine(zstd_compresser$lastTxLine, txLine);
                rxLine = zstd_compresser$averageLine(zstd_compresser$lastRxLine, rxLine);
            }
            zstd_compresser$lastTxLine = txLine;
            zstd_compresser$lastRxLine = rxLine;
        }

        zstd_compresser$lastBatchLine = zstd_compresser$buildBatchLine();

        zstd_compresser$drawHud(guiGraphics,
                txLine != null ? txLine : zstd_compresser$lastTxLine,
                rxLine != null ? rxLine : zstd_compresser$lastRxLine);
    }

    @Unique
    private void zstd_compresser$drawHud(GuiGraphics g, String tx, String rx) {
        String batch = zstd_compresser$lastBatchLine;
        Font font = Minecraft.getInstance().font;
        int x = 4, y = 4, lineH = 10;
        if (tx == null && batch == null) return;

        int totalLines = (batch != null ? 1 : 0) + (tx != null ? 1 : 0) + (rx != null ? 1 : 0);
        int w1 = batch != null ? font.width(zstd_compresser$stripFmt(batch)) : 0;
        int w2 = tx != null ? font.width(zstd_compresser$stripFmt(tx)) : 0;
        int w3 = rx != null ? font.width(zstd_compresser$stripFmt(rx)) : 0;
        int maxW = Math.max(Math.max(w1, w2), w3) + 4;

        g.fill(x - 2, y - 2, x + maxW, y + lineH * totalLines, 0x88000000);
        if (batch != null) {
            g.drawString(font, Component.literal(batch), x, y, 0xFFFFFF);
            y += lineH;
        }
        if (tx != null) {
            g.drawString(font, Component.literal(tx), x, y, 0xFFFFFF);
            y += lineH;
        }
        if (rx != null) {
            g.drawString(font, Component.literal(rx), x, y, 0xFFFFFF);
        }
    }

    @Unique
    private static String zstd_compresser$buildBatchLine() {
        boolean local = ZstdStatsData.batchActive;
        boolean peer = ZstdStatsData.peerBatchActive;
        if (!local && !peer) {
            return "§7§lBATCH:§7 OFF";
        }
        String localStr = local ? "§aON" : "§7OFF";
        String peerStr = peer ? "§aON" : "§7OFF";
        return "§lBATCH:§r TX " + localStr + " §7|§r RX " + peerStr;
    }

    @Unique
    private static String zstd_compresser$averageLine(String prev, String curr) {
        return prev.replaceAll("§[0-9a-fk-orl]", "").equals(
                curr.replaceAll("§[0-9a-fk-orl]", "")) ? prev : curr;
    }

    @Unique
    private static String zstd_compresser$stripFmt(String s) {
        return s.replaceAll("§[0-9a-fk-orl]", "");
    }
}

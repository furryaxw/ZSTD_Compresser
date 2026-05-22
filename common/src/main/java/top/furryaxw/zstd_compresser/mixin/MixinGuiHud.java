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
            String txPart = parts.length > 0 ? parts[0].trim() : "";
            String rxPart = parts.length > 1 ? parts[1].trim() : "";
            txLine = "§lTX:§r" + txPart.substring(txPart.indexOf(':') + 1);
            rxLine = "§lRX:§r" + rxPart.substring(rxPart.indexOf(':') + 1);

            if (zstd_compresser$lastTxLine != null) {
                txLine = zstd_compresser$averageLine(zstd_compresser$lastTxLine, txLine);
                rxLine = zstd_compresser$averageLine(zstd_compresser$lastRxLine, rxLine);
            }
            zstd_compresser$lastTxLine = txLine;
            zstd_compresser$lastRxLine = rxLine;
        }

        zstd_compresser$drawHud(guiGraphics, txLine != null ? txLine : zstd_compresser$lastTxLine,
                rxLine != null ? rxLine : zstd_compresser$lastRxLine);
    }

    @Unique
    private void zstd_compresser$drawHud(GuiGraphics g, String tx, String rx) {
        if (tx == null) return;
        Font font = Minecraft.getInstance().font;
        int x = 4;
        int y = 4;
        int lineH = 10;

        String raw = rx != null ? zstd_compresser$stripFmt(tx + rx) : zstd_compresser$stripFmt(tx);
        int maxW = font.width(raw) + 4;

        g.fill(x - 2, y - 2, x + maxW, y + lineH * 2, 0x88000000);
        g.drawString(font, Component.literal(tx), x, y, 0xFFFFFF);
        if (rx != null) {
            g.drawString(font, Component.literal(rx), x, y + lineH, 0xFFFFFF);
        }
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

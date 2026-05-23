package top.furryaxw.zstd_compresser.forge;

import dev.architectury.platform.forge.EventBuses;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import top.furryaxw.zstd_compresser.ZstdConfig;
import top.furryaxw.zstd_compresser.ZstdKeyMappings;
import top.furryaxw.zstd_compresser.ZstdStatsData;
import top.furryaxw.zstd_compresser.Zstd_compresser;

@Mod(Zstd_compresser.MOD_ID)
public class Zstd_compresserForge {
    private static final int REFRESH_MS = 100;
    private long lastUpdateMs;
    private String lastTxLine;
    private String lastRxLine;

    @SuppressWarnings("removal")
    public Zstd_compresserForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        EventBuses.registerModEventBus(Zstd_compresser.MOD_ID, modEventBus);
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(this::registerKeyMappings);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            Zstd_compresser.init();
            MinecraftForge.EVENT_BUS.addListener(this::onRenderHud);
        });
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ZstdKeyMappings.TOGGLE_HUD);
    }

    private void onRenderHud(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (!ZstdConfig.hudEnabledRuntime) return;
        if (Minecraft.getInstance().player == null) return;

        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < REFRESH_MS) {
            drawHud(event.getGuiGraphics(), lastTxLine, lastRxLine);
            return;
        }
        lastUpdateMs = now;

        String stats = ZstdStatsData.captureTabStats(1);
        String txLine = null;
        String rxLine = null;

        if (stats != null) {
            String[] parts = stats.split("\\|");
            String txPart = parts.length > 0 ? parts[0].trim() : "";
            String rxPart = parts.length > 1 ? parts[1].trim() : "";
            txLine = "§lTX:§r" + txPart.substring(txPart.indexOf(':') + 1);
            rxLine = "§lRX:§r" + rxPart.substring(rxPart.indexOf(':') + 1);

            if (lastTxLine != null) {
                txLine = averageLine(lastTxLine, txLine);
                rxLine = averageLine(lastRxLine, rxLine);
            }
            lastTxLine = txLine;
            lastRxLine = rxLine;
        }

        drawHud(event.getGuiGraphics(), txLine != null ? txLine : lastTxLine,
                rxLine != null ? rxLine : lastRxLine);
    }

    private void drawHud(GuiGraphics g, String tx, String rx) {
        if (tx == null) return;
        Font font = Minecraft.getInstance().font;
        int x = 4;
        int y = 4;
        int lineH = 10;

        String raw = rx != null ? stripFmt(tx + rx) : stripFmt(tx);
        int maxW = font.width(raw) + 4;

        g.fill(x - 2, y - 2, x + maxW, y + lineH * 2, 0x88000000);
        g.drawString(font, Component.literal(tx), x, y, 0xFFFFFF);
        if (rx != null) {
            g.drawString(font, Component.literal(rx), x, y + lineH, 0xFFFFFF);
        }
    }

    private static String averageLine(String prev, String curr) {
        return prev.replaceAll("§[0-9a-fk-orl]", "").equals(
                curr.replaceAll("§[0-9a-fk-orl]", "")) ? prev : curr;
    }

    private static String stripFmt(String s) {
        return s.replaceAll("§[0-9a-fk-orl]", "");
    }
}

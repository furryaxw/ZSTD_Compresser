package top.furryaxw.zSTDCompresser;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ZstdCommand implements SimpleCommand {

    private final ProxyServer proxy;

    public ZstdCommand(ProxyServer proxy) {
        this.proxy = proxy;
    }

    private static final List<String> SUBCOMMANDS = List.of("status", "reload", "train");

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            showStatus(invocation);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "status":
                showStatus(invocation);
                break;
            case "reload":
                reload(invocation);
                break;
            case "train":
                train(invocation, args);
                break;
            default:
                invocation.source().sendMessage(
                        Component.text("Unknown subcommand. Use: /zstd [status|reload|train]", NamedTextColor.RED));
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return CompletableFuture.completedFuture(SUBCOMMANDS);
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("zstd.command");
    }

    private void showStatus(Invocation invocation) {
        var source = invocation.source();
        ZstdVelocityConfig cfg = ZstdVelocityConfig.INSTANCE;

        source.sendMessage(Component.text("═══ Zstd Compresser Status ═══", NamedTextColor.GOLD));
        source.sendMessage(Component.text("Version: " + BuildConstants.VERSION, NamedTextColor.GRAY));

        source.sendMessage(Component.text("── Compression ──", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  Level: " + cfg.level + "  WindowLog: " + cfg.windowLog,
                NamedTextColor.GRAY));

        int connCount = proxy.getPlayerCount();

        source.sendMessage(Component.text("── Connections ──", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  Online players: " + connCount, NamedTextColor.GRAY));

        source.sendMessage(Component.text("── Trainer ──", NamedTextColor.YELLOW));
        showTrainerStatus(source, "Encoder", ZstdSampleTrainer.getEncoder());
        showTrainerStatus(source, "Decoder", ZstdSampleTrainer.getDecoder());

        source.sendMessage(Component.text("── Config ──", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  debug: " + cfg.debug
                + "  statsEnabled: " + cfg.statsEnabled
                + "  statsIntervalSec: " + cfg.statsIntervalSec,
                NamedTextColor.GRAY));
    }

    private void showTrainerStatus(net.kyori.adventure.audience.Audience source, String label, ZstdSampleTrainer trainer) {
        if (trainer == null) {
            source.sendMessage(Component.text("  " + label + ": not initialized", NamedTextColor.RED));
            return;
        }
        long dictId = trainer.getCurrentDictId();
        byte[] dict = trainer.getCurrentDict();
        source.sendMessage(Component.text("  " + label + ": samples=" + trainer.getSampleCount()
                + " bytes=" + trainer.getSampleBytes()
                + " dictId=" + dictId
                + " dictSize=" + (dict != null ? dict.length : 0) + "B",
                NamedTextColor.GRAY));
    }

    private void reload(Invocation invocation) {
        ZstdVelocityConfig.reload();
        invocation.source().sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
    }

    private void train(Invocation invocation, String[] args) {
        ZstdSampleTrainer enc = ZstdSampleTrainer.getEncoder();
        ZstdSampleTrainer dec = ZstdSampleTrainer.getDecoder();

        if (args.length > 1 && "force".equalsIgnoreCase(args[1])) {
            invocation.source().sendMessage(Component.text("Force training not yet implemented.", NamedTextColor.YELLOW));
            return;
        }

        if (enc != null) enc.tryTrain();
        if (dec != null) dec.tryTrain();

        Component msg = Component.text("Training check triggered. ", NamedTextColor.GREEN);
        if (enc != null) {
            msg = msg.append(Component.text("Encoder: " + enc.getSampleCount() + " samples, dict="
                    + enc.getCurrentDictId() + "  ", NamedTextColor.GRAY));
        }
        if (dec != null) {
            msg = msg.append(Component.text("Decoder: " + dec.getSampleCount() + " samples, dict="
                    + dec.getCurrentDictId(), NamedTextColor.GRAY));
        }
        invocation.source().sendMessage(msg);
    }
}

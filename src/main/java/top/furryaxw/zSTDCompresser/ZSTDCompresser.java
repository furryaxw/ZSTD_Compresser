package top.furryaxw.zSTDCompresser;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.ConnectionHandshakeEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import io.netty.channel.Channel;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

@Plugin(id = "zstd_velocity", name = "ZSTD Compresser", version = BuildConstants.VERSION, authors = {"Furryaxw"})
public class ZSTDCompresser {

    private static final String[] CHANNEL_FIELD_NAMES = {"channel"};
    private static final String[] CONNECTION_FIELD_NAMES = {"connection", "delegate"};

    private final Logger logger;
    private final ProxyServer proxy;
    private final Path dataDirectory;

    @Inject
    public ZSTDCompresser(Logger logger, ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ZstdVelocityConfig.load(dataDirectory.resolve("config.yml"));
        ZstdSampleTrainer.init(dataDirectory);
        if (ZstdVelocityConfig.INSTANCE.debug) {
            applyDebugLogLevel();
        }
        logger.info("ZSTD Compresser Velocity plugin initialized");
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("zstd")
                        .plugin(this)
                        .build(),
                new ZstdCommand(proxy));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        ZstdSampleTrainer.shutdown();
        logger.info("[Zstd] Samples saved on shutdown");
    }

    @Subscribe
    public void onConnectionHandshake(ConnectionHandshakeEvent event) {
        InboundConnection inbound = event.getConnection();

        String rawHost = inbound.getRawVirtualHost().orElse("");
        if (!rawHost.contains("\0ZSTD\0")) return;

        logger.info("[Zstd] Detected Zstd client: {}", rawHost);

        Channel channel = extractChannel(inbound);
        if (channel == null) {
            logger.warn("[Zstd] Failed to extract Netty Channel, falling back to vanilla");
            return;
        }

        ZstdChannelManager mgr = new ZstdChannelManager();
        channel.attr(ZstdChannelManager.KEY).set(mgr);
        channel.attr(ZstdChannelManager.ZSTD_ENABLED).set(true);
        channel.attr(ZstdChannelManager.ZSTD_STATE).set(ZstdChannelManager.TransportState.NEGOTIATING);
        channel.closeFuture().addListener(f -> mgr.close());

        channel.pipeline().addBefore("handler", "zstd_outbound_spy", new ZstdHijacker());

        logger.info("[Zstd] Hijacker injected | channel={} remote={} pipeline={}",
                channel.getClass().getSimpleName(), channel.remoteAddress(), channel.pipeline().names());
    }

    private void applyDebugLogLevel() {
        boolean ok = tryLog4j2() || tryLogback();
        if (ok) {
            logger.info("[Zstd] Debug logging enabled");
        } else {
            logger.warn("[Zstd] Could not set debug log level — configure the logging framework manually");
        }
    }

    private boolean tryLog4j2() {
        try {
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object debugLevel = levelClass.getField("DEBUG").get(null);
            configuratorClass.getMethod("setLevel", String.class, levelClass)
                    .invoke(null, "zstd_velocity", debugLevel);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryLogback() {
        try {
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
            Object debugLevel = levelClass.getField("DEBUG").get(null);
            Class<?> loggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Object logbackLogger = org.slf4j.LoggerFactory.getLogger("zstd_velocity");
            loggerClass.getMethod("setLevel", levelClass).invoke(logbackLogger, debugLevel);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Channel extractChannel(InboundConnection inbound) {
        Channel channel = tryReflectGetChannel(inbound);
        if (channel != null) return channel;

        for (String connField : CONNECTION_FIELD_NAMES) {
            Object conn = reflectField(inbound, connField);
            if (conn == null) continue;

            channel = tryReflectGetChannel(conn);
            if (channel != null) return channel;

            for (String nestedField : CONNECTION_FIELD_NAMES) {
                Object nested = reflectField(conn, nestedField);
                if (nested == null) continue;
                channel = tryReflectGetChannel(nested);
                if (channel != null) return channel;
            }
        }

        logger.warn("[Zstd] Channel not found, inboundClass={}", inbound.getClass().getName());
        return null;
    }

    private Channel tryReflectGetChannel(Object obj) {
        if (obj instanceof Channel) return (Channel) obj;

        Channel ch = reflectFieldTyped(obj, Channel.class, CHANNEL_FIELD_NAMES);
        if (ch != null) return ch;

        try {
            Method m = obj.getClass().getMethod("getChannel");
            Object result = m.invoke(obj);
            if (result instanceof Channel) return (Channel) result;
        } catch (Exception ignored) {
        }

        return null;
    }

    private static Channel reflectFieldTyped(Object obj, Class<Channel> type, String[] names) {
        for (String name : names) {
            Object val = reflectField(obj, name);
            if (type.isInstance(val)) return (Channel) val;
        }
        return null;
    }

    private static Object reflectField(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}

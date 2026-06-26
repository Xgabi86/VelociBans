package fr.aethernode.velocibans;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.aethernode.velocibans.command.*;
import fr.aethernode.velocibans.config.Config;
import fr.aethernode.velocibans.config.ConfigLoader;
import fr.aethernode.velocibans.database.BanDatabase;
import fr.aethernode.velocibans.discord.DiscordWebhook;
import fr.aethernode.velocibans.listener.BanListener;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

import static fr.aethernode.velocibans.util.AnsiColors.*;

@Plugin(
        id      = "velocibans",
        name    = "VelociBans",
        version = "1.0.0",
        authors = {"Aethernode"})
public final class VelociBansPlugin {

    private final PluginContainer container;
    private final ProxyServer     proxy;
    private final Logger          logger;
    private final Path            dataDirectory;

    private volatile Config  config;
    private BanDatabase      database;
    private DiscordWebhook   discord;
    private BanListener      activeBanListener;

    @Inject
    public VelociBansPlugin(
            PluginContainer container,
            ProxyServer proxy,
            Logger logger,
            @DataDirectory Path dataDirectory) {
        this.container     = container;
        this.proxy         = proxy;
        this.logger        = logger;
        this.dataDirectory = dataDirectory;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info(" ");
        logger.info(YELLOW + "  Starting up" + GREEN + " VelociBans v" + CYAN + "{}" + RESET, getVersion());
        logger.info(" ");

        // Config
        try {
            config = ConfigLoader.load(dataDirectory);
        } catch (IOException e) {
            logger.error("Failed to load config.yml — plugin will not start.", e);
            return;
        }

        // Database
        try {
            database = new BanDatabase(dataDirectory);
            logger.info(GRAY + "  Database       : " + GREEN + "H2 ready" + RESET);
        } catch (SQLException e) {
            logger.error("Failed to initialize H2 database — plugin will not start.", e);
            return;
        }

        // Discord webhook helper
        discord = new DiscordWebhook(logger);

        logConfig();
        registerListener();
        registerCommands();

        logger.info(" ");
        logger.info(GREEN + "  VelociBans is ready." + RESET);
        logger.info(" ");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (database != null) database.close();
        logger.info("[VelociBans] Stopped.");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Config        getConfig()      { return config; }
    public ProxyServer   getProxy()       { return proxy; }
    public Logger        getLogger()      { return logger; }
    public BanDatabase   getDatabase()    { return database; }
    public DiscordWebhook getDiscord()    { return discord; }
    public Path          getDataDirectory() { return dataDirectory; }

    public void reload() throws IOException {
        config = ConfigLoader.load(dataDirectory);
        unregisterListener();
        registerListener();
        logConfig();
        logger.info(GREEN + "  [VelociBans] Config reloaded." + RESET);
    }

    public String getVersion() {
        return container.getDescription().getVersion().orElse("unknown");
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void registerListener() {
        activeBanListener = new BanListener(this);
        proxy.getEventManager().register(this, activeBanListener);
    }

    private void unregisterListener() {
        if (activeBanListener != null) {
            proxy.getEventManager().unregisterListener(this, activeBanListener);
            activeBanListener = null;
        }
    }

    private void registerCommands() {
        register("kick",    new KickCommand(this));
        register("ban",     new BanCommand(this));
        register("banip",   new BanIpCommand(this));
        register("unban",   new UnbanCommand(this, false));
        register("unbanip", new UnbanCommand(this, true));
    }

    private void register(String name, com.velocitypowered.api.command.Command cmd) {
        CommandMeta meta = proxy.getCommandManager()
                .metaBuilder(name)
                .plugin(this)
                .build();
        proxy.getCommandManager().register(meta, cmd);
        logger.info(GRAY + "  Registered     : " + CYAN + "/" + name + RESET);
    }

    private void logConfig() {
        logger.info(GRAY + "  Perm kick      : " + CYAN + "{}" + RESET, config.permKick());
        logger.info(GRAY + "  Perm ban       : " + CYAN + "{}" + RESET, config.permBan());
        logger.info(GRAY + "  Perm banip     : " + CYAN + "{}" + RESET, config.permBanIp());
        logger.info(GRAY + "  Discord        : " + CYAN + "{}" + RESET, config.discordEnabled() ? "enabled" : "disabled");
    }
}

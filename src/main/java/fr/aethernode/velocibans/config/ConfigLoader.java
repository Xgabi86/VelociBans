package fr.aethernode.velocibans.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigLoader {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ConfigLoader() {}

    public static Config load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path configPath = dataDirectory.resolve("config.yml");

        YamlConfigurationLoader loader = buildLoader(configPath);
        CommentedConfigurationNode root = loader.load();
        boolean needsSave = false;

        // ── Permissions ────────────────────────────────────────────────────
        needsSave |= setDefault(root, "permissions.kick",   "velocibans.kick");
        needsSave |= setDefault(root, "permissions.ban",    "velocibans.ban");
        needsSave |= setDefault(root, "permissions.banip",  "velocibans.banip");

        String permKick  = root.node("permissions", "kick").getString("velocibans.kick");
        String permBan   = root.node("permissions", "ban").getString("velocibans.ban");
        String permBanIp = root.node("permissions", "banip").getString("velocibans.banip");

        // ── Messages ───────────────────────────────────────────────────────
        var kickDefault = List.of(
                "<red><bold>⛔ Vous avez été expulsé</bold></red>",
                "<gray>Raison : <white>{reason}</white></gray>",
                "<gray>Sanctionné par : <white>{staff}</white></gray>"
        );
        var banDefault = List.of(
                "<red><bold>⛔ Vous êtes banni</bold></red>",
                "<gray>Raison : <white>{reason}</white></gray>",
                "<gray>Expire : <white>{expires}</white></gray>",
                "<gray>Sanctionné par : <white>{staff}</white></gray>"
        );
        var banIpDefault = List.of(
                "<red><bold>⛔ Votre IP est bannie</bold></red>",
                "<gray>Raison : <white>{reason}</white></gray>",
                "<gray>Expire : <white>{expires}</white></gray>",
                "<gray>Sanctionné par : <white>{staff}</white></gray>"
        );

        needsSave |= setDefaultList(root, "messages.kick-screen",   kickDefault,
                "Message affiché au joueur kické. Supporte MiniMessage. Placeholders: {reason} {staff}");
        needsSave |= setDefaultList(root, "messages.ban-screen",    banDefault,
                "Message affiché au joueur banni à la connexion. Placeholders: {reason} {expires} {staff}");
        needsSave |= setDefaultList(root, "messages.banip-screen",  banIpDefault,
                "Message affiché au joueur IP-banni. Placeholders: {reason} {expires} {staff}");

        List<Component> kickScreen  = readMiniMessageList(root.node("messages", "kick-screen"),  kickDefault);
        List<Component> banScreen   = readMiniMessageList(root.node("messages", "ban-screen"),   banDefault);
        List<Component> banIpScreen = readMiniMessageList(root.node("messages", "banip-screen"), banIpDefault);

        // ── Discord webhook ────────────────────────────────────────────────
        needsSave |= setDefault(root, "discord.enabled",     false,
                "Active/désactive l'envoi de messages sur Discord.");
        needsSave |= setDefault(root, "discord.webhook-url", "https://discord.com/api/webhooks/VOTRE_ID/VOTRE_TOKEN",
                "URL du webhook Discord.");

        String kickJson = """
                {"embeds":[{"title":"⚡ Kick","color":16776960,"fields":[{"name":"Joueur","value":"{player}","inline":true},{"name":"Staff","value":"{staff}","inline":true},{"name":"Raison","value":"{reason}","inline":false}]}]}""";
        String banJson = """
                {"embeds":[{"title":"🔨 Ban","color":16711680,"fields":[{"name":"Joueur","value":"{player}","inline":true},{"name":"Staff","value":"{staff}","inline":true},{"name":"Durée","value":"{duration}","inline":true},{"name":"Expire","value":"{expires}","inline":true},{"name":"Raison","value":"{reason}","inline":false}]}]}""";
        String banIpJson = """
                {"embeds":[{"title":"🚫 Ban IP","color":10038562,"fields":[{"name":"Joueur","value":"{player}","inline":true},{"name":"Staff","value":"{staff}","inline":true},{"name":"Durée","value":"{duration}","inline":true},{"name":"Expire","value":"{expires}","inline":true},{"name":"Raison","value":"{reason}","inline":false}]}]}""";

        needsSave |= setDefault(root, "discord.messages.kick",  kickJson,
                "JSON envoyé au webhook lors d'un kick. Placeholders: {player} {staff} {reason}");
        needsSave |= setDefault(root, "discord.messages.ban",   banJson,
                "JSON envoyé au webhook lors d'un ban. Placeholders: {player} {staff} {reason} {duration} {expires}");
        needsSave |= setDefault(root, "discord.messages.banip", banIpJson,
                "JSON envoyé au webhook lors d'un banip. Mêmes placeholders que ban.");

        boolean discordEnabled    = root.node("discord", "enabled").getBoolean(false);
        String  discordWebhookUrl = root.node("discord", "webhook-url").getString("");
        String  discordKickJson   = root.node("discord", "messages", "kick").getString(kickJson);
        String  discordBanJson    = root.node("discord", "messages", "ban").getString(banJson);
        String  discordBanIpJson  = root.node("discord", "messages", "banip").getString(banIpJson);

        if (needsSave) loader.save(root);

        return new Config(
                permKick, permBan, permBanIp,
                kickScreen, banScreen, banIpScreen,
                discordEnabled, discordWebhookUrl,
                discordKickJson, discordBanJson, discordBanIpJson
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static YamlConfigurationLoader buildLoader(Path path) {
        return YamlConfigurationLoader.builder()
                .path(path)
                .nodeStyle(NodeStyle.BLOCK)
                .build();
    }

    /** Returns true if the node was virtual (i.e. a default was written). */
    private static boolean setDefault(CommentedConfigurationNode root, String dotPath,
                                       Object value) throws ConfigurateException {
        return setDefault(root, dotPath, value, null);
    }

    private static boolean setDefault(CommentedConfigurationNode root, String dotPath,
                                       Object value, String comment) throws ConfigurateException {
        String[] parts = dotPath.split("\\.");
        CommentedConfigurationNode node = root.node((Object[]) parts);
        if (!node.virtual()) return false;
        if (comment != null) node.comment(comment);
        node.set(value);
        return true;
    }

    private static boolean setDefaultList(CommentedConfigurationNode root, String dotPath,
                                           List<String> values, String comment) throws ConfigurateException {
        String[] parts = dotPath.split("\\.");
        CommentedConfigurationNode node = root.node((Object[]) parts);
        if (!node.virtual()) return false;
        if (comment != null) node.comment(comment);
        node.setList(String.class, values);
        return true;
    }

    private static List<Component> readMiniMessageList(CommentedConfigurationNode node,
                                                        List<String> defaults) throws ConfigurateException {
        List<String> raw = node.getList(String.class);
        if (raw == null || raw.isEmpty()) {
            // Might be a single string
            String single = node.getString();
            if (single != null && !single.isBlank()) {
                raw = List.of(single.split("\n"));
            } else {
                raw = defaults;
            }
        }
        List<Component> result = new ArrayList<>(raw.size());
        for (String line : raw) result.add(MM.deserialize(line));
        return result;
    }
}

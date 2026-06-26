package fr.aethernode.velocibans.config;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Immutable snapshot of {@code config.yml}.
 * Replaced atomically on reload.
 */
public record Config(
        // ── Permissions ────────────────────────────────────────────────────
        String permKick,
        String permBan,
        String permBanIp,

        // ── Messages ───────────────────────────────────────────────────────
        /** Shown to the player being kicked (MiniMessage, supports \n via list). */
        List<Component> kickScreenDefault,

        /** Template for ban screen. Placeholders: {reason}, {expires}, {staff} */
        List<Component> banScreen,

        /** Template for IP-ban screen. Same placeholders as banScreen. */
        List<Component> banIpScreen,

        // ── Discord webhook ────────────────────────────────────────────────
        boolean discordEnabled,
        String  discordWebhookUrl,

        /** Raw JSON template for kick event. Placeholders: {player}, {reason}, {staff} */
        String discordKickJson,

        /** Raw JSON template for ban event. Placeholders: {player}, {reason}, {staff}, {duration}, {expires} */
        String discordBanJson,

        /** Raw JSON template for banip event. Same placeholders as ban. */
        String discordBanIpJson
) {
    public Config {
        kickScreenDefault = List.copyOf(kickScreenDefault);
        banScreen         = List.copyOf(banScreen);
        banIpScreen       = List.copyOf(banIpScreen);
    }
}

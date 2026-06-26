package fr.aethernode.velocibans.discord;

import fr.aethernode.velocibans.config.Config;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

/**
 * Sends Discord webhook messages for kick / ban / banip events.
 * All HTTP calls are fire-and-forget on a virtual-thread executor.
 */
public final class DiscordWebhook {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);

    private final Logger logger;

    public DiscordWebhook(Logger logger) {
        this.logger = logger;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void sendKick(Config cfg, String player, String staff, String reason) {
        if (!cfg.discordEnabled()) return;
        String json = replace(cfg.discordKickJson(), player, staff, reason, null, null);
        send(cfg.discordWebhookUrl(), json);
    }

    public void sendBan(Config cfg, String player, String staff, String reason,
                        String duration, Instant expiresAt) {
        if (!cfg.discordEnabled()) return;
        String expires = expiresAt == null ? "Permanent" : FMT.format(expiresAt) + " UTC";
        String json = replace(cfg.discordBanJson(), player, staff, reason, duration, expires);
        send(cfg.discordWebhookUrl(), json);
    }

    public void sendBanIp(Config cfg, String player, String staff, String reason,
                          String duration, Instant expiresAt) {
        if (!cfg.discordEnabled()) return;
        String expires = expiresAt == null ? "Permanent" : FMT.format(expiresAt) + " UTC";
        String json = replace(cfg.discordBanIpJson(), player, staff, reason, duration, expires);
        send(cfg.discordWebhookUrl(), json);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String replace(String template,
                                   String player, String staff, String reason,
                                   String duration, String expires) {
        String s = template
                .replace("{player}",   esc(player))
                .replace("{staff}",    esc(staff))
                .replace("{reason}",   esc(reason));
        if (duration != null) s = s.replace("{duration}", esc(duration));
        if (expires  != null) s = s.replace("{expires}",  esc(expires));
        return s;
    }

    /** Escapes characters that would break a JSON string value. */
    private static String esc(String s) {
        if (s == null) return "?";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private void send(String webhookUrl, String json) {
        if (webhookUrl == null || webhookUrl.isBlank()
                || webhookUrl.startsWith("https://discord.com/api/webhooks/VOTRE")) {
            logger.warn("[VelociBans] Discord webhook URL non configurée — message ignoré.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    logger.warn("[VelociBans] Discord webhook HTTP {}", response.statusCode());
                }
            } catch (Exception e) {
                logger.error("[VelociBans] Erreur envoi webhook Discord", e);
            }
        });
    }
}

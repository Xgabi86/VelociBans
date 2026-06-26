package fr.aethernode.velocibans.listener;

import com.velocitypowered.api.event.ResultedEvent.ComponentResult;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import fr.aethernode.velocibans.VelociBansPlugin;
import fr.aethernode.velocibans.config.Config;
import fr.aethernode.velocibans.database.BanEntry;
import fr.aethernode.velocibans.util.DurationParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Bloque les joueurs bannis (nom ou IP) à la connexion.
 * Met également à jour le cache IP à chaque login réussi.
 */
public final class BanListener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final VelociBansPlugin plugin;

    public BanListener(VelociBansPlugin plugin) {
        this.plugin = plugin;
    }

    // ── Vérification ban au login ─────────────────────────────────────────────

    @Subscribe(priority = Short.MAX_VALUE, async = false)
    public void onLogin(LoginEvent event) {
        Config cfg        = plugin.getConfig();
        String playerName = event.getPlayer().getUsername();

        // Vérification ban par nom
        try {
            BanEntry ban = plugin.getDatabase().getActiveBan(playerName);
            if (ban != null) {
                event.setResult(ComponentResult.denied(buildScreen(cfg.banScreen(), ban)));
                return;
            }
        } catch (SQLException e) {
            plugin.getLogger().error("[VelociBans] DB error checking ban for {}", playerName, e);
        }

        // Vérification ban par IP
        InetSocketAddress addr = event.getPlayer().getRemoteAddress();
        if (addr != null) {
            String ip = addr.getAddress().getHostAddress();
            try {
                BanEntry ipBan = plugin.getDatabase().getActiveIpBan(ip);
                if (ipBan != null) {
                    event.setResult(ComponentResult.denied(buildScreen(cfg.banIpScreen(), ipBan)));
                }
            } catch (SQLException e) {
                plugin.getLogger().error("[VelociBans] DB error checking IP ban for {}", addr, e);
            }
        }
    }

    // ── Mise à jour du cache IP après login réussi ────────────────────────────

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        InetSocketAddress addr = event.getPlayer().getRemoteAddress();
        if (addr == null) return;

        String playerName = event.getPlayer().getUsername();
        String ip         = addr.getAddress().getHostAddress();

        try {
            plugin.getDatabase().upsertIpCache(playerName, ip);
        } catch (SQLException e) {
            plugin.getLogger().error("[VelociBans] DB error updating IP cache for {}", playerName, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Component buildScreen(List<Component> template, BanEntry ban) {
        String expires = ban.isPermanent()
                ? "Permanent"
                : DurationParser.format(Duration.between(Instant.now(), ban.expiresAt()));

        List<Component> lines = new ArrayList<>(template.size());
        for (Component raw : template) {
            String s = MM.serialize(raw)
                    .replace("{reason}",  escMM(ban.reason()))
                    .replace("{expires}", escMM(expires))
                    .replace("{staff}",   escMM(ban.staffName()));
            lines.add(MM.deserialize(s));
        }
        return lines.size() == 1
                ? lines.getFirst()
                : Component.join(JoinConfiguration.newlines(), lines);
    }

    private static String escMM(String s) {
        return s == null ? "?" : s.replace("<", "\\<").replace(">", "\\>");
    }
}

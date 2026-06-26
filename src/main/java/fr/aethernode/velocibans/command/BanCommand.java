package fr.aethernode.velocibans.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fr.aethernode.velocibans.VelociBansPlugin;
import fr.aethernode.velocibans.config.Config;
import fr.aethernode.velocibans.database.BanEntry;
import fr.aethernode.velocibans.util.DurationParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * /ban <joueur> [durée] [raison...]
 *
 * <p>Fonctionne que le joueur soit en ligne ou hors-ligne.
 *
 * <p>Exemples :
 * <ul>
 *   <li>/ban Player2 7d Insultes staff  → ban 7 jours, raison "Insultes staff"</li>
 *   <li>/ban Player2 Hack               → ban permanent, raison "Hack"</li>
 *   <li>/ban Player2                    → ban permanent, raison par défaut</li>
 * </ul>
 */
public final class BanCommand implements SimpleCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize(
            "<dark_gray>[</dark_gray><gold><bold>VelociBans</bold></gold><dark_gray>]</dark_gray> ");

    private static final String DEFAULT_REASON = "Aucune raison spécifiée";

    private final VelociBansPlugin plugin;

    public BanCommand(VelociBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src  = invocation.source();
        String[]      args = invocation.arguments();
        Config        cfg  = plugin.getConfig();

        if (args.length < 1) {
            send(src, "<red>Usage : /ban <joueur> [durée] [raison...]");
            send(src, "<gray>Exemples : /ban Player1 7d Hack | /ban Player1 Insultes | /ban Player1");
            return;
        }

        String playerName = args[0];
        String staffName  = src instanceof Player p ? p.getUsername() : "Console";

        // ── Parse durée + raison ───────────────────────────────────────────
        Duration duration = null;
        String   reason;

        if (args.length >= 2) {
            Duration parsed = DurationParser.parse(args[1]);
            if (parsed != null && args.length >= 3) {
                // /ban Player 7d Raison...
                duration = parsed;
                reason   = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            } else if (parsed != null) {
                // /ban Player 7d  → durée seule, pas de raison → raison par défaut
                duration = parsed;
                reason   = DEFAULT_REASON;
            } else {
                // /ban Player Raison...  → pas de durée, tout est raison
                reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        } else {
            // /ban Player  → pas de durée, raison par défaut
            reason = DEFAULT_REASON;
        }

        Instant expiresAt      = duration == null ? null : Instant.now().plus(duration);
        String  durationDisplay = duration == null ? "Permanent" : DurationParser.format(duration);

        // ── Persist ────────────────────────────────────────────────────────
        try {
            plugin.getDatabase().addBan(playerName, reason, staffName, expiresAt);
        } catch (SQLException e) {
            plugin.getLogger().error("[VelociBans] DB error adding ban", e);
            send(src, "<red>✘ Erreur base de données : " + e.getMessage());
            return;
        }

        // ── Kick si en ligne ───────────────────────────────────────────────
        plugin.getProxy().getPlayer(playerName).ifPresent(target -> {
            BanEntry fake = new BanEntry(-1, playerName, null, reason, staffName, Instant.now(), expiresAt);
            target.disconnect(buildBanScreen(cfg, fake));
        });

        // ── Feedback staff ─────────────────────────────────────────────────
        send(src, "<green>✔ <white>" + playerName + "</white> a été banni."
                + " <gray>Durée : <white>" + durationDisplay + "</white>"
                + " | Raison : <white>" + escMM(reason) + "</white></gray>");

        // ── Discord ────────────────────────────────────────────────────────
        plugin.getDiscord().sendBan(cfg, playerName, staffName, reason, durationDisplay, expiresAt);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // arg 0 → tous les joueurs en ligne
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return plugin.getProxy().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }

        // arg 1 → durées suggérées (ou <Raison> si ça ne ressemble pas à une durée)
        if (args.length == 2) {
            return List.of("1h", "1d", "7d", "30d", "1mo", "1w", "<Raison>");
        }

        // arg 2+ → hint raison (si arg 1 était une durée valide)
        if (args.length == 3 && DurationParser.parse(args[1]) != null && args[2].isEmpty()) {
            return List.of("<Raison>");
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(plugin.getConfig().permBan());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static Component buildBanScreen(Config cfg, BanEntry ban) {
        String expires = ban.isPermanent()
                ? "Permanent"
                : DurationParser.format(Duration.between(Instant.now(), ban.expiresAt()));

        List<Component> lines = new ArrayList<>(cfg.banScreen().size());
        for (Component raw : cfg.banScreen()) {
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

    private void send(CommandSource src, String mini) {
        src.sendMessage(Component.join(
                JoinConfiguration.noSeparators(), PREFIX, MM.deserialize(mini)));
    }

    static String escMM(String s) {
        return s == null ? "?" : s.replace("<", "\\<").replace(">", "\\>");
    }
}

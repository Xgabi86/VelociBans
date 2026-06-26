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

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * /banip <joueur|ip> [durée] [raison...]
 *
 * <p>Accepte en premier argument :
 * <ul>
 *   <li>Un <b>nom de joueur en ligne</b> → IP résolue depuis la connexion active</li>
 *   <li>Un <b>nom de joueur hors-ligne</b> → IP résolue depuis le cache H2 (ip_cache)</li>
 *   <li>Une <b>adresse IP directe</b> (IPv4 ou IPv6) → bannie telle quelle</li>
 * </ul>
 *
 * <p>Exemples :
 * <pre>
 * /banip Player1 Hack              → IP-ban permanent
 * /banip Player1 7d Triche         → IP-ban 7 jours
 * /banip 192.168.1.42 Bot          → ban direct par IP
 * /banip Player1                   → IP-ban permanent, raison par défaut
 * </pre>
 */
public final class BanIpCommand implements SimpleCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize(
            "<dark_gray>[</dark_gray><gold><bold>VelociBans</bold></gold><dark_gray>]</dark_gray> ");

    private static final String DEFAULT_REASON = "Aucune raison spécifiée";

    // Regex simple pour distinguer une IP d'un nom de joueur
    private static final Pattern IP_PATTERN =
            Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$|^[0-9a-fA-F:]+:[0-9a-fA-F:]+$");

    private final VelociBansPlugin plugin;

    public BanIpCommand(VelociBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src  = invocation.source();
        String[]      args = invocation.arguments();
        Config        cfg  = plugin.getConfig();

        if (args.length < 1) {
            send(src, "<red>Usage : /banip <joueur|ip> [durée] [raison...]");
            send(src, "<gray>Exemples : /banip Player1 Hack | /banip Player1 7d Triche | /banip 1.2.3.4 Bot");
            return;
        }

        String target    = args[0];
        String staffName = src instanceof Player p ? p.getUsername() : "Console";

        // ── Résolution de l'IP ─────────────────────────────────────────────
        String resolvedIp;
        String displayName; // nom affiché dans le feedback (joueur ou IP brute)

        if (IP_PATTERN.matcher(target).matches()) {
            // L'argument est directement une IP
            resolvedIp  = target;
            displayName = target;
        } else {
            // L'argument est un nom de joueur — cherche en ligne d'abord, puis cache
            Optional<Player> onlineOpt = plugin.getProxy().getPlayer(target);
            if (onlineOpt.isPresent()) {
                InetSocketAddress addr = onlineOpt.get().getRemoteAddress();
                if (addr == null) {
                    send(src, "<red>Impossible de récupérer l'IP de <white>" + target + "</white>.");
                    return;
                }
                resolvedIp = addr.getAddress().getHostAddress();
            } else {
                // Hors-ligne → cache
                try {
                    resolvedIp = plugin.getDatabase().getCachedIp(target);
                } catch (SQLException e) {
                    plugin.getLogger().error("[VelociBans] DB error fetching cached IP", e);
                    send(src, "<red>✘ Erreur base de données : " + e.getMessage());
                    return;
                }

                if (resolvedIp == null) {
                    send(src, "<red>Joueur <white>" + target + "</white> inconnu.");
                    send(src, "<gray>Le joueur n'a jamais été vu sur ce réseau. Utilisez une IP directe si besoin.");
                    return;
                }
            }
            displayName = target;
        }

        // ── Parse durée + raison ───────────────────────────────────────────
        Duration duration = null;
        String   reason;

        if (args.length >= 2) {
            Duration parsed = DurationParser.parse(args[1]);
            if (parsed != null && args.length >= 3) {
                duration = parsed;
                reason   = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
            } else if (parsed != null) {
                duration = parsed;
                reason   = DEFAULT_REASON;
            } else {
                reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            }
        } else {
            reason = DEFAULT_REASON;
        }

        Instant expiresAt      = duration == null ? null : Instant.now().plus(duration);
        String  durationDisplay = duration == null ? "Permanent" : DurationParser.format(duration);

        // ── Persist ────────────────────────────────────────────────────────
        try {
            plugin.getDatabase().addIpBan(displayName, resolvedIp, reason, staffName, expiresAt);
        } catch (SQLException e) {
            plugin.getLogger().error("[VelociBans] DB error adding IP ban", e);
            send(src, "<red>✘ Erreur base de données : " + e.getMessage());
            return;
        }

        // ── Kick si le joueur est en ligne (par IP ou par nom) ─────────────
        final String finalIp = resolvedIp;
        plugin.getProxy().getAllPlayers().stream()
                .filter(p -> {
                    InetSocketAddress a = p.getRemoteAddress();
                    return a != null && a.getAddress().getHostAddress().equals(finalIp);
                })
                .forEach(p -> {
                    BanEntry fake = new BanEntry(-1, p.getUsername(), finalIp, reason,
                            staffName, Instant.now(), expiresAt);
                    p.disconnect(buildBanIpScreen(cfg, fake));
                });

        // ── Feedback staff ─────────────────────────────────────────────────
        send(src, "<green>✔ IP bannie : <aqua>" + resolvedIp + "</aqua>"
                + " <dark_gray>(" + displayName + ")</dark_gray>"
                + " <gray>Durée : <white>" + durationDisplay + "</white>"
                + " | Raison : <white>" + BanCommand.escMM(reason) + "</white></gray>");

        // ── Discord ────────────────────────────────────────────────────────
        plugin.getDiscord().sendBanIp(cfg, displayName, staffName, reason, durationDisplay, expiresAt);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // arg 0 → joueurs en ligne + hint IP
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> online = plugin.getProxy().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            if ("<ip>".startsWith(partial)) online.add("<IP>");
            return online;
        }

        // arg 1 → durées + hint raison
        if (args.length == 2) {
            return List.of("1h", "1d", "7d", "30d", "1mo", "1w", "<Raison>");
        }

        // arg 2+ → hint raison si arg 1 était une durée
        if (args.length == 3 && DurationParser.parse(args[1]) != null && args[2].isEmpty()) {
            return List.of("<Raison>");
        }

        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(plugin.getConfig().permBanIp());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Component buildBanIpScreen(Config cfg, BanEntry ban) {
        String expires = ban.isPermanent()
                ? "Permanent"
                : DurationParser.format(Duration.between(Instant.now(), ban.expiresAt()));

        List<Component> lines = new ArrayList<>(cfg.banIpScreen().size());
        for (Component raw : cfg.banIpScreen()) {
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

    private static String escMM(String s) {
        return s == null ? "?" : s.replace("<", "\\<").replace(">", "\\>");
    }
}

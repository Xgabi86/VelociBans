package fr.aethernode.velocibans.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import fr.aethernode.velocibans.VelociBansPlugin;
import fr.aethernode.velocibans.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * /kick <joueur> [raison...]
 *
 * <p>Expulse le joueur du proxy entier.
 * Le message affiché supporte les placeholders {reason} et {staff}.
 * Si aucune raison n'est fournie, elle est remplacée par "Aucune raison spécifiée".
 */
public final class KickCommand implements SimpleCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize(
            "<dark_gray>[</dark_gray><gold><bold>VelociBans</bold></gold><dark_gray>]</dark_gray> ");

    private static final String DEFAULT_REASON = "Aucune raison spécifiée";

    private final VelociBansPlugin plugin;

    public KickCommand(VelociBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src  = invocation.source();
        String[]      args = invocation.arguments();
        Config        cfg  = plugin.getConfig();

        if (args.length < 1) {
            send(src, "<red>Usage : /kick <joueur> [raison...]");
            return;
        }

        String targetName = args[0];
        Optional<Player> optPlayer = plugin.getProxy().getPlayer(targetName);

        if (optPlayer.isEmpty()) {
            send(src, "<red>Joueur <white>" + targetName + "</white> introuvable ou hors-ligne.");
            return;
        }

        Player target    = optPlayer.get();
        String staffName = src instanceof Player p ? p.getUsername() : "Console";
        String reason    = args.length >= 2
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : DEFAULT_REASON;

        // Build kick screen — apply {reason} and {staff} placeholders to config template
        Component screen = buildKickScreen(cfg, reason, staffName);
        target.disconnect(screen);

        send(src, "<green>✔ <white>" + target.getUsername() + "</white> a été expulsé."
                + " <gray>(Raison : <white>" + escMM(reason) + "</white>)</gray>");

        plugin.getDiscord().sendKick(cfg, target.getUsername(), staffName, reason);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        // arg 0 → joueurs en ligne
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return plugin.getProxy().getAllPlayers().stream()
                    .map(Player::getUsername)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }

        // arg 1+ → suggestion de raison
        if (args.length == 2 && args[1].isEmpty()) {
            return List.of("<Raison>");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(plugin.getConfig().permKick());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies {reason} and {staff} placeholders to every line of the kick-screen template.
     */
    private static Component buildKickScreen(Config cfg, String reason, String staff) {
        List<Component> lines = new ArrayList<>(cfg.kickScreenDefault().size());
        for (Component raw : cfg.kickScreenDefault()) {
            String s = MM.serialize(raw)
                    .replace("{reason}", escMM(reason))
                    .replace("{staff}",  escMM(staff));
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

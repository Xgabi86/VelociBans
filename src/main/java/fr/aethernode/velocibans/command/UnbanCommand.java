package fr.aethernode.velocibans.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import fr.aethernode.velocibans.VelociBansPlugin;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/**
 * /unban <joueur> — supprime le ban actif d'un joueur.
 */
public final class UnbanCommand implements SimpleCommand {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = MM.deserialize(
            "<dark_gray>[</dark_gray><gold><bold>VelociBans</bold></gold><dark_gray>]</dark_gray> ");

    private final VelociBansPlugin plugin;
    private final boolean ipMode; // true = /unbanip, false = /unban

    public UnbanCommand(VelociBansPlugin plugin, boolean ipMode) {
        this.plugin = plugin;
        this.ipMode = ipMode;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource src  = invocation.source();
        String[]      args = invocation.arguments();

        if (args.length < 1) {
            send(src, "<red>Usage : /" + (ipMode ? "unbanip" : "unban") + " <joueur/ip>");
            return;
        }

        String target = args[0];

        try {
            if (ipMode) {
                plugin.getDatabase().removeIpBan(target);
                send(src, "<green>✔ IP-ban supprimé pour <white>" + target + "</white>.");
            } else {
                plugin.getDatabase().removeBan(target);
                send(src, "<green>✔ Ban supprimé pour <white>" + target + "</white>.");
            }
        } catch (SQLException e) {
            plugin.getLogger().error("[VelociBans] DB error removing ban", e);
            send(src, "<red>✘ Erreur base de données : " + e.getMessage());
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Reuse ban/banip permission for unban/unbanip
        return ipMode
                ? invocation.source().hasPermission(plugin.getConfig().permBanIp())
                : invocation.source().hasPermission(plugin.getConfig().permBan());
    }

    private void send(CommandSource src, String mini) {
        src.sendMessage(Component.join(
                JoinConfiguration.noSeparators(), PREFIX, MM.deserialize(mini)));
    }
}

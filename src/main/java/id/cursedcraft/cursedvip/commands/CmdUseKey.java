package id.cursedcraft.cursedvip.commands;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CmdUseKey implements CommandExecutor {
    private final CursedVIP plugin;

    public CmdUseKey(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.send(sender, plugin.msg("no_console_command") + "!");
            return true;
        }
        if (!plugin.hasPerm(sender, "cursedvip.usekey", true)) {
            plugin.send(sender, plugin.msg("no_permission") + "!");
            return true;
        }
        if (args.length != 1) {
            plugin.send(sender, "/" + label + " <key>");
            return true;
        }
        plugin.getVipService().useKey((Player) sender, args[0]);
        return true;
    }
}

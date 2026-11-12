package id.cursedcraft.cursedvip.commands;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CmdVipTime implements CommandExecutor {
    private final CursedVIP plugin;

    public CmdVipTime(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.send(sender, plugin.msg("no_console_command") + "!");
            return true;
        }
        if (!plugin.hasPerm(sender, "cursedvip.viptime", true)) {
            plugin.send(sender, plugin.msg("no_permission") + "!");
            return true;
        }
        Player p = (Player) sender;
        plugin.getVipService().info(p, p);
        return true;
    }
}

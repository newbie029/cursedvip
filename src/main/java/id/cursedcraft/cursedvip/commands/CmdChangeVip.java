package id.cursedcraft.cursedvip.commands;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CmdChangeVip implements CommandExecutor {
    private final CursedVIP plugin;

    public CmdChangeVip(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.send(sender, plugin.msg("no_console_command") + "!");
            return true;
        }
        if (!plugin.hasPerm(sender, "cursedvip.changevip", true)) {
            plugin.send(sender, plugin.msg("no_permission") + "!");
            return true;
        }
        if (args.length != 1) {
            plugin.send(sender, "/" + label + " <" + plugin.msg("group") + ">");
            return true;
        }
        String group = plugin.matchVipGroup(args[0]);
        if (group == null) {
            plugin.send(sender, plugin.msg("group_not_found") + "!");
            return true;
        }
        plugin.getVipService().changeVip((Player) sender, group);
        return true;
    }
}

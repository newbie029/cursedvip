package id.cursedcraft.cursedvip.commands;

import id.cursedcraft.cursedvip.CursedVIP;
import id.cursedcraft.cursedvip.service.VipService;
import id.cursedcraft.cursedvip.util.HelpUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.logging.Level;

public class CmdCursedVip implements CommandExecutor {
    private final CursedVIP plugin;

    public CmdCursedVip(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        HelpUtil help = new HelpUtil(plugin);
        if (args.length == 0) {
            help.sendHelp(sender, "help");
            return true;
        }
        VipService vip = plugin.getVipService();
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add":
            case "a": {
                if (!check(sender, "cursedvip.addvip")) {
                    return true;
                }
                if (args.length != 3) {
                    help.sendHelp(sender, "add");
                    return true;
                }
                String group = group(sender, args[1]);
                Integer days = group == null ? null : plugin.parseDays(sender, args[2]);
                if (days != null) {
                    vip.addDaysToGroup(sender, group, days);
                }
                return true;
            }
            case "give":
            case "g": {
                if (!check(sender, "cursedvip.givevip")) {
                    return true;
                }
                if (args.length != 4) {
                    help.sendHelp(sender, "give");
                    return true;
                }
                Player p = player(sender, args[1]);
                String group = p == null ? null : group(sender, args[2]);
                Integer days = group == null ? null : plugin.parseDays(sender, args[3]);
                if (days != null) {
                    vip.give(sender, p, group, days);
                }
                return true;
            }
            case "changedays":
            case "cd": {
                if (!check(sender, "cursedvip.changedays")) {
                    return true;
                }
                if (args.length != 4) {
                    help.sendHelp(sender, "changedays");
                    return true;
                }
                Player p = player(sender, args[1]);
                String group = p == null ? null : group(sender, args[2]);
                Integer days = group == null ? null : plugin.parseDays(sender, args[3]);
                if (days != null) {
                    vip.changeDays(sender, p, group, days);
                }
                return true;
            }
            case "newkey":
            case "nk": {
                if (!check(sender, "cursedvip.newkey")) {
                    return true;
                }
                if (args.length != 3) {
                    help.sendHelp(sender, "newkey");
                    return true;
                }
                String group = group(sender, args[1]);
                Integer days = group == null ? null : plugin.parseDays(sender, args[2]);
                if (days != null) {
                    vip.newKey(sender, group, days);
                }
                return true;
            }
            case "keys":
            case "key":
            case "k":
                if (check(sender, "cursedvip.keys")) {
                    vip.listKeys(sender);
                }
                return true;
            case "delkeys":
            case "dks":
                if (!check(sender, "cursedvip.delkeys")) {
                    return true;
                }
                if (args.length != 1) {
                    help.sendHelp(sender, "delkeys");
                    return true;
                }
                vip.deleteAllKeys(sender);
                return true;
            case "delkey":
            case "dk":
                if (!check(sender, "cursedvip.delkey")) {
                    return true;
                }
                if (args.length != 2) {
                    help.sendHelp(sender, "delkey");
                    return true;
                }
                vip.deleteKey(sender, args[1]);
                return true;
            case "remove":
            case "rm":
            case "r": {
                if (!check(sender, "cursedvip.removevip")) {
                    return true;
                }
                if (args.length != 2 && args.length != 3) {
                    help.sendHelp(sender, "remove");
                    return true;
                }
                Player p = player(sender, args[1]);
                if (p == null) {
                    return true;
                }
                String group = null;
                if (args.length == 3) {
                    group = group(sender, args[2]);
                    if (group == null) {
                        return true;
                    }
                }
                vip.remove(sender, p, group);
                return true;
            }
            case "info":
            case "i": {
                if (!check(sender, "cursedvip.info")) {
                    return true;
                }
                if (args.length != 2) {
                    help.sendHelp(sender, "info");
                    return true;
                }
                Player p = player(sender, args[1]);
                if (p != null) {
                    vip.info(sender, p);
                }
                return true;
            }
            case "resetcooldown":
            case "resetcd":
            case "rcooldown":
            case "rcd": {
                if (!check(sender, "cursedvip.resetcooldown")) {
                    return true;
                }
                if (args.length != 2) {
                    help.sendHelp(sender, "resetcooldown");
                    return true;
                }
                Player p = player(sender, args[1]);
                if (p != null) {
                    vip.resetCooldown(sender, p);
                }
                return true;
            }
            case "reload":
                if (!check(sender, "cursedvip.reload")) {
                    return true;
                }
                if (args.length != 1) {
                    help.sendHelp(sender, "reload");
                    return true;
                }
                try {
                    plugin.reloadAll();
                    plugin.send(sender, plugin.msg("reload") + "!");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Reload failed", e);
                    plugin.send(sender, ChatColor.RED + "Reload failed, see console.");
                }
                return true;
            case "help":
            case "h":
                help.sendHelp(sender, "help");
                return true;
            default:
                plugin.send(sender, plugin.msg("invalid_command") + "!");
                sender.sendMessage(plugin.msg("help_hint"));
                return true;
        }
    }

    private boolean check(CommandSender sender, String node) {
        if (plugin.hasPerm(sender, node, false)) {
            return true;
        }
        plugin.send(sender, plugin.msg("no_permission") + "!");
        return false;
    }

    private Player player(CommandSender sender, String name) {
        Player p = plugin.findPlayer(name);
        if (p == null) {
            plugin.send(sender, plugin.msg("player_not_found") + "!");
        }
        return p;
    }

    private String group(CommandSender sender, String name) {
        String g = plugin.matchVipGroup(name);
        if (g == null) {
            plugin.send(sender, plugin.msg("group_not_found") + "!");
        }
        return g;
    }
}

package id.cursedcraft.cursedvip.util;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class HelpUtil {
    private final CursedVIP plugin;

    public HelpUtil(CursedVIP plugin) {
        this.plugin = plugin;
    }

    private void usage(CommandSender sender, String text) {
        sender.sendMessage(plugin.prefix() + plugin.color("&fUsage : &e" + text));
    }

    public void sendHelp(CommandSender sender, String type) {
        String name = "<" + plugin.getMessage("name") + ">";
        String group = "<" + plugin.getMessage("group") + ">";
        String days = "<" + plugin.getMessage("days") + ">";
        switch (type) {
            case "add":
                usage(sender, "/cursedvip add &f" + group + " " + days);
                return;
            case "give":
                usage(sender, "/cursedvip give &f" + name + " " + group + " " + days);
                return;
            case "changedays":
                usage(sender, "/cursedvip changedays &f" + name + " " + group + " " + days);
                return;
            case "newkey":
                usage(sender, "/cursedvip newkey &f" + group + " " + days);
                return;
            case "delkey":
                usage(sender, "/cursedvip delkey &f<key>");
                return;
            case "delkeys":
                usage(sender, "/cursedvip delkeys");
                return;
            case "remove":
                usage(sender, "/cursedvip remove &f" + name + " &for &e/cursedvip remove &f" + name + " " + group);
                return;
            case "info":
                usage(sender, "/cursedvip info &f" + name);
                return;
            case "resetcooldown":
                usage(sender, "/cursedvip resetcooldown &f" + name);
                return;
            case "reload":
                usage(sender, "/cursedvip reload");
                return;
            default:
                sendFullHelp(sender);
        }
    }

    private void sendFullHelp(CommandSender sender) {
        sender.sendMessage(plugin.prefix() + plugin.color("&6Commands of CursedVIP:"));
        if (sender instanceof Player) {
            line(sender, "cursedvip.usekey", true, "/usekey", "Use a VIP key.");
            line(sender, "cursedvip.viptime", true, "/viptime", "Shows the expiration date of VIPs.");
            line(sender, "cursedvip.changevip", true, "/changevip", "Change your active VIP group.");
        }
        line(sender, "cursedvip.newkey", false, "/cursedvip newkey", "Creates a new key with x days.");
        line(sender, "cursedvip.keys", false, "/cursedvip keys", "View the existing keys.");
        line(sender, "cursedvip.delkeys", false, "/cursedvip delkeys", "Delete all keys.");
        line(sender, "cursedvip.delkey", false, "/cursedvip delkey", "Delete specific key.");
        line(sender, "cursedvip.removevip", false, "/cursedvip remove", "Remove VIPs from player.");
        line(sender, "cursedvip.changedays", false, "/cursedvip changedays", "Change the days of a VIP.");
        line(sender, "cursedvip.info", false, "/cursedvip info", "See the information of a VIP.");
        line(sender, "cursedvip.givevip", false, "/cursedvip give", "Give VIP to a player.");
        line(sender, "cursedvip.addvip", false, "/cursedvip add", "Add more days to all players of a group.");
        line(sender, "cursedvip.resetcooldown", false, "/cursedvip resetcooldown", "Remove player ChangeVIP cooldown.");
        line(sender, "cursedvip.reload", false, "/cursedvip reload", "Reload config and language file.");
        line(sender, "cursedvip.help", true, "/cursedvip help", "Show the plugin commands.");
        sender.sendMessage(ChatColor.GRAY + "CursedVIP v" + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.GRAY + "Author : Aneryan (newbie029)");
    }

    private void line(CommandSender sender, String node, boolean userNode, String command, String description) {
        if (plugin.hasPerm(sender, node, userNode)) {
            sender.sendMessage(ChatColor.YELLOW + command + " " + ChatColor.WHITE + "- " + description);
        }
    }
}

package id.cursedcraft.cursedvip.placeholders;

import id.cursedcraft.cursedvip.CursedVIP;
import id.cursedcraft.cursedvip.storage.VipData;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * %CursedVIP_is_vip%, %CursedVIP_active_vip%, %CursedVIP_days_left%, %CursedVIP_time_left%, %CursedVIP_expires%.
 * Reads only the in-memory cache, so it is safe from any thread and never hits the database.
 */
public class CursedPAPI extends PlaceholderExpansion {

    private final CursedVIP plugin;

    public CursedPAPI(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    // no @Override: persist() only exists in newer PlaceholderAPI versions
    public boolean persist() {
        return true;
    }

    @Override
    public String getAuthor() {
        return "Aneryan(newbie029)";
    }

    @Override
    public String getIdentifier() {
        return "CursedVIP";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player p, String identifier) {
        if (p == null) {
            return "";
        }
        VipData d = plugin.getCached(p.getUniqueId());
        boolean vip = d != null && d.isVip();
        long left = vip ? d.getRemaining(d.getActive(), System.currentTimeMillis()) : 0L;
        switch (identifier.toLowerCase(Locale.ROOT)) {
            case "is_vip":
                return vip ? "Yes" : "No";
            case "active_vip":
                return vip ? d.getActive().toUpperCase(Locale.ROOT) : "Not VIP";
            case "days_left":
                // rounded up: 1 hour left still shows 1 day
                return Long.toString((left + VipData.DAY_MS - 1) / VipData.DAY_MS);
            case "time_left":
                return vip ? plugin.formatRemaining(left) : "0";
            case "expires":
                return vip ? plugin.formatDate(d.getExpiresAt()) : "-";
            default:
                return null;
        }
    }
}

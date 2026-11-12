package id.cursedcraft.cursedvip.updater;

import org.bukkit.configuration.Configuration;

/** Adds keys that exist in the bundled defaults but are missing from a user's file. */
public final class Updater {

    private Updater() {
    }

    /** @return number of keys added; existing values are never touched. */
    public static int mergeMissing(Configuration target, Configuration defaults) {
        int added = 0;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key) || target.contains(key)) {
                continue;
            }
            target.set(key, defaults.get(key));
            added++;
        }
        return added;
    }
}

package id.cursedcraft.cursedvip.service;

import id.cursedcraft.cursedvip.CursedVIP;
import id.cursedcraft.cursedvip.storage.KeyData;
import id.cursedcraft.cursedvip.storage.Storage;
import id.cursedcraft.cursedvip.storage.VipData;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * All VIP business logic. Public methods are called from the server thread; the work runs through
 * {@link CursedVIP#runStorage} (inline for flatfile, on the database thread for MySQL) and every
 * Bukkit/Vault side effect goes back to the server thread via {@link CursedVIP#sync}.
 * <p>
 * Every change goes through {@link Storage#update} so it is read and written atomically.
 */
public final class VipService {
    /** Wait after a wrong key, so keys cannot be guessed by spamming /usekey. */
    private static final long WRONG_KEY_DELAY_MS = 3_000L;

    private final CursedVIP plugin;
    private final Map<UUID, Long> wrongKeyAt = new ConcurrentHashMap<>();

    public VipService(CursedVIP plugin) {
        this.plugin = plugin;
    }

    private Storage storage() {
        return plugin.getStorage();
    }

    private static long days(int days) {
        return days * VipData.DAY_MS;
    }

    /** What happened inside one {@link Storage#update}; read after the write. */
    private static final class Change {
        final long now = System.currentTimeMillis();
        String before;
        final List<String> expired = new ArrayList<>();
        /** Message key when the action was refused. */
        String error;
    }

    // ------------------------------------------------------------------ expiry

    /** Inside a mutation: ends expired groups and starts the next queued one. @return whether data changed */
    private boolean settle(VipData d, Change c) {
        c.before = d.getActive();
        c.expired.addAll(d.expire(c.now, plugin.getVipGroups()));
        return !c.expired.isEmpty() || !Objects.equals(c.before, d.getActive());
    }

    /** After the write: refresh the cache, switch the Vault group if the active VIP changed, tell about expiries. */
    private void afterWrite(Player p, Change c, VipData d) {
        plugin.updateCache(p.getUniqueId(), d);
        final String active = d.getActive();
        if (!Objects.equals(c.before, active)) {
            plugin.sync(() -> {
                if (active == null) {
                    plugin.applyDefaultGroup(p);
                } else {
                    plugin.applyVipGroup(p, active);
                }
            });
        }
        if (!c.expired.isEmpty()) {
            final List<String> expired = c.expired;
            plugin.syncLater(() -> {
                if (p.isOnline()) {
                    for (String g : expired) {
                        plugin.send(p, plugin.msg("vip_expired", "%group%", upper(g)) + ".");
                    }
                }
            }, 80L);
        }
    }

    /** Runs expiry and returns the player's current data. Storage thread. */
    private VipData settled(Player p) throws Exception {
        Change c = new Change();
        VipData d = storage().update(p.getUniqueId(), data -> settle(data, c));
        afterWrite(p, c, d);
        return d;
    }

    /**
     * Periodic / join check.
     *
     * @param enforceGroup {@code true} on join: put the player back in their active VIP group even when an
     *                     admin changed their groups by hand
     */
    public void check(Player p, boolean enforceGroup) {
        plugin.runStorage(() -> {
            VipData d = settled(p);
            if (d.isVip()) {
                if (enforceGroup) {
                    final String active = d.getActive();
                    plugin.sync(() -> {
                        if (p.isOnline() && !plugin.hasOnlyVipGroup(p, active)) {
                            plugin.applyVipGroup(p, active);
                            plugin.getLogger().info("Restored VIP group " + active + " for " + p.getName() + ".");
                        }
                    });
                }
            } else if (plugin.getConfig().getBoolean("remove_non_vip")) {
                plugin.sync(() -> {
                    if (p.isOnline() && plugin.isInVipGroup(p)) {
                        plugin.applyDefaultGroup(p);
                    }
                });
            }
        });
    }

    /** Called on quit. */
    public void forget(UUID uuid) {
        wrongKeyAt.remove(uuid);
    }

    // ------------------------------------------------------------------ granting

    /** Storage thread. */
    private void grant(Player p, String group, int days, CommandSender giver, boolean viaKey) throws Exception {
        final boolean activate = plugin.getConfig().getBoolean("set_vip_on_acquire");
        Change c = new Change();
        VipData d = storage().update(p.getUniqueId(), data -> {
            settle(data, c);
            data.add(group, days(days), c.now);
            if (activate) {
                data.activate(group, c.now);
            }
            return true;
        });
        afterWrite(p, c, d);

        String g = upper(group);
        String n = Integer.toString(days);
        if (plugin.isBroadcastVipGive()) {
            plugin.broadcast(plugin.msg("vip_acquired_broadcast", "%name%", p.getName(), "%group%", g, "%days%", n) + "!");
        } else {
            plugin.send(p, plugin.msg("vip_acquired", "%group%", g, "%days%", n) + "!");
            String given = plugin.msg("gived_vip", "%group%", g, "%days%", n, "%name%", p.getName()) + "!";
            if (viaKey) {
                plugin.getLogger().info(ChatColor.stripColor(given));
            } else if (giver != null && giver != p) {
                plugin.send(giver, given);
            }
        }
    }

    public void give(CommandSender sender, Player target, String group, int days) {
        plugin.runStorage(sender, () -> grant(target, group, days, sender, false));
    }

    public void useKey(Player p, String rawKey) {
        final UUID id = p.getUniqueId();
        Long last = wrongKeyAt.get(id);
        long wait = last == null ? 0L : WRONG_KEY_DELAY_MS - (System.currentTimeMillis() - last);
        if (wait > 0) {
            plugin.send(p, plugin.msg("key_cooldown", "%seconds%", Long.toString((wait + 999) / 1000)) + "!");
            return;
        }
        final String key = rawKey.toUpperCase(Locale.ROOT);
        if (!plugin.isValidKeyFormat(key)) {
            wrongKey(p);
            return;
        }
        if (!plugin.markKeyInProcess(key)) {
            plugin.send(p, plugin.msg("code_being_process") + "!");
            return;
        }
        plugin.runStorage(p, () -> {
            try {
                KeyData k = storage().getKey(key);
                if (k == null) {
                    wrongKey(p);
                    return;
                }
                String group = plugin.matchVipGroup(k.getGroup());
                if (group == null || k.getDays() < 1) {
                    plugin.getLogger().warning("Key " + key + " points to unknown group '" + k.getGroup()
                            + "' or invalid days (" + k.getDays() + "); key kept.");
                    plugin.send(p, plugin.msg("group_not_found") + "!");
                    return;
                }
                // Deleting first makes redemption atomic, also across servers sharing one database.
                if (!storage().deleteKey(key)) {
                    wrongKey(p);
                    return;
                }
                wrongKeyAt.remove(id);
                try {
                    grant(p, group, k.getDays(), null, true);
                } catch (Exception e) {
                    // the key is already deleted: put it back so the player does not lose it
                    try {
                        storage().createKey(key, k.getGroup(), k.getDays());
                    } catch (Exception restoreFailed) {
                        plugin.getLogger().severe("Could not restore key " + key + " (" + k.getGroup() + ", "
                                + k.getDays() + " days) for " + p.getName() + " after a failed redeem. Recreate it by hand!");
                    }
                    throw e;
                }
                if (plugin.getConfig().getBoolean("logging.usekey")) {
                    try {
                        storage().logUseKey(id, key, group, k.getDays());
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Could not log usekey", e);
                    }
                }
            } finally {
                plugin.unmarkKeyInProcess(key);
            }
        });
    }

    private void wrongKey(Player p) {
        wrongKeyAt.put(p.getUniqueId(), System.currentTimeMillis());
        plugin.send(p, plugin.msg("key_not_found") + "!");
    }

    public void addDaysToGroup(CommandSender sender, String group, int days) {
        plugin.runStorage(sender, () -> {
            storage().addTimeToGroup(group, days(days));
            plugin.send(sender, plugin.msg("added_vip", "%days%", Integer.toString(days), "%group%", upper(group)) + ".");
            plugin.sync(plugin::checkOnlinePlayers);
        });
    }

    // ------------------------------------------------------------------ admin edits

    public void changeDays(CommandSender sender, Player target, String group, int days) {
        plugin.runStorage(sender, () -> {
            Change c = new Change();
            VipData d = storage().update(target.getUniqueId(), data -> {
                boolean changed = settle(data, c);
                if (!data.isVip()) {
                    c.error = "not_vip";
                    return changed;
                }
                data.setRemaining(group, days(days), c.now);
                return true;
            });
            afterWrite(target, c, d);
            if (c.error != null) {
                plugin.send(sender, target.getName() + " " + plugin.msg("not_vip") + "!");
                return;
            }
            String g = upper(group);
            String n = Integer.toString(days);
            plugin.send(target, plugin.msg("changed_days", "%admin%", sender.getName(), "%group%", g, "%days%", n) + "!");
            if (sender != target) {
                plugin.send(sender, plugin.msg("console_changed_days", "%name%", target.getName(), "%group%", g, "%days%", n) + "!");
            }
        });
    }

    /** @param group {@code null} removes every VIP group. */
    public void remove(CommandSender sender, Player target, String group) {
        plugin.runStorage(sender, () -> {
            Change c = new Change();
            VipData d = storage().update(target.getUniqueId(), data -> {
                boolean changed = settle(data, c);
                if (!data.isVip()) {
                    c.error = "not_vip";
                    return changed;
                }
                if (group != null && !data.owns(group)) {
                    c.error = "player_no_vip_type";
                    return changed;
                }
                if (group == null) {
                    data.clear();
                } else {
                    data.remove(group, c.now, plugin.getVipGroups());
                }
                return true;
            });
            afterWrite(target, c, d);
            if ("not_vip".equals(c.error)) {
                plugin.send(sender, target.getName() + " " + plugin.msg("not_vip") + "!");
            } else if (c.error != null) {
                plugin.send(sender, plugin.msg(c.error, "%name%", target.getName(), "%group%", upper(group)) + "!");
            } else if (!d.isVip()) {
                plugin.send(target, plugin.msg("removed_vip", "%admin%", sender.getName()) + "!");
                if (sender != target) {
                    plugin.send(sender, plugin.msg("console_removed_vip", "%name%", target.getName()) + "!");
                }
            } else {
                plugin.send(target, plugin.msg("removed_specific_vip", "%admin%", sender.getName(), "%group%", upper(group)) + "!");
                if (sender != target) {
                    plugin.send(sender, plugin.msg("console_removed_specific_vip", "%name%", target.getName(), "%group%", upper(group)) + "!");
                }
            }
        });
    }

    /** Shows VIP info of {@code target} to {@code sender} (/viptime when they are the same player). */
    public void info(CommandSender sender, Player target) {
        final boolean self = sender == target;
        plugin.runStorage(sender, () -> {
            VipData d = settled(target);
            if (!d.isVip()) {
                plugin.send(sender, self ? plugin.msg("player_not_vip") + "!" : target.getName() + " " + plugin.msg("not_vip") + "!");
                return;
            }
            long now = System.currentTimeMillis();
            List<String> lines = new ArrayList<>();
            lines.add(plugin.prefix() + (self ? "" : target.getName() + " - ") + plugin.msg("vip_time") + ":");
            lines.add(plugin.msg("current_vip") + " " + upper(d.getActive()));
            lines.add(plugin.msg("expires_on") + " " + plugin.formatDate(d.getExpiresAt()));
            for (String g : plugin.getVipGroups()) {
                if (d.owns(g)) {
                    lines.add(ChatColor.YELLOW + upper(g) + ChatColor.WHITE + " - " + plugin.msg("days_left") + ": "
                            + plugin.formatRemaining(d.getRemaining(g, now)));
                }
            }
            plugin.sendRaw(sender, lines);
        });
    }

    public void changeVip(Player p, String group) {
        final boolean limit = plugin.getConfig().getBoolean("limit_changevip");
        final boolean bypass = plugin.hasPerm(p, "cursedvip.changebypass", false);
        final long cooldownMs = Math.max(0L, plugin.getConfig().getLong("changevip_cooldown")) * 60000L;
        plugin.runStorage(p, () -> {
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            if (limit && !bypass) {
                Long last = storage().getCooldown(id);
                if (last != null && now - last < cooldownMs) {
                    plugin.send(p, plugin.msg("changevip_cooldown", "%cooldown%", plugin.formatRemaining(cooldownMs - (now - last))) + "!");
                    return;
                }
            }
            Change c = new Change();
            VipData d = storage().update(id, data -> {
                boolean changed = settle(data, c);
                if (!data.isVip()) {
                    c.error = "player_not_vip";
                } else if (!data.owns(group)) {
                    c.error = "no_vip_type";
                } else if (group.equals(data.getActive())) {
                    c.error = "error_group_same";
                } else {
                    data.activate(group, c.now);
                    return true;
                }
                return changed;
            });
            afterWrite(p, c, d);
            if (c.error != null) {
                plugin.send(p, plugin.msg(c.error) + "!");
                return;
            }
            if (limit) {
                storage().setCooldown(id, now);
            }
            plugin.send(p, plugin.msg("group_changed") + "!");
        });
    }

    public void resetCooldown(CommandSender sender, Player target) {
        plugin.runStorage(sender, () -> {
            boolean removed = storage().removeCooldown(target.getUniqueId());
            plugin.send(sender, plugin.msg(removed ? "cooldown_reset" : "cooldown_not_found", "%name%", target.getName()) + "!");
        });
    }

    // ------------------------------------------------------------------ keys

    public void newKey(CommandSender sender, String group, int days) {
        plugin.runStorage(sender, () -> {
            for (int attempt = 0; attempt < 50; attempt++) {
                String key = plugin.generateKey();
                if (storage().createKey(key, group, days)) {
                    plugin.send(sender, "Key: " + ChatColor.LIGHT_PURPLE + key + ChatColor.WHITE + " (" + upper(group) + ") - "
                            + ChatColor.BLUE + days + ChatColor.WHITE + " " + plugin.msg("days_with_vip") + ".");
                    return;
                }
            }
            plugin.send(sender, ChatColor.RED + "Could not generate a unique key, increase key_length.");
        });
    }

    public void listKeys(CommandSender sender) {
        plugin.runStorage(sender, () -> {
            List<KeyData> keys = storage().listKeys();
            if (keys.isEmpty()) {
                plugin.send(sender, plugin.msg("no_keys_found_create") + ".");
                return;
            }
            List<String> lines = new ArrayList<>();
            lines.add(plugin.prefix() + plugin.msg("list_keys") + ":");
            String daysWord = capitalize(plugin.msg("days"));
            for (KeyData k : keys) {
                lines.add(ChatColor.WHITE + "Key: " + ChatColor.LIGHT_PURPLE + k.getKey() + ChatColor.WHITE + " ("
                        + upper(k.getGroup()) + ") - " + daysWord + ": " + ChatColor.BLUE + k.getDays());
            }
            plugin.sendRaw(sender, lines);
        });
    }

    public void deleteKey(CommandSender sender, String rawKey) {
        final String key = rawKey.toUpperCase(Locale.ROOT);
        if (!plugin.isValidKeyFormat(key)) {
            plugin.send(sender, plugin.msg("key_not_found") + "!");
            return;
        }
        plugin.runStorage(sender, () -> {
            if (storage().deleteKey(key)) {
                plugin.send(sender, plugin.msg("key_deleted", "%key%", key) + "!");
            } else {
                plugin.send(sender, plugin.msg("key_not_found") + "!");
            }
        });
    }

    public void deleteAllKeys(CommandSender sender) {
        plugin.runStorage(sender, () -> {
            storage().deleteAllKeys();
            plugin.send(sender, plugin.msg("deleted_all_keys") + "!");
        });
    }

    // ------------------------------------------------------------------ helpers

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}

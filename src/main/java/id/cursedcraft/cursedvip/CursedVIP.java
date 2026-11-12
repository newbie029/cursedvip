package id.cursedcraft.cursedvip;

import id.cursedcraft.cursedvip.commands.CmdChangeVip;
import id.cursedcraft.cursedvip.commands.CmdCursedVip;
import id.cursedcraft.cursedvip.commands.CmdUseKey;
import id.cursedcraft.cursedvip.commands.CmdVipTime;
import id.cursedcraft.cursedvip.placeholders.CursedPAPI;
import id.cursedcraft.cursedvip.service.VipService;
import id.cursedcraft.cursedvip.storage.FlatfileStorage;
import id.cursedcraft.cursedvip.storage.MySQLStorage;
import id.cursedcraft.cursedvip.storage.Storage;
import id.cursedcraft.cursedvip.storage.VipData;
import id.cursedcraft.cursedvip.updater.Updater;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class CursedVIP extends JavaPlugin implements Listener {
    public static final int MIN_DAYS = 1;
    public static final int MAX_DAYS = 99999;
    private static final String KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 10;
    private static final String DEFAULT_DATE_FORMAT = "dd/MM/yyyy HH:mm";

    public static Permission perms = null;

    private final SecureRandom random = new SecureRandom();
    private final Set<String> keysInProcess = ConcurrentHashMap.newKeySet();
    private final Map<UUID, VipData> cache = new ConcurrentHashMap<>();

    private FileConfiguration language;
    private FileConfiguration defaultLanguage;
    private volatile List<String> vipGroups = Collections.emptyList();
    private volatile boolean broadcastVipGive;
    private volatile DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
    private Storage storage;
    private ExecutorService dbExecutor;
    private BukkitTask checkTask;
    private VipService vipService;

    /** Work that touches storage; may throw, errors are logged. */
    public interface StorageTask {
        void run() throws Exception;
    }

    // ================================================================== lifecycle

    @Override
    public void onEnable() {
        getLogger().info("Author: Aneryan (newbie029)");
        getLogger().info("SpigotMC: https://www.spigotmc.org/members/aneryan.136195/");
        try {
            loadConfiguration();
            loadLanguage();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load config/language files, disabling.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!setupStorage()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        vipService = new VipService(this);

        registerCommand("usekey", new CmdUseKey(this));
        registerCommand("viptime", new CmdVipTime(this));
        registerCommand("changevip", new CmdChangeVip(this));
        registerCommand("cursedvip", new CmdCursedVip(this));
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Processing Hook...");
        if (setupPermissions()) {
            onPermissionsReady();
        } else {
            // The permission plugin may register its Vault provider after us; retry once the server has started.
            getServer().getScheduler().runTask(this, () -> {
                if (setupPermissions()) {
                    onPermissionsReady();
                } else {
                    getLogger().severe("No Vault permission provider found (install Vault + a permission plugin). Disabling...");
                    getServer().getPluginManager().disablePlugin(this);
                }
            });
        }

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new CursedPAPI(this).register();
            getLogger().info("PlaceholderAPI found. Hooked.");
        } else {
            getLogger().info("PlaceholderAPI not found, placeholders disabled.");
        }
    }

    private void onPermissionsReady() {
        getLogger().info("Hooked to Vault (Permission: " + perms.getName() + ").");
        warnMissingGroups();
        startChecker();
        checkOnlinePlayers();
    }

    @Override
    public void onDisable() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    getLogger().warning("Database tasks did not finish within 10 seconds.");
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dbExecutor = null;
        }
        if (storage != null) {
            storage.close();
            storage = null;
        }
        cache.clear();
        keysInProcess.clear();
        getLogger().info("Author: Aneryan (newbie029)");
    }

    private void registerCommand(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command /" + name + " missing from plugin.yml");
            return;
        }
        cmd.setExecutor(executor);
    }

    // ================================================================== config & language

    private void loadConfiguration() throws IOException {
        File cfg = new File(getDataFolder(), "config.yml");
        if (!cfg.exists()) {
            getDataFolder().mkdirs();
            try (InputStream in = getResource("config_template.yml")) {
                Files.copy(in, cfg.toPath());
            }
            getLogger().info("New config file created.");
        }
        reloadConfig();
        getLogger().info("Checking for config file update...");
        int added = Updater.mergeMissing(getConfig(), loadBundledYaml("config_template.yml"));
        if (added > 0) {
            saveConfig();
            getLogger().info(added + " config updates found and added!");
        } else {
            getLogger().info("No updates found.");
        }
        applyConfigValues();
    }

    private void applyConfigValues() {
        List<String> groups = new ArrayList<>();
        for (String raw : getConfig().getStringList("vip_groups")) {
            String g = raw == null ? "" : raw.trim();
            if (g.isEmpty()) {
                continue;
            }
            // '.' would split the YAML path in data.yml and lose the group's time
            if (g.indexOf('.') >= 0 || g.length() > 64) {
                getLogger().warning("Ignoring invalid VIP group name (no dots, max 64 chars): " + g);
                continue;
            }
            if (matchIn(groups, g) == null) {
                groups.add(g);
            }
        }
        vipGroups = Collections.unmodifiableList(groups);
        broadcastVipGive = getConfig().getBoolean("broadcast_vip_give");

        String pattern = getConfig().getString("date_format", DEFAULT_DATE_FORMAT);
        try {
            dateFormat = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid date_format '" + pattern + "', using " + DEFAULT_DATE_FORMAT);
            dateFormat = DateTimeFormatter.ofPattern(DEFAULT_DATE_FORMAT);
        }

        int keyLength = getConfig().getInt("key_length", 10);
        if (keyLength < MIN_KEY_LENGTH || keyLength > MAX_KEY_LENGTH) {
            getLogger().warning("key_length must be " + MIN_KEY_LENGTH + "-" + MAX_KEY_LENGTH + " (shorter keys can be guessed); using "
                    + getKeyLength() + ".");
        }
    }

    private void loadLanguage() throws IOException {
        defaultLanguage = loadBundledYaml("language_en.yml");
        File en = new File(getDataFolder(), "language_en.yml");
        if (!en.exists()) {
            saveResource("language_en.yml", false);
            getLogger().info("Saved language_en.yml");
        }
        String code = getConfig().getString("language", "en").trim();
        File file = new File(getDataFolder(), "language_" + code + ".yml");
        if (!file.exists()) {
            getLogger().warning("Language file " + file.getName() + " not found, using language_en.yml");
            file = en;
            code = "en";
        }
        language = YamlConfiguration.loadConfiguration(file);
        if ("en".equalsIgnoreCase(code)) {
            getLogger().info("Checking for language file update...");
            int added = Updater.mergeMissing(language, defaultLanguage);
            if (added > 0) {
                ((YamlConfiguration) language).save(file);
                getLogger().info(added + " language updates found and added!");
            } else {
                getLogger().info("No updates found.");
            }
        }
    }

    private YamlConfiguration loadBundledYaml(String name) throws IOException {
        InputStream in = getResource(name);
        if (in == null) {
            throw new IOException("Missing bundled resource " + name);
        }
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(r);
        }
    }

    /** /cursedvip reload */
    public void reloadAll() throws IOException {
        reloadConfig();
        applyConfigValues();
        loadLanguage();
        runStorage(storage::reload);
        warnMissingGroups();
        startChecker();
        checkOnlinePlayers();
    }

    // ================================================================== storage & threading

    private boolean setupStorage() {
        if (getConfig().getBoolean("MySQL.enable")) {
            MySQLStorage mysql = MySQLStorage.fromConfig(this);
            try {
                mysql.init();
            } catch (Exception e) {
                // No fallback to data.yml: it is empty on a MySQL server, so every VIP would lose their rank.
                getLogger().log(Level.SEVERE, "Could not connect to MySQL. CursedVIP is disabled until the database"
                        + " is reachable; fix it and restart. No VIP data was changed.", e);
                mysql.close();
                return false;
            }
            storage = mysql;
            dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CursedVIP-MySQL");
                t.setDaemon(true);
                return t;
            });
            getLogger().info("Connected to MySQL server!");
            return true;
        }
        getLogger().info("Using flatfile system.");
        FlatfileStorage file = new FlatfileStorage(this);
        try {
            file.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not load data.yml (fix or remove the file). Disabling to protect your data.", e);
            return false;
        }
        storage = file;
        return true;
    }

    public Storage getStorage() {
        return storage;
    }

    /** Runs storage work: inline on the server thread for flatfile, on the single DB thread for MySQL. */
    public void runStorage(StorageTask task) {
        runStorage(null, task);
    }

    /** Same as {@link #runStorage(StorageTask)}, and tells {@code who} when it fails instead of staying silent. */
    public void runStorage(CommandSender who, StorageTask task) {
        Runnable r = () -> {
            try {
                task.run();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Storage operation failed", e);
                if (who != null) {
                    send(who, ChatColor.RED + msg("storage_error"));
                }
            }
        };
        ExecutorService ex = dbExecutor;
        if (ex == null) {
            sync(r);
            return;
        }
        try {
            ex.execute(r);
        } catch (RejectedExecutionException e) {
            getLogger().warning("Plugin is shutting down, storage task dropped.");
        }
    }

    /** Runs on the server thread (immediately if already there). */
    public void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (isEnabled()) {
            getServer().getScheduler().runTask(this, r);
        }
    }

    public void syncLater(Runnable r, long ticks) {
        if (isEnabled()) {
            getServer().getScheduler().runTaskLater(this, r, ticks);
        }
    }

    private void startChecker() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        int minutes = getConfig().getInt("check_time", 10);
        if (minutes <= 0 || perms == null) {
            return;
        }
        checkTask = getServer().getScheduler().runTaskTimer(this, this::checkOnlinePlayers, 1200L * minutes, 1200L * minutes);
    }

    /** Server thread. */
    public void checkOnlinePlayers() {
        if (perms == null || vipService == null) {
            return;
        }
        for (Player p : getServer().getOnlinePlayers()) {
            vipService.check(p, false);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        // small delay so the permission plugin has loaded the player
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline() && perms != null) {
                vipService.check(p, getConfig().getBoolean("restore_vip_on_join", true));
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        cache.remove(id);
        if (vipService != null) {
            vipService.forget(id);
        }
    }

    // ================================================================== cache (PlaceholderAPI)

    public void updateCache(UUID uuid, VipData data) {
        if (data == null || data.isEmpty()) {
            cache.remove(uuid);
        } else {
            cache.put(uuid, data.copy());
        }
    }

    public VipData getCached(UUID uuid) {
        return cache.get(uuid);
    }

    // ================================================================== Vault

    private boolean setupPermissions() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        if (rsp == null) {
            return false;
        }
        perms = rsp.getProvider();
        return perms != null;
    }

    /** Vault fails silently when a group does not exist, so tell the admin at startup and on reload. */
    private void warnMissingGroups() {
        if (perms == null || !perms.hasGroupSupport()) {
            return;
        }
        String[] existing;
        try {
            existing = perms.getGroups();
        } catch (UnsupportedOperationException e) {
            return;
        }
        if (existing == null) {
            return;
        }
        List<String> known = Arrays.asList(existing);
        for (String g : vipGroups) {
            if (matchIn(known, g) == null) {
                getLogger().warning("VIP group '" + g + "' from vip_groups does not exist in " + perms.getName()
                        + ". Players will not get this rank until you create it.");
            }
        }
        String def = getDefaultGroup();
        if (!def.isEmpty() && matchIn(known, def) == null) {
            getLogger().warning("default_group '" + def + "' does not exist in " + perms.getName() + ".");
        }
    }

    /** Server thread. */
    public void removeVipGroups(Player p) {
        if (perms == null) {
            return;
        }
        String[] current = perms.getPlayerGroups(p);
        if (current != null) {
            for (String g : current) {
                if (matchVipGroup(g) != null) {
                    perms.playerRemoveGroup((String) null, p, g);
                }
            }
        }
        for (String g : vipGroups) {
            if (perms.playerInGroup((String) null, p, g)) {
                perms.playerRemoveGroup((String) null, p, g);
            }
        }
    }

    /** Server thread: puts the player in exactly this VIP group. */
    public void applyVipGroup(Player p, String group) {
        if (perms == null || group == null) {
            return;
        }
        removeVipGroups(p);
        perms.playerAddGroup((String) null, p, group);
    }

    /** Server thread: removes VIP groups and puts the player in default_group. */
    public void applyDefaultGroup(Player p) {
        if (perms == null) {
            return;
        }
        removeVipGroups(p);
        String def = getDefaultGroup();
        if (!def.isEmpty() && !perms.playerInGroup((String) null, p, def)) {
            perms.playerAddGroup((String) null, p, def);
        }
    }

    /** Server thread: {@code true} when the player is in {@code group} and in no other VIP group. */
    public boolean hasOnlyVipGroup(Player p, String group) {
        if (perms == null) {
            return true;
        }
        if (!perms.playerInGroup((String) null, p, group)) {
            return false;
        }
        for (String g : vipGroups) {
            if (!g.equalsIgnoreCase(group) && perms.playerInGroup((String) null, p, g)) {
                return false;
            }
        }
        return true;
    }

    public boolean isInVipGroup(Player p) {
        if (perms == null) {
            return false;
        }
        for (String g : vipGroups) {
            if (perms.playerInGroup((String) null, p, g)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPerm(CommandSender s, String node, boolean userNode) {
        return s.isOp() || s.hasPermission("cursedvip.admin") || s.hasPermission(node)
                || (userNode && s.hasPermission("cursedvip.user"));
    }

    // ================================================================== groups & keys

    public List<String> getVipGroups() {
        return vipGroups;
    }

    /** @return the configured group name matching {@code input} (case-insensitive), or {@code null}. */
    public String matchVipGroup(String input) {
        return matchIn(vipGroups, input);
    }

    private static String matchIn(List<String> groups, String input) {
        if (input == null) {
            return null;
        }
        String t = input.trim();
        for (String g : groups) {
            if (g.equalsIgnoreCase(t)) {
                return g;
            }
        }
        return null;
    }

    public String getDefaultGroup() {
        return getConfig().getString("default_group", "member").trim();
    }

    public boolean isBroadcastVipGive() {
        return broadcastVipGive;
    }

    public int getKeyLength() {
        int len = getConfig().getInt("key_length", MAX_KEY_LENGTH);
        return Math.max(MIN_KEY_LENGTH, Math.min(MAX_KEY_LENGTH, len));
    }

    public String generateKey() {
        int len = getKeyLength();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(KEY_CHARS.charAt(random.nextInt(KEY_CHARS.length())));
        }
        return sb.toString();
    }

    /** Accepts any 1-10 character alphanumeric key, so keys made with an older key_length stay usable. */
    public boolean isValidKeyFormat(String key) {
        if (key == null || key.isEmpty() || key.length() > 10) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (KEY_CHARS.indexOf(key.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    public boolean markKeyInProcess(String key) {
        return keysInProcess.add(key);
    }

    public void unmarkKeyInProcess(String key) {
        keysInProcess.remove(key);
    }

    public VipService getVipService() {
        return vipService;
    }

    // ================================================================== messages

    public String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }

    public String prefix() {
        return color(getConfig().getString("prefix", "&f[&6CursedVIP&f] "));
    }

    public String getMessage(String key) {
        String v = language != null ? language.getString(key) : null;
        if (v == null && defaultLanguage != null) {
            v = defaultLanguage.getString(key);
        }
        return v == null ? key : v.trim();
    }

    /** Colored message with {@code %placeholder%, value} replacement pairs. */
    public String msg(String key, String... replacements) {
        String m = getMessage(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            m = m.replace(replacements[i], replacements[i + 1] == null ? "" : replacements[i + 1]);
        }
        return color(m);
    }

    /** Sends prefix + text; safe from any thread. */
    public void send(CommandSender to, String text) {
        final String full = prefix() + ChatColor.WHITE + text;
        sync(() -> to.sendMessage(full));
    }

    /** Sends lines as-is; safe from any thread. */
    public void sendRaw(CommandSender to, List<String> lines) {
        final List<String> copy = new ArrayList<>(lines);
        sync(() -> {
            for (String l : copy) {
                to.sendMessage(l);
            }
        });
    }

    public void broadcast(String text) {
        final String full = prefix() + ChatColor.WHITE + text;
        sync(() -> getServer().broadcastMessage(full));
    }

    /** Parses a days argument, telling the sender what is wrong. @return {@code null} when invalid. */
    public Integer parseDays(CommandSender sender, String arg) {
        int days;
        try {
            days = Integer.parseInt(arg.trim());
        } catch (NumberFormatException e) {
            send(sender, msg("error_number") + "!");
            return null;
        }
        if (days < MIN_DAYS) {
            send(sender, msg("error_day0") + "!");
            return null;
        }
        if (days > MAX_DAYS) {
            send(sender, msg("error_days_max", "%max%", Integer.toString(MAX_DAYS)) + "!");
            return null;
        }
        return days;
    }

    /** Online player by exact name. No prefix matching: "Ste" must never hit "Steve". */
    public Player findPlayer(String name) {
        return getServer().getPlayerExact(name);
    }

    /** "12 days and 3 hour(s)" or "3 hour(s) and 20 minute(s)", rounded up to the minute. */
    public String formatRemaining(long ms) {
        long totalMinutes = (Math.max(0L, ms) + 59999L) / 60000L;
        long d = totalMinutes / 1440;
        long h = (totalMinutes % 1440) / 60;
        long m = totalMinutes % 60;
        String and = " " + msg("and") + " ";
        if (d > 0) {
            return d + " " + msg("days") + and + h + " " + msg("hours");
        }
        return h + " " + msg("hours") + and + m + " " + msg("mintues");
    }

    /** Formats epoch millis with config {@code date_format} in the server time zone. */
    public String formatDate(long epochMillis) {
        return dateFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}

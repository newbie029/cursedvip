package id.cursedcraft.cursedvip.storage;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * data.yml backend. Only used from the server thread.
 * <pre>
 * version: 2
 * vips:
 *   &lt;uuid&gt;:
 *     active: vip1
 *     expires: 1731400000000      # epoch millis
 *     paused:
 *       vip2: 864000000           # remaining millis, not ticking
 * keys:
 *   &lt;KEY&gt;: "group,days"
 * cooldown:
 *   &lt;uuid&gt;: 1731400000000       # last /changevip, epoch millis
 * </pre>
 */
public final class FlatfileStorage implements Storage {
    static final int SCHEMA_VERSION = 2;

    private final CursedVIP plugin;
    private File file;
    private YamlConfiguration data;
    private boolean saveScheduled;

    public FlatfileStorage(CursedVIP plugin) {
        this.plugin = plugin;
    }

    @Override
    public void init() throws Exception {
        file = new File(plugin.getDataFolder(), "data.yml");
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("data.yml", false);
            plugin.getLogger().info("Created data file.");
        }
        YamlConfiguration loaded = new YamlConfiguration();
        // Fail loudly instead of starting with an empty file that would overwrite the real data on next save.
        loaded.load(file);
        data = loaded;
        migrateIfNeeded();
        plugin.getLogger().info("Flatstorage loaded.");
    }

    // ------------------------------------------------------------------ migration 1.x -> 2

    private void migrateIfNeeded() throws IOException {
        int version = data.getInt("version", 1);
        if (version >= SCHEMA_VERSION) {
            return;
        }
        ConfigurationSection vips = data.getConfigurationSection("vips");
        if (vips == null || vips.getKeys(false).isEmpty()) {
            data.set("version", SCHEMA_VERSION);
            data.save(file);
            return;
        }
        File backup = new File(plugin.getDataFolder(), "data-v1-backup.yml");
        if (backup.exists()) {
            backup = new File(plugin.getDataFolder(), "data-v1-backup-" + System.currentTimeMillis() + ".yml");
        }
        Files.copy(file.toPath(), backup.toPath());
        plugin.getLogger().info("Migrating data.yml to the new format (backup: " + backup.getName() + ")...");
        ZoneId zone = ZoneId.systemDefault();
        int migrated = migrate(data, plugin.getVipGroups(), plugin::matchVipGroup, zone, LocalDate.now(zone));
        data.save(file);
        plugin.getLogger().info("Migration done: " + migrated + " VIP players converted.");
    }

    /** Converts every 1.x {@code vips} entry in place and sets the version. Keys and cooldowns need no change. */
    static int migrate(YamlConfiguration data, List<String> order, Function<String, String> canonical,
                       ZoneId zone, LocalDate today) {
        int migrated = 0;
        ConfigurationSection vips = data.getConfigurationSection("vips");
        if (vips != null) {
            for (String id : vips.getKeys(false)) {
                ConfigurationSection s = vips.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                Map<String, Integer> days = new LinkedHashMap<>();
                for (String k : s.getKeys(false)) {
                    if (!k.equals("start") && !k.equals("using") && s.get(k) instanceof Number) {
                        days.put(k, s.getInt(k));
                    }
                }
                VipData d = LegacyConverter.convert(s.getString("start"), s.getString("using"), days,
                        order, canonical, zone, today);
                vips.set(id, null);
                if (!d.isEmpty()) {
                    write(data, id, d);
                    migrated++;
                }
            }
        }
        data.set("version", SCHEMA_VERSION);
        return migrated;
    }

    // ------------------------------------------------------------------ storage

    @Override
    public void reload() throws Exception {
        flush();
        init();
    }

    @Override
    public void close() {
        flush();
    }

    /**
     * Writing the whole file is slow with thousands of players, so changes made in the same tick
     * are written once at the end of the tick.
     */
    private void save() {
        if (saveScheduled) {
            return;
        }
        if (!plugin.isEnabled()) {
            saveScheduled = true;
            flush();
            return;
        }
        saveScheduled = true;
        plugin.getServer().getScheduler().runTask(plugin, this::flush);
    }

    private void flush() {
        if (!saveScheduled) {
            return;
        }
        saveScheduled = false;
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save data.yml", e);
        }
    }

    private static String vipPath(Object uuid) {
        return "vips." + uuid;
    }

    @Override
    public VipData update(UUID uuid, Mutation mutation) {
        String id = uuid.toString();
        VipData d = readVip(id);
        boolean existed = d != null;
        if (!existed) {
            d = new VipData();
        }
        if (mutation.apply(d)) {
            if (!d.isEmpty()) {
                write(id, d);
                save();
            } else if (existed) {
                data.set(vipPath(id), null);
                save();
            }
        }
        return d;
    }

    private VipData readVip(String id) {
        ConfigurationSection s = data.getConfigurationSection(vipPath(id));
        if (s == null) {
            return null;
        }
        VipData d = new VipData();
        String active = s.getString("active");
        if (active != null) {
            String c = plugin.matchVipGroup(active);
            d.setActive(c != null ? c : active, s.getLong("expires"));
        }
        ConfigurationSection paused = s.getConfigurationSection("paused");
        if (paused != null) {
            for (String g : paused.getKeys(false)) {
                String c = plugin.matchVipGroup(g);
                d.setPaused(c != null ? c : g, paused.getLong(g));
            }
        }
        return d;
    }

    private void write(Object uuid, VipData d) {
        write(data, uuid, d);
    }

    private static void write(YamlConfiguration data, Object uuid, VipData d) {
        String p = vipPath(uuid);
        data.set(p, null);
        data.set(p + ".active", d.getActive());
        if (d.getActive() != null) {
            data.set(p + ".expires", d.getExpiresAt());
        }
        for (Map.Entry<String, Long> e : d.getPaused().entrySet()) {
            data.set(p + ".paused." + e.getKey(), e.getValue());
        }
    }

    @Override
    public int addTimeToGroup(String group, long ms) throws IOException {
        ConfigurationSection vips = data.getConfigurationSection("vips");
        if (vips == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int count = 0;
        for (String id : vips.getKeys(false)) {
            VipData d = readVip(id);
            if (d != null && d.owns(group)) {
                d.add(group, ms, now);
                write(id, d);
                count++;
            }
        }
        if (count > 0) {
            save();
        }
        return count;
    }

    @Override
    public boolean createKey(String key, String group, int days) throws IOException {
        if (data.contains("keys." + key)) {
            return false;
        }
        data.set("keys." + key, group + "," + days);
        save();
        return true;
    }

    @Override
    public KeyData getKey(String key) {
        return parseKey(key, data.getString("keys." + key));
    }

    private static KeyData parseKey(String key, String raw) {
        if (raw == null) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new KeyData(key, parts[0].trim(), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean deleteKey(String key) throws IOException {
        if (!data.contains("keys." + key)) {
            return false;
        }
        data.set("keys." + key, null);
        save();
        return true;
    }

    @Override
    public List<KeyData> listKeys() {
        List<KeyData> list = new ArrayList<>();
        ConfigurationSection keys = data.getConfigurationSection("keys");
        if (keys != null) {
            for (String k : keys.getKeys(false)) {
                KeyData kd = parseKey(k, keys.getString(k));
                if (kd != null) {
                    list.add(kd);
                }
            }
        }
        return list;
    }

    @Override
    public void deleteAllKeys() throws IOException {
        data.set("keys", null);
        save();
    }

    @Override
    public Long getCooldown(UUID uuid) {
        String p = "cooldown." + uuid;
        return data.contains(p) ? data.getLong(p) : null;
    }

    @Override
    public void setCooldown(UUID uuid, long time) throws IOException {
        data.set("cooldown." + uuid, time);
        save();
    }

    @Override
    public boolean removeCooldown(UUID uuid) throws IOException {
        String p = "cooldown." + uuid;
        if (!data.contains(p)) {
            return false;
        }
        data.set(p, null);
        save();
        return true;
    }

    @Override
    public void logUseKey(UUID uuid, String key, String group, int days) throws IOException {
        File log = new File(plugin.getDataFolder(), "log.txt");
        // same line format as 1.x, epoch millis appended at the end
        String line = "usekey|" + uuid + "|" + key + "|" + LegacyConverter.LEGACY_DATE.format(LocalDate.now()) + "|"
                + group + "|" + days + "|" + System.currentTimeMillis();
        try (Writer w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(log.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), StandardCharsets.UTF_8))) {
            w.write(line);
            w.write(System.lineSeparator());
        }
    }
}

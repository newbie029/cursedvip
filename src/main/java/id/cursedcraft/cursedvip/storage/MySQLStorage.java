package id.cursedcraft.cursedvip.storage;

import id.cursedcraft.cursedvip.CursedVIP;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL/MariaDB backend. Only used from the plugin's single database thread.
 * <p>
 * Schema v2 (all times are epoch millis, BIGINT):
 * <ul>
 *   <li>{@code cursedvip_players}: uuid, active group, expires_at</li>
 *   <li>{@code cursedvip_queue}: uuid, vip_group, remaining_ms (paused groups)</li>
 *   <li>{@code cursedvip_cooldowns}: uuid, last_change</li>
 *   <li>{@code cursedvip_key_log}: key redemption history</li>
 *   <li>{@code cursedvip_keys}: unchanged from 1.x</li>
 *   <li>{@code cursedvip_meta}: schema_version</li>
 * </ul>
 * 1.x tables ({@code cursedvip_vips}, {@code cursedvip_changes}, {@code cursedvip_log}) are migrated once
 * and then left untouched as a backup.
 */
public final class MySQLStorage implements Storage {
    static final int SCHEMA_VERSION = 2;

    /** Re-check a connection only after it sat idle this long; a check costs a round trip. */
    private static final long VALIDATE_AFTER_IDLE_MS = 30_000L;

    private final String url;
    private final String user;
    private final String password;
    private final Logger log;
    private final Supplier<List<String>> groups;
    private final Function<String, String> canonical;
    private Connection connection;
    private long lastUsed;

    public MySQLStorage(String url, String user, String password, Logger log,
                        Supplier<List<String>> groups, Function<String, String> canonical) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.log = log;
        this.groups = groups;
        this.canonical = canonical;
    }

    public static MySQLStorage fromConfig(CursedVIP plugin) {
        FileConfiguration c = plugin.getConfig();
        String url = "jdbc:mysql://" + c.getString("MySQL.host", "127.0.0.1").trim() + ":" + c.getInt("MySQL.port", 3306)
                + "/" + c.getString("MySQL.database", "minecraft").trim()
                + "?useSSL=" + c.getBoolean("MySQL.ssl")
                + "&useUnicode=true&characterEncoding=utf8"
                // without these a dead network blocks startup or the database thread forever
                + "&connectTimeout=10000&socketTimeout=30000";
        return new MySQLStorage(url, c.getString("MySQL.username", "").trim(), c.getString("MySQL.password", ""),
                plugin.getLogger(), plugin::getVipGroups, plugin::matchVipGroup);
    }

    private String canon(String group) {
        String c = canonical.apply(group);
        return c != null ? c : group;
    }

    @Override
    public void init() throws Exception {
        loadDriver();
        try (Statement st = conn().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_meta` (`name` VARCHAR(32) PRIMARY KEY, `value` VARCHAR(64) NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_players` (`uuid` VARCHAR(36) PRIMARY KEY, `active` VARCHAR(64) NULL, `expires_at` BIGINT NOT NULL DEFAULT 0) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_queue` (`uuid` VARCHAR(36) NOT NULL, `vip_group` VARCHAR(64) NOT NULL, `remaining_ms` BIGINT NOT NULL, PRIMARY KEY (`uuid`, `vip_group`)) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_cooldowns` (`uuid` VARCHAR(36) PRIMARY KEY, `last_change` BIGINT NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_key_log` (`id` BIGINT AUTO_INCREMENT PRIMARY KEY, `uuid` VARCHAR(36) NOT NULL, `key_code` VARCHAR(16) NOT NULL, `vip_group` VARCHAR(64) NOT NULL, `days` INT NOT NULL, `used_at` BIGINT NOT NULL) ENGINE=InnoDB");
            st.execute("CREATE TABLE IF NOT EXISTS `cursedvip_keys` (`key` VARCHAR(11) PRIMARY KEY, `group` VARCHAR(64), `days` INT)");
        }
        // 1.x created the keys table with VARCHAR(15) groups
        tryExecute("ALTER TABLE `cursedvip_keys` MODIFY `group` VARCHAR(64)");
        migrateIfNeeded();
    }

    private static void loadDriver() {
        // Spigot/Paper bundle Connector/J; the class name changed in 8.x.
        for (String driver : new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"}) {
            try {
                Class.forName(driver);
                return;
            } catch (ClassNotFoundException ignored) {
                // try next
            }
        }
    }

    @Override
    public void reload() {
        // nothing config-dependent in the schema anymore
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "Error closing MySQL connection", e);
        }
        connection = null;
    }

    /** Returns a live connection, reconnecting when the server dropped it (wait_timeout etc.). */
    private Connection conn() throws SQLException {
        long now = System.currentTimeMillis();
        if (connection == null || connection.isClosed()
                || (now - lastUsed > VALIDATE_AFTER_IDLE_MS && !connection.isValid(3))) {
            close();
            connection = DriverManager.getConnection(url, user, password);
        }
        lastUsed = now;
        return connection;
    }

    private void tryExecute(String sql) {
        try (Statement st = conn().createStatement()) {
            st.execute(sql);
        } catch (SQLException ignored) {
            // optional upgrade step
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (ResultSet rs = conn().getMetaData().getTables(conn().getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    /** Runs {@code work} in one transaction. */
    private <T> T inTransaction(SqlWork<T> work) throws SQLException {
        Connection c = conn();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    // ------------------------------------------------------------------ migration 1.x -> 2

    private void migrateIfNeeded() throws SQLException {
        final boolean legacy = tableExists("cursedvip_vips");
        inTransaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO `cursedvip_meta` (`name`,`value`) VALUES ('schema_version',?)")) {
                ps.setString(1, Integer.toString(legacy ? 1 : SCHEMA_VERSION));
                ps.executeUpdate();
            }
            int version;
            // FOR UPDATE: a second server starting at the same time waits here and then sees version 2
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT `value` FROM `cursedvip_meta` WHERE `name`='schema_version' FOR UPDATE")) {
                rs.next();
                version = Integer.parseInt(rs.getString(1).trim());
            }
            if (version >= SCHEMA_VERSION) {
                return null;
            }
            log.info("Migrating MySQL data from CursedVIP 1.x (old tables are kept as backup)...");
            int players = migrateVips(c);
            int cooldowns = tableExists("cursedvip_changes") ? migrateCooldowns(c) : 0;
            int logs = tableExists("cursedvip_log") ? migrateLogs(c) : 0;
            try (PreparedStatement ps = c.prepareStatement("UPDATE `cursedvip_meta` SET `value`=? WHERE `name`='schema_version'")) {
                ps.setString(1, Integer.toString(SCHEMA_VERSION));
                ps.executeUpdate();
            }
            log.info("Migration done: " + players + " VIP players, " + cooldowns + " cooldowns, "
                    + logs + " log entries. You can drop cursedvip_vips, cursedvip_changes and cursedvip_log"
                    + " once every server runs this version.");
            return null;
        });
    }

    private int migrateVips(Connection c) throws SQLException {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        int count = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `cursedvip_vips`")) {
            ResultSetMetaData md = rs.getMetaData();
            List<String> groupCols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String name = md.getColumnName(i);
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.equals("uuid") && !lower.equals("start") && !lower.equals("using")) {
                    groupCols.add(name);
                }
            }
            while (rs.next()) {
                String uuid = rs.getString("uuid");
                if (uuid == null) {
                    continue;
                }
                Map<String, Integer> days = new LinkedHashMap<>();
                for (String g : groupCols) {
                    days.put(g, rs.getInt(g));
                }
                VipData d = LegacyConverter.convert(rs.getString("start"), rs.getString("using"), days,
                        groups.get(), canonical, zone, today);
                if (!d.isEmpty()) {
                    write(c, uuid.trim(), d);
                    count++;
                }
            }
        }
        return count;
    }

    private int migrateCooldowns(Connection c) throws SQLException {
        int count = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT `uuid`,`date` FROM `cursedvip_changes`");
             PreparedStatement ins = c.prepareStatement(
                     "INSERT INTO `cursedvip_cooldowns` (`uuid`,`last_change`) VALUES (?,?) ON DUPLICATE KEY UPDATE `last_change`=VALUES(`last_change`)")) {
            while (rs.next()) {
                long time;
                try {
                    time = Long.parseLong(rs.getString("date").trim());
                } catch (NumberFormatException | NullPointerException e) {
                    continue;
                }
                ins.setString(1, rs.getString("uuid"));
                ins.setLong(2, time);
                ins.addBatch();
                count++;
            }
            ins.executeBatch();
        }
        return count;
    }

    private int migrateLogs(Connection c) throws SQLException {
        ZoneId zone = ZoneId.systemDefault();
        int count = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `cursedvip_log`");
             PreparedStatement ins = c.prepareStatement(
                     "INSERT INTO `cursedvip_key_log` (`uuid`,`key_code`,`vip_group`,`days`,`used_at`) VALUES (?,?,?,?,?)")) {
            while (rs.next()) {
                ins.setString(1, String.valueOf(rs.getString("uuid")));
                ins.setString(2, String.valueOf(rs.getString("key")));
                ins.setString(3, String.valueOf(rs.getString("group")));
                ins.setInt(4, rs.getInt("days"));
                ins.setLong(5, LegacyConverter.dateToMillis(rs.getString("data"), zone, 0L));
                ins.addBatch();
                count++;
            }
            ins.executeBatch();
        }
        return count;
    }

    // ------------------------------------------------------------------ VIP data

    @Override
    public VipData update(UUID uuid, Mutation mutation) throws SQLException {
        final String id = uuid.toString();
        return inTransaction(c -> {
            VipData d = new VipData();
            boolean existed;
            // FOR UPDATE keeps the row locked until commit, so another server waits instead of overwriting
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT `active`,`expires_at` FROM `cursedvip_players` WHERE `uuid`=? FOR UPDATE")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    existed = rs.next();
                    if (existed && rs.getString("active") != null) {
                        d.setActive(canon(rs.getString("active")), rs.getLong("expires_at"));
                    }
                }
            }
            if (existed) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT `vip_group`,`remaining_ms` FROM `cursedvip_queue` WHERE `uuid`=?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            d.setPaused(canon(rs.getString("vip_group")), rs.getLong("remaining_ms"));
                        }
                    }
                }
            }
            if (mutation.apply(d)) {
                if (!d.isEmpty()) {
                    write(c, id, d);
                } else if (existed) {
                    for (String table : new String[]{"cursedvip_players", "cursedvip_queue"}) {
                        try (PreparedStatement ps = c.prepareStatement("DELETE FROM `" + table + "` WHERE `uuid`=?")) {
                            ps.setString(1, id);
                            ps.executeUpdate();
                        }
                    }
                }
            }
            return d;
        });
    }

    private static void write(Connection c, String uuid, VipData d) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO `cursedvip_players` (`uuid`,`active`,`expires_at`) VALUES (?,?,?)"
                        + " ON DUPLICATE KEY UPDATE `active`=VALUES(`active`),`expires_at`=VALUES(`expires_at`)")) {
            ps.setString(1, uuid);
            ps.setString(2, d.getActive());
            ps.setLong(3, d.getExpiresAt());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM `cursedvip_queue` WHERE `uuid`=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        if (!d.getPaused().isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO `cursedvip_queue` (`uuid`,`vip_group`,`remaining_ms`) VALUES (?,?,?)")) {
                for (Map.Entry<String, Long> e : d.getPaused().entrySet()) {
                    ps.setString(1, uuid);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }

    @Override
    public int addTimeToGroup(String group, long ms) throws SQLException {
        return inTransaction(c -> {
            int count;
            // an already expired (not yet checked) active VIP is extended from its expiry, same as before
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE `cursedvip_players` SET `expires_at`=`expires_at`+? WHERE `active`=?")) {
                ps.setLong(1, ms);
                ps.setString(2, group);
                count = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE `cursedvip_queue` SET `remaining_ms`=`remaining_ms`+? WHERE `vip_group`=?")) {
                ps.setLong(1, ms);
                ps.setString(2, group);
                count += ps.executeUpdate();
            }
            return count;
        });
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean createKey(String key, String group, int days) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT IGNORE INTO `cursedvip_keys` (`key`,`group`,`days`) VALUES (?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, group);
            ps.setInt(3, days);
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public KeyData getKey(String key) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT * FROM `cursedvip_keys` WHERE `key`=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new KeyData(rs.getString("key"), rs.getString("group"), rs.getInt("days")) : null;
            }
        }
    }

    @Override
    public boolean deleteKey(String key) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM `cursedvip_keys` WHERE `key`=?")) {
            ps.setString(1, key);
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public List<KeyData> listKeys() throws SQLException {
        List<KeyData> list = new ArrayList<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM `cursedvip_keys`")) {
            while (rs.next()) {
                list.add(new KeyData(rs.getString("key"), rs.getString("group"), rs.getInt("days")));
            }
        }
        return list;
    }

    @Override
    public void deleteAllKeys() throws SQLException {
        try (Statement st = conn().createStatement()) {
            st.executeUpdate("DELETE FROM `cursedvip_keys`");
        }
    }

    // ------------------------------------------------------------------ cooldown & log

    @Override
    public Long getCooldown(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("SELECT `last_change` FROM `cursedvip_cooldowns` WHERE `uuid`=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    @Override
    public void setCooldown(UUID uuid, long time) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO `cursedvip_cooldowns` (`uuid`,`last_change`) VALUES (?,?) ON DUPLICATE KEY UPDATE `last_change`=VALUES(`last_change`)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, time);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean removeCooldown(UUID uuid) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement("DELETE FROM `cursedvip_cooldowns` WHERE `uuid`=?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    @Override
    public void logUseKey(UUID uuid, String key, String group, int days) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO `cursedvip_key_log` (`uuid`,`key_code`,`vip_group`,`days`,`used_at`) VALUES (?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, key);
            ps.setString(3, group);
            ps.setInt(4, days);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}

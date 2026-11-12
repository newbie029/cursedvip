package id.cursedcraft.cursedvip.storage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import static id.cursedcraft.cursedvip.storage.VipData.DAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Runs MySQLStorage against H2 in MySQL mode: real SQL, real transactions, but not a real MySQL server.
 */
public class MySQLStorageTest {
    private static final List<String> GROUPS = Arrays.asList("vip1", "vip2", "vvip+");
    private static final Function<String, String> CANON = s -> {
        for (String g : GROUPS) {
            if (g.equalsIgnoreCase(s)) {
                return g;
            }
        }
        return null;
    };
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private String url;
    private Connection keepAlive;
    private MySQLStorage storage;

    @Before
    public void setUp() throws Exception {
        url = "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        keepAlive = DriverManager.getConnection(url);
    }

    @After
    public void tearDown() throws Exception {
        if (storage != null) {
            storage.close();
        }
        keepAlive.close();
    }

    private MySQLStorage open() throws Exception {
        if (storage != null) {
            storage.close();
        }
        storage = new MySQLStorage(url, "", "", Logger.getLogger("test"), () -> GROUPS, CANON);
        storage.init();
        return storage;
    }

    private void sql(String... statements) throws Exception {
        try (Statement st = keepAlive.createStatement()) {
            for (String s : statements) {
                st.execute(s);
            }
        }
    }

    private VipData read(UUID id) throws Exception {
        return storage.update(id, d -> false);
    }

    @Test
    public void migratesV1TablesOnceAndKeepsThem() throws Exception {
        // tables exactly as CursedVIP 1.2.5 created them
        sql("CREATE TABLE `cursedvip_keys` (`key` VARCHAR(11) PRIMARY KEY, `group` VARCHAR(15), `days` INT)",
                "CREATE TABLE `cursedvip_vips` (`uuid` VARCHAR(40) PRIMARY KEY, `start` VARCHAR(11), `using` VARCHAR(15),"
                        + " `vip1` INT(15) NOT NULL DEFAULT 0, `vip2` INT(15) NOT NULL DEFAULT 0, `vvip+` INT(15) NOT NULL DEFAULT 0)",
                "CREATE TABLE `cursedvip_changes` (`uuid` VARCHAR(40) PRIMARY KEY, `date` VARCHAR(20))",
                "CREATE TABLE `cursedvip_log` (`command` VARCHAR(20), `uuid` VARCHAR(40), `key` VARCHAR(11) PRIMARY KEY,"
                        + " `data` VARCHAR(11), `group` VARCHAR(15), `days` INT)",
                "INSERT INTO `cursedvip_vips` VALUES ('" + A + "', '10/11/2026', 'vvip+', 30, 0, 5)",
                "INSERT INTO `cursedvip_vips` VALUES ('" + B + "', '01/11/2026', 'vip2', 0, 0, 0)",
                "INSERT INTO `cursedvip_keys` VALUES ('ABC123XYZ9', 'vip1', 30)",
                "INSERT INTO `cursedvip_changes` VALUES ('" + A + "', '1731400000000')",
                "INSERT INTO `cursedvip_log` VALUES ('usekey', '" + A + "', 'OLDKEY0001', '10/11/2026', 'vvip+', 5)");

        open();

        VipData a = read(A);
        assertEquals("vvip+", a.getActive());
        long expected = LocalDate.of(2026, 11, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(expected, a.getExpiresAt());
        assertEquals(30 * DAY_MS, a.getRemaining("vip1", 0));
        assertTrue(read(B).isEmpty());
        assertEquals(Long.valueOf(1731400000000L), storage.getCooldown(A));
        assertEquals(30, storage.getKey("ABC123XYZ9").getDays());
        assertEquals(1, count("cursedvip_key_log"));
        assertEquals("old tables are kept as backup", 2, count("cursedvip_vips"));

        // a second start must not migrate again (it would overwrite newer data)
        storage.update(A, d -> {
            d.add("vip1", DAY_MS, System.currentTimeMillis());
            return true;
        });
        open();
        assertEquals(31 * DAY_MS, read(A).getRemaining("vip1", 0));
    }

    @Test
    public void freshInstallUpdateAndDelete() throws Exception {
        open();
        long now = System.currentTimeMillis();
        assertTrue(read(A).isEmpty());

        VipData d = storage.update(A, v -> {
            v.add("vip1", 3 * DAY_MS, now);
            v.add("vip2", 5 * DAY_MS, now);
            return true;
        });
        assertEquals("vip1", d.getActive());
        VipData back = read(A);
        assertEquals(now + 3 * DAY_MS, back.getExpiresAt());
        assertEquals(5 * DAY_MS, back.getRemaining("vip2", now));

        assertEquals(1, storage.addTimeToGroup("vip2", DAY_MS)); // one queued vip2 entry
        assertEquals(1, storage.addTimeToGroup("vip1", 0L));     // one active vip1 entry
        assertEquals(6 * DAY_MS, read(A).getRemaining("vip2", now));

        storage.update(A, v -> {
            v.clear();
            return true;
        });
        assertTrue(read(A).isEmpty());
        assertEquals(0, count("cursedvip_queue"));
    }

    @Test
    public void failedMutationRollsBack() throws Exception {
        open();
        long now = System.currentTimeMillis();
        storage.update(A, v -> {
            v.add("vip1", DAY_MS, now);
            return true;
        });
        try {
            storage.update(A, v -> {
                v.clear();
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals("vip1", read(A).getActive());
    }

    @Test
    public void keysAndCooldowns() throws Exception {
        open();
        assertTrue(storage.createKey("KEY0000001", "vip1", 7));
        assertFalse(storage.createKey("KEY0000001", "vip2", 9));
        assertEquals("vip1", storage.getKey("KEY0000001").getGroup());
        assertTrue(storage.deleteKey("KEY0000001"));
        assertFalse(storage.deleteKey("KEY0000001"));
        assertNull(storage.getKey("KEY0000001"));

        storage.setCooldown(A, 5L);
        storage.setCooldown(A, 6L);
        assertEquals(Long.valueOf(6L), storage.getCooldown(A));
        assertTrue(storage.removeCooldown(A));
        assertNull(storage.getCooldown(A));

        storage.logUseKey(A, "KEY0000001", "vip1", 7);
        storage.logUseKey(A, "KEY0000001", "vip1", 7); // same key twice must not fail
        assertEquals(2, count("cursedvip_key_log"));
    }

    private int count(String table) throws Exception {
        try (Statement st = keepAlive.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

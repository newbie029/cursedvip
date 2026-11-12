package id.cursedcraft.cursedvip.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static id.cursedcraft.cursedvip.storage.VipData.DAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** Migrates a real 1.x data.yml. */
public class FlatfileMigrationTest {
    private static final List<String> ORDER = Arrays.asList("vip1", "vip2", "vvip+");
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static final String V1 = ""
            + "keys:\n"
            + "  ABC123XYZ9: vip1,30\n"
            + "vips:\n"
            + "  11111111-1111-1111-1111-111111111111:\n"
            + "    start: 10/11/2026\n"
            + "    using: vvip+\n"
            + "    vvip+: 5\n"
            + "    vip1: 30\n"
            + "  22222222-2222-2222-2222-222222222222:\n"
            + "    start: 01/11/2026\n"
            + "    using: vip2\n"
            + "    vip2: 0\n"
            + "cooldown:\n"
            + "  11111111-1111-1111-1111-111111111111: 1731400000000\n";

    @Test
    public void migratesV1File() throws Exception {
        YamlConfiguration data = new YamlConfiguration();
        data.loadFromString(V1);

        int migrated = FlatfileStorage.migrate(data, ORDER, s -> {
            for (String g : ORDER) {
                if (g.equalsIgnoreCase(s)) {
                    return g;
                }
            }
            return null;
        }, ZONE, TODAY);

        // round-trip through text, like a real save + restart
        YamlConfiguration reloaded = new YamlConfiguration();
        reloaded.loadFromString(data.saveToString());

        assertEquals(1, migrated);
        assertEquals(2, reloaded.getInt("version"));
        String p = "vips.11111111-1111-1111-1111-111111111111";
        assertEquals("vvip+", reloaded.getString(p + ".active"));
        long expected = LocalDate.of(2026, 11, 15).atStartOfDay(ZONE).toInstant().toEpochMilli();
        assertEquals(expected, reloaded.getLong(p + ".expires"));
        assertEquals(30 * DAY_MS, reloaded.getLong(p + ".paused.vip1"));
        assertNull(reloaded.get(p + ".start"));
        assertNull(reloaded.get(p + ".using"));
        // player with only expired/zero groups is dropped
        assertFalse(reloaded.contains("vips.22222222-2222-2222-2222-222222222222"));
        // untouched sections
        assertEquals("vip1,30", reloaded.getString("keys.ABC123XYZ9"));
        assertEquals(1731400000000L, reloaded.getLong("cooldown.11111111-1111-1111-1111-111111111111"));
    }
}

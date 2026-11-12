package id.cursedcraft.cursedvip.storage;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static id.cursedcraft.cursedvip.storage.VipData.DAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyConverterTest {
    private static final List<String> ORDER = Arrays.asList("vip1", "vip2", "vvip");
    private static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Function<String, String> CANON = s -> {
        if (s == null) {
            return null;
        }
        for (String g : ORDER) {
            if (g.equalsIgnoreCase(s.trim())) {
                return g;
            }
        }
        return null;
    };

    private static long midnight(LocalDate d) {
        return d.atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    private static Map<String, Integer> days(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Integer) kv[i + 1]);
        }
        return m;
    }

    @Test
    public void activeExpiresAtStartPlusDaysAndOthersAreQueued() {
        VipData d = LegacyConverter.convert("05/11/2026", "vip2", days("vip1", 10, "vip2", 5, "vvip", 0),
                ORDER, CANON, ZONE, TODAY);
        assertEquals("vip2", d.getActive());
        assertEquals(midnight(LocalDate.of(2026, 11, 10)), d.getExpiresAt());
        assertEquals(10 * DAY_MS, d.getRemaining("vip1", 0));
        assertEquals(0L, d.getRemaining("vvip", 0)); // 0 days = not owned
    }

    @Test
    public void matchesOldBehaviourAfterExpiry() {
        // 1.x: started 8 days ago with vip2 (5 days) active and vip1 (10 days) queued.
        // Old logic on TODAY: vip2 expired, vip1 active with 7 days counted from today.
        VipData d = LegacyConverter.convert("04/11/2026", "vip2", days("vip1", 10, "vip2", 5),
                ORDER, CANON, ZONE, TODAY);
        long noonToday = midnight(TODAY) + DAY_MS / 2;
        d.expire(noonToday, ORDER);
        assertEquals("vip1", d.getActive());
        assertEquals(midnight(TODAY.plusDays(7)), d.getExpiresAt());
    }

    @Test
    public void groupNamesAreCanonicalisedAndUnknownUsingFallsBackToConfigOrder() {
        // MySQL column names may come back in another case; "using" may point to a removed group
        VipData d = LegacyConverter.convert("12/11/2026", "deleted", days("VVIP", 3, "Vip2", 4),
                ORDER, CANON, ZONE, TODAY);
        assertEquals("vip2", d.getActive());
        assertEquals(3 * DAY_MS, d.getRemaining("vvip", 0));
    }

    @Test
    public void groupsNotInConfigAreKeptQueued() {
        VipData d = LegacyConverter.convert("12/11/2026", "vip1", days("vip1", 3, "legacy", 9),
                ORDER, CANON, ZONE, TODAY);
        assertEquals(9 * DAY_MS, d.getRemaining("legacy", 0));
    }

    @Test
    public void missingOrInvalidStartUsesToday() {
        VipData d = LegacyConverter.convert("garbage", "vip1", days("vip1", 2), ORDER, CANON, ZONE, TODAY);
        assertEquals(midnight(TODAY) + 2 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void noOwnedGroupsGivesEmptyData() {
        assertTrue(LegacyConverter.convert("12/11/2026", "vip1", days("vip1", 0), ORDER, CANON, ZONE, TODAY).isEmpty());
    }
}

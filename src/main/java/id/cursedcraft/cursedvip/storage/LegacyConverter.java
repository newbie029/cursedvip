package id.cursedcraft.cursedvip.storage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Converts the 1.x format (start date {@code dd/MM/yyyy} + days left per group) to {@link VipData}.
 * <p>
 * In 1.x the active group with N days started on day D expired at the start of day D+N (server time zone),
 * and the other groups did not tick. That is exactly: active expires at midnight(D) + N days,
 * every other group is queued with N days.
 */
public final class LegacyConverter {
    public static final DateTimeFormatter LEGACY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private LegacyConverter() {
    }

    /**
     * @param start     stored start date, {@code null}/invalid means today
     * @param using     stored active group
     * @param days      days per group as stored (values &lt;= 0 are ignored)
     * @param order     configured group order, used when {@code using} is not owned
     * @param canonical maps a stored group name to the configured name ({@code null} = not configured)
     */
    public static VipData convert(String start, String using, Map<String, Integer> days, List<String> order,
                                  Function<String, String> canonical, ZoneId zone, LocalDate today) {
        Map<String, Integer> owned = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : days.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                String c = canonical.apply(e.getKey());
                owned.merge(c != null ? c : e.getKey(), e.getValue(), Integer::sum);
            }
        }
        VipData d = new VipData();
        if (owned.isEmpty()) {
            return d;
        }
        String active = canonical.apply(using);
        if (active == null || !owned.containsKey(active)) {
            active = null;
            for (String g : order) {
                if (owned.containsKey(g)) {
                    active = g;
                    break;
                }
            }
            if (active == null) {
                active = owned.keySet().iterator().next();
            }
        }
        LocalDate startDate = parseDate(start);
        if (startDate == null) {
            startDate = today;
        }
        long startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli();
        d.setActive(active, startMs + owned.get(active) * VipData.DAY_MS);
        for (Map.Entry<String, Integer> e : owned.entrySet()) {
            if (!e.getKey().equals(active)) {
                d.setPaused(e.getKey(), e.getValue() * VipData.DAY_MS);
            }
        }
        return d;
    }

    public static LocalDate parseDate(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim(), LEGACY_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Epoch millis of the start of a 1.x date, or {@code fallback} when unparsable. */
    public static long dateToMillis(String text, ZoneId zone, long fallback) {
        LocalDate d = parseDate(text);
        return d == null ? fallback : d.atStartOfDay(zone).toInstant().toEpochMilli();
    }
}

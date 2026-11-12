package id.cursedcraft.cursedvip.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VIP state of one player, in epoch milliseconds.
 * <ul>
 *   <li>{@code active} runs until {@code expiresAt} (wall clock, keeps running while offline).</li>
 *   <li>{@code paused} groups are queued with a frozen remaining duration; they only start ticking
 *       when they become active.</li>
 * </ul>
 * All methods are pure: the caller passes {@code now} and the configured group order.
 */
public final class VipData {
    public static final long DAY_MS = 86_400_000L;

    private String active;
    private long expiresAt;
    private final Map<String, Long> paused = new LinkedHashMap<>();

    public String getActive() {
        return active;
    }

    public void setActive(String active, long expiresAt) {
        this.active = active;
        this.expiresAt = active == null ? 0L : expiresAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public Map<String, Long> getPaused() {
        return Collections.unmodifiableMap(paused);
    }

    public void setPaused(String group, long remainingMs) {
        if (remainingMs > 0) {
            paused.put(group, remainingMs);
        } else {
            paused.remove(group);
        }
    }

    public boolean isVip() {
        return active != null;
    }

    public boolean isEmpty() {
        return active == null && paused.isEmpty();
    }

    public boolean owns(String group) {
        return group != null && (group.equals(active) || paused.containsKey(group));
    }

    /** Active group first, then queued groups. */
    public Set<String> groups() {
        Set<String> s = new LinkedHashSet<>();
        if (active != null) {
            s.add(active);
        }
        s.addAll(paused.keySet());
        return s;
    }

    public long getRemaining(String group, long now) {
        if (group == null) {
            return 0L;
        }
        if (group.equals(active)) {
            return Math.max(0L, expiresAt - now);
        }
        Long r = paused.get(group);
        return r == null ? 0L : r;
    }

    /**
     * Ends the active group when its time is up and starts the next queued group (in {@code order}),
     * chained from the previous expiry so no time is lost while the server was offline.
     *
     * @return the groups that expired, in order
     */
    public List<String> expire(long now, List<String> order) {
        List<String> expired = new ArrayList<>();
        if (active == null) {
            String next = nextQueued(order);
            if (next == null) {
                return expired;
            }
            active = next;
            expiresAt = add(now, paused.remove(next));
        }
        while (active != null && now >= expiresAt) {
            expired.add(active);
            long carry = expiresAt;
            String next = nextQueued(order);
            if (next == null) {
                active = null;
                expiresAt = 0L;
                break;
            }
            active = next;
            expiresAt = add(carry, paused.remove(next));
        }
        return expired;
    }

    /** Adds time to a group. A player without an active VIP gets it activated right away. */
    public void add(String group, long ms, long now) {
        if (active == null) {
            active = group;
            expiresAt = add(now, ms + (paused.containsKey(group) ? paused.remove(group) : 0L));
        } else if (group.equals(active)) {
            expiresAt = add(Math.max(expiresAt, now), ms);
        } else {
            Long old = paused.get(group);
            paused.put(group, add(old == null ? 0L : old, ms));
        }
    }

    /** Makes an owned group the active one; the previous active group is paused with its remaining time. */
    public void activate(String group, long now) {
        if (group.equals(active)) {
            return;
        }
        Long remaining = paused.remove(group);
        if (remaining == null) {
            throw new IllegalArgumentException("Group not owned: " + group);
        }
        if (active != null && expiresAt > now) {
            paused.put(active, expiresAt - now);
        }
        active = group;
        expiresAt = add(now, remaining);
    }

    /** Sets the remaining time of a group (added to the queue if not owned yet). */
    public void setRemaining(String group, long ms, long now) {
        if (ms <= 0) {
            throw new IllegalArgumentException("ms must be > 0");
        }
        if (group.equals(active) || active == null) {
            paused.remove(group);
            active = group;
            expiresAt = add(now, ms);
        } else {
            paused.put(group, ms);
        }
    }

    /** Removes every group, including queued groups that are no longer configured. */
    public void clear() {
        active = null;
        expiresAt = 0L;
        paused.clear();
    }

    /** Removes one group; when it was active the next queued group starts now. */
    public void remove(String group, long now, List<String> order) {
        if (group.equals(active)) {
            active = null;
            expiresAt = 0L;
            String next = nextQueued(order);
            if (next != null) {
                active = next;
                expiresAt = add(now, paused.remove(next));
            }
        } else {
            paused.remove(group);
        }
    }

    /** First queued group in config order. Groups no longer in the config are kept but never started. */
    private String nextQueued(List<String> order) {
        for (String g : order) {
            if (paused.containsKey(g)) {
                return g;
            }
        }
        return null;
    }

    private static long add(long a, long b) {
        long r = a + b;
        // saturate instead of wrapping around on absurd values
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    public VipData copy() {
        VipData c = new VipData();
        c.active = active;
        c.expiresAt = expiresAt;
        c.paused.putAll(paused);
        return c;
    }
}

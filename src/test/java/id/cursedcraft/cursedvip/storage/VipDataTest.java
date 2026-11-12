package id.cursedcraft.cursedvip.storage;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static id.cursedcraft.cursedvip.storage.VipData.DAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VipDataTest {
    private static final List<String> ORDER = Arrays.asList("vip1", "vip2", "vvip");
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void notExpiredStaysUntouched() {
        VipData d = new VipData();
        d.setActive("vip1", NOW + DAY_MS);
        assertTrue(d.expire(NOW, ORDER).isEmpty());
        assertEquals("vip1", d.getActive());
    }

    @Test
    public void expiryChainsQueuedGroupsFromPreviousExpiry() {
        // vip2 ended 3 days ago (server offline); vip1 (10 days) must have started back then, vvip still queued
        VipData d = new VipData();
        d.setActive("vip2", NOW - 3 * DAY_MS);
        d.setPaused("vip1", 10 * DAY_MS);
        d.setPaused("vvip", 20 * DAY_MS);
        assertEquals(Collections.singletonList("vip2"), d.expire(NOW, ORDER));
        assertEquals("vip1", d.getActive());
        assertEquals(NOW + 7 * DAY_MS, d.getExpiresAt());
        assertEquals(20 * DAY_MS, d.getRemaining("vvip", NOW));
    }

    @Test
    public void expiryCanEndSeveralGroups() {
        VipData d = new VipData();
        d.setActive("vip1", NOW - 10 * DAY_MS);
        d.setPaused("vip2", 5 * DAY_MS);
        d.setPaused("vvip", 30 * DAY_MS);
        assertEquals(Arrays.asList("vip1", "vip2"), d.expire(NOW, ORDER));
        assertEquals("vvip", d.getActive());
        assertEquals(NOW + 25 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void lastGroupExpiringLeavesNoVip() {
        VipData d = new VipData();
        d.setActive("vip1", NOW);
        assertEquals(Collections.singletonList("vip1"), d.expire(NOW, ORDER));
        assertFalse(d.isVip());
        assertTrue(d.isEmpty());
    }

    @Test
    public void unconfiguredQueuedGroupIsKeptButNeverStarted() {
        VipData d = new VipData();
        d.setActive("vip1", NOW - 1);
        d.setPaused("oldgroup", 5 * DAY_MS);
        d.expire(NOW, ORDER);
        assertNull(d.getActive());
        assertFalse(d.isEmpty());
    }

    @Test
    public void addToActiveExtendsExpiry() {
        VipData d = new VipData();
        d.setActive("vip1", NOW + DAY_MS);
        d.add("vip1", 2 * DAY_MS, NOW);
        assertEquals(NOW + 3 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void addToOtherGroupQueuesIt() {
        VipData d = new VipData();
        d.setActive("vip1", NOW + DAY_MS);
        d.add("vvip", 5 * DAY_MS, NOW);
        assertEquals("vip1", d.getActive());
        assertEquals(5 * DAY_MS, d.getRemaining("vvip", NOW));
    }

    @Test
    public void addWithoutVipActivatesNow() {
        VipData d = new VipData();
        d.add("vip2", 5 * DAY_MS, NOW);
        assertEquals("vip2", d.getActive());
        assertEquals(NOW + 5 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void activatePausesPreviousWithItsRemainingTime() {
        VipData d = new VipData();
        d.setActive("vip1", NOW + 4 * DAY_MS);
        d.setPaused("vvip", 10 * DAY_MS);
        d.activate("vvip", NOW);
        assertEquals("vvip", d.getActive());
        assertEquals(NOW + 10 * DAY_MS, d.getExpiresAt());
        assertEquals(4 * DAY_MS, d.getRemaining("vip1", NOW));
    }

    @Test
    public void removingActiveStartsNextQueued() {
        VipData d = new VipData();
        d.setActive("vip2", NOW + DAY_MS);
        d.setPaused("vvip", 3 * DAY_MS);
        d.remove("vip2", NOW, ORDER);
        assertEquals("vvip", d.getActive());
        assertEquals(NOW + 3 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void setRemainingOnActiveCountsFromNow() {
        VipData d = new VipData();
        d.setActive("vip1", NOW + DAY_MS);
        d.setRemaining("vip1", 30 * DAY_MS, NOW);
        assertEquals(NOW + 30 * DAY_MS, d.getExpiresAt());
    }

    @Test
    public void hugeValuesSaturateInsteadOfOverflowing() {
        VipData d = new VipData();
        d.setActive("vip1", Long.MAX_VALUE - 10);
        d.add("vip1", DAY_MS, NOW);
        assertEquals(Long.MAX_VALUE, d.getExpiresAt());
    }
}

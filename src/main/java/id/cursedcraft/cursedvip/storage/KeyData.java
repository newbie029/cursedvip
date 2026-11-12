package id.cursedcraft.cursedvip.storage;

/** A redeemable VIP key. */
public final class KeyData {
    private final String key;
    private final String group;
    private final int days;

    public KeyData(String key, String group, int days) {
        this.key = key;
        this.group = group;
        this.days = days;
    }

    public String getKey() {
        return key;
    }

    public String getGroup() {
        return group;
    }

    public int getDays() {
        return days;
    }
}

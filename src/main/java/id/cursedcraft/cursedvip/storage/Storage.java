package id.cursedcraft.cursedvip.storage;

import java.util.List;
import java.util.UUID;

/**
 * Persistence backend. Implementations are only ever called from one thread at a time:
 * the server thread for flatfile, the plugin's single database thread for MySQL.
 * <p>
 * {@link #init()} migrates data from the 1.x format when needed.
 */
public interface Storage {

    /** Changes a player's VIP data in place. */
    interface Mutation {
        /**
         * @param data the current data, never {@code null} (empty when the player has none)
         * @return {@code true} when {@code data} was changed and must be written
         */
        boolean apply(VipData data);
    }

    void init() throws Exception;

    /** Re-reads state after /cursedvip reload. */
    void reload() throws Exception;

    void close();

    /**
     * Reads, mutates and writes one player's VIP data atomically. For MySQL the row stays locked
     * until the write, so servers sharing the database cannot overwrite each other's changes.
     * Empty data after the mutation deletes the player.
     *
     * @return the resulting data (empty when the player has none)
     */
    VipData update(UUID uuid, Mutation mutation) throws Exception;

    /** Adds time to every player that owns {@code group} (active or queued). @return affected entries. */
    int addTimeToGroup(String group, long ms) throws Exception;

    /** @return {@code false} if the key already exists. */
    boolean createKey(String key, String group, int days) throws Exception;

    KeyData getKey(String key) throws Exception;

    /** @return {@code true} if the key existed and was removed by this call. */
    boolean deleteKey(String key) throws Exception;

    List<KeyData> listKeys() throws Exception;

    void deleteAllKeys() throws Exception;

    /** @return last /changevip time in epoch millis, or {@code null}. */
    Long getCooldown(UUID uuid) throws Exception;

    void setCooldown(UUID uuid, long time) throws Exception;

    boolean removeCooldown(UUID uuid) throws Exception;

    void logUseKey(UUID uuid, String key, String group, int days) throws Exception;
}

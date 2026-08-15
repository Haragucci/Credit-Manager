package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CreditEventRepository {
    static final int CACHE_LIMIT = DatabaseManager.PAGE_SIZE;
    private static final CreditEventRepository INSTANCE = new CreditEventRepository();
    private volatile List<CreditEventEntry> recentEvents = List.of();
    private volatile boolean recoveryRequired;
    private volatile long revision;

    private CreditEventRepository() { }

    public static CreditEventRepository getInstance() { return INSTANCE; }

    public synchronized boolean load() {
        recoveryRequired = true;
        try {
            DatabaseManager.QueryPage<CreditEventEntry> page = DatabaseManager.getInstance()
                    .queryCreditEventPage("", null, CACHE_LIMIT, 0);
            List<CreditEventEntry> nextEvents = new ArrayList<>(page.entries());
            recentEvents = List.copyOf(nextEvents);
            recoveryRequired = false;
            revision = DatabaseManager.getInstance().revision();
            CreditManagerClient.LOGGER.info("Loaded {} of {} credit events into the bounded runtime cache.",
                    nextEvents.size(), page.totalCount());
            return true;
        } catch (RuntimeException exception) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Historien-Ereignisse konnten nicht aus der Datenbank gelesen werden; der vorhandene Laufzeit-Cache bleibt erhalten.");
            CreditManagerClient.LOGGER.error("Could not load bounded CreditManager event history", exception);
            return false;
        }
    }

    public List<CreditEventEntry> getRecentEvents() { return recentEvents; }

    public long getRevision() { return revision; }

    public boolean isWritable() {
        return !recoveryRequired && DatabaseManager.getInstance().isSafeForWrites();
    }

    public DatabaseManager.QueryPage<CreditEventEntry> queryPlayerPage(String player, int limit, int offset) {
        if (player == null || player.isBlank()) throw new IllegalArgumentException("player must not be blank");
        return DatabaseManager.getInstance().queryCreditEventPage(player, null, limit, offset);
    }

    public DatabaseManager.QueryPage<CreditEventEntry> queryCreditPage(UUID creditId, int limit, int offset) {
        if (creditId == null) throw new IllegalArgumentException("creditId must not be null");
        return DatabaseManager.getInstance().queryCreditEventPage("", creditId, limit, offset);
    }

    public DatabaseManager.StatisticsEventSlice getStatisticsSlice(String player, long fromInclusive, long toInclusive) {
        return DatabaseManager.getInstance().queryStatisticsEvents(player, fromInclusive, toInclusive);
    }

    public synchronized void acceptCommittedEvents(List<CreditEventEntry> values, long committedRevision) {
        if (values != null && !values.isEmpty()) {
            List<CreditEventEntry> nextEvents = new ArrayList<>(recentEvents);
            Set<UUID> ids = new HashSet<>();
            for (CreditEventEntry event : nextEvents) {
                if (event != null && event.getId() != null) ids.add(event.getId());
            }
            for (CreditEventEntry event : values) {
                if (event != null && event.getId() != null && ids.add(event.getId())) nextEvents.add(0, event);
            }
            if (nextEvents.size() > CACHE_LIMIT) nextEvents.subList(CACHE_LIMIT, nextEvents.size()).clear();
            recentEvents = List.copyOf(nextEvents);
        }
        recoveryRequired = false;
        revision = committedRevision;
    }
}

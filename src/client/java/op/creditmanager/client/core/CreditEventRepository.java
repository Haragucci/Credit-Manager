package op.creditmanager.client.core;

import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CreditEventRepository {
    private static final Type LIST_TYPE = new TypeToken<List<CreditEventEntry>>() {}.getType();
    private static final CreditEventRepository INSTANCE = new CreditEventRepository();
    private final List<CreditEventEntry> events = new CopyOnWriteArrayList<>();
    private boolean recoveryRequired;
    private long revision;
    private CreditRepository core;

    private CreditEventRepository() { }
    public static CreditEventRepository getInstance() { return INSTANCE; }

    public synchronized void bind(CreditRepository repository) { this.core = repository; }

    public synchronized boolean load() {
        JsonStorage.LoadResult<List<CreditEventEntry>> result = core == null || !core.hasPrimaryState()
                ? JsonStorage.load(FileManager.getCreditEventsFile(), LIST_TYPE, new ArrayList<>())
                : new JsonStorage.LoadResult<>(core.getEvents(), false, false);
        if (result.recoveryRequired()) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Historien-Ereignisse konnten nicht vollständig gelesen werden; die zuletzt sichtbaren Ereignisse bleiben erhalten.");
            return false;
        }
        List<CreditEventEntry> nextEvents = new ArrayList<>();
        boolean nextRecoveryRequired = false;
        int index = 0;
        for (CreditEventEntry entry : result.value()) {
            if (entry == null || entry.getType() == null || entry.getCreditId() == null) {
                java.nio.file.Path source = core != null && core.hasPrimaryState()
                        ? FileManager.getDatabaseStorageFile() : FileManager.getCreditEventsFile();
                if (core != null && core.hasPrimaryState()) DatabaseManager.getInstance().createBackup();
                else JsonStorage.createBackup(source);
                DataHealth.reportRecoveryRequired("invalid credit event");
                if (core != null) {
                    String key = entry != null && entry.getId() != null ? entry.getId().toString() : "event-" + index;
                    core.registerEventRecovery(entry, key, source);
                } else nextRecoveryRequired = true;
                index++;
                continue;
            }
            if (entry.getId() == null) entry.setId(UUID.randomUUID());
            nextEvents.add(entry);
            index++;
        }
        if (core != null) {
            core.replaceEvents(nextEvents);
            if (!core.hasPrimaryState() && !nextRecoveryRequired && !core.saveAll()) {
                nextRecoveryRequired = true;
                DataHealth.reportRecoveryRequired("Event-Migration konnte nicht gespeichert werden");
            }
        }
        events.clear();
        events.addAll(nextEvents);
        recoveryRequired = nextRecoveryRequired;
        revision++;
        CreditManagerClient.LOGGER.info("Loaded " + nextEvents.size() + " credit events."
                + (nextRecoveryRequired ? " Recovery required." : ""));
        return true;
    }

    public synchronized boolean add(CreditEventEntry entry) {
        if (entry == null || recoveryRequired) return false;
        events.add(entry);
        if (save()) {
            revision++;
            return true;
        }
        events.remove(entry);
        if (core != null) core.replaceEvents(events);
        return false;
    }

    public List<CreditEventEntry> getAll() { return Collections.unmodifiableList(new ArrayList<>(events)); }
    public synchronized long getRevision() { return revision; }
    public synchronized boolean isWritable() { return !recoveryRequired; }

    public List<CreditEventEntry> getForPlayer(String player) {
        if (player == null || player.isBlank()) return List.of();
        String value = player.toLowerCase(Locale.ROOT);
        return events.stream().filter(event -> value.equals(lower(event.getCreditor())) || value.equals(lower(event.getDebtor()))).toList();
    }

    public List<CreditEventEntry> getForCredit(UUID creditId) {
        if (creditId == null) return List.of();
        return events.stream().filter(event -> creditId.equals(event.getCreditId())).toList();
    }

    public synchronized boolean resetWithBackup() {
        if (recoveryRequired) return false;
        try {
            if (core != null) {
                if (!DatabaseManager.getInstance().createBackup()) return false;
            } else {
                java.nio.file.Path source = FileManager.getCreditEventsFile();
                if (Files.exists(source)) Files.copy(source, FileManager.getBackupFile(source.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
            List<CreditEventEntry> previous = new ArrayList<>(events);
            events.clear();
            if (!save()) {
                events.addAll(previous);
                return false;
            }
            revision++;
            return true;
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.error("Could not back up credit event history before reset", exception);
            return false;
        }
    }

    synchronized void acceptRecoveredEvent(CreditEventEntry entry) {
        if (entry == null || entry.getId() == null || events.stream().anyMatch(value -> entry.getId().equals(value.getId()))) return;
        events.add(entry);
        revision++;
    }

    synchronized void acceptCommittedEvents(List<CreditEventEntry> values) {
        if (values == null || values.isEmpty()) return;
        for (CreditEventEntry entry : values) {
            if (entry != null && entry.getId() != null && events.stream().noneMatch(existing -> entry.getId().equals(existing.getId()))) events.add(entry);
        }
        revision++;
    }

    private boolean save() {
        if (core != null) {
            core.replaceEvents(events);
            return core.saveAll();
        }
        return JsonStorage.save(FileManager.getCreditEventsFile(), new ArrayList<>(events));
    }

    public synchronized void recoverFromCore() {
        if (core == null) return;
        events.clear();
        events.addAll(core.getEvents());
        recoveryRequired = false;
        revision++;
    }
    private String lower(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}

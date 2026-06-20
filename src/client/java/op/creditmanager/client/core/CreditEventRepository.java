package op.creditmanager.client.core;

import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Dedicated event history for credit statistics; it never reads Paylog transactions. */
public final class CreditEventRepository {
    private static final Type LIST_TYPE = new TypeToken<List<CreditEventEntry>>() {}.getType();
    private static final CreditEventRepository INSTANCE = new CreditEventRepository();
    private final List<CreditEventEntry> events = new CopyOnWriteArrayList<>();

    private CreditEventRepository() {
    }

    public static CreditEventRepository getInstance() {
        return INSTANCE;
    }

    public synchronized void load() {
        List<CreditEventEntry> loaded = JsonStorage.load(FileManager.getCreditEventsFile(), LIST_TYPE, new ArrayList<>());
        events.clear();
        if (loaded != null) {
            for (CreditEventEntry entry : loaded) {
                if (entry.getId() == null) entry.setId(UUID.randomUUID());
                events.add(entry);
            }
        }
        CreditManagerClient.LOGGER.info("Loaded " + events.size() + " credit events.");
    }

    public void add(CreditEventEntry entry) {
        if (entry == null) return;
        events.add(entry);
        save();
    }

    public List<CreditEventEntry> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public List<CreditEventEntry> getForPlayer(String player) {
        if (player == null || player.isBlank()) return List.of();
        String value = player.toLowerCase(Locale.ROOT);
        return events.stream().filter(event -> value.equals(lower(event.getCreditor())) || value.equals(lower(event.getDebtor())))
                .toList();
    }

    public List<CreditEventEntry> getForCredit(UUID creditId) {
        if (creditId == null) return List.of();
        return events.stream().filter(event -> creditId.equals(event.getCreditId())).toList();
    }

    public synchronized boolean resetWithBackup() {
        try {
            if (Files.exists(FileManager.getCreditEventsFile())) {
                Files.copy(FileManager.getCreditEventsFile(), FileManager.getBackupFile("credit_events.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            events.clear();
            save();
            return true;
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.error("Could not back up credit event history before reset", exception);
            return false;
        }
    }

    private void save() {
        JsonStorage.save(FileManager.getCreditEventsFile(), new ArrayList<>(events));
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

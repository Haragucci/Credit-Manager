package op.creditmanager.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import op.creditmanager.client.model.CreditEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CreditStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final List<CreditEntry> credits = new ArrayList<>();

    public List<CreditEntry> getAll() {
        return credits;
    }

    public Optional<CreditEntry> find(UUID id) {
        return credits.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }

    public void add(CreditEntry entry) {
        credits.add(entry);
        save();
    }

    public void delete(UUID id) {
        credits.removeIf(c -> c.getId().equals(id));
        save();
    }

    public void clear() {
        credits.clear();
        save();
    }

    public void load() {
        try {
            Path file = FileManager.getCreditsFile();

            if (!Files.exists(file)) return;

            String json = Files.readString(file);

            CreditEntry[] loaded = GSON.fromJson(json, CreditEntry[].class);

            credits.clear();

            if (loaded != null) {
                credits.addAll(Arrays.asList(loaded));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try {
            Path file = FileManager.getCreditsFile();

            String json = GSON.toJson(credits);

            Files.writeString(file, json);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
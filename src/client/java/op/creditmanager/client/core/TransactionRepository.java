package op.creditmanager.client.core;

import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class TransactionRepository {

    private static volatile TransactionRepository instance;

    private final List<TransactionEntry> transactions = new CopyOnWriteArrayList<>();
    private static final Type LIST_TYPE = new TypeToken<List<TransactionEntry>>() {}.getType();

    private TransactionRepository() {}

    public static TransactionRepository getInstance() {
        if (instance == null) {
            synchronized (TransactionRepository.class) {
                if (instance == null) {
                    instance = new TransactionRepository();
                }
            }
        }
        return instance;
    }

    public void load() {
        List<TransactionEntry> loaded = JsonStorage.load(
                FileManager.getTransactionsFile(), LIST_TYPE, new ArrayList<>());
        transactions.clear();
        if (loaded != null) {
            for (TransactionEntry e : loaded) {
                if (e.getId() == null) e.setId(UUID.randomUUID());
                transactions.add(e);
            }
        }
        CreditManagerClient.LOGGER.info("Loaded " + transactions.size() + " transactions.");
    }

    public void add(TransactionEntry entry) {
        transactions.add(entry);
        save();
    }

    public List<TransactionEntry> getAll() {
        return Collections.unmodifiableList(transactions);
    }

    private void save() {
        JsonStorage.save(FileManager.getTransactionsFile(), new ArrayList<>(transactions));
    }
}
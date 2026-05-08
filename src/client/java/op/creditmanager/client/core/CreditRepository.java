package op.creditmanager.client.core;

import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.PlayerCreditData;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CreditRepository {

    private final Map<UUID, CreditEntry> credits = new ConcurrentHashMap<>();
    private final Map<String, PlayerCreditData> players = new ConcurrentHashMap<>();
    private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();

    private static final Type CREDIT_MAP_TYPE = new TypeToken<Map<String, CreditEntry>>() {}.getType();
    private static final Type PLAYER_MAP_TYPE = new TypeToken<Map<String, PlayerCreditData>>() {}.getType();
    private static final Type PAYMENT_MAP_TYPE = new TypeToken<Map<String, Payment>>() {}.getType();

    public void load() {
        loadCredits();
        loadPlayers();
        loadPayments();
        CreditManagerClient.LOGGER.info("Loaded " + credits.size() + " credits, "
                + players.size() + " players, " + payments.size() + " payments.");
    }

    public void deleteCredit(UUID id) {

        CreditEntry entry = credits.remove(id);
        if (entry == null) return;

        String debtor = entry.getDebtor().toLowerCase();
        String creditor = entry.getCreditor().toLowerCase();

        PlayerCreditData d1 = players.get(debtor);
        if (d1 != null) {
            d1.getCreditsAsDebtor().remove(id);
        }

        PlayerCreditData d2 = players.get(creditor);
        if (d2 != null) {
            d2.getCreditsAsCreditor().remove(id);
        }

        saveCredits();
        savePlayers();
    }

    public void deletePayment(UUID paymentId) {
        Payment p = payments.remove(paymentId);
        if (p == null) return;

        CreditEntry entry = credits.get(p.getCreditId());
        if (entry != null) {
            entry.removePayment(paymentId);
            saveCredits();
        }

        savePayments();
    }

    private void loadCredits() {
        Map<String, CreditEntry> raw = JsonStorage.load(FileManager.getCreditsFile(), CREDIT_MAP_TYPE, new HashMap<>());
        credits.clear();
        if (raw != null) {
            raw.forEach((k, v) -> {
                try {
                    UUID id = UUID.fromString(k);
                    v.setId(id);
                    if (v.getPayments() == null) v.setPayments(new ArrayList<>());
                    if (v.getDealName() == null || v.getDealName().isBlank()) {
                        v.setDealName(CreditEntry.buildDealName(v.getDebtor(), v.getCreditor(), null));
                    }
                    credits.put(id, v);
                } catch (Exception e) {
                    CreditManagerClient.LOGGER.error("Invalid credit entry: " + k, e);
                }
            });
        }
    }

    private void loadPlayers() {
        Map<String, PlayerCreditData> raw = JsonStorage.load(FileManager.getPlayersFile(), PLAYER_MAP_TYPE, new HashMap<>());
        players.clear();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (v.getCreditsAsDebtor() == null) v.setCreditsAsDebtor(new ArrayList<>());
                if (v.getCreditsAsCreditor() == null) v.setCreditsAsCreditor(new ArrayList<>());
                players.put(k.toLowerCase(), v);
            });
        }
    }

    private void loadPayments() {
        Map<String, Payment> raw = JsonStorage.load(FileManager.getPaymentsFile(), PAYMENT_MAP_TYPE, new HashMap<>());
        payments.clear();
        if (raw != null) {
            raw.forEach((k, v) -> {
                try {
                    UUID id = UUID.fromString(k);
                    v.setId(id);
                    if (v.getItems() == null) v.setItems(new ArrayList<>());
                    payments.put(id, v);
                } catch (Exception e) {
                    CreditManagerClient.LOGGER.error("Invalid payment entry: " + k, e);
                }
            });
        }
    }

    public void saveCredits() {
        Map<String, CreditEntry> s = new LinkedHashMap<>();
        credits.forEach((k, v) -> s.put(k.toString(), v));
        JsonStorage.save(FileManager.getCreditsFile(), s);
    }

    public void savePlayers() {
        JsonStorage.save(FileManager.getPlayersFile(), players);
    }

    public void savePayments() {
        Map<String, Payment> s = new LinkedHashMap<>();
        payments.forEach((k, v) -> s.put(k.toString(), v));
        JsonStorage.save(FileManager.getPaymentsFile(), s);
    }

    public void saveCredit(CreditEntry entry) {
        credits.put(entry.getId(), entry);
        saveCredits();
    }

    public void savePayment(Payment payment) {
        payments.put(payment.getId(), payment);
        savePayments();
    }

    public void savePlayer(PlayerCreditData data) {
        players.put(data.getPlayerName().toLowerCase(), data);
        savePlayers();
    }

    public Optional<CreditEntry> findCreditById(UUID id) {
        return Optional.ofNullable(credits.get(id));
    }

    public Optional<CreditEntry> findCreditByShortId(String shortId) {
        String lower = shortId.toLowerCase();
        return credits.values().stream()
                .filter(c -> c.getId().toString().toLowerCase().startsWith(lower))
                .findFirst();
    }

    public Optional<CreditEntry> findCreditByName(String name) {
        String lower = name.toLowerCase();
        return credits.values().stream()
                .filter(c -> lower.equals(c.getDealName()))
                .findFirst();
    }

    public Optional<CreditEntry> findCreditByNamePrefix(String prefix) {
        String lower = prefix.toLowerCase();
        return credits.values().stream()
                .filter(c -> c.getDealName() != null && c.getDealName().startsWith(lower))
                .findFirst();
    }

    public List<CreditEntry> getAllCredits() {
        return new ArrayList<>(credits.values());
    }

    public List<CreditEntry> getCreditsByDebtor(String playerName) {
        String lower = playerName.toLowerCase();
        PlayerCreditData data = players.get(lower);
        if (data == null) return new ArrayList<>();
        List<CreditEntry> result = new ArrayList<>();
        for (UUID id : data.getCreditsAsDebtor()) {
            CreditEntry e = credits.get(id);
            if (e != null) result.add(e);
        }
        return result;
    }

    public List<CreditEntry> getCreditsByCreditor(String playerName) {
        String lower = playerName.toLowerCase();
        PlayerCreditData data = players.get(lower);
        if (data == null) return new ArrayList<>();
        List<CreditEntry> result = new ArrayList<>();
        for (UUID id : data.getCreditsAsCreditor()) {
            CreditEntry e = credits.get(id);
            if (e != null) result.add(e);
        }
        return result;
    }

    public PlayerCreditData getOrCreatePlayer(String playerName) {
        return players.computeIfAbsent(playerName.toLowerCase(),
                k -> new PlayerCreditData(playerName.toLowerCase()));
    }

    public List<Payment> getPaymentsByCreditId(UUID creditId) {
        List<Payment> result = new ArrayList<>();
        payments.values().stream()
                .filter(p -> creditId.equals(p.getCreditId()))
                .sorted(Comparator.comparingLong(Payment::getTimestamp))
                .forEach(result::add);
        return result;
    }

    public List<Payment> getAllPayments() {
        return new ArrayList<>(payments.values());
    }
}
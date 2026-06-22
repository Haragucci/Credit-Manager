package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.PlayerCreditData;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CreditRepository {
    private final Map<UUID, CreditEntry> credits = new ConcurrentHashMap<>();
    private final Map<String, PlayerCreditData> players = new ConcurrentHashMap<>();
    private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();
    private final List<CreditEventEntry> events = new ArrayList<>();
    private final List<RecoveryRecord> recoveryRecords = new ArrayList<>();
    private boolean recoveryRequired;
    private long revision;

    public synchronized void load() {
        recoveryRequired = false;
        recoveryRecords.clear();
        credits.clear(); payments.clear(); players.clear(); events.clear();
        try {
            DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
            for (CreditEntry entry : state.credits()) {
                if (!isValidCredit(entry)) {
                    addRecovery(RecoveryType.CREDIT, entry == null || entry.getId() == null ? "unknown" : entry.getId().toString(), entry, null, null, "Ungültiger Deal in der Datenbank");
                    continue;
                }
                if (entry.getPayments() == null) entry.setPayments(new ArrayList<>());
                credits.put(entry.getId(), entry);
            }
            for (Payment payment : state.payments()) {
                if (!isValidPayment(payment) || !credits.containsKey(payment.getCreditId())) {
                    addRecovery(RecoveryType.PAYMENT, payment == null || payment.getId() == null ? "unknown" : payment.getId().toString(), null, payment, null, "Ungültige oder verwaiste Zahlung in der Datenbank");
                    continue;
                }
                CreditEntry credit = credits.get(payment.getCreditId());
                if (payment.getFromPlayer() == null || payment.getFromPlayer().isBlank()) payment.setFromPlayer(credit.getDebtor());
                if (payment.getToPlayer() == null || payment.getToPlayer().isBlank()) payment.setToPlayer(credit.getCreditor());
                payments.put(payment.getId(), payment);
            }
            for (CreditEventEntry event : state.events()) {
                if (event == null || event.getId() == null || event.getType() == null || event.getCreditId() == null || !credits.containsKey(event.getCreditId())) {
                    addRecovery(RecoveryType.EVENT, event == null || event.getId() == null ? "unknown" : event.getId().toString(), null, null, event, "Ungültiges Historien-Ereignis in der Datenbank");
                    continue;
                }
                events.add(event);
            }
            reconcilePayments();
            rebuildPlayerIndex();
            revision = DatabaseManager.getInstance().revision();
            if (!DatabaseManager.getInstance().isSafeForWrites()) {
                addRecovery(RecoveryType.TRANSACTION_LOG, "database-health", null, null, null,
                        "Datenprüfung erforderlich – vorhandene Daten wurden nicht gelöscht.");
            }
            CreditManagerClient.LOGGER.info("Loaded " + credits.size() + " credits and " + payments.size() + " payments from the local database.");
        } catch (RuntimeException exception) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Lokale Datenbank konnte nicht gelesen werden");
            CreditManagerClient.LOGGER.error("Could not load CreditManager database", exception);
        }
    }

    public synchronized boolean isWritable() { return !recoveryRequired && DatabaseManager.getInstance().isSafeForWrites(); }
    public synchronized long getRevision() { return revision; }
    public synchronized boolean hasPrimaryState() { return true; }

    public synchronized boolean saveAll() {
        if (recoveryRequired) return false;
        boolean saved = DatabaseManager.getInstance().replaceCreditState(credits.values(), payments.values(), events);
        if (saved) { revision = DatabaseManager.getInstance().revision(); FileManager.tidyAfterSuccessfulSave(); }
        return saved;
    }

    public synchronized void putCredit(CreditEntry entry) { credits.put(entry.getId(), entry); rebuildPlayerIndex(); revision++; }
    synchronized void replaceLoadedCredit(CreditEntry entry) {
        if (entry == null || entry.getId() == null) return;
        credits.put(entry.getId(), entry);
        rebuildPlayerIndex();
    }
    public synchronized void putPayment(Payment payment) {
        payments.put(payment.getId(), payment);
        CreditEntry entry = credits.get(payment.getCreditId());
        if (entry != null && entry.getPayments().stream().noneMatch(existing -> payment.getId().equals(existing.getId()))) entry.addPayment(payment);
        revision++;
    }
    public synchronized void deleteCredit(UUID id) { if (credits.remove(id) != null) { payments.entrySet().removeIf(value -> id.equals(value.getValue().getCreditId())); events.removeIf(event -> id.equals(event.getCreditId())); rebuildPlayerIndex(); revision++; } }
    public synchronized void deletePayment(UUID paymentId) { Payment payment = payments.remove(paymentId); if (payment != null) { CreditEntry entry = credits.get(payment.getCreditId()); if (entry != null) entry.removePayment(paymentId); events.removeIf(event -> paymentId.toString().equals(event.getSource())); revision++; } }

    public synchronized List<CreditEventEntry> getEvents() { return new ArrayList<>(events); }
    public synchronized void replaceEvents(List<CreditEventEntry> values) { events.clear(); if (values != null) events.addAll(values); revision++; }
    public synchronized List<RecoveryRecord> getRecoveryRecords() { return List.copyOf(recoveryRecords); }

    public synchronized boolean repairCredit(UUID token, String creditor, String debtor, double amount, long createdAt) {
        RecoveryRecord record = findRecovery(token, RecoveryType.CREDIT);
        if (record == null || creditor == null || debtor == null || creditor.isBlank() || debtor.isBlank() || !Double.isFinite(amount) || amount <= 0 || creditor.equalsIgnoreCase(debtor)) return false;
        CreditEntry entry = record.credit() == null ? new CreditEntry() : record.credit();
        UUID id = parseId(record.sourceKey());
        entry.setId(id); entry.setCreditor(creditor.trim().toLowerCase(Locale.ROOT)); entry.setDebtor(debtor.trim().toLowerCase(Locale.ROOT)); entry.setAmount(amount); entry.setCreatedAt(createdAt > 0 ? createdAt : System.currentTimeMillis());
        if (entry.getDealName() == null || entry.getDealName().isBlank()) entry.setDealName(CreditEntry.buildDealName(entry.getDebtor(), entry.getCreditor(), null));
        entry.replacePayments(entry.getPayments());
        if (entry.getPaidAmount() > amount + 0.0001D) return false;
        credits.put(id, entry); recoveryRecords.remove(record); rebuildPlayerIndex(); revision++; return persistRecoveredWhenComplete(record);
    }
    public synchronized boolean repairPayment(UUID token, UUID creditId, double amount, long timestamp) {
        RecoveryRecord record = findRecovery(token, RecoveryType.PAYMENT);
        if (record == null || creditId == null || !credits.containsKey(creditId) || !Double.isFinite(amount) || amount <= 0 || amount > credits.get(creditId).getRemainingAmount() + 0.0001D) return false;
        CreditEntry credit = credits.get(creditId);
        Payment payment = record.payment() == null ? new Payment(creditId, credit.getDebtor(), credit.getCreditor(), amount, null, "RECOVERY") : record.payment();
        payment.setId(parseId(record.sourceKey())); payment.setCreditId(creditId); payment.setAmount(amount); payment.setTimestamp(timestamp > 0 ? timestamp : System.currentTimeMillis()); payment.setFromPlayer(credit.getDebtor()); payment.setToPlayer(credit.getCreditor());
        payments.put(payment.getId(), payment); recoveryRecords.remove(record); reconcilePayments(); revision++; return persistRecoveredWhenComplete(record);
    }
    public synchronized boolean repairEvent(UUID token, UUID creditId, CreditEventType type, double amount, long timestamp) {
        RecoveryRecord record = findRecovery(token, RecoveryType.EVENT);
        if (record == null || creditId == null || type == null || !credits.containsKey(creditId) || !Double.isFinite(amount) || amount < 0) return false;
        CreditEntry credit = credits.get(creditId); CreditEventEntry event = record.event() == null ? new CreditEventEntry() : record.event();
        UUID id = event.getId() == null || events.stream().anyMatch(value -> event.getId().equals(value.getId())) ? UUID.randomUUID() : event.getId();
        event.setId(id); event.setCreditId(creditId); event.setType(type); event.setAmount(amount); event.setTimestamp(timestamp > 0 ? timestamp : System.currentTimeMillis()); event.setDealName(credit.getDealName()); event.setCreditor(credit.getCreditor()); event.setDebtor(credit.getDebtor()); event.setPaidAmountAfter(credit.getPaidAmount()); event.setRemainingAmountAfter(credit.getRemainingAmount()); event.setAmountAfter(credit.getRemainingAmount()); if (event.getSource() == null) event.setSource("RECOVERY");
        events.add(event); recoveryRecords.remove(record); revision++; boolean saved = persistRecoveredWhenComplete(record); if (!saved) events.remove(event); return saved;
    }
    public synchronized boolean ignoreRecovery(UUID token) { RecoveryRecord record = recoveryRecords.stream().filter(value -> value.token().equals(token)).findFirst().orElse(null); if (record == null) return false; recoveryRecords.remove(record); return persistRecoveredWhenComplete(record); }
    public synchronized boolean createRecoveryBackup(UUID token) { return recoveryRecords.stream().anyMatch(value -> value.token().equals(token)) && DatabaseManager.getInstance().createBackup(); }

    public synchronized int repairWithSafeDefaults() {
        if (recoveryRequired && recoveryRecords.isEmpty()) {
            if (!DatabaseManager.getInstance().resetCorruptDatabaseWithBackup()) return 0;
            load();
            return recoveryRequired ? 0 : 1;
        }
        int repaired = 0;
        for (RecoveryRecord record : new ArrayList<>(recoveryRecords)) {
            boolean saved = switch (record.type()) {
                case CREDIT -> repairCreditWithDefaults(record);
                case PAYMENT -> repairPaymentWithKnownCredit(record);
                case EVENT -> repairEventWithKnownCredit(record);
                case CONFIG, TRANSACTION_LOG -> false;
            };
            if (saved) repaired++;
        }
        return repaired;
    }
    public synchronized void registerEventRecovery(CreditEventEntry event, String sourceKey, Path sourceFile) { addRecovery(RecoveryType.EVENT, sourceKey, null, null, event, "Ungültiges Historien-Ereignis"); }

    private void addRecovery(RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, String message) { recoveryRequired = true; recoveryRecords.add(new RecoveryRecord(UUID.randomUUID(), type, sourceKey, credit, payment, event, FileManager.getDatabaseFile(), message)); DataHealth.reportRecoveryRequired(message); }
    private RecoveryRecord findRecovery(UUID token, RecoveryType type) { return recoveryRecords.stream().filter(value -> value.token().equals(token) && value.type() == type).findFirst().orElse(null); }
    private boolean persistRecoveredWhenComplete(RecoveryRecord removed) { if (!recoveryRecords.isEmpty()) return true; recoveryRequired = false; if (saveAll()) return true; recoveryRequired = true; recoveryRecords.add(removed); return false; }
    private UUID parseId(String value) { try { return UUID.fromString(value); } catch (RuntimeException ignored) { return UUID.randomUUID(); } }

    private boolean repairPaymentWithKnownCredit(RecoveryRecord record) {
        Payment payment = record.payment();
        if (payment == null || payment.getCreditId() == null || !credits.containsKey(payment.getCreditId())) return false;
        return repairPayment(record.token(), payment.getCreditId(), safeAmount(payment.getAmount()), payment.getTimestamp());
    }

    private boolean repairCreditWithDefaults(RecoveryRecord record) {
        CreditEntry credit = record.credit();
        String creditor = safeParty(credit == null ? null : credit.getCreditor(), "recovered_creditor");
        String debtor = safeParty(credit == null ? null : credit.getDebtor(), "recovered_debtor");
        if (creditor.equalsIgnoreCase(debtor)) debtor = "recovered_debtor_" + record.token().toString().substring(0, 8);
        return repairCredit(record.token(), creditor, debtor, safeAmount(credit == null ? 0 : credit.getAmount()),
                credit == null ? System.currentTimeMillis() : credit.getCreatedAt());
    }

    private boolean repairEventWithKnownCredit(RecoveryRecord record) {
        CreditEventEntry event = record.event();
        if (event == null || event.getCreditId() == null || !credits.containsKey(event.getCreditId())) return false;
        CreditEventType type = event.getType() == null ? CreditEventType.CREDIT_UPDATED : event.getType();
        return repairEvent(record.token(), event.getCreditId(), type, Math.max(0D, safeAmount(event.getAmount())), event.getTimestamp());
    }

    private String safeParty(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private double safeAmount(Double value) { return value != null && Double.isFinite(value) && value > 0 ? value : 1D; }
    private double safeAmount(double value) { return Double.isFinite(value) && value > 0 ? value : 1D; }

    private void reconcilePayments() { for (CreditEntry entry : credits.values()) entry.replacePayments(payments.values().stream().filter(payment -> entry.getId().equals(payment.getCreditId())).sorted(Comparator.comparingLong(Payment::getTimestamp)).toList()); }
    private void rebuildPlayerIndex() { players.clear(); for (CreditEntry entry : credits.values()) { getOrCreatePlayer(entry.getDebtor()).addDebtorCredit(entry.getId()); getOrCreatePlayer(entry.getCreditor()).addCreditorCredit(entry.getId()); } }
    private boolean isValidCredit(CreditEntry entry) { return entry != null && entry.getId() != null && entry.getCreditor() != null && !entry.getCreditor().isBlank() && entry.getDebtor() != null && !entry.getDebtor().isBlank() && !entry.getCreditor().equalsIgnoreCase(entry.getDebtor()) && Double.isFinite(entry.getAmount()) && entry.getAmount() > 0; }
    private boolean isValidPayment(Payment payment) { return payment != null && payment.getId() != null && payment.getCreditId() != null && payment.getAmount() != null && Double.isFinite(payment.getAmount()) && payment.getAmount() > 0; }

    public Optional<CreditEntry> findCreditById(UUID id) { return Optional.ofNullable(credits.get(id)); }
    public Optional<CreditEntry> findCreditByShortId(String shortId) { String lower = shortId.toLowerCase(Locale.ROOT); return credits.values().stream().filter(credit -> credit.getId().toString().toLowerCase(Locale.ROOT).startsWith(lower)).findFirst(); }
    public Optional<CreditEntry> findCreditByName(String name) { String lower = name.toLowerCase(Locale.ROOT); return credits.values().stream().filter(credit -> lower.equals(credit.getDealName())).findFirst(); }
    public Optional<CreditEntry> findCreditByNamePrefix(String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return credits.values().stream().filter(credit -> credit.getDealName() != null && credit.getDealName().startsWith(lower)).findFirst(); }
    public List<CreditEntry> getAllCredits() { return new ArrayList<>(credits.values()); }
    public List<CreditEntry> getCreditsByDebtor(String playerName) { return getCredits(playerName, true); }
    public List<CreditEntry> getCreditsByCreditor(String playerName) { return getCredits(playerName, false); }
    private List<CreditEntry> getCredits(String playerName, boolean debtor) { PlayerCreditData data = players.get(playerName.toLowerCase(Locale.ROOT)); if (data == null) return List.of(); List<UUID> ids = debtor ? data.getCreditsAsDebtor() : data.getCreditsAsCreditor(); List<CreditEntry> result = new ArrayList<>(); for (UUID id : ids) if (credits.containsKey(id)) result.add(credits.get(id)); return result; }
    public PlayerCreditData getOrCreatePlayer(String playerName) { return players.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), PlayerCreditData::new); }
    public List<Payment> getPaymentsByCreditId(UUID creditId) { return payments.values().stream().filter(payment -> creditId.equals(payment.getCreditId())).sorted(Comparator.comparingLong(Payment::getTimestamp)).toList(); }
    public List<Payment> getAllPayments() { return new ArrayList<>(payments.values()); }

    public enum RecoveryType { CREDIT, PAYMENT, EVENT, CONFIG, TRANSACTION_LOG }
    public record RecoveryRecord(UUID token, RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, Path sourceFile, String message) { }
}

package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.money.MoneyRules;
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
    private volatile Map<UUID, CreditEntry> credits = new ConcurrentHashMap<>();
    private volatile Map<String, PlayerCreditData> players = new ConcurrentHashMap<>();
    private volatile Map<UUID, Payment> payments = new ConcurrentHashMap<>();
    private volatile List<CreditEventEntry> events = new ArrayList<>();
    private volatile List<RecoveryRecord> recoveryRecords = new ArrayList<>();
    private List<RecoveryRecord> stagedRecoveryRecords;
    private boolean recoveryRequired;
    private long revision;

    public synchronized boolean load() {
        try {
            Map<UUID, CreditEntry> nextCredits = new ConcurrentHashMap<>();
            Map<String, PlayerCreditData> nextPlayers = new ConcurrentHashMap<>();
            Map<UUID, Payment> nextPayments = new ConcurrentHashMap<>();
            List<CreditEventEntry> nextEvents = new ArrayList<>();
            List<RecoveryRecord> nextRecoveryRecords = new ArrayList<>();
            stagedRecoveryRecords = nextRecoveryRecords;
            DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
            for (CreditEntry entry : state.credits()) {
                if (!isValidCredit(entry)) {
                    addRecovery(RecoveryType.CREDIT, entry == null || entry.getId() == null ? "unknown" : entry.getId().toString(), entry, null, null, "Ungültiger Deal in der Datenbank");
                    continue;
                }
                if (entry.getPayments() == null) entry.setPayments(new ArrayList<>());
                nextCredits.put(entry.getId(), entry);
            }
            for (Payment payment : state.payments()) {
                if (!isValidPayment(payment) || !nextCredits.containsKey(payment.getCreditId())) {
                    addRecovery(RecoveryType.PAYMENT, payment == null || payment.getId() == null ? "unknown" : payment.getId().toString(), null, payment, null, "Ungültige oder verwaiste Zahlung in der Datenbank");
                    continue;
                }
                CreditEntry credit = nextCredits.get(payment.getCreditId());
                if (payment.getFromPlayer() == null || payment.getFromPlayer().isBlank()) payment.setFromPlayer(credit.getDebtor());
                if (payment.getToPlayer() == null || payment.getToPlayer().isBlank()) payment.setToPlayer(credit.getCreditor());
                nextPayments.put(payment.getId(), payment);
            }
            for (CreditEventEntry event : state.events()) {
                if (event == null || event.getId() == null || event.getType() == null || event.getCreditId() == null || !nextCredits.containsKey(event.getCreditId())) {
                    addRecovery(RecoveryType.EVENT, event == null || event.getId() == null ? "unknown" : event.getId().toString(), null, null, event, "Ungültiges Historien-Ereignis in der Datenbank");
                    continue;
                }
                nextEvents.add(event);
            }
            reconcilePayments(nextCredits, nextPayments);
            rebuildPlayerIndex(nextPlayers, nextCredits);
            if (!DatabaseManager.getInstance().isSafeForWrites()) {
                addRecovery(RecoveryType.TRANSACTION_LOG, "database-health", null, null, null,
                        "Datenprüfung erforderlich – vorhandene Daten wurden nicht gelöscht.");
            }
            credits = nextCredits;
            payments = nextPayments;
            players = nextPlayers;
            events = nextEvents;
            recoveryRecords = nextRecoveryRecords;
            recoveryRequired = !nextRecoveryRecords.isEmpty();
            revision = DatabaseManager.getInstance().revision();
            for (RecoveryRecord record : nextRecoveryRecords) DataHealth.reportRecoveryRequired(record.message());
            CreditManagerClient.LOGGER.info("Loaded " + nextCredits.size() + " credits and " + nextPayments.size() + " payments from the local database.");
            return true;
        } catch (RuntimeException exception) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Lokale Datenbank konnte nicht gelesen werden");
            CreditManagerClient.LOGGER.error("Could not load CreditManager database", exception);
            return false;
        } finally {
            stagedRecoveryRecords = null;
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
    public synchronized void replaceLoadedCredit(CreditEntry entry) {
        if (entry == null || entry.getId() == null) return;
        credits.put(entry.getId(), entry);
        rebuildPlayerIndex();
    }
    public synchronized void applyCommittedMutation(CreditEntry credit, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                                             List<CreditEventEntry> newEvents) {
        if (credit == null || credit.getId() == null) return;
        Map<UUID, CreditEntry> nextCredits = new ConcurrentHashMap<>(credits);
        Map<UUID, Payment> nextPayments = new ConcurrentHashMap<>(payments);
        Map<String, PlayerCreditData> nextPlayers = new ConcurrentHashMap<>();
        List<CreditEventEntry> nextEvents = new ArrayList<>(events);
        nextCredits.put(credit.getId(), credit);
        if (paymentDeletions != null) for (UUID id : paymentDeletions) if (id != null) nextPayments.remove(id);
        if (paymentUpserts != null) for (Payment payment : paymentUpserts) if (payment != null && payment.getId() != null) nextPayments.put(payment.getId(), payment);
        if (newEvents != null) for (CreditEventEntry event : newEvents) {
            if (event != null && event.getId() != null && nextEvents.stream().noneMatch(existing -> event.getId().equals(existing.getId()))) nextEvents.add(event);
        }
        reconcilePayments(nextCredits, nextPayments);
        rebuildPlayerIndex(nextPlayers, nextCredits);
        credits = nextCredits;
        payments = nextPayments;
        players = nextPlayers;
        events = nextEvents;
        revision = DatabaseManager.getInstance().revision();
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
    public synchronized void replaceEvents(List<CreditEventEntry> values) { events = values == null ? new ArrayList<>() : new ArrayList<>(values); revision++; }
    public synchronized List<RecoveryRecord> getRecoveryRecords() { return List.copyOf(recoveryRecords); }

    public synchronized boolean repairCredit(UUID token, String creditor, String debtor, double amount, long createdAt) {
        try {
            return repairCreditMinor(token, creditor, debtor, MoneyRules.fromLegacyDouble(amount, true).minorUnits(), createdAt);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public synchronized boolean repairCreditMinor(UUID token, String creditor, String debtor, long amountMinor, long createdAt) {
        RecoveryRecord record = findRecovery(token, RecoveryType.CREDIT);
        if (record == null || creditor == null || debtor == null || creditor.isBlank() || debtor.isBlank() || !MoneyRules.isPositive(amountMinor) || creditor.equalsIgnoreCase(debtor)) return false;
        CreditEntry entry = copyCredit(record.credit());
        UUID id = parseId(record.sourceKey());
        entry.setId(id); entry.setCreditor(creditor.trim().toLowerCase(Locale.ROOT)); entry.setDebtor(debtor.trim().toLowerCase(Locale.ROOT)); entry.setAmountMinor(amountMinor); entry.setCreatedAt(createdAt > 0 ? createdAt : 1L);
        if (entry.getDealName() == null || entry.getDealName().isBlank()) entry.setDealName(CreditEntry.buildDealName(entry.getDebtor(), entry.getCreditor(), null));
        if (!DatabaseManager.getInstance().repairCredit(entry, record.message())) return false;
        publishRepair(record);
        return true;
    }

    public synchronized boolean repairPayment(UUID token, UUID creditId, double amount, long timestamp) {
        try {
            return repairPaymentMinor(token, creditId, MoneyRules.fromLegacyDouble(amount, true).minorUnits(), timestamp);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public synchronized boolean repairPaymentMinor(UUID token, UUID creditId, long amountMinor, long timestamp) {
        RecoveryRecord record = findRecovery(token, RecoveryType.PAYMENT);
        if (record == null || creditId == null || !credits.containsKey(creditId) || !MoneyRules.isPositive(amountMinor) || amountMinor > credits.get(creditId).getAmountMinor()) return false;
        CreditEntry credit = credits.get(creditId);
        Payment payment = copyPayment(record.payment());
        payment.setId(parseId(record.sourceKey())); payment.setCreditId(creditId); payment.setAmountMinor(amountMinor); payment.setTimestamp(timestamp > 0 ? timestamp : 1L); payment.setFromPlayer(credit.getDebtor()); payment.setToPlayer(credit.getCreditor());
        if (payment.getSource() == null) payment.setSource("RECOVERY");
        if (!DatabaseManager.getInstance().repairPayment(payment, record.message())) return false;
        publishRepair(record);
        return true;
    }

    public synchronized boolean repairEvent(UUID token, UUID creditId, CreditEventType type, double amount, long timestamp) {
        try {
            return repairEventMinor(token, creditId, type, MoneyRules.fromLegacyDouble(amount, false).minorUnits(), timestamp);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public synchronized boolean repairEventMinor(UUID token, UUID creditId, CreditEventType type, long amountMinor, long timestamp) {
        RecoveryRecord record = findRecovery(token, RecoveryType.EVENT);
        if (record == null || creditId == null || type == null || !credits.containsKey(creditId) || amountMinor < 0L || !MoneyRules.isValid(amountMinor)) return false;
        CreditEntry credit = credits.get(creditId);
        CreditEventEntry event = copyEvent(record.event());
        event.setId(event.getId() == null ? parseId(record.sourceKey()) : event.getId());
        event.setCreditId(creditId); event.setType(type); event.setAmountMinor(amountMinor); event.setTimestamp(timestamp > 0 ? timestamp : 1L); event.setDealName(credit.getDealName()); event.setCreditor(credit.getCreditor()); event.setDebtor(credit.getDebtor()); event.setPaidAmountAfterMinor(credit.getPaidAmountMinor()); event.setRemainingAmountAfterMinor(credit.getRemainingAmountMinor()); event.setAmountAfterMinor(credit.getRemainingAmountMinor()); if (event.getSource() == null) event.setSource("RECOVERY");
        if (!DatabaseManager.getInstance().repairEvent(event, record.message())) return false;
        publishRepair(record);
        return true;
    }
    public synchronized boolean ignoreRecovery(UUID token) {
        for (int index = 0; index < recoveryRecords.size(); index++) {
            RecoveryRecord record = recoveryRecords.get(index);
            if (!record.token().equals(token) || record.status() == RecoveryStatus.ACKNOWLEDGED) continue;
            recoveryRecords.set(index, record.withStatus(RecoveryStatus.ACKNOWLEDGED));
            recoveryRequired = true;
            return true;
        }
        return false;
    }
    public synchronized boolean discardRecovery(UUID token, boolean confirmed) {
        if (!confirmed) return false;
        RecoveryRecord record = recoveryRecords.stream().filter(value -> value.token().equals(token)).findFirst().orElse(null);
        if (record == null || record.type() == RecoveryType.CONFIG || record.type() == RecoveryType.TRANSACTION_LOG) return false;
        UUID id;
        try {
            id = UUID.fromString(record.sourceKey());
        } catch (RuntimeException exception) {
            return false;
        }
        DatabaseManager.DiscardRecordType type = DatabaseManager.DiscardRecordType.valueOf(record.type().name());
        if (!DatabaseManager.getInstance().discardRecoveryRecord(type, id, record.message(), true)) return false;
        publishRepair(record);
        return true;
    }
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

    private void addRecovery(RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, String message) {
        RecoveryRecord record = new RecoveryRecord(UUID.randomUUID(), type, sourceKey, credit, payment, event, FileManager.getDatabaseFile(), message);
        if (stagedRecoveryRecords != null) {
            stagedRecoveryRecords.add(record);
            return;
        }
        recoveryRequired = true;
        recoveryRecords.add(record);
        DataHealth.reportRecoveryRequired(message);
    }
    private RecoveryRecord findRecovery(UUID token, RecoveryType type) { return recoveryRecords.stream().filter(value -> value.token().equals(token) && value.type() == type).findFirst().orElse(null); }
    private UUID parseId(String value) { try { return UUID.fromString(value); } catch (RuntimeException ignored) { return UUID.nameUUIDFromBytes(("recovery:" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }

    private void publishRepair(RecoveryRecord record) {
        recoveryRecords.remove(record);
        if (!load()) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Reparatur wurde gespeichert, konnte aber nicht vollständig in den Laufzeitstatus geladen werden.");
        }
    }

    private boolean repairPaymentWithKnownCredit(RecoveryRecord record) {
        Payment payment = record.payment();
        if (payment == null || payment.getCreditId() == null || !credits.containsKey(payment.getCreditId()) || !MoneyRules.isPositive(payment.getAmountMinor())) return false;
        return repairPaymentMinor(record.token(), payment.getCreditId(), payment.getAmountMinor(), payment.getTimestamp());
    }

    private boolean repairCreditWithDefaults(RecoveryRecord record) {
        CreditEntry credit = record.credit();
        String creditor = safeParty(credit == null ? null : credit.getCreditor(), "recovered_creditor");
        String debtor = safeParty(credit == null ? null : credit.getDebtor(), "recovered_debtor");
        if (creditor.equalsIgnoreCase(debtor)) debtor = "recovered_debtor_" + record.token().toString().substring(0, 8);
        if (credit == null || !MoneyRules.isPositive(credit.getAmountMinor())) return false;
        return repairCreditMinor(record.token(), creditor, debtor, credit.getAmountMinor(), credit.getCreatedAt());
    }

    private boolean repairEventWithKnownCredit(RecoveryRecord record) {
        CreditEventEntry event = record.event();
        if (event == null || event.getCreditId() == null || !credits.containsKey(event.getCreditId())) return false;
        CreditEventType type = event.getType() == null ? CreditEventType.CREDIT_UPDATED : event.getType();
        if (event.getAmountMinor() < 0L || !MoneyRules.isValid(event.getAmountMinor())) return false;
        return repairEventMinor(record.token(), event.getCreditId(), type, event.getAmountMinor(), event.getTimestamp());
    }

    private String safeParty(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private CreditEntry copyCredit(CreditEntry source) {
        CreditEntry copy = new CreditEntry();
        if (source == null) return copy;
        copy.setId(source.getId()); copy.setDealName(source.getDealName()); copy.setCreditor(source.getCreditor()); copy.setDebtor(source.getDebtor()); copy.setAmountMinor(source.getAmountMinor()); copy.setPaidAmountMinor(source.getPaidAmountMinor()); copy.setCreatedAt(source.getCreatedAt()); copy.setDueDate(source.getDueDate()); copy.setStatus(source.getStatus()); copy.setNote(source.getNote()); copy.setCompletedAt(source.getCompletedAt()); copy.setArchived(source.isArchived()); copy.setPayments(new ArrayList<>(source.getPayments()));
        return copy;
    }

    private Payment copyPayment(Payment source) {
        Payment copy = new Payment();
        if (source == null) return copy;
        copy.setId(source.getId()); copy.setCreditId(source.getCreditId()); copy.setFromPlayer(source.getFromPlayer()); copy.setToPlayer(source.getToPlayer()); copy.setAmountMinor(source.getAmountMinor()); copy.setPaymentKind(source.getPaymentKind()); copy.setItems(new ArrayList<>(source.getItems())); copy.setItemNbt(source.getItemNbt()); copy.setItemNbtEntries(source.getItemNbtEntries()); copy.setTimestamp(source.getTimestamp()); copy.setSource(source.getSource()); copy.setPaylogId(source.getPaylogId()); copy.setNote(source.getNote());
        return copy;
    }

    private CreditEventEntry copyEvent(CreditEventEntry source) {
        CreditEventEntry copy = new CreditEventEntry();
        if (source == null) return copy;
        copy.setId(source.getId()); copy.setTimestamp(source.getTimestamp()); copy.setType(source.getType()); copy.setCreditId(source.getCreditId()); copy.setDealName(source.getDealName()); copy.setCreditor(source.getCreditor()); copy.setDebtor(source.getDebtor()); copy.setAmountMinor(source.getAmountMinor()); copy.setPaidAmountAfterMinor(source.getPaidAmountAfterMinor()); copy.setRemainingAmountAfterMinor(source.getRemainingAmountAfterMinor()); copy.setNote(source.getNote()); copy.setAmountBeforeMinor(source.getAmountBeforeMinor()); copy.setAmountAfterMinor(source.getAmountAfterMinor()); copy.setActor(source.getActor()); copy.setSource(source.getSource()); copy.setItemPayment(source.isItemPayment());
        return copy;
    }

    private void reconcilePayments() { reconcilePayments(credits, payments); }
    private void reconcilePayments(Map<UUID, CreditEntry> targetCredits, Map<UUID, Payment> targetPayments) {
        Map<UUID, List<Payment>> paymentsByCredit = new LinkedHashMap<>();
        for (Payment payment : targetPayments.values()) {
            paymentsByCredit.computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>()).add(payment);
        }
        for (List<Payment> creditPayments : paymentsByCredit.values()) {
            creditPayments.sort(Comparator.comparingLong(Payment::getTimestamp));
        }
        for (CreditEntry entry : targetCredits.values()) {
            entry.replacePayments(paymentsByCredit.getOrDefault(entry.getId(), List.of()));
        }
    }
    private void rebuildPlayerIndex() { rebuildPlayerIndex(players, credits); }
    private void rebuildPlayerIndex(Map<String, PlayerCreditData> targetPlayers, Map<UUID, CreditEntry> targetCredits) {
        targetPlayers.clear();
        for (CreditEntry entry : targetCredits.values()) {
            targetPlayers.computeIfAbsent(entry.getDebtor().toLowerCase(Locale.ROOT), PlayerCreditData::new).addDebtorCredit(entry.getId());
            targetPlayers.computeIfAbsent(entry.getCreditor().toLowerCase(Locale.ROOT), PlayerCreditData::new).addCreditorCredit(entry.getId());
        }
    }
    private boolean isValidCredit(CreditEntry entry) { return entry != null && entry.getId() != null && entry.getCreditor() != null && !entry.getCreditor().isBlank() && entry.getDebtor() != null && !entry.getDebtor().isBlank() && !entry.getCreditor().equalsIgnoreCase(entry.getDebtor()) && MoneyRules.isPositive(entry.getAmountMinor()); }
    private boolean isValidPayment(Payment payment) { return payment != null && payment.getId() != null && payment.getCreditId() != null && MoneyRules.isPositive(payment.getAmountMinor()); }

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
    public enum RecoveryStatus { OPEN, ACKNOWLEDGED }
    public record RecoveryRecord(UUID token, RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, Path sourceFile, String message, RecoveryStatus status) {
        public RecoveryRecord(UUID token, RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, Path sourceFile, String message) {
            this(token, type, sourceKey, credit, payment, event, sourceFile, message, RecoveryStatus.OPEN);
        }

        public RecoveryRecord withStatus(RecoveryStatus replacement) {
            return new RecoveryRecord(token, type, sourceKey, credit, payment, event, sourceFile, message, replacement);
        }
    }
}

package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.money.MoneyAggregate;
import op.creditmanager.client.money.CreditStatusRules;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class CreditRepository {
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();
    private volatile RepositoryState state = RepositoryState.empty();
    private volatile List<RecoveryRecord> recoveryRecords = List.of();
    private List<RecoveryRecord> stagedRecoveryRecords;
    private volatile boolean recoveryRequired;
    private volatile long revision;

    private record CreditParties(String creditor, String debtor) {
        private static CreditParties from(CreditEntry entry) {
            return new CreditParties(normalizePlayer(entry.getCreditor()), normalizePlayer(entry.getDebtor()));
        }
    }

    private record PendingRecovery(RecoveryType type, String sourceKey, CreditEntry credit,
                                   Payment payment, CreditEventEntry event, String message) {
    }

    private record RepositoryState(Map<UUID, CreditEntry> credits,
                                   Map<String, PlayerCreditData> players,
                                   Map<UUID, Payment> payments,
                                   Map<UUID, List<Payment>> paymentsByCreditId,
                                   Map<UUID, CreditParties> indexedParties) {
        private static RepositoryState empty() {
            return new RepositoryState(new HashMap<>(), new HashMap<>(), new HashMap<>(),
                    new HashMap<>(), new HashMap<>());
        }
    }

    public synchronized boolean load() {
        recoveryRequired = true;
        try {
            Map<UUID, CreditEntry> nextCredits = new HashMap<>();
            Map<String, PlayerCreditData> nextPlayers = new HashMap<>();
            Map<UUID, Payment> nextPayments = new HashMap<>();
            Map<UUID, List<Payment>> nextPaymentsByCreditId = new HashMap<>();
            Map<UUID, CreditParties> nextIndexedParties = new HashMap<>();
            List<RecoveryRecord> nextRecoveryRecords = new ArrayList<>();
            stagedRecoveryRecords = nextRecoveryRecords;
            DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadRuntimeCreditState();
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
            rebuildPaymentIndex(nextPaymentsByCreditId, nextPayments);
            reconcilePayments(nextCredits, nextPaymentsByCreditId);
            rebuildPlayerIndex(nextPlayers, nextIndexedParties, nextCredits);
            if (!DatabaseManager.getInstance().isSafeForWrites()) {
                addRecovery(RecoveryType.TRANSACTION_LOG, "database-health", null, null, null,
                        "Datenprüfung erforderlich – vorhandene Daten wurden nicht gelöscht.");
            }
            RepositoryState nextState = new RepositoryState(nextCredits, nextPlayers, nextPayments,
                    nextPaymentsByCreditId, nextIndexedParties);
            stateLock.writeLock().lock();
            try {
                this.state = nextState;
            } finally {
                stateLock.writeLock().unlock();
            }
            recoveryRecords = List.copyOf(nextRecoveryRecords);
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

    public boolean isWritable() { return !recoveryRequired && DatabaseManager.getInstance().isSafeForWrites(); }
    public long getRevision() { return revision; }

    public CreditStatisticsSnapshot snapshotOpenCredits(String playerName) {
        String normalized = playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
        stateLock.readLock().lock();
        try {
            RepositoryState current = state;
            PlayerCreditData data = current.players().get(normalized);
            if (data == null) return new CreditStatisticsSnapshot(normalized, List.of(), List.of(), revision);
            List<CreditStatisticsSnapshot.OpenCredit> claims = snapshotOpenCredits(current, data.getCreditsAsCreditor());
            List<CreditStatisticsSnapshot.OpenCredit> debts = snapshotOpenCredits(current, data.getCreditsAsDebtor());
            return new CreditStatisticsSnapshot(normalized, claims, debts, revision);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public synchronized boolean saveAll() {
        if (recoveryRequired) return false;
        List<CreditEntry> creditSnapshot;
        List<Payment> paymentSnapshot;
        stateLock.readLock().lock();
        try {
            creditSnapshot = new ArrayList<>(state.credits().values());
            paymentSnapshot = new ArrayList<>(state.payments().values());
        } finally {
            stateLock.readLock().unlock();
        }
        boolean saved = DatabaseManager.getInstance().replaceCreditDataPreservingEvents(creditSnapshot, paymentSnapshot);
        if (saved) { revision = DatabaseManager.getInstance().revision(); FileManager.tidyAfterSuccessfulSave(); }
        return saved;
    }

    public synchronized void putCredit(CreditEntry entry) {
        if (entry == null || entry.getId() == null) return;
        stateLock.writeLock().lock();
        try {
            putCreditIndexed(state, entry);
            revision++;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public synchronized void replaceLoadedCredit(CreditEntry entry) {
        if (entry == null || entry.getId() == null) return;
        stateLock.writeLock().lock();
        try {
            putCreditIndexed(state, entry);
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public synchronized void applyCommittedMutation(CreditEntry credit, List<Payment> paymentUpserts,
                                                    List<UUID> paymentDeletions,
                                                    List<CreditEventEntry> newEvents, long committedRevision) {
        if (credit == null || credit.getId() == null) return;
        List<PendingRecovery> pendingRecoveries = new ArrayList<>();
        stateLock.writeLock().lock();
        try {
            RepositoryState current = state;
            HashSet<UUID> affectedCredits = new HashSet<>();
            affectedCredits.add(credit.getId());
            putCreditIndexed(current, credit);
            if (paymentDeletions != null) {
                for (UUID id : paymentDeletions) {
                    if (id == null) continue;
                    Payment removed = current.payments().remove(id);
                    if (removed == null) continue;
                    affectedCredits.add(removed.getCreditId());
                    removePaymentFromIndex(current, removed);
                }
            }
            if (paymentUpserts != null) {
                for (Payment payment : paymentUpserts) {
                    if (payment == null || payment.getId() == null || payment.getCreditId() == null) continue;
                    Payment previous = current.payments().put(payment.getId(), payment);
                    if (previous != null) {
                        affectedCredits.add(previous.getCreditId());
                        removePaymentFromIndex(current, previous);
                    }
                    affectedCredits.add(payment.getCreditId());
                    addPaymentToIndex(current, payment);
                }
            }
            for (UUID creditId : affectedCredits) reconcileCredit(current, creditId, pendingRecoveries);
            if (!pendingRecoveries.isEmpty()) recoveryRequired = true;
            revision = committedRevision;
        } finally {
            stateLock.writeLock().unlock();
        }
        for (PendingRecovery pending : pendingRecoveries) {
            addRecovery(pending.type(), pending.sourceKey(), pending.credit(), pending.payment(), pending.event(),
                    pending.message());
        }
    }

    public synchronized void putPayment(Payment payment) {
        if (payment == null || payment.getId() == null || payment.getCreditId() == null) return;
        stateLock.writeLock().lock();
        try {
            RepositoryState current = state;
            Payment previous = current.payments().put(payment.getId(), payment);
            if (previous != null) removePaymentFromIndex(current, previous);
            addPaymentToIndex(current, payment);
            if (previous != null && !previous.getCreditId().equals(payment.getCreditId())) {
                CreditEntry previousCredit = current.credits().get(previous.getCreditId());
                if (previousCredit != null) previousCredit.replacePayments(sortedPayments(current, previous.getCreditId()));
            }
            CreditEntry entry = current.credits().get(payment.getCreditId());
            if (entry != null) entry.replacePayments(sortedPayments(current, payment.getCreditId()));
            revision++;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public synchronized void deleteCredit(UUID id) {
        if (id == null) return;
        stateLock.writeLock().lock();
        try {
            RepositoryState current = state;
            CreditEntry removed = current.credits().remove(id);
            if (removed == null) return;
            removeCreditFromPlayerIndex(current, id, current.indexedParties().remove(id));
            List<Payment> removedPayments = current.paymentsByCreditId().remove(id);
            if (removedPayments != null) {
                for (Payment payment : removedPayments) current.payments().remove(payment.getId());
            }
            revision++;
        } finally {
            stateLock.writeLock().unlock();
        }
    }

    public synchronized void deletePayment(UUID paymentId) {
        if (paymentId == null) return;
        stateLock.writeLock().lock();
        try {
            RepositoryState current = state;
            Payment payment = current.payments().remove(paymentId);
            if (payment == null) return;
            removePaymentFromIndex(current, payment);
            CreditEntry entry = current.credits().get(payment.getCreditId());
            if (entry != null) entry.removePayment(paymentId);
            revision++;
        } finally {
            stateLock.writeLock().unlock();
        }
    }
    public List<RecoveryRecord> getRecoveryRecords() { return recoveryRecords; }

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
        CreditEntry credit = findCreditById(creditId).orElse(null);
        if (record == null || credit == null || !MoneyRules.isPositive(amountMinor) || amountMinor > credit.getAmountMinor()) return false;
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
        CreditEntry credit = findCreditById(creditId).orElse(null);
        if (record == null || credit == null || type == null || amountMinor < 0L || !MoneyRules.isValid(amountMinor)) return false;
        CreditEventEntry event = copyEvent(record.event());
        event.setId(event.getId() == null ? parseId(record.sourceKey()) : event.getId());
        event.setCreditId(creditId); event.setType(type); event.setAmountMinor(amountMinor); event.setTimestamp(timestamp > 0 ? timestamp : 1L); event.setDealName(credit.getDealName()); event.setCreditor(credit.getCreditor()); event.setDebtor(credit.getDebtor()); event.setPaidAmountAfterMinor(credit.getPaidAmountMinor()); event.setRemainingAmountAfterMinor(credit.getRemainingAmountMinor()); event.setAmountAfterMinor(credit.getRemainingAmountMinor()); if (event.getSource() == null) event.setSource("RECOVERY");
        if (!DatabaseManager.getInstance().repairEvent(event, record.message())) return false;
        publishRepair(record);
        return true;
    }
    public synchronized boolean ignoreRecovery(UUID token) {
        List<RecoveryRecord> current = recoveryRecords;
        for (int index = 0; index < current.size(); index++) {
            RecoveryRecord record = current.get(index);
            if (!record.token().equals(token) || record.status() == RecoveryStatus.ACKNOWLEDGED) continue;
            List<RecoveryRecord> updated = new ArrayList<>(current);
            updated.set(index, record.withStatus(RecoveryStatus.ACKNOWLEDGED));
            recoveryRecords = List.copyOf(updated);
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
    public synchronized boolean createRecoveryBackup(UUID token) { return recoveryRecords.stream().anyMatch(value -> value.token().equals(token)) && DatabaseManager.getInstance().createRecoverySnapshot(); }

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
    private synchronized void addRecovery(RecoveryType type, String sourceKey, CreditEntry credit, Payment payment, CreditEventEntry event, String message) {
        RecoveryRecord record = new RecoveryRecord(UUID.randomUUID(), type, sourceKey, credit, payment, event, FileManager.getDatabaseFile(), message);
        if (stagedRecoveryRecords != null) {
            stagedRecoveryRecords.add(record);
            return;
        }
        recoveryRequired = true;
        List<RecoveryRecord> updated = new ArrayList<>(recoveryRecords);
        updated.add(record);
        recoveryRecords = List.copyOf(updated);
        DataHealth.reportRecoveryRequired(message);
    }
    private RecoveryRecord findRecovery(UUID token, RecoveryType type) { return recoveryRecords.stream().filter(value -> value.token().equals(token) && value.type() == type).findFirst().orElse(null); }
    private UUID parseId(String value) { try { return UUID.fromString(value); } catch (RuntimeException ignored) { return UUID.nameUUIDFromBytes(("recovery:" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }

    private void publishRepair(RecoveryRecord record) {
        List<RecoveryRecord> updated = new ArrayList<>(recoveryRecords);
        updated.remove(record);
        recoveryRecords = List.copyOf(updated);
        if (!load()) {
            recoveryRequired = true;
            DataHealth.reportRecoveryRequired("Reparatur wurde gespeichert, konnte aber nicht vollständig in den Laufzeitstatus geladen werden.");
        }
    }

    private boolean repairPaymentWithKnownCredit(RecoveryRecord record) {
        Payment payment = record.payment();
        if (payment == null || payment.getCreditId() == null || findCreditById(payment.getCreditId()).isEmpty() || !MoneyRules.isPositive(payment.getAmountMinor())) return false;
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
        if (event == null || event.getCreditId() == null || findCreditById(event.getCreditId()).isEmpty()) return false;
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

    private void rebuildPaymentIndex(Map<UUID, List<Payment>> targetIndex, Map<UUID, Payment> targetPayments) {
        targetIndex.clear();
        for (Payment payment : targetPayments.values()) {
            targetIndex.computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>()).add(payment);
        }
        for (List<Payment> creditPayments : targetIndex.values()) {
            creditPayments.sort(Comparator.comparingLong(Payment::getTimestamp));
        }
    }

    private void reconcilePayments(Map<UUID, CreditEntry> targetCredits,
                                   Map<UUID, List<Payment>> targetPaymentsByCreditId) {
        for (UUID creditId : targetCredits.keySet()) {
            reconcileCredit(targetCredits, targetPaymentsByCreditId, creditId);
        }
    }

    private void reconcileCredit(RepositoryState current, UUID creditId, List<PendingRecovery> pendingRecoveries) {
        reconcileCredit(current.credits(), current.paymentsByCreditId(), creditId, pendingRecoveries);
    }

    private void reconcileCredit(Map<UUID, CreditEntry> targetCredits,
                                 Map<UUID, List<Payment>> targetPaymentsByCreditId, UUID creditId) {
        reconcileCredit(targetCredits, targetPaymentsByCreditId, creditId, null);
    }

    private void reconcileCredit(Map<UUID, CreditEntry> targetCredits,
                                 Map<UUID, List<Payment>> targetPaymentsByCreditId, UUID creditId,
                                 List<PendingRecovery> pendingRecoveries) {
        CreditEntry entry = targetCredits.get(creditId);
        if (entry == null) return;
        List<Payment> values = targetPaymentsByCreditId.getOrDefault(creditId, List.of());
        if (values.size() > 1) values.sort(Comparator.comparingLong(Payment::getTimestamp));
        java.math.BigInteger total = MoneyAggregate.sum(values, Payment::getAmountMinor);
        boolean valid = values.stream().allMatch(payment -> MoneyRules.isPositive(payment.getAmountMinor()))
                && total.signum() >= 0 && total.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) <= 0
                && total.compareTo(java.math.BigInteger.valueOf(entry.getAmountMinor())) <= 0
                && total.equals(java.math.BigInteger.valueOf(entry.getPaidAmountMinor()));
        if (valid) {
            entry.replacePayments(values);
        } else {
            entry.setPayments(values);
            String message = "Zahlungssumme ist außerhalb des sicheren Bereichs; Rohdaten wurden nicht normalisiert.";
            if (pendingRecoveries == null) {
                addRecovery(RecoveryType.CREDIT, entry.getId().toString(), entry, null, null, message);
            } else {
                pendingRecoveries.add(new PendingRecovery(RecoveryType.CREDIT, entry.getId().toString(), entry,
                        null, null, message));
            }
        }
    }

    private void rebuildPlayerIndex(Map<String, PlayerCreditData> targetPlayers,
                                    Map<UUID, CreditParties> targetIndexedParties,
                                    Map<UUID, CreditEntry> targetCredits) {
        targetPlayers.clear();
        targetIndexedParties.clear();
        for (CreditEntry entry : targetCredits.values()) {
            CreditParties parties = CreditParties.from(entry);
            targetIndexedParties.put(entry.getId(), parties);
            addPlayerRole(targetPlayers, parties.debtor(), entry.getId(), true);
            addPlayerRole(targetPlayers, parties.creditor(), entry.getId(), false);
        }
    }

    private void putCreditIndexed(RepositoryState current, CreditEntry entry) {
        UUID creditId = entry.getId();
        CreditParties previous = current.indexedParties().get(creditId);
        CreditParties replacement = CreditParties.from(entry);
        current.credits().put(creditId, entry);
        if (previous == null || !previous.debtor().equals(replacement.debtor())) {
            if (previous != null) removePlayerRole(current.players(), previous.debtor(), creditId, true);
            addPlayerRole(current.players(), replacement.debtor(), creditId, true);
        }
        if (previous == null || !previous.creditor().equals(replacement.creditor())) {
            if (previous != null) removePlayerRole(current.players(), previous.creditor(), creditId, false);
            addPlayerRole(current.players(), replacement.creditor(), creditId, false);
        }
        current.indexedParties().put(creditId, replacement);
    }

    private void removeCreditFromPlayerIndex(RepositoryState current, UUID creditId, CreditParties parties) {
        if (parties == null) return;
        removePlayerRole(current.players(), parties.debtor(), creditId, true);
        removePlayerRole(current.players(), parties.creditor(), creditId, false);
    }

    private static void addPlayerRole(Map<String, PlayerCreditData> targetPlayers, String player,
                                      UUID creditId, boolean debtor) {
        PlayerCreditData data = targetPlayers.computeIfAbsent(player, PlayerCreditData::new);
        if (debtor) data.addDebtorCredit(creditId);
        else data.addCreditorCredit(creditId);
    }

    private static void removePlayerRole(Map<String, PlayerCreditData> targetPlayers, String player,
                                         UUID creditId, boolean debtor) {
        PlayerCreditData data = targetPlayers.get(player);
        if (data == null) return;
        if (debtor) data.removeDebtorCredit(creditId);
        else data.removeCreditorCredit(creditId);
        if (data.getCreditsAsDebtor().isEmpty() && data.getCreditsAsCreditor().isEmpty()) targetPlayers.remove(player);
    }

    private static String normalizePlayer(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void addPaymentToIndex(RepositoryState current, Payment payment) {
        List<Payment> values = current.paymentsByCreditId()
                .computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>());
        values.removeIf(existing -> payment.getId().equals(existing.getId()));
        values.add(payment);
        values.sort(Comparator.comparingLong(Payment::getTimestamp));
    }

    private static void removePaymentFromIndex(RepositoryState current, Payment payment) {
        List<Payment> values = current.paymentsByCreditId().get(payment.getCreditId());
        if (values == null) return;
        values.removeIf(existing -> payment.getId().equals(existing.getId()));
        if (values.isEmpty()) current.paymentsByCreditId().remove(payment.getCreditId());
    }

    private static List<Payment> sortedPayments(RepositoryState current, UUID creditId) {
        List<Payment> values = current.paymentsByCreditId().get(creditId);
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > 1) values.sort(Comparator.comparingLong(Payment::getTimestamp));
        return values;
    }
    private boolean isValidCredit(CreditEntry entry) { return entry != null && entry.getId() != null && entry.getCreditor() != null && !entry.getCreditor().isBlank() && entry.getDebtor() != null && !entry.getDebtor().isBlank() && !entry.getCreditor().equalsIgnoreCase(entry.getDebtor()) && MoneyRules.isPositive(entry.getAmountMinor()); }
    private boolean isValidPayment(Payment payment) { return payment != null && payment.getId() != null && payment.getCreditId() != null && MoneyRules.isPositive(payment.getAmountMinor()); }

    private List<CreditStatisticsSnapshot.OpenCredit> snapshotOpenCredits(RepositoryState current, List<UUID> ids) {
        List<CreditStatisticsSnapshot.OpenCredit> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            CreditEntry credit = current.credits().get(id);
            if (CreditStatusRules.isActive(credit)) {
                result.add(new CreditStatisticsSnapshot.OpenCredit(id, credit.getRemainingAmountMinor()));
            }
        }
        return List.copyOf(result);
    }

    public Optional<CreditEntry> findCreditById(UUID id) {
        stateLock.readLock().lock();
        try { return Optional.ofNullable(state.credits().get(id)); }
        finally { stateLock.readLock().unlock(); }
    }
    public Optional<CreditEntry> findCreditByShortId(String shortId) {
        String lower = shortId.toLowerCase(Locale.ROOT);
        stateLock.readLock().lock();
        try { return state.credits().values().stream().filter(credit -> credit.getId().toString().toLowerCase(Locale.ROOT).startsWith(lower)).findFirst(); }
        finally { stateLock.readLock().unlock(); }
    }
    public Optional<CreditEntry> findCreditByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        stateLock.readLock().lock();
        try { return state.credits().values().stream().filter(credit -> lower.equals(credit.getDealName())).findFirst(); }
        finally { stateLock.readLock().unlock(); }
    }
    public Optional<CreditEntry> findCreditByNamePrefix(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        stateLock.readLock().lock();
        try { return state.credits().values().stream().filter(credit -> credit.getDealName() != null && credit.getDealName().startsWith(lower)).findFirst(); }
        finally { stateLock.readLock().unlock(); }
    }
    public List<CreditEntry> getAllCredits() {
        stateLock.readLock().lock();
        try { return new ArrayList<>(state.credits().values()); }
        finally { stateLock.readLock().unlock(); }
    }
    public List<CreditEntry> getCreditsByDebtor(String playerName) { return getCredits(playerName, true); }
    public List<CreditEntry> getCreditsByCreditor(String playerName) { return getCredits(playerName, false); }
    private List<CreditEntry> getCredits(String playerName, boolean debtor) {
        stateLock.readLock().lock();
        try {
            RepositoryState current = state;
            PlayerCreditData data = current.players().get(playerName.toLowerCase(Locale.ROOT));
            if (data == null) return List.of();
            List<UUID> ids = debtor ? data.getCreditsAsDebtor() : data.getCreditsAsCreditor();
            List<CreditEntry> result = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                CreditEntry credit = current.credits().get(id);
                if (credit != null) result.add(credit);
            }
            return result;
        } finally {
            stateLock.readLock().unlock();
        }
    }
    public PlayerCreditData getOrCreatePlayer(String playerName) {
        stateLock.writeLock().lock();
        try { return state.players().computeIfAbsent(playerName.toLowerCase(Locale.ROOT), PlayerCreditData::new); }
        finally { stateLock.writeLock().unlock(); }
    }
    public List<Payment> getPaymentsByCreditId(UUID creditId) {
        stateLock.readLock().lock();
        try {
            List<Payment> values = state.paymentsByCreditId().get(creditId);
            return values == null ? List.of() : List.copyOf(values);
        } finally {
            stateLock.readLock().unlock();
        }
    }
    public List<Payment> getAllPayments() {
        stateLock.readLock().lock();
        try { return new ArrayList<>(state.payments().values()); }
        finally { stateLock.readLock().unlock(); }
    }

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

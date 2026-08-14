package op.creditmanager.client.core;

import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.core.service.CreditEventFactory;
import op.creditmanager.client.core.service.CreditMutationService;
import op.creditmanager.client.core.service.CreditSnapshotMapper;
import op.creditmanager.client.core.service.CreditStatusService;
import op.creditmanager.client.core.service.CreditOperations;
import op.creditmanager.client.core.service.PaymentApplicationService;
import op.creditmanager.client.core.service.PaylogLinkingService;
import op.creditmanager.client.core.service.CreditQueryService;
import op.creditmanager.client.core.service.CreditApplicationService;
import op.creditmanager.client.core.service.CreditRecoveryService;
import op.creditmanager.client.core.service.MutationCommitResult;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class CreditManagerCore implements CreditOperations {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private final CreditRepository repository;
    private final CreditSnapshotMapper snapshots = new CreditSnapshotMapper();
    private final CreditEventFactory events = new CreditEventFactory();
    private final CreditStatusService statuses = new CreditStatusService();
    private final CreditMutationService mutations;
    private final PaymentApplicationService payments;
    private final PaylogLinkingService paylogLinks;
    private final CreditQueryService queries;
    private final CreditApplicationService credits;
    private final CreditRecoveryService recovery;
    private final ThreadLocal<MutationCommitResult> lastMutationCommit = new ThreadLocal<>();

    protected CreditManagerCore(CreditRepository repository) {
        if (repository == null) throw new IllegalStateException("CreditRepository darf nicht NULL sein.");
        this.repository = repository;
        this.mutations = new CreditMutationService(repository, snapshots);
        this.payments = new PaymentApplicationService(repository, this, events);
        this.paylogLinks = new PaylogLinkingService(this);
        this.queries = new CreditQueryService(repository, statuses);
        this.credits = new CreditApplicationService(repository, this);
        this.recovery = new CreditRecoveryService(repository, this::componentsWritable, this::reloadAll);
    }

    public CreditEntry createCredit(String creditor, String debtor, double amount,
                                    Long dueDate, String label, String note) throws CreditException {
        return createCreditMinor(creditor, debtor, legacyMinor(amount), dueDate, label, note);
    }

    public CreditEntry createCreditMinor(String creditor, String debtor, long amountMinor,
                                         Long dueDate, String label, String note) throws CreditException {
        return credits.createCredit(creditor, debtor, amountMinor, dueDate, label, note);
    }

    public Payment addMoneyPayment(UUID dealId, double amount) throws CreditException {
        return addMoneyPaymentMinor(dealId, legacyMinor(amount));
    }

    public Payment addMoneyPaymentMinor(UUID dealId, long amountMinor) throws CreditException {
        return payments.addMoneyPayment(dealId, amountMinor);
    }

    public Payment addMoneyPayment(UUID dealId, String fromPlayer, double amount) throws CreditException {
        return addMoneyPaymentMinor(dealId, fromPlayer, legacyMinor(amount));
    }

    public Payment addMoneyPaymentMinor(UUID dealId, String fromPlayer, long amountMinor) throws CreditException {
        return payments.addMoneyPayment(dealId, fromPlayer, amountMinor);
    }

    public synchronized PaylogLinkResult addPaylogPayment(UUID dealId, UUID paylogId, double requestedAmount,
                                                           long timestamp, String note) throws CreditException {
        return addPaylogPaymentMinor(dealId, paylogId,
                legacyMinor(requestedAmount), timestamp, note);
    }

    public synchronized PaylogLinkResult addPaylogPaymentMinor(UUID dealId, UUID paylogId, long requestedAmountMinor,
                                                                long timestamp, String note) throws CreditException {
        return paylogLinks.addPaylogPayment(dealId, paylogId, requestedAmountMinor, timestamp, note);
    }

    public synchronized PaylogLinkResult linkPaylogToDeal(UUID paylogId, UUID dealId) throws CreditException {
        return paylogLinks.linkPaylogToDeal(paylogId, dealId);
    }

    public synchronized PaylogLinkResult autoLinkDetectedPaylog(UUID paylogId) throws CreditException {
        return autoLinkDetectedPaylog(paylogId, ClientConfigManager.getPaylogAutoLinkMode(),
                ClientConfigManager.isCompleteDealOnPaylogOverpay());
    }

    public synchronized PaylogLinkResult autoLinkDetectedPaylog(UUID paylogId, PaylogAutoLinkMode mode,
                                                                  boolean completeOverpay) throws CreditException {
        return paylogLinks.autoLinkDetectedPaylog(paylogId, mode, completeOverpay);
    }

    public List<CreditEntry> getLinkableCreditsForPaylog(TransactionEntry paylog) {
        return paylogLinks.getLinkableCreditsForPaylog(paylog);
    }

    public Payment addItemPayment(UUID dealId, List<String> items, double value, String nbt) throws CreditException {
        return addItemPaymentMinor(dealId, items, legacyMinor(value), nbt);
    }

    public Payment addItemPaymentMinor(UUID dealId, List<String> items, long valueMinor, String nbt) throws CreditException {
        return payments.addItemPayment(dealId, items, valueMinor, nbt);
    }

    public Payment addItemPayment(UUID dealId, List<String> items, double value,
                                  List<String> nbtEntries) throws CreditException {
        return addItemPaymentMinor(dealId, items, legacyMinor(value), nbtEntries);
    }

    public Payment addItemPaymentMinor(UUID dealId, List<String> items, long valueMinor,
                                       List<String> nbtEntries) throws CreditException {
        return payments.addItemPayment(dealId, items, valueMinor, nbtEntries);
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value, String nbt) throws CreditException {
        return addItemPaymentMinor(dealId, fromPlayer, items, legacyMinor(value), nbt);
    }

    public Payment addItemPaymentMinor(UUID dealId, String fromPlayer, List<String> items, long valueMinor, String nbt) throws CreditException {
        return payments.addItemPayment(dealId, fromPlayer, items, valueMinor, nbt);
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value,
                                  List<String> nbtEntries) throws CreditException {
        return addItemPaymentMinor(dealId, fromPlayer, items, legacyMinor(value), nbtEntries);
    }

    public Payment addItemPaymentMinor(UUID dealId, String fromPlayer, List<String> items, long valueMinor,
                                       List<String> nbtEntries) throws CreditException {
        return payments.addItemPayment(dealId, fromPlayer, items, valueMinor, nbtEntries);
    }

    public CreditEntry deleteCredit(UUID dealId) throws CreditException {
        return archiveCredit(dealId);
    }

    public CreditEntry archiveCredit(UUID dealId) throws CreditException {
        return credits.archiveCredit(dealId);
    }

    public CreditEntry closeCredit(UUID dealId) throws CreditException {
        return credits.closeCredit(dealId);
    }

    public CreditEntry reactivateCredit(UUID dealId) throws CreditException {
        return credits.reactivateCredit(dealId);
    }

    public void deletePayment(UUID paymentId) throws CreditException {
        payments.deletePayment(paymentId);
    }

    public CreditEntry updateCredit(UUID dealId, String creditor, String debtor, double amount, Long dueDate, String label, String note) throws CreditException {
        return updateCreditMinor(dealId, creditor, debtor, legacyMinor(amount), dueDate, label, note);
    }

    public CreditEntry updateCreditMinor(UUID dealId, String creditor, String debtor, long amountMinor, Long dueDate,
                                         String label, String note) throws CreditException {
        return credits.updateCredit(dealId, creditor, debtor, amountMinor, dueDate, label, note);
    }

    public List<CreditEntry> getOpenCreditsAsDebtor(String player) { return queries.openAsDebtor(player); }
    public List<CreditEntry> getOpenCreditsAsCreditor(String player) { return queries.openAsCreditor(player); }
    public CreditStatisticsSnapshot getStatisticsSnapshot(String player) { return repository.snapshotOpenCredits(player); }
    public List<CreditEntry> getAllCreditsAsDebtor(String player) { return queries.allAsDebtor(player); }
    public List<CreditEntry> getAllCreditsAsCreditor(String player) { return queries.allAsCreditor(player); }

    public List<CreditEntry> getCreditsForPlayer(String player) {
        return queries.forPlayer(player);
    }

    public List<String> getDealNamesForPlayer(String player) {
        return queries.dealNamesForPlayer(player);
    }

    public Optional<CreditEntry> findCredit(String input) {
        return queries.find(input);
    }

    public List<Payment> getPaymentsForCredit(UUID dealId) { return queries.paymentsForCredit(dealId); }
    public boolean requiresRecovery() {
        return recovery.requiresRecovery();
    }
    public boolean isWritable() {
        return recovery.isWritable();
    }
    private boolean componentsWritable() {
        return DatabaseManager.getInstance().isSafeForWrites() && repository.isWritable() && CreditEventRepository.getInstance().isWritable()
                && TransactionRepository.getInstance().isWritable() && ClientConfigManager.isWritable();
    }
    public long getRevision() { return repository.getRevision(); }
    public List<CreditRepository.RecoveryRecord> getRecoveryRecords() {
        return recovery.records();
    }
    public boolean repairCredit(UUID token, String creditor, String debtor, double amount, long createdAt) { return recovery.repairCredit(token, creditor, debtor, amount, createdAt); }
    public boolean repairPayment(UUID token, UUID creditId, double amount, long timestamp) { return recovery.repairPayment(token, creditId, amount, timestamp); }
    public boolean repairEvent(UUID token, UUID creditId, CreditEventType type, double amount, long timestamp) {
        return recovery.repairEvent(token, creditId, type, amount, timestamp);
    }
    public boolean ignoreRecovery(UUID token) { return recovery.ignore(token); }
    public boolean discardRecovery(UUID token, boolean confirmed) { return recovery.discard(token, confirmed); }
    public boolean createRecoveryBackup(UUID token) { return recovery.createRecoveryBackup(token); }
    public boolean createSafetyBackup() { return recovery.createSafetyBackup(); }
    public DatabaseManager.ManualBackupResult createHealthyBackupNow() { return DatabaseManager.getInstance().createHealthyBackupNow(); }
    public boolean restoreLatestSafetyBackup() { return recovery.restoreLatestSafetyBackup(); }
    public boolean recheckAndRepairDatabase() { return recovery.recheckAndRepairDatabase(); }
    public boolean createEmptyDatabaseAfterPhysicalRecovery() { return recovery.createEmptyDatabaseAfterPhysicalRecovery(); }
    public boolean repairDefaultRecovery(UUID token) { return recovery.repairDefault(token); }
    public int repairWithSafeDefaults() { return recovery.repairWithSafeDefaults(); }
    public void reloadData() { recovery.reloadData(); }
    private void reloadAll() {
        LegacyJsonMigrationService.getInstance().inspectAtStartup();
        repository.load();
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        ClientConfigManager.reload();
    }

    public CreditEntry getSafeCredit(UUID id) throws CreditException {
        return repository.findCreditById(id).orElseThrow(() -> new CreditException("Deal nicht gefunden."));
    }

    public void requireWritable() throws CreditException {
        if (!isWritable()) throw new CreditException("Datenprüfung erforderlich: Änderungen sind vorübergehend gesperrt.");
    }

    public MutationCommitResult commitMutation(CreditEntry draft, List<Payment> paymentUpserts,
                                               List<UUID> paymentDeletions, List<CreditEventEntry> events,
                                               CreditEntry published) throws CreditException {
        MutationCommitResult result = mutations.commit(draft, paymentUpserts, paymentDeletions, events, published);
        lastMutationCommit.set(result);
        if (!result.committed()) throw new CreditException(result.userMessage());
        return result;
    }

    public MutationCommitResult consumeLastMutationCommit() {
        MutationCommitResult result = lastMutationCommit.get();
        lastMutationCommit.remove();
        return result;
    }

    public List<CreditEventEntry> paymentEvents(CreditEntry entry, Payment payment, long remainingBeforeMinor) {
        List<CreditEventEntry> events = new ArrayList<>();
        long amountMinor = payment.getAmountMinor();
        boolean itemPayment = payment.getPaymentKind() == op.creditmanager.client.model.PaymentKind.ITEM;
        events.add(event(CreditEventType.PAYMENT_ADDED, entry, amountMinor, remainingBeforeMinor, "Zahlung hinzugefügt",
                payment.getFromPlayer(), payment.getSource(), itemPayment));
        if (STATUS_PAID.equals(entry.getStatus())) {
            events.add(event(CreditEventType.CREDIT_PAID, entry, amountMinor, remainingBeforeMinor, "Deal vollständig bezahlt",
                    payment.getFromPlayer(), payment.getSource(), itemPayment));
        } else if (STATUS_PARTIAL.equals(entry.getStatus())) {
            events.add(event(CreditEventType.CREDIT_PARTIAL, entry, amountMinor, remainingBeforeMinor, "Teilzahlung",
                    payment.getFromPlayer(), payment.getSource(), itemPayment));
        }
        return List.copyOf(events);
    }

    public CreditEventEntry event(CreditEventType type, CreditEntry entry, long amountMinor, long amountBeforeMinor,
                                   String note, String actor, String source, boolean itemPayment) {
        return events.create(type, entry, amountMinor, amountBeforeMinor, note, actor, source, itemPayment);
    }

    public CreditEntry copyCredit(CreditEntry source) {
        return snapshots.copyCredit(source);
    }

    public void validateActive(CreditEntry entry) throws CreditException {
        statuses.requireActive(entry);
    }

    public void validatePaymentSource(String fromPlayer, CreditEntry entry) throws CreditException {
        if (fromPlayer != null && !fromPlayer.isBlank() && !samePlayer(fromPlayer, entry.getDebtor())) {
            throw new CreditException("Eine manuelle Zahlung muss vom Schuldner stammen.");
        }
    }

    public TransactionEntry getPaylog(UUID paylogId) throws CreditException {
        if (paylogId == null) throw new CreditException("Paylog nicht gefunden.");
        return TransactionRepository.getInstance().find(paylogId)
                .orElseThrow(() -> new CreditException("Paylog nicht gefunden."));
    }

    public List<CreditEntry> matchingActiveDeals(TransactionEntry paylog) {
        return repository.getAllCredits().stream()
                .filter(entry -> !entry.isArchived())
                .filter(entry -> STATUS_OPEN.equals(entry.getStatus()) || STATUS_PARTIAL.equals(entry.getStatus()))
                .filter(entry -> samePlayer(paylog.getFromPlayer(), entry.getDebtor()))
                .filter(entry -> samePlayer(paylog.getToPlayer(), entry.getCreditor()))
                .sorted(Comparator.comparingLong(CreditEntry::getCreatedAt).thenComparing(entry -> entry.getId().toString()))
                .toList();
    }

    public boolean samePlayer(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public void validateAmountMinor(long amountMinor) throws CreditException {
        if (!MoneyRules.isPositive(amountMinor)) {
            throw new CreditException("Betrag liegt außerhalb des erlaubten Bereichs.");
        }
    }

    public void validateNames(String creditor, String debtor) throws CreditException {
        validatePlayerName(creditor, "Gläubiger");
        validatePlayerName(debtor, "Schuldner");
        if (creditor == null || creditor.isBlank()) throw new CreditException("Ungültiger Gläubiger.");
        if (debtor == null || debtor.isBlank()) throw new CreditException("Ungültiger Schuldner.");
        if (creditor.equalsIgnoreCase(debtor)) throw new CreditException("Gläubiger und Schuldner dürfen nicht identisch sein.");
    }

    private void validatePlayerName(String value, String role) throws CreditException {
        if (!CreditValidationRules.isValidPlayerName(value)) {
            throw new CreditException("Ungültiger " + role + ".");
        }
    }

    public void validateDealInput(String label, String note, Long dueDate) throws CreditException {
        if (!CreditValidationRules.isValidLabel(label)) throw new CreditException("Die Deal-Bezeichnung ist zu lang.");
        if (!CreditValidationRules.isValidNote(note)) throw new CreditException("Die Notiz ist zu lang.");
        if (dueDate != null && (dueDate <= 0 || dueDate > System.currentTimeMillis() + 3_155_760_000_000L)) {
            throw new CreditException("Ungültiges Fälligkeitsdatum.");
        }
    }

    public String normalizeNote(String note) throws CreditException {
        if (note == null || note.isBlank()) return null;
        if (!CreditValidationRules.isValidNote(note)) throw new CreditException("Die Notiz ist zu lang.");
        return note.trim();
    }

    public String lower(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String lowerOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : lower(value); }
    public static class CreditException extends Exception {
        public CreditException(String message) { super(message); }
    }

    public record PaylogLinkResult(Status status, TransactionEntry paylog, CreditEntry credit, Payment payment,
                                   long remainingPaylogMinor, boolean automatic, List<UUID> candidateIds) {
        public enum Status { LINKED, AMBIGUOUS, NO_MATCHING_DEAL, NO_SINGLE_DEAL_FITS, AUTO_LINK_DISABLED, ALREADY_CONSUMED }
        public PaylogLinkResult {
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        }
        public static PaylogLinkResult linked(TransactionEntry paylog, CreditEntry credit, Payment payment, long remainingMinor, boolean automatic) { return new PaylogLinkResult(Status.LINKED, paylog, credit, payment, Math.max(0L, remainingMinor), automatic, List.of(credit.getId())); }
        public static PaylogLinkResult ambiguous(TransactionEntry paylog, List<CreditEntry> candidates) { return new PaylogLinkResult(Status.AMBIGUOUS, paylog, null, null, paylog.getRemainingAmountMinor(), true, candidates.stream().map(CreditEntry::getId).toList()); }
        public static PaylogLinkResult noMatchingDeal(TransactionEntry paylog) { return new PaylogLinkResult(Status.NO_MATCHING_DEAL, paylog, null, null, paylog.getRemainingAmountMinor(), true, List.of()); }
        public static PaylogLinkResult noSingleDealFits(TransactionEntry paylog) { return new PaylogLinkResult(Status.NO_SINGLE_DEAL_FITS, paylog, null, null, paylog.getRemainingAmountMinor(), true, List.of()); }
        public static PaylogLinkResult autoLinkDisabled(TransactionEntry paylog, List<CreditEntry> credits) { return new PaylogLinkResult(Status.AUTO_LINK_DISABLED, paylog, credits.size() == 1 ? credits.getFirst() : null, null, paylog.getRemainingAmountMinor(), false, credits.stream().map(CreditEntry::getId).toList()); }
        public static PaylogLinkResult alreadyConsumed(TransactionEntry paylog) { return new PaylogLinkResult(Status.ALREADY_CONSUMED, paylog, null, null, 0L, false, List.of()); }
        public boolean linked() { return status == Status.LINKED; }
        @Deprecated public double remainingPaylogAmount() { return MoneyRules.toDisplayDouble(remainingPaylogMinor); }
    }

    private long legacyMinor(double amount) throws CreditException {
        try {
            return MoneyRules.fromLegacyDouble(amount, true).minorUnits();
        } catch (IllegalArgumentException exception) {
            throw new CreditException("Betrag liegt außerhalb des erlaubten Bereichs.");
        }
    }
}

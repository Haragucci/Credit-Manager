package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class CreditManager {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    private static final double EPSILON = 0.0001D;
    private static final double MAX_AMOUNT = 1_000_000_000_000_000D;
    private static final int MAX_PLAYER_NAME_LENGTH = 32;
    private static final int MAX_LABEL_LENGTH = 128;
    private static final int MAX_NOTE_LENGTH = 4_096;

    private final CreditRepository repository;
    private static final UUID CONFIG_RECOVERY_TOKEN = UUID.nameUUIDFromBytes("creditmanager-config-recovery".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID TRANSACTION_RECOVERY_TOKEN = UUID.nameUUIDFromBytes("creditmanager-transaction-recovery".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    public CreditManager(CreditRepository repository) {
        if (repository == null) throw new IllegalStateException("CreditRepository darf nicht NULL sein.");
        this.repository = repository;
    }

    public CreditEntry createCredit(String creditor, String debtor, double amount,
                                    Long dueDate, String label, String note) throws CreditException {
        requireWritable();
        validateNames(creditor, debtor);
        validateAmount(amount);
        validateDealInput(label, note, dueDate);

        String dealName = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(entry -> dealName.equalsIgnoreCase(entry.getDealName())
                && !STATUS_CANCELLED.equals(entry.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");

        CreditEntry entry = new CreditEntry(UUID.randomUUID(), dealName, lower(creditor), lower(debtor), amount, dueDate, note);
        commitMutation(entry, List.of(), List.of(), List.of(event(CreditEventType.CREDIT_CREATED, entry,
                entry.getAmount(), entry.getAmount(), entry.getNote(), creditor, "CREATE", false)), null);
        return entry;
    }

    public Payment addMoneyPayment(UUID dealId, String fromPlayer, double amount) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateActive(entry);
        validateAmount(amount);

        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(),
                Math.min(amount, remainingBefore), null, "MANUELL");
        draft.addPayment(payment);
        commitMutation(draft, List.of(payment), List.of(), paymentEvents(draft, payment, remainingBefore), entry);
        return payment;
    }

    public synchronized PaylogLinkResult addPaylogPayment(UUID dealId, UUID paylogId, double requestedAmount,
                                                           long timestamp, String note) throws CreditException {
        return linkPaylogToDeal(paylogId, dealId, false, requestedAmount, timestamp, note, "PAYLOG_SELECTED");
    }

    public synchronized PaylogLinkResult linkPaylogToDeal(UUID paylogId, UUID dealId) throws CreditException {
        return linkPaylogToDeal(paylogId, dealId, false, Double.NaN, 0L, null, "PAYLOG_MANUAL");
    }

    public synchronized PaylogLinkResult autoLinkDetectedPaylog(UUID paylogId) throws CreditException {
        requireWritable();
        TransactionEntry paylog = getPaylog(paylogId);
        if (paylog.getRemainingAmount() <= EPSILON) return PaylogLinkResult.alreadyConsumed(paylog);
        List<CreditEntry> candidates = matchingActiveDeals(paylog);
        for (CreditEntry candidate : candidates) {
            if (candidate.getRemainingAmount() + EPSILON >= paylog.getRemainingAmount()) {
                return linkPaylogToDeal(paylogId, candidate.getId(), true, paylog.getRemainingAmount(), paylog.getTimestamp(), null, "PAYLOG_AUTO");
            }
        }
        return candidates.isEmpty() ? PaylogLinkResult.noMatchingDeal(paylog) : PaylogLinkResult.noSingleDealFits(paylog);
    }

    public List<CreditEntry> getLinkableCreditsForPaylog(TransactionEntry paylog) {
        return paylog == null ? List.of() : matchingActiveDeals(paylog);
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value, String nbt) throws CreditException {
        return addItemPayment(dealId, fromPlayer, items, value, nbt == null || nbt.isBlank() ? List.of() : List.of(nbt));
    }

    public Payment addItemPayment(UUID dealId, String fromPlayer, List<String> items, double value,
                                  List<String> nbtEntries) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateActive(entry);
        validateAmount(value);
        if (items == null || items.isEmpty()) throw new CreditException("Mindestens ein Item muss ausgewählt werden.");

        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(),
                Math.min(value, remainingBefore), new ArrayList<>(items), "MANUELL");
        payment.setItemNbtEntries(nbtEntries);
        payment.setItemNbt(nbtEntries == null || nbtEntries.isEmpty() ? null : nbtEntries.get(0));
        draft.addPayment(payment);
        commitMutation(draft, List.of(payment), List.of(), paymentEvents(draft, payment, remainingBefore), entry);
        return payment;
    }

    public CreditEntry deleteCredit(UUID dealId) throws CreditException {
        return archiveCredit(dealId);
    }

    public CreditEntry archiveCredit(UUID dealId) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        if (entry.isArchived()) return entry;
        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setArchived(true);
        commitMutation(draft, List.of(), List.of(), List.of(event(CreditEventType.CREDIT_ARCHIVED, draft, draft.getAmount(), remainingBefore,
                "Deal archiviert", null, "ARCHIVE", false)), entry);
        return entry;
    }

    public CreditEntry closeCredit(UUID dealId) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateActive(entry);
        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setStatus(STATUS_CLOSED);
        draft.setCompletedAt(System.currentTimeMillis());
        commitMutation(draft, List.of(), List.of(), List.of(event(CreditEventType.CREDIT_CLOSED, draft, draft.getAmount(), remainingBefore,
                "Deal manuell abgeschlossen", null, "CLOSE", false)), entry);
        return entry;
    }

    public CreditEntry reactivateCredit(UUID dealId) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        if (!entry.isArchived() && !STATUS_CANCELLED.equals(entry.getStatus()) && !STATUS_CLOSED.equals(entry.getStatus())) return entry;
        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.setArchived(false);
        draft.setCompletedAt(null);
        draft.setStatus(STATUS_OPEN);
        draft.refreshPaymentState();
        commitMutation(draft, List.of(), List.of(), List.of(event(CreditEventType.CREDIT_REACTIVATED, draft, draft.getAmount(), remainingBefore,
                "Deal reaktiviert", null, "REACTIVATE", false)), entry);
        return entry;
    }

    public void deletePayment(UUID paymentId) throws CreditException {
        Payment payment = repository.getAllPayments().stream().filter(value -> value.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new CreditException("Zahlung nicht gefunden."));
        CreditEntry entry = getSafeCredit(payment.getCreditId());
        requireWritable();
        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        draft.removePayment(paymentId);
        if (STATUS_OPEN.equals(draft.getStatus()) || STATUS_PARTIAL.equals(draft.getStatus())) {
            draft.setArchived(false);
            draft.setCompletedAt(null);
        }
        commitMutation(draft, List.of(), List.of(paymentId), List.of(event(CreditEventType.PAYMENT_DELETED, draft,
                safeAmount(payment), remainingBefore, "Zahlung gelöscht", payment.getFromPlayer(), payment.getSource(), !payment.getItems().isEmpty())), entry);
    }

    public CreditEntry updateCredit(UUID dealId, String creditor, String debtor, double amount, Long dueDate, String label, String note) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateNames(creditor, debtor);
        validateAmount(amount);
        validateDealInput(label, note, dueDate);
        if (amount + 0.0001D < entry.getPaidAmount()) throw new CreditException("Der Gesamtbetrag darf nicht kleiner als bereits bezahlt sein.");
        boolean counterpartyChanged = !lower(creditor).equals(entry.getCreditor()) || !lower(debtor).equals(entry.getDebtor());
        if (counterpartyChanged && !entry.getPayments().isEmpty()) {
            throw new CreditException("Die Gegenpartei kann nach vorhandenen Zahlungen nicht geändert werden.");
        }
        String name = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(other -> !other.getId().equals(dealId)
                && name.equalsIgnoreCase(other.getDealName()) && !STATUS_CANCELLED.equals(other.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");
        CreditEntry draft = copyCredit(entry);
        double previousAmount = draft.getAmount();
        draft.setCreditor(lower(creditor)); draft.setDebtor(lower(debtor)); draft.setDealName(name); draft.setAmount(amount); draft.setDueDate(dueDate); draft.setNote(note == null || note.isBlank() ? null : note.trim());
        draft.refreshPaymentState();
        commitMutation(draft, List.of(), List.of(), List.of(event(CreditEventType.CREDIT_UPDATED, draft, amount, previousAmount,
                "Deal bearbeitet", null, "EDIT", false)), entry);
        return entry;
    }

    public List<CreditEntry> getOpenCreditsAsDebtor(String player) { return open(repository.getCreditsByDebtor(player)); }
    public List<CreditEntry> getOpenCreditsAsCreditor(String player) { return open(repository.getCreditsByCreditor(player)); }
    public List<CreditEntry> getAllCreditsAsDebtor(String player) { return repository.getCreditsByDebtor(player); }
    public List<CreditEntry> getAllCreditsAsCreditor(String player) { return repository.getCreditsByCreditor(player); }

    public List<CreditEntry> getCreditsForPlayer(String player) {
        List<CreditEntry> all = new ArrayList<>();
        all.addAll(repository.getCreditsByDebtor(player));
        all.addAll(repository.getCreditsByCreditor(player));
        all.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return all;
    }

    public List<String> getDealNamesForPlayer(String player) {
        if (player == null || player.isBlank()) return List.of();
        return getCreditsForPlayer(player).stream().filter(entry -> !STATUS_CANCELLED.equals(entry.getStatus()))
                .map(CreditEntry::getDealName).filter(name -> name != null && !name.isBlank())
                .distinct().sorted().toList();
    }

    public Optional<CreditEntry> findCredit(String input) {
        if (input == null || input.isBlank()) return Optional.empty();
        try { return repository.findCreditById(UUID.fromString(input)); } catch (IllegalArgumentException ignored) { }
        if (input.length() >= 6 && input.length() < 36 && !input.contains("-")) {
            Optional<CreditEntry> shortId = repository.findCreditByShortId(input);
            if (shortId.isPresent()) return shortId;
        }
        Optional<CreditEntry> byName = repository.findCreditByName(input);
        return byName.isPresent() ? byName : repository.findCreditByNamePrefix(input);
    }

    public List<Payment> getPaymentsForCredit(UUID dealId) { return repository.getPaymentsByCreditId(dealId); }
    public boolean requiresRecovery() {
        return !isWritable() || !repository.getRecoveryRecords().isEmpty();
    }
    public boolean isWritable() {
        return DatabaseManager.getInstance().isSafeForWrites() && repository.isWritable() && CreditEventRepository.getInstance().isWritable()
                && TransactionRepository.getInstance().isWritable() && ClientConfigManager.isWritable();
    }
    public long getRevision() { return repository.getRevision(); }
    public List<CreditRepository.RecoveryRecord> getRecoveryRecords() {
        List<CreditRepository.RecoveryRecord> records = new ArrayList<>(repository.getRecoveryRecords());
        if (!ClientConfigManager.isWritable()) {
            records.add(new CreditRepository.RecoveryRecord(CONFIG_RECOVERY_TOKEN, CreditRepository.RecoveryType.CONFIG,
                    "client_config.json", null, null, null, FileManager.getClientConfigFile(), "Beschädigte lokale Konfiguration"));
        }
        if (!TransactionRepository.getInstance().isWritable()) {
            records.add(new CreditRepository.RecoveryRecord(TRANSACTION_RECOVERY_TOKEN, CreditRepository.RecoveryType.TRANSACTION_LOG,
                    "creditmanager.mv.db", null, null, null, FileManager.getDatabaseStorageFile(), "Beschädigte Paylog-Daten"));
        }
        return List.copyOf(records);
    }
    public boolean repairCredit(UUID token, String creditor, String debtor, double amount, long createdAt) { return repository.repairCredit(token, creditor, debtor, amount, createdAt); }
    public boolean repairPayment(UUID token, UUID creditId, double amount, long timestamp) { return repository.repairPayment(token, creditId, amount, timestamp); }
    public boolean repairEvent(UUID token, UUID creditId, CreditEventType type, double amount, long timestamp) {
        return repository.repairEvent(token, creditId, type, amount, timestamp);
    }
    public boolean ignoreRecovery(UUID token) { return repository.ignoreRecovery(token); }
    public boolean createRecoveryBackup(UUID token) {
        if (CONFIG_RECOVERY_TOKEN.equals(token)) return JsonStorage.createBackup(FileManager.getClientConfigFile());
        if (TRANSACTION_RECOVERY_TOKEN.equals(token)) return op.creditmanager.client.storage.db.DatabaseManager.getInstance().createBackup();
        return repository.createRecoveryBackup(token);
    }
    public boolean createSafetyBackup() { return DatabaseManager.getInstance().createBackup(); }
    public boolean restoreLatestSafetyBackup() {
        if (!DatabaseManager.getInstance().restoreLatestValidBackup()) return false;
        reloadData();
        return !requiresRecovery();
    }
    public boolean repairDefaultRecovery(UUID token) {
        if (CONFIG_RECOVERY_TOKEN.equals(token)) return ClientConfigManager.resetCorruptConfigWithDefaults();
        if (TRANSACTION_RECOVERY_TOKEN.equals(token)) return TransactionRepository.getInstance().resetCorruptTransactionsWithBackup();
        return false;
    }
    public int repairWithSafeDefaults() {
        int repaired = repository.repairWithSafeDefaults();
        if (!ClientConfigManager.isWritable() && ClientConfigManager.resetCorruptConfigWithDefaults()) repaired++;
        if (!TransactionRepository.getInstance().isWritable()
                && TransactionRepository.getInstance().resetCorruptTransactionsWithBackup()) repaired++;
        return repaired;
    }
    public void reloadData() {
        DataHealth.clearReasons();
        LegacyJsonMigrationService.getInstance().inspectAtStartup();
        repository.load();
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        ClientConfigManager.reload();
    }

    private List<CreditEntry> open(List<CreditEntry> entries) {
        return entries.stream().filter(entry -> !entry.isArchived()
                && (STATUS_OPEN.equals(entry.getStatus()) || STATUS_PARTIAL.equals(entry.getStatus()))).toList();
    }

    private CreditEntry getSafeCredit(UUID id) throws CreditException {
        return repository.findCreditById(id).orElseThrow(() -> new CreditException("Deal nicht gefunden."));
    }

    private void requireWritable() throws CreditException {
        if (!isWritable()) throw new CreditException("Datenprüfung erforderlich: Änderungen sind vorübergehend gesperrt.");
    }

    private void commitMutation(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                                List<CreditEventEntry> events, CreditEntry published) throws CreditException {
        DatabaseManager.CreditMutation mutation = new DatabaseManager.CreditMutation(draft, paymentUpserts, paymentDeletions, events);
        if (!DatabaseManager.getInstance().commitCreditMutation(mutation)) {
            throw new CreditException("Vorgang wurde nicht gespeichert; der vorherige Datenstand bleibt unverändert.");
        }
        repository.load();
        CreditEventRepository.getInstance().bind(repository);
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        CreditEntry persisted = repository.findCreditById(draft.getId())
                .orElseThrow(() -> new CreditException("Gespeicherter Deal konnte nicht erneut geladen werden."));
        CreditEntry target = published == null ? draft : published;
        syncCredit(target, persisted);
        repository.replaceLoadedCredit(target);
    }

    private List<CreditEventEntry> paymentEvents(CreditEntry entry, Payment payment, double remainingBefore) {
        List<CreditEventEntry> events = new ArrayList<>();
        double amount = safeAmount(payment);
        boolean itemPayment = !payment.getItems().isEmpty();
        events.add(event(CreditEventType.PAYMENT_ADDED, entry, amount, remainingBefore, "Zahlung hinzugefügt",
                payment.getFromPlayer(), payment.getSource(), itemPayment));
        if (STATUS_PAID.equals(entry.getStatus())) {
            events.add(event(CreditEventType.CREDIT_PAID, entry, amount, remainingBefore, "Deal vollständig bezahlt",
                    payment.getFromPlayer(), payment.getSource(), itemPayment));
        } else if (STATUS_PARTIAL.equals(entry.getStatus())) {
            events.add(event(CreditEventType.CREDIT_PARTIAL, entry, amount, remainingBefore, "Teilzahlung",
                    payment.getFromPlayer(), payment.getSource(), itemPayment));
        }
        return List.copyOf(events);
    }

    private CreditEventEntry event(CreditEventType type, CreditEntry entry, double amount, double amountBefore,
                                   String note, String actor, String source, boolean itemPayment) {
        return new CreditEventEntry(type, entry, amount, amountBefore, note, actor, source, itemPayment);
    }

    private CreditEntry copyCredit(CreditEntry source) {
        CreditEntry copy = new CreditEntry();
        copy.setId(source.getId());
        copy.setDealName(source.getDealName());
        copy.setCreditor(source.getCreditor());
        copy.setDebtor(source.getDebtor());
        copy.setAmount(source.getAmount());
        copy.setPaidAmount(source.getPaidAmount());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setDueDate(source.getDueDate());
        copy.setStatus(source.getStatus());
        copy.setNote(source.getNote());
        copy.setCompletedAt(source.getCompletedAt());
        copy.setArchived(source.isArchived());
        copy.setPayments(new ArrayList<>(source.getPayments().stream().map(this::copyPayment).toList()));
        return copy;
    }

    private Payment copyPayment(Payment source) {
        Payment copy = new Payment(source.getCreditId(), source.getFromPlayer(), source.getToPlayer(), source.getAmount(),
                new ArrayList<>(source.getItems()), source.getSource());
        copy.setId(source.getId());
        copy.setItemNbt(source.getItemNbt());
        copy.setItemNbtEntries(source.getItemNbtEntries());
        copy.setTimestamp(source.getTimestamp());
        copy.setPaylogId(source.getPaylogId());
        copy.setNote(source.getNote());
        return copy;
    }

    private void syncCredit(CreditEntry target, CreditEntry source) {
        target.setId(source.getId());
        target.setDealName(source.getDealName());
        target.setCreditor(source.getCreditor());
        target.setDebtor(source.getDebtor());
        target.setAmount(source.getAmount());
        target.setPaidAmount(source.getPaidAmount());
        target.setCreatedAt(source.getCreatedAt());
        target.setDueDate(source.getDueDate());
        target.setStatus(source.getStatus());
        target.setNote(source.getNote());
        target.setCompletedAt(source.getCompletedAt());
        target.setArchived(source.isArchived());
        target.setPayments(new ArrayList<>(source.getPayments().stream().map(this::copyPayment).toList()));
    }

    private void validateActive(CreditEntry entry) throws CreditException {
        if (entry.isArchived() || STATUS_PAID.equals(entry.getStatus()) || STATUS_CLOSED.equals(entry.getStatus()) || STATUS_CANCELLED.equals(entry.getStatus())) {
            throw new CreditException("Deal ist abgeschlossen oder storniert.");
        }
    }

    private PaylogLinkResult linkPaylogToDeal(UUID paylogId, UUID dealId, boolean automatic, double requestedAmount,
                                              long requestedTimestamp, String note, String source) throws CreditException {
        requireWritable();
        TransactionEntry paylog = getPaylog(paylogId);
        CreditEntry entry = getSafeCredit(dealId);
        validateActive(entry);
        if (!samePlayer(paylog.getFromPlayer(), entry.getDebtor()) || !samePlayer(paylog.getToPlayer(), entry.getCreditor())) {
            throw new CreditException("Dieser Paylog passt nicht zu den Parteien des Deals.");
        }
        double available = paylog.getRemainingAmount();
        if (available <= EPSILON) return PaylogLinkResult.alreadyConsumed(paylog);
        CreditEntry draft = copyCredit(entry);
        double remainingBefore = draft.getRemainingAmount();
        if (automatic && available > remainingBefore + EPSILON) return PaylogLinkResult.noSingleDealFits(paylog);

        double wanted = Double.isFinite(requestedAmount) ? requestedAmount : available;
        validateAmount(wanted);
        double booked = Math.min(Math.min(wanted, available), remainingBefore);
        Payment payment = new Payment(draft.getId(), draft.getDebtor(), draft.getCreditor(), booked, null,
                source == null ? automatic ? "PAYLOG_AUTO" : "PAYLOG_MANUAL" : source);
        payment.setPaylogId(paylog.getId());
        payment.setTimestamp(requestedTimestamp > 0 ? requestedTimestamp : paylog.getTimestamp());
        payment.setNote(normalizeNote(note));
        draft.addPayment(payment);
        draft.setArchived(false);
        commitMutation(draft, List.of(payment), List.of(), paymentEvents(draft, payment, remainingBefore), entry);
        return PaylogLinkResult.linked(paylog, entry, payment, available - booked, automatic);
    }

    private TransactionEntry getPaylog(UUID paylogId) throws CreditException {
        if (paylogId == null) throw new CreditException("Paylog nicht gefunden.");
        return TransactionRepository.getInstance().find(paylogId)
                .orElseThrow(() -> new CreditException("Paylog nicht gefunden."));
    }

    private List<CreditEntry> matchingActiveDeals(TransactionEntry paylog) {
        return repository.getAllCredits().stream()
                .filter(entry -> !entry.isArchived())
                .filter(entry -> STATUS_OPEN.equals(entry.getStatus()) || STATUS_PARTIAL.equals(entry.getStatus()))
                .filter(entry -> samePlayer(paylog.getFromPlayer(), entry.getDebtor()))
                .filter(entry -> samePlayer(paylog.getToPlayer(), entry.getCreditor()))
                .sorted(Comparator.comparingLong(CreditEntry::getCreatedAt).thenComparing(entry -> entry.getId().toString()))
                .toList();
    }

    private boolean samePlayer(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private void validateAmount(double amount) throws CreditException {
        if (!Double.isFinite(amount) || amount <= 0 || amount > MAX_AMOUNT) {
            throw new CreditException("Betrag liegt außerhalb des erlaubten Bereichs.");
        }
        if (!Double.isFinite(amount) || amount <= 0) throw new CreditException("Betrag muss größer als 0 sein.");
    }

    private void validateNames(String creditor, String debtor) throws CreditException {
        validatePlayerName(creditor, "Gläubiger");
        validatePlayerName(debtor, "Schuldner");
        if (creditor == null || creditor.isBlank()) throw new CreditException("Ungültiger Gläubiger.");
        if (debtor == null || debtor.isBlank()) throw new CreditException("Ungültiger Schuldner.");
        if (creditor.equalsIgnoreCase(debtor)) throw new CreditException("Gläubiger und Schuldner dürfen nicht identisch sein.");
    }

    private void validatePlayerName(String value, String role) throws CreditException {
        if (value == null || !value.matches("[A-Za-z0-9_]{1," + MAX_PLAYER_NAME_LENGTH + "}")) {
            throw new CreditException("Ungültiger " + role + ".");
        }
    }

    private void validateDealInput(String label, String note, Long dueDate) throws CreditException {
        if (label != null && label.length() > MAX_LABEL_LENGTH) throw new CreditException("Die Deal-Bezeichnung ist zu lang.");
        if (note != null && note.length() > MAX_NOTE_LENGTH) throw new CreditException("Die Notiz ist zu lang.");
        if (dueDate != null && (dueDate <= 0 || dueDate > System.currentTimeMillis() + 3_155_760_000_000L)) {
            throw new CreditException("Ungültiges Fälligkeitsdatum.");
        }
    }

    private String normalizeNote(String note) throws CreditException {
        if (note == null || note.isBlank()) return null;
        if (note.length() > MAX_NOTE_LENGTH) throw new CreditException("Die Notiz ist zu lang.");
        return note.trim();
    }

    private String lower(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String lowerOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : lower(value); }
    private double safeAmount(Payment payment) { return payment.getAmount() == null ? 0.0 : payment.getAmount(); }

    public static class CreditException extends Exception {
        public CreditException(String message) { super(message); }
    }

    public record PaylogLinkResult(Status status, TransactionEntry paylog, CreditEntry credit, Payment payment,
                                   double remainingPaylogAmount, boolean automatic) {
        public enum Status { LINKED, NO_MATCHING_DEAL, NO_SINGLE_DEAL_FITS, ALREADY_CONSUMED }
        static PaylogLinkResult linked(TransactionEntry paylog, CreditEntry credit, Payment payment, double remaining, boolean automatic) {
            return new PaylogLinkResult(Status.LINKED, paylog, credit, payment, Math.max(0D, remaining), automatic);
        }
        static PaylogLinkResult noMatchingDeal(TransactionEntry paylog) { return new PaylogLinkResult(Status.NO_MATCHING_DEAL, paylog, null, null, paylog.getRemainingAmount(), true); }
        static PaylogLinkResult noSingleDealFits(TransactionEntry paylog) { return new PaylogLinkResult(Status.NO_SINGLE_DEAL_FITS, paylog, null, null, paylog.getRemainingAmount(), true); }
        static PaylogLinkResult alreadyConsumed(TransactionEntry paylog) { return new PaylogLinkResult(Status.ALREADY_CONSUMED, paylog, null, null, 0D, false); }
        public boolean linked() { return status == Status.LINKED; }
    }
}

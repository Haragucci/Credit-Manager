package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.JsonStorage;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public class CreditManager {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_CANCELLED = "CANCELLED";

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

        String dealName = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(entry -> dealName.equalsIgnoreCase(entry.getDealName())
                && !STATUS_CANCELLED.equals(entry.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");

        CreditEntry entry = new CreditEntry(UUID.randomUUID(), dealName, lower(creditor), lower(debtor), amount, dueDate, note);
        repository.putCredit(entry);
        persistCore();
        persistEvent(CreditEventType.CREDIT_CREATED, entry, entry.getAmount(), entry.getAmount(),
                entry.getNote(), creditor, "CREATE", false);
        return entry;
    }

    public Payment addMoneyPayment(UUID dealId, String fromPlayer, double amount) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateActive(entry);
        validateAmount(amount);

        double remainingBefore = entry.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(),
                Math.min(amount, remainingBefore), null, "MANUELL");
        entry.addPayment(payment);
        repository.putPayment(payment);
        persistCore();
        persistPaymentEvents(entry, payment, remainingBefore);
        return payment;
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

        double remainingBefore = entry.getRemainingAmount();
        Payment payment = new Payment(dealId, entry.getDebtor(), entry.getCreditor(),
                Math.min(value, remainingBefore), new ArrayList<>(items), "MANUELL");
        payment.setItemNbtEntries(nbtEntries);
        payment.setItemNbt(nbtEntries == null || nbtEntries.isEmpty() ? null : nbtEntries.get(0));
        entry.addPayment(payment);
        repository.putPayment(payment);
        persistCore();
        persistPaymentEvents(entry, payment, remainingBefore);
        return payment;
    }

    public CreditEntry deleteCredit(UUID dealId) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        double remainingBefore = entry.getRemainingAmount();
        // Preserve an auditable final record instead of orphaning the linked
        // payments and events through a physical delete.
        entry.setStatus(STATUS_CANCELLED);
        entry.setArchived(true);
        entry.setCompletedAt(System.currentTimeMillis());
        repository.putCredit(entry);
        persistEvent(CreditEventType.CREDIT_DELETED, entry, entry.getAmount(), remainingBefore,
                "Deal gelöscht", null, "DELETE", false);
        return entry;
    }

    public void deletePayment(UUID paymentId) throws CreditException {
        Payment payment = repository.getAllPayments().stream().filter(value -> value.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new CreditException("Zahlung nicht gefunden."));
        CreditEntry entry = getSafeCredit(payment.getCreditId());
        requireWritable();
        double remainingBefore = entry.getRemainingAmount();
        repository.deletePayment(paymentId);
        persistCore();
        persistEvent(CreditEventType.PAYMENT_DELETED, entry, safeAmount(payment), remainingBefore,
                "Zahlung gelöscht", payment.getFromPlayer(), payment.getSource(), !payment.getItems().isEmpty());
    }

    public CreditEntry updateCredit(UUID dealId, String creditor, String debtor, double amount, Long dueDate, String label, String note) throws CreditException {
        CreditEntry entry = getSafeCredit(dealId);
        requireWritable();
        validateNames(creditor, debtor);
        validateAmount(amount);
        if (amount + 0.0001D < entry.getPaidAmount()) throw new CreditException("Der Gesamtbetrag darf nicht kleiner als bereits bezahlt sein.");
        boolean counterpartyChanged = !lower(creditor).equals(entry.getCreditor()) || !lower(debtor).equals(entry.getDebtor());
        if (counterpartyChanged && !entry.getPayments().isEmpty()) {
            throw new CreditException("Die Gegenpartei kann nach vorhandenen Zahlungen nicht geändert werden.");
        }
        String name = CreditEntry.buildDealName(debtor, creditor, label);
        boolean exists = repository.getAllCredits().stream().anyMatch(other -> !other.getId().equals(dealId)
                && name.equalsIgnoreCase(other.getDealName()) && !STATUS_CANCELLED.equals(other.getStatus()));
        if (exists) throw new CreditException("Ein aktiver Deal mit diesem Namen existiert bereits.");
        double previousAmount = entry.getAmount();
        entry.setCreditor(lower(creditor)); entry.setDebtor(lower(debtor)); entry.setDealName(name); entry.setAmount(amount); entry.setDueDate(dueDate); entry.setNote(note == null || note.isBlank() ? null : note.trim());
        entry.refreshPaymentState();
        repository.putCredit(entry);
        persistEvent(CreditEventType.CREDIT_UPDATED, entry, amount, previousAmount, "Deal bearbeitet", null, "EDIT", false);
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
    public boolean isWritable() {
        return repository.isWritable() && CreditEventRepository.getInstance().isWritable()
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
                    "transactions.json", null, null, null, FileManager.getTransactionsFile(), "Beschädigte Paylog-Daten"));
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
        if (TRANSACTION_RECOVERY_TOKEN.equals(token)) return JsonStorage.createBackup(FileManager.getTransactionsFile());
        return repository.createRecoveryBackup(token);
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
        return entries.stream().filter(entry -> STATUS_OPEN.equals(entry.getStatus()) || STATUS_PARTIAL.equals(entry.getStatus())).toList();
    }

    private CreditEntry getSafeCredit(UUID id) throws CreditException {
        return repository.findCreditById(id).orElseThrow(() -> new CreditException("Deal nicht gefunden."));
    }

    private void requireWritable() throws CreditException {
        if (!isWritable()) throw new CreditException("Datenprüfung erforderlich: Änderungen sind vorübergehend gesperrt.");
    }

    private void persistCore() throws CreditException {
    }

    private void persistPaymentEvents(CreditEntry entry, Payment payment, double remainingBefore) throws CreditException {
        double amount = safeAmount(payment);
        boolean itemPayment = !payment.getItems().isEmpty();
        persistEvent(CreditEventType.PAYMENT_ADDED, entry, amount, remainingBefore, "Zahlung hinzugefügt",
                payment.getFromPlayer(), payment.getSource(), itemPayment);
        if (STATUS_PAID.equals(entry.getStatus())) {
            persistEvent(CreditEventType.CREDIT_PAID, entry, amount, remainingBefore, "Deal vollständig bezahlt",
                    payment.getFromPlayer(), payment.getSource(), itemPayment);
        } else if (STATUS_PARTIAL.equals(entry.getStatus())) {
            persistEvent(CreditEventType.CREDIT_PARTIAL, entry, amount, remainingBefore, "Teilzahlung",
                    payment.getFromPlayer(), payment.getSource(), itemPayment);
        }
    }

    private void persistEvent(CreditEventType type, CreditEntry entry, double amount, double amountBefore,
                              String note, String actor, String source, boolean itemPayment) throws CreditException {
        boolean saved = CreditEventRepository.getInstance().add(new CreditEventEntry(type, entry, amount, amountBefore,
                note, actor, source, itemPayment));
        if (!saved) {
            repository.load();
            CreditEventRepository.getInstance().recoverFromCore();
            throw new CreditException("Vorgang wurde nicht gespeichert; der vorherige Datenstand wurde wiederhergestellt.");
        }
    }

    private void validateActive(CreditEntry entry) throws CreditException {
        if (STATUS_PAID.equals(entry.getStatus()) || STATUS_CANCELLED.equals(entry.getStatus())) {
            throw new CreditException("Deal ist abgeschlossen oder storniert.");
        }
    }

    private void validateAmount(double amount) throws CreditException {
        if (!Double.isFinite(amount) || amount <= 0) throw new CreditException("Betrag muss größer als 0 sein.");
    }

    private void validateNames(String creditor, String debtor) throws CreditException {
        if (creditor == null || creditor.isBlank()) throw new CreditException("Ungültiger Gläubiger.");
        if (debtor == null || debtor.isBlank()) throw new CreditException("Ungültiger Schuldner.");
        if (creditor.equalsIgnoreCase(debtor)) throw new CreditException("Gläubiger und Schuldner dürfen nicht identisch sein.");
    }

    private String lower(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String lowerOrUnknown(String value) { return value == null || value.isBlank() ? "unknown" : lower(value); }
    private double safeAmount(Payment payment) { return payment.getAmount() == null ? 0.0 : payment.getAmount(); }

    public static class CreditException extends Exception {
        public CreditException(String message) { super(message); }
    }
}

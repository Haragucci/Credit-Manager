package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LegacyJsonMigrationService {
    private static final LegacyJsonMigrationService INSTANCE = new LegacyJsonMigrationService();
    private static final Gson GSON = new Gson();

    private LegacyJsonMigrationService() { }
    public static LegacyJsonMigrationService getInstance() { return INSTANCE; }

    public synchronized void inspectAtStartup() {
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();
        List<Path> files = legacyFiles();
        if (files.isEmpty()) {
            ClientConfigManager.markJsonMigrationChecked(true);
            return;
        }
        AutomaticPayload payload = readPayload();
        if (database.hasDomainData()) database.createBackup();
        DatabaseManager.AutomaticMigrationResult result = database.importLegacyAutomatically(
                new DatabaseManager.DatabaseState(payload.credits(), payload.payments(), payload.events()),
                payload.paylogs(), payload.preserved(), payload.summary());
        if (!result.success()) {
            DataHealth.reportRecoveryRequired("Automatische JSON-Migration konnte nicht gespeichert werden. Die Originaldateien bleiben erhalten.");
            CreditManagerClient.LOGGER.error("Automatic legacy JSON migration failed; source files were kept.");
            return;
        }
        archiveLegacyFiles(files);
        ClientConfigManager.markJsonMigrationChecked(true);
        CreditManagerClient.LOGGER.info("Automatic JSON migration completed: {} credits, {} payments, {} events, {} paylogs, {} preserved legacy records.",
                result.credits(), result.payments(), result.events(), result.paylogs(), result.preservedRecords());
    }

    public boolean isPending() { return false; }

    private AutomaticPayload readPayload() {
        Map<UUID, CreditEntry> credits = new LinkedHashMap<>();
        Map<UUID, Payment> payments = new LinkedHashMap<>();
        Map<UUID, CreditEventEntry> events = new LinkedHashMap<>();
        Map<UUID, TransactionEntry> paylogs = new LinkedHashMap<>();
        List<DatabaseManager.LegacyRecord> preserved = new ArrayList<>();

        JsonObject unified = readObject(FileManager.getCreditStateFile(), preserved);
        JsonObject creditSource = unified == null ? readObject(FileManager.getCreditsFile(), preserved) : object(unified.get("credits"));
        JsonObject paymentSource = readObject(FileManager.getPaymentsFile(), preserved);
        if (paymentSource == null && unified != null) paymentSource = object(unified.get("payments"));
        JsonArray eventSource = unified == null ? readArray(FileManager.getCreditEventsFile(), preserved) : array(unified.get("events"));
        JsonArray paylogSource = readArray(FileManager.getTransactionsFile(), preserved);

        for (Path backup : paymentBackupFiles()) readPayments(readObject(backup, preserved), payments, preserved, false, backup.getFileName().toString());
        readCredits(creditSource, credits, payments, preserved);
        readPayments(paymentSource, payments, preserved, true, "payments.json");
        readEvents(eventSource, events, preserved);
        readPaylogs(paylogSource, paylogs, preserved);
        createMissingCredits(credits, payments.values(), events.values());
        reconcileCreditPaymentState(credits, payments, preserved);

        return new AutomaticPayload(List.copyOf(credits.values()), List.copyOf(payments.values()), List.copyOf(events.values()),
                List.copyOf(paylogs.values()), List.copyOf(preserved));
    }

    private void readCredits(JsonObject source, Map<UUID, CreditEntry> credits, Map<UUID, Payment> payments,
                             List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> raw : source.entrySet()) {
            String payload = raw.getValue().toString();
            try {
                CreditEntry value = GSON.fromJson(raw.getValue(), CreditEntry.class);
                UUID id = uuid(raw.getKey(), value == null ? null : value.getId(), "credit", raw.getKey(), payload, preserved);
                if (value == null) value = new CreditEntry();
                normaliseCredit(value, id, raw.getKey(), payload, preserved);
                credits.putIfAbsent(id, value);
                if (value.getPayments() != null) {
                    for (Payment payment : value.getPayments()) {
                        Payment normalised = normalisePayment(payment, payment == null ? "embedded" : String.valueOf(payment.getId()), GSON.toJson(payment), preserved);
                        if (normalised != null) mergePayment(payments, normalised, raw.getKey(), GSON.toJson(payment), preserved, true, "embedded credit payment");
                    }
                }
            } catch (RuntimeException error) {
                UUID id = uuid(raw.getKey(), null, "credit", raw.getKey(), payload, preserved);
                CreditEntry placeholder = placeholderCredit(id, List.of(), "Ungültiger Legacy-Deal");
                credits.putIfAbsent(id, placeholder);
                preserve(preserved, "CREDIT", raw.getKey(), payload, "Ungültiger Deal wurde als archivierter Legacy-Deal erhalten.");
            }
        }
    }

    private void readPayments(JsonObject source, Map<UUID, Payment> payments, List<DatabaseManager.LegacyRecord> preserved,
                              boolean incomingWins, String sourceName) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> raw : source.entrySet()) {
            Payment value;
            try { value = GSON.fromJson(raw.getValue(), Payment.class); }
            catch (RuntimeException error) { preserve(preserved, "PAYMENT", raw.getKey(), raw.getValue().toString(), "Zahlung ist nicht lesbar."); continue; }
            Payment normalised = normalisePayment(value, raw.getKey(), raw.getValue().toString(), preserved);
            if (normalised != null) mergePayment(payments, normalised, raw.getKey(), raw.getValue().toString(), preserved, incomingWins, sourceName);
        }
    }

    private void mergePayment(Map<UUID, Payment> payments, Payment incoming, String originalId, String raw,
                              List<DatabaseManager.LegacyRecord> preserved, boolean incomingWins, String sourceName) {
        Payment existing = payments.get(incoming.getId());
        if (existing == null) {
            payments.put(incoming.getId(), incoming);
            return;
        }
        if (!samePayment(existing, incoming)) {
            preserve(preserved, "PAYMENT", originalId, raw,
                    "Abweichende Zahlung mit identischer UUID aus " + sourceName + " wurde als Rohdaten erhalten.");
            if (incomingWins) payments.put(incoming.getId(), incoming);
        }
    }

    private boolean samePayment(Payment left, Payment right) {
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getCreditId(), right.getCreditId())
                && Objects.equals(left.getFromPlayer(), right.getFromPlayer())
                && Objects.equals(left.getToPlayer(), right.getToPlayer())
                && Objects.equals(left.getAmount(), right.getAmount())
                && Objects.equals(left.getItems(), right.getItems())
                && Objects.equals(left.getItemNbt(), right.getItemNbt())
                && Objects.equals(left.getItemNbtEntries(), right.getItemNbtEntries())
                && left.getTimestamp() == right.getTimestamp()
                && Objects.equals(left.getSource(), right.getSource());
    }

    private void readEvents(JsonArray source, Map<UUID, CreditEventEntry> events, List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (int index = 0; index < source.size(); index++) {
            JsonElement raw = source.get(index);
            String key = "event-" + index;
            try {
                CreditEventEntry value = GSON.fromJson(raw, CreditEventEntry.class);
                if (value == null) throw new IllegalArgumentException();
                UUID id = uuid(key, value.getId(), "event", key, raw.toString(), preserved);
                UUID creditId = uuid(key, value.getCreditId(), "event-credit", key, raw.toString(), preserved);
                value.setId(id); value.setCreditId(creditId);
                if (value.getType() == null) { value.setType(CreditEventType.CREDIT_UPDATED); preserve(preserved, "EVENT", key, raw.toString(), "Unbekannter Eventtyp wurde als CREDIT_UPDATED übernommen."); }
                if (!Double.isFinite(value.getAmount())) { value.setAmount(0D); preserve(preserved, "EVENT", key, raw.toString(), "Ungültiger Eventbetrag wurde als 0 übernommen."); }
                if (value.getTimestamp() <= 0) value.setTimestamp(System.currentTimeMillis());
                if (blank(value.getSource())) value.setSource("LEGACY_JSON");
                events.putIfAbsent(id, value);
            } catch (RuntimeException error) {
                preserve(preserved, "EVENT", key, raw.toString(), "Event ist nicht lesbar.");
            }
        }
    }

    private void readPaylogs(JsonArray source, Map<UUID, TransactionEntry> paylogs, List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (int index = 0; index < source.size(); index++) {
            JsonElement raw = source.get(index);
            String key = "paylog-" + index;
            try {
                TransactionEntry value = GSON.fromJson(raw, TransactionEntry.class);
                if (value == null) throw new IllegalArgumentException();
                value.setId(uuid(key, value.getId(), "paylog", key, raw.toString(), preserved));
                boolean repaired = false;
                if (blank(value.getFromPlayer())) { value.setFromPlayer("legacy_sender"); repaired = true; }
                if (blank(value.getToPlayer())) { value.setToPlayer("legacy_receiver"); repaired = true; }
                if (!Double.isFinite(value.getAmount()) || value.getAmount() <= 0) { value.setAmount(0.01D); repaired = true; }
                if (value.getTimestamp() <= 0) { value.setTimestamp(System.currentTimeMillis()); repaired = true; }
                if (blank(value.getRawText())) value.setRawText(raw.toString());
                value.setSource("LEGACY_JSON");
                if (repaired) preserve(preserved, "PAYLOG", key, raw.toString(), "Unvollständiger Paylog wurde mit sicheren Legacy-Standardwerten übernommen.");
                paylogs.putIfAbsent(value.getId(), value);
            } catch (RuntimeException error) {
                preserve(preserved, "PAYLOG", key, raw.toString(), "Paylog ist nicht lesbar.");
            }
        }
    }

    private Payment normalisePayment(Payment value, String key, String raw, List<DatabaseManager.LegacyRecord> preserved) {
        if (value == null) { preserve(preserved, "PAYMENT", key, raw, "Zahlung fehlt."); return null; }
        UUID id = uuid(key, value.getId(), "payment", key, raw, preserved);
        UUID creditId = uuid(key, value.getCreditId(), "payment-credit", key, raw, preserved);
        if (value.getAmount() == null || !Double.isFinite(value.getAmount()) || value.getAmount() <= 0) {
            preserve(preserved, "PAYMENT", key, raw, "Ungültiger Zahlungsbetrag; Original wurde erhalten, aber nicht als Zahlung gebucht.");
            return null;
        }
        value.setId(id); value.setCreditId(creditId);
        if (value.getTimestamp() <= 0) value.setTimestamp(System.currentTimeMillis());
        if (blank(value.getSource())) value.setSource("LEGACY_JSON");
        return value;
    }

    private void normaliseCredit(CreditEntry value, UUID id, String key, String raw, List<DatabaseManager.LegacyRecord> preserved) {
        value.setId(id);
        String creditor = party(value.getCreditor(), "legacy_creditor_" + shortId(id));
        String debtor = party(value.getDebtor(), "legacy_debtor_" + shortId(id));
        if (creditor.equalsIgnoreCase(debtor)) debtor = "legacy_debtor_" + shortId(id);
        value.setCreditor(creditor); value.setDebtor(debtor);
        if (blank(value.getDealName())) value.setDealName("legacy-deal-" + shortId(id));
        boolean repaired = false;
        if (!Double.isFinite(value.getAmount()) || value.getAmount() <= 0) { value.setAmount(1D); repaired = true; }
        if (!Double.isFinite(value.getPaidAmount()) || value.getPaidAmount() < 0 || value.getPaidAmount() > value.getAmount()) { value.setPaidAmount(Double.isFinite(value.getPaidAmount()) ? Math.max(0D, Math.min(value.getAmount(), value.getPaidAmount())) : 0D); repaired = true; }
        if (value.getCreatedAt() <= 0) { value.setCreatedAt(System.currentTimeMillis()); repaired = true; }
        if (blank(value.getStatus())) value.setStatus(value.getPaidAmount() >= value.getAmount() ? "PAID" : value.getPaidAmount() > 0 ? "PARTIAL" : "OPEN");
        if (repaired) preserve(preserved, "CREDIT", key, raw, "Ungültige Dealwerte wurden mit sicheren Legacy-Standardwerten übernommen.");
    }

    private void createMissingCredits(Map<UUID, CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) {
        Map<UUID, List<Payment>> groupedPayments = new LinkedHashMap<>();
        for (Payment payment : payments) groupedPayments.computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>()).add(payment);
        for (CreditEventEntry event : events) groupedPayments.putIfAbsent(event.getCreditId(), List.of());
        for (Map.Entry<UUID, List<Payment>> missing : groupedPayments.entrySet()) {
            if (!credits.containsKey(missing.getKey())) credits.put(missing.getKey(), placeholderCredit(missing.getKey(), missing.getValue(), "Automatisch aus verwaisten Legacy-Daten erstellt"));
        }
    }

    private void reconcileCreditPaymentState(Map<UUID, CreditEntry> credits, Map<UUID, Payment> payments,
                                             List<DatabaseManager.LegacyRecord> preserved) {
        for (CreditEntry credit : credits.values()) {
            List<Payment> linked = new ArrayList<>(payments.values().stream()
                    .filter(payment -> credit.getId().equals(payment.getCreditId()))
                    .sorted(Comparator.comparingLong(Payment::getTimestamp)).toList());
            double total = linked.stream().map(Payment::getAmount).filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
            double recorded = Math.max(0D, credit.getPaidAmount());
            if (recorded > total + 0.0001D) {
                double difference = recorded - total;
                UUID id = UUID.nameUUIDFromBytes(("migration-balance:" + credit.getId()).getBytes(StandardCharsets.UTF_8));
                if (!payments.containsKey(id)) {
                    Payment balance = new Payment(credit.getId(), credit.getDebtor(), credit.getCreditor(), difference, null, "MIGRATION_BALANCE");
                    balance.setId(id);
                    balance.setTimestamp(Math.max(1L, credit.getCreatedAt()));
                    payments.put(id, balance);
                    linked.add(balance);
                    total += difference;
                    preserve(preserved, "CREDIT", credit.getId().toString(), GSON.toJson(credit),
                            "Gespeicherter paidAmount ohne Einzelzahlung wurde als MIGRATION_BALANCE erhalten.");
                }
            }
            if (total > credit.getAmount() + 0.0001D) {
                credit.setAmount(total);
                preserve(preserved, "CREDIT", credit.getId().toString(), GSON.toJson(credit),
                        "Zahlungssumme war größer als der Dealbetrag; Betrag wurde verlustfrei angepasst.");
            }
            String previousStatus = credit.getStatus();
            credit.replacePayments(linked);
            if ("CANCELLED".equals(previousStatus)) credit.setStatus("CANCELLED");
        }
    }

    private CreditEntry placeholderCredit(UUID id, Collection<Payment> linked, String note) {
        Payment sample = linked.stream().findFirst().orElse(null);
        String creditor = party(sample == null ? null : sample.getToPlayer(), "legacy_creditor_" + shortId(id));
        String debtor = party(sample == null ? null : sample.getFromPlayer(), "legacy_debtor_" + shortId(id));
        if (creditor.equalsIgnoreCase(debtor)) debtor = "legacy_debtor_" + shortId(id);
        double amount = linked.stream().map(Payment::getAmount).filter(value -> value != null && Double.isFinite(value) && value > 0).mapToDouble(Double::doubleValue).sum();
        if (amount <= 0) amount = 1D;
        CreditEntry value = new CreditEntry(id, "legacy-unlinked-" + shortId(id), creditor, debtor, amount, null, note);
        value.setPaidAmount(amount); value.setStatus("PAID"); value.setArchived(true); value.setCompletedAt(System.currentTimeMillis());
        return value;
    }

    private JsonObject readObject(Path file, List<DatabaseManager.LegacyRecord> preserved) {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonElement value = JsonParser.parseString(Files.readString(file));
            if (!value.isJsonObject()) throw new IllegalArgumentException("Erwartetes JSON-Objekt fehlt.");
            return value.getAsJsonObject();
        } catch (Exception error) {
            preserveFile(file, preserved, error.getMessage());
            return null;
        }
    }

    private JsonArray readArray(Path file, List<DatabaseManager.LegacyRecord> preserved) {
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonElement value = JsonParser.parseString(Files.readString(file));
            if (!value.isJsonArray()) throw new IllegalArgumentException("Erwartetes JSON-Array fehlt.");
            return value.getAsJsonArray();
        } catch (Exception error) {
            preserveFile(file, preserved, error.getMessage());
            return null;
        }
    }

    private void preserveFile(Path file, List<DatabaseManager.LegacyRecord> preserved, String reason) {
        try { preserve(preserved, "FILE", file.getFileName().toString(), Files.readString(file), reason); }
        catch (IOException ignored) { preserve(preserved, "FILE", file.getFileName().toString(), "", reason); }
    }
    private void preserve(List<DatabaseManager.LegacyRecord> values, String kind, String id, String raw, String reason) { values.add(new DatabaseManager.LegacyRecord(kind, id, raw == null ? "null" : raw, reason)); }
    private UUID uuid(String source, UUID preferred, String kind, String originalId, String raw, List<DatabaseManager.LegacyRecord> preserved) { if (preferred != null) return preferred; try { return UUID.fromString(source); } catch (RuntimeException ignored) { UUID generated = UUID.nameUUIDFromBytes((kind + ':' + source).getBytes(StandardCharsets.UTF_8)); preserve(preserved, kind.toUpperCase(Locale.ROOT), originalId, raw, "Ungültige oder fehlende UUID; automatisch ersetzt durch " + generated + '.'); return generated; } }
    private JsonObject object(JsonElement value) { return value != null && value.isJsonObject() ? value.getAsJsonObject() : null; }
    private JsonArray array(JsonElement value) { return value != null && value.isJsonArray() ? value.getAsJsonArray() : null; }
    private String party(String value, String fallback) { return blank(value) ? fallback : value.trim().toLowerCase(Locale.ROOT); }
    private String shortId(UUID id) { return id.toString().substring(0, 8); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    private List<Path> legacyFiles() {
        List<Path> files = new ArrayList<>();
        for (Path file : List.of(FileManager.getCreditStateFile(), FileManager.getCreditsFile(), FileManager.getPaymentsFile(), FileManager.getPlayersFile(), FileManager.getCreditEventsFile(), FileManager.getTransactionsFile())) if (Files.isRegularFile(file)) files.add(file);
        files.addAll(paymentBackupFiles());
        return files;
    }

    private List<Path> paymentBackupFiles() {
        List<Path> files = new ArrayList<>();
        try (var listed = Files.list(FileManager.getDataDirectory())) {
            listed.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().matches("payments_backup_[0-9]+\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(files::add);
        } catch (IOException error) { CreditManagerClient.LOGGER.warn("Could not list legacy JSON backups", error); }
        return files;
    }

    private void archiveLegacyFiles(List<Path> files) {
        if (files.isEmpty()) return;
        try {
            Path target = FileManager.getLegacyArchiveDirectory().resolve("migration-" + System.currentTimeMillis());
            Files.createDirectories(target);
            for (Path file : files) if (Files.isRegularFile(file)) Files.move(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("JSON migration committed, but legacy files could not be archived", exception);
        }
    }

    private record AutomaticPayload(List<CreditEntry> credits, List<Payment> payments, List<CreditEventEntry> events,
                                    List<TransactionEntry> paylogs, List<DatabaseManager.LegacyRecord> preserved) {
        private String summary() { return credits.size() + " Deals, " + payments.size() + " Zahlungen, " + events.size() + " Events, " + paylogs.size() + " Paylogs, " + preserved.size() + " Rohdatensätze"; }
    }
}

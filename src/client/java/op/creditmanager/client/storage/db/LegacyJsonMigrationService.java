package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.PaymentKind;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class LegacyJsonMigrationService {
    private static final LegacyJsonMigrationService INSTANCE = new LegacyJsonMigrationService();
    private static final Gson GSON = new Gson();
    private static final long MAX_JSON_BYTES = 67_108_864L;
    private final RecoveryFileOps fileOps;

    private LegacyJsonMigrationService() { this(new RecoveryFileOps()); }
    LegacyJsonMigrationService(RecoveryFileOps fileOps) { this.fileOps = fileOps == null ? new RecoveryFileOps() : fileOps; }

    public static LegacyJsonMigrationService getInstance() {
        return INSTANCE;
    }

    public synchronized void inspectAtStartup() {
        DatabaseManager database = DatabaseManager.getInstance();
        List<Path> files = legacyFiles();
        try {
            database.initialize();
            if (database.hasCompletedAutomaticJsonMigration() && files.isEmpty()) {
                ClientConfigManager.markJsonMigrationChecked(true);
                return;
            }
        } catch (RuntimeException exception) {
            DataHealth.reportRecoveryRequired("Legacy-Migrationsstatus konnte nicht sicher gelesen werden. Es wurden keine Quelldaten verändert.");
            CreditManagerClient.LOGGER.error("Automatic legacy JSON migration state check failed", exception);
            return;
        }
        if (files.isEmpty()) {
            try {
                if (database.hasPendingAutomaticJsonMigration() && finishMigration(database, "Quellen waren bereits vollständig archiviert.")) {
                    ClientConfigManager.markJsonMigrationChecked(true);
                }
            } catch (RuntimeException exception) {
                DataHealth.reportRecoveryRequired("Der Abschluss der Legacy-Migration konnte nicht sicher geprüft werden.");
                CreditManagerClient.LOGGER.error("Automatic legacy JSON migration completion check failed", exception);
            }
            return;
        }
        SourceManifest manifest;
        AutomaticPayload payload;
        try {
            manifest = sourceManifest(files);
            payload = readPayload();
        } catch (RuntimeException exception) {
            DataHealth.reportRecoveryRequired("Legacy-JSON-Dateien konnten nicht sicher vorbereitet werden. Die Quelldateien bleiben erhalten.");
            CreditManagerClient.LOGGER.error("Automatic legacy JSON migration preflight failed", exception);
            return;
        }
        try {
            if (database.hasDomainData() && !database.createBackup()) {
                DataHealth.reportRecoveryRequired("Die Pflichtsicherung vor der Legacy-Migration konnte nicht validiert werden. Es wurden keine Daten importiert.");
                return;
            }
        } catch (RuntimeException exception) {
            DataHealth.reportRecoveryRequired("Der Datenbestand vor der Legacy-Migration konnte nicht sicher geprüft werden. Es wurden keine Daten importiert.");
            CreditManagerClient.LOGGER.error("Automatic legacy JSON migration data-state check failed", exception);
            return;
        }
        DatabaseManager.AutomaticMigrationResult result = database.importLegacyAutomatically(
                new DatabaseManager.DatabaseState(payload.credits(), payload.payments(), payload.events()),
                payload.paylogs(), payload.preserved(), payload.summary() + "; sources=" + manifest.summary());
        if (!result.success()) {
            DataHealth.reportRecoveryRequired("Automatische JSON-Migration konnte nicht gespeichert werden. Die Originaldateien bleiben erhalten.");
            CreditManagerClient.LOGGER.error("Automatic legacy JSON migration failed; source files were kept.");
            return;
        }
        ArchiveResult archive = archiveLegacyFiles(manifest);
        if (!archive.success()) {
            DataHealth.reportRecoveryRequired("Legacy-Daten wurden importiert, aber nicht alle Quelldateien konnten verifiziert archiviert werden. Die Migration bleibt unvollständig.");
            return;
        }
        if (!finishMigration(database, payload.summary() + "; archive=" + archive.details())) return;
        ClientConfigManager.markJsonMigrationChecked(true);
        CreditManagerClient.LOGGER.info("Automatic JSON migration completed: {} credits, {} payments, {} events, {} paylogs, {} preserved legacy records.",
                result.credits(), result.payments(), result.events(), result.paylogs(), result.preservedRecords());
    }

    public boolean isPending() {
        return DatabaseManager.getInstance().hasPendingAutomaticJsonMigration();
    }

    private boolean finishMigration(DatabaseManager database, String details) {
        boolean hasErrors = database.runHealthCheck().stream().anyMatch(record -> "ERROR".equals(record.severity()) && "OPEN".equals(record.status()));
        if (hasErrors) {
            DataHealth.reportRecoveryRequired("Die importierten Daten haben die abschließende Integritätsprüfung nicht bestanden.");
            return false;
        }
        return database.completeAutomaticJsonMigration(details);
    }

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
        createDerivedCredits(credits, payments.values(), events, preserved);
        reconcileCreditPaymentState(credits, payments, preserved);

        return new AutomaticPayload(List.copyOf(credits.values()), List.copyOf(payments.values()), List.copyOf(events.values()),
                List.copyOf(paylogs.values()), List.copyOf(preserved));
    }

    private void readCredits(JsonObject source, Map<UUID, CreditEntry> credits, Map<UUID, Payment> payments,
                             List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> raw : source.entrySet()) {
            String payload = raw.getValue().toString();
            if (!raw.getValue().isJsonObject()) {
                preserve(preserved, "CREDIT", raw.getKey(), payload, "Deal ist kein JSON-Objekt.");
                continue;
            }
            JsonObject object = raw.getValue().getAsJsonObject();
            UUID preferred = parsedUuid(object, "id");
            UUID id = uuid(raw.getKey(), preferred, "credit", raw.getKey(), payload, preserved);
            JsonArray embedded = array(object.get("payments"));
            if (embedded != null) {
                for (int index = 0; index < embedded.size(); index++) {
                    JsonElement element = embedded.get(index);
                    Payment payment = parsePayment(element, "embedded-" + id + '-' + index, preserved);
                    if (payment != null) mergePayment(payments, payment, String.valueOf(payment.getId()), element.toString(), preserved, true, "embedded credit payment");
                }
            }
            try {
                CreditEntry value = GSON.fromJson(object, CreditEntry.class);
                if (value == null) throw new IllegalArgumentException("Deal fehlt");
                value.setId(id);
                value.setAmountMinor(requiredMoney(object, "amount", "amountMinor", true));
                value.setPaidAmountMinor(optionalMoney(object, "paidAmount", "paidAmountMinor", false, 0L));
                normalizeCredit(value, raw.getKey(), payload, preserved);
                CreditEntry existing = credits.putIfAbsent(id, value);
                if (existing != null && !sameCredit(existing, value)) preserve(preserved, "CREDIT", raw.getKey(), payload, "Abweichender Deal mit identischer UUID wurde nicht überschrieben.");
            } catch (RuntimeException exception) {
                preserve(preserved, "CREDIT", raw.getKey(), payload, "Ungültiger Deal wurde nicht als aktive Forderung übernommen: " + safe(exception.getMessage()));
            }
        }
    }

    private void readPayments(JsonObject source, Map<UUID, Payment> payments, List<DatabaseManager.LegacyRecord> preserved,
                              boolean incomingWins, String sourceName) {
        if (source == null) return;
        for (Map.Entry<String, JsonElement> raw : source.entrySet()) {
            Payment value = parsePayment(raw.getValue(), raw.getKey(), preserved);
            if (value != null) mergePayment(payments, value, raw.getKey(), raw.getValue().toString(), preserved, incomingWins, sourceName);
        }
    }

    private Payment parsePayment(JsonElement raw, String key, List<DatabaseManager.LegacyRecord> preserved) {
        String payload = String.valueOf(raw);
        try {
            if (raw == null || !raw.isJsonObject()) throw new IllegalArgumentException("Zahlung ist kein Objekt");
            JsonObject object = raw.getAsJsonObject();
            Payment value = GSON.fromJson(object, Payment.class);
            if (value == null) throw new IllegalArgumentException("Zahlung fehlt");
            value.setId(uuid(key, value.getId(), "payment", key, payload, preserved));
            value.setCreditId(uuid(key, value.getCreditId(), "payment-credit", key, payload, preserved));
            value.setAmountMinor(requiredMoney(object, "amount", "amountMinor", true));
            value.setItems(stringList(object, "items"));
            value.setItemNbtEntries(stringList(object, "itemNbtEntries"));
            PaymentKind kind = value.getPaymentKind();
            if (object.has("paymentKind")) kind = PaymentKind.valueOf(object.get("paymentKind").getAsString().toUpperCase(Locale.ROOT));
            else kind = value.getItems().isEmpty() ? PaymentKind.MONEY : PaymentKind.ITEM;
            if (kind == PaymentKind.ITEM && value.getItems().isEmpty()) throw new IllegalArgumentException("Item-Zahlung ohne Itemdaten");
            value.setPaymentKind(kind);
            if (value.getTimestamp() <= 0L) throw new IllegalArgumentException("Zahlungszeitpunkt fehlt");
            if (blank(value.getSource())) value.setSource("LEGACY_JSON");
            return value;
        } catch (RuntimeException exception) {
            preserve(preserved, "PAYMENT", key, payload, "Zahlung wurde nicht gebucht: " + safe(exception.getMessage()));
            return null;
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
            preserve(preserved, "PAYMENT", originalId, raw, "Abweichende Zahlung mit identischer UUID aus " + sourceName + " wurde als Rohdaten erhalten.");
            if (incomingWins) payments.put(incoming.getId(), incoming);
        }
    }

    private boolean samePayment(Payment left, Payment right) {
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getCreditId(), right.getCreditId())
                && Objects.equals(left.getFromPlayer(), right.getFromPlayer())
                && Objects.equals(left.getToPlayer(), right.getToPlayer())
                && left.getAmountMinor() == right.getAmountMinor()
                && left.getPaymentKind() == right.getPaymentKind()
                && Objects.equals(left.getItems(), right.getItems())
                && Objects.equals(left.getItemNbt(), right.getItemNbt())
                && Objects.equals(left.getItemNbtEntries(), right.getItemNbtEntries())
                && left.getTimestamp() == right.getTimestamp()
                && Objects.equals(left.getSource(), right.getSource())
                && Objects.equals(left.getPaylogId(), right.getPaylogId())
                && Objects.equals(left.getNote(), right.getNote());
    }

    private boolean sameCredit(CreditEntry left, CreditEntry right) {
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getDealName(), right.getDealName())
                && Objects.equals(left.getCreditor(), right.getCreditor())
                && Objects.equals(left.getDebtor(), right.getDebtor())
                && left.getAmountMinor() == right.getAmountMinor()
                && left.getPaidAmountMinor() == right.getPaidAmountMinor()
                && left.getCreatedAt() == right.getCreatedAt()
                && Objects.equals(left.getDueDate(), right.getDueDate())
                && Objects.equals(left.getStatus(), right.getStatus())
                && Objects.equals(left.getNote(), right.getNote());
    }

    private void readEvents(JsonArray source, Map<UUID, CreditEventEntry> events, List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (int index = 0; index < source.size(); index++) {
            JsonElement raw = source.get(index);
            String key = "event-" + index;
            try {
                if (!raw.isJsonObject()) throw new IllegalArgumentException("Event ist kein Objekt");
                JsonObject object = raw.getAsJsonObject();
                CreditEventEntry value = GSON.fromJson(object, CreditEventEntry.class);
                if (value == null || value.getType() == null) throw new IllegalArgumentException("Eventtyp fehlt");
                value.setId(uuid(key, value.getId(), "event", key, raw.toString(), preserved));
                value.setCreditId(uuid(key, value.getCreditId(), "event-credit", key, raw.toString(), preserved));
                value.setAmountMinor(optionalMoney(object, "amount", "amountMinor", false, 0L));
                value.setPaidAmountAfterMinor(optionalMoney(object, "paidAmountAfter", "paidAmountAfterMinor", false, 0L));
                value.setRemainingAmountAfterMinor(optionalMoney(object, "remainingAmountAfter", "remainingAmountAfterMinor", false, 0L));
                value.setAmountBeforeMinor(optionalMoney(object, "amountBefore", "amountBeforeMinor", false, 0L));
                value.setAmountAfterMinor(optionalMoney(object, "amountAfter", "amountAfterMinor", false, 0L));
                if (value.getAmountMinor() < 0L || value.getPaidAmountAfterMinor() < 0L || value.getRemainingAmountAfterMinor() < 0L || value.getAmountBeforeMinor() < 0L || value.getAmountAfterMinor() < 0L) throw new IllegalArgumentException("Negativer Eventbetrag");
                if (value.getTimestamp() <= 0L) throw new IllegalArgumentException("Eventzeitpunkt fehlt");
                if (blank(value.getSource())) value.setSource("LEGACY_JSON");
                events.putIfAbsent(value.getId(), value);
            } catch (RuntimeException exception) {
                preserve(preserved, "EVENT", key, raw.toString(), "Event wurde nicht aktiv übernommen: " + safe(exception.getMessage()));
            }
        }
    }

    private void readPaylogs(JsonArray source, Map<UUID, TransactionEntry> paylogs, List<DatabaseManager.LegacyRecord> preserved) {
        if (source == null) return;
        for (int index = 0; index < source.size(); index++) {
            JsonElement raw = source.get(index);
            String key = "paylog-" + index;
            try {
                if (!raw.isJsonObject()) throw new IllegalArgumentException("Paylog ist kein Objekt");
                JsonObject object = raw.getAsJsonObject();
                TransactionEntry value = GSON.fromJson(object, TransactionEntry.class);
                if (value == null) throw new IllegalArgumentException("Paylog fehlt");
                value.setId(uuid(key, value.getId(), "paylog", key, raw.toString(), preserved));
                value.setAmountMinor(requiredMoney(object, "amount", "amountMinor", true));
                if (blank(value.getFromPlayer()) || blank(value.getToPlayer()) || value.getFromPlayer().equalsIgnoreCase(value.getToPlayer())) throw new IllegalArgumentException("Paylog-Parteien fehlen");
                if (value.getTimestamp() <= 0L) throw new IllegalArgumentException("Paylog-Zeitpunkt fehlt");
                if (blank(value.getRawText())) value.setRawText(raw.toString());
                if (blank(value.getSource())) value.setSource("LEGACY_JSON");
                TransactionEntry existing = paylogs.putIfAbsent(value.getId(), value);
                if (existing != null && !samePaylog(existing, value)) preserve(preserved, "PAYLOG", key, raw.toString(), "Abweichender Paylog mit identischer UUID wurde nicht überschrieben.");
            } catch (RuntimeException exception) {
                preserve(preserved, "PAYLOG", key, raw.toString(), "Paylog wurde nicht aktiv übernommen: " + safe(exception.getMessage()));
            }
        }
    }

    private boolean samePaylog(TransactionEntry left, TransactionEntry right) {
        return Objects.equals(left.getId(), right.getId())
                && Objects.equals(left.getFromPlayer(), right.getFromPlayer())
                && Objects.equals(left.getToPlayer(), right.getToPlayer())
                && left.getAmountMinor() == right.getAmountMinor()
                && left.getTimestamp() == right.getTimestamp()
                && Objects.equals(left.getRawText(), right.getRawText())
                && Objects.equals(left.getSource(), right.getSource())
                && Objects.equals(left.getMetadata(), right.getMetadata());
    }

    private void normalizeCredit(CreditEntry value, String key, String raw, List<DatabaseManager.LegacyRecord> preserved) {
        String creditor = party(value.getCreditor(), "legacy_creditor_" + shortId(value.getId()));
        String debtor = party(value.getDebtor(), "legacy_debtor_" + shortId(value.getId()));
        if (creditor.equalsIgnoreCase(debtor)) throw new IllegalArgumentException("Identische Deal-Parteien");
        value.setCreditor(creditor);
        value.setDebtor(debtor);
        if (blank(value.getDealName())) value.setDealName("legacy-deal-" + shortId(value.getId()));
        if (value.getPaidAmountMinor() < 0L || value.getPaidAmountMinor() > value.getAmountMinor()) throw new IllegalArgumentException("Ungültiger paidAmount");
        if (value.getCreatedAt() <= 0L) {
            value.setCreatedAt(1L);
            preserve(preserved, "CREDIT", key, raw, "Fehlender Erstellzeitpunkt wurde deterministisch auf den frühesten darstellbaren Zeitpunkt gesetzt.");
        }
        if (blank(value.getStatus())) value.setStatus(CreditStatusRules.derive(value.getAmountMinor(), value.getPaidAmountMinor()));
        if (!CreditStatusRules.isManualFinal(value.getStatus())) {
            String derived = CreditStatusRules.derive(value.getAmountMinor(), value.getPaidAmountMinor());
            if (!derived.equals(value.getStatus())) {
                preserve(preserved, "CREDIT", key, raw, "Inkonsistenter Legacy-Status wurde aus exakten Beträgen neu abgeleitet.");
                value.setStatus(derived);
            }
        }
        value.setPayments(new ArrayList<>());
    }

    private void createDerivedCredits(Map<UUID, CreditEntry> credits, Collection<Payment> payments,
                                      Map<UUID, CreditEventEntry> events, List<DatabaseManager.LegacyRecord> preserved) {
        Map<UUID, List<Payment>> grouped = new LinkedHashMap<>();
        for (Payment payment : payments) grouped.computeIfAbsent(payment.getCreditId(), ignored -> new ArrayList<>()).add(payment);
        for (Map.Entry<UUID, List<Payment>> missing : grouped.entrySet()) {
            if (credits.containsKey(missing.getKey())) continue;
            try {
                CreditEntry derived = derivedCredit(missing.getKey(), missing.getValue());
                credits.put(missing.getKey(), derived);
                preserve(preserved, "CREDIT", missing.getKey().toString(), GSON.toJson(missing.getValue()), "Fehlender Deal wurde ausschließlich aus validierten Zahlungen abgeleitet.");
            } catch (RuntimeException exception) {
                for (Payment payment : missing.getValue()) payments.remove(payment);
                preserve(preserved, "CREDIT", missing.getKey().toString(), GSON.toJson(missing.getValue()), "Verwaiste Zahlungen konnten keinen eindeutigen Deal ableiten.");
            }
        }
        events.entrySet().removeIf(entry -> {
            if (credits.containsKey(entry.getValue().getCreditId())) return false;
            preserve(preserved, "EVENT", entry.getKey().toString(), GSON.toJson(entry.getValue()), "Event verweist auf keinen ableitbaren Deal.");
            return true;
        });
    }

    private CreditEntry derivedCredit(UUID id, List<Payment> linked) {
        if (linked.isEmpty()) throw new IllegalArgumentException("Keine Zahlungen");
        Payment sample = linked.getFirst();
        String creditor = party(sample.getToPlayer(), null);
        String debtor = party(sample.getFromPlayer(), null);
        if (blank(creditor) || blank(debtor) || creditor.equalsIgnoreCase(debtor)) {
            creditor = "legacy_creditor_" + shortId(id);
            debtor = "legacy_debtor_" + shortId(id);
        }
        long total = 0L;
        for (Payment payment : linked) total = Math.addExact(total, payment.getAmountMinor());
        if (!MoneyRules.isPositive(total)) throw new IllegalArgumentException("Betrag nicht ableitbar");
        CreditEntry value = new CreditEntry(id, "legacy-unlinked-" + shortId(id), creditor, debtor, total, null, "Aus validierten Legacy-Zahlungen abgeleitet");
        value.setCreatedAt(linked.stream().mapToLong(Payment::getTimestamp).min().orElse(1L));
        value.setPaidAmountMinor(total);
        value.setStatus("PAID");
        value.setArchived(true);
        value.setCompletedAt(linked.stream().mapToLong(Payment::getTimestamp).max().orElse(value.getCreatedAt()));
        return value;
    }

    private void reconcileCreditPaymentState(Map<UUID, CreditEntry> credits, Map<UUID, Payment> payments,
                                             List<DatabaseManager.LegacyRecord> preserved) {
        for (CreditEntry credit : credits.values()) {
            List<Payment> linked = payments.values().stream().filter(payment -> credit.getId().equals(payment.getCreditId()))
                    .sorted(Comparator.comparingLong(Payment::getTimestamp)).toList();
            long total = 0L;
            for (Payment payment : linked) total = Math.addExact(total, payment.getAmountMinor());
            long recorded = credit.getPaidAmountMinor();
            if (recorded > total) {
                long difference = Math.subtractExact(recorded, total);
                UUID id = UUID.nameUUIDFromBytes(("migration-balance:" + credit.getId()).getBytes(StandardCharsets.UTF_8));
                if (!payments.containsKey(id)) {
                    Payment balance = new Payment(credit.getId(), credit.getDebtor(), credit.getCreditor(), difference, List.of(), "MIGRATION_BALANCE");
                    balance.setId(id);
                    balance.setTimestamp(credit.getCreatedAt());
                    payments.put(id, balance);
                    linked = new ArrayList<>(linked);
                    linked.add(balance);
                    total = Math.addExact(total, difference);
                    preserve(preserved, "CREDIT", credit.getId().toString(), GSON.toJson(credit), "Quellwert paidAmount wurde als exakt abgeleitete MIGRATION_BALANCE erhalten.");
                }
            }
            if (total > credit.getAmountMinor()) {
                credit.setAmountMinor(total);
                preserve(preserved, "CREDIT", credit.getId().toString(), GSON.toJson(credit), "Dealbetrag wurde auf die validierte Zahlungssumme angehoben.");
            }
            String previousStatus = credit.getStatus();
            credit.replacePayments(linked);
            if (CreditStatusRules.isManualFinal(previousStatus)) credit.setStatus(previousStatus);
        }
    }

    private long requiredMoney(JsonObject object, String majorName, String minorName, boolean positive) {
        JsonElement minor = object.get(minorName);
        if (minor != null && !minor.isJsonNull()) {
            long value = new BigDecimal(minor.getAsString()).longValueExact();
            if (positive ? !MoneyRules.isPositive(value) : !MoneyRules.isValid(value)) throw new IllegalArgumentException("Ungültiger Minor-Unit-Betrag");
            return value;
        }
        JsonElement major = object.get(majorName);
        if (major == null || major.isJsonNull() || !major.isJsonPrimitive()) throw new IllegalArgumentException("Betrag fehlt");
        return MoneyRules.fromMajor(new BigDecimal(major.getAsString()), positive).minorUnits();
    }

    private long optionalMoney(JsonObject object, String majorName, String minorName, boolean positive, long fallback) {
        return object.has(minorName) || object.has(majorName) ? requiredMoney(object, majorName, minorName, positive) : fallback;
    }

    private List<String> stringList(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) return List.of();
        if (!element.isJsonArray()) throw new IllegalArgumentException(name + " ist kein Array");
        List<String> result = new ArrayList<>();
        for (JsonElement value : element.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name + " enthält ungültige Daten");
            result.add(value.getAsString());
        }
        return List.copyOf(result);
    }

    private JsonObject readObject(Path file, List<DatabaseManager.LegacyRecord> preserved) {
        JsonElement value = readJson(file, preserved);
        if (value == null) return null;
        if (!value.isJsonObject()) {
            preserveFile(file, preserved, "Erwartetes JSON-Objekt fehlt.");
            return null;
        }
        return value.getAsJsonObject();
    }

    private JsonArray readArray(Path file, List<DatabaseManager.LegacyRecord> preserved) {
        JsonElement value = readJson(file, preserved);
        if (value == null) return null;
        if (!value.isJsonArray()) {
            preserveFile(file, preserved, "Erwartetes JSON-Array fehlt.");
            return null;
        }
        return value.getAsJsonArray();
    }

    private JsonElement readJson(Path file, List<DatabaseManager.LegacyRecord> preserved) {
        if (!Files.isRegularFile(file)) return null;
        try {
            if (Files.size(file) > MAX_JSON_BYTES) throw new IOException("JSON-Datei überschreitet das Größenlimit");
            JsonElement value = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (value == null || value.isJsonNull()) throw new IOException("JSON-Wurzel ist null");
            return value;
        } catch (Exception exception) {
            preserveFile(file, preserved, exception.getMessage());
            return null;
        }
    }

    private void preserveFile(Path file, List<DatabaseManager.LegacyRecord> preserved, String reason) {
        try {
            String raw = Files.size(file) <= MAX_JSON_BYTES ? Files.readString(file, StandardCharsets.UTF_8) : "<file-too-large>";
            preserve(preserved, "FILE", file.getFileName().toString(), raw, reason);
        } catch (IOException exception) {
            preserve(preserved, "FILE", file.getFileName().toString(), "", reason);
        }
    }

    private SourceManifest sourceManifest(List<Path> files) {
        List<SourceFile> sources = new ArrayList<>();
        for (Path file : files) {
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_JSON_BYTES) throw new IOException("Ungültige Legacy-Quelldatei");
                sources.add(new SourceFile(file.toAbsolutePath().normalize(), Files.size(file), sha256(file)));
            } catch (Exception exception) {
                throw new IllegalStateException("Legacy-Quelldatei konnte nicht gesichert werden: " + file, exception);
            }
        }
        return new SourceManifest(List.copyOf(sources));
    }

    private ArchiveResult archiveLegacyFiles(SourceManifest manifest) {
        if (manifest.files().isEmpty()) return new ArchiveResult(true, "0 Dateien");
        Path target = FileManager.getLegacyArchiveDirectory().resolve("migration-" + System.currentTimeMillis() + '-' + UUID.randomUUID());
        int archived = 0;
        try {
            fileOps.createDirectories(target);
            for (SourceFile source : manifest.files()) {
                if (!Files.isRegularFile(source.path()) || Files.size(source.path()) != source.size() || !source.sha256().equals(sha256(source.path()))) throw new IOException("Legacy-Quelle wurde seit Preflight verändert: " + source.path());
                Path destination = target.resolve(source.path().getFileName()).normalize();
                if (!destination.getParent().equals(target.toAbsolutePath().normalize())) throw new IOException("Unsicherer Archivpfad");
                fileOps.moveWithoutReplacing(source.path(), destination);
                if (!Files.isRegularFile(destination) || !source.sha256().equals(sha256(destination))) throw new IOException("Archiv-Checksumme stimmt nicht");
                archived++;
            }
            return new ArchiveResult(true, archived + " Dateien nach " + target.getFileName());
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.error("Legacy source archival stopped after {} files", archived, exception);
            return new ArchiveResult(false, archived + "/" + manifest.files().size() + " Dateien archiviert");
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[16_384];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<Path> legacyFiles() {
        List<Path> files = new ArrayList<>();
        for (Path file : List.of(FileManager.getCreditStateFile(), FileManager.getCreditsFile(), FileManager.getPaymentsFile(), FileManager.getPlayersFile(), FileManager.getCreditEventsFile(), FileManager.getTransactionsFile())) if (Files.isRegularFile(file)) files.add(file);
        files.addAll(paymentBackupFiles());
        return files.stream().map(path -> path.toAbsolutePath().normalize()).distinct().toList();
    }

    private List<Path> paymentBackupFiles() {
        List<Path> files = new ArrayList<>();
        try (var listed = Files.list(FileManager.getDataDirectory())) {
            listed.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().matches("payments_backup_[0-9]+\\.json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(files::add);
        } catch (IOException exception) {
            CreditManagerClient.LOGGER.warn("Could not list legacy JSON backups", exception);
        }
        return files;
    }

    private void preserve(List<DatabaseManager.LegacyRecord> values, String kind, String id, String raw, String reason) {
        values.add(new DatabaseManager.LegacyRecord(kind, id, raw == null ? "null" : raw, reason));
    }

    private UUID uuid(String source, UUID preferred, String kind, String originalId, String raw,
                      List<DatabaseManager.LegacyRecord> preserved) {
        if (preferred != null) return preferred;
        try {
            return UUID.fromString(source);
        } catch (RuntimeException ignored) {
            UUID generated = UUID.nameUUIDFromBytes((kind + ':' + source).getBytes(StandardCharsets.UTF_8));
            preserve(preserved, kind.toUpperCase(Locale.ROOT), originalId, raw, "Ungültige oder fehlende UUID wurde deterministisch ersetzt durch " + generated + '.');
            return generated;
        }
    }

    private UUID parsedUuid(JsonObject object, String name) {
        try {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? null : UUID.fromString(value.getAsString());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private JsonArray array(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private String party(String value, String fallback) {
        if (blank(value)) return fallback;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record AutomaticPayload(List<CreditEntry> credits, List<Payment> payments, List<CreditEventEntry> events,
                                    List<TransactionEntry> paylogs, List<DatabaseManager.LegacyRecord> preserved) {
        private String summary() {
            return credits.size() + " Deals, " + payments.size() + " Zahlungen, " + events.size() + " Events, " + paylogs.size() + " Paylogs, " + preserved.size() + " Rohdatensätze";
        }
    }

    private record SourceFile(Path path, long size, String sha256) { }

    private record SourceManifest(List<SourceFile> files) {
        private String summary() {
            return files.stream().map(file -> file.path().getFileName() + ":" + file.size() + ":" + file.sha256()).reduce((left, right) -> left + ',' + right).orElse("none");
        }
    }

    private record ArchiveResult(boolean success, String details) { }
}

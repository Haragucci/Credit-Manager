package op.creditmanager.client.storage.db;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.PaymentKind;
import op.creditmanager.client.storage.db.DatabaseManager.DataHealthRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DatabaseHealthInspectionService {
    private static final Set<String> MANAGED_TYPES = Set.of(
            "CREDIT_ID", "CREDIT_PARTIES", "CREDIT_AMOUNT", "CREDIT_PAYMENT_TOTAL", "CREDIT_STATUS",
            "CREDIT_COMPLETION", "CREDIT_DUE_DATE", "PAYMENT_ID", "PAYMENT_CREDIT_ORPHAN", "PAYMENT_AMOUNT",
            "PAYMENT_DIRECTION", "PAYMENT_KIND", "PAYMENT_ITEMS_JSON", "PAYMENT_ITEMS", "PAYLOG_LINK_ORPHAN",
            "PAYLOG_LINK_SOURCE", "PAYLOG_LINK_DIRECTION", "PAYLOG_LINK_AMOUNT", "EVENT_ID", "EVENT_CREDIT_ORPHAN",
            "EVENT_TYPE", "EVENT_AMOUNT", "PAYLOG_DATA", "PAYLOG_LINK_AGGREGATE", "PAYLOG_LINK_OVERBOOKED",
            "PAYLOG_DUPLICATE_HASH", "MIGRATION_INCOMPLETE", "SCHEMA_VERSION"
    );
    private final DatabaseCoordinator database;
    private volatile int lastInspectionQueryCount;
    private final ThreadLocal<QueryCounter> inspectionCounter = new ThreadLocal<>();

    DatabaseHealthInspectionService(DatabaseCoordinator database) {
        this.database = database;
    }

    List<DataHealthRecord> runHealthCheck() {
        database.initialize();
        Set<FindingKey> observed = new HashSet<>();
        QueryCounter counter = new QueryCounter();
        inspectionCounter.set(counter);
        try {
            boolean completed = database.inTransaction(DatabaseWriteMode.HEALTH_MAINTENANCE, connection -> {
                PaymentAggregates aggregates = loadPaymentAggregates(connection);
                try (Statement statement = connection.createStatement()) {
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT c.* FROM credits c")) {
                        while (result.next()) inspectCredit(connection, result,
                                aggregates.byCredit().getOrDefault(result.getString("id"), PaymentAggregateValue.ZERO), observed);
                    }
                    database.inject(DatabaseFaultInjector.FailurePoint.HEALTH_AFTER_CREDITS_BEFORE_PAYMENTS);
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT p.*, c.id AS credit_exists, l.id AS paylog_exists, l.amount AS paylog_amount, l.payer AS paylog_payer, l.receiver AS paylog_receiver FROM payments p LEFT JOIN credits c ON c.id=p.credit_id LEFT JOIN paylogs l ON l.id=p.paylog_id")) {
                        while (result.next()) inspectPayment(connection, result, observed);
                    }
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT e.*, c.id AS credit_exists FROM credit_events e LEFT JOIN credits c ON c.id=e.credit_id")) {
                        while (result.next()) inspectEvent(connection, result, observed);
                    }
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT l.* FROM paylogs l")) {
                        while (result.next()) inspectPaylog(connection, result,
                                aggregates.byPaylog().getOrDefault(result.getString("id"), PaymentAggregateValue.ZERO), observed);
                    }
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT entry_hash FROM paylogs GROUP BY entry_hash HAVING COUNT(*) > 1")) {
                        while (result.next()) report(connection, observed, "PAYLOG_DUPLICATE_HASH", "WARNING", "paylogs", result.getString(1), "Doppelter Paylog-Hash", "Mehrere Paylogs besitzen denselben Hash.", null, null);
                    }
                    queryExecuted();
                    try (ResultSet result = statement.executeQuery("SELECT id FROM migration_log WHERE status='STARTED' OR status='FAILED'")) {
                        while (result.next()) report(connection, observed, "MIGRATION_INCOMPLETE", "ERROR", "migration_log", result.getString(1), "Unvollständige Migration", "Eine Migration wurde begonnen, aber nicht abgeschlossen.", null, null);
                    }
                }
                if (database.installedSchemaVersion(connection) != DatabaseManager.SCHEMA_VERSION) {
                    report(connection, observed, "SCHEMA_VERSION", "ERROR", "metadata", "schema_version", "Unerwartete Schemaversion", "Die gespeicherte Schemaversion passt nicht zur Anwendung.", null, null);
                }
                resolveAbsentFindings(connection, observed);
            });
            if (!completed) throw new IllegalStateException("Data-health scan could not be completed");
            List<DataHealthRecord> records = listHealthRecords(false);
            lastInspectionQueryCount = counter.value();
            return records;
        } finally {
            inspectionCounter.remove();
        }
    }

    int lastInspectionQueryCount() {
        return lastInspectionQueryCount;
    }

    List<DataHealthRecord> listHealthRecords(boolean includeResolved) {
        database.initialize();
        String sql = "SELECT * FROM data_health_records" + (includeResolved ? "" : " WHERE status='OPEN'") + " ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, created_at DESC";
        queryExecuted();
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<DataHealthRecord> records = new ArrayList<>();
            while (result.next()) records.add(database.readHealth(result));
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new IllegalStateException("Data-health records could not be loaded", exception);
        }
    }

    boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) {
        if (id == null) return false;
        return database.inTransaction(DatabaseWriteMode.HEALTH_MAINTENANCE, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE data_health_records SET status=?, repair_payload=?, resolved_at=? WHERE id=? AND status='OPEN'")) {
                statement.setString(1, ignored ? "IGNORED" : "RESOLVED");
                statement.setString(2, repairPayload);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, id.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Health finding no longer open");
            }
            database.bumpRevision(connection);
        });
    }

    private void inspectCredit(Connection connection, ResultSet result, PaymentAggregateValue aggregate,
                               Set<FindingKey> observed) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id)) report(connection, observed, "CREDIT_ID", "ERROR", "credits", id, "Ungültige Deal-ID", "Die Deal-ID ist keine UUID.", rowPayload(result), null);
        String creditor = result.getString("creditor");
        String debtor = result.getString("debtor");
        if (blank(creditor) || blank(debtor) || creditor.equalsIgnoreCase(debtor)) report(connection, observed, "CREDIT_PARTIES", "ERROR", "credits", id, "Ungültige Parteien", "Gläubiger und Schuldner müssen vorhanden und verschieden sein.", rowPayload(result), null);
        long amount = result.getLong("amount");
        long paid = result.getLong("paid_amount");
        BigInteger actualPaid = aggregate.total();
        if (!MoneyRules.isPositive(amount) || paid < 0L || paid > amount) report(connection, observed, "CREDIT_AMOUNT", "ERROR", "credits", id, "Ungültiger Betrag", "Gesamtbetrag oder bezahlter Betrag ist inkonsistent.", rowPayload(result), null);
        if (!actualPaid.equals(BigInteger.valueOf(paid))) report(connection, observed, "CREDIT_PAYMENT_TOTAL", "ERROR", "credits", id, "Zahlungssumme inkonsistent", "Der gespeicherte bezahlte Betrag entspricht nicht der Summe der Zahlungen oder liegt außerhalb des Long-Bereichs.", rowPayload(result), null);
        String status = result.getString("status");
        if (!CreditStatusRules.isManualFinal(status) && MoneyRules.isPositive(amount) && paid >= 0L && paid <= amount && !CreditStatusRules.derive(amount, paid).equals(status)) report(connection, observed, "CREDIT_STATUS", "ERROR", "credits", id, "Status inkonsistent", "Der Status entspricht nicht dem exakten Zahlungsstand.", rowPayload(result), null);
        result.getLong("completed_at");
        boolean hasCompleted = !result.wasNull();
        boolean finalStatus = "PAID".equals(status) || CreditStatusRules.isManualFinal(status);
        if (finalStatus != hasCompleted) report(connection, observed, "CREDIT_COMPLETION", "WARNING", "credits", id, "Abschlussdatum inkonsistent", "Status und Abschlussdatum passen nicht zusammen.", rowPayload(result), null);
        long due = result.getLong("due_date");
        if (!result.wasNull() && due <= 0L) report(connection, observed, "CREDIT_DUE_DATE", "WARNING", "credits", id, "Ungültige Fälligkeit", "Das Fälligkeitsdatum ist ungültig.", rowPayload(result), null);
    }

    private void inspectPayment(Connection connection, ResultSet result, Set<FindingKey> observed) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id) || !validUuid(result.getString("credit_id"))) report(connection, observed, "PAYMENT_ID", "ERROR", "payments", id, "Ungültige Zahlungs-ID", "Zahlung oder zugehörige Deal-ID ist ungültig.", rowPayload(result), null);
        if (result.getString("credit_exists") == null) report(connection, observed, "PAYMENT_CREDIT_ORPHAN", "ERROR", "payments", id, "Verwaiste Zahlung", "Die Zahlung verweist auf einen nicht vorhandenen Deal.", rowPayload(result), null);
        long amount = result.getLong("amount");
        if (!MoneyRules.isPositive(amount)) report(connection, observed, "PAYMENT_AMOUNT", "ERROR", "payments", id, "Ungültiger Zahlungsbetrag", "Eine Zahlung muss einen positiven gültigen Betrag haben.", rowPayload(result), null);
        if (blank(result.getString("from_player")) || blank(result.getString("to_player"))) report(connection, observed, "PAYMENT_DIRECTION", "WARNING", "payments", id, "Fehlende Zahlungsrichtung", "Sender oder Empfänger der Zahlung fehlt.", rowPayload(result), null);
        PaymentKind kind = null;
        try {
            kind = PaymentKind.valueOf(result.getString("payment_kind"));
        } catch (RuntimeException ignored) {
            report(connection, observed, "PAYMENT_KIND", "ERROR", "payments", id, "Ungültige Zahlungsart", "Die Zahlungsart ist weder MONEY noch ITEM.", rowPayload(result), null);
        }
        JsonElement items = parseStringArray(result.getString("items_json"));
        JsonElement nbtEntries = parseStringArray(result.getString("item_nbt_entries"));
        if (items == null || nbtEntries == null) report(connection, observed, "PAYMENT_ITEMS_JSON", "ERROR", "payments", id, "Beschädigte Itemdaten", "Die gespeicherten Itemdaten sind kein gültiges String-Array.", rowPayload(result), null);
        if (kind == PaymentKind.ITEM && (items == null || items.getAsJsonArray().isEmpty())) report(connection, observed, "PAYMENT_ITEMS", "ERROR", "payments", id, "Fehlende Itemdaten", "Eine Item-Zahlung enthält keine Itemdaten.", rowPayload(result), null);
        String paylogId = result.getString("paylog_id");
        if (!blank(paylogId)) {
            if (!validUuid(paylogId) || result.getString("paylog_exists") == null) {
                report(connection, observed, "PAYLOG_LINK_ORPHAN", "ERROR", "payments", id, "Verwaister Paylog-Link", "Die Zahlung verweist auf einen fehlenden oder ungültigen Paylog.", rowPayload(result), null);
                return;
            }
            if (!safe(result.getString("source")).startsWith("PAYLOG_")) report(connection, observed, "PAYLOG_LINK_SOURCE", "WARNING", "payments", id, "Unpassende Paylog-Quelle", "Eine Paylog-verknüpfte Zahlung muss eine PAYLOG_-Quelle besitzen.", rowPayload(result), null);
            if (!safe(result.getString("from_player")).equalsIgnoreCase(safe(result.getString("paylog_payer"))) || !safe(result.getString("to_player")).equalsIgnoreCase(safe(result.getString("paylog_receiver")))) report(connection, observed, "PAYLOG_LINK_DIRECTION", "ERROR", "payments", id, "Falsche Paylog-Richtung", "Die Zahlungsrichtung passt nicht zum verknüpften Paylog.", rowPayload(result), null);
            if (amount > result.getLong("paylog_amount")) report(connection, observed, "PAYLOG_LINK_AMOUNT", "ERROR", "payments", id, "Ungültiger Paylog-Betrag", "Eine einzelne Zahlung ist größer als ihr Paylog.", rowPayload(result), null);
        }
    }

    private void inspectEvent(Connection connection, ResultSet result, Set<FindingKey> observed) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id) || !validUuid(result.getString("credit_id"))) report(connection, observed, "EVENT_ID", "ERROR", "credit_events", id, "Ungültige Event-ID", "Event oder zugehörige Deal-ID ist ungültig.", rowPayload(result), null);
        if (result.getString("credit_exists") == null) report(connection, observed, "EVENT_CREDIT_ORPHAN", "ERROR", "credit_events", id, "Verwaistes Event", "Das Event verweist auf einen nicht vorhandenen Deal.", rowPayload(result), null);
        try {
            CreditEventType.valueOf(result.getString("event_type"));
        } catch (RuntimeException error) {
            report(connection, observed, "EVENT_TYPE", "ERROR", "credit_events", id, "Unbekannter Eventtyp", "Das Event kann keiner bekannten Aktion zugeordnet werden.", rowPayload(result), null);
        }
        if (result.getLong("amount") < 0L || result.getLong("paid_after") < 0L || result.getLong("remaining_after") < 0L || result.getLong("amount_before") < 0L || result.getLong("amount_after") < 0L) report(connection, observed, "EVENT_AMOUNT", "ERROR", "credit_events", id, "Ungültiger Eventbetrag", "Ein Event enthält einen negativen Betrag.", rowPayload(result), null);
    }

    private void inspectPaylog(Connection connection, ResultSet result, PaymentAggregateValue aggregate,
                               Set<FindingKey> observed) throws SQLException {
        String id = result.getString("id");
        long amount = result.getLong("amount");
        BigInteger actualLinked = aggregate.total();
        int actualCount = aggregate.count();
        if (!validUuid(id) || !MoneyRules.isPositive(amount) || blank(result.getString("payer")) || blank(result.getString("receiver")) || result.getLong("created_at") <= 0L || blank(result.getString("raw_text"))) report(connection, observed, "PAYLOG_DATA", "WARNING", "paylogs", id, "Unvollständiger Paylog", "Paylog enthält fehlende Parteien, Betrag, Datum oder Originaltext.", rowPayload(result), null);
        if (!actualLinked.equals(BigInteger.valueOf(result.getLong("linked_amount"))) || actualCount != result.getInt("link_count")) report(connection, observed, "PAYLOG_LINK_AGGREGATE", "ERROR", "paylogs", id, "Paylog-Aggregat inkonsistent", "Gespeicherte Linksumme oder Linkanzahl entspricht nicht den Zahlungen.", rowPayload(result), null);
        if (actualLinked.compareTo(BigInteger.valueOf(amount)) > 0) report(connection, observed, "PAYLOG_LINK_OVERBOOKED", "ERROR", "paylogs", id, "Paylog überbucht", "Die Summe verknüpfter Zahlungen ist größer als der Paylog-Betrag.", rowPayload(result), null);
    }

    private PaymentAggregates loadPaymentAggregates(Connection connection) throws SQLException {
        Map<String, PaymentAggregateValue> byCredit = new LinkedHashMap<>();
        Map<String, PaymentAggregateValue> byPaylog = new LinkedHashMap<>();
        queryExecuted();
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id,paylog_id,amount FROM payments");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long amount = result.getLong("amount");
                mergeAggregate(byCredit, result.getString("credit_id"), amount);
                String paylogId = result.getString("paylog_id");
                if (!blank(paylogId)) mergeAggregate(byPaylog, paylogId, amount);
            }
        }
        return new PaymentAggregates(Map.copyOf(byCredit), Map.copyOf(byPaylog));
    }

    private void mergeAggregate(Map<String, PaymentAggregateValue> aggregates, String id, long amount) {
        if (blank(id)) return;
        PaymentAggregateValue current = aggregates.getOrDefault(id, PaymentAggregateValue.ZERO);
        int count = current.count() == Integer.MAX_VALUE ? Integer.MAX_VALUE : current.count() + 1;
        aggregates.put(id, new PaymentAggregateValue(current.total().add(BigInteger.valueOf(amount)), count));
    }

    private JsonElement parseStringArray(String json) {
        if (blank(json)) return null;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonArray()) return null;
            for (JsonElement value : element.getAsJsonArray()) if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
            return element;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void report(Connection connection, Set<FindingKey> observed, String type, String severity, String table, String sourceId, String title, String message, String raw, String repair) throws SQLException {
        observed.add(new FindingKey(type, safe(table), safe(sourceId)));
        storeHealth(connection, type, severity, table, sourceId, title, message, raw, repair);
    }

    private void resolveAbsentFindings(Connection connection, Set<FindingKey> observed) throws SQLException {
        List<UUID> resolved = new ArrayList<>();
        queryExecuted();
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,record_type,COALESCE(source_table,''),COALESCE(source_id,'') FROM data_health_records WHERE status='OPEN'"); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                FindingKey key = new FindingKey(result.getString(2), result.getString(3), result.getString(4));
                if (MANAGED_TYPES.contains(key.type()) && !observed.contains(key)) resolved.add(UUID.fromString(result.getString(1)));
            }
        }
        if (!resolved.isEmpty()) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE data_health_records SET status='RESOLVED',resolved_at=? WHERE id=? AND status='OPEN'")) {
                for (UUID id : resolved) {
                    statement.setLong(1, System.currentTimeMillis());
                    statement.setString(2, id.toString());
                    statement.addBatch();
                }
                queryExecuted();
                statement.executeBatch();
            }
        }
    }

    void storeHealth(Connection connection, String type, String severity, String table, String sourceId, String title, String message, String raw, String repair) throws SQLException {
        queryExecuted();
        try (PreparedStatement check = connection.prepareStatement("SELECT id FROM data_health_records WHERE record_type=? AND COALESCE(source_table,'')=COALESCE(?,'') AND COALESCE(source_id,'')=COALESCE(?,'') AND status='OPEN'")) {
            check.setString(1, type);
            check.setString(2, table);
            check.setString(3, sourceId);
            try (ResultSet result = check.executeQuery()) {
                if (result.next()) return;
            }
        }
        queryExecuted();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO data_health_records (id,record_type,severity,source_table,source_id,title,message,raw_payload,repair_payload,status,created_at,resolved_at) VALUES (?,?,?,?,?,?,?,?,?,'OPEN',?,NULL)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, type);
            statement.setString(3, severity);
            statement.setString(4, table);
            statement.setString(5, sourceId);
            statement.setString(6, title);
            statement.setString(7, message);
            statement.setString(8, raw);
            statement.setString(9, repair);
            statement.setLong(10, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String rowPayload(ResultSet result) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            var metadata = result.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Object value = result.getObject(index);
                payload.put(metadata.getColumnLabel(index), value == null ? null : String.valueOf(value));
            }
            return new com.google.gson.Gson().toJson(payload);
        } catch (SQLException ignored) {
            return null;
        }
    }

    void queryExecuted() {
        QueryCounter counter = inspectionCounter.get();
        if (counter != null) counter.increment();
    }

    private record FindingKey(String type, String table, String sourceId) { }
    private record PaymentAggregateValue(BigInteger total, int count) {
        private static final PaymentAggregateValue ZERO = new PaymentAggregateValue(BigInteger.ZERO, 0);
    }
    private record PaymentAggregates(Map<String, PaymentAggregateValue> byCredit,
                                     Map<String, PaymentAggregateValue> byPaylog) { }
    private static final class QueryCounter {
        private int value;
        private void increment() { value++; }
        private int value() { return value; }
    }
}

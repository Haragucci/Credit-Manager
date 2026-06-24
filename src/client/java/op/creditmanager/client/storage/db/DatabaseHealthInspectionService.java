package op.creditmanager.client.storage.db;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.storage.db.DatabaseManager.DataHealthRecord;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class DatabaseHealthInspectionService {
    private final DatabaseCoordinator database;

    DatabaseHealthInspectionService(DatabaseCoordinator database) { this.database = database; }

    List<DataHealthRecord> runHealthCheck() {
        database.initialize();
        if (database.isWriteLocked()) return List.of();
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT * FROM credits")) {
                    while (result.next()) inspectCredit(connection, result);
                }
                try (ResultSet result = statement.executeQuery("SELECT p.*, l.id AS paylog_exists, l.amount AS paylog_amount, l.payer AS paylog_payer, l.receiver AS paylog_receiver FROM payments p LEFT JOIN credits c ON c.id=p.credit_id LEFT JOIN paylogs l ON l.id=p.paylog_id")) {
                    while (result.next()) inspectPayment(connection, result);
                }
                try (ResultSet result = statement.executeQuery("SELECT e.* FROM credit_events e LEFT JOIN credits c ON c.id=e.credit_id")) {
                    while (result.next()) inspectEvent(connection, result);
                }
                try (ResultSet result = statement.executeQuery("SELECT * FROM paylogs")) {
                    while (result.next()) inspectPaylog(connection, result);
                }
                try (ResultSet result = statement.executeQuery("SELECT entry_hash FROM paylogs GROUP BY entry_hash HAVING COUNT(*) > 1")) {
                    while (result.next()) storeHealth(connection, "PAYLOG_DUPLICATE_HASH", "WARNING", "paylogs", result.getString(1), "Doppelter Paylog-Hash", "Mehrere Paylogs besitzen denselben Hash.", null, null);
                }
                try (ResultSet result = statement.executeQuery("SELECT id FROM migration_log WHERE status='STARTED' OR (completed_at IS NULL AND status <> 'COMPLETED')")) {
                    while (result.next()) storeHealth(connection, "MIGRATION_INCOMPLETE", "ERROR", "migration_log", result.getString(1), "Unvollständige Migration", "Eine Migration wurde begonnen, aber nicht abgeschlossen.", null, null);
                }
            }
            if (database.installedSchemaVersion(connection) != DatabaseManager.SCHEMA_VERSION) storeHealth(connection, "SCHEMA_VERSION", "ERROR", "metadata", "schema_version", "Unerwartete Schemaversion", "Die gespeicherte Schemaversion passt nicht zur Anwendung.", null, null);
        });
        return listHealthRecords(false);
    }

    List<DataHealthRecord> listHealthRecords(boolean includeResolved) {
        database.initialize();
        String sql = "SELECT * FROM data_health_records" + (includeResolved ? "" : " WHERE status='OPEN'") + " ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, created_at DESC";
        try (Connection connection = database.connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<DataHealthRecord> records = new ArrayList<>();
            while (result.next()) records.add(database.readHealth(result));
            return List.copyOf(records);
        } catch (SQLException exception) {
            CreditManagerClient.LOGGER.warn("Could not load data-health records", exception);
            return List.of();
        }
    }

    boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) {
        if (id == null) return false;
        return database.inTransaction(connection -> {
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

    private void inspectCredit(Connection connection, ResultSet result) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id)) storeHealth(connection, "CREDIT_ID", "ERROR", "credits", id, "Ungültige Deal-ID", "Die Deal-ID ist keine UUID.", rowPayload(result), null);
        String creditor = result.getString("creditor"), debtor = result.getString("debtor");
        if (blank(creditor) || blank(debtor) || creditor.equalsIgnoreCase(debtor)) storeHealth(connection, "CREDIT_PARTIES", "ERROR", "credits", id, "Ungültige Parteien", "Gläubiger und Schuldner müssen vorhanden und verschieden sein.", rowPayload(result), null);
        double amount = result.getDouble("amount"), paid = result.getDouble("paid_amount");
        if (!Double.isFinite(amount) || amount <= 0 || !Double.isFinite(paid) || paid < 0 || paid > amount + 0.0001D) storeHealth(connection, "CREDIT_AMOUNT", "ERROR", "credits", id, "Ungültiger Betrag", "Gesamtbetrag oder bezahlter Betrag ist inkonsistent.", rowPayload(result), null);
        String status = result.getString("status"); long completed = result.getLong("completed_at"); boolean hasCompleted = !result.wasNull();
        boolean finalStatus = "PAID".equals(status) || "CLOSED".equals(status) || "CANCELLED".equals(status);
        if (finalStatus != hasCompleted || (!finalStatus && hasCompleted)) storeHealth(connection, "CREDIT_COMPLETION", "WARNING", "credits", id, "Abschlussdatum inkonsistent", "Status und Abschlussdatum passen nicht zusammen.", rowPayload(result), null);
        long due = result.getLong("due_date"); if (!result.wasNull() && due <= 0) storeHealth(connection, "CREDIT_DUE_DATE", "WARNING", "credits", id, "Ungültige Fälligkeit", "Das Fälligkeitsdatum ist ungültig.", rowPayload(result), null);
    }

    private void inspectPayment(Connection connection, ResultSet result) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id) || !validUuid(result.getString("credit_id"))) storeHealth(connection, "PAYMENT_ID", "ERROR", "payments", id, "Ungültige Zahlungs-ID", "Zahlung oder zugehörige Deal-ID ist ungültig.", rowPayload(result), null);
        double amount = result.getDouble("amount");
        if (!Double.isFinite(amount) || amount <= 0) storeHealth(connection, "PAYMENT_AMOUNT", "ERROR", "payments", id, "Ungültiger Zahlungsbetrag", "Eine Zahlung muss einen positiven endlichen Betrag haben.", rowPayload(result), null);
        if (blank(result.getString("from_player")) || blank(result.getString("to_player"))) storeHealth(connection, "PAYMENT_DIRECTION", "WARNING", "payments", id, "Fehlende Zahlungsrichtung", "Sender oder Empfänger der Zahlung fehlt.", rowPayload(result), null);
        String items = result.getString("items_json");
        if ("ITEM".equalsIgnoreCase(result.getString("source")) && blank(items)) storeHealth(connection, "PAYMENT_ITEMS", "ERROR", "payments", id, "Fehlende Itemdaten", "Eine Item-Zahlung enthält keine erhaltenen Itemdaten.", rowPayload(result), null);
        String paylogId = result.getString("paylog_id");
        if (!blank(paylogId)) {
            if (!validUuid(paylogId) || result.getString("paylog_exists") == null) {
                storeHealth(connection, "PAYLOG_LINK_ORPHAN", "ERROR", "payments", id, "Verwaister Paylog-Link", "Die Zahlung verweist auf einen fehlenden oder ungültigen Paylog.", rowPayload(result), null);
                return;
            }
            if (!safe(result.getString("source")).startsWith("PAYLOG_")) {
                storeHealth(connection, "PAYLOG_LINK_SOURCE", "WARNING", "payments", id, "Unpassende Paylog-Quelle", "Eine Paylog-verknüpfte Zahlung muss eine PAYLOG_-Quelle besitzen.", rowPayload(result), null);
            }
            if (!safe(result.getString("from_player")).equalsIgnoreCase(safe(result.getString("paylog_payer")))
                    || !safe(result.getString("to_player")).equalsIgnoreCase(safe(result.getString("paylog_receiver")))) {
                storeHealth(connection, "PAYLOG_LINK_DIRECTION", "ERROR", "payments", id, "Falsche Paylog-Richtung", "Die Zahlungsrichtung passt nicht zum verknüpften Paylog.", rowPayload(result), null);
            }
            if (amount > result.getDouble("paylog_amount") + 0.0001D) {
                storeHealth(connection, "PAYLOG_LINK_AMOUNT", "ERROR", "payments", id, "Ungültiger Paylog-Betrag", "Eine einzelne Zahlung ist gröÃƒÆ’Ã…Â¸er als ihr Paylog.", rowPayload(result), null);
            }
        }
    }

    private void inspectEvent(Connection connection, ResultSet result) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id) || !validUuid(result.getString("credit_id"))) storeHealth(connection, "EVENT_ID", "ERROR", "credit_events", id, "Ungültige Event-ID", "Event oder zugehörige Deal-ID ist ungültig.", rowPayload(result), null);
        try { CreditEventType.valueOf(result.getString("event_type")); }
        catch (RuntimeException error) { storeHealth(connection, "EVENT_TYPE", "ERROR", "credit_events", id, "Unbekannter Eventtyp", "Das Event kann keiner bekannten Aktion zugeordnet werden.", rowPayload(result), null); }
    }

    private void inspectPaylog(Connection connection, ResultSet result) throws SQLException {
        String id = result.getString("id");
        if (!validUuid(id) || !Double.isFinite(result.getDouble("amount")) || result.getDouble("amount") <= 0 || blank(result.getString("payer")) || blank(result.getString("receiver")) || result.getLong("created_at") <= 0 || blank(result.getString("raw_text"))) {
            storeHealth(connection, "PAYLOG_DATA", "WARNING", "paylogs", id, "Unvollständiger Paylog", "Paylog enthält fehlende Parteien, Betrag, Datum oder Originaltext.", rowPayload(result), null);
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE paylog_id=?")) {
            statement.setString(1, id);
            try (ResultSet linked = statement.executeQuery()) {
                linked.next();
                if (linked.getDouble(1) > result.getDouble("amount") + 0.0001D) {
                    storeHealth(connection, "PAYLOG_LINK_OVERBOOKED", "ERROR", "paylogs", id, "Paylog überbucht", "Die Summe verknüpfter Zahlungen ist gröÃƒÆ’Ã…Â¸er als der Paylog-Betrag.", rowPayload(result), null);
                }
            }
        }
    }

    void storeHealth(Connection connection, String type, String severity, String table, String sourceId, String title, String message, String raw, String repair) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement("SELECT id FROM data_health_records WHERE record_type=? AND COALESCE(source_table,'')=COALESCE(?, '') AND COALESCE(source_id,'')=COALESCE(?, '') AND status='OPEN'")) {
            check.setString(1, type); check.setString(2, table); check.setString(3, sourceId);
            try (ResultSet result = check.executeQuery()) { if (result.next()) return; }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO data_health_records (id, record_type, severity, source_table, source_id, title, message, raw_payload, repair_payload, status, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, NULL)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, type); statement.setString(3, severity); statement.setString(4, table); statement.setString(5, sourceId); statement.setString(6, title); statement.setString(7, message); statement.setString(8, raw); statement.setString(9, repair); statement.setLong(10, System.currentTimeMillis()); statement.executeUpdate();
        }
    }


    private boolean validUuid(String value) { try { UUID.fromString(value); return true; } catch (RuntimeException ignored) { return false; } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
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
}

package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.storage.db.DatabaseManager.DiscardRecordType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class DatabaseLogicalRepairService {
    private static final Gson GSON = new Gson();
    private final DatabaseCoordinator database;

    DatabaseLogicalRepairService(DatabaseCoordinator database) {
        this.database = database;
    }

    boolean repairCredit(CreditEntry replacement, String reason) {
        if (replacement == null || replacement.getId() == null || !MoneyRules.isPositive(replacement.getAmountMinor())) return false;
        boolean committed = database.inTransaction(DatabaseWriteMode.REPAIR, connection -> {
            preserveAudit(connection, "CREDIT", replacement.getId(), existingRow(connection, "credits", replacement.getId()), reason);
            long paid = paymentTotal(connection, replacement.getId(), null);
            if (paid > replacement.getAmountMinor()) throw new SQLException("Repaired credit amount is below persisted payments");
            replacement.setPaidAmountMinor(paid);
            if (!CreditStatusRules.isManualFinal(replacement.getStatus())) replacement.setStatus(CreditStatusRules.derive(replacement.getAmountMinor(), paid));
            database.upsertCredit(connection, replacement, database.nextRevision(connection));
            database.validateDerivedCreditState(replacement, paid);
            database.bumpRevision(connection);
            database.inject(DatabaseFaultInjector.FailurePoint.BEFORE_REPAIR_COMMIT);
        });
        if (committed) database.runHealthCheck();
        return committed;
    }

    boolean repairPayment(Payment replacement, String reason) {
        if (replacement == null || replacement.getId() == null || replacement.getCreditId() == null || !MoneyRules.isPositive(replacement.getAmountMinor())) return false;
        boolean committed = database.inTransaction(DatabaseWriteMode.REPAIR, connection -> {
            CreditEntry credit = loadCredit(connection, replacement.getCreditId());
            if (credit == null) throw new SQLException("Repair target credit does not exist");
            database.validatePaylogPaymentLink(connection, replacement);
            preserveAudit(connection, "PAYMENT", replacement.getId(), existingRow(connection, "payments", replacement.getId()), reason);
            UUID previousPaylog = database.paymentPaylogId(connection, replacement.getId());
            UUID previousCredit = paymentCreditId(connection, replacement.getId());
            database.upsertPayment(connection, replacement, database.nextRevision(connection));
            recomputeCredit(connection, replacement.getCreditId());
            if (previousCredit != null && !previousCredit.equals(replacement.getCreditId())) recomputeCredit(connection, previousCredit);
            Set<UUID> affected = new HashSet<>();
            if (previousPaylog != null) affected.add(previousPaylog);
            if (replacement.getPaylogId() != null) affected.add(replacement.getPaylogId());
            database.refreshPaylogLinkAmounts(connection, affected);
            database.validatePersistedPaylogLinks(connection, database.loadPaymentsForCredit(connection, replacement.getCreditId()));
            if (previousCredit != null && !previousCredit.equals(replacement.getCreditId())) {
                database.validatePersistedPaylogLinks(connection, database.loadPaymentsForCredit(connection, previousCredit));
            }
            database.bumpRevision(connection);
            database.inject(DatabaseFaultInjector.FailurePoint.BEFORE_REPAIR_COMMIT);
        });
        if (committed) database.runHealthCheck();
        return committed;
    }

    private void recomputeCredit(Connection connection, UUID creditId) throws SQLException {
        CreditEntry credit = loadCredit(connection, creditId);
        if (credit == null) throw new SQLException("Payment refers to a missing credit during repair");
        long paid = paymentTotal(connection, creditId, null);
        if (paid > credit.getAmountMinor()) throw new SQLException("Repaired payment overpays credit " + creditId);
        credit.setPaidAmountMinor(paid);
        if (!CreditStatusRules.isManualFinal(credit.getStatus())) credit.setStatus(CreditStatusRules.derive(credit.getAmountMinor(), paid));
        database.upsertCredit(connection, credit, database.nextRevision(connection));
        database.validateDerivedCreditState(credit, paid);
    }

    private UUID paymentCreditId(Connection connection, UUID paymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id FROM payments WHERE id=?")) {
            statement.setString(1, paymentId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? UUID.fromString(result.getString(1)) : null;
            }
        }
    }

    boolean repairEvent(CreditEventEntry replacement, String reason) {
        if (replacement == null || replacement.getId() == null || replacement.getCreditId() == null || replacement.getType() == null) return false;
        boolean committed = database.inTransaction(DatabaseWriteMode.REPAIR, connection -> {
            if (loadCredit(connection, replacement.getCreditId()) == null) throw new SQLException("Repair target credit does not exist");
            preserveAudit(connection, "EVENT", replacement.getId(), existingRow(connection, "credit_events", replacement.getId()), reason);
            database.upsertEvent(connection, replacement, database.nextRevision(connection));
            database.bumpRevision(connection);
            database.inject(DatabaseFaultInjector.FailurePoint.BEFORE_REPAIR_COMMIT);
        });
        if (committed) database.runHealthCheck();
        return committed;
    }

    boolean discard(DiscardRecordType type, UUID id, String reason, Path safetySnapshot) {
        if (type == null || id == null || safetySnapshot == null || !Files.isRegularFile(safetySnapshot)) return false;
        String auditReason = (reason == null || reason.isBlank() ? "Explicit recovery discard" : reason) + " | Safety snapshot: " + safetySnapshot.toAbsolutePath().normalize();
        String migrationId = UUID.randomUUID().toString();
        boolean committed = database.inTransaction(DatabaseWriteMode.REPAIR, connection -> {
            switch (type) {
                case CREDIT -> discardCredit(connection, id, auditReason, migrationId);
                case PAYMENT -> discardPayment(connection, id, auditReason, migrationId);
                case EVENT -> discardEvent(connection, id, auditReason, migrationId);
            }
            database.bumpRevision(connection);
            database.inject(DatabaseFaultInjector.FailurePoint.BEFORE_REPAIR_COMMIT);
        });
        if (committed) database.runHealthCheck();
        return committed;
    }

    private void discardCredit(Connection connection, UUID id, String reason, String migrationId) throws SQLException {
        String raw = existingRow(connection, "credits", id);
        if (raw == null) throw new SQLException("Discard target credit does not exist");
        preserveAudit(connection, "DISCARD_CREDIT", id.toString(), raw, reason, migrationId);
        List<String> affectedPaylogs = relatedValues(connection, "SELECT paylog_id FROM payments WHERE credit_id=? AND paylog_id IS NOT NULL", id);
        preserveRelatedRows(connection, "DISCARD_CREDIT_PAYMENT", "payments", id, reason, migrationId);
        preserveRelatedRows(connection, "DISCARD_CREDIT_EVENT", "credit_events", id, reason, migrationId);
        deleteOne(connection, "credits", id);
        if (rowCount(connection, "payments", "credit_id", id.toString()) != 0L || rowCount(connection, "credit_events", "credit_id", id.toString()) != 0L) {
            throw new SQLException("Credit discard left related domain rows behind");
        }
        for (String paylogId : affectedPaylogs) refreshAndValidatePaylog(connection, paylogId);
    }

    private void discardPayment(Connection connection, UUID id, String reason, String migrationId) throws SQLException {
        String raw = existingRow(connection, "payments", id);
        if (raw == null) throw new SQLException("Discard target payment does not exist");
        String creditId = columnValue(connection, "payments", "credit_id", id);
        String paylogId = columnValue(connection, "payments", "paylog_id", id);
        preserveAudit(connection, "DISCARD_PAYMENT", id.toString(), raw, reason, migrationId);
        deleteOne(connection, "payments", id);
        UUID parsedCreditId = validUuid(creditId) ? UUID.fromString(creditId) : null;
        if (parsedCreditId != null && loadCredit(connection, parsedCreditId) != null) {
            recomputeCredit(connection, parsedCreditId);
            database.validatePersistedPaylogLinks(connection, database.loadPaymentsForCredit(connection, parsedCreditId));
        }
        if (paylogId != null) refreshAndValidatePaylog(connection, paylogId);
    }

    private void discardEvent(Connection connection, UUID id, String reason, String migrationId) throws SQLException {
        String raw = existingRow(connection, "credit_events", id);
        if (raw == null) throw new SQLException("Discard target event does not exist");
        preserveAudit(connection, "DISCARD_EVENT", id.toString(), raw, reason, migrationId);
        deleteOne(connection, "credit_events", id);
        database.rebuildEventCounts(connection);
    }

    private void preserveRelatedRows(Connection connection, String kind, String table, UUID creditId, String reason, String migrationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE credit_id=?")) {
            statement.setString(1, creditId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) preserveAudit(connection, kind, result.getString("id"), database.rowPayload(result), reason, migrationId);
            }
        }
    }

    private List<String> relatedValues(Connection connection, String sql, UUID creditId) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, creditId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) values.add(result.getString(1));
            }
        }
        return values;
    }

    private String columnValue(Connection connection, String table, String column, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM " + table + " WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Discard target no longer exists");
                return result.getString(1);
            }
        }
    }

    private void deleteOne(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id=?")) {
            statement.setString(1, id.toString());
            if (statement.executeUpdate() != 1) throw new SQLException("Discard target no longer exists");
        }
    }

    private long rowCount(Connection connection, String table, String column, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void refreshAndValidatePaylog(Connection connection, String paylogId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE paylogs SET linked_amount=COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0), link_count=COALESCE((SELECT COUNT(*) FROM payments WHERE paylog_id=paylogs.id),0) WHERE id=?")) {
            statement.setString(1, paylogId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT amount,linked_amount,link_count,(SELECT COALESCE(SUM(amount),0) FROM payments WHERE paylog_id=paylogs.id),(SELECT COUNT(*) FROM payments WHERE paylog_id=paylogs.id) FROM paylogs WHERE id=?")) {
            statement.setString(1, paylogId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && (result.getLong(2) != result.getLong(4) || result.getLong(3) != result.getLong(5) || result.getLong(2) > result.getLong(1))) {
                    throw new SQLException("Paylog aggregate is invalid after discard");
                }
            }
        }
    }

    private boolean validUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private CreditEntry loadCredit(Connection connection, UUID id) throws SQLException {
        List<CreditEntry> values = database.readCredits(connection, "SELECT * FROM credits WHERE id=?", List.of(id.toString()));
        return values.isEmpty() ? null : values.getFirst();
    }

    private long paymentTotal(Connection connection, UUID creditId, UUID excludedPayment) throws SQLException {
        String sql = "SELECT COALESCE(SUM(amount),0) FROM payments WHERE credit_id=?" + (excludedPayment == null ? "" : " AND id<>?");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, creditId.toString());
            if (excludedPayment != null) statement.setString(2, excludedPayment.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String existingRow(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? database.rowPayload(result) : null;
            }
        }
    }

    private void preserveAudit(Connection connection, String kind, UUID id, String raw, String reason) throws SQLException {
        preserveAudit(connection, "REPAIR_" + kind, id.toString(), raw, reason, UUID.randomUUID().toString());
    }

    private void preserveAudit(Connection connection, String kind, String id, String raw, String reason, String migrationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO legacy_records (id,record_kind,original_id,raw_payload,reason,created_at,migration_id) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, kind);
            statement.setString(3, id);
            statement.setString(4, raw == null ? GSON.toJson(java.util.Map.of("missing", true)) : raw);
            statement.setString(5, reason == null ? "Logical repair" : reason);
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, migrationId);
            statement.executeUpdate();
        }
    }
}

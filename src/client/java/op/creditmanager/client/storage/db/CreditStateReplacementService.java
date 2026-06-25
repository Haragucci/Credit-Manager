package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

final class CreditStateReplacementService {
    private static final int ID_BATCH_SIZE = 500;
    private final DatabaseCoordinator database;

    CreditStateReplacementService(DatabaseCoordinator database) {
        this.database = database;
    }

    boolean replace(Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) {
        if (credits == null || payments == null || events == null) return false;
        return database.inTransaction(connection -> {
            if (database.hasDomainData(connection) && credits.isEmpty()) {
                throw new SQLException("Refusing to replace non-empty CreditManager data with an empty state");
            }
            if (database.hasOpenHealthErrors(connection)) {
                throw new SQLException("Refusing full-state replacement while unresolved data-health errors exist");
            }
            database.validateState(connection, credits, payments, events);
            Set<UUID> affectedPaylogs = linkedPaylogIds(connection);
            for (Payment payment : payments) {
                if (payment.getPaylogId() != null) affectedPaylogs.add(payment.getPaylogId());
            }
            deleteStaleRows(connection, "credit_events", ids(events, CreditEventEntry::getId));
            deleteStaleRows(connection, "payments", ids(payments, Payment::getId));
            deleteStaleRows(connection, "credits", ids(credits, CreditEntry::getId));
            long rowRevision = database.nextRevision(connection);
            for (CreditEntry credit : credits) database.upsertCredit(connection, credit, rowRevision);
            for (Payment payment : payments) database.upsertPayment(connection, payment, rowRevision);
            for (CreditEventEntry event : events) database.upsertEvent(connection, event, rowRevision);
            database.refreshPaylogLinkAmounts(connection, affectedPaylogs);
            database.bumpRevision(connection);
        });
    }

    private Set<UUID> linkedPaylogIds(Connection connection) throws SQLException {
        Set<UUID> paylogIds = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT paylog_id FROM payments WHERE paylog_id IS NOT NULL"); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String value = result.getString(1);
                try {
                    paylogIds.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return paylogIds;
    }

    private void deleteStaleRows(Connection connection, String table, Set<String> replacementIds) throws SQLException {
        List<String> staleIds = rowIds(connection, table);
        staleIds.removeAll(replacementIds);
        for (int start = 0; start < staleIds.size(); start += ID_BATCH_SIZE) {
            List<String> chunk = staleIds.subList(start, Math.min(staleIds.size(), start + ID_BATCH_SIZE));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id IN (" + placeholders + ")")) {
                for (int index = 0; index < chunk.size(); index++) statement.setString(index + 1, chunk.get(index));
                statement.executeUpdate();
            }
        }
    }

    private List<String> rowIds(Connection connection, String table) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT id FROM " + table)) {
            while (result.next()) ids.add(result.getString(1));
        }
        return ids;
    }

    private <T> Set<String> ids(Collection<T> values, Function<T, UUID> idExtractor) {
        Set<String> ids = new HashSet<>();
        for (T value : values) ids.add(idExtractor.apply(value).toString());
        return ids;
    }
}

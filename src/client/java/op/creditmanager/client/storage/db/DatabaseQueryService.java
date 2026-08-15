package op.creditmanager.client.storage.db;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DatabaseQueryService {
    private final DatabaseCoordinator database;

    DatabaseQueryService(DatabaseCoordinator database) { this.database = database; }

    List<TransactionEntry> queryPaylogs(String player, int direction, String query, int limit, int offset) {
        return queryPaylogPage(player, direction, query, limit, offset).entries();
    }

    List<TransactionEntry> findPaylogCandidates(long minTimestamp, long maxTimestamp) {
        if (minTimestamp < 0L || maxTimestamp < minTimestamp) return List.of();
        return database.executeQueryWithSchemaRetry("Paylog-Kandidaten konnten nicht aus der Datenbank geladen werden.", () -> {
            try (Connection connection = database.connection()) {
                database.beginConsistentRead(connection);
                String sql = "SELECT id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, metadata, linked_amount, (SELECT COUNT(paylog_id) FROM payments WHERE 1=0) AS schema_guard FROM paylogs WHERE created_at>=? AND created_at<=? ORDER BY created_at ASC, id ASC";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, minTimestamp);
                    statement.setLong(2, maxTimestamp);
                    try (ResultSet result = statement.executeQuery()) {
                        List<TransactionEntry> entries = new ArrayList<>();
                        while (result.next()) entries.add(database.readPaylog(result));
                        return List.copyOf(entries);
                    }
                }
            }
        });
    }

    DatabaseManager.QueryPage<TransactionEntry> queryPaylogPage(String player, int direction, String query, int limit, int offset) {
        int pageSize = Math.max(1, Math.min(DatabaseManager.PAGE_SIZE, limit));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<String> values = new ArrayList<>();
        if (player != null && !player.isBlank()) {
            String lower = player.toLowerCase(Locale.ROOT);
            where.append(" AND (LOWER(payer)=? OR LOWER(receiver)=?)"); values.add(lower); values.add(lower);
            if (direction == 1) { where.append(" AND LOWER(receiver)=?"); values.add(lower); }
            if (direction == 2) { where.append(" AND LOWER(payer)=?"); values.add(lower); }
        }
        for (String token : DealSearchText.tokens(query)) appendPaylogSearchToken(where, values, token);
        return database.executeQueryWithSchemaRetry("Paylogs konnten nicht aus der Datenbank geladen werden.", () -> {
            try (Connection connection = database.connection()) {
                database.beginConsistentRead(connection);
                long count = database.count(connection, "SELECT COUNT(*) FROM paylogs" + where, values);
                String sql = "SELECT id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, metadata, linked_amount, (SELECT COUNT(paylog_id) FROM payments WHERE 1=0) AS schema_guard FROM paylogs" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    database.bindStrings(statement, values);
                    statement.setInt(values.size() + 1, pageSize);
                    statement.setInt(values.size() + 2, Math.max(0, offset));
                    try (ResultSet result = statement.executeQuery()) {
                        List<TransactionEntry> entries = new ArrayList<>();
                        while (result.next()) entries.add(database.readPaylog(result));
                        return new DatabaseManager.QueryPage<>(List.copyOf(entries), count, Math.max(0, offset), pageSize);
                    }
                }
            }
        });
    }

    DatabaseManager.QueryPage<TransactionEntry> queryAvailablePaylogs(String payer, String receiver, String query, int limit, int offset) {
        int pageSize = Math.max(1, Math.min(DatabaseManager.PAGE_SIZE, limit));
        StringBuilder where = new StringBuilder(" WHERE LOWER(payer)=? AND LOWER(receiver)=? AND linked_amount<amount");
        List<String> values = new ArrayList<>();
        values.add(database.safe(payer).toLowerCase(Locale.ROOT));
        values.add(database.safe(receiver).toLowerCase(Locale.ROOT));
        for (String token : DealSearchText.tokens(query)) appendPaylogSearchToken(where, values, token);
        return database.executeQueryWithSchemaRetry("Verfügbare Paylogs konnten nicht aus der Datenbank geladen werden.", () -> {
            try (Connection connection = database.connection()) {
                database.beginConsistentRead(connection);
                long count = database.count(connection, "SELECT COUNT(*) FROM paylogs" + where, values);
                String sql = "SELECT id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, metadata, linked_amount, (SELECT COUNT(paylog_id) FROM payments WHERE 1=0) AS schema_guard FROM paylogs" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    database.bindStrings(statement, values);
                    statement.setInt(values.size() + 1, pageSize);
                    statement.setInt(values.size() + 2, Math.max(0, offset));
                    try (ResultSet result = statement.executeQuery()) {
                        List<TransactionEntry> entries = new ArrayList<>();
                        while (result.next()) entries.add(database.readPaylog(result));
                        return new DatabaseManager.QueryPage<>(List.copyOf(entries), count, Math.max(0, offset), pageSize);
                    }
                }
            }
        });
    }

    DatabaseManager.QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, int limit, int offset) {
        return queryDealHistoryPage(player, query, false, DatabaseManager.DealHistorySort.NEWEST, limit, offset);
    }

    DatabaseManager.QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, boolean includeArchived, DatabaseManager.DealHistorySort sort, int limit, int offset) {
        int pageSize = Math.max(1, Math.min(DatabaseManager.PAGE_SIZE, limit));
        String lowerPlayer = player == null ? "" : player.toLowerCase(Locale.ROOT);
        StringBuilder where = new StringBuilder(" WHERE (status IN ('PAID','CLOSED','CANCELLED') OR archived=TRUE) AND (LOWER(debtor)=? OR LOWER(creditor)=?)");
        List<String> values = new ArrayList<>(); values.add(lowerPlayer); values.add(lowerPlayer);
        if (!includeArchived) where.append(" AND archived=FALSE");
        for (String token : DealSearchText.tokens(query)) {
            where.append(" AND EXISTS (SELECT 1 FROM deal_search_tokens tokens WHERE tokens.credit_id=credits.id AND tokens.token LIKE ? ESCAPE '!')");
            values.add(database.escapeLike(token) + '%');
        }
        String order = historyOrder(sort == null ? DatabaseManager.DealHistorySort.NEWEST : sort);
        return database.executeQueryWithSchemaRetry("Deal-History konnte nicht aus der Datenbank geladen werden.", () -> {
            try (Connection connection = database.connection()) {
                database.beginConsistentRead(connection);
                long count = database.count(connection, "SELECT COUNT(*) FROM credits" + where, values);
                List<CreditEntry> entries = database.readCredits(connection, "SELECT * FROM credits" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", values, pageSize, Math.max(0, offset));
                return new DatabaseManager.QueryPage<>(List.copyOf(entries), count, Math.max(0, offset), pageSize);
            }
        });
    }

    List<CreditEntry> queryDealHistory(String player, String query, int limit, int offset) { return queryDealHistoryPage(player, query, limit, offset).entries(); }

    private void appendPaylogSearchToken(StringBuilder where, List<String> values, String token) {
        String linked = "paylogs.linked_amount";
        switch (token) {
            case "verknupft" -> where.append(" AND ").append(linked).append("=amount");
            case "teilweise" -> where.append(" AND ").append(linked).append(">0 AND ").append(linked).append("<amount");
            case "offen", "rest" -> where.append(" AND ").append(linked).append("<amount");
            case "manual", "manuell" -> where.append(" AND UPPER(COALESCE(source,''))='MANUAL'");
            case "detected", "erkannt" -> where.append(" AND UPPER(COALESCE(source,''))='DETECTED'");
            default -> { where.append(" AND (EXISTS (SELECT 1 FROM paylog_search_tokens tokens WHERE tokens.paylog_id=paylogs.id AND tokens.token LIKE ? ESCAPE '!') OR LOWER(COALESCE(source,'')) LIKE ? ESCAPE '!')"); values.add(database.escapeLike(token) + '%'); values.add(database.escapeLike(token) + '%'); }
        }
    }

    private String historyOrder(DatabaseManager.DealHistorySort sort) {
        String secondary = switch (sort) {
            case OLDEST -> "COALESCE(completed_at, created_at) ASC";
            case AMOUNT_DESC -> "amount DESC";
            case AMOUNT_ASC -> "amount ASC";
            case PLAYER_ASC -> "LOWER(debtor) ASC, LOWER(creditor) ASC";
            case STATUS -> "status ASC";
            case NEWEST -> "COALESCE(completed_at, created_at) DESC";
        };
        return "CASE WHEN archived=TRUE THEN 1 ELSE 0 END ASC, " + secondary + ", id DESC";
    }
}

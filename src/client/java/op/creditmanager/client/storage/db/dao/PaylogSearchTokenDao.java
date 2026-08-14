package op.creditmanager.client.storage.db.dao;

import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import op.creditmanager.client.search.PaylogSearchText;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.UUID;

public final class PaylogSearchTokenDao {
    public void backfill(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT id, payer, receiver, amount, raw_text, created_at, source, metadata FROM paylogs p WHERE NOT EXISTS (SELECT 1 FROM paylog_search_tokens t WHERE t.paylog_id=p.id)");
             ResultSet result = select.executeQuery()) {
            while (result.next()) replace(connection, read(result));
        }
    }

    public void replace(Connection connection, TransactionEntry entry) throws SQLException {
        if (entry == null || entry.getId() == null) return;
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM paylog_search_tokens WHERE paylog_id=?");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO paylog_search_tokens (paylog_id, token) VALUES (?, ?)")) {
            delete.setString(1, entry.getId().toString());
            delete.executeUpdate();
            for (String token : new HashSet<>(DealSearchText.tokens(PaylogSearchText.build(entry)))) {
                if (token.length() < 2 || token.length() > 96) continue;
                insert.setString(1, entry.getId().toString());
                insert.setString(2, token);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private TransactionEntry read(ResultSet result) throws SQLException {
        TransactionEntry entry = new TransactionEntry();
        entry.setId(UUID.fromString(result.getString("id")));
        entry.setFromPlayer(result.getString("payer"));
        entry.setToPlayer(result.getString("receiver"));
        entry.setAmountMinor(result.getLong("amount"));
        entry.setRawText(result.getString("raw_text"));
        entry.setTimestamp(result.getLong("created_at"));
        entry.setSource(result.getString("source"));
        entry.setMetadata(result.getString("metadata"));
        return entry;
    }
}

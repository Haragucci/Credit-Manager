package op.creditmanager.client.storage.db.dao;

import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.search.DealSearchText;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.UUID;

public final class DealSearchTokenDao {
    public void backfill(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credits c WHERE NOT EXISTS (SELECT 1 FROM deal_search_tokens t WHERE t.credit_id=c.id)");
             ResultSet result = select.executeQuery()) {
            while (result.next()) replace(connection, read(result));
        }
    }

    public void replace(Connection connection, CreditEntry entry) throws SQLException {
        if (entry == null || entry.getId() == null) return;
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM deal_search_tokens WHERE credit_id=?");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO deal_search_tokens (credit_id, token) VALUES (?, ?)")) {
            delete.setString(1, entry.getId().toString());
            delete.executeUpdate();
            for (String token : new HashSet<>(DealSearchText.tokens(DealSearchText.build(entry)))) {
                if (token.length() < 2 || token.length() > 96) continue;
                insert.setString(1, entry.getId().toString());
                insert.setString(2, token);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private CreditEntry read(ResultSet result) throws SQLException {
        CreditEntry entry = new CreditEntry();
        entry.setId(UUID.fromString(result.getString("id")));
        entry.setDealName(result.getString("deal_name"));
        entry.setCreditor(result.getString("creditor"));
        entry.setDebtor(result.getString("debtor"));
        entry.setAmountMinor(result.getLong("amount"));
        entry.setPaidAmountMinor(result.getLong("paid_amount"));
        entry.setCreatedAt(result.getLong("created_at"));
        long due = result.getLong("due_date");
        entry.setDueDate(result.wasNull() ? null : due);
        entry.setStatus(result.getString("status"));
        entry.setNote(result.getString("note"));
        long completed = result.getLong("completed_at");
        entry.setCompletedAt(result.wasNull() ? null : completed);
        entry.setArchived(result.getBoolean("archived"));
        return entry;
    }
}

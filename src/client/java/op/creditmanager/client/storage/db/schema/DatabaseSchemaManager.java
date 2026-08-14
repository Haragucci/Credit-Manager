package op.creditmanager.client.storage.db.schema;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import op.creditmanager.client.money.CreditStatusRules;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import op.creditmanager.client.search.PaylogSearchText;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;
import op.creditmanager.client.storage.db.dao.DealSearchTokenDao;
import op.creditmanager.client.storage.db.dao.PaylogSearchTokenDao;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DatabaseSchemaManager {
    private static final Gson GSON = new Gson();
    private static final List<String> CORE_TABLES = List.of("credits", "payments", "credit_events", "paylogs");
    private static final List<String> REQUIRED_TABLES = List.of("metadata", "schema_migrations", "credits", "payments",
            "credit_events", "credit_event_counts", "paylogs", "paylog_search_tokens", "deal_search_tokens", "data_health_records",
            "migration_log", "legacy_records");
    private static final Map<String, List<ExpectedColumn>> REQUIRED_COLUMNS = expectedColumns();
    private static final List<ExpectedIndex> REQUIRED_INDEXES = List.of(
            new ExpectedIndex("credits", "idx_credits_status", false, List.of("status")),
            new ExpectedIndex("credits", "idx_credits_parties", false, List.of("debtor", "creditor")),
            new ExpectedIndex("credits", "idx_credits_completed", false, List.of("completed_at")),
            new ExpectedIndex("credits", "idx_credits_archived", false, List.of("archived", "status")),
            new ExpectedIndex("payments", "idx_payments_credit", false, List.of("credit_id")),
            new ExpectedIndex("payments", "idx_payments_paylog", false, List.of("paylog_id")),
            new ExpectedIndex("payments", "idx_payments_created", false, List.of("created_at")),
            new ExpectedIndex("credit_events", "idx_events_credit", false, List.of("credit_id")),
            new ExpectedIndex("credit_events", "idx_events_credit_created", false, List.of("credit_id", "created_at", "id")),
            new ExpectedIndex("credit_events", "idx_events_created", false, List.of("created_at")),
            new ExpectedIndex("credit_events", "idx_events_created_id", false, List.of("created_at", "id")),
            new ExpectedIndex("paylogs", "idx_paylogs_hash", true, List.of("entry_hash")),
            new ExpectedIndex("paylogs", "idx_paylogs_created", false, List.of("created_at")),
            new ExpectedIndex("paylogs", "idx_paylogs_parties", false, List.of("payer", "receiver")),
            new ExpectedIndex("paylogs", "idx_paylogs_amount", false, List.of("amount")),
            new ExpectedIndex("paylogs", "idx_paylogs_linked_amount", false, List.of("linked_amount", "amount")),
            new ExpectedIndex("paylog_search_tokens", "idx_paylog_search_token", false, List.of("token", "paylog_id")),
            new ExpectedIndex("deal_search_tokens", "idx_deal_search_token", false, List.of("token", "credit_id")),
            new ExpectedIndex("data_health_records", "idx_health_status", false, List.of("status", "severity")),
            new ExpectedIndex("legacy_records", "idx_legacy_records_migration", false, List.of("migration_id"))
    );
    private final DatabaseMetadataDao metadata;
    private final PaylogSearchTokenDao paylogTokens;
    private final DealSearchTokenDao dealTokens = new DealSearchTokenDao();
    private final HealthReporter healthReporter;

    public DatabaseSchemaManager(DatabaseMetadataDao metadata, PaylogSearchTokenDao paylogTokens,
                                 HealthReporter healthReporter) {
        this.metadata = metadata;
        this.paylogTokens = paylogTokens;
        this.healthReporter = healthReporter;
    }

    public void ensureMetadataTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
        }
    }

    public int installedSchemaVersion(Connection connection) throws SQLException {
        String value = metadata.read(connection, "schema_version");
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new SchemaValidationException("Invalid schema_version metadata");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public void validateMetadataTables(Connection connection) throws SQLException {
        if (!tableExists(connection, "metadata") || !tableExists(connection, "schema_migrations")) {
            throw new SchemaValidationException("Existing database is missing schema metadata tables");
        }
    }

    public boolean hasAnyCoreTable(Connection connection) throws SQLException {
        for (String table : CORE_TABLES) if (tableExists(connection, table)) return true;
        return false;
    }

    public boolean ensureRequiredSchemaObjects(Connection connection) throws SQLException {
        if (!hasAnyCoreTable(connection)) {
            createFreshSchema(connection);
            return true;
        }
        for (String table : CORE_TABLES) {
            if (!tableExists(connection, table)) throw new SchemaValidationException("Existing database is missing core table: " + table);
        }
        return false;
    }

    public void createFreshSchema(Connection connection) throws SQLException {
        ensureMetadataTable(connection);
        try (Statement statement = connection.createStatement()) {
            createV8Tables(statement, "");
            createIndexes(statement);
        }
        recordSchemaVersion(connection, DatabaseManager.SCHEMA_VERSION);
        if (metadata.read(connection, "data_revision") == null) metadata.write(connection, "data_revision", "0");
    }

    public void ensureAdditiveSchemaObjects(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createEventCountTable(statement);
            createIndexes(statement);
        }
    }

    public void applyMigration(Connection connection, int version) throws SQLException {
        if (version == DatabaseManager.SCHEMA_VERSION) {
            migrateLegacyToV8(connection);
            return;
        }
        if (version < DatabaseManager.SCHEMA_VERSION) return;
        throw new SQLException("Unknown CreditManager schema migration " + version);
    }

    public void migrateLegacyToV8(Connection connection) throws SQLException {
        for (String table : CORE_TABLES) {
            if (!tableExists(connection, table)) throw new SchemaValidationException("Cannot migrate database missing core table: " + table);
        }
        dropStagingTables(connection);
        try (Statement statement = connection.createStatement()) {
            createV8Tables(statement, "_v8");
        }
        copyPaylogs(connection);
        copyCredits(connection);
        copyPayments(connection);
        copyEvents(connection);
        validateAndRebuildDerivedState(connection);
        swapV8Tables(connection);
        try (Statement statement = connection.createStatement()) {
            createIndexes(statement);
        }
        recordSchemaVersion(connection, DatabaseManager.SCHEMA_VERSION);
        backfill(connection);
    }

    public void backfill(Connection connection) throws SQLException {
        ensureAdditiveSchemaObjects(connection);
        rebuildEventCounts(connection);
        backfillCreditSearchText(connection);
        backfillPaylogSearchText(connection);
        paylogTokens.backfill(connection);
        dealTokens.backfill(connection);
    }

    public void replacePaylogSearchTokens(Connection connection, TransactionEntry entry) throws SQLException {
        paylogTokens.replace(connection, entry);
    }

    public void replaceDealSearchTokens(Connection connection, CreditEntry entry) throws SQLException {
        dealTokens.replace(connection, entry);
    }

    public void validateRequiredSchema(Connection connection) throws SQLException {
        for (String table : REQUIRED_TABLES) {
            if (!tableExists(connection, table)) throw new SchemaValidationException("Required table is missing: " + table);
        }
        for (Map.Entry<String, List<ExpectedColumn>> table : REQUIRED_COLUMNS.entrySet()) {
            for (ExpectedColumn column : table.getValue()) validateColumn(connection, table.getKey(), column);
        }
        validatePrimaryKey(connection, "credits", List.of("id"));
        validatePrimaryKey(connection, "payments", List.of("id"));
        validatePrimaryKey(connection, "credit_events", List.of("id"));
        validatePrimaryKey(connection, "credit_event_counts", List.of("credit_id"));
        validatePrimaryKey(connection, "paylogs", List.of("id"));
        validatePrimaryKey(connection, "paylog_search_tokens", List.of("paylog_id", "token"));
        validatePrimaryKey(connection, "deal_search_tokens", List.of("credit_id", "token"));
        validateForeignKey(connection, "payments", "credit_id", "credits", "id", DatabaseMetaData.importedKeyCascade);
        validateForeignKey(connection, "payments", "paylog_id", "paylogs", "id", DatabaseMetaData.importedKeyNoAction);
        validateForeignKey(connection, "credit_events", "credit_id", "credits", "id", DatabaseMetaData.importedKeyCascade);
        validateForeignKey(connection, "credit_event_counts", "credit_id", "credits", "id", DatabaseMetaData.importedKeyCascade);
        validateForeignKey(connection, "paylog_search_tokens", "paylog_id", "paylogs", "id", DatabaseMetaData.importedKeyCascade);
        validateForeignKey(connection, "deal_search_tokens", "credit_id", "credits", "id", DatabaseMetaData.importedKeyCascade);
        validateUniqueConstraint(connection, "paylogs", List.of("entry_hash"));
        validateCheckConstraintSemantics(connection);
        for (ExpectedIndex index : REQUIRED_INDEXES) validateIndex(connection, index);
        if (installedSchemaVersion(connection) != DatabaseManager.SCHEMA_VERSION) {
            throw new SchemaValidationException("Unexpected schema version");
        }
    }

    public void repairSchemaMetadata(Connection connection, boolean repairedSchema) throws SQLException {
        recordSchemaVersion(connection, DatabaseManager.SCHEMA_VERSION);
        if (metadata.read(connection, "data_revision") == null) metadata.write(connection, "data_revision", "0");
        if (repairedSchema) {
            healthReporter.report(connection, "SCHEMA_BOOTSTRAPPED", "INFO", "metadata", "schema_version",
                    "Schema erstellt", "Eine neue leere Datenbank wurde mit Schema v8 erstellt.", null, null);
        }
    }

    private void createV8Tables(Statement statement, String suffix) throws SQLException {
        String credits = "credits" + suffix;
        String paylogs = "paylogs" + suffix;
        String payments = "payments" + suffix;
        String events = "credit_events" + suffix;
        statement.execute("CREATE TABLE IF NOT EXISTS " + credits + " (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, paid_amount BIGINT NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL, CONSTRAINT ck_" + credits + "_amount CHECK (amount>0 AND amount<=" + MoneyRules.MAX_MINOR + "), CONSTRAINT ck_" + credits + "_paid CHECK (paid_amount>=0 AND paid_amount<=amount))");
        statement.execute("CREATE TABLE IF NOT EXISTS " + paylogs + " (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB, linked_amount BIGINT NOT NULL DEFAULT 0, link_count INT NOT NULL DEFAULT 0, CONSTRAINT ck_" + paylogs + "_amount CHECK (amount>0 AND amount<=" + MoneyRules.MAX_MINOR + "), CONSTRAINT ck_" + paylogs + "_linked CHECK (linked_amount>=0 AND linked_amount<=amount AND link_count>=0))");
        statement.execute("CREATE TABLE IF NOT EXISTS " + payments + " (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount BIGINT NOT NULL, payment_kind VARCHAR(16) NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL, CONSTRAINT ck_" + payments + "_amount CHECK (amount>0 AND amount<=" + MoneyRules.MAX_MINOR + "), CONSTRAINT ck_" + payments + "_kind CHECK (payment_kind IN ('MONEY','ITEM')), CONSTRAINT fk_" + payments + "_credit FOREIGN KEY (credit_id) REFERENCES " + credits + "(id) ON DELETE CASCADE, CONSTRAINT fk_" + payments + "_paylog FOREIGN KEY (paylog_id) REFERENCES " + paylogs + "(id) ON DELETE RESTRICT)");
        statement.execute("CREATE TABLE IF NOT EXISTS " + events + " (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, paid_after BIGINT NOT NULL, remaining_after BIGINT NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before BIGINT NOT NULL, amount_after BIGINT NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_" + events + "_credit FOREIGN KEY (credit_id) REFERENCES " + credits + "(id) ON DELETE CASCADE)");
        if (suffix.isEmpty()) createAuxiliaryTables(statement);
    }

    private void createAuxiliaryTables(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS paylog_search_tokens (paylog_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (paylog_id, token), CONSTRAINT fk_paylog_search_token FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS deal_search_tokens (credit_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (credit_id, token), CONSTRAINT fk_deal_search_token FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
        statement.execute("CREATE TABLE IF NOT EXISTS migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
        createEventCountTable(statement);
    }

    private void createEventCountTable(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS credit_event_counts (credit_id VARCHAR(36) PRIMARY KEY, event_count BIGINT NOT NULL, CONSTRAINT ck_credit_event_counts_value CHECK (event_count>=0), CONSTRAINT fk_credit_event_counts_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
    }

    public void rebuildEventCounts(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM credit_event_counts");
            statement.executeUpdate("INSERT INTO credit_event_counts (credit_id,event_count) SELECT credit_id,COUNT(*) FROM credit_events GROUP BY credit_id");
        }
    }

    private void createIndexes(Statement statement) throws SQLException {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_status ON credits(status)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_parties ON credits(debtor, creditor)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_completed ON credits(completed_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_archived ON credits(archived, status)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_credit ON payments(credit_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_created ON payments(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_credit ON credit_events(credit_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_credit_created ON credit_events(credit_id, created_at DESC, id DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_created ON credit_events(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_created_id ON credit_events(created_at DESC, id DESC)");
        statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_paylogs_hash ON paylogs(entry_hash)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_created ON paylogs(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_parties ON paylogs(payer, receiver)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_amount ON paylogs(amount)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_linked_amount ON paylogs(linked_amount, amount)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylog_search_token ON paylog_search_tokens(token, paylog_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_deal_search_token ON deal_search_tokens(token, credit_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_health_status ON data_health_records(status, severity)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_legacy_records_migration ON legacy_records(migration_id)");
    }

    private void copyPaylogs(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM paylogs");
             ResultSet row = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO paylogs_v8 (id,payer,receiver,amount,raw_text,normalized_text,created_at,entry_hash,source,revision,metadata,linked_amount,link_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (row.next()) {
                insert.setString(1, required(row, "id"));
                insert.setString(2, required(row, "payer"));
                insert.setString(3, required(row, "receiver"));
                insert.setLong(4, legacyMinor(row, "amount", true));
                insert.setString(5, row.getString("raw_text"));
                insert.setString(6, required(row, "normalized_text"));
                insert.setLong(7, row.getLong("created_at"));
                insert.setString(8, required(row, "entry_hash"));
                insert.setString(9, row.getString("source"));
                insert.setLong(10, row.getLong("revision"));
                insert.setString(11, hasColumn(row, "metadata") ? row.getString("metadata") : null);
                insert.setLong(12, 0L);
                insert.setInt(13, 0);
                insert.executeUpdate();
            }
        }
    }

    private void copyCredits(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credits");
             ResultSet row = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO credits_v8 (id,deal_name,creditor,debtor,amount,paid_amount,created_at,due_date,status,note,search_text,completed_at,archived,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (row.next()) {
                insert.setString(1, required(row, "id"));
                insert.setString(2, required(row, "deal_name"));
                insert.setString(3, required(row, "creditor"));
                insert.setString(4, required(row, "debtor"));
                insert.setLong(5, legacyMinor(row, "amount", true));
                insert.setLong(6, legacyMinor(row, "paid_amount", false));
                insert.setLong(7, row.getLong("created_at"));
                setNullableLong(insert, 8, nullableLong(row, "due_date"));
                insert.setString(9, required(row, "status"));
                insert.setString(10, row.getString("note"));
                insert.setString(11, hasColumn(row, "search_text") ? row.getString("search_text") : null);
                setNullableLong(insert, 12, nullableLong(row, "completed_at"));
                insert.setBoolean(13, row.getBoolean("archived"));
                insert.setLong(14, row.getLong("revision"));
                insert.executeUpdate();
            }
        }
    }

    private void copyPayments(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM payments");
             ResultSet row = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO payments_v8 (id,credit_id,from_player,to_player,amount,payment_kind,items_json,item_nbt,item_nbt_entries,created_at,source,paylog_id,note,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (row.next()) {
                String items = row.getString("items_json");
                String nbtEntries = row.getString("item_nbt_entries");
                List<String> parsedItems = strictStringList(items, true);
                strictStringList(nbtEntries, true);
                insert.setString(1, required(row, "id"));
                insert.setString(2, required(row, "credit_id"));
                insert.setString(3, row.getString("from_player"));
                insert.setString(4, row.getString("to_player"));
                insert.setLong(5, legacyMinor(row, "amount", true));
                insert.setString(6, parsedItems.isEmpty() ? "MONEY" : "ITEM");
                insert.setString(7, items);
                insert.setString(8, row.getString("item_nbt"));
                insert.setString(9, nbtEntries);
                insert.setLong(10, row.getLong("created_at"));
                insert.setString(11, row.getString("source"));
                insert.setString(12, hasColumn(row, "paylog_id") ? row.getString("paylog_id") : null);
                insert.setString(13, hasColumn(row, "note") ? row.getString("note") : null);
                insert.setLong(14, row.getLong("revision"));
                insert.executeUpdate();
            }
        }
    }

    private void copyEvents(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credit_events");
             ResultSet row = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO credit_events_v8 (id,credit_id,event_type,amount,paid_after,remaining_after,created_at,deal_name,creditor,debtor,note,amount_before,amount_after,actor,source,item_payment,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            while (row.next()) {
                insert.setString(1, required(row, "id"));
                insert.setString(2, required(row, "credit_id"));
                insert.setString(3, required(row, "event_type"));
                insert.setLong(4, legacyMinor(row, "amount", false));
                insert.setLong(5, legacyMinor(row, "paid_after", false));
                insert.setLong(6, legacyMinor(row, "remaining_after", false));
                insert.setLong(7, row.getLong("created_at"));
                insert.setString(8, row.getString("deal_name"));
                insert.setString(9, row.getString("creditor"));
                insert.setString(10, row.getString("debtor"));
                insert.setString(11, row.getString("note"));
                insert.setLong(12, legacyMinor(row, "amount_before", false));
                insert.setLong(13, legacyMinor(row, "amount_after", false));
                insert.setString(14, row.getString("actor"));
                insert.setString(15, row.getString("source"));
                insert.setBoolean(16, row.getBoolean("item_payment"));
                insert.setLong(17, row.getLong("revision"));
                insert.executeUpdate();
            }
        }
    }

    private void validateAndRebuildDerivedState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT c.id,c.amount,c.paid_amount,c.status,COALESCE(SUM(p.amount),0) actual_paid FROM credits_v8 c LEFT JOIN payments_v8 p ON p.credit_id=c.id GROUP BY c.id,c.amount,c.paid_amount,c.status")) {
            while (rows.next()) {
                long amount = rows.getLong("amount");
                long storedPaid = rows.getLong("paid_amount");
                long actualPaid = rows.getLong("actual_paid");
                if (storedPaid != actualPaid) throw new SchemaValidationException("Credit paid aggregate mismatch during v8 migration: " + rows.getString("id"));
                String status = rows.getString("status");
                if (!CreditStatusRules.isManualFinal(status)) {
                    String derived;
                    try {
                        derived = CreditStatusRules.derive(amount, actualPaid);
                    } catch (IllegalArgumentException exception) {
                        throw new SchemaValidationException("Invalid credit aggregate during v8 migration: " + rows.getString("id"));
                    }
                    try (PreparedStatement update = connection.prepareStatement("UPDATE credits_v8 SET status=? WHERE id=?")) {
                        update.setString(1, derived);
                        update.setString(2, rows.getString("id"));
                        update.executeUpdate();
                    }
                }
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT p.id,p.amount,p.source,p.from_player,p.to_player,l.id paylog_exists,l.amount paylog_amount,l.payer,l.receiver FROM payments_v8 p LEFT JOIN paylogs_v8 l ON l.id=p.paylog_id WHERE p.paylog_id IS NOT NULL")) {
            while (rows.next()) {
                if (rows.getString("paylog_exists") == null) throw new SchemaValidationException("Orphan paylog link during v8 migration");
                if (!safe(rows.getString("source")).startsWith("PAYLOG_")
                        || !safe(rows.getString("from_player")).equalsIgnoreCase(safe(rows.getString("payer")))
                        || !safe(rows.getString("to_player")).equalsIgnoreCase(safe(rows.getString("receiver")))
                        || rows.getLong("amount") > rows.getLong("paylog_amount")) {
                    throw new SchemaValidationException("Invalid paylog link during v8 migration: " + rows.getString("id"));
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE paylogs_v8 SET linked_amount=COALESCE((SELECT SUM(amount) FROM payments_v8 WHERE paylog_id=paylogs_v8.id),0), link_count=COALESCE((SELECT COUNT(*) FROM payments_v8 WHERE paylog_id=paylogs_v8.id),0)");
        }
    }

    private void swapV8Tables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS paylog_search_tokens");
            statement.execute("DROP TABLE IF EXISTS deal_search_tokens");
            statement.execute("DROP TABLE IF EXISTS credit_event_counts");
            statement.execute("DROP TABLE credit_events");
            statement.execute("DROP TABLE payments");
            statement.execute("DROP TABLE credits");
            statement.execute("DROP TABLE paylogs");
            statement.execute("ALTER TABLE credits_v8 RENAME TO credits");
            statement.execute("ALTER TABLE paylogs_v8 RENAME TO paylogs");
            statement.execute("ALTER TABLE payments_v8 RENAME TO payments");
            statement.execute("ALTER TABLE credit_events_v8 RENAME TO credit_events");
            createAuxiliaryTables(statement);
        }
    }

    private void dropStagingTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS credit_events_v8");
            statement.execute("DROP TABLE IF EXISTS payments_v8");
            statement.execute("DROP TABLE IF EXISTS credits_v8");
            statement.execute("DROP TABLE IF EXISTS paylogs_v8");
        }
    }

    private void backfillCreditSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credits");
             ResultSet row = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE credits SET search_text=? WHERE id=?")) {
            while (row.next()) {
                CreditEntry entry = new CreditEntry();
                entry.setId(UUID.fromString(row.getString("id")));
                entry.setDealName(row.getString("deal_name"));
                entry.setCreditor(row.getString("creditor"));
                entry.setDebtor(row.getString("debtor"));
                entry.setAmountMinor(row.getLong("amount"));
                entry.setPaidAmountMinor(row.getLong("paid_amount"));
                entry.setCreatedAt(row.getLong("created_at"));
                entry.setDueDate(nullableLong(row, "due_date"));
                entry.setStatus(row.getString("status"));
                entry.setNote(row.getString("note"));
                entry.setCompletedAt(nullableLong(row, "completed_at"));
                entry.setArchived(row.getBoolean("archived"));
                update.setString(1, DealSearchText.build(entry));
                update.setString(2, entry.getId().toString());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void backfillPaylogSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM paylogs");
             ResultSet row = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE paylogs SET normalized_text=? WHERE id=?")) {
            while (row.next()) {
                TransactionEntry entry = new TransactionEntry();
                entry.setId(UUID.fromString(row.getString("id")));
                entry.setFromPlayer(row.getString("payer"));
                entry.setToPlayer(row.getString("receiver"));
                entry.setAmountMinor(row.getLong("amount"));
                entry.setRawText(row.getString("raw_text"));
                entry.setTimestamp(row.getLong("created_at"));
                entry.setSource(row.getString("source"));
                entry.setMetadata(row.getString("metadata"));
                update.setString(1, PaylogSearchText.build(entry));
                update.setString(2, entry.getId().toString());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void recordSchemaVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO schema_migrations (version, applied_at) KEY(version) VALUES (?, ?)")) {
            statement.setInt(1, version);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
        metadata.write(connection, "schema_version", String.valueOf(version));
    }

    private void validateColumn(Connection connection, String table, ExpectedColumn expected) throws SQLException {
        try (ResultSet result = connection.getMetaData().getColumns(null, null, table, expected.name())) {
            if (!result.next()) throw new SchemaValidationException("Required column is missing: " + table + '.' + expected.name());
            int actualType = result.getInt("DATA_TYPE");
            if (actualType != expected.sqlType()) throw new SchemaValidationException("Wrong type for " + table + '.' + expected.name());
            boolean nullable = result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
            if (nullable != expected.nullable()) throw new SchemaValidationException("Wrong nullability for " + table + '.' + expected.name());
        }
    }

    private void validatePrimaryKey(Connection connection, String table, List<String> expected) throws SQLException {
        Map<Short, String> columns = new LinkedHashMap<>();
        try (ResultSet result = connection.getMetaData().getPrimaryKeys(null, null, table)) {
            while (result.next()) columns.put(result.getShort("KEY_SEQ"), result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        if (!new ArrayList<>(columns.values()).equals(expected)) throw new SchemaValidationException("Wrong primary key for " + table);
    }

    private void validateForeignKey(Connection connection, String table, String column, String targetTable,
                                    String targetColumn, int deleteRule) throws SQLException {
        try (ResultSet result = connection.getMetaData().getImportedKeys(null, null, table)) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("FKCOLUMN_NAME"))
                        && targetTable.equalsIgnoreCase(result.getString("PKTABLE_NAME"))
                        && targetColumn.equalsIgnoreCase(result.getString("PKCOLUMN_NAME"))) {
                    int actual = result.getInt("DELETE_RULE");
                    if (actual != deleteRule && !(deleteRule == DatabaseMetaData.importedKeyNoAction
                            && actual == DatabaseMetaData.importedKeyRestrict)) {
                        throw new SchemaValidationException("Wrong delete rule for " + table + '.' + column);
                    }
                    return;
                }
            }
        }
        throw new SchemaValidationException("Missing foreign key for " + table + '.' + column);
    }

    private void validateIndex(Connection connection, ExpectedIndex expected) throws SQLException {
        Map<Short, String> columns = new LinkedHashMap<>();
        Boolean nonUnique = null;
        try (ResultSet result = connection.getMetaData().getIndexInfo(null, null, expected.table(), false, false)) {
            while (result.next()) {
                if (!expected.name().equalsIgnoreCase(result.getString("INDEX_NAME"))) continue;
                nonUnique = result.getBoolean("NON_UNIQUE");
                String column = result.getString("COLUMN_NAME");
                if (column != null) columns.put(result.getShort("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
            }
        }
        if (nonUnique == null || expected.unique() == nonUnique || !new ArrayList<>(columns.values()).equals(expected.columns())) {
            throw new SchemaValidationException("Missing or invalid index: " + expected.name());
        }
    }

    private void validateUniqueConstraint(Connection connection, String table, List<String> expectedColumns) throws SQLException {
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        String sql = "SELECT tc.constraint_name,kcu.column_name,kcu.ordinal_position FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu ON kcu.constraint_catalog=tc.constraint_catalog AND kcu.constraint_schema=tc.constraint_schema AND kcu.constraint_name=tc.constraint_name WHERE LOWER(tc.table_name)=? AND tc.constraint_type='UNIQUE' ORDER BY tc.constraint_name,kcu.ordinal_position";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) constraints.computeIfAbsent(result.getString(1), ignored -> new ArrayList<>()).add(result.getString(2).toLowerCase(Locale.ROOT));
            }
        }
        if (constraints.values().stream().noneMatch(expectedColumns::equals)) throw new SchemaValidationException("Missing unique constraint for " + table + expectedColumns);
    }

    private void validateCheckConstraintSemantics(Connection connection) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) connection.setAutoCommit(false);
        java.sql.Savepoint savepoint = connection.setSavepoint();
        String creditId = java.util.UUID.randomUUID().toString();
        String paylogId = java.util.UUID.randomUUID().toString();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO credits (id,deal_name,creditor,debtor,amount,paid_amount,created_at,status,archived,revision) VALUES ('" + creditId + "','schema-probe','probe-creditor','probe-debtor',100,0,1,'OPEN',FALSE,0)");
            statement.executeUpdate("INSERT INTO paylogs (id,payer,receiver,amount,normalized_text,created_at,entry_hash,revision,linked_amount,link_count) VALUES ('" + paylogId + "','probe-payer','probe-receiver',100,'schema probe',1,'" + paylogId + "',0,0,0)");
            assertRejected(statement, "INSERT INTO credits (id,deal_name,creditor,debtor,amount,paid_amount,created_at,status,archived,revision) VALUES ('" + java.util.UUID.randomUUID() + "','invalid','a','b',0,0,1,'OPEN',FALSE,0)", "credits.amount");
            assertRejected(statement, "INSERT INTO credits (id,deal_name,creditor,debtor,amount,paid_amount,created_at,status,archived,revision) VALUES ('" + java.util.UUID.randomUUID() + "','invalid','a','b',100,101,1,'PARTIAL',FALSE,0)", "credits.paid_amount");
            assertRejected(statement, "INSERT INTO payments (id,credit_id,amount,payment_kind,created_at,revision) VALUES ('" + java.util.UUID.randomUUID() + "','" + creditId + "',0,'MONEY',1,0)", "payments.amount");
            assertRejected(statement, "INSERT INTO payments (id,credit_id,amount,payment_kind,created_at,revision) VALUES ('" + java.util.UUID.randomUUID() + "','" + creditId + "',1,'INVALID',1,0)", "payments.payment_kind");
            assertRejected(statement, "INSERT INTO paylogs (id,payer,receiver,amount,normalized_text,created_at,entry_hash,revision,linked_amount,link_count) VALUES ('" + java.util.UUID.randomUUID() + "','a','b',0,'invalid',1,'" + java.util.UUID.randomUUID() + "',0,0,0)", "paylogs.amount");
            assertRejected(statement, "UPDATE paylogs SET linked_amount=101 WHERE id='" + paylogId + "'", "paylogs.linked_amount");
            assertRejected(statement, "UPDATE paylogs SET link_count=-1 WHERE id='" + paylogId + "'", "paylogs.link_count");
        } finally {
            connection.rollback(savepoint);
            if (originalAutoCommit) connection.setAutoCommit(true);
        }
    }

    private void assertRejected(Statement statement, String sql, String invariant) throws SQLException {
        try {
            statement.executeUpdate(sql);
        } catch (SQLException expected) {
            return;
        }
        throw new SchemaValidationException("Check constraint does not enforce " + invariant);
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema','pg_catalog')")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private long legacyMinor(ResultSet result, String column, boolean positive) throws SQLException {
        try {
            java.math.BigDecimal value = result.getBigDecimal(column);
            if (value == null) throw new IllegalArgumentException("missing legacy money");
            return MoneyRules.fromMajor(value, positive).minorUnits();
        } catch (IllegalArgumentException exception) {
            throw new SchemaValidationException("Invalid legacy money in " + column);
        }
    }

    private List<String> strictStringList(String json, boolean blankIsEmpty) throws SQLException {
        if (json == null || json.isBlank()) return blankIsEmpty ? List.of() : null;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonArray()) throw new IllegalArgumentException();
            JsonArray array = parsed.getAsJsonArray();
            List<String> values = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
                values.add(element.getAsString());
            }
            return List.copyOf(values);
        } catch (RuntimeException exception) {
            throw new SchemaValidationException("Malformed persisted item JSON");
        }
    }

    private String required(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null || value.isBlank()) throw new SchemaValidationException("Missing required value: " + column);
        return value;
    }

    private boolean hasColumn(ResultSet result, String name) throws SQLException {
        for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
            if (name.equalsIgnoreCase(result.getMetaData().getColumnLabel(index))) return true;
        }
        return false;
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, List<ExpectedColumn>> expectedColumns() {
        Map<String, List<ExpectedColumn>> values = new LinkedHashMap<>();
        values.put("credits", List.of(new ExpectedColumn("id", Types.VARCHAR, false), new ExpectedColumn("amount", Types.BIGINT, false), new ExpectedColumn("paid_amount", Types.BIGINT, false), new ExpectedColumn("status", Types.VARCHAR, false), new ExpectedColumn("revision", Types.BIGINT, false)));
        values.put("payments", List.of(new ExpectedColumn("id", Types.VARCHAR, false), new ExpectedColumn("credit_id", Types.VARCHAR, false), new ExpectedColumn("amount", Types.BIGINT, false), new ExpectedColumn("payment_kind", Types.VARCHAR, false), new ExpectedColumn("paylog_id", Types.VARCHAR, true), new ExpectedColumn("revision", Types.BIGINT, false)));
        values.put("credit_events", List.of(new ExpectedColumn("id", Types.VARCHAR, false), new ExpectedColumn("credit_id", Types.VARCHAR, false), new ExpectedColumn("amount", Types.BIGINT, false), new ExpectedColumn("paid_after", Types.BIGINT, false), new ExpectedColumn("remaining_after", Types.BIGINT, false), new ExpectedColumn("amount_before", Types.BIGINT, false), new ExpectedColumn("amount_after", Types.BIGINT, false)));
        values.put("credit_event_counts", List.of(new ExpectedColumn("credit_id", Types.VARCHAR, false), new ExpectedColumn("event_count", Types.BIGINT, false)));
        values.put("paylogs", List.of(new ExpectedColumn("id", Types.VARCHAR, false), new ExpectedColumn("amount", Types.BIGINT, false), new ExpectedColumn("entry_hash", Types.VARCHAR, false), new ExpectedColumn("linked_amount", Types.BIGINT, false), new ExpectedColumn("link_count", Types.INTEGER, false)));
        return Map.copyOf(values);
    }

    public static final class SchemaValidationException extends SQLException {
        public SchemaValidationException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface HealthReporter {
        void report(Connection connection, String type, String severity, String table, String sourceId,
                    String title, String message, String raw, String repair) throws SQLException;
    }

    private record ExpectedColumn(String name, int sqlType, boolean nullable) { }
    private record ExpectedIndex(String table, String name, boolean unique, List<String> columns) { }
}

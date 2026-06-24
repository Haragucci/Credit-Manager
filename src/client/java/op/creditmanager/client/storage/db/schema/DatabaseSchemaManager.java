package op.creditmanager.client.storage.db.schema;

import com.google.gson.Gson;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import op.creditmanager.client.search.PaylogSearchText;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.dao.DatabaseMetadataDao;
import op.creditmanager.client.storage.db.dao.PaylogSearchTokenDao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class DatabaseSchemaManager {
    private static final Gson GSON = new Gson();
    private static final List<String> REQUIRED_TABLES = List.of("metadata", "schema_migrations", "credits", "payments", "credit_events", "paylogs", "paylog_search_tokens", "data_health_records", "migration_log", "legacy_records");
    private static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("metadata", "meta_key", "VARCHAR(128)"), new RequiredColumn("metadata", "meta_value", "VARCHAR(4096)"),
            new RequiredColumn("schema_migrations", "version", "INT"), new RequiredColumn("schema_migrations", "applied_at", "BIGINT DEFAULT 0"),
            new RequiredColumn("credits", "id", "VARCHAR(36)"), new RequiredColumn("credits", "deal_name", "VARCHAR(256) DEFAULT ''"), new RequiredColumn("credits", "creditor", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("credits", "debtor", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("credits", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credits", "paid_amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credits", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("credits", "due_date", "BIGINT"), new RequiredColumn("credits", "status", "VARCHAR(32) DEFAULT 'OPEN'"), new RequiredColumn("credits", "note", "CLOB"), new RequiredColumn("credits", "search_text", "CLOB"), new RequiredColumn("credits", "completed_at", "BIGINT"), new RequiredColumn("credits", "archived", "BOOLEAN NOT NULL DEFAULT FALSE"), new RequiredColumn("credits", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("payments", "id", "VARCHAR(36)"), new RequiredColumn("payments", "credit_id", "VARCHAR(36)"), new RequiredColumn("payments", "from_player", "VARCHAR(64)"), new RequiredColumn("payments", "to_player", "VARCHAR(64)"), new RequiredColumn("payments", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("payments", "items_json", "CLOB"), new RequiredColumn("payments", "item_nbt", "CLOB"), new RequiredColumn("payments", "item_nbt_entries", "CLOB"), new RequiredColumn("payments", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("payments", "source", "VARCHAR(64)"), new RequiredColumn("payments", "paylog_id", "VARCHAR(36)"), new RequiredColumn("payments", "note", "CLOB"), new RequiredColumn("payments", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("credit_events", "id", "VARCHAR(36)"), new RequiredColumn("credit_events", "credit_id", "VARCHAR(36)"), new RequiredColumn("credit_events", "event_type", "VARCHAR(64)"), new RequiredColumn("credit_events", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "paid_after", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "remaining_after", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("credit_events", "deal_name", "VARCHAR(256)"), new RequiredColumn("credit_events", "creditor", "VARCHAR(64)"), new RequiredColumn("credit_events", "debtor", "VARCHAR(64)"), new RequiredColumn("credit_events", "note", "CLOB"), new RequiredColumn("credit_events", "amount_before", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "amount_after", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "actor", "VARCHAR(64)"), new RequiredColumn("credit_events", "source", "VARCHAR(64)"), new RequiredColumn("credit_events", "item_payment", "BOOLEAN NOT NULL DEFAULT FALSE"), new RequiredColumn("credit_events", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("paylogs", "id", "VARCHAR(36)"), new RequiredColumn("paylogs", "payer", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("paylogs", "receiver", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("paylogs", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("paylogs", "raw_text", "CLOB"), new RequiredColumn("paylogs", "normalized_text", "CLOB DEFAULT ''"), new RequiredColumn("paylogs", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("paylogs", "entry_hash", "VARCHAR(64)"), new RequiredColumn("paylogs", "source", "VARCHAR(64)"), new RequiredColumn("paylogs", "revision", "BIGINT NOT NULL DEFAULT 0"), new RequiredColumn("paylogs", "metadata", "CLOB"), new RequiredColumn("paylogs", "linked_amount", "DOUBLE PRECISION NOT NULL DEFAULT 0"), new RequiredColumn("paylogs", "link_count", "INT NOT NULL DEFAULT 0"),
            new RequiredColumn("paylog_search_tokens", "paylog_id", "VARCHAR(36)"), new RequiredColumn("paylog_search_tokens", "token", "VARCHAR(96)"),
            new RequiredColumn("data_health_records", "id", "VARCHAR(36)"), new RequiredColumn("data_health_records", "record_type", "VARCHAR(64)"), new RequiredColumn("data_health_records", "severity", "VARCHAR(32)"), new RequiredColumn("data_health_records", "source_table", "VARCHAR(64)"), new RequiredColumn("data_health_records", "source_id", "VARCHAR(128)"), new RequiredColumn("data_health_records", "title", "VARCHAR(256)"), new RequiredColumn("data_health_records", "message", "CLOB"), new RequiredColumn("data_health_records", "raw_payload", "CLOB"), new RequiredColumn("data_health_records", "repair_payload", "CLOB"), new RequiredColumn("data_health_records", "status", "VARCHAR(32)"), new RequiredColumn("data_health_records", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("data_health_records", "resolved_at", "BIGINT"),
            new RequiredColumn("migration_log", "id", "VARCHAR(36)"), new RequiredColumn("migration_log", "migration_type", "VARCHAR(64)"), new RequiredColumn("migration_log", "started_at", "BIGINT DEFAULT 0"), new RequiredColumn("migration_log", "completed_at", "BIGINT"), new RequiredColumn("migration_log", "details", "CLOB"), new RequiredColumn("migration_log", "status", "VARCHAR(32)"),
            new RequiredColumn("legacy_records", "id", "VARCHAR(36)"), new RequiredColumn("legacy_records", "record_kind", "VARCHAR(64)"), new RequiredColumn("legacy_records", "original_id", "VARCHAR(256)"), new RequiredColumn("legacy_records", "raw_payload", "CLOB"), new RequiredColumn("legacy_records", "reason", "CLOB"), new RequiredColumn("legacy_records", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("legacy_records", "migration_id", "VARCHAR(36)")
    );
    private static final List<RequiredIndex> REQUIRED_INDICES = List.of(
            new RequiredIndex("credits", "idx_credits_status", "CREATE INDEX IF NOT EXISTS idx_credits_status ON credits(status)"), new RequiredIndex("credits", "idx_credits_parties", "CREATE INDEX IF NOT EXISTS idx_credits_parties ON credits(debtor, creditor)"), new RequiredIndex("credits", "idx_credits_completed", "CREATE INDEX IF NOT EXISTS idx_credits_completed ON credits(completed_at)"), new RequiredIndex("credits", "idx_credits_archived", "CREATE INDEX IF NOT EXISTS idx_credits_archived ON credits(archived, status)"), new RequiredIndex("credits", "idx_history_final", "CREATE INDEX IF NOT EXISTS idx_history_final ON credits(status, completed_at)"),
            new RequiredIndex("payments", "idx_payments_credit", "CREATE INDEX IF NOT EXISTS idx_payments_credit ON payments(credit_id)"), new RequiredIndex("payments", "idx_payments_paylog", "CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)"), new RequiredIndex("payments", "idx_payments_created", "CREATE INDEX IF NOT EXISTS idx_payments_created ON payments(created_at)"),
            new RequiredIndex("credit_events", "idx_events_credit", "CREATE INDEX IF NOT EXISTS idx_events_credit ON credit_events(credit_id)"), new RequiredIndex("credit_events", "idx_events_created", "CREATE INDEX IF NOT EXISTS idx_events_created ON credit_events(created_at)"),
            new RequiredIndex("paylogs", "idx_paylogs_hash", "CREATE INDEX IF NOT EXISTS idx_paylogs_hash ON paylogs(entry_hash)"), new RequiredIndex("paylogs", "idx_paylogs_created", "CREATE INDEX IF NOT EXISTS idx_paylogs_created ON paylogs(created_at)"), new RequiredIndex("paylogs", "idx_paylogs_parties", "CREATE INDEX IF NOT EXISTS idx_paylogs_parties ON paylogs(payer, receiver)"), new RequiredIndex("paylogs", "idx_paylogs_amount", "CREATE INDEX IF NOT EXISTS idx_paylogs_amount ON paylogs(amount)"), new RequiredIndex("paylogs", "idx_paylogs_linked_amount", "CREATE INDEX IF NOT EXISTS idx_paylogs_linked_amount ON paylogs(linked_amount, amount)"), new RequiredIndex("paylogs", "idx_paylogs_player_time", "CREATE INDEX IF NOT EXISTS idx_paylogs_player_time ON paylogs(payer, receiver, created_at DESC)"),
            new RequiredIndex("paylog_search_tokens", "idx_paylog_search_token", "CREATE INDEX IF NOT EXISTS idx_paylog_search_token ON paylog_search_tokens(token, paylog_id)"), new RequiredIndex("data_health_records", "idx_health_status", "CREATE INDEX IF NOT EXISTS idx_health_status ON data_health_records(status, severity)"), new RequiredIndex("legacy_records", "idx_legacy_records_migration", "CREATE INDEX IF NOT EXISTS idx_legacy_records_migration ON legacy_records(migration_id)")
    );
    private final DatabaseMetadataDao metadata;
    private final PaylogSearchTokenDao searchTokens;
    private final HealthReporter healthReporter;

    public DatabaseSchemaManager(DatabaseMetadataDao metadata, PaylogSearchTokenDao searchTokens, HealthReporter healthReporter) {
        this.metadata = metadata;
        this.searchTokens = searchTokens;
        this.healthReporter = healthReporter;
    }

    public void ensureMetadataTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            if (!columnExists(connection, "metadata", "meta_key")) statement.execute("ALTER TABLE metadata ADD COLUMN meta_key VARCHAR(128)");
            if (!columnExists(connection, "metadata", "meta_value")) statement.execute("ALTER TABLE metadata ADD COLUMN meta_value VARCHAR(4096)");
            if (!columnExists(connection, "schema_migrations", "version")) statement.execute("ALTER TABLE schema_migrations ADD COLUMN version INT");
            if (!columnExists(connection, "schema_migrations", "applied_at")) statement.execute("ALTER TABLE schema_migrations ADD COLUMN applied_at BIGINT DEFAULT 0");
        }
    }

    public int installedSchemaVersion(Connection connection) throws SQLException {
        String value = metadata.read(connection, "schema_version");
        if (value != null) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) { return result.next() ? result.getInt(1) : 0; }
    }

    public boolean ensureRequiredSchemaObjects(Connection connection) throws SQLException {
        boolean repaired = false;
        try (Statement statement = connection.createStatement()) {
            repaired |= ensureTable(connection, statement, "credits", "CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            repaired |= ensureTable(connection, statement, "payments", "CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL, CONSTRAINT fk_payment_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            repaired |= ensureTable(connection, statement, "credit_events", "CREATE TABLE credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_event_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            repaired |= ensureTable(connection, statement, "paylogs", "CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB, linked_amount DOUBLE PRECISION NOT NULL DEFAULT 0, link_count INT NOT NULL DEFAULT 0)");
            repaired |= ensureTable(connection, statement, "paylog_search_tokens", "CREATE TABLE paylog_search_tokens (paylog_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (paylog_id, token), CONSTRAINT fk_paylog_search_token FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE)");
            repaired |= ensureTable(connection, statement, "data_health_records", "CREATE TABLE data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
            repaired |= ensureTable(connection, statement, "migration_log", "CREATE TABLE migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
            repaired |= ensureTable(connection, statement, "legacy_records", "CREATE TABLE legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
            for (RequiredColumn column : REQUIRED_COLUMNS) repaired |= ensureColumn(connection, statement, column);
            for (RequiredIndex index : REQUIRED_INDICES) repaired |= ensureIndex(connection, statement, index);
        }
        return repaired;
    }

    public void applyMigration(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            switch (version) {
                case 1 -> createInitialSchema(statement);
                case 2 -> { statement.execute("ALTER TABLE data_health_records ADD COLUMN IF NOT EXISTS repair_payload CLOB"); statement.execute("ALTER TABLE paylogs ADD COLUMN IF NOT EXISTS metadata CLOB"); statement.execute("CREATE INDEX IF NOT EXISTS idx_health_status ON data_health_records(status, severity)"); }
                case 3 -> { statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_hash ON paylogs(entry_hash)"); statement.execute("CREATE INDEX IF NOT EXISTS idx_history_final ON credits(status, completed_at)"); }
                case 4 -> { statement.execute("CREATE TABLE IF NOT EXISTS legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)"); statement.execute("CREATE INDEX IF NOT EXISTS idx_legacy_records_migration ON legacy_records(migration_id)"); }
                case 5 -> { statement.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS paylog_id VARCHAR(36)"); statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)"); }
                case 6 -> { statement.execute("ALTER TABLE credits ADD COLUMN IF NOT EXISTS search_text CLOB"); statement.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS note CLOB"); statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_archived ON credits(archived, status)"); }
                case 7 -> { statement.execute("ALTER TABLE paylogs ADD COLUMN IF NOT EXISTS linked_amount DOUBLE PRECISION NOT NULL DEFAULT 0"); statement.execute("ALTER TABLE paylogs ADD COLUMN IF NOT EXISTS link_count INT NOT NULL DEFAULT 0"); statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_linked_amount ON paylogs(linked_amount, amount)"); statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_player_time ON paylogs(payer, receiver, created_at DESC)"); statement.execute("CREATE TABLE IF NOT EXISTS paylog_search_tokens (paylog_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (paylog_id, token), CONSTRAINT fk_paylog_search_token FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE)"); statement.execute("CREATE INDEX IF NOT EXISTS idx_paylog_search_token ON paylog_search_tokens(token, paylog_id)"); }
                default -> throw new SQLException("Unknown CreditManager schema migration " + version);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO schema_migrations (version, applied_at) KEY(version) VALUES (?, ?)")) { statement.setInt(1, version); statement.setLong(2, System.currentTimeMillis()); statement.executeUpdate(); }
    }

    public void backfill(Connection connection) throws SQLException {
        backfillCreditSearchText(connection);
        backfillPaylogSearchText(connection);
        try (Statement statement = connection.createStatement()) { statement.executeUpdate("UPDATE paylogs SET linked_amount=COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0), link_count=COALESCE((SELECT COUNT(*) FROM payments WHERE paylog_id=paylogs.id),0) WHERE linked_amount<>COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0) OR link_count<>COALESCE((SELECT COUNT(*) FROM payments WHERE paylog_id=paylogs.id),0)"); }
        searchTokens.backfill(connection);
    }

    public void replacePaylogSearchTokens(Connection connection, TransactionEntry entry) throws SQLException { searchTokens.replace(connection, entry); }

    public void validateRequiredSchema(Connection connection) throws SQLException {
        for (String table : REQUIRED_TABLES) if (!tableExists(connection, table)) throw new SchemaValidationException("Required table is missing: " + table);
        for (RequiredColumn column : REQUIRED_COLUMNS) if (!columnExists(connection, column.table(), column.name())) throw new SchemaValidationException("Required column is missing: " + column.table() + '.' + column.name());
        for (RequiredIndex index : REQUIRED_INDICES) if (!indexExists(connection, index.table(), index.name())) throw new SchemaValidationException("Required index is missing: " + index.name());
        for (String sql : List.of("SELECT COUNT(*) FROM credits", "SELECT COUNT(*) FROM payments", "SELECT COUNT(*) FROM paylogs", "SELECT p.id, p.metadata, p.linked_amount, p.link_count FROM paylogs p WHERE 1=0", "SELECT paylog_id, token FROM paylog_search_tokens WHERE 1=0", "SELECT id FROM credits WHERE (status IN ('PAID','CLOSED','CANCELLED') OR archived=TRUE) AND COALESCE(search_text,'') LIKE '%' LIMIT 1")) executeSmokeQuery(connection, sql);
    }

    public void repairSchemaMetadata(Connection connection, boolean repairedSchema) throws SQLException {
        for (int version = 1; version <= DatabaseManager.SCHEMA_VERSION; version++) {
            try (PreparedStatement statement = connection.prepareStatement("MERGE INTO schema_migrations (version, applied_at) KEY(version) VALUES (?, ?)")) { statement.setInt(1, version); statement.setLong(2, System.currentTimeMillis()); statement.executeUpdate(); }
        }
        if (metadata.read(connection, "data_revision") == null) metadata.write(connection, "data_revision", "0");
        metadata.write(connection, "schema_version", String.valueOf(DatabaseManager.SCHEMA_VERSION));
        if (repairedSchema) healthReporter.report(connection, "SCHEMA_DRIFT_REPAIRED", "INFO", "metadata", "schema_version", "Schemaabweichung repariert", "Fehlende Tabellen, Spalten oder Indizes wurden ohne das Löschen von Nutzerdaten ergänzt.", null, null);
    }

    private void createInitialSchema(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL, CONSTRAINT fk_payment_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_event_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB, linked_amount DOUBLE PRECISION NOT NULL DEFAULT 0, link_count INT NOT NULL DEFAULT 0)");
        statement.execute("CREATE TABLE IF NOT EXISTS paylog_search_tokens (paylog_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (paylog_id, token), CONSTRAINT fk_paylog_search_token FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
        statement.execute("CREATE TABLE IF NOT EXISTS migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
        for (RequiredIndex index : REQUIRED_INDICES) statement.execute(index.definition());
    }

    private void backfillCreditSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credits"); ResultSet result = select.executeQuery(); PreparedStatement update = connection.prepareStatement("UPDATE credits SET search_text=? WHERE id=? AND (search_text IS NULL OR search_text<>?)")) {
            while (result.next()) {
                try {
                    CreditEntry entry = new CreditEntry();
                    entry.setId(UUID.fromString(result.getString("id"))); entry.setDealName(result.getString("deal_name")); entry.setCreditor(result.getString("creditor")); entry.setDebtor(result.getString("debtor")); entry.setAmount(result.getDouble("amount")); entry.setPaidAmount(result.getDouble("paid_amount")); entry.setCreatedAt(result.getLong("created_at")); long due = result.getLong("due_date"); entry.setDueDate(result.wasNull() ? null : due); entry.setStatus(result.getString("status")); entry.setNote(result.getString("note")); long completed = result.getLong("completed_at"); entry.setCompletedAt(result.wasNull() ? null : completed); entry.setArchived(result.getBoolean("archived"));
                    String searchText = DealSearchText.build(entry);
                    update.setString(1, searchText); update.setString(2, entry.getId().toString()); update.setString(3, searchText); update.addBatch();
                } catch (RuntimeException rowError) {
                    healthReporter.report(connection, "CREDIT_SEARCH_BACKFILL", "WARNING", "credits", result.getString("id"), "Suchtext konnte nicht ergänzt werden", "Dieser Deal bleibt erhalten und kann weiterhin über klassische Felder gesucht werden.", rowPayload(result), null);
                }
            }
            update.executeBatch();
        }
    }

    private void backfillPaylogSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM paylogs"); ResultSet result = select.executeQuery(); PreparedStatement update = connection.prepareStatement("UPDATE paylogs SET normalized_text=? WHERE id=? AND normalized_text<>?")) {
            while (result.next()) {
                TransactionEntry entry = new TransactionEntry();
                entry.setId(UUID.fromString(result.getString("id"))); entry.setFromPlayer(result.getString("payer")); entry.setToPlayer(result.getString("receiver")); entry.setAmount(result.getDouble("amount")); entry.setRawText(result.getString("raw_text")); entry.setTimestamp(result.getLong("created_at")); entry.setSource(result.getString("source")); entry.setMetadata(result.getString("metadata"));
                String searchText = PaylogSearchText.build(entry);
                update.setString(1, searchText); update.setString(2, entry.getId().toString()); update.setString(3, searchText); update.addBatch();
            }
            update.executeBatch();
        }
    }

    private boolean ensureTable(Connection connection, Statement statement, String table, String definition) throws SQLException { if (tableExists(connection, table)) return false; statement.execute(definition); return true; }
    private boolean ensureColumn(Connection connection, Statement statement, RequiredColumn column) throws SQLException { if (columnExists(connection, column.table(), column.name())) return false; statement.execute("ALTER TABLE " + column.table() + " ADD COLUMN " + column.name() + ' ' + column.definition()); return true; }
    private boolean ensureIndex(Connection connection, Statement statement, RequiredIndex index) throws SQLException { if (indexExists(connection, index.table(), index.name())) return false; statement.execute(index.definition()); return true; }
    private boolean tableExists(Connection connection, String table) throws SQLException { return exists(connection, "SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')", table); }
    private boolean columnExists(Connection connection, String table, String column) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME)=? AND LOWER(COLUMN_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')")) { statement.setString(1, table.toLowerCase(Locale.ROOT)); statement.setString(2, column.toLowerCase(Locale.ROOT)); try (ResultSet result = statement.executeQuery()) { return result.next(); } } }
    private boolean indexExists(Connection connection, String table, String index) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(TABLE_NAME)=? AND LOWER(INDEX_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')")) { statement.setString(1, table.toLowerCase(Locale.ROOT)); statement.setString(2, index.toLowerCase(Locale.ROOT)); try (ResultSet result = statement.executeQuery()) { return result.next(); } } }
    private boolean exists(Connection connection, String sql, String value) throws SQLException { try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setString(1, value.toLowerCase(Locale.ROOT)); try (ResultSet result = statement.executeQuery()) { return result.next(); } } }
    private void executeSmokeQuery(Connection connection, String sql) throws SQLException { try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet ignored = statement.executeQuery()) { } }
    private String rowPayload(ResultSet result) { try { Map<String, String> payload = new LinkedHashMap<>(); var metadata = result.getMetaData(); for (int index = 1; index <= metadata.getColumnCount(); index++) { Object value = result.getObject(index); payload.put(metadata.getColumnLabel(index), value == null ? null : String.valueOf(value)); } return GSON.toJson(payload); } catch (SQLException ignored) { return null; } }

    public static final class SchemaValidationException extends SQLException { public SchemaValidationException(String message) { super(message); } }
    @FunctionalInterface public interface HealthReporter { void report(Connection connection, String type, String severity, String table, String sourceId, String title, String message, String raw, String repair) throws SQLException; }
    private record RequiredColumn(String table, String name, String definition) { }
    private record RequiredIndex(String table, String name, String definition) { }
}

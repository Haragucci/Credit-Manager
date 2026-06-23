package op.creditmanager.client.storage.db;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.CreditEventType;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.search.DealSearchText;
import op.creditmanager.client.search.PaylogSearchText;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class DatabaseManager {
    public static final int SCHEMA_VERSION = 6;
    public static final int PAGE_SIZE = 500;
    private static final DatabaseManager INSTANCE = new DatabaseManager();
    private static final Gson GSON = new Gson();
    private static final Type STRING_LIST = new TypeToken<List<String>>() { }.getType();
    private static final Type BACKUP_MANIFEST_LIST = new TypeToken<List<BackupManifestEntry>>() { }.getType();
    private static final List<String> REQUIRED_TABLES = List.of("metadata", "schema_migrations", "credits", "payments",
            "credit_events", "paylogs", "data_health_records", "migration_log", "legacy_records");
    private static final List<RequiredColumn> REQUIRED_COLUMNS = List.of(
            new RequiredColumn("metadata", "meta_key", "VARCHAR(128)"), new RequiredColumn("metadata", "meta_value", "VARCHAR(4096)"),
            new RequiredColumn("schema_migrations", "version", "INT"), new RequiredColumn("schema_migrations", "applied_at", "BIGINT DEFAULT 0"),
            new RequiredColumn("credits", "id", "VARCHAR(36)"), new RequiredColumn("credits", "deal_name", "VARCHAR(256) DEFAULT ''"), new RequiredColumn("credits", "creditor", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("credits", "debtor", "VARCHAR(64) DEFAULT ''"),
            new RequiredColumn("credits", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credits", "paid_amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credits", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("credits", "due_date", "BIGINT"), new RequiredColumn("credits", "status", "VARCHAR(32) DEFAULT 'OPEN'"),
            new RequiredColumn("credits", "note", "CLOB"), new RequiredColumn("credits", "search_text", "CLOB"), new RequiredColumn("credits", "completed_at", "BIGINT"), new RequiredColumn("credits", "archived", "BOOLEAN NOT NULL DEFAULT FALSE"), new RequiredColumn("credits", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("payments", "id", "VARCHAR(36)"), new RequiredColumn("payments", "credit_id", "VARCHAR(36)"), new RequiredColumn("payments", "from_player", "VARCHAR(64)"), new RequiredColumn("payments", "to_player", "VARCHAR(64)"), new RequiredColumn("payments", "amount", "DOUBLE PRECISION DEFAULT 0"),
            new RequiredColumn("payments", "items_json", "CLOB"), new RequiredColumn("payments", "item_nbt", "CLOB"), new RequiredColumn("payments", "item_nbt_entries", "CLOB"), new RequiredColumn("payments", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("payments", "source", "VARCHAR(64)"), new RequiredColumn("payments", "paylog_id", "VARCHAR(36)"), new RequiredColumn("payments", "note", "CLOB"), new RequiredColumn("payments", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("credit_events", "id", "VARCHAR(36)"), new RequiredColumn("credit_events", "credit_id", "VARCHAR(36)"), new RequiredColumn("credit_events", "event_type", "VARCHAR(64)"), new RequiredColumn("credit_events", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "paid_after", "DOUBLE PRECISION DEFAULT 0"),
            new RequiredColumn("credit_events", "remaining_after", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("credit_events", "deal_name", "VARCHAR(256)"), new RequiredColumn("credit_events", "creditor", "VARCHAR(64)"), new RequiredColumn("credit_events", "debtor", "VARCHAR(64)"),
            new RequiredColumn("credit_events", "note", "CLOB"), new RequiredColumn("credit_events", "amount_before", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "amount_after", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("credit_events", "actor", "VARCHAR(64)"), new RequiredColumn("credit_events", "source", "VARCHAR(64)"), new RequiredColumn("credit_events", "item_payment", "BOOLEAN NOT NULL DEFAULT FALSE"), new RequiredColumn("credit_events", "revision", "BIGINT NOT NULL DEFAULT 0"),
            new RequiredColumn("paylogs", "id", "VARCHAR(36)"), new RequiredColumn("paylogs", "payer", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("paylogs", "receiver", "VARCHAR(64) DEFAULT ''"), new RequiredColumn("paylogs", "amount", "DOUBLE PRECISION DEFAULT 0"), new RequiredColumn("paylogs", "raw_text", "CLOB"),
            new RequiredColumn("paylogs", "normalized_text", "CLOB DEFAULT ''"), new RequiredColumn("paylogs", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("paylogs", "entry_hash", "VARCHAR(64)"), new RequiredColumn("paylogs", "source", "VARCHAR(64)"), new RequiredColumn("paylogs", "revision", "BIGINT NOT NULL DEFAULT 0"), new RequiredColumn("paylogs", "metadata", "CLOB"),
            new RequiredColumn("data_health_records", "id", "VARCHAR(36)"), new RequiredColumn("data_health_records", "record_type", "VARCHAR(64)"), new RequiredColumn("data_health_records", "severity", "VARCHAR(32)"), new RequiredColumn("data_health_records", "source_table", "VARCHAR(64)"), new RequiredColumn("data_health_records", "source_id", "VARCHAR(128)"),
            new RequiredColumn("data_health_records", "title", "VARCHAR(256)"), new RequiredColumn("data_health_records", "message", "CLOB"), new RequiredColumn("data_health_records", "raw_payload", "CLOB"), new RequiredColumn("data_health_records", "repair_payload", "CLOB"), new RequiredColumn("data_health_records", "status", "VARCHAR(32)"), new RequiredColumn("data_health_records", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("data_health_records", "resolved_at", "BIGINT"),
            new RequiredColumn("migration_log", "id", "VARCHAR(36)"), new RequiredColumn("migration_log", "migration_type", "VARCHAR(64)"), new RequiredColumn("migration_log", "started_at", "BIGINT DEFAULT 0"), new RequiredColumn("migration_log", "completed_at", "BIGINT"), new RequiredColumn("migration_log", "details", "CLOB"), new RequiredColumn("migration_log", "status", "VARCHAR(32)"),
            new RequiredColumn("legacy_records", "id", "VARCHAR(36)"), new RequiredColumn("legacy_records", "record_kind", "VARCHAR(64)"), new RequiredColumn("legacy_records", "original_id", "VARCHAR(256)"), new RequiredColumn("legacy_records", "raw_payload", "CLOB"), new RequiredColumn("legacy_records", "reason", "CLOB"), new RequiredColumn("legacy_records", "created_at", "BIGINT DEFAULT 0"), new RequiredColumn("legacy_records", "migration_id", "VARCHAR(36)")
    );
    private static final List<RequiredIndex> REQUIRED_INDICES = List.of(
            new RequiredIndex("credits", "idx_credits_status", "CREATE INDEX IF NOT EXISTS idx_credits_status ON credits(status)"), new RequiredIndex("credits", "idx_credits_parties", "CREATE INDEX IF NOT EXISTS idx_credits_parties ON credits(debtor, creditor)"), new RequiredIndex("credits", "idx_credits_completed", "CREATE INDEX IF NOT EXISTS idx_credits_completed ON credits(completed_at)"), new RequiredIndex("credits", "idx_credits_archived", "CREATE INDEX IF NOT EXISTS idx_credits_archived ON credits(archived, status)"), new RequiredIndex("credits", "idx_history_final", "CREATE INDEX IF NOT EXISTS idx_history_final ON credits(status, completed_at)"),
            new RequiredIndex("payments", "idx_payments_credit", "CREATE INDEX IF NOT EXISTS idx_payments_credit ON payments(credit_id)"), new RequiredIndex("payments", "idx_payments_paylog", "CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)"), new RequiredIndex("payments", "idx_payments_created", "CREATE INDEX IF NOT EXISTS idx_payments_created ON payments(created_at)"),
            new RequiredIndex("credit_events", "idx_events_credit", "CREATE INDEX IF NOT EXISTS idx_events_credit ON credit_events(credit_id)"), new RequiredIndex("credit_events", "idx_events_created", "CREATE INDEX IF NOT EXISTS idx_events_created ON credit_events(created_at)"),
            new RequiredIndex("paylogs", "idx_paylogs_hash", "CREATE INDEX IF NOT EXISTS idx_paylogs_hash ON paylogs(entry_hash)"), new RequiredIndex("paylogs", "idx_paylogs_created", "CREATE INDEX IF NOT EXISTS idx_paylogs_created ON paylogs(created_at)"), new RequiredIndex("paylogs", "idx_paylogs_parties", "CREATE INDEX IF NOT EXISTS idx_paylogs_parties ON paylogs(payer, receiver)"), new RequiredIndex("paylogs", "idx_paylogs_amount", "CREATE INDEX IF NOT EXISTS idx_paylogs_amount ON paylogs(amount)"),
            new RequiredIndex("data_health_records", "idx_health_status", "CREATE INDEX IF NOT EXISTS idx_health_status ON data_health_records(status, severity)"), new RequiredIndex("legacy_records", "idx_legacy_records_migration", "CREATE INDEX IF NOT EXISTS idx_legacy_records_migration ON legacy_records(migration_id)")
    );

    private boolean initialized;
    private boolean healthy = true;
    private boolean writeLocked;
    private boolean recovering;
    private Path initializedAt;

    private DatabaseManager() { }
    public static DatabaseManager getInstance() { return INSTANCE; }

    public synchronized void initialize() {
        FileManager.initialize();
        Path requestedPath = FileManager.getDatabaseFile().toAbsolutePath();
        if (initialized && requestedPath.equals(initializedAt) && healthy && !writeLocked) return;
        initialized = false;
        healthy = true;
        writeLocked = false;
        try {
            Class.forName("org.h2.Driver");
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                try {
                    ensureMetadataTable(connection);
                    int installed = installedSchemaVersion(connection);
                    if (installed > SCHEMA_VERSION) {
                        connection.rollback();
                        writeLocked = true;
                        healthy = false;
                        CreditManagerClient.LOGGER.error("CreditManager database schema {} is newer than supported {}. Writes are locked.", installed, SCHEMA_VERSION);
                    } else {
                        boolean repairedSchema = ensureRequiredSchemaObjects(connection);
                        for (int version = installed + 1; version <= SCHEMA_VERSION; version++) applyMigration(connection, version);
                        backfillCreditSearchText(connection);
                        backfillPaylogSearchText(connection);
                        validateRequiredSchema(connection);
                        repairSchemaMetadata(connection, repairedSchema);
                        connection.commit();
                    }
                } catch (Exception error) {
                    connection.rollback();
                    throw error;
                }
            }
            initialized = true;
            initializedAt = requestedPath;
            if (activeDatabaseIsUnexpectedlyEmpty()) {
                writeLocked = true;
                healthy = false;
                DataHealth.reportRecoveryRequired("Aktive Datenbank ist leer, obwohl ein Backup mit CreditManager-Daten vorhanden ist.");
                CreditManagerClient.LOGGER.error("CreditManager database is empty while a backup manifest reports domain data. Writes are locked.");
            }
        } catch (SchemaValidationException exception) {
            healthy = false;
            writeLocked = true;
            DataHealth.reportRecoveryRequired("Datenbank-Schema konnte nicht sicher repariert werden. Keine Daten wurden gelöscht.");
            CreditManagerClient.LOGGER.error("CreditManager database schema validation failed; writes are locked.", exception);
            initialized = true;
            initializedAt = requestedPath;
        } catch (Exception exception) {
            if (!recovering && Files.exists(FileManager.getDatabaseStorageFile()) && restoreLatestValidBackupInternal()) {
                initialized = false;
                initializedAt = null;
                healthy = true;
                writeLocked = false;
                initialize();
                return;
            }
            healthy = false;
            writeLocked = true;
            DataHealth.reportRecoveryRequired("Lokale Datenbank konnte nicht gelesen werden. Keine Daten wurden gelöscht.");
            CreditManagerClient.LOGGER.error("Could not initialise the local CreditManager database", exception);
            initialized = true;
            initializedAt = requestedPath;
        }
    }

    private void ensureMetadataTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            if (!columnExists(connection, "metadata", "meta_key")) statement.execute("ALTER TABLE metadata ADD COLUMN meta_key VARCHAR(128)");
            if (!columnExists(connection, "metadata", "meta_value")) statement.execute("ALTER TABLE metadata ADD COLUMN meta_value VARCHAR(4096)");
            if (!columnExists(connection, "schema_migrations", "version")) statement.execute("ALTER TABLE schema_migrations ADD COLUMN version INT");
            if (!columnExists(connection, "schema_migrations", "applied_at")) statement.execute("ALTER TABLE schema_migrations ADD COLUMN applied_at BIGINT DEFAULT 0");
        }
    }

    /** Repairs physical schema drift independently from metadata.schema_version. */
    private boolean ensureRequiredSchemaObjects(Connection connection) throws SQLException {
        boolean repaired = false;
        try (Statement statement = connection.createStatement()) {
            repaired |= ensureTable(connection, statement, "credits", "CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            repaired |= ensureTable(connection, statement, "payments", "CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL, CONSTRAINT fk_payment_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            repaired |= ensureTable(connection, statement, "credit_events", "CREATE TABLE credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_event_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            repaired |= ensureTable(connection, statement, "paylogs", "CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB)");
            repaired |= ensureTable(connection, statement, "data_health_records", "CREATE TABLE data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
            repaired |= ensureTable(connection, statement, "migration_log", "CREATE TABLE migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
            repaired |= ensureTable(connection, statement, "legacy_records", "CREATE TABLE legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
            for (RequiredColumn column : REQUIRED_COLUMNS) repaired |= ensureColumn(connection, statement, column);
            for (RequiredIndex index : REQUIRED_INDICES) repaired |= ensureIndex(connection, statement, index);
        }
        return repaired;
    }

    private boolean ensureTable(Connection connection, Statement statement, String table, String definition) throws SQLException {
        if (tableExists(connection, table)) return false;
        statement.execute(definition);
        return true;
    }

    private boolean ensureColumn(Connection connection, Statement statement, RequiredColumn column) throws SQLException {
        if (columnExists(connection, column.table(), column.name())) return false;
        statement.execute("ALTER TABLE " + column.table() + " ADD COLUMN " + column.name() + ' ' + column.definition());
        return true;
    }

    private boolean ensureIndex(Connection connection, Statement statement, RequiredIndex index) throws SQLException {
        if (indexExists(connection, index.table(), index.name())) return false;
        statement.execute(index.definition());
        return true;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME)=? AND LOWER(COLUMN_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            statement.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(TABLE_NAME)=? AND LOWER(INDEX_NAME)=? AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema', 'pg_catalog')")) {
            statement.setString(1, table.toLowerCase(Locale.ROOT));
            statement.setString(2, index.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void validateRequiredSchema(Connection connection) throws SQLException {
        for (String table : REQUIRED_TABLES) if (!tableExists(connection, table)) throw new SchemaValidationException("Required table is missing: " + table);
        for (RequiredColumn column : REQUIRED_COLUMNS) if (!columnExists(connection, column.table(), column.name())) throw new SchemaValidationException("Required column is missing: " + column.table() + '.' + column.name());
        for (RequiredIndex index : REQUIRED_INDICES) if (!indexExists(connection, index.table(), index.name())) throw new SchemaValidationException("Required index is missing: " + index.name());
        executeSmokeQuery(connection, "SELECT COUNT(*) FROM credits");
        executeSmokeQuery(connection, "SELECT COUNT(*) FROM payments");
        executeSmokeQuery(connection, "SELECT COUNT(*) FROM paylogs");
        executeSmokeQuery(connection, "SELECT p.id, p.metadata, COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=p.id), 0) AS linked_amount FROM paylogs p WHERE 1=0");
        executeSmokeQuery(connection, "SELECT id FROM credits WHERE (status IN ('PAID','CLOSED','CANCELLED') OR archived=TRUE) AND COALESCE(search_text,'') LIKE '%' LIMIT 1");
    }

    private void executeSmokeQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet ignored = statement.executeQuery()) { }
    }

    private void repairSchemaMetadata(Connection connection, boolean repairedSchema) throws SQLException {
        for (int version = 1; version <= SCHEMA_VERSION; version++) {
            try (PreparedStatement statement = connection.prepareStatement("MERGE INTO schema_migrations (version, applied_at) KEY(version) VALUES (?, ?)")) {
                statement.setInt(1, version);
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
        if (metadata(connection, "data_revision") == null) putMetadata(connection, "data_revision", "0");
        putMetadata(connection, "schema_version", String.valueOf(SCHEMA_VERSION));
        if (repairedSchema) storeHealth(connection, "SCHEMA_DRIFT_REPAIRED", "INFO", "metadata", "schema_version", "Schemaabweichung repariert", "Fehlende Tabellen, Spalten oder Indizes wurden ohne das Löschen von Nutzerdaten ergänzt.", null, null);
    }

    private int installedSchemaVersion(Connection connection) throws SQLException {
        String value = metadata(connection, "schema_version");
        if (value != null) {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
        }
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private void applyMigration(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            switch (version) {
                case 1 -> createInitialSchema(statement);
                case 2 -> {
                    statement.execute("ALTER TABLE data_health_records ADD COLUMN IF NOT EXISTS repair_payload CLOB");
                    statement.execute("ALTER TABLE paylogs ADD COLUMN IF NOT EXISTS metadata CLOB");
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_health_status ON data_health_records(status, severity)");
                }
                case 3 -> {
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_hash ON paylogs(entry_hash)");
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_history_final ON credits(status, completed_at)");
                }
                case 4 -> {
                    statement.execute("CREATE TABLE IF NOT EXISTS legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_legacy_records_migration ON legacy_records(migration_id)");
                }
                case 5 -> {
                    statement.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS paylog_id VARCHAR(36)");
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)");
                }
                case 6 -> {
                    statement.execute("ALTER TABLE credits ADD COLUMN IF NOT EXISTS search_text CLOB");
                    statement.execute("ALTER TABLE payments ADD COLUMN IF NOT EXISTS note CLOB");
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_archived ON credits(archived, status)");
                }
                default -> throw new SQLException("Unknown CreditManager schema migration " + version);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO schema_migrations (version, applied_at) KEY(version) VALUES (?, ?)")) {
            statement.setInt(1, version);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void createInitialSchema(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB, search_text CLOB, completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64), paylog_id VARCHAR(36), note CLOB, revision BIGINT NOT NULL, CONSTRAINT fk_payment_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_event_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
        statement.execute("CREATE TABLE IF NOT EXISTS paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
        statement.execute("CREATE TABLE IF NOT EXISTS migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
        statement.execute("CREATE TABLE IF NOT EXISTS legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_status ON credits(status)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_parties ON credits(debtor, creditor)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_credits_completed ON credits(completed_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_credit ON payments(credit_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_paylog ON payments(paylog_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_payments_created ON payments(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_credit ON credit_events(credit_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_events_created ON credit_events(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_created ON paylogs(created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_parties ON paylogs(payer, receiver)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_paylogs_amount ON paylogs(amount)");
    }

    private void backfillCreditSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM credits");
             ResultSet result = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE credits SET search_text=? WHERE id=? AND (search_text IS NULL OR search_text<>?)")) {
            while (result.next()) {
                try {
                    CreditEntry entry = new CreditEntry();
                    entry.setId(UUID.fromString(result.getString("id"))); entry.setDealName(result.getString("deal_name")); entry.setCreditor(result.getString("creditor")); entry.setDebtor(result.getString("debtor")); entry.setAmount(result.getDouble("amount")); entry.setPaidAmount(result.getDouble("paid_amount")); entry.setCreatedAt(result.getLong("created_at")); long due = result.getLong("due_date"); entry.setDueDate(result.wasNull() ? null : due); entry.setStatus(result.getString("status")); entry.setNote(result.getString("note")); long completed = result.getLong("completed_at"); entry.setCompletedAt(result.wasNull() ? null : completed); entry.setArchived(result.getBoolean("archived"));
                    String searchText = DealSearchText.build(entry);
                    update.setString(1, searchText); update.setString(2, entry.getId().toString()); update.setString(3, searchText); update.addBatch();
                } catch (RuntimeException rowError) {
                    storeHealth(connection, "CREDIT_SEARCH_BACKFILL", "WARNING", "credits", result.getString("id"), "Suchtext konnte nicht ergänzt werden", "Dieser Deal bleibt erhalten und kann weiterhin über klassische Felder gesucht werden.", rowPayload(result), null);
                }
            }
            update.executeBatch();
        }
    }

    private void backfillPaylogSearchText(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT * FROM paylogs");
             ResultSet result = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE paylogs SET normalized_text=? WHERE id=? AND normalized_text<>?")) {
            while (result.next()) {
                TransactionEntry entry = new TransactionEntry();
                entry.setId(UUID.fromString(result.getString("id"))); entry.setFromPlayer(result.getString("payer")); entry.setToPlayer(result.getString("receiver")); entry.setAmount(result.getDouble("amount")); entry.setRawText(result.getString("raw_text")); entry.setTimestamp(result.getLong("created_at")); entry.setSource(result.getString("source")); entry.setMetadata(result.getString("metadata"));
                String searchText = PaylogSearchText.build(entry);
                update.setString(1, searchText); update.setString(2, entry.getId().toString()); update.setString(3, searchText); update.addBatch();
            }
            update.executeBatch();
        }
    }

    public synchronized boolean isHealthy() { return healthy && !writeLocked; }
    public synchronized boolean isWriteLocked() { return writeLocked; }
    public synchronized boolean isSafeForWrites() {
        if (!isHealthy()) return false;
        try (Connection connection = connection()) {
            return !hasOpenHealthErrors(connection);
        } catch (SQLException exception) {
            healthy = false;
            return false;
        }
    }
    public synchronized long revision() {
        initialize();
        try (Connection connection = connection()) {
            String revision = metadata(connection, "data_revision");
            return revision == null ? 0L : Long.parseLong(revision);
        } catch (Exception exception) { return 0L; }
    }

    public synchronized boolean hasDomainData() {
        initialize();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM credits) + (SELECT COUNT(*) FROM payments) + (SELECT COUNT(*) FROM credit_events) + (SELECT COUNT(*) FROM paylogs)")) {
            result.next();
            return result.getLong(1) > 0;
        } catch (SQLException exception) { return false; }
    }

    public synchronized boolean hasCompletedJsonMigration() {
        initialize();
        try (Connection connection = connection()) { return "COMPLETED".equals(metadata(connection, "json_migration_status")); }
        catch (SQLException exception) { return false; }
    }

    public synchronized boolean hasCompletedAutomaticJsonMigration() {
        initialize();
        try (Connection connection = connection()) { return "2".equals(metadata(connection, "json_auto_migration_version")); }
        catch (SQLException exception) { return false; }
    }

    public synchronized boolean createBackup() {
        initialize();
        Path source = FileManager.getDatabaseStorageFile();
        if (!Files.exists(source)) return false;
        Path target = FileManager.getBackupDirectory().resolve("creditmanager_backup_" + System.currentTimeMillis() + ".zip");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            Files.createDirectories(target.getParent());
            statement.execute("BACKUP TO '" + escapeSqlLiteral(target.toAbsolutePath().normalize().toString()) + "'");
            BackupValidation validation = validateBackup(target);
            if (!validation.valid()) {
                Files.deleteIfExists(target);
                return false;
            }
            appendBackupManifest(new BackupManifestEntry(target.getFileName().toString(), System.currentTimeMillis(), SCHEMA_VERSION,
                    revision(connection), validation.creditCount(), validation.paymentCount(), validation.paylogCount(), validation.eventCount(),
                    !hasOpenHealthErrors(connection), "h2-backup"));
            return true;
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not create CreditManager database backup", exception);
            return false;
        }
    }

    public synchronized DatabaseState loadCreditState() {
        initialize();
        try (Connection connection = connection()) {
            List<CreditEntry> credits = readCredits(connection, "SELECT * FROM credits", List.of());
            List<Payment> payments = loadPayments(connection);
            for (CreditEntry credit : credits) {
                credit.replacePayments(payments.stream().filter(payment -> credit.getId().equals(payment.getCreditId())).toList());
            }
            return new DatabaseState(credits, payments, loadEvents(connection));
        } catch (SQLException exception) {
            healthy = false;
            throw new IllegalStateException("CreditManager-Datenbank konnte nicht gelesen werden.", exception);
        }
    }

    public synchronized boolean replaceCreditState(Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) {
        if (credits == null || payments == null || events == null) return false;
        return inTransaction(connection -> {
            if (hasDomainData(connection) && credits.isEmpty()) {
                throw new SQLException("Refusing to replace non-empty CreditManager data with an empty state");
            }
            if (hasOpenHealthErrors(connection)) {
                throw new SQLException("Refusing full-state replacement while unresolved data-health errors exist");
            }
            validateState(connection, credits, payments, events);
            long rowRevision = nextRevision(connection);
            for (CreditEntry credit : credits) upsertCredit(connection, credit, rowRevision);
            for (Payment payment : payments) upsertPayment(connection, payment, rowRevision);
            for (CreditEventEntry event : events) upsertEvent(connection, event, rowRevision);
            bumpRevision(connection);
        });
    }

    /**
     * Commits exactly one credit mutation. Unlike the legacy snapshot writer,
     * it can never remove unrelated deals, payments or events.
     */
    public synchronized boolean commitCreditMutation(CreditMutation mutation) {
        if (!isValidMutation(mutation)) return false;
        return inTransaction(connection -> {
            long rowRevision = nextRevision(connection);
            applyCreditMutation(connection, mutation, rowRevision);
            bumpRevision(connection);
        });
    }

    /** Applies independent credit mutations in one transaction and increments the data revision once. */
    public synchronized boolean commitCreditMutationsBatch(Collection<CreditMutation> mutations) {
        if (mutations == null || mutations.isEmpty()) return false;
        List<CreditMutation> values = List.copyOf(mutations);
        if (values.stream().anyMatch(mutation -> !isValidMutation(mutation))) return false;
        return inTransaction(connection -> {
            long rowRevision = nextRevision(connection);
            for (CreditMutation mutation : values) applyCreditMutation(connection, mutation, rowRevision);
            bumpRevision(connection);
        });
    }

    private boolean isValidMutation(CreditMutation mutation) {
        return mutation != null && mutation.credit() != null && mutation.credit().getId() != null;
    }

    private void applyCreditMutation(Connection connection, CreditMutation mutation, long rowRevision) throws SQLException {
        List<Payment> upserts = mutation.paymentUpserts() == null ? List.of() : List.copyOf(mutation.paymentUpserts());
        List<UUID> deletions = mutation.paymentDeletions() == null ? List.of() : List.copyOf(mutation.paymentDeletions());
        List<CreditEventEntry> events = mutation.events() == null ? List.of() : List.copyOf(mutation.events());
        for (Payment payment : upserts) {
            if (!isValidPayment(payment) || !mutation.credit().getId().equals(payment.getCreditId())) throw new SQLException("Invalid payment mutation");
            validatePaylogPaymentLink(connection, payment);
        }
        for (CreditEventEntry event : events) {
            if (!isValidEvent(event) || !mutation.credit().getId().equals(event.getCreditId())) throw new SQLException("Invalid credit event mutation");
        }

        upsertCredit(connection, mutation.credit(), rowRevision);
        for (UUID paymentId : deletions) {
            if (paymentId == null) throw new SQLException("Invalid payment deletion");
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM payments WHERE id=? AND credit_id=?")) {
                statement.setString(1, paymentId.toString());
                statement.setString(2, mutation.credit().getId().toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Payment no longer exists");
            }
        }
        for (Payment payment : upserts) upsertPayment(connection, payment, rowRevision);

        List<Payment> persistedPayments = loadPaymentsForCredit(connection, mutation.credit().getId());
        validateDerivedCreditState(mutation.credit(), persistedPayments.stream().mapToDouble(payment -> payment.getAmount()).sum());
        validatePersistedPaylogLinks(connection, persistedPayments);
        for (CreditEventEntry event : events) upsertEvent(connection, event, rowRevision);
    }

    public synchronized boolean importLegacy(DatabaseState state, Collection<TransactionEntry> paylogs, String details) {
        if (state == null || paylogs == null) return false;
        return inTransaction(connection -> {
            if ("COMPLETED".equals(metadata(connection, "json_migration_status"))) return;
            if (hasDomainData(connection)) throw new SQLException("Existing CreditManager data must not be overwritten by JSON migration");
            validateState(connection, state.credits(), state.payments(), state.events());
            long rowRevision = nextRevision(connection);
            for (CreditEntry credit : state.credits()) upsertCredit(connection, credit, rowRevision);
            for (Payment payment : state.payments()) upsertPayment(connection, payment, rowRevision);
            for (CreditEventEntry event : state.events()) upsertEvent(connection, event, rowRevision);
            for (TransactionEntry entry : paylogs) insertPaylog(connection, entry, true, rowRevision);
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_log (id, migration_type, started_at, completed_at, details, status) VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, "JSON_V1");
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.setString(5, details == null ? "" : details);
                statement.setString(6, "COMPLETED");
                statement.executeUpdate();
            }
            putMetadata(connection, "json_migration_status", "COMPLETED");
            bumpRevision(connection);
        });
    }

    public synchronized AutomaticMigrationResult importLegacyAutomatically(DatabaseState state, Collection<TransactionEntry> paylogs,
                                                                              Collection<LegacyRecord> preserved, String details) {
        if (state == null || paylogs == null || preserved == null) return AutomaticMigrationResult.failed();
        int[] counts = new int[5];
        boolean committed = inTransaction(connection -> {
            String migrationId = UUID.randomUUID().toString();
            long rowRevision = nextRevision(connection);
            for (CreditEntry credit : state.credits()) {
                if (!rowExists(connection, "credits", credit.getId().toString())) {
                    upsertCredit(connection, credit, rowRevision);
                    counts[0]++;
                } else if (!sameCredit(connection, credit)) {
                    preserveLegacyRecord(connection, new LegacyRecord("CREDIT", credit.getId().toString(), GSON.toJson(credit),
                            "Abweichender JSON-Deal mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (Payment payment : state.payments()) {
                if (!rowExists(connection, "payments", payment.getId().toString())) {
                    upsertPayment(connection, payment, rowRevision);
                    counts[1]++;
                } else if (!samePayment(connection, payment)) {
                    preserveLegacyRecord(connection, new LegacyRecord("PAYMENT", payment.getId().toString(), GSON.toJson(payment),
                            "Abweichende JSON-Zahlung mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (CreditEventEntry event : state.events()) {
                if (!rowExists(connection, "credit_events", event.getId().toString())) {
                    upsertEvent(connection, event, rowRevision);
                    counts[2]++;
                } else if (!sameEvent(connection, event)) {
                    preserveLegacyRecord(connection, new LegacyRecord("EVENT", event.getId().toString(), GSON.toJson(event),
                            "Abweichendes JSON-Event mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                }
            }
            for (TransactionEntry paylog : paylogs) {
                if (paylog.getId() != null && rowExists(connection, "paylogs", paylog.getId().toString())) {
                    if (!samePaylog(connection, paylog)) {
                        preserveLegacyRecord(connection, new LegacyRecord("PAYLOG", paylog.getId().toString(), GSON.toJson(paylog),
                                "Abweichender JSON-Paylog mit identischer UUID; die vorhandene Datenbankzeile bleibt maßgeblich."), migrationId, counts);
                    }
                } else if (insertPaylog(connection, paylog, true, rowRevision)) counts[3]++;
            }
            for (LegacyRecord record : preserved) {
                preserveLegacyRecord(connection, record, migrationId, counts);
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO migration_log (id, migration_type, started_at, completed_at, details, status) VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, migrationId);
                statement.setString(2, "JSON_AUTO_V2");
                statement.setLong(3, now);
                statement.setLong(4, now);
                statement.setString(5, details == null ? "" : details);
                statement.setString(6, "COMPLETED");
                statement.executeUpdate();
            }
            putMetadata(connection, "json_migration_status", "COMPLETED");
            putMetadata(connection, "json_auto_migration_version", "2");
            bumpRevision(connection);
        });
        return committed ? new AutomaticMigrationResult(true, counts[0], counts[1], counts[2], counts[3], counts[4]) : AutomaticMigrationResult.failed();
    }

    public synchronized int legacyRecordCount() {
        initialize();
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM legacy_records")) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) { return 0; }
    }

    public synchronized boolean addPaylog(TransactionEntry entry) {
        if (!isValidPaylog(entry)) return false;
        return inTransaction(connection -> {
            if (!insertPaylog(connection, entry, true, nextRevision(connection))) throw new DuplicatePaylogException();
            bumpRevision(connection);
        });
    }

    public synchronized boolean resetCorruptDatabaseWithBackup() {
        boolean restored = restoreLatestValidBackupInternal();
        if (!restored) {
            healthy = false;
            writeLocked = true;
            DataHealth.reportRecoveryRequired("Keine valide Datenbank-Sicherung zur Wiederherstellung gefunden. Die aktive Datenbank wurde nicht gelöscht.");
            return false;
        }
        initialized = false;
        initializedAt = null;
        healthy = true;
        writeLocked = false;
        try {
            initialize();
            return isHealthy();
        } catch (RuntimeException exception) {
            healthy = false;
            writeLocked = true;
            return false;
        }
    }

    public synchronized boolean restoreLatestValidBackup() {
        boolean restored = restoreLatestValidBackupInternal();
        if (!restored) return false;
        initialized = false;
        initializedAt = null;
        healthy = true;
        writeLocked = false;
        try {
            initialize();
            return isHealthy();
        } catch (RuntimeException exception) {
            healthy = false;
            writeLocked = true;
            return false;
        }
    }

    public synchronized List<BackupManifestEntry> listBackups() {
        List<BackupManifestEntry> backups = new ArrayList<>(readBackupManifest());
        for (Path legacy : legacyDatabaseBackups()) {
            BackupValidation validation = validateDatabaseFile(legacy);
            if (validation.valid()) {
                backups.add(new BackupManifestEntry(legacy.getFileName().toString(), modifiedAt(legacy), 0, 0,
                        validation.creditCount(), validation.paymentCount(), validation.paylogCount(), validation.eventCount(), true, "legacy-h2"));
            }
        }
        backups.sort(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed());
        return List.copyOf(backups);
    }

    private boolean activeDatabaseIsUnexpectedlyEmpty() {
        try (Connection connection = connection()) {
            if (hasDomainData(connection)) return false;
            boolean manifestBackup = readBackupManifest().stream()
                    .anyMatch(entry -> entry.domainCount() > 0 && Files.isRegularFile(FileManager.getBackupDirectory().resolve(entry.fileName())));
            return manifestBackup || legacyDatabaseBackups().stream()
                    .map(this::validateDatabaseFile)
                    .anyMatch(validation -> validation.valid() && validation.domainCount() > 0);
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean restoreLatestValidBackupInternal() {
        if (recovering) return false;
        recovering = true;
        try {
            for (BackupManifestEntry entry : readBackupManifest().stream()
                    .sorted(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed()).toList()) {
                Path archive = FileManager.getBackupDirectory().resolve(entry.fileName());
                BackupValidation validation = validateBackup(archive);
                if (!validation.valid() || validation.domainCount() < entry.domainCount()) continue;
                if (!restoreBackupArchive(archive)) continue;
                DataHealth.reportRecoveryRequired("Valide Datenbanksicherung wurde wiederhergestellt; die vorherige Datenbank liegt in der Quarantäne.");
                return true;
            }
            for (Path legacy : legacyDatabaseBackups()) {
                BackupValidation validation = validateDatabaseFile(legacy);
                if (!validation.valid() || !restoreLegacyDatabaseFile(legacy)) continue;
                DataHealth.reportRecoveryRequired("Valide Legacy-Datenbanksicherung wurde wiederhergestellt; die vorherige Datenbank liegt in der Quarantäne.");
                return true;
            }
            return false;
        } finally {
            recovering = false;
        }
    }

    private boolean restoreBackupArchive(Path archive) {
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("restore-" + UUID.randomUUID());
        try {
            Path extracted = extractBackup(archive, staging);
            Path databaseFile = findDatabaseFile(extracted);
            if (databaseFile == null || !validateDatabaseFile(databaseFile).valid()) return false;
            Path active = FileManager.getDatabaseStorageFile();
            if (Files.exists(active) && !quarantineActiveDatabase(active)) return false;
            Files.createDirectories(active.getParent());
            moveWithoutReplacing(databaseFile, active);
            return true;
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not restore CreditManager backup {}", archive, exception);
            return false;
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private boolean restoreLegacyDatabaseFile(Path legacy) {
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("legacy-restore-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            Path copy = staging.resolve("creditmanager.mv.db");
            Files.copy(legacy, copy);
            if (!validateDatabaseFile(copy).valid()) return false;
            Path active = FileManager.getDatabaseStorageFile();
            if (Files.exists(active) && !quarantineActiveDatabase(active)) return false;
            moveWithoutReplacing(copy, active);
            return true;
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not restore legacy CreditManager database backup {}", legacy, exception);
            return false;
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private boolean quarantineActiveDatabase(Path active) {
        try {
            Files.createDirectories(FileManager.getQuarantineDirectory());
            String name = "creditmanager_" + System.currentTimeMillis() + ".mv.db";
            moveWithoutReplacing(active, FileManager.getQuarantineDirectory().resolve(name));
            return true;
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.error("Could not quarantine active CreditManager database", exception);
            return false;
        }
    }

    private BackupValidation validateBackup(Path archive) {
        if (archive == null || !Files.isRegularFile(archive)) return BackupValidation.invalid();
        Path staging = FileManager.getRecoveryValidationDirectory().resolve("validate-" + UUID.randomUUID());
        try {
            Path extracted = extractBackup(archive, staging);
            Path databaseFile = findDatabaseFile(extracted);
            return databaseFile == null ? BackupValidation.invalid() : validateDatabaseFile(databaseFile);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        } finally {
            deleteTemporaryTree(staging);
        }
    }

    private Path extractBackup(Path archive, Path staging) throws IOException {
        Files.createDirectories(staging);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path target = staging.resolve(entry.getName()).normalize();
                if (!target.startsWith(staging)) throw new IOException("Unsafe backup entry");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(target)) {
                        input.transferTo(output);
                    }
                }
            }
        }
        return staging;
    }

    private Path findDatabaseFile(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".mv.db"))
                    .findFirst().orElse(null);
        }
    }

    private BackupValidation validateDatabaseFile(Path databaseFile) {
        String name = databaseFile.getFileName().toString();
        if (!name.endsWith(".mv.db")) return BackupValidation.invalid();
        Path base = databaseFile.resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
        try (Connection connection = openConnection(base); Statement statement = connection.createStatement()) {
            int credits = countRows(statement, "credits");
            int payments = countRows(statement, "payments");
            int paylogs = countRows(statement, "paylogs");
            int events = countRows(statement, "credit_events");
            return new BackupValidation(true, credits, payments, paylogs, events);
        } catch (Exception exception) {
            return BackupValidation.invalid();
        }
    }

    private int countRows(Statement statement, String table) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private List<BackupManifestEntry> readBackupManifest() {
        Path manifest = FileManager.getBackupManifestFile();
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            List<BackupManifestEntry> entries = GSON.fromJson(Files.readString(manifest), BACKUP_MANIFEST_LIST);
            return entries == null ? List.of() : entries.stream().filter(entry -> entry != null && entry.fileName() != null).toList();
        } catch (Exception exception) {
            CreditManagerClient.LOGGER.warn("Could not read CreditManager backup manifest", exception);
            return List.of();
        }
    }

    private List<Path> legacyDatabaseBackups() {
        Path directory = FileManager.getBackupDirectory();
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mv.db"))
                    .sorted(Comparator.comparingLong(this::modifiedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private void appendBackupManifest(BackupManifestEntry entry) throws IOException {
        List<BackupManifestEntry> entries = new ArrayList<>(readBackupManifest());
        entries.add(entry);
        entries.sort(Comparator.comparingLong(BackupManifestEntry::createdAt).reversed());
        if (entries.size() > 32) entries = new ArrayList<>(entries.subList(0, 32));
        Path manifest = FileManager.getBackupManifestFile();
        Files.createDirectories(manifest.getParent());
        Path temporary = manifest.resolveSibling(manifest.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(entries), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailed) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void moveWithoutReplacing(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(source, target);
        }
    }

    private void deleteTemporaryTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private long modifiedAt(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ignored) { return 0L; }
    }

    public synchronized Optional<TransactionEntry> findPaylog(UUID id) {
        if (id == null) return Optional.empty();
        initialize();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("SELECT p.id, p.payer, p.receiver, p.amount, p.raw_text, p.normalized_text, p.created_at, p.entry_hash, p.source, p.metadata, COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=p.id), 0) AS linked_amount FROM paylogs p WHERE p.id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readPaylog(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw queryFailure("Paylog konnte nicht aus der Datenbank geladen werden.", exception);
        }
    }

    public synchronized int addPaylogsBatch(Collection<TransactionEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        List<TransactionEntry> values = new ArrayList<>(entries);
        if (values.stream().anyMatch(entry -> !isValidPaylog(entry))) return 0;
        final int[] inserted = {0};
        boolean committed = inTransaction(connection -> {
            long rowRevision = nextRevision(connection);
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO paylogs (id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, revision, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (TransactionEntry entry : values) {
                    String normalized = entry.getNormalizedText();
                    if (blank(normalized)) normalized = PaylogSearchText.build(entry);
                    String hash = blank(entry.getHash()) ? paylogHash(entry, normalized) : entry.getHash();
                    statement.setString(1, entry.getId() == null ? UUID.randomUUID().toString() : entry.getId().toString());
                    statement.setString(2, entry.getFromPlayer()); statement.setString(3, entry.getToPlayer()); statement.setDouble(4, entry.getAmount()); statement.setString(5, entry.getRawText()); statement.setString(6, normalized); statement.setLong(7, entry.getTimestamp()); statement.setString(8, hash); statement.setString(9, blank(entry.getSource()) ? "DETECTED" : entry.getSource()); statement.setLong(10, rowRevision); statement.setString(11, entry.getMetadata());
                    statement.addBatch();
                    entry.setNormalizedText(normalized); entry.setHash(hash);
                }
                statement.executeBatch();
                inserted[0] = values.size();
            }
            bumpRevision(connection);
        });
        return committed ? inserted[0] : 0;
    }

    public synchronized int clearDevTestData() {
        final int[] removed = {0};
        boolean committed = inTransaction(connection -> {
            try (PreparedStatement paylogs = connection.prepareStatement("DELETE FROM paylogs WHERE source='TEST_DATA' OR raw_text LIKE 'TEST_DATA%'");
                 PreparedStatement credits = connection.prepareStatement("DELETE FROM credits WHERE note='TEST_DATA' AND LOWER(deal_name) LIKE '%test_%'")) {
                removed[0] += paylogs.executeUpdate();
                removed[0] += credits.executeUpdate();
            }
            bumpRevision(connection);
        });
        return committed ? removed[0] : 0;
    }

    public synchronized List<TransactionEntry> queryPaylogs(String player, int direction, String query, int limit, int offset) {
        return queryPaylogPage(player, direction, query, limit, offset).entries();
    }

    public synchronized QueryPage<TransactionEntry> queryPaylogPage(String player, int direction, String query, int limit, int offset) {
        initialize();
        int pageSize = Math.max(1, Math.min(PAGE_SIZE, limit));
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<String> strings = new ArrayList<>();
        if (player != null && !player.isBlank()) {
            String lower = player.toLowerCase(Locale.ROOT);
            where.append(" AND (LOWER(payer)=? OR LOWER(receiver)=?)"); strings.add(lower); strings.add(lower);
            if (direction == 1) { where.append(" AND LOWER(receiver)=?"); strings.add(lower); }
            if (direction == 2) { where.append(" AND LOWER(payer)=?"); strings.add(lower); }
        }
        for (String token : DealSearchText.tokens(query)) {
            appendPaylogSearchToken(where, strings, token);
        }
        try (Connection connection = connection()) {
            long count = count(connection, "SELECT COUNT(*) FROM paylogs" + where, strings);
            String sql = "SELECT id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, metadata, COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id), 0) AS linked_amount FROM paylogs" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindStrings(statement, strings);
                statement.setInt(strings.size() + 1, pageSize);
                statement.setInt(strings.size() + 2, Math.max(0, offset));
                try (ResultSet result = statement.executeQuery()) {
                    List<TransactionEntry> entries = new ArrayList<>();
                    while (result.next()) entries.add(readPaylog(result));
                    return new QueryPage<>(List.copyOf(entries), count, offset, pageSize);
                }
            }
        } catch (SQLException exception) {
            throw queryFailure("Paylogs konnten nicht aus der Datenbank geladen werden.", exception);
        }
    }

    public synchronized QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, int limit, int offset) {
        return queryDealHistoryPage(player, query, false, DealHistorySort.NEWEST, limit, offset);
    }

    public synchronized QueryPage<TransactionEntry> queryAvailablePaylogs(String payer, String receiver, String query, int limit, int offset) {
        initialize();
        int pageSize = Math.max(1, Math.min(PAGE_SIZE, limit));
        String linked = "COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0)";
        StringBuilder where = new StringBuilder(" WHERE LOWER(payer)=? AND LOWER(receiver)=? AND " + linked + "<amount-0.0001");
        List<String> values = new ArrayList<>(); values.add(safe(payer).toLowerCase(Locale.ROOT)); values.add(safe(receiver).toLowerCase(Locale.ROOT));
        for (String token : DealSearchText.tokens(query)) appendPaylogSearchToken(where, values, token);
        try (Connection connection = connection()) {
            long count = count(connection, "SELECT COUNT(*) FROM paylogs" + where, values);
            String sql = "SELECT id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, metadata, " + linked + " AS linked_amount FROM paylogs" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindStrings(statement, values); statement.setInt(values.size() + 1, pageSize); statement.setInt(values.size() + 2, Math.max(0, offset));
                try (ResultSet result = statement.executeQuery()) {
                    List<TransactionEntry> entries = new ArrayList<>(); while (result.next()) entries.add(readPaylog(result));
                    return new QueryPage<>(List.copyOf(entries), count, Math.max(0, offset), pageSize);
                }
            }
        } catch (SQLException exception) {
            throw queryFailure("Verfügbare Paylogs konnten nicht aus der Datenbank geladen werden.", exception);
        }
    }

    private void appendPaylogSearchToken(StringBuilder where, List<String> values, String token) {
        String linked = "COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=paylogs.id),0)";
        switch (token) {
            case "verknupft" -> where.append(" AND ").append(linked).append(">=amount-0.0001");
            case "teilweise" -> where.append(" AND ").append(linked).append(">0.0001 AND ").append(linked).append("<amount-0.0001");
            case "offen", "rest" -> where.append(" AND ").append(linked).append("<amount-0.0001");
            case "manual", "manuell" -> { where.append(" AND UPPER(COALESCE(source,''))='MANUAL'"); }
            case "detected", "erkannt" -> { where.append(" AND UPPER(COALESCE(source,''))='DETECTED'"); }
            default -> {
                where.append(" AND (COALESCE(normalized_text,'') LIKE ? ESCAPE '!' OR LOWER(COALESCE(source,'')) LIKE ? ESCAPE '!')");
                String like = '%' + escapeLike(token) + '%'; values.add(like); values.add(like);
            }
        }
    }

    public synchronized QueryPage<CreditEntry> queryDealHistoryPage(String player, String query, boolean includeArchived,
                                                                      DealHistorySort sort, int limit, int offset) {
        initialize();
        int pageSize = Math.max(1, Math.min(PAGE_SIZE, limit));
        String lowerPlayer = player == null ? "" : player.toLowerCase(Locale.ROOT);
        StringBuilder where = new StringBuilder(" WHERE (status IN ('PAID','CLOSED','CANCELLED') OR archived=TRUE) AND (LOWER(debtor)=? OR LOWER(creditor)=?)");
        List<String> values = new ArrayList<>(); values.add(lowerPlayer); values.add(lowerPlayer);
        if (!includeArchived) where.append(" AND archived=FALSE");
        for (String token : DealSearchText.tokens(query)) {
            where.append(" AND (COALESCE(search_text,'') LIKE ? ESCAPE '!' OR LOWER(COALESCE(deal_name,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(debtor,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(creditor,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(status,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(note,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(id,'')) LIKE ? ESCAPE '!')");
            String like = '%' + escapeLike(token) + '%';
            for (int index = 0; index < 7; index++) values.add(like);
        }
        String order = historyOrder(sort == null ? DealHistorySort.NEWEST : sort);
        try (Connection connection = connection()) {
            long count = count(connection, "SELECT COUNT(*) FROM credits" + where, values);
            List<CreditEntry> entries = readCredits(connection, "SELECT * FROM credits" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", values, pageSize, Math.max(0, offset));
            return new QueryPage<>(List.copyOf(entries), count, Math.max(0, offset), pageSize);
        } catch (SQLException exception) {
            throw queryFailure("Deal-History konnte nicht aus der Datenbank geladen werden.", exception);
        }
    }

    public synchronized List<CreditEntry> queryDealHistory(String player, String query, int limit, int offset) {
        return queryDealHistoryPage(player, query, limit, offset).entries();
    }

    private String historyOrder(DealHistorySort sort) {
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

    public synchronized List<DataHealthRecord> runHealthCheck() {
        initialize();
        if (writeLocked) return List.of();
        inTransaction(connection -> {
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
            if (installedSchemaVersion(connection) != SCHEMA_VERSION) storeHealth(connection, "SCHEMA_VERSION", "ERROR", "metadata", "schema_version", "Unerwartete Schemaversion", "Die gespeicherte Schemaversion passt nicht zur Anwendung.", null, null);
        });
        return listHealthRecords(false);
    }

    public synchronized List<DataHealthRecord> listHealthRecords(boolean includeResolved) {
        initialize();
        String sql = "SELECT * FROM data_health_records" + (includeResolved ? "" : " WHERE status='OPEN'") + " ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END, created_at DESC";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            List<DataHealthRecord> records = new ArrayList<>();
            while (result.next()) records.add(readHealth(result));
            return List.copyOf(records);
        } catch (SQLException exception) {
            CreditManagerClient.LOGGER.warn("Could not load data-health records", exception);
            return List.of();
        }
    }

    public synchronized boolean resolveHealthRecord(UUID id, String repairPayload, boolean ignored) {
        if (id == null) return false;
        return inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE data_health_records SET status=?, repair_payload=?, resolved_at=? WHERE id=? AND status='OPEN'")) {
                statement.setString(1, ignored ? "IGNORED" : "RESOLVED");
                statement.setString(2, repairPayload);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, id.toString());
                if (statement.executeUpdate() != 1) throw new SQLException("Health finding no longer open");
            }
            bumpRevision(connection);
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
                storeHealth(connection, "PAYLOG_LINK_AMOUNT", "ERROR", "payments", id, "Ungültiger Paylog-Betrag", "Eine einzelne Zahlung ist größer als ihr Paylog.", rowPayload(result), null);
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
                    storeHealth(connection, "PAYLOG_LINK_OVERBOOKED", "ERROR", "paylogs", id, "Paylog überbucht", "Die Summe verknüpfter Zahlungen ist größer als der Paylog-Betrag.", rowPayload(result), null);
                }
            }
        }
    }

    private void storeHealth(Connection connection, String type, String severity, String table, String sourceId, String title, String message, String raw, String repair) throws SQLException {
        try (PreparedStatement check = connection.prepareStatement("SELECT id FROM data_health_records WHERE record_type=? AND COALESCE(source_table,'')=COALESCE(?, '') AND COALESCE(source_id,'')=COALESCE(?, '') AND status='OPEN'")) {
            check.setString(1, type); check.setString(2, table); check.setString(3, sourceId);
            try (ResultSet result = check.executeQuery()) { if (result.next()) return; }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO data_health_records (id, record_type, severity, source_table, source_id, title, message, raw_payload, repair_payload, status, created_at, resolved_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, NULL)")) {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, type); statement.setString(3, severity); statement.setString(4, table); statement.setString(5, sourceId); statement.setString(6, title); statement.setString(7, message); statement.setString(8, raw); statement.setString(9, repair); statement.setLong(10, System.currentTimeMillis()); statement.executeUpdate();
        }
    }

    private void validateState(Connection connection, Collection<CreditEntry> credits, Collection<Payment> payments, Collection<CreditEventEntry> events) throws SQLException {
        Set<UUID> creditIds = new HashSet<>();
        Map<UUID, Double> paymentTotals = new LinkedHashMap<>();
        for (CreditEntry credit : credits) {
            if (!isValidCredit(credit) || !creditIds.add(credit.getId())) throw new SQLException("Invalid or duplicate credit");
        }
        Set<UUID> paymentIds = new HashSet<>();
        for (Payment payment : payments) {
            if (!isValidPayment(payment) || !creditIds.contains(payment.getCreditId()) || !paymentIds.add(payment.getId())) throw new SQLException("Invalid, duplicate or orphan payment");
            paymentTotals.merge(payment.getCreditId(), payment.getAmount(), Double::sum);
            validatePaylogPaymentLink(connection, payment);
        }
        for (CreditEntry credit : credits) validateDerivedCreditState(credit, paymentTotals.getOrDefault(credit.getId(), 0D));
        validatePaylogLinks(connection, payments);
        Set<UUID> eventIds = new HashSet<>();
        for (CreditEventEntry event : events) {
            if (!isValidEvent(event) || !creditIds.contains(event.getCreditId()) || !eventIds.add(event.getId())) throw new SQLException("Invalid, duplicate or orphan event");
        }
    }

    private void validateDerivedCreditState(CreditEntry credit, double paymentTotal) throws SQLException {
        if (Math.abs(credit.getPaidAmount() - paymentTotal) > 0.0001D) {
            throw new SQLException("Credit paid amount does not match its payments");
        }
        if ("CLOSED".equals(credit.getStatus()) || "CANCELLED".equals(credit.getStatus())) return;
        String expected = paymentTotal + 0.0001D >= credit.getAmount() ? "PAID"
                : paymentTotal > 0.0001D ? "PARTIAL" : "OPEN";
        if (!expected.equals(credit.getStatus())) throw new SQLException("Credit status does not match its payments");
    }

    private void validatePaylogLinks(Connection connection, Collection<Payment> payments) throws SQLException {
        Map<UUID, Double> linkedAmounts = new LinkedHashMap<>();
        for (Payment payment : payments) {
            if (payment.getPaylogId() != null) {
                linkedAmounts.merge(payment.getPaylogId(), payment.getAmount(), Double::sum);
            }
        }
        for (Map.Entry<UUID, Double> link : linkedAmounts.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT amount FROM paylogs WHERE id=?")) {
                statement.setString(1, link.getKey().toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Payment links unknown paylog " + link.getKey());
                    if (link.getValue() > result.getDouble(1) + 0.0001D) throw new SQLException("Linked payments exceed paylog amount");
                }
            }
        }
    }

    private void validatePersistedPaylogLinks(Connection connection, Collection<Payment> payments) throws SQLException {
        Set<UUID> paylogIds = new HashSet<>();
        for (Payment payment : payments) if (payment.getPaylogId() != null) paylogIds.add(payment.getPaylogId());
        for (UUID paylogId : paylogIds) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT p.amount, COALESCE((SELECT SUM(amount) FROM payments WHERE paylog_id=p.id), 0) FROM paylogs p WHERE p.id=?")) {
                statement.setString(1, paylogId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Payment links unknown paylog " + paylogId);
                    if (result.getDouble(2) > result.getDouble(1) + 0.0001D) {
                        throw new SQLException("Linked payments exceed paylog amount");
                    }
                }
            }
        }
    }

    private void validatePaylogPaymentLink(Connection connection, Payment payment) throws SQLException {
        if (payment.getPaylogId() == null) return;
        try (PreparedStatement statement = connection.prepareStatement("SELECT payer, receiver, amount FROM paylogs WHERE id=?")) {
            statement.setString(1, payment.getPaylogId().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Payment links unknown paylog " + payment.getPaylogId());
                if (!safe(payment.getFromPlayer()).equalsIgnoreCase(safe(result.getString("payer")))
                        || !safe(payment.getToPlayer()).equalsIgnoreCase(safe(result.getString("receiver")))) {
                    throw new SQLException("Payment direction does not match linked paylog");
                }
                if (!safe(payment.getSource()).startsWith("PAYLOG_")) throw new SQLException("Paylog-linked payment has invalid source");
                if (payment.getAmount() > result.getDouble("amount") + 0.0001D) throw new SQLException("Payment exceeds linked paylog");
            }
        }
    }

    private boolean isValidCredit(CreditEntry value) { return value != null && value.getId() != null && !blank(value.getCreditor()) && !blank(value.getDebtor()) && !value.getCreditor().equalsIgnoreCase(value.getDebtor()) && Double.isFinite(value.getAmount()) && value.getAmount() > 0 && Double.isFinite(value.getPaidAmount()) && value.getPaidAmount() >= 0 && value.getPaidAmount() <= value.getAmount() + 0.0001D; }
    private boolean isValidPayment(Payment value) { return value != null && value.getId() != null && value.getCreditId() != null && value.getAmount() != null && Double.isFinite(value.getAmount()) && value.getAmount() > 0; }
    private boolean isValidEvent(CreditEventEntry value) { return value != null && value.getId() != null && value.getCreditId() != null && value.getType() != null && Double.isFinite(value.getAmount()); }
    private boolean isValidPaylog(TransactionEntry value) { return value != null && !blank(value.getFromPlayer()) && !blank(value.getToPlayer()) && Double.isFinite(value.getAmount()) && value.getAmount() > 0 && value.getTimestamp() > 0; }

    private void upsertCredit(Connection connection, CreditEntry entry, long rowRevision) throws SQLException {
        Long completed = entry.getCompletedAt();
        boolean finalStatus = "PAID".equals(entry.getStatus()) || "CLOSED".equals(entry.getStatus()) || "CANCELLED".equals(entry.getStatus());
        if (finalStatus && completed == null) completed = existingCompletedAt(connection, entry.getId());
        if (finalStatus && completed == null) completed = System.currentTimeMillis();
        if (!finalStatus) completed = null;
        entry.setCompletedAt(completed);
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO credits (id, deal_name, creditor, debtor, amount, paid_amount, created_at, due_date, status, note, search_text, completed_at, archived, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, entry.getId().toString()); statement.setString(2, entry.getDealName()); statement.setString(3, entry.getCreditor()); statement.setString(4, entry.getDebtor()); statement.setDouble(5, entry.getAmount()); statement.setDouble(6, entry.getPaidAmount()); statement.setLong(7, entry.getCreatedAt()); setNullableLong(statement, 8, entry.getDueDate()); statement.setString(9, entry.getStatus()); statement.setString(10, entry.getNote()); statement.setString(11, DealSearchText.build(entry)); setNullableLong(statement, 12, completed); statement.setBoolean(13, entry.isArchived()); statement.setLong(14, rowRevision); statement.executeUpdate();
        }
    }

    private void upsertPayment(Connection connection, Payment payment, long rowRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO payments (id, credit_id, from_player, to_player, amount, items_json, item_nbt, item_nbt_entries, created_at, source, paylog_id, note, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, payment.getId().toString()); statement.setString(2, payment.getCreditId().toString()); statement.setString(3, payment.getFromPlayer()); statement.setString(4, payment.getToPlayer()); statement.setDouble(5, payment.getAmount()); statement.setString(6, GSON.toJson(payment.getItems())); statement.setString(7, payment.getItemNbt()); statement.setString(8, GSON.toJson(payment.getItemNbtEntries())); statement.setLong(9, payment.getTimestamp()); statement.setString(10, payment.getSource()); statement.setString(11, payment.getPaylogId() == null ? null : payment.getPaylogId().toString()); statement.setString(12, payment.getNote()); statement.setLong(13, rowRevision); statement.executeUpdate();
        }
    }

    private void upsertEvent(Connection connection, CreditEventEntry event, long rowRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("MERGE INTO credit_events (id, credit_id, event_type, amount, paid_after, remaining_after, created_at, deal_name, creditor, debtor, note, amount_before, amount_after, actor, source, item_payment, revision) KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, event.getId().toString()); statement.setString(2, event.getCreditId().toString()); statement.setString(3, event.getType().name()); statement.setDouble(4, event.getAmount()); statement.setDouble(5, event.getPaidAmountAfter()); statement.setDouble(6, event.getRemainingAmountAfter()); statement.setLong(7, event.getTimestamp()); statement.setString(8, event.getDealName()); statement.setString(9, event.getCreditor()); statement.setString(10, event.getDebtor()); statement.setString(11, event.getNote()); statement.setDouble(12, event.getAmountBefore()); statement.setDouble(13, event.getAmountAfter()); statement.setString(14, event.getActor()); statement.setString(15, event.getSource()); statement.setBoolean(16, event.isItemPayment()); statement.setLong(17, rowRevision); statement.executeUpdate();
        }
    }

    private boolean insertPaylog(Connection connection, TransactionEntry entry, boolean deduplicate, long rowRevision) throws SQLException {
        String normalized = entry.getNormalizedText();
        if (blank(normalized)) normalized = PaylogSearchText.build(entry);
        String hash = blank(entry.getHash()) ? paylogHash(entry, normalized) : entry.getHash();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO paylogs (id, payer, receiver, amount, raw_text, normalized_text, created_at, entry_hash, source, revision, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") ) {
            statement.setString(1, entry.getId() == null ? UUID.randomUUID().toString() : entry.getId().toString()); statement.setString(2, entry.getFromPlayer()); statement.setString(3, entry.getToPlayer()); statement.setDouble(4, entry.getAmount()); statement.setString(5, entry.getRawText()); statement.setString(6, normalized); statement.setLong(7, entry.getTimestamp()); statement.setString(8, hash); statement.setString(9, blank(entry.getSource()) ? "DETECTED" : entry.getSource()); statement.setLong(10, rowRevision); statement.setString(11, entry.getMetadata()); statement.executeUpdate();
            entry.setNormalizedText(normalized); entry.setHash(hash); return true;
        } catch (SQLException exception) {
            if (deduplicate && "23505".equals(exception.getSQLState())) return false;
            throw exception;
        }
    }

    private String paylogHash(TransactionEntry entry, String normalized) {
        try {
            String canonical = safe(entry.getSource()) + '\u0000' + safe(entry.getFromPlayer()).toLowerCase(Locale.ROOT) + '\u0000' + safe(entry.getToPlayer()).toLowerCase(Locale.ROOT) + '\u0000' + Double.toString(entry.getAmount()) + '\u0000' + safe(entry.getRawText()) + '\u0000' + normalized + '\u0000' + entry.getTimestamp();
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { return UUID.randomUUID().toString().replace("-", ""); }
    }

    private List<CreditEntry> readCredits(Connection connection, String sql, List<String> values, Object... page) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindStrings(statement, values);
            for (int index = 0; index < page.length; index++) statement.setObject(values.size() + index + 1, page[index]);
            try (ResultSet result = statement.executeQuery()) {
                List<CreditEntry> entries = new ArrayList<>();
                while (result.next()) {
                    CreditEntry entry = new CreditEntry();
                    entry.setId(UUID.fromString(result.getString("id"))); entry.setDealName(result.getString("deal_name")); entry.setCreditor(result.getString("creditor")); entry.setDebtor(result.getString("debtor")); entry.setAmount(result.getDouble("amount")); entry.setPaidAmount(result.getDouble("paid_amount")); entry.setCreatedAt(result.getLong("created_at")); long due = result.getLong("due_date"); entry.setDueDate(result.wasNull() ? null : due); entry.setStatus(result.getString("status")); entry.setNote(result.getString("note")); long completed = result.getLong("completed_at"); entry.setCompletedAt(result.wasNull() ? null : completed); entry.setArchived(result.getBoolean("archived")); entry.setPayments(new ArrayList<>()); entries.add(entry);
                }
                return entries;
            }
        }
    }

    private List<Payment> loadPayments(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM payments"); ResultSet result = statement.executeQuery()) {
            List<Payment> entries = new ArrayList<>();
            while (result.next()) {
                Payment payment = new Payment(UUID.fromString(result.getString("credit_id")), result.getString("from_player"), result.getString("to_player"), result.getDouble("amount"), fromJson(result.getString("items_json")), result.getString("source"));
                payment.setId(UUID.fromString(result.getString("id"))); payment.setItemNbt(result.getString("item_nbt")); payment.setItemNbtEntries(fromJson(result.getString("item_nbt_entries"))); payment.setTimestamp(result.getLong("created_at")); String paylogId = result.getString("paylog_id"); if (validUuid(paylogId)) payment.setPaylogId(UUID.fromString(paylogId)); payment.setNote(result.getString("note")); entries.add(payment);
            }
            return entries;
        }
    }

    private List<Payment> loadPaymentsForCredit(Connection connection, UUID creditId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM payments WHERE credit_id=?")) {
            statement.setString(1, creditId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<Payment> entries = new ArrayList<>();
                while (result.next()) {
                    Payment payment = new Payment(UUID.fromString(result.getString("credit_id")), result.getString("from_player"), result.getString("to_player"), result.getDouble("amount"), fromJson(result.getString("items_json")), result.getString("source"));
                    payment.setId(UUID.fromString(result.getString("id")));
                    payment.setItemNbt(result.getString("item_nbt"));
                    payment.setItemNbtEntries(fromJson(result.getString("item_nbt_entries")));
                    payment.setTimestamp(result.getLong("created_at"));
                    String paylogId = result.getString("paylog_id");
                    if (validUuid(paylogId)) payment.setPaylogId(UUID.fromString(paylogId));
                    payment.setNote(result.getString("note"));
                    entries.add(payment);
                }
                return entries;
            }
        }
    }

    private List<CreditEventEntry> loadEvents(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM credit_events"); ResultSet result = statement.executeQuery()) {
            List<CreditEventEntry> entries = new ArrayList<>();
            while (result.next()) {
                CreditEventEntry event = new CreditEventEntry();
                event.setId(UUID.fromString(result.getString("id"))); event.setCreditId(UUID.fromString(result.getString("credit_id"))); event.setType(CreditEventType.valueOf(result.getString("event_type"))); event.setAmount(result.getDouble("amount")); event.setPaidAmountAfter(result.getDouble("paid_after")); event.setRemainingAmountAfter(result.getDouble("remaining_after")); event.setTimestamp(result.getLong("created_at")); event.setDealName(result.getString("deal_name")); event.setCreditor(result.getString("creditor")); event.setDebtor(result.getString("debtor")); event.setNote(result.getString("note")); event.setAmountBefore(result.getDouble("amount_before")); event.setAmountAfter(result.getDouble("amount_after")); event.setActor(result.getString("actor")); event.setSource(result.getString("source")); event.setItemPayment(result.getBoolean("item_payment")); entries.add(event);
            }
            return entries;
        }
    }

    private TransactionEntry readPaylog(ResultSet result) throws SQLException {
        TransactionEntry entry = new TransactionEntry();
        entry.setId(UUID.fromString(result.getString("id"))); entry.setFromPlayer(result.getString("payer")); entry.setToPlayer(result.getString("receiver")); entry.setAmount(result.getDouble("amount")); entry.setRawText(result.getString("raw_text")); entry.setNormalizedText(result.getString("normalized_text")); entry.setTimestamp(result.getLong("created_at")); entry.setHash(result.getString("entry_hash")); entry.setSource(result.getString("source")); entry.setMetadata(result.getString("metadata"));
        try { entry.setLinkedAmount(result.getDouble("linked_amount")); } catch (SQLException ignored) { entry.setLinkedAmount(0.0D); }
        return entry;
    }

    private Long existingCompletedAt(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT completed_at FROM credits WHERE id=?")) {
            statement.setString(1, id.toString());
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) return null; long value = result.getLong(1); return result.wasNull() ? null : value; }
        }
    }

    private boolean rowExists(Connection connection, String table, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + table + " WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void preserveLegacyRecord(Connection connection, LegacyRecord record, String migrationId, int[] counts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO legacy_records (id, record_kind, original_id, raw_payload, reason, created_at, migration_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, record.kind());
            statement.setString(3, record.originalId());
            statement.setString(4, record.rawPayload());
            statement.setString(5, record.reason());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, migrationId);
            statement.executeUpdate();
            counts[4]++;
        }
    }

    private boolean sameCredit(Connection connection, CreditEntry credit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT deal_name, creditor, debtor, amount, paid_amount, created_at, due_date, status, note, archived FROM credits WHERE id=?")) {
            statement.setString(1, credit.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return false;
                long dueDate = result.getLong("due_date");
                Long storedDueDate = result.wasNull() ? null : dueDate;
                return Objects.equals(result.getString("deal_name"), credit.getDealName())
                        && Objects.equals(result.getString("creditor"), credit.getCreditor())
                        && Objects.equals(result.getString("debtor"), credit.getDebtor())
                        && Double.compare(result.getDouble("amount"), credit.getAmount()) == 0
                        && Double.compare(result.getDouble("paid_amount"), credit.getPaidAmount()) == 0
                        && result.getLong("created_at") == credit.getCreatedAt()
                        && Objects.equals(storedDueDate, credit.getDueDate())
                        && Objects.equals(result.getString("status"), credit.getStatus())
                        && Objects.equals(result.getString("note"), credit.getNote())
                        && result.getBoolean("archived") == credit.isArchived();
            }
        }
    }

    private boolean samePayment(Connection connection, Payment payment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id, from_player, to_player, amount, items_json, item_nbt, item_nbt_entries, created_at, source, paylog_id, note FROM payments WHERE id=?")) {
            statement.setString(1, payment.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("credit_id"), payment.getCreditId().toString())
                        && Objects.equals(result.getString("from_player"), payment.getFromPlayer())
                        && Objects.equals(result.getString("to_player"), payment.getToPlayer())
                        && Double.compare(result.getDouble("amount"), payment.getAmount()) == 0
                        && Objects.equals(result.getString("items_json"), GSON.toJson(payment.getItems()))
                        && Objects.equals(result.getString("item_nbt"), payment.getItemNbt())
                        && Objects.equals(result.getString("item_nbt_entries"), GSON.toJson(payment.getItemNbtEntries()))
                        && result.getLong("created_at") == payment.getTimestamp()
                        && Objects.equals(result.getString("source"), payment.getSource())
                        && Objects.equals(result.getString("paylog_id"), payment.getPaylogId() == null ? null : payment.getPaylogId().toString())
                        && Objects.equals(result.getString("note"), payment.getNote());
            }
        }
    }

    private boolean sameEvent(Connection connection, CreditEventEntry event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT credit_id, event_type, amount, paid_after, remaining_after, created_at, deal_name, creditor, debtor, note, amount_before, amount_after, actor, source, item_payment FROM credit_events WHERE id=?")) {
            statement.setString(1, event.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("credit_id"), event.getCreditId().toString())
                        && Objects.equals(result.getString("event_type"), event.getType().name())
                        && Double.compare(result.getDouble("amount"), event.getAmount()) == 0
                        && Double.compare(result.getDouble("paid_after"), event.getPaidAmountAfter()) == 0
                        && Double.compare(result.getDouble("remaining_after"), event.getRemainingAmountAfter()) == 0
                        && result.getLong("created_at") == event.getTimestamp()
                        && Objects.equals(result.getString("deal_name"), event.getDealName())
                        && Objects.equals(result.getString("creditor"), event.getCreditor())
                        && Objects.equals(result.getString("debtor"), event.getDebtor())
                        && Objects.equals(result.getString("note"), event.getNote())
                        && Double.compare(result.getDouble("amount_before"), event.getAmountBefore()) == 0
                        && Double.compare(result.getDouble("amount_after"), event.getAmountAfter()) == 0
                        && Objects.equals(result.getString("actor"), event.getActor())
                        && Objects.equals(result.getString("source"), event.getSource())
                        && result.getBoolean("item_payment") == event.isItemPayment();
            }
        }
    }

    private boolean samePaylog(Connection connection, TransactionEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT payer, receiver, amount, raw_text, created_at, source, metadata FROM paylogs WHERE id=?")) {
            statement.setString(1, entry.getId().toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && Objects.equals(result.getString("payer"), entry.getFromPlayer())
                        && Objects.equals(result.getString("receiver"), entry.getToPlayer())
                        && Double.compare(result.getDouble("amount"), entry.getAmount()) == 0
                        && Objects.equals(result.getString("raw_text"), entry.getRawText())
                        && result.getLong("created_at") == entry.getTimestamp()
                        && Objects.equals(result.getString("source"), entry.getSource())
                        && Objects.equals(result.getString("metadata"), entry.getMetadata());
            }
        }
    }

    private boolean hasDomainData(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT (SELECT COUNT(*) FROM credits) + (SELECT COUNT(*) FROM payments) + (SELECT COUNT(*) FROM credit_events) + (SELECT COUNT(*) FROM paylogs)")) { result.next(); return result.getLong(1) > 0; }
    }

    private boolean hasOpenHealthErrors(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM data_health_records WHERE status='OPEN' AND severity='ERROR' LIMIT 1");
             ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private boolean inTransaction(DatabaseWork work) {
        initialize();
        if (writeLocked) return false;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try { work.run(connection); connection.commit(); healthy = true; return true; }
            catch (DuplicatePaylogException ignored) { connection.rollback(); return false; }
            catch (Exception exception) { connection.rollback(); CreditManagerClient.LOGGER.warn("Local database transaction was rolled back", exception); return false; }
        } catch (SQLException exception) { healthy = false; CreditManagerClient.LOGGER.warn("Could not open local database transaction", exception); return false; }
    }

    private Connection connection() throws SQLException { return openConnection(FileManager.getDatabaseFile()); }
    private Connection openConnection(Path databaseBase) throws SQLException { return DriverManager.getConnection(databaseUrl(databaseBase)); }
    private IllegalStateException queryFailure(String message, SQLException exception) {
        healthy = false;
        writeLocked = true;
        DataHealth.reportRecoveryRequired(message + " Daten wurden nicht gelöscht; bitte die Wiederherstellung öffnen.");
        CreditManagerClient.LOGGER.error(message, exception);
        return new IllegalStateException(message + " Datenbankprüfung erforderlich.", exception);
    }
    private String databaseUrl(Path databaseBase) {
        String path = databaseBase.toAbsolutePath().normalize().toString().replace('\\', '/').replace(";", "\\;");
        return "jdbc:h2:file:" + path + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
    }
    private long count(Connection connection, String sql, List<String> values) throws SQLException { try (PreparedStatement statement = connection.prepareStatement(sql)) { bindStrings(statement, values); try (ResultSet result = statement.executeQuery()) { result.next(); return result.getLong(1); } } }
    private void bindStrings(PreparedStatement statement, List<String> values) throws SQLException { for (int index = 0; index < values.size(); index++) statement.setString(index + 1, values.get(index)); }
    private long nextRevision(Connection connection) throws SQLException { String value = metadata(connection, "data_revision"); return (value == null ? 0L : Long.parseLong(value)) + 1; }
    private long revision(Connection connection) throws SQLException { String value = metadata(connection, "data_revision"); return value == null ? 0L : Long.parseLong(value); }
    private void bumpRevision(Connection connection) throws SQLException { putMetadata(connection, "data_revision", String.valueOf(nextRevision(connection))); }
    private String metadata(Connection connection, String key) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT meta_value FROM metadata WHERE meta_key=?")) { statement.setString(1, key); try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; } } }
    private void putMetadata(Connection connection, String key, String value) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("MERGE INTO metadata (meta_key, meta_value) KEY(meta_key) VALUES (?, ?)")) { statement.setString(1, key); statement.setString(2, value); statement.executeUpdate(); } }
    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException { if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value); }
    private List<String> fromJson(String json) { if (blank(json)) return List.of(); try { List<String> values = GSON.fromJson(json, STRING_LIST); return values == null ? List.of() : values; } catch (RuntimeException ignored) { return List.of(); } }
    private String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private boolean validUuid(String value) { try { UUID.fromString(value); return true; } catch (RuntimeException ignored) { return false; } }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return value == null ? "" : value; }
    private String escapeSqlLiteral(String value) { return value.replace("'", "''"); }
    private String rowPayload(ResultSet result) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            var metadata = result.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                Object value = result.getObject(index);
                payload.put(metadata.getColumnLabel(index), value == null ? null : String.valueOf(value));
            }
            return GSON.toJson(payload);
        } catch (SQLException ignored) {
            return null;
        }
    }

    public record DatabaseState(List<CreditEntry> credits, List<Payment> payments, List<CreditEventEntry> events) { }
    public record CreditMutation(CreditEntry credit, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                                 List<CreditEventEntry> events) { }
    public record BackupManifestEntry(String fileName, long createdAt, int schemaVersion, long revision,
                                      int creditCount, int paymentCount, int paylogCount, int eventCount,
                                      boolean healthy, String format) {
        public int domainCount() { return creditCount + paymentCount + paylogCount + eventCount; }
    }
    private record BackupValidation(boolean valid, int creditCount, int paymentCount, int paylogCount, int eventCount) {
        private static BackupValidation invalid() { return new BackupValidation(false, 0, 0, 0, 0); }
        private int domainCount() { return creditCount + paymentCount + paylogCount + eventCount; }
    }
    private record RequiredColumn(String table, String name, String definition) { }
    private record RequiredIndex(String table, String name, String definition) { }
    public record LegacyRecord(String kind, String originalId, String rawPayload, String reason) { }
    public record AutomaticMigrationResult(boolean success, int credits, int payments, int events, int paylogs, int preservedRecords) {
        private static AutomaticMigrationResult failed() { return new AutomaticMigrationResult(false, 0, 0, 0, 0, 0); }
    }
    public record QueryPage<T>(List<T> entries, long totalCount, int offset, int pageSize) {
        public boolean hasPrevious() { return offset > 0; }
        public boolean hasNext() { return offset + entries.size() < totalCount; }
        public int pageNumber() { return offset / pageSize + 1; }
        public int pageCount() { return Math.max(1, (int) Math.ceil(totalCount / (double) pageSize)); }
    }
    public enum DealHistorySort { NEWEST, OLDEST, AMOUNT_DESC, AMOUNT_ASC, PLAYER_ASC, STATUS }
    public record DataHealthRecord(UUID id, String type, String severity, String sourceTable, String sourceId, String title, String message, String rawPayload, String repairPayload, String status, long createdAt, Long resolvedAt) { }
    private DataHealthRecord readHealth(ResultSet result) throws SQLException { long resolved = result.getLong("resolved_at"); return new DataHealthRecord(UUID.fromString(result.getString("id")), result.getString("record_type"), result.getString("severity"), result.getString("source_table"), result.getString("source_id"), result.getString("title"), result.getString("message"), result.getString("raw_payload"), result.getString("repair_payload"), result.getString("status"), result.getLong("created_at"), result.wasNull() ? null : resolved); }
    @FunctionalInterface private interface DatabaseWork { void run(Connection connection) throws Exception; }
    private static final class SchemaValidationException extends SQLException {
        private SchemaValidationException(String message) { super(message); }
    }
    private static final class DuplicatePaylogException extends RuntimeException { }
}

package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import op.creditmanager.client.storage.FileManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaylogLinkMigrationTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;

    @AfterEach
    void restoreDirectory() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
    }

    @Test
    void completeVersionSevenDatabaseMigratesThroughStagingWithExactLinks() throws Exception {
        useTemporaryDataDirectory();
        createVersionSevenDatabase();

        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();

        assertTrue(manager.isHealthy());
        assertEquals(DatabaseManager.SCHEMA_VERSION, schemaVersion());
        assertEquals(1L, manager.revision());
        assertEquals("bigint", columnType("credits", "amount"));
        assertEquals("bigint", columnType("payments", "amount"));
        assertEquals("bigint", columnType("paylogs", "amount"));
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT amount,linked_amount,link_count FROM paylogs WHERE id='00000000-0000-0000-0000-000000000002'")) {
            assertTrue(rows.next());
            assertEquals(10_025L, rows.getLong("amount"));
            assertEquals(4_000L, rows.getLong("linked_amount"));
            assertEquals(1, rows.getInt("link_count"));
        }
        try (var files = Files.list(FileManager.getBackupDirectory())) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("creditmanager_pre_v8_")));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7})
    void everyReleasedLegacySchemaVersionUsesItsAuthenticStagedRestoreFixture(int legacyVersion) throws Exception {
        useTemporaryDataDirectory();
        createLegacyDatabase(legacyVersion);
        Path active = FileManager.getDatabaseStorageFile();
        Path candidate = dataDirectory.resolve("restore-v" + legacyVersion).resolve("candidate.mv.db");
        Files.createDirectories(candidate.getParent());
        Files.copy(active, candidate);

        assertTrue(prepareRestoreCandidate(new DatabaseCoordinator(), candidate));

        assertEquals(legacyVersion, schemaVersion(FileManager.getDatabaseFile()));
        assertEquals(DatabaseManager.SCHEMA_VERSION, schemaVersion(candidateBase(candidate)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void unreleasedIntermediateSchemaMetadataIsNotClaimedAsRestoreCompatible(int legacyVersion) throws Exception {
        useTemporaryDataDirectory();
        createLegacyDatabase(4);
        Path candidate = dataDirectory.resolve("unsupported-v" + legacyVersion).resolve("candidate.mv.db");
        Files.createDirectories(candidate.getParent());
        Files.copy(FileManager.getDatabaseStorageFile(), candidate);
        try (var connection = DriverManager.getConnection(jdbcUrl(candidateBase(candidate))); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE metadata SET meta_value='" + legacyVersion + "' WHERE meta_key='schema_version'");
        }

        assertFalse(prepareRestoreCandidate(new DatabaseCoordinator(), candidate));
        assertEquals(4, schemaVersion(FileManager.getDatabaseFile()));
    }

    @Test
    void futureSchemaRestoreCandidateIsRejectedWithoutDowngradeOrActiveMutation() throws Exception {
        useTemporaryDataDirectory();
        createVersionSevenDatabase();
        Path candidate = dataDirectory.resolve("future-schema").resolve("candidate.mv.db");
        Files.createDirectories(candidate.getParent());
        Files.copy(FileManager.getDatabaseStorageFile(), candidate);
        try (var connection = DriverManager.getConnection(jdbcUrl(candidateBase(candidate))); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE metadata SET meta_value='" + (DatabaseManager.SCHEMA_VERSION + 1) + "' WHERE meta_key='schema_version'");
        }

        assertFalse(prepareRestoreCandidate(new DatabaseCoordinator(), candidate));
        assertEquals(DatabaseManager.SCHEMA_VERSION + 1, schemaVersion(candidateBase(candidate)));
        assertEquals(7, schemaVersion(FileManager.getDatabaseFile()));
    }

    @Test
    void restoreCandidateMigratesAndFullyValidatesBeforeTheActiveDatabaseIsTouched() throws Exception {
        useTemporaryDataDirectory();
        createVersionSevenDatabase();
        Path active = FileManager.getDatabaseStorageFile();
        Path candidate = dataDirectory.resolve("restore-staging").resolve("candidate.mv.db");
        Files.createDirectories(candidate.getParent());
        Files.copy(active, candidate);

        assertTrue(prepareRestoreCandidate(new DatabaseCoordinator(), candidate));

        assertEquals(7, schemaVersion(FileManager.getDatabaseFile()));
        assertEquals(DatabaseManager.SCHEMA_VERSION, schemaVersion(candidateBase(candidate)));
    }

    @Test
    void invalidLegacyRestoreGraphIsRejectedWithoutChangingTheActiveDatabase() throws Exception {
        useTemporaryDataDirectory();
        createVersionSevenDatabase();
        Path active = FileManager.getDatabaseStorageFile();
        Path candidate = dataDirectory.resolve("restore-invalid").resolve("candidate.mv.db");
        Files.createDirectories(candidate.getParent());
        Files.copy(active, candidate);
        try (var connection = DriverManager.getConnection(jdbcUrl(candidateBase(candidate)));
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP CONSTRAINT fk_payment_credit");
            statement.executeUpdate("UPDATE payments SET credit_id='00000000-0000-0000-0000-000000000099'");
        }

        assertFalse(prepareRestoreCandidate(new DatabaseCoordinator(), candidate));

        assertEquals(7, schemaVersion(FileManager.getDatabaseFile()));
        try (var connection = DriverManager.getConnection(jdbcUrl());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT credit_id FROM payments")) {
            assertTrue(rows.next());
            assertEquals("00000000-0000-0000-0000-000000000001", rows.getString(1));
        }
    }

    @Test
    void missingCoreTableInExistingDatabaseIsNeverBootstrappedSilently() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE payments");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertFalse(tableExists("payments"));
    }

    @Test
    void missingForeignKeyIsRejectedBySchemaValidation() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP CONSTRAINT fk_payments_credit");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void missingUniquePaylogHashConstraintIsRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl());
             var find = connection.prepareStatement("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name='paylogs' AND constraint_type='UNIQUE'");
             var result = find.executeQuery()) {
            assertTrue(result.next());
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE paylogs DROP CONSTRAINT \"" + result.getString(1) + "\"");
            }
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void wrongMoneyTypeAndNullableMoneyColumnAreRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE credits DROP CONSTRAINT ck_credits_paid");
            statement.execute("ALTER TABLE credits DROP CONSTRAINT ck_credits_amount");
            statement.execute("ALTER TABLE credits ALTER COLUMN amount DOUBLE PRECISION");
            statement.execute("ALTER TABLE payments DROP CONSTRAINT ck_payments_amount");
            statement.execute("ALTER TABLE payments ALTER COLUMN amount SET NULL");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void semanticallyUselessCheckConstraintIsRejectedEvenWhenItNamesTheRightColumn() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE credits DROP CONSTRAINT ck_credits_amount");
            statement.execute("ALTER TABLE credits ADD CONSTRAINT ck_credits_amount_fake CHECK (amount=amount)");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    @Test
    void wrongPaylogForeignKeyDeleteRuleIsRejected() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager manager = DatabaseManager.getInstance();
        manager.initialize();
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE payments DROP CONSTRAINT fk_payments_paylog");
            statement.execute("ALTER TABLE payments ADD CONSTRAINT fk_payments_paylog_wrong FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE");
        }
        resetDatabaseInitialization();

        manager.initialize();

        assertTrue(manager.isWriteLocked());
        assertEquals(DatabaseManager.DatabaseAvailability.SCHEMA_REPAIRABLE, manager.availability());
    }

    private void createVersionSevenDatabase() throws Exception {
        createLegacyDatabase(7);
    }

    private void createLegacyDatabase(int schemaVersion) throws Exception {
        if (schemaVersion < 4 || schemaVersion > 7) throw new IllegalArgumentException("unsupported fixture version");
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE metadata (meta_key VARCHAR(128) PRIMARY KEY, meta_value VARCHAR(4096) NOT NULL)");
            statement.execute("CREATE TABLE schema_migrations (version INT PRIMARY KEY, applied_at BIGINT NOT NULL)");
            String searchText = schemaVersion >= 6 ? ", search_text CLOB" : "";
            String paylogLink = schemaVersion >= 5 ? ", paylog_id VARCHAR(36)" : "";
            String paymentNote = schemaVersion >= 6 ? ", note CLOB" : "";
            String linkAggregate = schemaVersion >= 7 ? ", linked_amount DOUBLE PRECISION NOT NULL DEFAULT 0, link_count INT NOT NULL DEFAULT 0" : "";
            statement.execute("CREATE TABLE credits (id VARCHAR(36) PRIMARY KEY, deal_name VARCHAR(256) NOT NULL, creditor VARCHAR(64) NOT NULL, debtor VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_amount DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, due_date BIGINT, status VARCHAR(32) NOT NULL, note CLOB" + searchText + ", completed_at BIGINT, archived BOOLEAN NOT NULL DEFAULT FALSE, revision BIGINT NOT NULL)");
            statement.execute("CREATE TABLE paylogs (id VARCHAR(36) PRIMARY KEY, payer VARCHAR(64) NOT NULL, receiver VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, raw_text CLOB, normalized_text CLOB NOT NULL, created_at BIGINT NOT NULL, entry_hash VARCHAR(64) NOT NULL UNIQUE, source VARCHAR(64), revision BIGINT NOT NULL, metadata CLOB" + linkAggregate + ")");
            statement.execute("CREATE TABLE payments (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, from_player VARCHAR(64), to_player VARCHAR(64), amount DOUBLE PRECISION NOT NULL, items_json CLOB, item_nbt CLOB, item_nbt_entries CLOB, created_at BIGINT NOT NULL, source VARCHAR(64)" + paylogLink + paymentNote + ", revision BIGINT NOT NULL, CONSTRAINT fk_payment_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE credit_events (id VARCHAR(36) PRIMARY KEY, credit_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL, amount DOUBLE PRECISION NOT NULL, paid_after DOUBLE PRECISION NOT NULL, remaining_after DOUBLE PRECISION NOT NULL, created_at BIGINT NOT NULL, deal_name VARCHAR(256), creditor VARCHAR(64), debtor VARCHAR(64), note CLOB, amount_before DOUBLE PRECISION NOT NULL, amount_after DOUBLE PRECISION NOT NULL, actor VARCHAR(64), source VARCHAR(64), item_payment BOOLEAN NOT NULL, revision BIGINT NOT NULL, CONSTRAINT fk_event_credit FOREIGN KEY (credit_id) REFERENCES credits(id) ON DELETE CASCADE)");
            statement.execute("CREATE TABLE data_health_records (id VARCHAR(36) PRIMARY KEY, record_type VARCHAR(64) NOT NULL, severity VARCHAR(32) NOT NULL, source_table VARCHAR(64), source_id VARCHAR(128), title VARCHAR(256), message CLOB, raw_payload CLOB, repair_payload CLOB, status VARCHAR(32) NOT NULL, created_at BIGINT NOT NULL, resolved_at BIGINT)");
            statement.execute("CREATE TABLE migration_log (id VARCHAR(36) PRIMARY KEY, migration_type VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, details CLOB, status VARCHAR(32) NOT NULL)");
            statement.execute("CREATE TABLE legacy_records (id VARCHAR(36) PRIMARY KEY, record_kind VARCHAR(64) NOT NULL, original_id VARCHAR(256), raw_payload CLOB NOT NULL, reason CLOB, created_at BIGINT NOT NULL, migration_id VARCHAR(36) NOT NULL)");
            if (schemaVersion >= 7) statement.execute("CREATE TABLE paylog_search_tokens (paylog_id VARCHAR(36) NOT NULL, token VARCHAR(96) NOT NULL, PRIMARY KEY (paylog_id, token), CONSTRAINT fk_paylog_search_token FOREIGN KEY (paylog_id) REFERENCES paylogs(id) ON DELETE CASCADE)");
            statement.execute("INSERT INTO metadata VALUES ('schema_version','" + schemaVersion + "'),('data_revision','1')");
            for (int version = 1; version <= schemaVersion; version++) {
                statement.execute("INSERT INTO schema_migrations VALUES (" + version + ",1)");
            }
            statement.execute("INSERT INTO credits (id,deal_name,creditor,debtor,amount,paid_amount,created_at,due_date,status,note,completed_at,archived,revision) VALUES ('00000000-0000-0000-0000-000000000001','deal','creditor','debtor',100.25,40,1,NULL,'PARTIAL',NULL,NULL,FALSE,1)");
            statement.execute("INSERT INTO paylogs (id,payer,receiver,amount,raw_text,normalized_text,created_at,entry_hash,source,revision,metadata) VALUES ('00000000-0000-0000-0000-000000000002','debtor','creditor',100.25,'legacy','legacy',1,'legacy-hash','DETECTED',1,NULL)");
            String paymentColumns = "id,credit_id,from_player,to_player,amount,items_json,item_nbt,item_nbt_entries,created_at,source" + (schemaVersion >= 5 ? ",paylog_id" : "") + (schemaVersion >= 6 ? ",note" : "") + ",revision";
            String paymentValues = "'00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','debtor','creditor',40,'[]',NULL,'[]',1,'PAYLOG_DETECTED'" + (schemaVersion >= 5 ? ",'00000000-0000-0000-0000-000000000002'" : "") + (schemaVersion >= 6 ? ",NULL" : "") + ",1";
            statement.execute("INSERT INTO payments (" + paymentColumns + ") VALUES (" + paymentValues + ")");
            statement.execute("INSERT INTO credit_events (id,credit_id,event_type,amount,paid_after,remaining_after,created_at,deal_name,creditor,debtor,note,amount_before,amount_after,actor,source,item_payment,revision) VALUES ('00000000-0000-0000-0000-000000000004','00000000-0000-0000-0000-000000000001','PAYMENT_ADDED',40,40,60.25,1,'deal','creditor','debtor',NULL,100.25,60.25,'debtor','PAYLOG_DETECTED',FALSE,1)");
        }
    }

    private void useTemporaryDataDirectory() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        Files.createDirectories(dataDirectory);
        dataDirectoryField().set(null, dataDirectory);
        resetDatabaseInitialization();
    }

    private void resetDatabaseInitialization() throws Exception {
        Object coordinator = coordinator();
        set(coordinator, "initialized", false);
        set(coordinator, "initializedAt", null);
        set(coordinator, "healthy", true);
        set(coordinator, "writeLocked", false);
        set(coordinator, "availability", DatabaseManager.DatabaseAvailability.UNKNOWN);
    }

    private Object coordinator() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return field.get(DatabaseManager.getInstance());
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private int schemaVersion() throws Exception {
        return schemaVersion(FileManager.getDatabaseFile());
    }

    private int schemaVersion(Path base) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(base)); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT meta_value FROM metadata WHERE meta_key='schema_version'")) {
            result.next();
            return Integer.parseInt(result.getString(1));
        }
    }

    private boolean prepareRestoreCandidate(DatabaseCoordinator coordinator, Path candidate) throws Exception {
        Method method = DatabaseCoordinator.class.getDeclaredMethod("prepareRestoreCandidate", Path.class);
        method.setAccessible(true);
        return (boolean) method.invoke(coordinator, candidate);
    }

    private Path candidateBase(Path storage) {
        String name = storage.getFileName().toString();
        return storage.resolveSibling(name.substring(0, name.length() - ".mv.db".length()));
    }

    private String columnType(String table, String column) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT data_type FROM information_schema.columns WHERE table_name=? AND column_name=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl()); var statement = connection.prepareStatement("SELECT 1 FROM information_schema.tables WHERE table_name=?")) {
            statement.setString(1, table);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    private String jdbcUrl() {
        return jdbcUrl(FileManager.getDatabaseFile());
    }

    private String jdbcUrl(Path base) {
        return "jdbc:h2:file:" + base.toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE";
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }
}

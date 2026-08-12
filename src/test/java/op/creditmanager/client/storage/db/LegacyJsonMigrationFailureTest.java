package op.creditmanager.client.storage.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.config.ClientConfig;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.money.MoneyRules;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyJsonMigrationFailureTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private Object previousConfig;
    private boolean previousRecovery;

    @BeforeEach
    void useTemporaryDataDirectory() throws Exception {
        previousDirectory = (Path) dataDirectoryField().get(null);
        previousConfig = configField().get(null);
        previousRecovery = configRecoveryField().getBoolean(null);
        dataDirectoryField().set(null, dataDirectory);
        configField().set(null, new ClientConfig());
        configRecoveryField().setBoolean(null, false);
        FileManager.initialize();
        DatabaseManager.getInstance().initialize();
        ClientConfigManager.resetJsonMigrationCheck();
    }

    @AfterEach
    void restoreStatics() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        configField().set(null, previousConfig);
        configRecoveryField().setBoolean(null, previousRecovery);
        DataHealth.clearReasons();
    }

    @Test
    void archiveMoveFailureLeavesImportPendingAndRestartCompletesDeterministically() throws Exception {
        UUID creditId = UUID.randomUUID();
        writeCreditJson(creditId);
        RecoveryFileOps failingArchive = new RecoveryFileOps() {
            @Override
            void moveWithoutReplacing(Path source, Path target) throws IOException {
                if (source.toAbsolutePath().normalize().equals(FileManager.getCreditsFile().toAbsolutePath().normalize())) {
                    throw new IOException("injected source archive failure");
                }
                super.moveWithoutReplacing(source, target);
            }
        };

        new LegacyJsonMigrationService(failingArchive).inspectAtStartup();

        assertEquals(1, DatabaseManager.getInstance().loadCreditState().credits().size());
        assertTrue(DatabaseManager.getInstance().hasPendingAutomaticJsonMigration());
        assertFalse(DatabaseManager.getInstance().hasCompletedAutomaticJsonMigration());
        assertTrue(Files.isRegularFile(FileManager.getCreditsFile()));
        assertFalse(ClientConfigManager.isCheckedForJsonMigration());

        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        assertEquals(1, DatabaseManager.getInstance().loadCreditState().credits().size());
        assertTrue(DatabaseManager.getInstance().hasCompletedAutomaticJsonMigration());
        assertFalse(Files.exists(FileManager.getCreditsFile()));
        assertTrue(ClientConfigManager.isCheckedForJsonMigration());
    }

    @Test
    void requiredBackupFailureStopsMergeBeforeAnyLegacyDomainWrite() throws Exception {
        CreditEntry existing = new CreditEntry(UUID.randomUUID(), "existing", "creditor", "debtor", 10_000L, null, null);
        assertTrue(DatabaseManager.getInstance().commitCreditMutation(new DatabaseManager.CreditMutation(existing, List.of(), List.of(), List.of())));
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE"); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO data_health_records (id,record_type,severity,title,message,status,created_at) VALUES ('" + UUID.randomUUID() + "','TEST_BACKUP_BLOCK','ERROR','test','test','OPEN',1)");
        }
        UUID legacy = UUID.randomUUID();
        writeCreditJson(legacy);

        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
        assertEquals(1, state.credits().size());
        assertTrue(state.credits().stream().anyMatch(credit -> existing.getId().equals(credit.getId())));
        assertTrue(state.credits().stream().noneMatch(credit -> legacy.equals(credit.getId())));
        assertTrue(Files.isRegularFile(FileManager.getCreditsFile()));
        assertFalse(DatabaseManager.getInstance().hasPendingAutomaticJsonMigration());
        assertFalse(ClientConfigManager.isCheckedForJsonMigration());
    }

    @Test
    void maximumMinusOneCentLegacyTokenNeverPassesThroughDouble() throws Exception {
        UUID id = UUID.randomUUID();
        Files.writeString(FileManager.getCreditsFile(), "{\"" + id + "\":{\"id\":\"" + id + "\",\"dealName\":\"maximum\",\"creditor\":\"creditor\",\"debtor\":\"debtor\",\"amount\":999999999999999.99,\"paidAmount\":0,\"createdAt\":1,\"status\":\"OPEN\",\"payments\":[]}}");

        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        CreditEntry migrated = DatabaseManager.getInstance().loadCreditState().credits().getFirst();
        assertEquals(MoneyRules.MAX_MINOR - 1L, migrated.getAmountMinor());
    }

    private void writeCreditJson(UUID id) throws Exception {
        Files.writeString(FileManager.getCreditsFile(), "{\"" + id + "\":{\"id\":\"" + id + "\",\"dealName\":\"legacy\",\"creditor\":\"creditor\",\"debtor\":\"debtor\",\"amount\":100.00,\"paidAmount\":0,\"createdAt\":1,\"status\":\"OPEN\",\"payments\":[]}}");
    }

    private Field dataDirectoryField() throws Exception {
        Field field = FileManager.class.getDeclaredField("dataDirectory");
        field.setAccessible(true);
        return field;
    }

    private Field configField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("config");
        field.setAccessible(true);
        return field;
    }

    private Field configRecoveryField() throws Exception {
        Field field = ClientConfigManager.class.getDeclaredField("recoveryRequired");
        field.setAccessible(true);
        return field;
    }
}

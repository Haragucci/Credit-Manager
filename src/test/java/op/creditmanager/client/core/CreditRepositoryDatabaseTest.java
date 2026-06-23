package op.creditmanager.client.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import op.creditmanager.client.config.ClientConfig;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.storage.db.LegacyJsonMigrationService;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditRepositoryDatabaseTest {
    @TempDir Path dataDirectory;
    private Path previousDirectory;
    private Object previousConfig;
    private boolean previousConfigRecovery;

    @AfterEach
    void restoreStatics() throws Exception {
        dataDirectoryField().set(null, previousDirectory);
        configField().set(null, previousConfig);
        configRecoveryField().setBoolean(null, previousConfigRecovery);
    }

    @Test
    void creditsAndPaymentsRoundTripThroughTheLocalDatabase() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, "note");
        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 25D, java.util.List.of("minecraft:diamond*3"), "MANUELL");
        credit.addPayment(payment);
        repository.putCredit(credit);
        repository.putPayment(payment);

        assertTrue(repository.saveAll());
        CreditRepository reloaded = new CreditRepository();
        reloaded.load();

        CreditEntry restored = reloaded.findCreditById(credit.getId()).orElseThrow();
        assertEquals(25D, restored.getPaidAmount());
        assertEquals(75D, restored.getRemainingAmount());
        assertEquals(1, reloaded.getPaymentsByCreditId(credit.getId()).size());
    }

    @Test
    void stagedLoadKeepsThePreviousInMemoryStateWhenReadingTheDatabaseFails() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "staged-deal", "creditor", "debtor", 100D, null, null);
        repository.putCredit(credit);
        assertTrue(repository.saveAll());
        assertTrue(repository.load());

        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE credits DROP COLUMN deal_name");
        }

        assertFalse(repository.load());
        assertTrue(repository.findCreditById(credit.getId()).isPresent());
        assertEquals(1, repository.getAllCredits().size());
    }

    @Test
    void failedDatabaseTransactionLeavesThePreviousStateIntact() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository(); repository.load();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, null);
        repository.putCredit(credit); assertTrue(repository.saveAll());
        Payment orphan = new Payment(UUID.randomUUID(), "x", "y", 10D, null, "TEST");
        repository.putPayment(orphan);

        assertFalse(repository.saveAll());
        CreditRepository reloaded = new CreditRepository(); reloaded.load();
        assertEquals(1, reloaded.getAllCredits().size());
        assertTrue(reloaded.getAllPayments().isEmpty());
    }

    @Test
    void legacyJsonIsImportedAutomaticallyAndArchived() throws Exception {
        useTemporaryDataDirectory();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, null);
        Files.writeString(FileManager.getCreditsFile(), "{\"" + credit.getId() + "\":{\"dealName\":\"debtor-creditor\",\"creditor\":\"creditor\",\"debtor\":\"debtor\",\"amount\":100.0,\"paidAmount\":0.0,\"createdAt\":1,\"dueDate\":null,\"status\":\"OPEN\",\"payments\":[],\"note\":null}}");
        ClientConfigManager.resetJsonMigrationCheck();

        LegacyJsonMigrationService service = LegacyJsonMigrationService.getInstance();
        service.inspectAtStartup();

        CreditRepository repository = new CreditRepository(); repository.load();
        assertEquals(1, repository.getAllCredits().size());
        assertTrue(ClientConfigManager.isCheckedForJsonMigration());
        assertTrue(Files.isDirectory(FileManager.getLegacyArchiveDirectory()));
        assertFalse(Files.exists(FileManager.getCreditsFile()));
        assertFalse(service.isPending());
    }

    @Test
    void automaticMigrationCreatesArchivedDealsForOrphanPayments() throws Exception {
        useTemporaryDataDirectory();
        UUID orphanCredit = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Files.writeString(FileManager.getPaymentsFile(), "{\"" + paymentId + "\":{\"creditId\":\"" + orphanCredit + "\",\"fromPlayer\":\"same-player\",\"toPlayer\":\"same-player\",\"amount\":750000.0,\"items\":[\"1x Teleport Pad\"],\"itemNbt\":\"{components:{test:1}}\",\"timestamp\":42,\"source\":\"MANUELL\"}}");
        ClientConfigManager.resetJsonMigrationCheck();
        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        CreditRepository repository = new CreditRepository(); repository.load();
        CreditEntry placeholder = repository.findCreditById(orphanCredit).orElseThrow();
        Payment payment = repository.getPaymentsByCreditId(orphanCredit).getFirst();
        assertTrue(placeholder.isArchived());
        assertEquals("same-player", payment.getFromPlayer());
        assertEquals("same-player", payment.getToPlayer());
        assertEquals("{components:{test:1}}", payment.getItemNbt());
        assertTrue(ClientConfigManager.isCheckedForJsonMigration());
    }

    @Test
    void paymentStoredOnlyInLegacyBackupIsImportedWithAnArchivedPlaceholderDeal() throws Exception {
        useTemporaryDataDirectory();
        UUID orphanCredit = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Path backup = FileManager.getDataDirectory().resolve("payments_backup_1.json");
        Files.writeString(backup, "{\"" + paymentId + "\":{\"creditId\":\"" + orphanCredit + "\",\"fromPlayer\":\"legacy-debtor\",\"toPlayer\":\"legacy-creditor\",\"amount\":42.0,\"items\":[\"minecraft:diamond*2\"],\"timestamp\":42,\"source\":\"MANUELL\"}}");

        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
        assertEquals(1, state.payments().size());
        assertEquals(paymentId, state.payments().getFirst().getId());
        CreditEntry placeholder = state.credits().stream().filter(credit -> orphanCredit.equals(credit.getId())).findFirst().orElseThrow();
        assertTrue(placeholder.isArchived());
        assertFalse(Files.exists(backup));
        assertTrue(Files.isDirectory(FileManager.getLegacyArchiveDirectory()));
    }

    @Test
    void paymentsJsonWinsOverABackupWhenBothContainTheSamePaymentUuid() throws Exception {
        useTemporaryDataDirectory();
        UUID orphanCredit = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Files.writeString(FileManager.getDataDirectory().resolve("payments_backup_1.json"), "{\"" + paymentId + "\":{\"creditId\":\"" + orphanCredit + "\",\"fromPlayer\":\"backup\",\"toPlayer\":\"creditor\",\"amount\":10.0,\"timestamp\":42,\"source\":\"MANUELL\"}}");
        Files.writeString(FileManager.getPaymentsFile(), "{\"" + paymentId + "\":{\"creditId\":\"" + orphanCredit + "\",\"fromPlayer\":\"main\",\"toPlayer\":\"creditor\",\"amount\":20.0,\"timestamp\":42,\"source\":\"MANUELL\"}}");

        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        Payment imported = DatabaseManager.getInstance().loadCreditState().payments().getFirst();
        assertEquals(20.0D, imported.getAmount());
        assertEquals("main", imported.getFromPlayer());
        assertTrue(DatabaseManager.getInstance().legacyRecordCount() >= 1);
    }

    @Test
    void newLegacyJsonRowsAreMergedAfterTheAutomaticMigrationVersionWasMarkedComplete() throws Exception {
        useTemporaryDataDirectory();
        UUID firstCredit = UUID.randomUUID();
        UUID firstPayment = UUID.randomUUID();
        Files.writeString(FileManager.getPaymentsFile(), "{\"" + firstPayment + "\":{\"creditId\":\"" + firstCredit + "\",\"fromPlayer\":\"first-debtor\",\"toPlayer\":\"first-creditor\",\"amount\":10.0,\"timestamp\":42,\"source\":\"MANUELL\"}}");
        LegacyJsonMigrationService service = LegacyJsonMigrationService.getInstance();
        service.inspectAtStartup();
        assertTrue(DatabaseManager.getInstance().hasCompletedAutomaticJsonMigration());

        UUID additionalCredit = UUID.randomUUID();
        UUID additionalPayment = UUID.randomUUID();
        Files.writeString(FileManager.getPaymentsFile(), "{\"" + additionalPayment + "\":{\"creditId\":\"" + additionalCredit + "\",\"fromPlayer\":\"second-debtor\",\"toPlayer\":\"second-creditor\",\"amount\":25.0,\"timestamp\":43,\"source\":\"MANUELL\"}}");
        service.inspectAtStartup();

        DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
        assertEquals(2, state.payments().size());
        assertTrue(state.payments().stream().anyMatch(payment -> additionalPayment.equals(payment.getId())));
        assertEquals(2, state.credits().size());
        assertTrue(state.credits().stream().filter(credit -> additionalCredit.equals(credit.getId())).findFirst().orElseThrow().isArchived());
    }

    @Test
    void malformedLegacyValuesArePreservedAutomatically() throws Exception {
        useTemporaryDataDirectory();
        Files.writeString(FileManager.getCreditsFile(), "{\"not-a-uuid\":{\"creditor\":\"creditor\",\"debtor\":\"debtor\",\"amount\":100.0}}");
        ClientConfigManager.resetJsonMigrationCheck();
        LegacyJsonMigrationService.getInstance().inspectAtStartup();
        assertEquals(1, DatabaseManager.getInstance().loadCreditState().credits().size());
        assertTrue(DatabaseManager.getInstance().legacyRecordCount() >= 1);
        assertTrue(ClientConfigManager.isCheckedForJsonMigration());
    }

    @Test
    void automaticMigrationMergesOldJsonIntoAnAlreadyMigratedDatabaseWithoutDroppingOrphanItemPayments() throws Exception {
        useTemporaryDataDirectory();
        CreditEntry existing = new CreditEntry(UUID.randomUUID(), "existing", "creditor", "debtor", 1D, null, null);
        assertTrue(DatabaseManager.getInstance().importLegacy(new DatabaseManager.DatabaseState(List.of(existing), List.of(), List.of()), List.of(), "old migration"));
        List<UUID> orphanCredits = new ArrayList<>();
        for (int index = 0; index < 29; index++) orphanCredits.add(UUID.randomUUID());
        StringBuilder json = new StringBuilder("{");
        for (int index = 0; index < 54; index++) {
            if (index > 0) json.append(',');
            json.append('\"').append(UUID.randomUUID()).append("\":{\"creditId\":\"").append(orphanCredits.get(index % orphanCredits.size()))
                    .append("\",\"fromPlayer\":\"same-player\",\"toPlayer\":\"same-player\",\"amount\":").append(index + 1)
                    .append(",\"items\":[\"1x legacy item\"],\"itemNbt\":\"{components:{legacy:1}}\",\"timestamp\":").append(1_000L + index).append(",\"source\":\"MANUELL\"}");
        }
        json.append('}');
        Files.writeString(FileManager.getPaymentsFile(), json.toString());
        ClientConfigManager.resetJsonMigrationCheck();
        LegacyJsonMigrationService.getInstance().inspectAtStartup();

        DatabaseManager.DatabaseState state = DatabaseManager.getInstance().loadCreditState();
        assertEquals(30, state.credits().size());
        assertEquals(54, state.payments().size());
        assertTrue(state.payments().stream().allMatch(payment -> "same-player".equals(payment.getFromPlayer()) && "{components:{legacy:1}}".equals(payment.getItemNbt())));
        assertTrue(DatabaseManager.getInstance().hasCompletedAutomaticJsonMigration());
    }

    @Test
    void paylogDefaultPageIsLimitedButSearchReachesOlderDatabaseRows() throws Exception {
        useTemporaryDataDirectory();
        List<TransactionEntry> paylogs = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            TransactionEntry entry = new TransactionEntry("player" + index, "me", index + 1D);
            entry.setTimestamp(1_000L + index);
            paylogs.add(entry);
        }
        assertTrue(DatabaseManager.getInstance().importLegacy(new DatabaseManager.DatabaseState(List.of(), List.of(), List.of()), paylogs, "paylog test"));

        assertEquals(500, DatabaseManager.getInstance().queryPaylogs("me", 0, "", 500, 0).size());
        assertEquals("player0", DatabaseManager.getInstance().queryPaylogs("me", 0, "player0", 500, 0).getFirst().getFromPlayer());
    }

    @Test
    void completedDealsLeaveActiveQueriesAndAppearInHistory() throws Exception {
        useTemporaryDataDirectory();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "debtor-creditor", "creditor", "debtor", 100D, null, null);
        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 100D, null, "TEST");
        credit.addPayment(payment);
        assertTrue(DatabaseManager.getInstance().importLegacy(new DatabaseManager.DatabaseState(List.of(credit), List.of(payment), List.of()), List.of(), "history test"));
        CreditRepository repository = new CreditRepository(); repository.load();
        CreditManager manager = new CreditManager(repository);

        assertTrue(manager.getOpenCreditsAsCreditor("creditor").isEmpty());
        assertEquals(1, DatabaseManager.getInstance().queryDealHistory("creditor", "debtor-creditor", 500, 0).size());
    }

    @Test
    void paylogPaginationDedupeAndBackupAreDatabaseBacked() throws Exception {
        useTemporaryDataDirectory();
        List<TransactionEntry> entries = new ArrayList<>();
        for (int index = 0; index < 1_001; index++) {
            TransactionEntry entry = new TransactionEntry("payer" + index, "receiver", index + 1D);
            entry.setTimestamp(1_000_000L + index * 3_000L);
            entry.setRawText("TEST_PAYLOG_" + index);
            entries.add(entry);
        }
        assertTrue(DatabaseManager.getInstance().importLegacy(new DatabaseManager.DatabaseState(List.of(), List.of(), List.of()), entries, "page test"));
        DatabaseManager.QueryPage<TransactionEntry> first = DatabaseManager.getInstance().queryPaylogPage("receiver", 0, "", 500, 0);
        DatabaseManager.QueryPage<TransactionEntry> third = DatabaseManager.getInstance().queryPaylogPage("receiver", 0, "", 500, 1_000);
        assertEquals(500, first.entries().size());
        assertTrue(first.hasNext());
        assertEquals(1, third.entries().size());

        TransactionEntry duplicate = new TransactionEntry("same", "receiver", 5D);
        duplicate.setTimestamp(9_000_000L); duplicate.setRawText("TEST_DUPLICATE");
        assertTrue(DatabaseManager.getInstance().addPaylog(duplicate));
        TransactionEntry repeated = new TransactionEntry("same", "receiver", 5D);
        repeated.setTimestamp(9_000_000L); repeated.setRawText("TEST_DUPLICATE");
        assertFalse(DatabaseManager.getInstance().addPaylog(repeated));
        TransactionEntry distinctRapid = new TransactionEntry("same", "receiver", 5D);
        distinctRapid.setTimestamp(9_000_001L); distinctRapid.setRawText("TEST_DUPLICATE");
        assertTrue(DatabaseManager.getInstance().addPaylog(distinctRapid));
        assertTrue(DatabaseManager.getInstance().createBackup());
        try (var files = Files.list(FileManager.getDataDirectory().resolve("backups"))) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().matches("creditmanager_backup_.*\\.zip")));
        }
        assertFalse(DatabaseManager.getInstance().listBackups().isEmpty());
    }

    @Test
    void healthCheckPersistsFindingsWithoutDuplicatesAndForeignKeysRejectOrphans() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();
        try (Connection connection = connection()) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO credits (id, deal_name, creditor, debtor, amount, paid_amount, created_at, status, archived, revision) VALUES ('" + UUID.randomUUID() + "', 'bad', 'creditor', 'debtor', -1, 0, 1, 'OPEN', FALSE, 0)");
            }
            assertThrows(SQLException.class, () -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO payments (id, credit_id, amount, created_at, revision) VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 1, 1, 0)");
                }
            });
        }
        List<DatabaseManager.DataHealthRecord> first = database.runHealthCheck();
        assertTrue(first.stream().anyMatch(record -> "CREDIT_AMOUNT".equals(record.type())));
        List<DatabaseManager.DataHealthRecord> second = database.runHealthCheck();
        assertEquals(first.size(), second.size());
        DatabaseManager.DataHealthRecord finding = first.stream().filter(record -> "CREDIT_AMOUNT".equals(record.type())).findFirst().orElseThrow();
        assertTrue(database.resolveHealthRecord(finding.id(), "{}", false));
        assertTrue(database.listHealthRecords(false).stream().noneMatch(record -> finding.id().equals(record.id())));
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:file:" + FileManager.getDatabaseFile().toAbsolutePath() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=FALSE");
    }

    private void useTemporaryDataDirectory() throws Exception {
        Files.createDirectories(dataDirectory);
        previousDirectory = (Path) dataDirectoryField().get(null);
        previousConfig = configField().get(null);
        previousConfigRecovery = configRecoveryField().getBoolean(null);
        dataDirectoryField().set(null, dataDirectory);
        configField().set(null, new ClientConfig());
        configRecoveryField().setBoolean(null, false);
    }
    private Field dataDirectoryField() throws Exception { Field field = FileManager.class.getDeclaredField("dataDirectory"); field.setAccessible(true); return field; }
    private Field configField() throws Exception { Field field = ClientConfigManager.class.getDeclaredField("config"); field.setAccessible(true); return field; }
    private Field configRecoveryField() throws Exception { Field field = ClientConfigManager.class.getDeclaredField("recoveryRequired"); field.setAccessible(true); return field; }
}

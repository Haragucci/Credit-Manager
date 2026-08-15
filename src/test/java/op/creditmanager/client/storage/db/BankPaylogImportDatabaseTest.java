package op.creditmanager.client.storage.db;

import op.creditmanager.client.cache.BoundedQueryCache;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.paylog.importer.BankPaylogImportCandidate;
import op.creditmanager.client.paylog.importer.BankPaylogImportResult;
import op.creditmanager.client.paylog.importer.BankPaylogImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPaylogImportDatabaseTest {
    @TempDir Path dataDirectory;
    private long previousRepositoryRevision;
    private boolean previousRepositoryRecovery;
    private StorageTestScope storageScope;

    @BeforeEach
    void initializeTemporaryDatabase() throws Exception {
        TransactionRepository repository = TransactionRepository.getInstance();
        previousRepositoryRevision = repositoryLongField("revision").getLong(repository);
        previousRepositoryRecovery = repositoryBooleanField("recoveryRequired").getBoolean(repository);
        storageScope = new StorageTestScope();
        storageScope.configureExternal(dataDirectory);
        resetDatabaseInitialization();
        DatabaseManager.getInstance().initialize();
        repository.load();
    }

    @AfterEach
    void restoreStatics() throws Exception {
        resetDatabaseInitialization();
        storageScope.close();
        TransactionRepository repository = TransactionRepository.getInstance();
        repositoryLongField("revision").setLong(repository, previousRepositoryRevision);
        repositoryBooleanField("recoveryRequired").setBoolean(repository, previousRepositoryRecovery);
        repositoryCache(repository).clear();
    }

    @Test
    void multiPaylogBatchCommitsCompletelyAndExactReimportAddsNothing() {
        DatabaseManager database = DatabaseManager.getInstance();
        List<TransactionEntry> entries = List.of(paylog("first", 1_000L), paylog("second", 2_000L));

        DatabaseManager.BatchInsertResult first = database.addPaylogsBatchDetailed(entries);
        DatabaseManager.BatchInsertResult second = database.addPaylogsBatchDetailed(entries);

        assertEquals(2, first.inserted());
        assertEquals(0, first.failed());
        assertEquals(0, second.inserted());
        assertEquals(2, second.skipped());
        assertEquals(2, database.findPaylogCandidates(0L, 3_000L).size());
    }

    @Test
    void databaseErrorRollsBackTheCompletePaylogBatch() {
        DatabaseManager database = DatabaseManager.getInstance();
        long revision = database.revision();
        TransactionEntry valid = paylog("valid", 1_000L);
        TransactionEntry invalidForColumn = paylog("too-long-hash", 2_000L);
        invalidForColumn.setHash("x".repeat(65));

        DatabaseManager.BatchInsertResult result =
                database.addPaylogsBatchDetailed(List.of(valid, invalidForColumn));

        assertEquals(0, result.inserted());
        assertEquals(2, result.failed());
        assertEquals(revision, database.revision());
        assertEquals(0, database.findPaylogCandidates(0L, 3_000L).size());
    }

    @Test
    void repositoryBatchRefreshesRevisionAndInvalidatesQueryCache() throws Exception {
        TransactionRepository repository = TransactionRepository.getInstance();
        repository.queryPage("", 0, "bank-import-cache-probe", 500, 0);
        assertTrue(repository.queryCacheStats().entries() > 0);
        long previousRevision = repository.getRevision();

        DatabaseManager.BatchInsertResult result = repository.addBatchDetailed(
                List.of(paylog("repository-first", 1_000L), paylog("repository-second", 2_000L)));

        assertEquals(2, result.inserted());
        assertTrue(repository.getRevision() > previousRevision);
        assertEquals(DatabaseManager.getInstance().revision(), repository.getRevision());
        assertEquals(0, repository.queryCacheStats().entries());
    }

    @Test
    void identicalSameSecondOccurrencesGetStableDistinctHashesAndReimportExactly() {
        long timestamp = 1_723_669_826_000L;
        BankPaylogImportCandidate first = candidate(timestamp, 0L, 3);
        BankPaylogImportCandidate second = candidate(timestamp, 1L, 4);
        BankPaylogImportService service = new BankPaylogImportService();

        BankPaylogImportResult initial = service.importCandidates(1, List.of(first, second));
        BankPaylogImportResult repeated = service.importCandidates(1, List.of(first, second));
        List<TransactionEntry> persisted = DatabaseManager.getInstance()
                .findPaylogCandidates(timestamp, timestamp);

        assertEquals(2, initial.imported());
        assertEquals(0, repeated.imported());
        assertEquals(2, repeated.skippedExact());
        assertEquals(2, persisted.size());
        assertEquals(2, persisted.stream().map(TransactionEntry::getHash).collect(Collectors.toSet()).size());
        assertEquals(Set.of(first.deterministicRawText()), persisted.stream()
                .map(TransactionEntry::getRawText).collect(Collectors.toSet()));
        assertTrue(persisted.stream().allMatch(entry -> entry.getMetadata().contains("\"mergedTransactionCount\":1")));
    }

    private TransactionEntry paylog(String rawText, long timestamp) {
        TransactionEntry entry = new TransactionEntry("payer", "receiver", 1_000L);
        entry.setRawText(rawText);
        entry.setTimestamp(timestamp);
        entry.setSource("OPSUCHT_BANK_IMPORT");
        return entry;
    }

    private BankPaylogImportCandidate candidate(long timestamp, long scanOrder, int slotId) {
        return new BankPaylogImportCandidate("gerry237", "05haragucci", 110_000L, timestamp,
                "14.08.2026 22:50:26", "Gerry237", BankPaylogImportCandidate.Direction.INCOMING,
                1, scanOrder, 1, slotId);
    }

    private void resetDatabaseInitialization() throws Exception {
        DatabaseCoordinator coordinator = coordinator();
        set(coordinator, "initialized", false);
        set(coordinator, "initializedAt", null);
        set(coordinator, "healthy", true);
        set(coordinator, "writeLocked", false);
        set(coordinator, "availability", DatabaseManager.DatabaseAvailability.UNKNOWN);
    }

    private DatabaseCoordinator coordinator() throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("coordinator");
        field.setAccessible(true);
        return (DatabaseCoordinator) field.get(DatabaseManager.getInstance());
    }

    @SuppressWarnings("unchecked")
    private BoundedQueryCache<Object, Object> repositoryCache(TransactionRepository repository) throws Exception {
        Field field = TransactionRepository.class.getDeclaredField("queryCache");
        field.setAccessible(true);
        return (BoundedQueryCache<Object, Object>) field.get(repository);
    }

    private Field repositoryLongField(String name) throws Exception {
        Field field = TransactionRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Field repositoryBooleanField(String name) throws Exception {
        Field field = TransactionRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

package op.creditmanager.client.core.service;

import op.creditmanager.client.config.ClientConfig;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.CreditEventEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditMutationServiceDatabaseTest {
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
    void committedPaymentAndPaylogLinkSurvivePublicationFailureExactlyOnce() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager database = DatabaseManager.getInstance();
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "deal", "creditor", "debtor", 10_000L, null, null);
        assertTrue(database.commitCreditMutation(new DatabaseManager.CreditMutation(
                credit, List.of(), List.of(), List.of())));

        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", 5_000L);
        paylog.setRawText("OPSUCHT » Du hast Jerry237 50$ gegeben.");
        paylog.setSource("CHAT");
        assertTrue(database.addPaylog(paylog));

        Payment payment = new Payment(credit.getId(), "debtor", "creditor", 5_000L, List.of(), "PAYLOG_AUTO");
        payment.setPaylogId(paylog.getId());
        credit.addPayment(payment);
        ReloadingRuntime runtime = new ReloadingRuntime(database, credit.getId(), paylog.getId());
        CreditMutationService service = new CreditMutationService(
                database::commitCreditMutationWithReceipt, runtime);

        MutationCommitResult result = service.commit(credit, List.of(payment), List.of(), List.of(), null);

        assertEquals(MutationCommitResult.Status.COMMITTED_RELOAD_REQUIRED, result.status());
        assertTrue(result.committed());
        assertEquals(1, runtime.publishCount);
        assertEquals(1, runtime.reloadCount);
        DatabaseManager.DatabaseState state = database.loadCreditState();
        assertEquals(1, state.credits().size());
        assertEquals(1, state.payments().size());
        assertEquals(payment.getId(), state.payments().getFirst().getId());
        TransactionEntry reloadedPaylog = database.queryPaylogs("creditor", 0, "", 10, 0).getFirst();
        assertEquals(paylog.getId(), reloadedPaylog.getId());
        assertEquals(5_000L, reloadedPaylog.getLinkedAmountMinor());
    }

    @Test
    void degradedRuntimeStateLocksSubsequentWrites() throws Exception {
        useTemporaryDataDirectory();
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize();

        database.markRuntimeStateDegraded("injected publication and reload failure");

        assertFalse(database.isSafeForWrites());
        CreditEntry credit = new CreditEntry(UUID.randomUUID(), "blocked", "creditor", "debtor", 1_000L, null, null);
        assertFalse(database.commitCreditMutation(new DatabaseManager.CreditMutation(
                credit, List.of(), List.of(), List.of())));
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

    private static final class ReloadingRuntime implements CreditMutationService.MutationRuntime {
        private final DatabaseManager database;
        private final UUID creditId;
        private final UUID paylogId;
        private int publishCount;
        private int reloadCount;

        private ReloadingRuntime(DatabaseManager database, UUID creditId, UUID paylogId) {
            this.database = database;
            this.creditId = creditId;
            this.paylogId = paylogId;
        }

        @Override
        public void publish(CreditEntry draft, List<Payment> paymentUpserts, List<UUID> paymentDeletions,
                            List<CreditEventEntry> events, CreditEntry published, long committedRevision) {
            publishCount++;
            throw new IllegalStateException("injected post-commit publication failure");
        }

        @Override
        public boolean reload() {
            reloadCount++;
            DatabaseManager.DatabaseState state = database.loadCreditState();
            boolean creditPresent = state.credits().stream().anyMatch(credit -> creditId.equals(credit.getId()));
            boolean paymentPresent = state.payments().stream().anyMatch(payment -> paylogId.equals(payment.getPaylogId()));
            return creditPresent && paymentPresent;
        }

        @Override
        public void degrade(String reason) {
            database.markRuntimeStateDegraded(reason);
        }
    }
}

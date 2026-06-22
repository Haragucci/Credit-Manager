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

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaylogLinkingDatabaseTest {
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
    void automaticLinkSkipsAnOlderDealThatCannotCoverThePaylog() throws Exception {
        CreditManager manager = manager();
        CreditEntry first = manager.createCredit("creditor", "debtor", 50D, null, "first", null);
        CreditEntry second = manager.createCredit("creditor", "debtor", 100D, null, "second", null);
        first.setCreatedAt(1L);
        second.setCreatedAt(2L);

        TransactionEntry paylog = paylog(60D);
        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId());

        assertTrue(result.linked());
        assertEquals(second.getId(), result.credit().getId());
        assertTrue(manager.getPaymentsForCredit(first.getId()).isEmpty());
        assertEquals(60D, manager.getPaymentsForCredit(second.getId()).getFirst().getAmount());
    }

    @Test
    void automaticLinkDoesNotSplitOnePaylogAcrossSeveralDeals() throws Exception {
        CreditManager manager = manager();
        manager.createCredit("creditor", "debtor", 50D, null, "first", null);
        manager.createCredit("creditor", "debtor", 50D, null, "second", null);
        TransactionEntry paylog = paylog(70D);

        CreditManager.PaylogLinkResult result = manager.autoLinkDetectedPaylog(paylog.getId());

        assertEquals(CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS, result.status());
        assertTrue(manager.getAllCreditsAsCreditor("creditor").stream().allMatch(credit -> credit.getPayments().isEmpty()));
        assertEquals(70D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());
    }

    @Test
    void manualLinkCapsOverpayAndPaymentDeletionReopensAndReleasesThePaylog() throws Exception {
        CreditManager manager = manager();
        CreditEntry credit = manager.createCredit("creditor", "debtor", 50D, null, "single", null);
        TransactionEntry paylog = paylog(80D);

        CreditManager.PaylogLinkResult result = manager.linkPaylogToDeal(paylog.getId(), credit.getId());
        Payment payment = result.payment();
        assertTrue(result.linked());
        assertEquals(50D, payment.getAmount());
        assertEquals(30D, result.remainingPaylogAmount());
        assertEquals(paylog.getId(), payment.getPaylogId());
        assertEquals(CreditManager.STATUS_PAID, credit.getStatus());

        CreditRepository reloaded = new CreditRepository();
        reloaded.load();
        Payment persisted = reloaded.getPaymentsByCreditId(credit.getId()).getFirst();
        assertEquals(paylog.getId(), persisted.getPaylogId());

        manager.deletePayment(payment.getId());
        assertEquals(CreditManager.STATUS_OPEN, credit.getStatus());
        assertFalse(credit.isArchived());
        assertEquals(80D, TransactionRepository.getInstance().find(paylog.getId()).orElseThrow().getRemainingAmount());

        manager.closeCredit(credit.getId());
        assertTrue(credit.isArchived());
        assertEquals(CreditManager.STATUS_CANCELLED, credit.getStatus());
        CreditRepository archivedReload = new CreditRepository();
        archivedReload.load();
        assertTrue(archivedReload.findCreditById(credit.getId()).orElseThrow().isArchived());
        assertEquals(CreditManager.STATUS_CANCELLED, archivedReload.findCreditById(credit.getId()).orElseThrow().getStatus());
        manager.reactivateCredit(credit.getId());
        assertFalse(credit.isArchived());
        assertEquals(CreditManager.STATUS_OPEN, credit.getStatus());
    }

    private CreditManager manager() throws Exception {
        useTemporaryDataDirectory();
        CreditRepository repository = new CreditRepository();
        repository.load();
        CreditEventRepository.getInstance().bind(repository);
        CreditEventRepository.getInstance().load();
        TransactionRepository.getInstance().load();
        return new CreditManager(repository);
    }

    private TransactionEntry paylog(double amount) {
        TransactionEntry paylog = new TransactionEntry("debtor", "creditor", amount);
        paylog.setRawText("manual test paylog " + UUID.randomUUID());
        paylog.setSource("TEST");
        assertTrue(TransactionRepository.getInstance().add(paylog));
        assertNotNull(paylog.getId());
        return paylog;
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

package op.creditmanager.client.storage.db;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.core.PaymentDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecondaryPrimaryHandoverTest {
    @TempDir Path temporary;

    @Test
    void healthyTakeoverRebindsRepositoriesAndPaymentDetectorWithoutRestart() throws Exception {
        try (StorageTestScope scope = new StorageTestScope()) {
            scope.configureExternal(temporary);
            DatabaseManager.getInstance().initialize();
            CreditRepository repository = new CreditRepository();
            CreditManager manager = new CreditManager(repository);
            setClientField("creditRepository", repository);
            setClientField("creditManager", manager);
            setClientField("paymentDetector", null);
            setClientField("shuttingDown", false);

            assertTrue(CreditManagerClient.rebindAfterStorageRecovery());
            assertNotNull(clientField("paymentDetector").get(null));
            assertTrue(clientField("paymentDetector").get(null) instanceof PaymentDetector);
            assertTrue(repository.isWritable());
        } finally {
            setClientField("paymentDetector", null);
            setClientField("creditManager", null);
            setClientField("creditRepository", null);
            setClientField("shuttingDown", false);
        }
    }

    private Field clientField(String name) throws Exception {
        Field field = CreditManagerClient.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void setClientField(String name, Object value) throws Exception {
        clientField(name).set(null, value);
    }
}

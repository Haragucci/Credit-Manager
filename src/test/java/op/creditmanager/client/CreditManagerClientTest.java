package op.creditmanager.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CreditManagerClientTest {
    @Test
    void callbackBoundaryContainsPredispatchMetadataFailures() {
        assertDoesNotThrow(() -> CreditManagerClient.runPaymentMessageCallback("test", () -> {
            throw new IllegalStateException("injected metadata failure");
        }));
    }

    @Test
    void callbackBoundaryExecutesSuccessfulDispatchExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        CreditManagerClient.runPaymentMessageCallback("test", calls::incrementAndGet);

        assertEquals(1, calls.get());
    }
}

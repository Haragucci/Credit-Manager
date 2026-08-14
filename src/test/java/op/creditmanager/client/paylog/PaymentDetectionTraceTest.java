package op.creditmanager.client.paylog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaymentDetectionTraceTest {
    @Test
    void diagnosticHashesAreDeterministicAndDoNotExposeInput() {
        String value = "private-player-or-message";

        String first = PaymentDetectionTrace.hash(value);

        assertEquals(first, PaymentDetectionTrace.hash(value));
        assertNotEquals(value, first);
        assertEquals(16, first.length());
        assertEquals("none", PaymentDetectionTrace.hash(null));
    }
}

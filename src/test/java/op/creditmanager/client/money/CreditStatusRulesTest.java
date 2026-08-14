package op.creditmanager.client.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditStatusRulesTest {
    @Test
    void derivesEveryExactPaymentBoundaryFromMinorUnits() {
        assertEquals("OPEN", CreditStatusRules.derive(10_000L, 0L));
        assertEquals("PARTIAL", CreditStatusRules.derive(10_000L, 1L));
        assertEquals("PARTIAL", CreditStatusRules.derive(10_000L, 9_999L));
        assertEquals("PAID", CreditStatusRules.derive(10_000L, 10_000L));
        assertThrows(IllegalArgumentException.class, () -> CreditStatusRules.derive(10_000L, 10_001L));
        assertThrows(IllegalArgumentException.class, () -> CreditStatusRules.derive(0L, 0L));
    }
}

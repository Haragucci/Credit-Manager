package op.creditmanager.client.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyRulesTest {
    @Test
    void normalizesAndLimitsAmounts() {
        assertEquals(12.35D, MoneyRules.normalize(12.345D));
        assertEquals(10D, MoneyRules.minRemaining(20D, 10D));
        assertEquals(0D, MoneyRules.minRemaining(-1D, 10D));
        assertTrue(MoneyRules.isPositive(0.01D));
        assertTrue(MoneyRules.isPositive(MoneyRules.MAX_AMOUNT));
        assertFalse(MoneyRules.isPositive(0D));
        assertFalse(MoneyRules.isPositive(MoneyRules.MAX_AMOUNT + 1D));
    }

    @Test
    void parsesGermanAndInternationalAmounts() {
        assertEquals(1234.56D, MoneyRules.parse("1.234,56").orElseThrow());
        assertEquals(1234.56D, MoneyRules.parse("1,234.56").orElseThrow());
        assertEquals(12.5D, MoneyRules.parse("12,50").orElseThrow());
        assertEquals(12.5D, MoneyRules.parse("12.50").orElseThrow());
        assertEquals(10_000D, MoneyRules.parse("10.000").orElseThrow());
        assertEquals(100_000D, MoneyRules.parse("100.000").orElseThrow());
        assertEquals(1_000_000D, MoneyRules.parse("1.000.000").orElseThrow());
        assertEquals(10_100_000D, MoneyRules.parse("10.100.000").orElseThrow());
        assertEquals(1_000_000D, MoneyRules.parse("1,000,000").orElseThrow());
        assertEquals(1_000_000.5D, MoneyRules.parse("1.000.000,50").orElseThrow());
        assertEquals(1_000_000.5D, MoneyRules.parse("1,000,000.50").orElseThrow());
        assertTrue(MoneyRules.parse("abc").isEmpty());
        assertTrue(MoneyRules.parse("1.00.000").isEmpty());
        assertTrue(MoneyRules.parse("1.000,000").isEmpty());
        assertTrue(MoneyRules.parse("1,000.000").isEmpty());
        assertTrue(MoneyRules.parse("1.234.56.78").isEmpty());
    }
}

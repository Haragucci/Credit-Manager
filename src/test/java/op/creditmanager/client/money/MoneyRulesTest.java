package op.creditmanager.client.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyRulesTest {
    @Test
    void normalizesAndLimitsAmounts() {
        assertEquals(1_235L, MoneyRules.fromMajor(new java.math.BigDecimal("12.345"), true).minorUnits());
        assertEquals(1_000L, MoneyRules.minPositive(2_000L, 1_000L));
        assertEquals(0L, MoneyRules.minPositive(-1L, 1_000L));
        assertTrue(MoneyRules.isPositive(1L));
        assertTrue(MoneyRules.isPositive(MoneyRules.MAX_MINOR));
        assertFalse(MoneyRules.isPositive(0L));
    }

    @Test
    void parsesGermanAndInternationalAmounts() {
        assertEquals(123_456L, MoneyRules.parse("1.234,56").orElseThrow().minorUnits());
        assertEquals(123_456L, MoneyRules.parse("1,234.56").orElseThrow().minorUnits());
        assertEquals(1_250L, MoneyRules.parse("12,50").orElseThrow().minorUnits());
        assertEquals(1_250L, MoneyRules.parse("12.50").orElseThrow().minorUnits());
        assertEquals(1_000_000L, MoneyRules.parse("10.000").orElseThrow().minorUnits());
        assertEquals(10_000_000L, MoneyRules.parse("100.000").orElseThrow().minorUnits());
        assertEquals(100_000_000L, MoneyRules.parse("1.000.000").orElseThrow().minorUnits());
        assertEquals(1_010_000_000L, MoneyRules.parse("10.100.000").orElseThrow().minorUnits());
        assertEquals(100_000_000L, MoneyRules.parse("1,000,000").orElseThrow().minorUnits());
        assertEquals(100_000_050L, MoneyRules.parse("1.000.000,50").orElseThrow().minorUnits());
        assertEquals(100_000_050L, MoneyRules.parse("1,000,000.50").orElseThrow().minorUnits());
        assertEquals(125_000L, MoneyRules.parse("1,25k").orElseThrow().minorUnits());
        assertEquals(MoneyRules.MAX_MINOR - 1L, MoneyRules.parse("999999999999999.99").orElseThrow().minorUnits());
        assertEquals(1L, MoneyRules.parse("0.005").orElseThrow().minorUnits());
        assertTrue(MoneyRules.parse("0.004").isEmpty());
        assertTrue(MoneyRules.parse("abc").isEmpty());
        assertTrue(MoneyRules.parse("1.00.000").isEmpty());
        assertTrue(MoneyRules.parse("1.000,000").isEmpty());
        assertTrue(MoneyRules.parse("1,000.000").isEmpty());
        assertTrue(MoneyRules.parse("1.234.56.78").isEmpty());
    }
}

package op.creditmanager.client.money;

import java.math.BigDecimal;

public record MoneyAmount(long minorUnits) implements Comparable<MoneyAmount> {
    public static final MoneyAmount ZERO = new MoneyAmount(0L);

    public MoneyAmount {
        if (!MoneyRules.isValid(minorUnits)) throw new IllegalArgumentException("Betrag liegt außerhalb des erlaubten Bereichs");
    }

    public static MoneyAmount ofMinor(long minorUnits) {
        return minorUnits == 0L ? ZERO : new MoneyAmount(minorUnits);
    }

    public static MoneyAmount positive(long minorUnits) {
        if (!MoneyRules.isPositive(minorUnits)) throw new IllegalArgumentException("Betrag muss positiv sein");
        return ofMinor(minorUnits);
    }

    public BigDecimal majorUnits() {
        return MoneyRules.toMajor(minorUnits);
    }

    public MoneyAmount add(MoneyAmount other) {
        return ofMinor(Math.addExact(minorUnits, other.minorUnits));
    }

    public MoneyAmount subtract(MoneyAmount other) {
        return ofMinor(Math.subtractExact(minorUnits, other.minorUnits));
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    @Override
    public int compareTo(MoneyAmount other) {
        return Long.compare(minorUnits, other.minorUnits);
    }
}

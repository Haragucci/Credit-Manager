package op.creditmanager.client.money;

import java.math.BigInteger;
import java.util.function.ToLongFunction;

public final class MoneyAggregate {
    private MoneyAggregate() { }

    public static <T> BigInteger sum(Iterable<T> values, ToLongFunction<T> amount) {
        BigInteger total = BigInteger.ZERO;
        if (values == null) return total;
        for (T value : values) total = total.add(BigInteger.valueOf(amount.applyAsLong(value)));
        return total;
    }

    public static BigInteger minor(long value) {
        return BigInteger.valueOf(value);
    }
}

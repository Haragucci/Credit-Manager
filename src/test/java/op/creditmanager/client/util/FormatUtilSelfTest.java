package op.creditmanager.client.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FormatUtilSelfTest {
    private FormatUtilSelfTest() { }

    public static void main(String[] args) {
        Map<String, Double> cases = new LinkedHashMap<>();
        cases.put("1.234,56", 1234.56);
        cases.put("1,234.56", 1234.56);
        cases.put("1.234", 1234.0);
        cases.put("1,234", 1234.0);
        cases.put("1234,56", 1234.56);
        cases.put("1234.56", 1234.56);
        cases.put("500k", 500_000.0);
        cases.put("1.5m", 1_500_000.0);
        cases.put("1,5m", 1_500_000.0);
        cases.put("2mio", 2_000_000.0);
        cases.put("1.2mrd", 1_200_000_000.0);

        for (Map.Entry<String, Double> test : cases.entrySet()) {
            double actual = FormatUtil.parseDisplayAmount(test.getKey());
            if (Math.abs(actual - test.getValue()) > 0.0001D) {
                throw new AssertionError(test.getKey() + ": expected " + test.getValue() + ", got " + actual);
            }
        }

        System.out.println("FormatUtil amount parser: " + cases.size() + " cases passed.");
    }
}

package op.creditmanager.client.gui.modern.stats;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernChartRendererTest {
    @Test
    void extremeBigIntegerUsesFinitePixelDomainWithoutChangingValue() {
        BigInteger scale = BigInteger.TEN.pow(100_000);
        BigInteger half = scale.divide(BigInteger.TWO);

        int fullHeight = ModernChartRenderer.scaledBarHeight(scale, scale, 137);
        int halfHeight = ModernChartRenderer.scaledBarHeight(half, scale, 137);

        assertEquals(137, fullHeight);
        assertTrue(halfHeight >= 68 && halfHeight <= 69);
        assertEquals(BigInteger.TEN.pow(100_000), scale);
    }
}

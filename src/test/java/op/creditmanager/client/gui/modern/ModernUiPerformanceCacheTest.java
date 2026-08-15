package op.creditmanager.client.gui.modern;

import net.minecraft.text.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernUiPerformanceCacheTest {

    @AfterEach
    void resetCaches() {
        ModernUi.resetPerformanceCaches();
    }

    @Test
    void textAndAnimationCachesStayBoundedWithoutFullClear() {
        Text retained = null;
        for (int index = 0; index < 1_200; index++) {
            Text text = ModernUi.guiText("value-" + index);
            if (index == 1_199) retained = text;
            ModernUi.animationProgress("hover-" + index, false);
            ModernUi.animatedPosition("position-" + index, index);
        }

        assertEquals(1_024, ModernUi.cachedTextCount());
        assertEquals(512, ModernUi.hoverAnimationCount());
        assertEquals(64, ModernUi.positionAnimationCount());
        assertSame(retained, ModernUi.guiText("value-1199"));
        assertTrue(ModernUi.hoverAnimationCount() > 1);
        assertTrue(ModernUi.positionAnimationCount() > 1);
    }

    @Test
    void fontInvalidationDropsCachedText() {
        Text before = ModernUi.guiText("cached");

        ModernUi.invalidateTextCaches();

        Text after = ModernUi.guiText("cached");
        assertNotSame(before, after);
        assertEquals(1, ModernUi.cachedTextCount());
    }

    @Test
    void binaryTrimMatchesLegacyResultWithFarFewerMeasurements() {
        String value = "abcdefghijklmnopqrstuvwxyz0123456789";
        CountingWidth width = new CountingWidth();

        String result = ModernUi.trimText(value, 15, width);

        assertEquals("abcdefghijkl...", result);
        assertTrue(width.calls < 10);
        assertEquals("...", ModernUi.trimText(value, 2, String::length));
        assertEquals(value, ModernUi.trimText(value, value.length(), String::length));
    }

    private static final class CountingWidth implements ToIntFunction<String> {
        private int calls;

        @Override
        public int applyAsInt(String value) {
            calls++;
            return value.length();
        }
    }
}

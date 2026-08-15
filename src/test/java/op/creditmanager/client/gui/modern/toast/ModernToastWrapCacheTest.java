package op.creditmanager.client.gui.modern.toast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ModernToastWrapCacheTest {

    @Test
    void keepsLegacyWrappingSemanticsForWordsAndLongTokens() {
        ToIntFunction<String> width = String::length;

        assertEquals(List.of("eins zwei", "drei"),
                ModernToastManager.wrap("eins zwei drei", 9, width));
        assertEquals(List.of("abcde", "fghij", "k"),
                ModernToastManager.wrap("abcdefghijk", 5, width));
        assertEquals(List.of(""), ModernToastManager.wrap("", 5, width));
    }

    @Test
    void reusesLinesUntilWidthOrFontEpochChanges() {
        ModernToast toast = new ModernToast("eins zwei drei", ModernToastType.INFO);
        AtomicInteger measurements = new AtomicInteger();
        ToIntFunction<String> width = value -> {
            measurements.incrementAndGet();
            return value.length();
        };

        List<String> first = toast.wrappedLines(9, 1L, width);
        int initialMeasurements = measurements.get();
        for (int frame = 0; frame < 100; frame++) {
            assertSame(first, toast.wrappedLines(9, 1L, width));
        }

        assertEquals(initialMeasurements, measurements.get());
        toast.wrappedLines(8, 1L, width);
        int afterWidthChange = measurements.get();
        toast.wrappedLines(8, 2L, width);

        assertEquals(true, afterWidthChange > initialMeasurements);
        assertEquals(true, measurements.get() > afterWidthChange);
    }
}

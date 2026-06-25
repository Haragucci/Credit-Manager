package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernLayoutTest {
    @Test
    void buttonRowsStayInsideTheirAvailableWidthOrStackVertically() {
        for (int width : List.of(1, 80, 176, 360)) {
            List<ModernLayout.Bounds> bounds = ModernLayout.buttonRow(10, 20, width, 2, 88, 23, 8);
            assertEquals(2, bounds.size());
            assertTrue(bounds.stream().allMatch(value -> value.width() >= 1 && value.height() == 23));
            if (ModernLayout.stack(width, 2, 88, 8)) {
                assertEquals(bounds.getFirst().x(), bounds.get(1).x());
                assertTrue(bounds.get(1).y() >= bounds.getFirst().bottom());
            } else {
                assertTrue(bounds.stream().allMatch(value -> value.x() >= 10 && value.right() <= 10 + width));
                assertFalse(bounds.getFirst().overlaps(bounds.get(1)));
            }
        }
    }

    @Test
    void inventoryGridAlwaysHasAtLeastOneColumnAndViewportChecksAreStrict() {
        assertEquals(1, ModernLayout.inventoryColumns(1, 16, 9));
        assertEquals(9, ModernLayout.inventoryColumns(400, 16, 9));
        assertTrue(ModernLayout.visibleInViewport(10, 20, 0, 30));
        assertFalse(ModernLayout.visibleInViewport(30, 10, 0, 30));
        assertFalse(ModernLayout.visibleInViewport(-11, 10, 0, 30));
    }
}

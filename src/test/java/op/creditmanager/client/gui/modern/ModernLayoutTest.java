package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernLayoutTest {
    @Test
    void tinyLogicalSizesKeepCreateAndEditActionsBoundedAndScrollable() {
        for (int[] size : List.of(new int[]{160, 120}, new int[]{200, 140}, new int[]{320, 180})) {
            ModernLayout.ShellBounds shell = ModernLayout.shell(size[0], size[1]);
            assertTrue(shell.contentWidth() > 0);
            assertTrue(shell.contentHeight() > 0);
            assertTrue(shell.contentX() >= shell.panelX());
            assertTrue(shell.contentX() + shell.contentWidth() <= shell.panelX() + shell.panelWidth());

            assertReachable(shell, 2, 74, 287);
            assertReachable(shell, 3, 74, ModernLayout.stack(Math.max(1, shell.contentWidth() - 8), 3, 74, 8) ? 558 : 496);
            assertTrue(Math.max(1, shell.contentWidth() - 22) <= shell.contentWidth());
        }
    }

    private void assertReachable(ModernLayout.ShellBounds shell, int count, int minimumWidth, int formHeight) {
        List<ModernLayout.Bounds> actions = ModernLayout.buttonRow(shell.contentX(), 0, shell.contentWidth(), count, minimumWidth, 23, 8);
        for (ModernLayout.Bounds bounds : actions) {
            assertTrue(bounds.x() >= shell.contentX());
            assertTrue(bounds.right() <= shell.contentX() + shell.contentWidth());
            assertTrue(bounds.width() > 0);
        }
        for (int left = 0; left < actions.size(); left++) {
            for (int right = left + 1; right < actions.size(); right++) assertFalse(actions.get(left).overlaps(actions.get(right)));
        }
        int viewportHeight = Math.max(1, shell.contentHeight() - 8);
        ModernScrollArea scroll = new ModernScrollArea();
        scroll.setBounds(shell.contentX(), shell.contentY(), shell.contentWidth(), viewportHeight, formHeight);
        if (formHeight > viewportHeight) {
            assertTrue(scroll.isScrollable());
            scroll.scroll(-10_000D);
            for (int index = 0; index < 100; index++) scroll.tick(0, 0);
            assertEquals(formHeight - viewportHeight, scroll.offset());
        }
    }
}

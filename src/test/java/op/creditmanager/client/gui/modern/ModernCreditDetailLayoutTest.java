package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernCreditDetailLayoutTest {
    private static final int LINE_HEIGHT = 12;

    @Test
    void noNoteKeepsThePreviousCompactDetailFlow() {
        ModernCreditDetailLayout.Layout layout = ModernCreditDetailLayout.calculate(420, 300,
                false, false, 0, LINE_HEIGHT);

        assertNull(layout.noteCard());
        assertNull(layout.noteViewport());
        assertNull(layout.noteToggle());
        assertEquals(97, layout.actions().getFirst().y());
        assertFalse(layout.actions().getFirst().overlaps(layout.paymentList()));
        assertTrue(layout.paymentList().height() >= 44);
    }

    @Test
    void shortNoteIsFullyVisibleInTheSharedDebtAndClaimDetailLayout() {
        for (String note : List.of("x", "Kurze Deal-Notiz")) {
            List<String> lines = ModernCreditDetailLayout.wrapNote(note, 160, String::length);
            ModernCreditDetailLayout.Layout debtLayout = ModernCreditDetailLayout.calculate(420, 300,
                    true, false, lines.size(), LINE_HEIGHT);
            ModernCreditDetailLayout.Layout claimLayout = ModernCreditDetailLayout.calculate(420, 300,
                    true, false, lines.size(), LINE_HEIGHT);

            assertEquals(List.of(note), lines);
            assertEquals(debtLayout, claimLayout);
            assertNotNull(debtLayout.noteCard());
            assertFalse(debtLayout.noteCollapsible());
            assertNull(debtLayout.noteToggle());
            assertEquals(LINE_HEIGHT, debtLayout.noteViewport().height());
            assertFalse(debtLayout.noteCard().overlaps(debtLayout.actions().getFirst()));
        }
    }

    @Test
    void manualLinesAndWrappedTextNeverOverlapFollowingControls() {
        String note = "Erste Zeile\n\nZweite Zeile mit deutlich mehr Inhalt für einen automatischen Umbruch";
        List<String> lines = ModernCreditDetailLayout.wrapNote(note, 18, String::length);
        ModernCreditDetailLayout.Layout layout = ModernCreditDetailLayout.calculate(260, 300,
                true, false, lines.size(), LINE_HEIGHT);

        assertEquals("Erste Zeile", lines.getFirst());
        assertEquals("", lines.get(1));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 18));
        assertTrue(layout.noteCard().bottom() <= layout.actions().stream().mapToInt(ModernLayout.Bounds::y).min().orElseThrow());
        assertTrue(layout.actions().stream().mapToInt(ModernLayout.Bounds::bottom).max().orElseThrow() < layout.paymentList().y());
    }

    @Test
    void maximumNoteUsesBoundedExpandedViewportAndRemainsFullyScrollable() {
        String note = ("langes wort mit inhalt und manueller zeile\n").repeat(100);
        note = note.substring(0, Math.min(note.length(), CreditValidationRules.MAX_NOTE_LENGTH));
        List<String> lines = ModernCreditDetailLayout.wrapNote(note, 34, String::length);
        ModernCreditDetailLayout.Layout layout = ModernCreditDetailLayout.calculate(420, 394,
                true, true, lines.size(), LINE_HEIGHT);

        assertTrue(lines.size() > ModernCreditDetailLayout.PREVIEW_LINES);
        assertTrue(layout.noteExpanded());
        assertTrue(layout.noteScrollable());
        assertTrue(layout.noteViewport().height() <= ModernCreditDetailLayout.MAX_NOTE_VIEWPORT_HEIGHT);
        assertTrue(layout.noteContentHeight() >= lines.size() * LINE_HEIGHT);
        assertTrue(layout.paymentList().height() >= 44);
        assertFalse(layout.noteCard().overlaps(layout.paymentList()));

        ModernScrollArea scroll = new ModernScrollArea();
        scroll.setBounds(layout.noteViewport().x(), layout.noteViewport().y(), layout.noteViewport().width(),
                layout.noteViewport().height(), layout.noteContentHeight());
        scroll.scroll(-10_000D);
        settle(scroll);
        assertEquals(layout.noteContentHeight() - layout.noteViewport().height(), scroll.offset());
    }

    @Test
    void tinyViewportsKeepActionsBoundedAndEverySectionReachable() {
        for (int[] size : List.of(new int[]{160, 120}, new int[]{200, 140}, new int[]{320, 180})) {
            ModernLayout.ShellBounds shell = ModernLayout.shell(size[0], size[1]);
            int noteWidth = ModernCreditDetailLayout.noteTextWidth(shell.contentWidth());
            List<String> lines = ModernCreditDetailLayout.wrapNote("x ".repeat(2_048), noteWidth, String::length);
            ModernCreditDetailLayout.Layout layout = ModernCreditDetailLayout.calculate(shell.contentWidth(),
                    shell.contentHeight(), true, true, lines.size(), LINE_HEIGHT);

            assertTrue(layout.pageScrollable(shell.contentHeight()));
            assertTrue(layout.noteScrollable());
            assertWithinDocument(layout.summary(), shell.contentWidth(), layout.documentHeight());
            assertWithinDocument(layout.noteCard(), shell.contentWidth(), layout.documentHeight());
            assertWithinDocument(layout.noteToggle(), shell.contentWidth(), layout.documentHeight());
            assertWithinDocument(layout.paymentList(), shell.contentWidth(), layout.documentHeight());
            for (ModernLayout.Bounds action : layout.actions()) assertWithinDocument(action, shell.contentWidth(), layout.documentHeight());
            for (int left = 0; left < layout.actions().size(); left++) {
                for (int right = left + 1; right < layout.actions().size(); right++) {
                    assertFalse(layout.actions().get(left).overlaps(layout.actions().get(right)));
                }
            }
            assertFalse(layout.noteCard().overlaps(layout.actions().getFirst()));
            assertFalse(layout.actions().getLast().overlaps(layout.paymentList()));

            ModernScrollArea page = new ModernScrollArea();
            page.setBounds(shell.contentX(), shell.contentY(), shell.contentWidth(), shell.contentHeight(), layout.documentHeight());
            page.scroll(-10_000D);
            settle(page);
            assertEquals(layout.documentHeight() - shell.contentHeight(), page.offset());
        }
    }

    @Test
    void unbreakableSequenceIsSplitWithoutHorizontalOverflowOrDataLoss() {
        String token = "https://example.invalid/" + "ABC123".repeat(100);
        List<String> lines = ModernCreditDetailLayout.wrapNote(token, 17, String::length);

        assertTrue(lines.size() > 1);
        assertTrue(lines.stream().allMatch(line -> !line.isEmpty() && line.length() <= 17));
        assertEquals(token, String.join("", lines));
    }

    private void assertWithinDocument(ModernLayout.Bounds bounds, int width, int documentHeight) {
        assertNotNull(bounds);
        assertTrue(bounds.x() >= 0);
        assertTrue(bounds.right() <= width);
        assertTrue(bounds.y() >= 0);
        assertTrue(bounds.bottom() <= documentHeight);
        assertTrue(bounds.width() > 0);
        assertTrue(bounds.height() > 0);
    }

    private void settle(ModernScrollArea scroll) {
        for (int index = 0; index < 100; index++) scroll.tick(0, 0);
    }
}

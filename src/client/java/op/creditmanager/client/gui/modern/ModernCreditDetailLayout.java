package op.creditmanager.client.gui.modern;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public final class ModernCreditDetailLayout {
    public static final int SUMMARY_HEIGHT = 89;
    public static final int ACTION_HEIGHT = 23;
    public static final int PAYMENT_ROW_HEIGHT = 39;
    public static final int PREVIEW_LINES = 3;
    public static final int MAX_NOTE_VIEWPORT_HEIGHT = 132;

    private ModernCreditDetailLayout() { }

    public static String normalizeNote(String value) {
        return value == null ? "" : value.trim();
    }

    public static int noteTextWidth(int contentWidth) {
        return Math.max(1, contentWidth - 32);
    }

    public static List<String> wrapNote(String value, int maxWidth, ToIntFunction<String> width) {
        if (width == null) throw new IllegalArgumentException("Text width function is required");
        String normalized = normalizeNote(value).replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) return List.of();
        int safeWidth = Math.max(1, maxWidth);
        List<String> lines = new ArrayList<>();
        for (String paragraph : normalized.split("\n", -1)) wrapParagraph(paragraph, safeWidth, width, lines);
        return List.copyOf(lines);
    }

    public static Layout calculate(int contentWidth, int contentHeight, boolean noteVisible,
                                   boolean noteExpanded, int noteLineCount, int lineHeight) {
        int width = Math.max(1, contentWidth);
        int height = Math.max(1, contentHeight);
        int safeLineHeight = Math.max(1, lineHeight);
        ModernLayout.Bounds summary = new ModernLayout.Bounds(0, 5, width, SUMMARY_HEIGHT);
        ModernLayout.Bounds noteCard = null;
        ModernLayout.Bounds noteViewport = null;
        ModernLayout.Bounds noteToggle = null;
        int cursor = summary.bottom() + 3;
        boolean collapsible = noteVisible && noteLineCount > PREVIEW_LINES;
        boolean expanded = collapsible && noteExpanded;

        if (noteVisible) {
            int viewportHeight;
            if (expanded) {
                int actionBlock = ModernLayout.stack(width, 3, 74, 8) ? 3 * ACTION_HEIGHT + 16 : ACTION_HEIGHT;
                int fixedHeight = summary.bottom() + 8 + 57 + 8 + actionBlock + 13 + 15 + 44 + 4;
                viewportHeight = Math.max(safeLineHeight * PREVIEW_LINES,
                        Math.min(MAX_NOTE_VIEWPORT_HEIGHT, height - fixedHeight));
            } else {
                viewportHeight = Math.max(safeLineHeight, Math.min(Math.max(1, noteLineCount), PREVIEW_LINES) * safeLineHeight);
            }
            int noteHeight = (collapsible ? 57 : 34) + viewportHeight;
            int noteY = summary.bottom() + 8;
            noteCard = new ModernLayout.Bounds(0, noteY, width, noteHeight);
            noteViewport = new ModernLayout.Bounds(12, noteY + 24, noteTextWidth(width), viewportHeight);
            if (collapsible) {
                int toggleWidth = Math.max(1, Math.min(104, width - 24));
                noteToggle = new ModernLayout.Bounds(width - 12 - toggleWidth, noteCard.bottom() - 28,
                        toggleWidth, 20);
            }
            cursor = noteCard.bottom() + 8;
        }

        List<ModernLayout.Bounds> actions = ModernLayout.buttonRow(0, cursor, width, 3, 74, ACTION_HEIGHT, 8);
        int actionBottom = actions.stream().mapToInt(ModernLayout.Bounds::bottom).max().orElse(cursor);
        int paymentTitleY = actionBottom + 13;
        int paymentListY = paymentTitleY + 15;
        int paymentHeight = Math.max(44, height - paymentListY - 4);
        ModernLayout.Bounds paymentList = new ModernLayout.Bounds(0, paymentListY, width, paymentHeight);
        int documentHeight = Math.max(height, paymentList.bottom() + 4);
        return new Layout(summary, noteCard, noteViewport, noteToggle, actions, paymentTitleY,
                paymentList, documentHeight, collapsible, expanded,
                Math.max(noteViewport == null ? 0 : noteViewport.height(), noteLineCount * safeLineHeight));
    }

    private static void wrapParagraph(String paragraph, int maxWidth, ToIntFunction<String> width, List<String> lines) {
        if (paragraph.isEmpty()) {
            lines.add("");
            return;
        }
        int start = 0;
        while (start < paragraph.length()) {
            while (start < paragraph.length() && Character.isWhitespace(paragraph.charAt(start))) start++;
            if (start >= paragraph.length()) {
                if (lines.isEmpty() || !lines.getLast().isEmpty()) lines.add("");
                return;
            }
            int end = maximumFittingEnd(paragraph, start, maxWidth, width);
            if (end >= paragraph.length()) {
                lines.add(paragraph.substring(start));
                return;
            }
            int breakAt = end;
            for (int index = end - 1; index > start; index--) {
                if (Character.isWhitespace(paragraph.charAt(index))) {
                    breakAt = index;
                    break;
                }
            }
            if (breakAt == start) breakAt = end;
            String line = paragraph.substring(start, breakAt).stripTrailing();
            if (line.isEmpty()) {
                breakAt = end;
                line = paragraph.substring(start, breakAt);
            }
            lines.add(line);
            start = breakAt;
        }
    }

    private static int maximumFittingEnd(String value, int start, int maxWidth, ToIntFunction<String> width) {
        int low = start + 1;
        int high = value.length();
        int best = start;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (width.applyAsInt(value.substring(start, middle)) <= maxWidth) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best == start) return Math.min(value.length(), start + Character.charCount(value.codePointAt(start)));
        if (best < value.length() && Character.isHighSurrogate(value.charAt(best - 1))
                && Character.isLowSurrogate(value.charAt(best))) {
            best = best - 1 > start ? best - 1 : Math.min(value.length(), start + 2);
        }
        return Math.max(start + 1, best);
    }

    public record Layout(ModernLayout.Bounds summary, ModernLayout.Bounds noteCard,
                         ModernLayout.Bounds noteViewport, ModernLayout.Bounds noteToggle,
                         List<ModernLayout.Bounds> actions, int paymentTitleY,
                         ModernLayout.Bounds paymentList, int documentHeight,
                         boolean noteCollapsible, boolean noteExpanded, int noteContentHeight) {
        public Layout {
            actions = List.copyOf(actions);
        }

        public boolean pageScrollable(int viewportHeight) {
            return documentHeight > Math.max(1, viewportHeight);
        }

        public boolean noteScrollable() {
            return noteExpanded && noteViewport != null && noteContentHeight > noteViewport.height();
        }
    }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.ArrayList;
import java.util.List;

public final class ModernLayout {
    private ModernLayout() { }

    public static int safeWidth(int width, int minimum) {
        return Math.max(minimum, width);
    }

    public static boolean stack(int availableWidth, int count, int minimumWidth, int gap) {
        return availableWidth < count * minimumWidth + Math.max(0, count - 1) * gap;
    }

    public static List<Bounds> buttonRow(int x, int y, int width, int count, int minimumWidth, int height, int gap) {
        if (count <= 0) return List.of();
        int safeGap = Math.max(0, gap);
        List<Bounds> bounds = new ArrayList<>(count);
        if (stack(width, count, minimumWidth, safeGap)) {
            for (int index = 0; index < count; index++) bounds.add(new Bounds(x, y + index * (height + safeGap), Math.max(1, width), height));
            return List.copyOf(bounds);
        }
        int buttonWidth = Math.max(1, (width - safeGap * (count - 1)) / count);
        for (int index = 0; index < count; index++) {
            int buttonX = x + index * (buttonWidth + safeGap);
            int remaining = x + width - buttonX;
            bounds.add(new Bounds(buttonX, y, Math.max(1, Math.min(buttonWidth, remaining)), height));
        }
        return List.copyOf(bounds);
    }

    public static int rowHeight(List<Bounds> bounds, int gap) {
        if (bounds == null || bounds.isEmpty()) return 0;
        int top = bounds.stream().mapToInt(Bounds::y).min().orElse(0);
        int bottom = bounds.stream().mapToInt(value -> value.y() + value.height()).max().orElse(top);
        return bottom - top + Math.max(0, gap);
    }

    public static boolean visibleInViewport(int y, int height, int viewportY, int viewportHeight) {
        return y + height > viewportY && y < viewportY + Math.max(0, viewportHeight);
    }

    public static void positionTextField(TextFieldWidget field, int x, int y, int width, int viewportY, int viewportHeight, boolean enabled) {
        if (field == null) return;
        field.setPosition(x, y);
        field.setWidth(Math.max(1, width));
        field.setVisible(enabled && visibleInViewport(y, field.getHeight(), viewportY, viewportHeight));
    }

    public static int inventoryColumns(int availableWidth, int slotSize, int preferredColumns) {
        if (slotSize <= 0) return 1;
        return Math.max(1, Math.min(preferredColumns, Math.max(1, availableWidth / slotSize)));
    }

    public record Bounds(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean overlaps(Bounds other) {
            return other != null && x < other.right() && right() > other.x && y < other.bottom() && bottom() > other.y;
        }
    }
}

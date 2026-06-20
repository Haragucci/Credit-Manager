package op.creditmanager.client.gui.modern.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import org.lwjgl.glfw.GLFW;
import op.creditmanager.client.gui.modern.ModernUi;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;

/** Smooth pixel-scroll model with a proportional, directly draggable scrollbar. */
public final class ModernScrollArea {
    private double position;
    private double target;
    private int x;
    private int y;
    private int width;
    private int viewportHeight;
    private int contentHeight;
    private boolean dragging;

    public void setBounds(int x, int y, int width, int viewportHeight, int contentHeight) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.viewportHeight = Math.max(1, viewportHeight);
        this.contentHeight = Math.max(this.viewportHeight, contentHeight);
        clamp();
    }

    public void scroll(double verticalAmount) {
        target -= verticalAmount * 18.0;
        clamp();
    }

    public void scrollToStart() {
        position = 0.0;
        target = 0.0;
    }

    /** Clears a potentially active thumb drag before a screen is hidden or replaced. */
    public void reset() {
        position = 0.0;
        target = 0.0;
        dragging = false;
    }

    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + viewportHeight;
    }

    public void tick(int mouseX, int mouseY) {
        if (dragging) {
            long handle = MinecraftClient.getInstance().getWindow().getHandle();
            if (GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS) {
                int thumbHeight = thumbHeight();
                int trackRange = Math.max(1, viewportHeight - thumbHeight);
                target = (mouseY - y - thumbHeight / 2.0) / trackRange * maxScroll();
                clamp();
            } else {
                dragging = false;
            }
        }
        position += (target - position) * 0.30;
        if (Math.abs(target - position) < 0.15) position = target;
    }

    public int offset() {
        return (int) Math.round(position);
    }

    public boolean isScrollable() {
        return maxScroll() > 0.0;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isScrollable()) return false;
        int trackX = x + width - 5;
        if (mouseX < trackX - 2 || mouseX > trackX + 6 || mouseY < y || mouseY > y + viewportHeight) return false;
        dragging = true;
        return true;
    }

    public void renderScrollbar(DrawContext context, int mouseX, int mouseY) {
        if (!isScrollable()) return;
        ModernThemePalette theme = ModernUi.theme();
        int trackX = x + width - 5;
        int thumbHeight = thumbHeight();
        int range = Math.max(1, viewportHeight - thumbHeight);
        int thumbY = y + (int) Math.round(range * position / maxScroll());
        context.fill(trackX, y, trackX + 3, y + viewportHeight, ColorUtil.withAlpha(theme.border, 110));
        boolean hovered = mouseX >= trackX - 2 && mouseX <= trackX + 5 && mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
        context.fill(trackX - 1, thumbY, trackX + 4, thumbY + thumbHeight, hovered || dragging ? theme.accent : theme.muted);
    }

    private int thumbHeight() {
        return Math.max(16, Math.min(viewportHeight, Math.round(viewportHeight * (viewportHeight / (float) contentHeight))));
    }

    private double maxScroll() {
        return Math.max(0, contentHeight - viewportHeight);
    }

    private void clamp() {
        target = Math.max(0, Math.min(maxScroll(), target));
        position = Math.max(0, Math.min(maxScroll(), position));
    }
}

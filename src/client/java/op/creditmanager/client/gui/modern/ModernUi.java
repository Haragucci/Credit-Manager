package op.creditmanager.client.gui.modern;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** Shared rendering primitives for the non-inventory CreditManager interface. */
public final class ModernUi {

    public static final int OVERLAY = 0xC7071108;
    public static final int PANEL = 0xFF0B170E;
    public static final int PANEL_ALT = 0xFF102618;
    public static final int CARD = 0xFF162D1B;
    public static final int CARD_HOVER = 0xFF214126;
    public static final int BORDER = 0xFF3D7637;
    public static final int TEXT = 0xFFF2F8E9;
    public static final int MUTED = 0xFFA7BC9D;
    public static final int GREEN = 0xFF86E34B;
    public static final int RED = 0xFFE46B4A;
    public static final int YELLOW = 0xFFF0B83F;
    public static final int BLUE = 0xFFBCE5D0;
    public static final int BUTTON_PRIMARY = 0xFF216D2B;
    public static final int BUTTON_GOLD = 0xFF8B641D;
    public static final int BUTTON_NEUTRAL = 0xFF354E37;
    public static final int BUTTON_DANGER = 0xFF743B2F;
    public static final int NAV_ACTIVE = 0xFF275F2C;
    public static final int NAV_HOVER = 0xFF1E3A24;
    public static final int SELECTION = 0x6086E34B;
    public static final int HOVER_POSITIVE = 0x3386E34B;
    public static final int HOVER_DANGER = 0x33743B2F;
    public static final int SHADOW = 0xFF071008;

    private ModernUi() {
    }

    public static void panel(DrawContext context, int x, int y, int width, int height, int color) {
        context.fill(x - 2, y - 2, x + width + 2, y + height + 3, 0x77040A05);
        context.fill(x, y, x + width, y + height, color);
        context.fill(x, y, x + width, y + 1, BORDER);
        context.fill(x, y, x + 1, y + height, BORDER);
        context.fill(x + width - 1, y, x + width, y + height, SHADOW);
        context.fill(x, y + height - 1, x + width, y + height, SHADOW);
    }

    public static void card(DrawContext context, int x, int y, int width, int height, boolean hovered) {
        int color = hovered ? CARD_HOVER : CARD;
        context.fill(x, y, x + width, y + height, color);
        context.fill(x, y, x + width, y + 1, hovered ? BLUE : BORDER);
        context.fill(x, y, x + 1, y + height, BORDER);
        context.fill(x + width - 1, y, x + width, y + height, SHADOW);
        context.fill(x, y + height - 1, x + width, y + height, SHADOW);
    }

    public static void button(DrawContext context, TextRenderer textRenderer, int x, int y, int width, int height,
                              String label, int color, boolean hovered) {
        int background = hovered ? lighten(color, 20) : color;
        context.fill(x, y, x + width, y + height, SHADOW);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, lighten(background, 18));
        drawCentered(context, textRenderer, truncate(textRenderer, label, Math.max(1, width - 8)),
                x + width / 2, y + (height - 8) / 2, TEXT);
    }

    public static void drawCentered(DrawContext context, TextRenderer textRenderer, String value,
                                    int centerX, int y, int color) {
        context.drawText(textRenderer, Text.literal(value), centerX - textRenderer.getWidth(value) / 2, y, color, false);
    }

    public static void drawTruncated(DrawContext context, TextRenderer textRenderer, String value,
                                     int x, int y, int maxWidth, int color) {
        context.drawText(textRenderer, Text.literal(truncate(textRenderer, value, maxWidth)), x, y, color, false);
    }

    public static String truncate(TextRenderer textRenderer, String value, int maxWidth) {
        String safe = value == null ? "" : value;
        if (textRenderer.getWidth(safe) <= maxWidth) {
            return safe;
        }

        String suffix = "...";
        int end = safe.length();
        while (end > 0 && textRenderer.getWidth(safe.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return safe.substring(0, end) + suffix;
    }

    public static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int lighten(int color, int amount) {
        int red = Math.min(255, ((color >> 16) & 0xFF) + amount);
        int green = Math.min(255, ((color >> 8) & 0xFF) + amount);
        int blue = Math.min(255, (color & 0xFF) + amount);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiFontMode;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemeManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;

import java.util.HashMap;
import java.util.Map;

public final class ModernUi {

    public static final Identifier GUI_FONT = Identifier.of("creditmanager", "gui");
    private static final Style MODERN_GUI_STYLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(GUI_FONT));
    private static final Map<String, HoverAnimation> HOVER_ANIMATIONS = new HashMap<>();
    private static final Map<String, PositionAnimation> POSITION_ANIMATIONS = new HashMap<>();

    private ModernUi() {
    }

    private static final class HoverAnimation {
        private float value;
        private long updatedAt;

        private HoverAnimation(long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }

    private static final class PositionAnimation {
        private float value;
        private long updatedAt;

        private PositionAnimation(float value, long updatedAt) {
            this.value = value;
            this.updatedAt = updatedAt;
        }
    }

    public static ModernThemePalette theme() {
        return ModernThemeManager.current();
    }

    public static void panel(DrawContext context, int x, int y, int width, int height, int color) {
        ModernThemePalette theme = theme();
        context.fill(x - 2, y - 2, x + width + 2, y + height + 3, ColorUtil.withAlpha(theme.shadow, 110));
        context.fill(x, y, x + width, y + height, color);
        context.fill(x, y, x + width, y + 1, theme.border);
        context.fill(x, y, x + 1, y + height, theme.border);
        context.fill(x + width - 1, y, x + width, y + height, theme.shadow);
        context.fill(x, y + height - 1, x + width, y + height, theme.shadow);
    }

    public static void card(DrawContext context, int x, int y, int width, int height, boolean hovered) {
        ModernThemePalette theme = theme();
        float hover = animationProgress("card:" + x + ':' + y + ':' + width + ':' + height, hovered);
        int color = ColorUtil.mix(theme.card, theme.cardHover, hover);
        context.fill(x, y, x + width, y + height, color);
        context.fill(x, y, x + width, y + 1, ColorUtil.mix(theme.border, theme.accent, hover));
        context.fill(x, y, x + 1, y + height, theme.border);
        context.fill(x + width - 1, y, x + width, y + height, theme.shadow);
        context.fill(x, y + height - 1, x + width, y + height, theme.shadow);
    }

    public static void button(DrawContext context, TextRenderer textRenderer, int x, int y, int width, int height,
                              String label, int color, boolean hovered) {
        ModernThemePalette theme = theme();
        float hover = animationProgress("button:" + label + ':' + x + ':' + y + ':' + width + ':' + height, hovered);
        int background = ColorUtil.lighten(color, 0.10F * hover);
        context.fill(x, y, x + width, y + height, theme.shadow);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, ColorUtil.lighten(background, 0.10F));
        drawGuiTextCentered(context, textRenderer, trimGuiText(textRenderer, label, Math.max(1, width - 8)),
                x + width / 2, y + (height - 8) / 2, ColorUtil.contrastText(background));
    }

    public static void closeButton(DrawContext context, TextRenderer textRenderer, int x, int y, boolean hovered) {
        ModernThemePalette theme = theme();
        int background = ColorUtil.lighten(theme.buttonDanger, 0.13F * animationProgress("close:" + x + ':' + y, hovered));
        context.fill(x - 1, y - 1, x + 19, y + 19, theme.shadow);
        context.fill(x, y, x + 18, y + 18, background);
        drawGuiTextCentered(context, textRenderer, "×", x + 9, y + 5, ColorUtil.contrastText(background));
    }

    public static void toggle(DrawContext context, int x, int y, int width, int height, boolean enabled, boolean hovered) {
        ModernThemePalette theme = theme();
        String key = "toggle:" + x + ':' + y + ':' + width + ':' + height;
        float state = animationProgress(key + ":state", enabled);
        float hover = animationProgress(key + ":hover", hovered);
        int track = ColorUtil.mix(theme.card, theme.success, state);
        track = ColorUtil.lighten(track, 0.08F * hover);
        context.fill(x - 1, y - 1, x + width + 1, y + height + 1, theme.shadow);
        context.fill(x, y, x + width, y + height, track);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, ColorUtil.lighten(track, 0.12F));
        int knobSize = Math.max(6, height - 6);
        int travel = Math.max(0, width - knobSize - 6);
        int knobX = x + 3 + Math.round(travel * state);
        int knobY = y + (height - knobSize) / 2;
        context.fill(knobX, knobY, knobX + knobSize, knobY + knobSize,
                ColorUtil.mix(theme.muted, theme.text, state));
    }


    public static Text guiText(String value) {
        return Text.literal(value == null ? "" : value).setStyle(guiTextStyle());
    }

    public static <T extends TextFieldWidget> T configureGuiTextField(T field) {
        field.addFormatter((value, firstCharacterIndex) -> OrderedText.styledForwardsVisitedString(value, guiTextStyle()));
        return field;
    }

    public static void setGuiPlaceholder(TextFieldWidget field, String value) {
        field.setPlaceholder(Text.literal(value == null ? "" : value)
                .setStyle(guiTextStyle().withColor(Formatting.DARK_GRAY)));
    }

    private static Style guiTextStyle() {
        return ClientConfigManager.getGuiFontMode() == GuiFontMode.MOD ? MODERN_GUI_STYLE : Style.EMPTY;
    }

    public static void drawGuiText(DrawContext context, TextRenderer textRenderer, String value,
                                   int x, int y, int color) {
        context.drawText(textRenderer, guiText(value), x, y, color, false);
    }

    public static void drawGuiTextCentered(DrawContext context, TextRenderer textRenderer, String value,
                                           int centerX, int y, int color) {
        drawGuiText(context, textRenderer, value, centerX - getGuiTextWidth(textRenderer, value) / 2, y, color);
    }

    public static void drawGuiTextRightAligned(DrawContext context, TextRenderer textRenderer, String value,
                                               int rightX, int y, int color) {
        drawGuiText(context, textRenderer, value, rightX - getGuiTextWidth(textRenderer, value), y, color);
    }

    public static int getGuiTextWidth(TextRenderer textRenderer, String value) {
        return textRenderer.getWidth(guiText(value));
    }

    public static String trimGuiText(TextRenderer textRenderer, String value, int maxWidth) {
        String safe = value == null ? "" : value;
        if (getGuiTextWidth(textRenderer, safe) <= maxWidth) {
            return safe;
        }

        String suffix = "...";
        int end = safe.length();
        while (end > 0 && getGuiTextWidth(textRenderer, safe.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return safe.substring(0, end) + suffix;
    }

    public static void drawCentered(DrawContext context, TextRenderer textRenderer, String value,
                                    int centerX, int y, int color) {
        drawGuiTextCentered(context, textRenderer, value, centerX, y, color);
    }

    public static void drawTruncated(DrawContext context, TextRenderer textRenderer, String value,
                                     int x, int y, int maxWidth, int color) {
        drawGuiText(context, textRenderer, trimGuiText(textRenderer, value, maxWidth), x, y, color);
    }

    public static String truncate(TextRenderer textRenderer, String value, int maxWidth) {
        return trimGuiText(textRenderer, value, maxWidth);
    }

    public static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    public static float animationProgress(String key, boolean target) {
        long now = System.nanoTime();
        HoverAnimation animation = HOVER_ANIMATIONS.get(key);
        if (animation == null) {
            if (HOVER_ANIMATIONS.size() >= 512) HOVER_ANIMATIONS.clear();
            animation = new HoverAnimation(now);
            HOVER_ANIMATIONS.put(key, animation);
        }
        float elapsedSeconds = Math.min(0.10F, Math.max(0.0F, (now - animation.updatedAt) / 1_000_000_000.0F));
        animation.updatedAt = now;
        float easing = 1.0F - (float) Math.exp(-14.0F * elapsedSeconds);
        animation.value += ((target ? 1.0F : 0.0F) - animation.value) * easing;
        return animation.value;
    }

    public static int animatedPosition(String key, int target) {
        long now = System.nanoTime();
        PositionAnimation animation = POSITION_ANIMATIONS.get(key);
        if (animation == null) {
            if (POSITION_ANIMATIONS.size() >= 64) POSITION_ANIMATIONS.clear();
            animation = new PositionAnimation(target, now);
            POSITION_ANIMATIONS.put(key, animation);
        }
        float elapsedSeconds = Math.min(0.10F, Math.max(0.0F, (now - animation.updatedAt) / 1_000_000_000.0F));
        animation.updatedAt = now;
        float easing = 1.0F - (float) Math.exp(-12.0F * elapsedSeconds);
        animation.value += (target - animation.value) * easing;
        return Math.round(animation.value);
    }

}

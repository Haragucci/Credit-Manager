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

/** Shared rendering primitives for the non-inventory CreditManager interface. */
public final class ModernUi {

    /**
     * This font is deliberately applied as a Text style instead of replacing Minecraft's global renderer.
     * Item tooltips, chat, inventory labels and every non-CreditManager screen therefore keep their own font.
     */
    public static final Identifier GUI_FONT = Identifier.of("creditmanager", "gui");
    private static final Style MODERN_GUI_STYLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(GUI_FONT));
    private static final Map<String, HoverAnimation> HOVER_ANIMATIONS = new HashMap<>();
    private static final Map<String, PositionAnimation> POSITION_ANIMATIONS = new HashMap<>();

    /*
     * Compatibility colours for older sub-screens. New code must use theme().<semantic colour>.
     * Shells, cards, navigation and the redesigned screens already resolve their palette dynamically.
     */
    @Deprecated public static final int OVERLAY = 0xB8101519;
    @Deprecated public static final int PANEL = 0xFF151A20;
    @Deprecated public static final int PANEL_ALT = 0xFF1C242C;
    @Deprecated public static final int CARD = 0xFF202A33;
    @Deprecated public static final int CARD_HOVER = 0xFF2B3843;
    @Deprecated public static final int BORDER = 0xFF3C5360;
    @Deprecated public static final int TEXT = 0xFFF1F5F3;
    @Deprecated public static final int MUTED = 0xFFABB8B3;
    @Deprecated public static final int GREEN = 0xFF72D98A;
    @Deprecated public static final int RED = 0xFFE66E67;
    @Deprecated public static final int YELLOW = 0xFFF2BD58;
    @Deprecated public static final int BLUE = 0xFF9BE7B0;
    @Deprecated public static final int BUTTON_PRIMARY = 0xFF2F8550;
    @Deprecated public static final int BUTTON_GOLD = 0xFF8D6A27;
    @Deprecated public static final int BUTTON_NEUTRAL = 0xFF3B4A54;
    @Deprecated public static final int BUTTON_DANGER = 0xFF934641;
    @Deprecated public static final int NAV_ACTIVE = 0xFF294F3B;
    @Deprecated public static final int NAV_HOVER = 0xFF243D31;
    @Deprecated public static final int SELECTION = 0x7079D88C;
    @Deprecated public static final int HOVER_POSITIVE = 0x3372D98A;
    @Deprecated public static final int HOVER_DANGER = 0x33E66E67;
    @Deprecated public static final int SHADOW = 0xB7080B0E;

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

    /** Compact destructive action used by the shared close control in every modern screen. */
    public static void closeButton(DrawContext context, TextRenderer textRenderer, int x, int y, boolean hovered) {
        ModernThemePalette theme = theme();
        int background = ColorUtil.lighten(theme.buttonDanger, 0.13F * animationProgress("close:" + x + ':' + y, hovered));
        context.fill(x - 1, y - 1, x + 19, y + 19, theme.shadow);
        context.fill(x, y, x + 18, y + 18, background);
        drawGuiTextCentered(context, textRenderer, "×", x + 9, y + 5, ColorUtil.contrastText(background));
    }

    /** Compact animated switch for true/false settings. The whole setting row may still remain clickable. */
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

    /**
     * Returns text for the modern CreditManager interface only. The default branch intentionally has
     * no explicit font style, so Minecraft resolves the player's active resource-pack font normally.
     */
    public static Text guiText(String value) {
        return Text.literal(value == null ? "" : value).setStyle(guiTextStyle());
    }

    /** Applies the selected modern-GUI font to editable text without touching classic or vanilla fields. */
    public static <T extends TextFieldWidget> T configureGuiTextField(T field) {
        field.addFormatter((value, firstCharacterIndex) -> OrderedText.styledForwardsVisitedString(value, guiTextStyle()));
        return field;
    }

    /** Sets a placeholder styled for the currently selected modern-GUI font. */
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

    // Compatibility aliases keep existing modern screens compact while still routing through the custom font.
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

    /** Shared, dependency-free hover easing for every modern control. */
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

    /** Eases a shared visual position without changing the corresponding hitboxes. */
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

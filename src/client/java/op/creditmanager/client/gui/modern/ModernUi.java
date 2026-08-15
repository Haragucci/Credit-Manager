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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

public final class ModernUi {

    public static final Identifier GUI_FONT = Identifier.of("creditmanager", "gui");
    private static final Style MODERN_GUI_STYLE = Style.EMPTY.withFont(new StyleSpriteSource.Font(GUI_FONT));
    private static final int TEXT_CACHE_LIMIT = 1024;
    private static final int HOVER_CACHE_LIMIT = 512;
    private static final int POSITION_CACHE_LIMIT = 64;
    private static final Map<String, Text> TEXT_CACHE = new BoundedMap<>(TEXT_CACHE_LIMIT);
    private static final Map<String, Integer> WIDTH_CACHE = new BoundedMap<>(TEXT_CACHE_LIMIT);
    private static final Map<Long, HoverAnimation> HOVER_ANIMATIONS = new BoundedMap<>(HOVER_CACHE_LIMIT);
    private static final Map<Long, PositionAnimation> POSITION_ANIMATIONS = new BoundedMap<>(POSITION_CACHE_LIMIT);
    private static long cachedFontEpoch = Long.MIN_VALUE;
    private static long resourceFontEpoch;
    private static TextRenderer widthRenderer;

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

    private static final class BoundedMap<K, V> extends LinkedHashMap<K, V> {
        private final int limit;

        private BoundedMap(int limit) {
            super(16, 0.75F, true);
            this.limit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > limit;
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
        float hover = animationProgress(animationKey(1L, x, y, width, height, 0L), hovered);
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
        float hover = animationProgress(animationKey(2L, x, y, width, height, hashString(label)), hovered);
        int background = ColorUtil.lighten(color, 0.10F * hover);
        context.fill(x, y, x + width, y + height, theme.shadow);
        context.fill(x + 1, y + 1, x + width - 1, y + height - 1, background);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, ColorUtil.lighten(background, 0.10F));
        drawGuiTextCentered(context, textRenderer, trimGuiText(textRenderer, label, Math.max(1, width - 8)),
                x + width / 2, y + (height - 8) / 2, ColorUtil.contrastText(background));
    }

    public static void closeButton(DrawContext context, TextRenderer textRenderer, int x, int y, boolean hovered) {
        ModernThemePalette theme = theme();
        int background = ColorUtil.lighten(theme.buttonDanger, 0.13F
                * animationProgress(animationKey(3L, x, y, 18, 18, 0L), hovered));
        context.fill(x - 1, y - 1, x + 19, y + 19, theme.shadow);
        context.fill(x, y, x + 18, y + 18, background);
        drawGuiTextCentered(context, textRenderer, "×", x + 9, y + 5, ColorUtil.contrastText(background));
    }

    public static void toggle(DrawContext context, int x, int y, int width, int height, boolean enabled, boolean hovered) {
        ModernThemePalette theme = theme();
        long key = animationKey(4L, x, y, width, height, 0L);
        float state = animationProgress(mix(key, 1L), enabled);
        float hover = animationProgress(mix(key, 2L), hovered);
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
        ensureTextCacheEpoch();
        String safe = value == null ? "" : value;
        Text cached = TEXT_CACHE.get(safe);
        if (cached != null) return cached;
        Text created = Text.literal(safe).setStyle(guiTextStyle());
        TEXT_CACHE.put(safe, created);
        return created;
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
        return ClientConfigManager.uiRenderConfig().fontMode() == GuiFontMode.MOD ? MODERN_GUI_STYLE : Style.EMPTY;
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
        ensureTextCacheEpoch();
        if (widthRenderer != textRenderer) {
            WIDTH_CACHE.clear();
            widthRenderer = textRenderer;
        }
        String safe = value == null ? "" : value;
        Integer cached = WIDTH_CACHE.get(safe);
        if (cached != null) return cached;
        int width = textRenderer.getWidth(guiText(safe));
        WIDTH_CACHE.put(safe, width);
        return width;
    }

    public static String trimGuiText(TextRenderer textRenderer, String value, int maxWidth) {
        return trimText(value, maxWidth, candidate -> getGuiTextWidth(textRenderer, candidate));
    }

    static String trimText(String value, int maxWidth, ToIntFunction<String> widthMeasurer) {
        String safe = value == null ? "" : value;
        if (widthMeasurer.applyAsInt(safe) <= maxWidth) {
            return safe;
        }

        String suffix = "...";
        int low = 0;
        int high = safe.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (widthMeasurer.applyAsInt(safe.substring(0, middle) + suffix) <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return safe.substring(0, low) + suffix;
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
        return animationProgress(hashString(key), target);
    }

    static float animationProgress(long key, boolean target) {
        long now = System.nanoTime();
        HoverAnimation animation = HOVER_ANIMATIONS.get(key);
        if (animation == null) {
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
        return animatedPosition(hashString(key), target);
    }

    static int animatedPosition(long key, int target) {
        long now = System.nanoTime();
        PositionAnimation animation = POSITION_ANIMATIONS.get(key);
        if (animation == null) {
            animation = new PositionAnimation(target, now);
            POSITION_ANIMATIONS.put(key, animation);
        }
        float elapsedSeconds = Math.min(0.10F, Math.max(0.0F, (now - animation.updatedAt) / 1_000_000_000.0F));
        animation.updatedAt = now;
        float easing = 1.0F - (float) Math.exp(-12.0F * elapsedSeconds);
        animation.value += (target - animation.value) * easing;
        return Math.round(animation.value);
    }

    public static long fontEpoch() {
        return ClientConfigManager.uiRenderConfig().fontEpoch() + resourceFontEpoch;
    }

    public static void invalidateTextCaches() {
        resourceFontEpoch++;
        cachedFontEpoch = Long.MIN_VALUE;
        ensureTextCacheEpoch();
    }

    static int cachedTextCount() {
        return TEXT_CACHE.size();
    }

    static int cachedWidthCount() {
        return WIDTH_CACHE.size();
    }

    static int hoverAnimationCount() {
        return HOVER_ANIMATIONS.size();
    }

    static int positionAnimationCount() {
        return POSITION_ANIMATIONS.size();
    }

    static void resetPerformanceCaches() {
        TEXT_CACHE.clear();
        WIDTH_CACHE.clear();
        HOVER_ANIMATIONS.clear();
        POSITION_ANIMATIONS.clear();
        widthRenderer = null;
        cachedFontEpoch = Long.MIN_VALUE;
    }

    private static void ensureTextCacheEpoch() {
        long epoch = fontEpoch();
        if (cachedFontEpoch == epoch) return;
        TEXT_CACHE.clear();
        WIDTH_CACHE.clear();
        widthRenderer = null;
        cachedFontEpoch = epoch;
    }

    private static long animationKey(long type, int x, int y, int width, int height, long discriminator) {
        long key = mix(0x9E3779B97F4A7C15L, type);
        key = mix(key, x);
        key = mix(key, y);
        key = mix(key, width);
        key = mix(key, height);
        return mix(key, discriminator);
    }

    private static long hashString(String value) {
        if (value == null) return 0L;
        long hash = 0xCBF29CE484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    private static long mix(long left, long right) {
        long value = left ^ (right + 0x9E3779B97F4A7C15L + (left << 6) + (left >>> 2));
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

}

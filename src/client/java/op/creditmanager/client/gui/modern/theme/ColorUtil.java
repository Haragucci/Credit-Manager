package op.creditmanager.client.gui.modern.theme;

import java.util.Locale;

/** Small ARGB colour helper kept independent from Minecraft rendering APIs. */
public final class ColorUtil {
    private ColorUtil() {
    }

    public static int opaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    public static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    public static int mix(int first, int second, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int a = Math.round(alpha(first) + (alpha(second) - alpha(first)) * t);
        int r = Math.round(red(first) + (red(second) - red(first)) * t);
        int g = Math.round(green(first) + (green(second) - green(first)) * t);
        int b = Math.round(blue(first) + (blue(second) - blue(first)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    public static int lighten(int color, float amount) {
        return mix(color, 0xFFFFFFFF, amount);
    }

    public static int darken(int color, float amount) {
        return mix(color, 0xFF000000, amount);
    }

    public static int contrastText(int background) {
        double brightness = (red(background) * 0.299 + green(background) * 0.587 + blue(background) * 0.114) / 255.0;
        return brightness > 0.56 ? 0xFF182019 : 0xFFF5F8F2;
    }

    public static int fromHsv(float hue, float saturation, float value) {
        return 0xFF000000 | java.awt.Color.HSBtoRGB(normalizeHue(hue), clamp(saturation), clamp(value)) & 0x00FFFFFF;
    }

    public static float[] toHsv(int color) {
        return java.awt.Color.RGBtoHSB(red(color), green(color), blue(color), null);
    }

    public static Integer parseHex(String input) {
        if (input == null) return null;
        String value = input.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9A-F]{6}")) return null;
        try {
            return 0xFF000000 | Integer.parseInt(value, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String toHex(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0x00FFFFFF);
    }

    private static int alpha(int color) { return color >>> 24 & 0xFF; }
    private static int red(int color) { return color >>> 16 & 0xFF; }
    private static int green(int color) { return color >>> 8 & 0xFF; }
    private static int blue(int color) { return color & 0xFF; }
    private static float clamp(float value) { return Math.max(0.0F, Math.min(1.0F, value)); }
    private static float normalizeHue(float hue) { return hue - (float) Math.floor(hue); }
}

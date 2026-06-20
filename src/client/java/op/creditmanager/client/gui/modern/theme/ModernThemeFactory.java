package op.creditmanager.client.gui.modern.theme;

/** Creates readable palettes from either built-in or user-selected colours. */
public final class ModernThemeFactory {
    private ModernThemeFactory() {
    }

    public static ModernThemePalette create(ModernThemeMode mode, int customMain, int customAccent) {
        return switch (mode == null ? ModernThemeMode.DARK : mode) {
            case LIGHT -> light();
            case CUSTOM -> custom(customMain, customAccent);
            case DARK -> dark();
        };
    }

    private static ModernThemePalette dark() {
        return new ModernThemePalette(
                0xB8101519, 0xFF151A20, 0xFF1C242C, 0xFF202A33, 0xFF2B3843, 0xFF3C5360,
                0xFFF1F5F3, 0xFFABB8B3, 0xFF79D88C, 0xFF9BE7B0, 0xFF72D98A, 0xFFE66E67, 0xFFF2BD58,
                0xFF2F8550, 0xFF934641, 0xFF3B4A54, 0xFF8D6A27, 0xFF294F3B, 0xFF243D31,
                0x7079D88C, 0xB7080B0E);
    }

    private static ModernThemePalette light() {
        return new ModernThemePalette(
                0x9CE8ECEA, 0xFFF0F4F1, 0xFFE3EAE5, 0xFFFFFFFF, 0xFFF0F7F1, 0xFFB7C7BE,
                0xFF1B2920, 0xFF5D7065, 0xFF277E4A, 0xFF2E9C5A, 0xFF258449, 0xFFC84E4A, 0xFFB87816,
                0xFF338B53, 0xFFBE5D58, 0xFFCED8D2, 0xFF9B751F, 0xFFD5ECDD, 0xFFE0EEE4,
                0x602E9C5A, 0x552E3C34);
    }

    private static ModernThemePalette custom(int main, int accent) {
        int base = ColorUtil.opaque(main);
        int highlight = ColorUtil.opaque(accent);
        boolean lightBase = ColorUtil.contrastText(base) == 0xFF182019;
        int panel = lightBase ? ColorUtil.lighten(base, 0.72F) : ColorUtil.darken(base, 0.68F);
        int panelAlt = lightBase ? ColorUtil.lighten(base, 0.56F) : ColorUtil.darken(base, 0.53F);
        int card = lightBase ? ColorUtil.lighten(base, 0.82F) : ColorUtil.darken(base, 0.42F);
        int cardHover = lightBase ? ColorUtil.lighten(base, 0.90F) : ColorUtil.darken(base, 0.28F);
        int text = ColorUtil.contrastText(panel);
        int muted = ColorUtil.mix(text, panel, 0.44F);
        int border = ColorUtil.mix(base, text, lightBase ? 0.35F : 0.23F);
        return new ModernThemePalette(
                ColorUtil.withAlpha(lightBase ? 0xFF2A3330 : 0xFF08100C, 174), panel, panelAlt, card, cardHover, border,
                text, muted, highlight, ColorUtil.lighten(highlight, lightBase ? 0.04F : 0.18F), 0xFF4DBB6A, 0xFFD65B57, 0xFFE4A63A,
                ColorUtil.mix(highlight, base, lightBase ? 0.23F : 0.42F), 0xFF9B4C49,
                ColorUtil.mix(panelAlt, text, lightBase ? 0.08F : 0.14F), 0xFF8D6A27,
                ColorUtil.mix(base, panelAlt, 0.43F), ColorUtil.mix(base, panelAlt, 0.67F),
                ColorUtil.withAlpha(highlight, 100), ColorUtil.withAlpha(lightBase ? 0xFF4B5D53 : 0xFF020604, 178));
    }
}

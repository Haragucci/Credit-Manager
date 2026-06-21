package op.creditmanager.client.gui.modern.theme;

import op.creditmanager.client.config.ClientConfigManager;

public final class ModernThemeManager {
    private static ModernThemePalette cached;
    private static ModernThemeMode cachedMode;
    private static int cachedMain;
    private static int cachedAccent;
    private ModernThemeManager() {
    }

    public static synchronized ModernThemePalette current() {
        ModernThemeMode mode = ClientConfigManager.getModernThemeMode();
        int main = ClientConfigManager.getCustomMainColor();
        int accent = ClientConfigManager.getCustomAccentColor();
        if (cached == null || cachedMode != mode || cachedMain != main || cachedAccent != accent) {
            cached = ModernThemeFactory.create(mode, main, accent);
            cachedMode = mode;
            cachedMain = main;
            cachedAccent = accent;
        }
        return cached;
    }
}

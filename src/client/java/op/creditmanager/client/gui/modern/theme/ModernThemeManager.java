package op.creditmanager.client.gui.modern.theme;

import op.creditmanager.client.config.ClientConfigManager;

/** Resolves the active palette at render time, so a settings change applies immediately. */
public final class ModernThemeManager {
    private ModernThemeManager() {
    }

    public static ModernThemePalette current() {
        return ModernThemeFactory.create(ClientConfigManager.getModernThemeMode(),
                ClientConfigManager.getCustomMainColor(), ClientConfigManager.getCustomAccentColor());
    }
}

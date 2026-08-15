package op.creditmanager.client.gui.modern.theme;

import op.creditmanager.client.config.ClientConfigManager;

public final class ModernThemeManager {
    private ModernThemeManager() {
    }

    public static ModernThemePalette current() {
        return ClientConfigManager.uiRenderConfig().theme();
    }
}

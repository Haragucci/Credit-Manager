package op.creditmanager.client.config;

import op.creditmanager.client.gui.modern.theme.ModernThemePalette;

public record UiRenderConfig(ModernThemePalette theme, GuiFontMode fontMode, long epoch, long fontEpoch) {
}

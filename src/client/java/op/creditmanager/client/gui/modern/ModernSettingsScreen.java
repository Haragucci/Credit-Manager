package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiMode;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.GuiRouter;

/** Scrollable settings page for presentation and local Paylog behaviour. */
public class ModernSettingsScreen extends ModernBaseScreen {

    private static final int ROW_HEIGHT = 31;
    private int listY;
    private int visibleRows;
    private int scrollOffset;

    public ModernSettingsScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Einstellungen", "settings");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        listY = contentY + 8;
        visibleRows = Math.max(1, (contentHeight - 12) / ROW_HEIGHT);
        int maxOffset = Math.max(0, settingCount() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));

        for (int row = 0; row < visibleRows && row + scrollOffset < settingCount(); row++) {
            int setting = row + scrollOffset;
            drawSetting(context, mouseX, mouseY, setting, contentX, listY + row * ROW_HEIGHT, contentWidth);
        }
        if (maxOffset > 0) {
            ModernUi.drawTruncated(context, textRenderer, "Mausrad für weitere Einstellungen", contentX, panelY + panelHeight - 14,
                    contentWidth, ModernUi.MUTED);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSetting(DrawContext context, int mouseX, int mouseY, int setting, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 25);
        ModernUi.card(context, x, y, width, 25, hovered);
        String label = settingLabel(setting);
        String value = settingValue(setting);
        ModernUi.drawTruncated(context, textRenderer, label, x + 10, y + 8, Math.max(40, width - 112), ModernUi.TEXT);
        int color = value.equals("AN") || value.equals("Modern GUI") ? ModernUi.GREEN : ModernUi.MUTED;
        int valueWidth = textRenderer.getWidth(value);
        context.drawText(textRenderer, Text.literal(value), x + width - valueWidth - 10, y + 8, color, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, visibleRows * ROW_HEIGHT)) {
            int setting = scrollOffset + (int) ((click.y() - listY) / ROW_HEIGHT);
            activateSetting(setting);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, visibleRows * ROW_HEIGHT) && verticalAmount != 0) {
            int maxOffset = Math.max(0, settingCount() - visibleRows);
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int settingCount() {
        return 6;
    }

    private String settingLabel(int setting) {
        return switch (setting) {
            case 0 -> "Classic GUI aktivieren";
            case 1 -> "Modern GUI aktivieren";
            case 2 -> "GUI-Auswahl beim nächsten Öffnen";
            case 3 -> "Paylogs automatisch erkennen";
            case 4 -> "Overlay-/Actionbar-Nachrichten prüfen";
            case 5 -> "Paylog-Benachrichtigungen im Chat";
            default -> "Unbekannte Einstellung";
        };
    }

    private String settingValue(int setting) {
        return switch (setting) {
            case 0 -> ClientConfigManager.getGuiMode() == GuiMode.CLASSIC ? "AKTIV" : "";
            case 1 -> ClientConfigManager.getGuiMode() == GuiMode.MODERN ? "Modern GUI" : "";
            case 2 -> "Zurücksetzen";
            case 3 -> ClientConfigManager.isAutomaticPaylogDetection() ? "AN" : "AUS";
            case 4 -> ClientConfigManager.isDetectPaylogsInOverlay() ? "AN" : "AUS";
            case 5 -> ClientConfigManager.isShowPaylogNotifications() ? "AN" : "AUS";
            default -> "";
        };
    }

    private void activateSetting(int setting) {
        switch (setting) {
            case 0 -> GuiRouter.selectModeAndOpen(manager, GuiMode.CLASSIC);
            case 1 -> GuiRouter.selectModeAndOpen(manager, GuiMode.MODERN);
            case 2 -> {
                ClientConfigManager.setGuiMode(GuiMode.UNSELECTED);
                closeToParent();
            }
            case 3 -> ClientConfigManager.setAutomaticPaylogDetection(!ClientConfigManager.isAutomaticPaylogDetection());
            case 4 -> ClientConfigManager.setDetectPaylogsInOverlay(!ClientConfigManager.isDetectPaylogsInOverlay());
            case 5 -> ClientConfigManager.setShowPaylogNotifications(!ClientConfigManager.isShowPaylogNotifications());
            default -> {
            }
        }
    }
}

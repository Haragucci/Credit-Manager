package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiFontMode;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemeMode;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.List;

public class ModernSettingsDetailScreen extends ModernBaseScreen {
    public enum Category {
        DETECTION("Detection System", "Erkennung von Paylogs und Overlay-Nachrichten"),
        GUI("GUI", "Themes, Farben, Schriftart und Darstellung"),
        PAYLOGS("Paylogs", "Benachrichtigungen und Suchverhalten"),
        STATISTICS("Statistiken", "Zeitraum und Diagramm"),
        ;

        private final String label;
        private final String description;

        Category(String label, String description) {
            this.label = label;
            this.description = description;
        }

        public String label() { return label; }
        public String description() { return description; }
    }

    private static final int ROW_HEIGHT = 37;
    private static final int PREVIEW_HEIGHT = 62;

    private final Category category;
    private final ModernScrollArea contentScroll = new ModernScrollArea();
    private int scrollY;
    private int scrollHeight;

    private record SettingOption(String label, String value, Boolean enabled) {
        private SettingOption(String label, String value) {
            this(label, value, null);
        }

        private boolean isToggle() {
            return enabled != null;
        }
    }

    public ModernSettingsDetailScreen(CreditManager manager, Screen parent, Category category) {
        super(manager, parent, category.label(), "settings");
        this.category = category;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        List<SettingOption> options = options();
        scrollY = contentY + 8;
        scrollHeight = Math.max(26, contentY + contentHeight - scrollY - 4);
        int contentHeight = options.size() * ROW_HEIGHT + (category == Category.GUI ? PREVIEW_HEIGHT : 0);
        contentScroll.setBounds(contentX, scrollY, contentWidth, scrollHeight, contentHeight);
        contentScroll.tick(mouseX, mouseY);
        int offset = contentScroll.offset();

        context.enableScissor(contentX, scrollY, contentX + contentWidth, scrollY + scrollHeight);
        drawOptions(context, mouseX, mouseY, options, offset);
        if (category == Category.GUI) drawThemePreview(context, scrollY + options.size() * ROW_HEIGHT - offset);
        context.disableScissor();
        contentScroll.renderScrollbar(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private List<SettingOption> options() {
        return switch (category) {
            case DETECTION -> List.of(
                    new SettingOption("Paylogs automatisch erkennen", "", ClientConfigManager.isAutomaticPaylogDetection()),
                    new SettingOption("Overlay-/Actionbar-Nachrichten prüfen", "", ClientConfigManager.isDetectPaylogsInOverlay()));
            case GUI -> List.of(
                    new SettingOption("Schriftart", guiFontModeLabel(ClientConfigManager.getGuiFontMode())),
                    new SettingOption("Theme", themeModeLabel(ClientConfigManager.getModernThemeMode())),
                    new SettingOption("Main-Farbe", ColorUtil.toHex(ClientConfigManager.getCustomMainColor())),
                    new SettingOption("Akzent-Farbe", ColorUtil.toHex(ClientConfigManager.getCustomAccentColor())),
                    new SettingOption("Custom-Farben zurücksetzen", "Standard"));
            case PAYLOGS -> List.of(
                    new SettingOption("Paylog-Benachrichtigungen im Chat", "", ClientConfigManager.isShowPaylogNotifications()),
                    new SettingOption("Suche", "Name · Betrag · Datum · Tippfehler"));
            case STATISTICS -> List.of(
                    new SettingOption("Standard-Zeitraum", periodLabel(ClientConfigManager.getStatisticsDefaultPeriodDays())),
                    new SettingOption("Statistik öffnen", "Diagramm & Filter"));
        };
    }

    private void drawOptions(DrawContext context, int mouseX, int mouseY, List<SettingOption> options, int offset) {
        ModernThemePalette theme = ModernUi.theme();
        int first = Math.max(0, offset / ROW_HEIGHT);
        int last = Math.min(options.size(), first + scrollHeight / ROW_HEIGHT + 3);
        int width = contentWidth - (contentScroll.isScrollable() ? 8 : 0);
        for (int index = first; index < last; index++) {
            SettingOption option = options.get(index);
            int y = scrollY + index * ROW_HEIGHT - offset;
            boolean hovered = ModernUi.contains(mouseX, mouseY, contentX, y, width, ROW_HEIGHT - 6);
            ModernUi.card(context, contentX, y, width, ROW_HEIGHT - 6, hovered);
            if (option.isToggle()) {
                int toggleWidth = 34;
                int toggleHeight = 16;
                int toggleX = contentX + width - toggleWidth - 10;
                int toggleY = y + (ROW_HEIGHT - 6 - toggleHeight) / 2;
                ModernUi.drawTruncated(context, textRenderer, option.label(), contentX + 10, y + 10,
                        Math.max(18, toggleX - contentX - 18), theme.text);
                ModernUi.toggle(context, toggleX, toggleY, toggleWidth, toggleHeight, option.enabled(),
                        ModernUi.contains(mouseX, mouseY, toggleX, toggleY, toggleWidth, toggleHeight));
            } else {
                ModernUi.drawTruncated(context, textRenderer, option.label(), contentX + 10, y + 10, width - 145, theme.text);
                ModernUi.drawGuiTextRightAligned(context, textRenderer, option.value(), contentX + width - 10, y + 10, theme.muted);
            }
        }
    }

    private void drawThemePreview(DrawContext context, int y) {
        ModernThemePalette theme = ModernUi.theme();
        int width = contentWidth - (contentScroll.isScrollable() ? 8 : 0);
        ModernUi.card(context, contentX, y, width, PREVIEW_HEIGHT - 4, false);
        ModernUi.drawGuiText(context, textRenderer, "Live-Vorschau", contentX + 10, y + 9, theme.muted);
        context.fill(contentX + 10, y + 25, contentX + 52, y + 33, theme.primary);
        context.fill(contentX + 57, y + 25, contentX + 99, y + 33, theme.accent);
        ModernUi.drawTruncated(context, textRenderer, "Text bleibt kontrastreich", contentX + 108, y + 24, width - 118, theme.text);
        ModernUi.drawTruncated(context, textRenderer,
                "Übersicht Forderungen Schulden 12345 ÄÖÜ äöü ß", contentX + 10, y + 42, width - 20, theme.text);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() != 0 || !contentScroll.contains(click.x(), click.y())) return super.mouseClicked(click, doubled);
        int row = (int) ((click.y() - scrollY + contentScroll.offset()) / ROW_HEIGHT);
        if (row >= 0 && row < options().size()) activate(row);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (contentScroll.contains(mouseX, mouseY)) {
            contentScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void activate(int row) {
        switch (category) {
            case DETECTION -> {
                boolean saved = row == 0
                        ? ClientConfigManager.setAutomaticPaylogDetection(!ClientConfigManager.isAutomaticPaylogDetection())
                        : row == 1 && ClientConfigManager.setDetectPaylogsInOverlay(!ClientConfigManager.isDetectPaylogsInOverlay());
                toastSettingResult(saved, "Erkennung gespeichert.");
            }
            case GUI -> activateGuiOption(row);
            case PAYLOGS -> {
                if (row == 0) {
                    toastSettingResult(ClientConfigManager.setShowPaylogNotifications(!ClientConfigManager.isShowPaylogNotifications()),
                            "Paylog-Einstellung gespeichert.");
                }
            }
            case STATISTICS -> activateStatisticsOption(row);
        }
    }

    private void activateGuiOption(int row) {
        switch (row) {
            case 0 -> {
                GuiFontMode next = ClientConfigManager.getGuiFontMode() == GuiFontMode.MOD
                        ? GuiFontMode.MINECRAFT
                        : GuiFontMode.MOD;
                toastSettingResult(ClientConfigManager.setGuiFontMode(next), "Schriftart: " + guiFontModeLabel(next));
            }
            case 1 -> {
                ModernThemeMode current = ClientConfigManager.getModernThemeMode();
                ModernThemeMode next = current == ModernThemeMode.DARK ? ModernThemeMode.LIGHT
                        : current == ModernThemeMode.LIGHT ? ModernThemeMode.CUSTOM : ModernThemeMode.DARK;
                toastSettingResult(ClientConfigManager.setModernThemeMode(next), "Theme übernommen.");
            }
            case 2 -> open(new ModernColorPickerScreen(manager, this, false));
            case 3 -> open(new ModernColorPickerScreen(manager, this, true));
            case 4 -> {
                toastSettingResult(ClientConfigManager.setCustomTheme(0xFF1F7A3A, 0xFF7EE787, ModernThemeMode.CUSTOM),
                        "Farben zurückgesetzt.");
            }
            default -> { }
        }
    }

    private void activateStatisticsOption(int row) {
        if (row == 0) {
            int current = ClientConfigManager.getStatisticsDefaultPeriodDays();
            toastSettingResult(ClientConfigManager.setStatisticsDefaultPeriodDays(current == 7 ? 30 : current == 30 ? 90 : current == 90 ? 0 : 7),
                    "Zeitraum gespeichert.");
        } else if (row == 1) {
            open(new ModernStatisticsScreen(manager, this));
        }
    }

    @Override
    protected void clearTransientState() {
        contentScroll.reset();
        super.clearTransientState();
    }

    private String guiFontModeLabel(GuiFontMode mode) {
        return mode == GuiFontMode.MINECRAFT ? "Eigene Minecraft-Schrift" : "Mod-Schriftart";
    }
    private String themeModeLabel(ModernThemeMode mode) { return switch (mode) { case DARK -> "Dark"; case LIGHT -> "Light"; case CUSTOM -> "Custom"; }; }
    private String periodLabel(int days) { return days == 0 ? "Alle" : days + " Tage"; }

    private void toastSettingResult(boolean saved, String success) {
        if (saved) toastSuccess(success); else toastError("Einstellung konnte nicht gespeichert werden.");
    }
}

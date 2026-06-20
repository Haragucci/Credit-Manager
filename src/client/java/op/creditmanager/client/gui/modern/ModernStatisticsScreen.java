package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditEventRepository;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.stats.CreditStatistics;
import op.creditmanager.client.gui.modern.stats.CreditStatisticsCalculator;
import op.creditmanager.client.gui.modern.stats.ModernChartRenderer;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.BalanceReader;
import op.creditmanager.client.util.FormatUtil;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalDouble;

/** Scrollable credit-only statistics with a persistent reset/back footer. */
public class ModernStatisticsScreen extends ModernBaseScreen {
    private static final String[] PERIODS = {"7 Tage", "30 Tage", "90 Tage", "Alle", "Custom"};
    private static final int FOOTER_HEIGHT = 22;
    private static final int METRIC_HEIGHT = 34;
    private static final int CHART_HEIGHT = 148;

    private final ModernScrollArea contentScroll = new ModernScrollArea();
    private int periodIndex;
    private boolean resetArmed;
    private TextFieldWidget startDate;
    private TextFieldWidget endDate;
    private int periodY;
    private int footerY;
    private int viewportY;
    private int viewportHeight;
    private int safeContentWidth;
    private int periodControlWidth;
    private int resetWidth;
    private boolean metricsTwoColumns;

    public ModernStatisticsScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Statistiken", "overview");
        int days = ClientConfigManager.getStatisticsDefaultPeriodDays();
        periodIndex = days == 7 ? 0 : days == 90 ? 2 : days == 0 ? 3 : 1;
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        startDate = ModernUi.configureGuiTextField(new TextFieldWidget(textRenderer, 0, 0, 88, 18, Text.empty()));
        endDate = ModernUi.configureGuiTextField(new TextFieldWidget(textRenderer, 0, 0, 88, 18, Text.empty()));
        ModernUi.setGuiPlaceholder(startDate, "YYYY-MM-DD");
        ModernUi.setGuiPlaceholder(endDate, "YYYY-MM-DD");
        addDrawableChild(startDate);
        addDrawableChild(endDate);
        updateCustomFieldVisibility(0, 0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        long now = System.currentTimeMillis();
        long from = rangeStart(now);
        long to = rangeEnd(now);
        List<CreditEntry> claims = manager.getOpenCreditsAsCreditor(currentPlayerName());
        List<CreditEntry> debts = manager.getOpenCreditsAsDebtor(currentPlayerName());
        CreditStatistics stats = CreditStatisticsCalculator.calculate(currentPlayerName(), claims, debts,
                CreditEventRepository.getInstance().getAll(), from, to);
        OptionalDouble accountBalance = BalanceReader.readCurrentBalance(MinecraftClient.getInstance());

        footerY = contentY + contentHeight - FOOTER_HEIGHT;
        viewportY = contentY + 6;
        viewportHeight = Math.max(32, footerY - viewportY - 7);
        int metricsOffset = periodIndex == 4 ? 58 : 30;
        // Reserve the scrollbar inset while deciding the grid so the chart never overlaps a wrapped metric row.
        metricsTwoColumns = contentWidth >= 350;
        int metricsHeight = metricsTwoColumns ? METRIC_HEIGHT * 2 + 6 : METRIC_HEIGHT * 4 + 18;
        int chartsY = metricsOffset + metricsHeight + 9;
        int scrollContentHeight = chartsY + CHART_HEIGHT + 20;
        contentScroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, scrollContentHeight);
        contentScroll.tick(mouseX, mouseY);
        safeContentWidth = Math.max(48, contentWidth - (contentScroll.isScrollable() ? 10 : 0));
        int scrollOffset = contentScroll.offset();
        periodY = viewportY - scrollOffset;

        updateCustomFieldVisibility(periodY + 30, scrollOffset);
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        drawPeriodControl(context, mouseX, mouseY, theme);
        drawMetrics(context, stats, accountBalance, metricsOffset + periodY);
        ModernChartRenderer.bars(context, textRenderer, contentX, periodY + chartsY, safeContentWidth,
                CHART_HEIGHT, stats.openClaims(), stats.openDebts());
        context.disableScissor();
        contentScroll.renderScrollbar(context, mouseX, mouseY);

        int gap = 6;
        resetWidth = Math.min(safeContentWidth, Math.max(94, (safeContentWidth - gap) * 2 / 3));
        ModernUi.button(context, textRenderer, contentX, footerY, resetWidth, FOOTER_HEIGHT,
                resetArmed ? "Reset bestätigen" : "Statistiken zurücksetzen", theme.buttonDanger,
                ModernUi.contains(mouseX, mouseY, contentX, footerY, resetWidth, FOOTER_HEIGHT));
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPeriodControl(DrawContext context, int mouseX, int mouseY, ModernThemePalette theme) {
        periodControlWidth = Math.min(140, safeContentWidth);
        ModernUi.button(context, textRenderer, contentX, periodY, periodControlWidth, 22, "Zeitraum: " + PERIODS[periodIndex], theme.buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, periodY, periodControlWidth, 22));
        if (periodIndex == 4) {
            ModernUi.drawGuiText(context, textRenderer, "Von / bis", contentX + Math.min(194, Math.max(148, safeContentWidth - 54)), periodY + 7, theme.muted);
        }
    }

    private void drawMetrics(DrawContext context, CreditStatistics stats, OptionalDouble accountBalance, int y) {
        ModernThemePalette theme = ModernUi.theme();
        int metricWidth = metricsTwoColumns ? (safeContentWidth - 8) / 2 : safeContentWidth;
        drawMetric(context, contentX, y, metricWidth, "Offene Forderungen", FormatUtil.formatAmount(stats.openClaims()), theme.success);
        drawMetric(context, contentX + (metricsTwoColumns ? metricWidth + 8 : 0), metricsTwoColumns ? y : y + 40,
                metricWidth, "Offene Schulden", FormatUtil.formatAmount(stats.openDebts()), theme.danger);
        int secondRowY = metricsTwoColumns ? y + 40 : y + 80;
        drawMetric(context, contentX, secondRowY, metricWidth, "Saldo", (stats.balance() >= 0 ? "+" : "") + FormatUtil.formatAmount(stats.balance()),
                stats.balance() >= 0 ? theme.success : theme.danger);
        String forecast = accountBalance.isPresent() ? FormatUtil.formatAmount(accountBalance.getAsDouble() + stats.balance())
                : "Kontostand nicht erkennbar";
        drawMetric(context, contentX + (metricsTwoColumns ? metricWidth + 8 : 0), metricsTwoColumns ? secondRowY : secondRowY + 40,
                metricWidth, "Nach Verrechnung", forecast, accountBalance.isPresent() ? theme.accent : theme.muted);
    }

    private void drawMetric(DrawContext context, int x, int y, int width, String label, String value, int color) {
        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, METRIC_HEIGHT, false);
        ModernUi.drawGuiText(context, textRenderer, label, x + 9, y + 6, theme.muted);
        ModernUi.drawTruncated(context, textRenderer, value, x + 9, y + 18, width - 18, color);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, footerY, resetWidth, FOOTER_HEIGHT)) {
            resetStatistics();
            return true;
        }
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, periodY, periodControlWidth, 22)) {
            periodIndex = (periodIndex + 1) % PERIODS.length;
            if (periodIndex != 4) ClientConfigManager.setStatisticsDefaultPeriodDays(periodIndex == 0 ? 7 : periodIndex == 2 ? 90 : periodIndex == 3 ? 0 : 30);
            resetArmed = false;
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (contentScroll.contains(mouseX, mouseY)) {
            contentScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void resetStatistics() {
        if (!resetArmed) {
            resetArmed = true;
            toastWarning("Erneut klicken, um Statistiken zurückzusetzen.");
        } else if (CreditEventRepository.getInstance().resetWithBackup()) {
            resetArmed = false;
            toastSuccess("Statistiken zurückgesetzt.");
        } else {
            resetArmed = false;
            toastError("Backup fehlgeschlagen.");
        }
    }

    private long rangeStart(long now) {
        if (periodIndex == 3) return 0L;
        if (periodIndex == 4) {
            try {
                if (startDate.getText().isBlank() || endDate.getText().isBlank()) return 0L;
                LocalDate start = LocalDate.parse(startDate.getText().trim());
                LocalDate end = LocalDate.parse(endDate.getText().trim());
                return end.isBefore(start) ? 0L : start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (RuntimeException ignored) {
                return 0L;
            }
        }
        int days = periodIndex == 0 ? 7 : periodIndex == 2 ? 90 : 30;
        return now - days * 86_400_000L;
    }

    private long rangeEnd(long fallback) {
        if (periodIndex != 4) return fallback;
        try {
            if (endDate.getText().isBlank()) return fallback;
            return LocalDate.parse(endDate.getText().trim()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private void updateCustomFieldVisibility(int fieldY, int scrollOffset) {
        boolean visible = periodIndex == 4 && fieldY + 18 > viewportY && fieldY < viewportY + viewportHeight;
        if (startDate != null) {
            startDate.setPosition(contentX + 6, fieldY);
            startDate.setVisible(visible);
        }
        if (endDate != null) {
            endDate.setPosition(contentX + 100, fieldY);
            endDate.setVisible(visible);
        }
    }

    @Override
    protected void clearTransientState() {
        resetArmed = false;
        contentScroll.reset();
        if (startDate != null) startDate.setText("");
        if (endDate != null) endDate.setText("");
        super.clearTransientState();
    }
}

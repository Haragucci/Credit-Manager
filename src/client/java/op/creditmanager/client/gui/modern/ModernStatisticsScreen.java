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
import op.creditmanager.client.core.CreditStatisticsSnapshot;
import op.creditmanager.client.gui.modern.stats.CreditStatistics;
import op.creditmanager.client.gui.modern.stats.CreditStatisticsCalculator;
import op.creditmanager.client.gui.modern.stats.ModernChartRenderer;
import op.creditmanager.client.gui.modern.stats.StatisticsRange;
import op.creditmanager.client.gui.modern.stats.StatisticsViewKey;
import op.creditmanager.client.gui.modern.stats.StatisticsViewModel;
import op.creditmanager.client.gui.modern.stats.StatisticsViewState;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.BalanceReader;
import op.creditmanager.client.util.FormatUtil;

import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigInteger;
import java.util.List;
import java.util.OptionalLong;

public class ModernStatisticsScreen extends ModernBaseScreen {
    private static final String[] PERIODS = {"7 Tage", "30 Tage", "90 Tage", "Alle", "Custom"};
    private static final int METRIC_HEIGHT = 34;
    private static final int CHART_HEIGHT = 172;

    private final ModernScrollArea contentScroll = new ModernScrollArea();
    private int periodIndex;
    private TextFieldWidget startDate;
    private TextFieldWidget endDate;
    private int periodY;
    private int viewportY;
    private int viewportHeight;
    private int safeContentWidth;
    private int periodControlWidth;
    private boolean metricsTwoColumns;
    private final StatisticsViewModel statisticsViewModel = new StatisticsViewModel();
    private ModernLayout.Bounds retryBounds;
    private String validationMessage = "";

    public ModernStatisticsScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Statistiken", "overview");
        int days = ClientConfigManager.getStatisticsDefaultPeriodDays();
        periodIndex = days == 7 ? 0 : days == 90 ? 2 : days == 0 ? 3 : 1;
    }

    @Override
    protected void init() {
        super.init();
        statisticsViewModel.reopen();
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
    public void tick() {
        super.tick();
        scheduleStatistics(false);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        StatisticsViewState statisticsState = statisticsViewModel.state();
        CreditStatistics stats = statisticsState.statistics();
        boolean loaded = statisticsState.status() == StatisticsViewState.Status.LOADED;
        OptionalLong accountBalance = loaded ? BalanceReader.readCurrentBalanceMinor(MinecraftClient.getInstance()) : OptionalLong.empty();

        viewportY = contentY + 6;
        viewportHeight = Math.max(32, contentY + contentHeight - viewportY - 7);
        int metricsOffset = periodIndex == 4 ? (contentWidth >= 200 ? 58 : 82) : 30;
        metricsTwoColumns = contentWidth >= 350;
        int metricsHeight = metricsTwoColumns ? METRIC_HEIGHT * 5 + 24 : METRIC_HEIGHT * 10 + 54;
        int chartsY = metricsOffset + metricsHeight + 9;
        int scrollContentHeight = loaded ? chartsY + CHART_HEIGHT + 20 : metricsOffset + 78;
        contentScroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, scrollContentHeight);
        contentScroll.tick(mouseX, mouseY);
        safeContentWidth = Math.max(48, contentWidth - (contentScroll.isScrollable() ? 10 : 0));
        int scrollOffset = contentScroll.offset();
        periodY = viewportY - scrollOffset;

        updateCustomFieldVisibility(periodY + 30, scrollOffset);
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        drawPeriodControl(context, mouseX, mouseY, theme);
        if (loaded) {
            drawMetrics(context, stats, accountBalance, metricsOffset + periodY);
            ModernChartRenderer.bars(context, textRenderer, contentX, periodY + chartsY, safeContentWidth,
                    CHART_HEIGHT, stats.openClaimsMinor(), stats.openDebtsMinor());
            retryBounds = null;
        } else {
            drawStatisticsState(context, mouseX, mouseY, statisticsState, periodY + metricsOffset);
        }
        context.disableScissor();
        contentScroll.renderScrollbar(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawStatisticsState(DrawContext context, int mouseX, int mouseY, StatisticsViewState state, int y) {
        int height = state.status() == StatisticsViewState.Status.ERROR ? 68 : 52;
        ModernUi.card(context, contentX, y, safeContentWidth, height, false);
        int color = state.status() == StatisticsViewState.Status.ERROR || state.status() == StatisticsViewState.Status.INVALID
                ? ModernUi.theme().danger : state.status() == StatisticsViewState.Status.LOADING
                ? ModernUi.theme().warning : ModernUi.theme().muted;
        ModernUi.drawTruncated(context, textRenderer, state.message(), contentX + 10, y + 12,
                Math.max(20, safeContentWidth - 20), color);
        retryBounds = null;
        if (state.status() == StatisticsViewState.Status.ERROR) {
            retryBounds = new ModernLayout.Bounds(contentX + 10, y + 35,
                    Math.min(96, Math.max(48, safeContentWidth - 20)), 23);
            ModernUi.button(context, textRenderer, retryBounds.x(), retryBounds.y(), retryBounds.width(), retryBounds.height(),
                    "Erneut laden", ModernUi.theme().buttonPrimary,
                    ModernUi.contains(mouseX, mouseY, retryBounds.x(), retryBounds.y(), retryBounds.width(), retryBounds.height()));
        }
    }

    private void drawPeriodControl(DrawContext context, int mouseX, int mouseY, ModernThemePalette theme) {
        periodControlWidth = Math.min(140, safeContentWidth);
        ModernUi.button(context, textRenderer, contentX, periodY, periodControlWidth, 22, "Zeitraum: " + PERIODS[periodIndex], theme.buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, periodY, periodControlWidth, 22));
        if (periodIndex == 4) {
            ModernUi.drawGuiText(context, textRenderer, "Von / bis", contentX + Math.min(194, Math.max(148, safeContentWidth - 54)), periodY + 7, theme.muted);
        }
    }

    private void drawMetrics(DrawContext context, CreditStatistics stats, OptionalLong accountBalance, int y) {
        ModernThemePalette theme = ModernUi.theme();
        BigInteger forecastMinor = accountBalance.isPresent()
                ? BigInteger.valueOf(accountBalance.getAsLong()).add(stats.balanceMinor()) : BigInteger.ZERO;
        String forecast = accountBalance.isPresent() ? FormatUtil.formatAmountMinor(forecastMinor)
                : "Kontostand nicht erkennbar";
        List<Metric> metrics = List.of(
                new Metric("Aktuell: offene Forderungen", FormatUtil.formatAmountMinor(stats.openClaimsMinor()), theme.success),
                new Metric("Aktuell: offene Schulden", FormatUtil.formatAmountMinor(stats.openDebtsMinor()), theme.danger),
                new Metric("Aktueller Saldo", (stats.balanceMinor().signum() >= 0 ? "+" : "") + FormatUtil.formatAmountMinor(stats.balanceMinor()),
                        stats.balanceMinor().signum() >= 0 ? theme.success : theme.danger),
                new Metric("Kontostand nach Verrechnung", forecast, accountBalance.isPresent() ? theme.accent : theme.muted),
                new Metric("Zeitraum: neue Forderungen", FormatUtil.formatAmountMinor(stats.createdClaimsInPeriodMinor()), theme.success),
                new Metric("Zeitraum: neue Schulden", FormatUtil.formatAmountMinor(stats.createdDebtsInPeriodMinor()), theme.danger),
                new Metric("Zeitraum: Zahlungen erhalten", FormatUtil.formatAmountMinor(stats.paidClaimsInPeriodMinor()), theme.success),
                new Metric("Zeitraum: Zahlungen geleistet", FormatUtil.formatAmountMinor(stats.paidDebtsInPeriodMinor()), theme.danger),
                new Metric("Zeitraum: Netto-Veränderung", (stats.netChangeMinor().signum() >= 0 ? "+" : "") + FormatUtil.formatAmountMinor(stats.netChangeMinor()),
                        stats.netChangeMinor().signum() >= 0 ? theme.success : theme.danger),
                new Metric("Zeitraum: Aktionen", String.valueOf(stats.actionCount()), theme.muted)
        );
        int columns = metricsTwoColumns ? 2 : 1;
        int metricWidth = metricsTwoColumns ? (safeContentWidth - 8) / 2 : safeContentWidth;
        for (int index = 0; index < metrics.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            drawMetric(context, contentX + column * (metricWidth + 8), y + row * 40, metricWidth,
                    metrics.get(index).label(), metrics.get(index).value(), metrics.get(index).color());
        }
    }

    private void drawMetric(DrawContext context, int x, int y, int width, String label, String value, int color) {
        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, METRIC_HEIGHT, false);
        ModernUi.drawGuiText(context, textRenderer, label, x + 9, y + 6, theme.muted);
        ModernUi.drawTruncated(context, textRenderer, value, x + 9, y + 18, width - 18, color);
    }

    private record Metric(String label, String value, int color) { }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && retryBounds != null && ModernUi.contains(click.x(), click.y(), retryBounds.x(), retryBounds.y(), retryBounds.width(), retryBounds.height())) {
            scheduleStatistics(true);
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, periodY, periodControlWidth, 22)) {
            periodIndex = (periodIndex + 1) % PERIODS.length;
            if (periodIndex != 4 && !ClientConfigManager.setStatisticsDefaultPeriodDays(
                    periodIndex == 0 ? 7 : periodIndex == 2 ? 90 : periodIndex == 3 ? 0 : 30)) {
                toastError("Zeitraum konnte nicht gespeichert werden.");
            }
            validationMessage = "";
            scheduleStatistics(false);
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

    private void scheduleStatistics(boolean retry) {
        if (startDate == null || endDate == null) return;
        long now = System.currentTimeMillis();
        StatisticsRange range = selectedRange(now);
        if (!range.valid()) {
            if (!range.error().equals(validationMessage)) {
                validationMessage = range.error();
                statisticsViewModel.invalidate(range.error());
            }
            return;
        }
        validationMessage = "";
        String player = currentPlayerName();
        CreditEventRepository eventRepository = CreditEventRepository.getInstance();
        long managerRevision = manager.getRevision();
        StatisticsViewKey key = new StatisticsViewKey(player, periodIndex, range.start(), range.end(),
                range.fromInclusive(), managerRevision, eventRepository.getRevision());
        if (!retry && !statisticsViewModel.needsRequest(key)) return;
        CreditStatisticsSnapshot creditSnapshot = manager.getStatisticsSnapshot(player);
        if (creditSnapshot.revision() != managerRevision) {
            key = new StatisticsViewKey(player, periodIndex, range.start(), range.end(), range.fromInclusive(),
                    creditSnapshot.revision(), eventRepository.getRevision());
        }
        StatisticsViewKey requestedKey = key;
        java.util.function.Supplier<CreditStatistics> loader = () -> {
            DatabaseManager.StatisticsEventSlice eventSlice = eventRepository
                    .getStatisticsSlice(player, range.fromInclusive(), range.toInclusive());
            return CreditStatisticsCalculator.calculate(creditSnapshot,
                    eventSlice.events(), eventSlice.inactiveCreditIds(), range.fromInclusive(), range.toInclusive());
        };
        java.util.function.BooleanSupplier stillCurrent = () -> manager.getRevision() == requestedKey.managerRevision()
                && eventRepository.getRevision() == requestedKey.eventRevision();
        if (retry) {
            statisticsViewModel.retry(requestedKey, loader, stillCurrent, ModernQueryExecutor.get(), runnable -> MinecraftClient.getInstance().execute(runnable));
        } else {
            statisticsViewModel.request(requestedKey, loader, stillCurrent, ModernQueryExecutor.get(), runnable -> MinecraftClient.getInstance().execute(runnable));
        }
    }

    private StatisticsRange selectedRange(long now) {
        if (periodIndex == 3) return StatisticsRange.preset(0L, now);
        if (periodIndex == 4) return StatisticsRange.custom(startDate.getText(), endDate.getText(), ZoneId.systemDefault());
        int days = periodIndex == 0 ? 7 : periodIndex == 2 ? 90 : 30;
        return StatisticsRange.preset(rangeStartForPreset(now, days, ZoneId.systemDefault()), now);
    }

    static long rangeStartForPreset(long now, int days, ZoneId zone) {
        if (days < 1) throw new IllegalArgumentException("days must be positive");
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(days - 1L)
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    private void updateCustomFieldVisibility(int fieldY, int scrollOffset) {
        boolean stacked = safeContentWidth < 200;
        boolean startVisible = periodIndex == 4 && fieldY + 18 > viewportY && fieldY < viewportY + viewportHeight;
        int endY = fieldY + (stacked ? 22 : 0);
        boolean endVisible = periodIndex == 4 && endY + 18 > viewportY && endY < viewportY + viewportHeight;
        int fieldWidth = stacked ? Math.max(88, safeContentWidth - 12) : 88;
        if (startDate != null) {
            startDate.setPosition(contentX + 6, fieldY);
            startDate.setWidth(fieldWidth);
            startDate.setVisible(startVisible);
        }
        if (endDate != null) {
            endDate.setPosition(contentX + (stacked ? 6 : 100), endY);
            endDate.setWidth(fieldWidth);
            endDate.setVisible(endVisible);
        }
    }

    @Override
    protected void clearTransientState() {
        contentScroll.reset();
        if (startDate != null) startDate.setText("");
        if (endDate != null) endDate.setText("");
        validationMessage = "";
        retryBounds = null;
        statisticsViewModel.close();
        super.clearTransientState();
    }
}

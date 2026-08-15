package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.util.BalanceReader;
import op.creditmanager.client.util.FormatUtil;

import java.math.BigInteger;
import java.util.OptionalLong;

public class ModernOverviewScreen extends ModernBaseScreen {
    private int claimsY;
    private int debtsY;
    private int statisticsY;
    private int safeContentWidth;
    private final ModernScrollArea contentScroll = new ModernScrollArea();
    private final OpenDealSummaryCache summaryCache = new OpenDealSummaryCache();

    public ModernOverviewScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Detaillierte Übersicht", "overview");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        OpenDealSummaryCache.OpenDealSummary summary = summaryCache.get(manager, currentPlayerName());
        BigInteger claimTotalMinor = summary.claimTotalMinor();
        BigInteger debtTotalMinor = summary.debtTotalMinor();
        BigInteger netMinor = summary.netMinor();
        OptionalLong currentBalance = BalanceReader.readCurrentBalanceMinor(MinecraftClient.getInstance());

        int rowHeight = contentHeight < 260 ? 42 : 49;
        int viewportY = contentY + 6;
        int viewportHeight = Math.max(30, contentHeight - 10);
        contentScroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, 320);
        contentScroll.tick(mouseX, mouseY);
        safeContentWidth = Math.max(48, contentWidth - (contentScroll.isScrollable() ? 10 : 0));
        claimsY = viewportY - contentScroll.offset();
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        debtsY = claimsY + rowHeight + 6;
        drawMetric(context, mouseX, mouseY, claimsY, "Forderungen", FormatUtil.formatAmountMinor(claimTotalMinor),
                summary.claimCount() + " offene Deals · Liste öffnen", theme.success);
        drawMetric(context, mouseX, mouseY, debtsY, "Schulden", FormatUtil.formatAmountMinor(debtTotalMinor),
                summary.debtCount() + " offene Deals · Liste öffnen", theme.danger);

        int columns = safeContentWidth >= 360 ? 2 : 1;
        int belowY = debtsY + rowHeight + 10;
        int metricWidth = columns == 2 ? (safeContentWidth - 8) / 2 : safeContentWidth;
        drawSmallMetric(context, contentX, belowY, metricWidth, "Saldo", (netMinor.signum() >= 0 ? "+" : "") + FormatUtil.formatAmountMinor(netMinor),
                netMinor.signum() >= 0 ? theme.success : theme.danger);
        int accountX = columns == 2 ? contentX + metricWidth + 8 : contentX;
        int accountY = columns == 2 ? belowY : belowY + 42;
        drawSmallMetric(context, accountX, accountY, metricWidth,
                currentBalance.isPresent() ? "Kontostand" : "Kontostand", currentBalance.isPresent()
                        ? FormatUtil.formatAmountMinor(currentBalance.getAsLong()) : "aktuell nicht erkennbar",
                currentBalance.isPresent() ? theme.accent : theme.muted);
        int forecastY = columns == 2 ? belowY + 42 : accountY + 42;
        BigInteger forecastMinor = currentBalance.isPresent() ? BigInteger.valueOf(currentBalance.getAsLong()).add(netMinor) : BigInteger.ZERO;
        String forecast = currentBalance.isPresent() ? FormatUtil.formatAmountMinor(forecastMinor)
                : "Kontostand wird benötigt";
        drawSmallMetric(context, contentX, forecastY, safeContentWidth, "Nach kompletter Verrechnung", forecast,
                currentBalance.isPresent() && forecastMinor.signum() < 0 ? theme.danger : theme.success);

        statisticsY = forecastY + 50;
        ModernUi.button(context, textRenderer, contentX, statisticsY, Math.min(180, safeContentWidth), 23, "Statistiken", theme.buttonPrimary,
                ModernUi.contains(mouseX, mouseY, contentX, statisticsY, Math.min(180, safeContentWidth), 23));
        context.disableScissor();
        contentScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawMetric(DrawContext context, int mouseX, int mouseY, int y, String label, String value, String detail, int accent) {
        ModernThemePalette theme = ModernUi.theme();
        boolean hovered = ModernUi.contains(mouseX, mouseY, contentX, y, safeContentWidth, contentHeight < 260 ? 42 : 49);
        ModernUi.card(context, contentX, y, safeContentWidth, contentHeight < 260 ? 42 : 49, hovered);
        context.fill(contentX + 10, y + 9, contentX + 13, y + 34, accent);
        ModernUi.drawGuiText(context, textRenderer, label, contentX + 22, y + 8, theme.muted);
        ModernUi.drawGuiText(context, textRenderer, value, contentX + 22, y + 22, accent);
        ModernUi.drawTruncated(context, textRenderer, detail, contentX + 128, y + 22, safeContentWidth - 140, theme.text);
    }

    private void drawSmallMetric(DrawContext context, int x, int y, int width, String label, String value, int accent) {
        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, 34, false);
        ModernUi.drawGuiText(context, textRenderer, label, x + 10, y + 7, theme.muted);
        ModernUi.drawTruncated(context, textRenderer, value, x + 10, y + 19, width - 20, accent);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (!contentScroll.contains(click.x(), click.y())) return super.mouseClicked(click, doubled);
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, claimsY, safeContentWidth,
                contentHeight < 260 ? 42 : 49)) {
            open(new ModernCreditListScreen(manager, false, null));
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, debtsY, safeContentWidth,
                contentHeight < 260 ? 42 : 49)) {
            open(new ModernCreditListScreen(manager, true, null));
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, statisticsY,
                Math.min(180, safeContentWidth), 23)) {
            open(new ModernStatisticsScreen(manager, this));
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

    @Override
    protected void clearTransientState() {
        contentScroll.reset();
        super.clearTransientState();
    }
}

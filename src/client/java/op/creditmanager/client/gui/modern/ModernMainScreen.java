package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.BalanceReader;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;
import java.util.OptionalLong;

public class ModernMainScreen extends ModernBaseScreen {
    private int claimsX;
    private int debtsX;
    private int cardsY;
    private int cardWidth;
    private int summaryHeight;
    private int newClaimsY;
    private int newDebtsY;
    private int overviewY;
    private int statisticsY;
    private int compactStatisticsX;
    private int compactStatisticsY;
    private int compactStatisticsWidth;
    private int safeContentWidth;
    private final ModernScrollArea contentScroll = new ModernScrollArea();
    private int contentViewportY;
    private int contentViewportHeight;

    public ModernMainScreen(CreditManager manager) {
        super(manager, null, "Übersicht", "overview");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        List<CreditEntry> claims = manager.getOpenCreditsAsCreditor(currentPlayerName());
        List<CreditEntry> debts = manager.getOpenCreditsAsDebtor(currentPlayerName());
        long claimTotalMinor = sumRemainingMinor(claims);
        long debtTotalMinor = sumRemainingMinor(debts);
        long netMinor = Math.subtractExact(claimTotalMinor, debtTotalMinor);
        OptionalLong account = BalanceReader.readCurrentBalanceMinor(MinecraftClient.getInstance());

        boolean compact = contentHeight < 250;
        summaryHeight = compact ? 62 : 78;
        int statusHeight = compact ? 58 : 74;
        contentViewportY = contentY + 6;
        contentViewportHeight = Math.max(30, contentHeight - 10);
        int totalContentHeight = summaryHeight + 10 + statusHeight + 12 + 4 * 29;
        contentScroll.setBounds(contentX, contentViewportY, contentWidth, contentViewportHeight, totalContentHeight);
        contentScroll.tick(mouseX, mouseY);
        safeContentWidth = Math.max(48, contentWidth - (contentScroll.isScrollable() ? 10 : 0));
        cardsY = contentViewportY - contentScroll.offset();
        context.enableScissor(contentX, contentViewportY, contentX + contentWidth, contentViewportY + contentViewportHeight);
        cardWidth = Math.max(70, (safeContentWidth - 10) / 2);
        claimsX = contentX;
        debtsX = contentX + cardWidth + 10;
        drawSummaryCard(context, mouseX, mouseY, claimsX, cardsY, cardWidth, summaryHeight, "Forderungen", claims.size(), claimTotalMinor,
                theme.success, compact ? "" : "Offen für dich");
        drawSummaryCard(context, mouseX, mouseY, debtsX, cardsY, cardWidth, summaryHeight, "Schulden", debts.size(), debtTotalMinor,
                theme.danger, compact ? "" : "Von dir offen");

        int statusY = cardsY + summaryHeight + 10;
        ModernUi.card(context, contentX, statusY, safeContentWidth, statusHeight,
                ModernUi.contains(mouseX, mouseY, contentX, statusY, safeContentWidth, statusHeight));
        ModernUi.drawGuiText(context, textRenderer, netLabel("Saldo", netMinor), contentX + 12, statusY + 11, theme.muted);
        ModernUi.drawGuiText(context, textRenderer, netText(netMinor), contentX + 12, statusY + 26,
                netMinor >= 0 ? theme.success : theme.danger);
        String accountLabel = account.isPresent() ? "Kontostand" : "Kontostand nicht erkennbar";
        String accountValue = account.isPresent() ? FormatUtil.formatAmountMinor(account.getAsLong()) : "–";
        int right = contentX + safeContentWidth - 12;
        ModernUi.drawGuiTextRightAligned(context, textRenderer, accountLabel, right, statusY + 11, theme.muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, accountValue, right, statusY + 26,
                account.isPresent() ? theme.accent : theme.muted);
        if (!compact) {
            long forecastMinor = account.isPresent() ? Math.addExact(account.getAsLong(), netMinor) : 0L;
            String forecast = account.isPresent() ? "Nach Verrechnung: " + FormatUtil.formatAmountMinor(forecastMinor)
                    : "Nach Verrechnung wird angezeigt, sobald der Kontostand lesbar ist.";
            ModernUi.drawTruncated(context, textRenderer, forecast, contentX + 12, statusY + 51, safeContentWidth - 24,
                    account.isPresent() ? (forecastMinor >= 0 ? theme.success : theme.danger) : theme.muted);
        }
        compactStatisticsY = -1;
        if (compact) {
            compactStatisticsWidth = Math.min(102, Math.max(72, safeContentWidth / 3));
            compactStatisticsX = contentX + safeContentWidth - compactStatisticsWidth - 10;
            compactStatisticsY = statusY + statusHeight - 22;
            ModernUi.button(context, textRenderer, compactStatisticsX, compactStatisticsY, compactStatisticsWidth, 18, "Statistiken",
                    theme.buttonNeutral, ModernUi.contains(mouseX, mouseY, compactStatisticsX, compactStatisticsY, compactStatisticsWidth, 18));
        }

        int actionsY = statusY + statusHeight + 12;
        int actionWidth = Math.min(220, safeContentWidth);
        newClaimsY = actionsY;
        newDebtsY = actionsY + 29;
        overviewY = actionsY + 58;
        statisticsY = actionsY + 87;
        ModernUi.button(context, textRenderer, contentX, newClaimsY, actionWidth, 23, "+ Neue Forderung", theme.buttonPrimary,
                ModernUi.contains(mouseX, mouseY, contentX, newClaimsY, actionWidth, 23));
        ModernUi.button(context, textRenderer, contentX, newDebtsY, actionWidth, 23, "+ Neue Schuld", theme.buttonDanger,
                ModernUi.contains(mouseX, mouseY, contentX, newDebtsY, actionWidth, 23));
        ModernUi.button(context, textRenderer, contentX, overviewY, actionWidth, 23, "Detaillierte Übersicht", theme.buttonGold,
                ModernUi.contains(mouseX, mouseY, contentX, overviewY, actionWidth, 23));
        ModernUi.button(context, textRenderer, contentX, statisticsY, actionWidth, 23, "Statistiken", theme.buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, statisticsY, actionWidth, 23));
        context.disableScissor();
        contentScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private String netLabel(String label, long valueMinor) {
        return label + (valueMinor >= 0 ? " · positiv" : " · negativ");
    }

    private String netText(long valueMinor) {
        return (valueMinor >= 0 ? "+" : "") + FormatUtil.formatAmountMinor(valueMinor);
    }

    private void drawSummaryCard(DrawContext context, int mouseX, int mouseY, int x, int y, int width, int height,
                                 String title, int count, long totalMinor, int accent, String description) {
        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, height, ModernUi.contains(mouseX, mouseY, x, y, width, height));
        context.fill(x + 10, y + 11, x + 13, y + height - 11, accent);
        ModernUi.drawGuiText(context, textRenderer, title, x + 22, y + 11, theme.muted);
        ModernUi.drawTruncated(context, textRenderer, FormatUtil.formatAmountMinor(totalMinor), x + 22, y + 27, width - 34, accent);
        ModernUi.drawTruncated(context, textRenderer, count + (count == 1 ? " offener Deal" : " offene Deals"),
                x + 22, y + 43, width - 34, theme.text);
        if (!description.isEmpty()) ModernUi.drawTruncated(context, textRenderer, description, x + 22, y + 59, width - 34, theme.muted);
    }

    private long sumRemainingMinor(List<CreditEntry> entries) {
        long sum = 0L;
        for (CreditEntry entry : entries) sum = Math.addExact(sum, entry.getRemainingAmountMinor());
        return sum;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (!contentScroll.contains(click.x(), click.y())) return super.mouseClicked(click, doubled);
        if (click.button() == 0) {
            if (compactStatisticsY >= 0 && ModernUi.contains(click.x(), click.y(), compactStatisticsX, compactStatisticsY,
                    compactStatisticsWidth, 18)) {
                open(new ModernStatisticsScreen(manager, this));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), claimsX, cardsY, cardWidth, summaryHeight)) {
                open(new ModernCreditListScreen(manager, false, null));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), debtsX, cardsY, cardWidth, summaryHeight)) {
                open(new ModernCreditListScreen(manager, true, null));
                return true;
            }
            int actionWidth = Math.min(220, safeContentWidth);
            if (ModernUi.contains(click.x(), click.y(), contentX, newClaimsY, actionWidth, 23)) {
                open(new ModernCreateCreditScreen(manager, false, this));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX, newDebtsY, actionWidth, 23)) {
                open(new ModernCreateCreditScreen(manager, true, this));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX, overviewY, actionWidth, 23)) {
                open(new ModernOverviewScreen(manager, this));
                return true;
            }
            if (statisticsY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, statisticsY, actionWidth, 23)) {
                open(new ModernStatisticsScreen(manager, this));
                return true;
            }
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

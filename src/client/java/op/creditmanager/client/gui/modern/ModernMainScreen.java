package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;

/** Modern landing page with the most important totals and primary actions. */
public class ModernMainScreen extends ModernBaseScreen {

    private int claimsX;
    private int debtsX;
    private int cardsY;
    private int cardWidth;
    private int summaryHeight;
    private int newClaimsY;
    private int newDebtsY;
    private int overviewY;

    public ModernMainScreen(CreditManager manager) {
        super(manager, null, "Übersicht", "overview");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);

        List<CreditEntry> claims = manager.getOpenCreditsAsCreditor(currentPlayerName());
        List<CreditEntry> debts = manager.getOpenCreditsAsDebtor(currentPlayerName());
        double claimTotal = claims.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double debtTotal = debts.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double balance = claimTotal - debtTotal;

        boolean compact = contentHeight < 230;
        cardsY = contentY + 6;
        summaryHeight = compact ? 64 : 82;
        cardWidth = Math.max(70, (contentWidth - 12) / 2);
        claimsX = contentX;
        debtsX = contentX + cardWidth + 12;
        drawSummaryCard(context, mouseX, mouseY, claimsX, cardsY, cardWidth, summaryHeight, "Forderungen", claims.size(), claimTotal,
                ModernUi.GREEN, compact ? "" : "Jemand schuldet dir Geld");
        drawSummaryCard(context, mouseX, mouseY, debtsX, cardsY, cardWidth, summaryHeight, "Schulden", debts.size(), debtTotal,
                ModernUi.RED, compact ? "" : "Du schuldest anderen Geld");

        int balanceY = cardsY + summaryHeight + 12;
        int balanceHeight = compact ? 50 : 62;
        ModernUi.card(context, contentX, balanceY, contentWidth, balanceHeight,
                ModernUi.contains(mouseX, mouseY, contentX, balanceY, contentWidth, balanceHeight));
        context.drawText(textRenderer, net.minecraft.text.Text.literal("Dein Saldo"), contentX + 14, balanceY + 12, ModernUi.MUTED, false);
        String signedBalance = (balance >= 0 ? "+" : "") + FormatUtil.formatAmount(balance);
        context.drawText(textRenderer, net.minecraft.text.Text.literal(signedBalance), contentX + 14, balanceY + 29,
                balance >= 0 ? ModernUi.GREEN : ModernUi.RED, false);
        ModernUi.drawTruncated(context, textRenderer,
                balance >= 0 ? "Du bist insgesamt im Plus." : "Du bist insgesamt im Minus.",
                contentX + 14, balanceY + (compact ? 40 : 47), Math.max(40, contentWidth - 28), ModernUi.MUTED);

        if (compact) {
            newClaimsY = -1;
            newDebtsY = -1;
            overviewY = -1;
        } else {
            newClaimsY = balanceY + balanceHeight + 16;
            newDebtsY = newClaimsY + 30;
            overviewY = newDebtsY + 30;
            int actionWidth = Math.min(contentWidth, 220);
            ModernUi.button(context, textRenderer, contentX, newClaimsY, actionWidth, 24, "+ Neue Forderung", ModernUi.BUTTON_PRIMARY,
                    ModernUi.contains(mouseX, mouseY, contentX, newClaimsY, actionWidth, 24));
            ModernUi.button(context, textRenderer, contentX, newDebtsY, actionWidth, 24, "+ Neue Schuld", ModernUi.BUTTON_DANGER,
                    ModernUi.contains(mouseX, mouseY, contentX, newDebtsY, actionWidth, 24));
            ModernUi.button(context, textRenderer, contentX, overviewY, actionWidth, 24, "Detaillierte Übersicht", ModernUi.BUTTON_GOLD,
                    ModernUi.contains(mouseX, mouseY, contentX, overviewY, actionWidth, 24));
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSummaryCard(DrawContext context, int mouseX, int mouseY, int x, int y, int width, int height,
                                 String title, int count, double total, int accent, String description) {
        ModernUi.card(context, x, y, width, height, ModernUi.contains(mouseX, mouseY, x, y, width, height));
        context.fill(x + 12, y + 13, x + 15, y + height - 12, accent);
        context.drawText(textRenderer, net.minecraft.text.Text.literal(title), x + 25, y + 13, ModernUi.MUTED, false);
        ModernUi.drawTruncated(context, textRenderer, FormatUtil.formatAmount(total), x + 25, y + 29, width - 37, accent);
        ModernUi.drawTruncated(context, textRenderer, count + (count == 1 ? " offener Deal" : " offene Deals"),
                x + 25, y + 46, width - 37, ModernUi.TEXT);
        if (!description.isEmpty()) {
            ModernUi.drawTruncated(context, textRenderer, description, x + 25, y + 60, width - 37, ModernUi.MUTED);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) {
            return true;
        }
        if (click.button() == 0) {
            if (ModernUi.contains(click.x(), click.y(), claimsX, cardsY, cardWidth, summaryHeight)) {
                open(new ModernCreditListScreen(manager, false, this));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), debtsX, cardsY, cardWidth, summaryHeight)) {
                open(new ModernCreditListScreen(manager, true, this));
                return true;
            }
            int actionWidth = Math.min(contentWidth, 220);
            if (newClaimsY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, newClaimsY, actionWidth, 24)) {
                open(new ModernCreateCreditScreen(manager, false, this));
                return true;
            }
            if (newDebtsY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, newDebtsY, actionWidth, 24)) {
                open(new ModernCreateCreditScreen(manager, true, this));
                return true;
            }
            if (overviewY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, overviewY, actionWidth, 24)) {
                open(new ModernOverviewScreen(manager, this));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }
}

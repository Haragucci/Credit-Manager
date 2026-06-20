package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;

/** Focused balance view retained as a separate modern page. */
public class ModernOverviewScreen extends ModernBaseScreen {

    private int claimsButtonY;
    private int debtsButtonY;
    private int overviewCardY;
    private int overviewCardHeight;

    public ModernOverviewScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Detaillierte Übersicht", "overview");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        List<CreditEntry> claims = manager.getOpenCreditsAsCreditor(currentPlayerName());
        List<CreditEntry> debts = manager.getOpenCreditsAsDebtor(currentPlayerName());
        double claimTotal = claims.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double debtTotal = debts.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double balance = claimTotal - debtTotal;

        boolean compact = contentHeight < 190;
        int cardY = contentY + 8;
        int cardHeight = compact ? 80 : 126;
        overviewCardY = cardY;
        overviewCardHeight = cardHeight;
        ModernUi.card(context, contentX, cardY, contentWidth, cardHeight, false);
        int splitY = compact ? cardY + 48 : cardY + 62;
        boolean claimsHovered = ModernUi.contains(mouseX, mouseY, contentX, cardY, contentWidth, splitY - cardY);
        boolean debtsHovered = ModernUi.contains(mouseX, mouseY, contentX, splitY, contentWidth, cardY + cardHeight - splitY);
        if (claimsHovered) {
            context.fill(contentX + 1, cardY + 1, contentX + contentWidth - 1, splitY, ModernUi.HOVER_POSITIVE);
        }
        if (debtsHovered) {
            context.fill(contentX + 1, splitY, contentX + contentWidth - 1, cardY + cardHeight - 1, ModernUi.HOVER_DANGER);
        }
        context.drawText(textRenderer, Text.literal("Forderungen"), contentX + 16, cardY + 17, ModernUi.MUTED, false);
        context.drawText(textRenderer, Text.literal(FormatUtil.formatAmount(claimTotal)), contentX + 16, cardY + 31, ModernUi.GREEN, false);
        context.drawText(textRenderer, Text.literal(claims.size() + " offene Deals"), contentX + 16, cardY + 47, ModernUi.TEXT, false);
        if (compact) {
            context.drawText(textRenderer, Text.literal("Schulden"), contentX + 16, cardY + 58, ModernUi.MUTED, false);
            context.drawText(textRenderer, Text.literal(FormatUtil.formatAmount(debtTotal)), contentX + 90, cardY + 58, ModernUi.RED, false);
        } else {
            context.drawText(textRenderer, Text.literal("Schulden"), contentX + 16, cardY + 69, ModernUi.MUTED, false);
            context.drawText(textRenderer, Text.literal(FormatUtil.formatAmount(debtTotal)), contentX + 16, cardY + 83, ModernUi.RED, false);
            context.drawText(textRenderer, Text.literal(debts.size() + " offene Deals"), contentX + 16, cardY + 99, ModernUi.TEXT, false);
        }
        if (!compact && contentWidth >= 340) {
            context.fill(contentX + contentWidth - 145, cardY + 16, contentX + contentWidth - 144, cardY + 110, ModernUi.BORDER);
            ModernUi.drawCentered(context, textRenderer, "Saldo", contentX + contentWidth - 72, cardY + 27, ModernUi.MUTED);
            ModernUi.drawCentered(context, textRenderer, (balance >= 0 ? "+" : "") + FormatUtil.formatAmount(balance),
                    contentX + contentWidth - 72, cardY + 48, balance >= 0 ? ModernUi.GREEN : ModernUi.RED);
            ModernUi.drawCentered(context, textRenderer, balance >= 0 ? "Positiver Stand" : "Negativer Stand",
                    contentX + contentWidth - 72, cardY + 70, ModernUi.TEXT);
        } else if (!compact) {
            ModernUi.drawTruncated(context, textRenderer, "Saldo: " + (balance >= 0 ? "+" : "") + FormatUtil.formatAmount(balance),
                    contentX + 16, cardY + 112, contentWidth - 32, balance >= 0 ? ModernUi.GREEN : ModernUi.RED);
        }
        if (contentWidth >= 300) {
            ModernUi.drawTruncated(context, textRenderer, claimsHovered ? "Klicken zum Öffnen" : "Forderungen", contentX + contentWidth - 108,
                    cardY + 17, 96, claimsHovered ? ModernUi.GREEN : ModernUi.MUTED);
            ModernUi.drawTruncated(context, textRenderer, debtsHovered ? "Klicken zum Öffnen" : "Schulden", contentX + contentWidth - 108,
                    splitY + 9, 96, debtsHovered ? ModernUi.RED : ModernUi.MUTED);
        }

        if (compact) {
            claimsButtonY = -1;
            debtsButtonY = -1;
        } else {
            claimsButtonY = cardY + 145;
            debtsButtonY = claimsButtonY + 31;
            ModernUi.button(context, textRenderer, contentX, claimsButtonY, 205, 24, "Forderungen öffnen", ModernUi.BUTTON_PRIMARY,
                    ModernUi.contains(mouseX, mouseY, contentX, claimsButtonY, 205, 24));
            ModernUi.button(context, textRenderer, contentX, debtsButtonY, 205, 24, "Schulden öffnen", ModernUi.BUTTON_DANGER,
                    ModernUi.contains(mouseX, mouseY, contentX, debtsButtonY, 205, 24));
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, overviewCardY, contentWidth,
                Math.min(overviewCardHeight, 62))) {
            open(new ModernCreditListScreen(manager, false, this));
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX,
                overviewCardY + (overviewCardHeight < 100 ? 48 : 62), contentWidth,
                overviewCardHeight - (overviewCardHeight < 100 ? 48 : 62))) {
            open(new ModernCreditListScreen(manager, true, this));
            return true;
        }
        if (click.button() == 0 && claimsButtonY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, claimsButtonY, 205, 24)) {
            open(new ModernCreditListScreen(manager, false, this));
            return true;
        }
        if (click.button() == 0 && debtsButtonY >= 0 && ModernUi.contains(click.x(), click.y(), contentX, debtsButtonY, 205, 24)) {
            open(new ModernCreditListScreen(manager, true, this));
            return true;
        }
        return super.mouseClicked(click, doubled);
    }
}

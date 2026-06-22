package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;

/** Lets the user apply a paylog to one matching deal, preserving any overpay remainder. */
public final class ModernPaylogLinkScreen extends ModernBaseScreen {
    private final TransactionEntry paylog;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private int listY, listHeight, renderedStart;
    private List<CreditEntry> rendered = List.of();

    public ModernPaylogLinkScreen(CreditManager manager, TransactionEntry paylog, Screen parent) {
        super(manager, parent, "Paylog verknüpfen", "paylogs");
        this.paylog = paylog;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernUi.card(context, contentX, contentY + 5, contentWidth, 59, false);
        ModernUi.drawTruncated(context, textRenderer, safe(paylog.getFromPlayer()) + " → " + safe(paylog.getToPlayer()), contentX + 10, contentY + 14, contentWidth - 20, ModernUi.theme().text);
        ModernUi.drawGuiText(context, textRenderer, "Paylog: " + FormatUtil.formatAmount(paylog.getAmount()) + " · verfügbar: " + FormatUtil.formatAmount(paylog.getRemainingAmount()),
                contentX + 10, contentY + 31, paylog.isFullyLinked() ? ModernUi.theme().success : ModernUi.theme().muted);
        ModernUi.drawGuiText(context, textRenderer, "Wähle einen passenden offenen Deal", contentX + 10, contentY + 47, ModernUi.theme().muted);

        List<CreditEntry> entries = paylog.isFullyLinked() ? List.of() : manager.getLinkableCreditsForPaylog(paylog);
        listY = contentY + 73;
        listHeight = Math.max(42, contentHeight - 78);
        int rowHeight = 43;
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight);
        scroll.tick(mouseX, mouseY);
        int offset = scroll.offset();
        renderedStart = Math.max(0, offset / rowHeight);
        int end = Math.min(entries.size(), renderedStart + listHeight / rowHeight + 3);
        rendered = entries.subList(renderedStart, end);
        if (rendered.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(56, listHeight), false);
            String message = paylog.isFullyLinked() ? "Dieser Paylog ist bereits vollständig verwendet." : "Keine passenden offenen Deals gefunden.";
            ModernUi.drawCentered(context, textRenderer, message, contentX + contentWidth / 2, listY + 24, ModernUi.theme().muted);
        } else {
            context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
            for (int index = 0; index < rendered.size(); index++) drawDeal(context, mouseX, mouseY, rendered.get(index),
                    contentX, listY + (renderedStart + index) * rowHeight - offset, contentWidth - (scroll.isScrollable() ? 8 : 0));
            context.disableScissor();
            scroll.renderScrollbar(context, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawDeal(DrawContext context, int mouseX, int mouseY, CreditEntry deal, int x, int y, int width) {
        ModernUi.card(context, x, y, width, 39, ModernUi.contains(mouseX, mouseY, x, y, width, 39));
        ModernUi.drawTruncated(context, textRenderer, deal.getDealName(), x + 10, y + 8, Math.max(44, width - 145), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, "Offen: " + FormatUtil.formatAmount(deal.getRemainingAmount()) + " · klicken zum Buchen", x + 10, y + 23,
                Math.max(44, width - 145), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, FormatUtil.formatAmount(Math.min(paylog.getRemainingAmount(), deal.getRemainingAmount())),
                x + width - 10, y + 14, ModernUi.theme().success);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 43);
            if (index >= renderedStart && index < renderedStart + rendered.size()) {
                link(rendered.get(index - renderedStart));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void link(CreditEntry deal) {
        try {
            CreditManager.PaylogLinkResult result = manager.linkPaylogToDeal(paylog.getId(), deal.getId());
            if (result.linked()) {
                if (result.remainingPaylogAmount() > 0.0001D) {
                    toastWarning("Teilbetrag gebucht; " + FormatUtil.formatAmount(result.remainingPaylogAmount()) + " bleiben im Paylog verfügbar.");
                } else {
                    toastSuccess("Paylog vollständig als Zahlung gebucht.");
                }
                closeToParent();
            } else {
                toastWarning("Dieser Paylog ist bereits vollständig verwendet.");
            }
        } catch (CreditManager.CreditException exception) {
            toastError(exception.getMessage());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scroll.contains(mouseX, mouseY)) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String safe(String value) { return value == null || value.isBlank() ? "Unbekannt" : value; }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class ModernCreditDetailScreen extends ModernBaseScreen {

    private CreditEntry entry;
    private final boolean debts;
    private int paymentListY;
    private int paymentListHeight;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private List<Payment> renderedPayments = List.of();
    private int renderedStart;
    private DealAction pendingDealAction;
    private UUID paymentDeleteArmed;
    private int actionWidth;

    private enum DealAction { ARCHIVE, CLOSE }

    public ModernCreditDetailScreen(CreditManager manager, CreditEntry entry, boolean debts, Screen parent) {
        super(manager, parent, "Deal-Details", debts ? "debts" : "claims");
        this.entry = entry;
        this.debts = debts;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refreshEntry();
        renderShell(context, mouseX, mouseY);

        int topY = contentY + 5;
        ModernUi.card(context, contentX, topY, contentWidth, 89, false);

        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), contentX + 14, topY + 12,
                Math.max(48, contentWidth - 28), ModernUi.theme().text);

        String counterparty = debts ? entry.getCreditor() : entry.getDebtor();

        ModernUi.drawTruncated(context, textRenderer,
                debts ? "Gläubiger: " + safe(counterparty) : "Schuldner: " + safe(counterparty),
                contentX + 14, topY + 28, contentWidth - 28, ModernUi.theme().muted);

        ModernUi.drawGuiText(context, textRenderer, "Gesamt: " + FormatUtil.formatAmount(entry.getAmount()),
                contentX + 14, topY + 44, ModernUi.theme().muted);

        ModernUi.drawGuiText(context, textRenderer, "Bezahlt: " + FormatUtil.formatAmount(entry.getPaidAmount()),
                contentX + 14, topY + 58, ModernUi.theme().success);

        String remaining = "Offen: " + FormatUtil.formatAmount(entry.getRemainingAmount());

        ModernUi.drawGuiTextRightAligned(context, textRenderer, remaining, contentX + contentWidth - 14, topY + 44,
                debts ? ModernUi.theme().danger : ModernUi.theme().success);

        String status = statusLabel(entry.getStatus());

        ModernUi.drawGuiTextRightAligned(context, textRenderer, status, contentX + contentWidth - 14, topY + 58,
                statusColor(entry.getStatus()));

        if (entry.getDueDate() != null) {
            ModernUi.drawTruncated(context, textRenderer, "Fällig: " + TimeUtil.formatDate(entry.getDueDate()),
                    contentX + 14, topY + 73, contentWidth - 28,
                    TimeUtil.isOverdue(entry.getDueDate()) ? ModernUi.theme().danger : ModernUi.theme().muted);
        }

        int actionY = topY + 92;
        actionWidth = Math.max(48, (contentWidth - 16) / 3);

        boolean finished = CreditManager.STATUS_PAID.equals(entry.getStatus())
                || CreditManager.STATUS_CANCELLED.equals(entry.getStatus());
        boolean canReactivate = entry.isArchived() || CreditManager.STATUS_CANCELLED.equals(entry.getStatus());

        ModernUi.button(context, textRenderer, contentX, actionY, actionWidth, 23,
                finished ? canReactivate ? "Reaktivieren" : "Abgeschlossen" : "Zahlung",
                finished ? ModernUi.theme().buttonNeutral : ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, contentX, actionY, actionWidth, 23));

        ModernUi.button(context, textRenderer, contentX + actionWidth + 8, actionY, actionWidth, 23,
                finished ? "Bearbeiten" : pendingDealAction == DealAction.CLOSE ? "Bestätigen" : "Abschließen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX + actionWidth + 8, actionY, actionWidth, 23));

        ModernUi.button(context, textRenderer, contentX + (actionWidth + 8) * 2, actionY, actionWidth, 23,
                pendingDealAction == DealAction.ARCHIVE ? "Bestätigen" : "Archivieren",
                ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX + (actionWidth + 8) * 2, actionY, actionWidth, 23));

        List<Payment> payments = manager.getPaymentsForCredit(entry.getId()).stream()
                .sorted(Comparator.comparingLong(Payment::getTimestamp).reversed())
                .toList();

        int paymentTitleY = actionY + 36;
        ModernUi.drawGuiText(context, textRenderer, "Zahlungen · " + payments.size(),
                contentX, paymentTitleY, ModernUi.theme().muted);

        paymentListY = paymentTitleY + 15;
        paymentListHeight = Math.max(44, contentHeight - (paymentListY - contentY) - 4);

        int rowHeight = 39;
        int visibleRows = Math.max(1, paymentListHeight / rowHeight);

        scroll.setBounds(contentX, paymentListY, contentWidth, paymentListHeight, payments.size() * rowHeight);
        scroll.tick(mouseX, mouseY);

        int pixelOffset = scroll.offset();
        renderedStart = Math.max(0, pixelOffset / rowHeight);

        int end = Math.min(payments.size(), renderedStart + visibleRows + 2);
        renderedPayments = payments.subList(renderedStart, end);

        if (renderedPayments.isEmpty()) {
            int emptyBoxHeight = Math.min(56, paymentListHeight);

            ModernUi.card(context, contentX, paymentListY, contentWidth, emptyBoxHeight, false);

            ModernUi.drawCentered(context, textRenderer, "Noch keine Zahlungen eingetragen.",
                    contentX + contentWidth / 2,
                    paymentListY + emptyBoxHeight / 2 - textRenderer.fontHeight / 2,
                    ModernUi.theme().muted);
        } else {
            context.enableScissor(contentX, paymentListY, contentX + contentWidth, paymentListY + paymentListHeight);

            for (int i = 0; i < renderedPayments.size(); i++) {
                drawPayment(context, mouseX, mouseY, renderedPayments.get(i), contentX,
                        paymentListY + (renderedStart + i) * rowHeight - pixelOffset,
                        contentWidth - (scroll.isScrollable() ? 8 : 0));
            }

            context.disableScissor();
            scroll.renderScrollbar(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPayment(DrawContext context, int mouseX, int mouseY, Payment payment, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 35);
        ModernUi.card(context, x, y, width, 35, hovered);
        boolean itemPayment = payment.getItems() != null && !payment.getItems().isEmpty();
        int color = itemPayment ? ModernUi.theme().warning : ModernUi.theme().success;
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String label = itemPayment ? "Item-Zahlung" : "Geld-Zahlung";
        ModernUi.drawTruncated(context, textRenderer, label, x + 19, y + 7, Math.max(40, width - 156), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, safe(payment.getFromPlayer()) + " -> " + safe(payment.getToPlayer())
                        + " · " + TimeUtil.formatDateTime(payment.getTimestamp()), x + 19, y + 20,
                Math.max(40, width - 156), ModernUi.theme().muted);
        String amount = FormatUtil.formatAmount(payment.getAmount() == null ? 0 : payment.getAmount());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, amount, x + width - 12, y + 7, color);
        String deleteHint = paymentDeleteArmed != null && paymentDeleteArmed.equals(payment.getId()) ? "Rechtsklick bestätigt" : "Rechtsklick: löschen";
        ModernUi.drawGuiTextRightAligned(context, textRenderer, deleteHint, x + width - 12, y + 20,
                paymentDeleteArmed != null && paymentDeleteArmed.equals(payment.getId()) ? ModernUi.theme().danger : ModernUi.theme().muted);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        int topY = contentY + 5;
        int actionY = topY + 92;
        if (click.button() == 0) {
            if (ModernUi.contains(click.x(), click.y(), contentX, actionY, actionWidth, 23)) {
                if (entry.isArchived() || CreditManager.STATUS_CANCELLED.equals(entry.getStatus())) {
                    reactivateDeal();
                } else if (CreditManager.STATUS_PAID.equals(entry.getStatus())) {
                    toastInfo("Zahlungen können in der Liste per Rechtsklick gelöscht werden.");
                } else {
                    open(new ModernPaymentScreen(manager, entry, debts, this));
                }
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX + actionWidth + 8, actionY, actionWidth, 23)) {
                if (CreditManager.STATUS_PAID.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus())) {
                    open(new ModernEditCreditScreen(manager, entry, debts, this));
                } else {
                    closeDeal();
                }
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX + (actionWidth + 8) * 2, actionY, actionWidth, 23)) {
                archiveDeal();
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX, paymentListY, contentWidth, paymentListHeight)) {
                int index = (int) ((click.y() - paymentListY + scroll.offset()) / 39);
                if (index >= renderedStart && index < renderedStart + renderedPayments.size()) {
                    Payment payment = renderedPayments.get(index - renderedStart);
                    if (payment.getItems() != null && !payment.getItems().isEmpty()) {
                        open(new ModernItemInspectionScreen(manager, payment, this));
                    }
                    return true;
                }
            }
        }
        if (click.button() == 1 && ModernUi.contains(click.x(), click.y(), contentX, paymentListY, contentWidth, paymentListHeight)) {
            int index = (int) ((click.y() - paymentListY + scroll.offset()) / 39);
            if (index >= renderedStart && index < renderedStart + renderedPayments.size()) {
                deletePayment(renderedPayments.get(index - renderedStart));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void archiveDeal() {
        if (pendingDealAction != DealAction.ARCHIVE) {
            pendingDealAction = DealAction.ARCHIVE;
            toastWarning("Erneut klicken, um den Deal zu archivieren.");
            return;
        }
        try {
            manager.archiveCredit(entry.getId());
            pendingDealAction = null;
            toastSuccess("Deal archiviert.");
        } catch (CreditManager.CreditException exception) {
            pendingDealAction = null;
            toastError(exception.getMessage());
        }
    }

    private void closeDeal() {
        if (pendingDealAction != DealAction.CLOSE) {
            pendingDealAction = DealAction.CLOSE;
            toastWarning("Erneut klicken, um den Deal ohne Zahlung abzuschließen.");
            return;
        }
        try {
            manager.closeCredit(entry.getId());
            pendingDealAction = null;
            toastSuccess("Deal abgeschlossen und archiviert.");
        } catch (CreditManager.CreditException exception) {
            pendingDealAction = null;
            toastError(exception.getMessage());
        }
    }

    private void reactivateDeal() {
        try {
            manager.reactivateCredit(entry.getId());
            toastSuccess("Deal reaktiviert.");
        } catch (CreditManager.CreditException exception) {
            toastError(exception.getMessage());
        }
    }

    private void deletePayment(Payment payment) {
        if (!payment.getId().equals(paymentDeleteArmed)) {
            paymentDeleteArmed = payment.getId();
            toastWarning("Erneut rechtsklicken, um die Zahlung zu löschen.");
            return;
        }
        try {
            manager.deletePayment(payment.getId());
            paymentDeleteArmed = null;
            toastSuccess("Zahlung gelöscht.");
        } catch (CreditManager.CreditException exception) {
            paymentDeleteArmed = null;
            toastError(exception.getMessage());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, paymentListY, contentWidth, paymentListHeight) && verticalAmount != 0) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void refreshEntry() {
        if (entry != null && entry.getId() != null) {
            manager.findCredit(entry.getId().toString()).ifPresent(fresh -> entry = fresh);
        }
    }

    private int statusColor(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> ModernUi.theme().success;
            case CreditManager.STATUS_PARTIAL -> ModernUi.theme().warning;
            case CreditManager.STATUS_CANCELLED -> ModernUi.theme().muted;
            default -> ModernUi.theme().danger;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise bezahlt";
            case CreditManager.STATUS_CANCELLED -> "Abgeschlossen";
            default -> "Offen";
        };
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }

    @Override
    protected void clearTransientState() {
        pendingDealAction = null;
        paymentDeleteArmed = null;
        scroll.reset();
        super.clearTransientState();
    }
}

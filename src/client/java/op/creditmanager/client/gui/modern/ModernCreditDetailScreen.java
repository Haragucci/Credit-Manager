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

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Deal details, payment history and destructive actions in the modern UI. */
public class ModernCreditDetailScreen extends ModernBaseScreen {

    private CreditEntry entry;
    private final boolean debts;
    private int paymentListY;
    private int paymentListHeight;
    private int scrollOffset;
    private List<Payment> renderedPayments = List.of();
    private boolean deleteArmed;
    private UUID paymentDeleteArmed;
    private String feedback;

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
                Math.max(48, contentWidth - 28), ModernUi.TEXT);
        String counterparty = debts ? entry.getCreditor() : entry.getDebtor();
        ModernUi.drawTruncated(context, textRenderer, debts ? "Gläubiger: " + safe(counterparty) : "Schuldner: " + safe(counterparty),
                contentX + 14, topY + 28, contentWidth - 28, ModernUi.MUTED);
        context.drawText(textRenderer, Text.literal("Gesamt: " + FormatUtil.formatAmount(entry.getAmount())),
                contentX + 14, topY + 44, ModernUi.MUTED, false);
        context.drawText(textRenderer, Text.literal("Bezahlt: " + FormatUtil.formatAmount(entry.getPaidAmount())),
                contentX + 14, topY + 58, ModernUi.GREEN, false);
        String remaining = "Offen: " + FormatUtil.formatAmount(entry.getRemainingAmount());
        int remainingWidth = textRenderer.getWidth(remaining);
        context.drawText(textRenderer, Text.literal(remaining), contentX + contentWidth - remainingWidth - 14, topY + 44,
                debts ? ModernUi.RED : ModernUi.GREEN, false);
        String status = statusLabel(entry.getStatus());
        int statusWidth = textRenderer.getWidth(status);
        context.drawText(textRenderer, Text.literal(status), contentX + contentWidth - statusWidth - 14, topY + 58,
                statusColor(entry.getStatus()), false);
        if (entry.getDueDate() != null) {
            ModernUi.drawTruncated(context, textRenderer, "Fällig: " + TimeUtil.formatDate(entry.getDueDate()),
                    contentX + 14, topY + 73, contentWidth - 28,
                    TimeUtil.isOverdue(entry.getDueDate()) ? ModernUi.RED : ModernUi.MUTED);
        }

        int actionY = topY + 98;
        int actionWidth = Math.max(72, Math.min(148, (contentWidth - 8) / 2));
        boolean finished = CreditManager.STATUS_PAID.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus());
        ModernUi.button(context, textRenderer, contentX, actionY, actionWidth, 23,
                finished ? "Deal abgeschlossen" : "Zahlung eintragen", finished ? ModernUi.BUTTON_NEUTRAL : ModernUi.BUTTON_PRIMARY,
                ModernUi.contains(mouseX, mouseY, contentX, actionY, actionWidth, 23));
        ModernUi.button(context, textRenderer, contentX + actionWidth + 8, actionY, actionWidth, 23,
                deleteArmed ? "Löschen bestätigen" : "Deal löschen", ModernUi.BUTTON_DANGER,
                ModernUi.contains(mouseX, mouseY, contentX + actionWidth + 8, actionY, actionWidth, 23));

        paymentListY = actionY + 34;
        paymentListHeight = Math.max(44, contentHeight - (paymentListY - contentY) - 4);
        List<Payment> payments = manager.getPaymentsForCredit(entry.getId()).stream()
                .sorted(Comparator.comparingLong(Payment::getTimestamp).reversed())
                .toList();
        context.drawText(textRenderer, Text.literal("Zahlungen · " + payments.size()), contentX, paymentListY - 13, ModernUi.MUTED, false);
        int rowHeight = 39;
        int visibleRows = Math.max(1, paymentListHeight / rowHeight);
        int maxOffset = Math.max(0, payments.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        int end = Math.min(payments.size(), scrollOffset + visibleRows);
        renderedPayments = payments.subList(scrollOffset, end);

        if (renderedPayments.isEmpty()) {
            ModernUi.card(context, contentX, paymentListY, contentWidth, Math.min(56, paymentListHeight), false);
            ModernUi.drawCentered(context, textRenderer, "Noch keine Zahlungen eingetragen.", contentX + contentWidth / 2,
                    paymentListY + 22, ModernUi.MUTED);
        } else {
            for (int i = 0; i < renderedPayments.size(); i++) {
                drawPayment(context, mouseX, mouseY, renderedPayments.get(i), contentX, paymentListY + i * rowHeight, contentWidth);
            }
        }
        if (feedback != null) {
            ModernUi.drawTruncated(context, textRenderer, feedback, contentX, panelY + panelHeight - 16, contentWidth,
                    ModernUi.YELLOW);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawPayment(DrawContext context, int mouseX, int mouseY, Payment payment, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 35);
        ModernUi.card(context, x, y, width, 35, hovered);
        boolean itemPayment = payment.getItems() != null && !payment.getItems().isEmpty();
        int color = itemPayment ? ModernUi.YELLOW : ModernUi.GREEN;
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String label = itemPayment ? "Item-Zahlung" : "Geld-Zahlung";
        ModernUi.drawTruncated(context, textRenderer, label, x + 19, y + 7, Math.max(40, width - 156), ModernUi.TEXT);
        ModernUi.drawTruncated(context, textRenderer, safe(payment.getFromPlayer()) + " -> " + safe(payment.getToPlayer())
                        + " · " + TimeUtil.formatDateTime(payment.getTimestamp()), x + 19, y + 20,
                Math.max(40, width - 156), ModernUi.MUTED);
        String amount = FormatUtil.formatAmount(payment.getAmount() == null ? 0 : payment.getAmount());
        int amountWidth = textRenderer.getWidth(amount);
        context.drawText(textRenderer, Text.literal(amount), x + width - amountWidth - 12, y + 7, color, false);
        String deleteHint = paymentDeleteArmed != null && paymentDeleteArmed.equals(payment.getId()) ? "Rechtsklick bestätigt" : "Rechtsklick: löschen";
        int hintWidth = textRenderer.getWidth(deleteHint);
        context.drawText(textRenderer, Text.literal(deleteHint), x + width - hintWidth - 12, y + 20,
                paymentDeleteArmed != null && paymentDeleteArmed.equals(payment.getId()) ? ModernUi.RED : ModernUi.MUTED, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        int topY = contentY + 5;
        int actionY = topY + 98;
        int actionWidth = Math.max(72, Math.min(148, (contentWidth - 8) / 2));
        if (click.button() == 0) {
            if (ModernUi.contains(click.x(), click.y(), contentX, actionY, actionWidth, 23)) {
                if (!CreditManager.STATUS_PAID.equals(entry.getStatus()) && !CreditManager.STATUS_CANCELLED.equals(entry.getStatus())) {
                    open(new ModernPaymentScreen(manager, entry, debts, this));
                }
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX + actionWidth + 8, actionY, actionWidth, 23)) {
                deleteDeal();
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX, paymentListY, contentWidth, paymentListHeight)) {
                int index = (int) ((click.y() - paymentListY) / 39);
                if (index >= 0 && index < renderedPayments.size()) {
                    Payment payment = renderedPayments.get(index);
                    if (payment.getItems() != null && !payment.getItems().isEmpty()) {
                        open(new ModernItemInspectionScreen(manager, payment, this));
                    }
                    return true;
                }
            }
        }
        if (click.button() == 1 && ModernUi.contains(click.x(), click.y(), contentX, paymentListY, contentWidth, paymentListHeight)) {
            int index = (int) ((click.y() - paymentListY) / 39);
            if (index >= 0 && index < renderedPayments.size()) {
                deletePayment(renderedPayments.get(index));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void deleteDeal() {
        if (!deleteArmed) {
            deleteArmed = true;
            feedback = "Zum endgültigen Löschen erneut auf den Button klicken.";
            return;
        }
        try {
            manager.deleteCredit(entry.getId());
            closeToParent();
        } catch (CreditManager.CreditException exception) {
            deleteArmed = false;
            feedback = exception.getMessage();
        }
    }

    private void deletePayment(Payment payment) {
        if (!payment.getId().equals(paymentDeleteArmed)) {
            paymentDeleteArmed = payment.getId();
            feedback = "Rechtsklick auf dieselbe Zahlung bestätigt das Löschen.";
            return;
        }
        try {
            manager.deletePayment(payment.getId());
            paymentDeleteArmed = null;
            feedback = "Zahlung wurde gelöscht.";
        } catch (CreditManager.CreditException exception) {
            paymentDeleteArmed = null;
            feedback = exception.getMessage();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, paymentListY, contentWidth, paymentListHeight) && verticalAmount != 0) {
            int maximum = Math.max(0, manager.getPaymentsForCredit(entry.getId()).size() - Math.max(1, paymentListHeight / 39));
            scrollOffset = Math.max(0, Math.min(maximum, scrollOffset - (int) Math.signum(verticalAmount)));
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
            case CreditManager.STATUS_PAID -> ModernUi.GREEN;
            case CreditManager.STATUS_PARTIAL -> ModernUi.YELLOW;
            case CreditManager.STATUS_CANCELLED -> ModernUi.MUTED;
            default -> ModernUi.RED;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise bezahlt";
            case CreditManager.STATUS_CANCELLED -> "Storniert";
            default -> "Offen";
        };
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }
}

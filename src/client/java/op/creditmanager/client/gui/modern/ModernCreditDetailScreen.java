package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiFontMode;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ModernCreditDetailScreen extends ModernBaseScreen {
    private CreditEntry entry;
    private final boolean debts;
    private int pageOffset;
    private int paymentListY;
    private int paymentListHeight;
    private int paymentListWidth;
    private final ModernScrollArea pageScroll = new ModernScrollArea();
    private final ModernScrollArea paymentScroll = new ModernScrollArea();
    private final ModernScrollArea noteScroll = new ModernScrollArea();
    private List<Payment> renderedPayments = List.of();
    private int renderedStart;
    private DealAction pendingDealAction;
    private UUID paymentDeleteArmed;
    private long paymentDeleteArmedAt;
    private List<String> noteLines = List.of();
    private ModernCreditDetailLayout.Layout detailLayout;
    private String layoutRawNote;
    private String layoutNote;
    private int layoutContentWidth = -1;
    private int layoutContentHeight = -1;
    private int layoutLineHeight = -1;
    private GuiFontMode layoutFont;
    private boolean layoutExpanded;
    private boolean noteExpanded;
    private final MutationSubmissionGuard submissionGuard = new MutationSubmissionGuard();

    private enum DealAction { ARCHIVE, CLOSE }

    public ModernCreditDetailScreen(CreditManager manager, CreditEntry entry, boolean debts, Screen parent) {
        super(manager, parent, "Deal-Details", debts ? "debts" : "claims");
        this.entry = entry;
        this.debts = debts;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        if (paymentDeleteArmed != null && System.currentTimeMillis() - paymentDeleteArmedAt > 5_000L) paymentDeleteArmed = null;
        refreshEntry();
        renderShell(context, mouseX, mouseY);
        ensureDetailLayout(entry.getNote());
        ModernCreditDetailLayout.Layout layout = detailLayout;
        pageScroll.setBounds(contentX, contentY, contentWidth, contentHeight, layout.documentHeight());
        pageScroll.tick(mouseX, mouseY);
        pageOffset = pageScroll.offset();
        List<Payment> payments = manager.getPaymentsForCredit(entry.getId()).stream()
                .sorted(Comparator.comparingLong(Payment::getTimestamp).reversed())
                .toList();
        context.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight);
        renderSummary(context, layout.summary());
        renderNote(context, mouseX, mouseY, layout);
        renderActions(context, mouseX, mouseY, layout.actions());
        int paymentTitleY = translatedY(layout.paymentTitleY());
        ModernUi.drawGuiText(context, textRenderer, "Zahlungen · " + payments.size(), contentX,
                paymentTitleY, ModernUi.theme().muted);
        renderPayments(context, mouseX, mouseY, payments, layout.paymentList());
        context.disableScissor();
        pageScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSummary(DrawContext context, ModernLayout.Bounds relative) {
        int x = contentX + relative.x();
        int y = translatedY(relative.y());
        ModernUi.card(context, x, y, relative.width(), relative.height(), false);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 14, y + 12,
                Math.max(48, relative.width() - 28), ModernUi.theme().text);
        String counterparty = debts ? entry.getCreditor() : entry.getDebtor();
        ModernUi.drawTruncated(context, textRenderer,
                debts ? "Gläubiger: " + safe(counterparty) : "Schuldner: " + safe(counterparty),
                x + 14, y + 28, relative.width() - 28, ModernUi.theme().muted);
        ModernUi.drawGuiText(context, textRenderer, "Gesamt: " + FormatUtil.formatAmountMinor(entry.getAmountMinor()),
                x + 14, y + 44, ModernUi.theme().muted);
        ModernUi.drawGuiText(context, textRenderer, "Bezahlt: " + FormatUtil.formatAmountMinor(entry.getPaidAmountMinor()),
                x + 14, y + 58, ModernUi.theme().success);
        String remaining = "Offen: " + FormatUtil.formatAmountMinor(entry.getRemainingAmountMinor());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, remaining, x + relative.width() - 14, y + 44,
                debts ? ModernUi.theme().danger : ModernUi.theme().success);
        String status = (entry.isArchived() ? "Archiviert · " : "") + statusLabel(entry.getStatus());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, status, x + relative.width() - 14, y + 58,
                entry.isArchived() ? ModernUi.theme().muted : statusColor(entry.getStatus()));
        if (entry.getDueDate() != null) {
            ModernUi.drawTruncated(context, textRenderer, "Fällig: " + TimeUtil.formatDate(entry.getDueDate()),
                    x + 14, y + 73, relative.width() - 28,
                    TimeUtil.isOverdue(entry.getDueDate()) ? ModernUi.theme().danger : ModernUi.theme().muted);
        }
    }

    private void renderNote(DrawContext context, int mouseX, int mouseY, ModernCreditDetailLayout.Layout layout) {
        if (layout.noteCard() == null || layout.noteViewport() == null) {
            noteScroll.reset();
            return;
        }
        ModernLayout.Bounds card = layout.noteCard();
        int cardX = contentX + card.x();
        int cardY = translatedY(card.y());
        ModernUi.card(context, cardX, cardY, card.width(), card.height(), false);
        ModernUi.drawGuiText(context, textRenderer, "Notiz", cardX + 12, cardY + 9, ModernUi.theme().accent);
        ModernLayout.Bounds viewport = layout.noteViewport();
        int viewportX = contentX + viewport.x();
        int viewportY = translatedY(viewport.y());
        int noteContentHeight = layout.noteExpanded() ? layout.noteContentHeight() : viewport.height();
        noteScroll.setBounds(viewportX, viewportY, viewport.width() + 8, viewport.height(), noteContentHeight);
        noteScroll.tick(mouseX, mouseY);
        int noteOffset = noteScroll.offset();
        int firstLine = layout.noteExpanded() ? Math.max(0, noteOffset / noteLineHeight()) : 0;
        int visibleLines = Math.max(1, viewport.height() / noteLineHeight() + 2);
        int lastLine = Math.min(noteLines.size(), firstLine + visibleLines);
        int visibleTop = Math.max(contentY, viewportY);
        int visibleBottom = Math.min(contentY + contentHeight, viewportY + viewport.height());
        if (visibleTop < visibleBottom) {
            context.enableScissor(viewportX, visibleTop, viewportX + viewport.width() + 8, visibleBottom);
            for (int index = firstLine; index < lastLine; index++) {
                ModernUi.drawGuiText(context, textRenderer, noteLines.get(index), viewportX,
                        viewportY + index * noteLineHeight() - noteOffset, ModernUi.theme().text);
            }
            context.disableScissor();
        }
        if (layout.noteToggle() != null) {
            ModernLayout.Bounds toggle = layout.noteToggle();
            int toggleX = contentX + toggle.x();
            int toggleY = translatedY(toggle.y());
            ModernUi.button(context, textRenderer, toggleX, toggleY, toggle.width(), toggle.height(),
                    layout.noteExpanded() ? "Weniger anzeigen" : "Mehr anzeigen",
                    ModernUi.theme().buttonNeutral, ModernUi.contains(mouseX, mouseY, toggleX,
                            toggleY, toggle.width(), toggle.height()));
        }
        noteScroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void renderActions(DrawContext context, int mouseX, int mouseY, List<ModernLayout.Bounds> relative) {
        if (submissionGuard.isActive()) {
            for (ModernLayout.Bounds bounds : relative) {
                drawAction(context, mouseX, mouseY, bounds, "Speichert…", ModernUi.theme().buttonNeutral);
            }
            return;
        }
        boolean finished = CreditManager.STATUS_PAID.equals(entry.getStatus())
                || CreditManager.STATUS_CLOSED.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus());
        boolean canReactivate = entry.isArchived() || CreditManager.STATUS_CLOSED.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus());
        drawAction(context, mouseX, mouseY, relative.getFirst(),
                finished ? canReactivate ? "Reaktivieren" : "Abgeschlossen" : "Zahlung",
                !finished ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral);
        drawAction(context, mouseX, mouseY, relative.get(1),
                finished ? "Bearbeiten" : pendingDealAction == DealAction.CLOSE ? "Bestätigen" : "Abschließen",
                ModernUi.theme().buttonNeutral);
        drawAction(context, mouseX, mouseY, relative.get(2),
                entry.isArchived() ? "Archiviert" : pendingDealAction == DealAction.ARCHIVE ? "Bestätigen" : "Archivieren",
                ModernUi.theme().buttonNeutral);
    }

    private void drawAction(DrawContext context, int mouseX, int mouseY, ModernLayout.Bounds bounds,
                            String label, int color) {
        int x = contentX + bounds.x();
        int y = translatedY(bounds.y());
        ModernUi.button(context, textRenderer, x, y, bounds.width(), bounds.height(), label, color,
                !submissionGuard.isActive() && ModernUi.contains(mouseX, mouseY, x, y, bounds.width(), bounds.height()));
    }

    private void renderPayments(DrawContext context, int mouseX, int mouseY, List<Payment> payments,
                                ModernLayout.Bounds relative) {
        int boundsX = contentX + relative.x();
        int boundsY = translatedY(relative.y());
        paymentListY = boundsY;
        paymentListHeight = relative.height();
        paymentListWidth = relative.width();
        int visibleRows = Math.max(1, paymentListHeight / ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT);
        paymentScroll.setBounds(boundsX, boundsY, relative.width(), relative.height(),
                payments.size() * ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT);
        paymentScroll.tick(mouseX, mouseY);
        int pixelOffset = paymentScroll.offset();
        renderedStart = Math.max(0, pixelOffset / ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT);
        int end = Math.min(payments.size(), renderedStart + visibleRows + 2);
        renderedPayments = payments.subList(renderedStart, end);
        if (renderedPayments.isEmpty()) {
            int emptyBoxHeight = Math.min(56, paymentListHeight);
            ModernUi.card(context, boundsX, boundsY, relative.width(), emptyBoxHeight, false);
            ModernUi.drawCentered(context, textRenderer, "Noch keine Zahlungen eingetragen.",
                    boundsX + relative.width() / 2,
                    boundsY + emptyBoxHeight / 2 - textRenderer.fontHeight / 2,
                    ModernUi.theme().muted);
            return;
        }
        int visibleTop = Math.max(contentY, boundsY);
        int visibleBottom = Math.min(contentY + contentHeight, boundsY + relative.height());
        if (visibleTop < visibleBottom) {
            context.enableScissor(boundsX, visibleTop, boundsX + relative.width(), visibleBottom);
            for (int index = 0; index < renderedPayments.size(); index++) {
                drawPayment(context, mouseX, mouseY, renderedPayments.get(index), boundsX,
                        boundsY + (renderedStart + index) * ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT - pixelOffset,
                        relative.width() - (paymentScroll.isScrollable() ? 8 : 0));
            }
            context.disableScissor();
        }
        paymentScroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void drawPayment(DrawContext context, int mouseX, int mouseY, Payment payment, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 35);
        ModernUi.card(context, x, y, width, 35, hovered);
        boolean itemPayment = payment.getItems() != null && !payment.getItems().isEmpty();
        int color = itemPayment ? ModernUi.theme().warning : ModernUi.theme().success;
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String label = itemPayment ? "Item-Zahlung" : "Geld-Zahlung";
        ModernUi.drawTruncated(context, textRenderer, label, x + 19, y + 7, Math.max(40, width - 170), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, safe(payment.getFromPlayer()) + " → " + safe(payment.getToPlayer())
                        + " · " + TimeUtil.formatDateTime(payment.getTimestamp()), x + 19, y + 20,
                Math.max(40, width - 170), ModernUi.theme().muted);
        String amount = FormatUtil.formatAmountMinor(payment.getAmountMinor());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, amount, x + width - 70, y + 7, color);
        boolean armed = paymentDeleteArmed != null && paymentDeleteArmed.equals(payment.getId());
        ModernUi.button(context, textRenderer, x + width - 62, y + 7, 54, 20,
                submissionGuard.isActive() ? "Warten…" : armed ? "Bestätigen" : "Löschen",
                armed ? ModernUi.theme().buttonDanger : ModernUi.theme().buttonNeutral,
                !submissionGuard.isActive() && ModernUi.contains(mouseX, mouseY, x + width - 62, y + 7, 54, 20));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (detailLayout != null && isVisible(detailLayout.noteViewport())
                && noteScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (isPaymentAreaVisible() && paymentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (pageScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && detailLayout != null && clickInside(detailLayout.noteToggle(), click)) {
            noteExpanded = !noteExpanded;
            noteScroll.reset();
            return true;
        }
        if (click.button() == 0 && detailLayout != null && detailLayout.actions().size() == 3) {
            if (clickInside(detailLayout.actions().getFirst(), click)) {
                if (entry.isArchived() || CreditManager.STATUS_CLOSED.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus())) {
                    reactivateDeal();
                } else if (CreditManager.STATUS_PAID.equals(entry.getStatus())) {
                    toastInfo("Zahlungen können in der Liste per Rechtsklick gelöscht werden.");
                } else {
                    open(new ModernPaymentScreen(manager, entry, debts, this));
                }
                return true;
            }
            if (clickInside(detailLayout.actions().get(1), click)) {
                if (CreditManager.STATUS_PAID.equals(entry.getStatus()) || CreditManager.STATUS_CLOSED.equals(entry.getStatus()) || CreditManager.STATUS_CANCELLED.equals(entry.getStatus())) {
                    open(new ModernEditCreditScreen(manager, entry, debts, this));
                } else {
                    closeDeal();
                }
                return true;
            }
            if (clickInside(detailLayout.actions().get(2), click)) {
                if (!entry.isArchived()) archiveDeal();
                return true;
            }
        }
        if (click.button() == 0 && isPaymentAreaVisible()
                && ModernUi.contains(click.x(), click.y(), contentX, paymentListY, paymentListWidth, paymentListHeight)) {
            int index = (int) ((click.y() - paymentListY + paymentScroll.offset()) / ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT);
            if (index >= renderedStart && index < renderedStart + renderedPayments.size()) {
                Payment payment = renderedPayments.get(index - renderedStart);
                int rowWidth = paymentListWidth - (paymentScroll.isScrollable() ? 8 : 0);
                if (click.x() >= contentX + rowWidth - 62) {
                    deletePayment(payment);
                    return true;
                }
                if (payment.getItems() != null && !payment.getItems().isEmpty()) {
                    open(new ModernItemInspectionScreen(manager, payment, this));
                }
                return true;
            }
        }
        if (click.button() == 1 && isPaymentAreaVisible()
                && ModernUi.contains(click.x(), click.y(), contentX, paymentListY, paymentListWidth, paymentListHeight)) {
            int index = (int) ((click.y() - paymentListY + paymentScroll.offset()) / ModernCreditDetailLayout.PAYMENT_ROW_HEIGHT);
            if (index >= renderedStart && index < renderedStart + renderedPayments.size()) {
                deletePayment(renderedPayments.get(index - renderedStart));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void archiveDeal() {
        if (submissionGuard.isActive()) return;
        if (pendingDealAction != DealAction.ARCHIVE) {
            pendingDealAction = DealAction.ARCHIVE;
            toastWarning("Erneut klicken, um den Deal zu archivieren.");
            return;
        }
        UUID creditId = entry.getId();
        pendingDealAction = null;
        submitMutation(submissionGuard, () -> manager.archiveCredit(creditId),
                (result, failure, screenCurrent) -> completeDealMutation(
                        result, failure, screenCurrent, "Deal archiviert.", "Deal konnte nicht archiviert werden."));
    }

    private void closeDeal() {
        if (submissionGuard.isActive()) return;
        if (pendingDealAction != DealAction.CLOSE) {
            pendingDealAction = DealAction.CLOSE;
            toastWarning("Erneut klicken, um den Deal ohne Zahlung abzuschließen.");
            return;
        }
        UUID creditId = entry.getId();
        pendingDealAction = null;
        submitMutation(submissionGuard, () -> manager.closeCredit(creditId),
                (result, failure, screenCurrent) -> completeDealMutation(
                        result, failure, screenCurrent, "Deal abgeschlossen.", "Deal konnte nicht abgeschlossen werden."));
    }

    private void reactivateDeal() {
        if (submissionGuard.isActive()) return;
        UUID creditId = entry.getId();
        submitMutation(submissionGuard, () -> manager.reactivateCredit(creditId),
                (result, failure, screenCurrent) -> completeDealMutation(
                        result, failure, screenCurrent, "Deal reaktiviert.", "Deal konnte nicht reaktiviert werden."));
    }

    private void deletePayment(Payment payment) {
        if (submissionGuard.isActive()) return;
        if (!payment.getId().equals(paymentDeleteArmed)) {
            paymentDeleteArmed = payment.getId();
            paymentDeleteArmedAt = System.currentTimeMillis();
            toastWarning("Löschen erneut bestätigen.");
            return;
        }
        UUID paymentId = payment.getId();
        paymentDeleteArmed = null;
        paymentDeleteArmedAt = 0L;
        submitMutation(submissionGuard, () -> {
            manager.deletePayment(paymentId);
            return null;
        }, (result, failure, screenCurrent) -> {
            if (failure != null) {
                toastError(failure.getMessage() == null ? "Zahlung konnte nicht gelöscht werden." : failure.getMessage());
                return;
            }
            if (!showMutationCommitNotice(result.commitResult())) toastSuccess("Zahlung gelöscht.");
        });
    }

    private void completeDealMutation(op.creditmanager.client.core.CreditManagerMutationExecutor.MutationOutcome<CreditEntry> result,
                                      Throwable failure, boolean screenCurrent, String success, String fallback) {
        if (failure != null) {
            toastError(failure.getMessage() == null ? fallback : failure.getMessage());
            return;
        }
        if (!showMutationCommitNotice(result.commitResult())) toastSuccess(success);
        if (screenCurrent && result.value() != null) entry = result.value();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0 && detailLayout != null && isVisible(detailLayout.noteViewport()) && noteScroll.isScrollable()
                && noteScroll.contains(mouseX, mouseY)) {
            noteScroll.scroll(verticalAmount);
            return true;
        }
        if (verticalAmount != 0 && isPaymentAreaVisible() && paymentScroll.isScrollable()
                && paymentScroll.contains(mouseX, mouseY)) {
            paymentScroll.scroll(verticalAmount);
            return true;
        }
        if (verticalAmount != 0 && pageScroll.isScrollable() && pageScroll.contains(mouseX, mouseY)) {
            pageScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void ensureDetailLayout(String rawNote) {
        GuiFontMode font = ClientConfigManager.getGuiFontMode();
        int lineHeight = noteLineHeight();
        if (detailLayout != null && Objects.equals(rawNote, layoutRawNote) && contentWidth == layoutContentWidth
                && contentHeight == layoutContentHeight && lineHeight == layoutLineHeight
                && font == layoutFont && noteExpanded == layoutExpanded) return;
        String note = ModernCreditDetailLayout.normalizeNote(rawNote);
        List<String> lines = ModernCreditDetailLayout.wrapNote(note,
                ModernCreditDetailLayout.noteTextWidth(contentWidth), value -> ModernUi.getGuiTextWidth(textRenderer, value));
        if (lines.size() <= ModernCreditDetailLayout.PREVIEW_LINES) noteExpanded = false;
        int finalWidth = contentWidth;
        ModernCreditDetailLayout.Layout next = ModernCreditDetailLayout.calculate(finalWidth, contentHeight,
                !note.isEmpty(), noteExpanded, lines.size(), lineHeight);
        if (next.pageScrollable(contentHeight) && contentWidth > 8) {
            finalWidth = contentWidth - 8;
            lines = ModernCreditDetailLayout.wrapNote(note, ModernCreditDetailLayout.noteTextWidth(finalWidth),
                    value -> ModernUi.getGuiTextWidth(textRenderer, value));
            if (lines.size() <= ModernCreditDetailLayout.PREVIEW_LINES) noteExpanded = false;
            next = ModernCreditDetailLayout.calculate(finalWidth, contentHeight,
                    !note.isEmpty(), noteExpanded, lines.size(), lineHeight);
        }
        boolean contentChanged = !note.equals(layoutNote) || finalWidth != (detailLayout == null ? -1 : detailLayout.summary().width())
                || font != layoutFont;
        noteLines = lines;
        detailLayout = next;
        layoutRawNote = rawNote;
        layoutNote = note;
        layoutContentWidth = contentWidth;
        layoutContentHeight = contentHeight;
        layoutLineHeight = lineHeight;
        layoutFont = font;
        layoutExpanded = noteExpanded;
        if (contentChanged) noteScroll.reset();
    }

    private int noteLineHeight() {
        return textRenderer.fontHeight + 3;
    }

    private int translatedY(int relativeY) {
        return contentY + relativeY - pageOffset;
    }

    private boolean clickInside(ModernLayout.Bounds bounds, Click click) {
        if (!isVisible(bounds)) return false;
        int x = contentX + bounds.x();
        int y = translatedY(bounds.y());
        return ModernUi.contains(click.x(), click.y(), x, y, bounds.width(), bounds.height());
    }

    private boolean isVisible(ModernLayout.Bounds bounds) {
        if (bounds == null) return false;
        int x = contentX + bounds.x();
        int y = translatedY(bounds.y());
        return y + bounds.height() > contentY && y < contentY + contentHeight
                && x + bounds.width() > contentX && x < contentX + contentWidth;
    }

    private boolean isPaymentAreaVisible() {
        return paymentListHeight > 0 && paymentListY + paymentListHeight > contentY
                && paymentListY < contentY + contentHeight;
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
            case CreditManager.STATUS_CLOSED -> ModernUi.theme().warning;
            default -> ModernUi.theme().danger;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise bezahlt";
            case CreditManager.STATUS_CANCELLED -> "Storniert";
            case CreditManager.STATUS_CLOSED -> "Abgeschlossen";
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
        paymentDeleteArmedAt = 0L;
        noteExpanded = false;
        detailLayout = null;
        layoutRawNote = null;
        layoutNote = null;
        layoutContentWidth = -1;
        layoutContentHeight = -1;
        layoutLineHeight = -1;
        layoutFont = null;
        layoutExpanded = false;
        noteLines = List.of();
        renderedPayments = List.of();
        pageScroll.reset();
        paymentScroll.reset();
        noteScroll.reset();
        super.clearTransientState();
    }
}

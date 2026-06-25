package op.creditmanager.client.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.core.service.PaylogDetectionOutcome;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.paylog.DetectedPayment;
import op.creditmanager.client.paylog.PaymentDetectionDeduplicator;
import op.creditmanager.client.paylog.PaymentMessageParser;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;

@Environment(EnvType.CLIENT)
public class PaymentDetector {

    private final PaymentMessageParser parser = new PaymentMessageParser();
    private final PaymentDetectionDeduplicator deduplicator = new PaymentDetectionDeduplicator(500L, 100);
    private final CreditManager creditManager;

    public PaymentDetector(CreditManager creditManager) {
        this.creditManager = creditManager;
    }

    public void process(String message) {
        if (message == null || message.isBlank()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        parser.parse(message, client.player.getName().getString()).ifPresent(this::recordTransaction);
    }

    private void recordTransaction(DetectedPayment detectedPayment) {
        long now = System.currentTimeMillis();
        if (!deduplicator.firstSeen(detectedPayment, now)) {
            CreditManagerClient.LOGGER.debug("[PaymentDetector] Duplikat ignoriert.");
            return;
        }
        String fromPlayer = detectedPayment.fromPlayer();
        String toPlayer = detectedPayment.toPlayer();
        double amount = detectedPayment.amount();

        TransactionEntry entry = new TransactionEntry(fromPlayer, toPlayer, amount);
        entry.setRawText(detectedPayment.rawMessage());
        entry.setSource("DETECTED");
        if (!TransactionRepository.getInstance().add(entry)) {
            CreditManagerClient.LOGGER.error("[PaymentDetector] Paylog konnte nicht sicher gespeichert werden.");
            ModernToastManager.getInstance().showError("Erkannter Paylog konnte nicht gespeichert werden.");
            return;
        }

        if (ClientConfigManager.isShowAnyPaylogFlyIns()) {
            ModernToastManager.getInstance().showInfo("Paylog erkannt: " + fromPlayer + " → " + toPlayer
                    + " (" + FormatUtil.formatAmount(amount) + ")");
        }

        handleDealDetection(entry);

        CreditManagerClient.LOGGER.info("[PaymentDetector] Transaktion geloggt: "
                + fromPlayer + " → " + toPlayer + " | " + FormatUtil.formatAmount(amount));
    }

    private void handleDealDetection(TransactionEntry entry) {
        if (creditManager == null) return;
        boolean showDealDetection = ClientConfigManager.isShowDealDetectionPaylogFlyIns();
        List<CreditEntry> matchingDeals = showDealDetection ? creditManager.getLinkableCreditsForPaylog(entry) : List.of();
        PaylogAutoLinkMode mode = ClientConfigManager.getPaylogAutoLinkMode();
        CreditManager.PaylogLinkResult linkResult = null;
        if (mode != PaylogAutoLinkMode.OFF) {
            try {
                linkResult = creditManager.autoLinkDetectedPaylog(entry.getId(), mode,
                        ClientConfigManager.isCompleteDealOnPaylogOverpay());
            } catch (CreditManager.CreditException exception) {
                ModernToastManager.getInstance().showWarning("Paylog erkannt, automatische Buchung nicht möglich: " + exception.getMessage());
                return;
            } catch (RuntimeException exception) {
                CreditManagerClient.LOGGER.warn("Paylog was saved, but optional auto-linking failed", exception);
                ModernToastManager.getInstance().showWarning("Paylog erkannt, automatische Verknüpfung nicht möglich. Der Paylog bleibt gespeichert.");
                return;
            }
        }
        if (!showDealDetection) return;

        boolean automaticallyLinked = linkResult != null && linkResult.linked();
        boolean overpayClosedDeal = automaticallyLinked && linkResult.remainingPaylogAmount() > 0.0001D;
        PaylogDetectionOutcome outcome = new PaylogDetectionOutcome(entry, linkResult, !matchingDeals.isEmpty(),
                automaticallyLinked, overpayClosedDeal, matchingDeals.size() > 1, null);
        String message = detectionMessage(outcome, matchingDeals);
        if (message == null) return;
        outcome = new PaylogDetectionOutcome(outcome.paylog(), outcome.linkResult(), outcome.matchingDealFound(),
                outcome.automaticallyLinked(), outcome.overpayClosedDeal(), outcome.multipleMatchingDeals(), message);
        if (outcome.automaticallyLinked()) {
            ModernToastManager.getInstance().showSuccess(outcome.userMessage());
        } else if (outcome.multipleMatchingDeals()) {
            ModernToastManager.getInstance().showWarning(outcome.userMessage());
        } else {
            ModernToastManager.getInstance().showInfo(outcome.userMessage());
        }
    }

    private String detectionMessage(PaylogDetectionOutcome outcome, List<CreditEntry> matchingDeals) {
        if (outcome.automaticallyLinked()) {
            String linked = "Paylog automatisch mit " + outcome.linkResult().credit().getDealName()
                    + " verknüpft: " + FormatUtil.formatAmount(outcome.linkResult().payment().getAmount());
            if (outcome.overpayClosedDeal()) {
                return linked + ". Deal abgeschlossen; " + FormatUtil.formatAmount(outcome.linkResult().remainingPaylogAmount())
                        + " bleibt im Paylog.";
            }
            return linked;
        }
        if (!outcome.matchingDealFound()) {
            return ClientConfigManager.isNotifyWhenPaylogHasNoMatchingDeal()
                    ? "Kein passender offener Deal gefunden."
                    : null;
        }
        if (outcome.multipleMatchingDeals() && ClientConfigManager.isNotifyWhenPaylogHasMultipleMatchingDeals()) {
            return "Mehrere passende Deals gefunden. Bitte manuell verknüpfen.";
        }
        if (!ClientConfigManager.isNotifyWhenPaylogHasMatchingDeal()) return null;
        CreditEntry match = matchingDeals.getFirst();
        if (outcome.linkResult() != null && outcome.linkResult().status() == CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS) {
            return "Paylog passt zu " + match.getDealName() + ", wurde aber wegen der Betragsregel nicht automatisch gebucht.";
        }
        return "Passender Deal gefunden: " + match.getDealName() + " · " + FormatUtil.formatAmount(match.getRemainingAmount()) + " offen";
    }
}

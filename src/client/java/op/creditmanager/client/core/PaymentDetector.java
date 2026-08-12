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
import op.creditmanager.client.paylog.PaymentDetectionEvent;
import op.creditmanager.client.paylog.PaymentMessageParser;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Environment(EnvType.CLIENT)
public class PaymentDetector {
    private final PaymentMessageParser parser;
    private final PaymentDetectionDeduplicator deduplicator;
    private final CreditManager creditManager;
    private final BooleanSupplier writable;
    private final PaylogSink paylogSink;
    private volatile String connectionId = "disconnected";
    private volatile String serverId = "unknown";

    public PaymentDetector(CreditManager creditManager) {
        this(creditManager, new PaymentMessageParser(), new PaymentDetectionDeduplicator(500L, 100),
                creditManager == null ? () -> false : creditManager::isWritable,
                entry -> TransactionRepository.getInstance().add(entry));
    }

    PaymentDetector(CreditManager creditManager, PaymentMessageParser parser,
                    PaymentDetectionDeduplicator deduplicator, PaylogSink paylogSink) {
        this(creditManager, parser, deduplicator,
                creditManager == null ? () -> false : creditManager::isWritable, paylogSink);
    }

    PaymentDetector(CreditManager creditManager, PaymentMessageParser parser,
                    PaymentDetectionDeduplicator deduplicator, BooleanSupplier writable, PaylogSink paylogSink) {
        this.creditManager = creditManager;
        this.parser = parser;
        this.deduplicator = deduplicator;
        this.writable = writable;
        this.paylogSink = paylogSink;
    }

    public void process(String message) {
        process(message, "UNKNOWN", System.currentTimeMillis(), null);
    }

    public void process(String message, String channel, long receptionTimestamp, String stableEventId) {
        if (message == null || message.isBlank() || creditManager == null || !isDetectionActive()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        parser.parse(message, client.player.getName().getString()).ifPresent(payment ->
                recordTransaction(new PaymentDetectionEvent(payment, channel, connectionId, serverId,
                        receptionTimestamp, stableEventId)));
    }

    public void rotateConnectionContext(String serverIdentity) {
        connectionId = UUID.randomUUID().toString();
        serverId = serverIdentity == null || serverIdentity.isBlank() ? "unknown" : serverIdentity;
        deduplicator.rotateConnection(connectionId);
    }

    boolean isDetectionActive() {
        return writable.getAsBoolean();
    }

    private void recordTransaction(PaymentDetectionEvent event) {
        long now = System.currentTimeMillis();
        PaymentDetectionDeduplicator.Reservation reservation = deduplicator.reserve(event, now);
        if (reservation == null) {
            CreditManagerClient.LOGGER.debug("[PaymentDetector] Duplikat ignoriert.");
            return;
        }
        DetectedPayment detectedPayment = event.payment();
        TransactionEntry entry = new TransactionEntry(detectedPayment.fromPlayer(), detectedPayment.toPlayer(),
                detectedPayment.amountMinor());
        entry.setTimestamp(event.receptionTimestamp());
        entry.setRawText(detectedPayment.rawMessage());
        entry.setSource("DETECTED");
        entry.setMetadata("channel=" + event.channel() + ";connection=" + event.connectionId() + ";server=" + event.serverId());
        if (!paylogSink.persist(entry)) {
            deduplicator.rollback(reservation);
            CreditManagerClient.LOGGER.error("[PaymentDetector] Paylog konnte nicht sicher gespeichert werden.");
            ModernToastManager.getInstance().showError("Erkannter Paylog konnte nicht gespeichert werden.");
            return;
        }
        deduplicator.commit(reservation, now);

        if (ClientConfigManager.isShowAnyPaylogFlyIns()) {
            ModernToastManager.getInstance().showInfo("Paylog erkannt: " + detectedPayment.fromPlayer() + " → "
                    + detectedPayment.toPlayer() + " (" + FormatUtil.formatAmountMinor(detectedPayment.amountMinor()) + ")");
        }
        handleDealDetection(entry);
        CreditManagerClient.LOGGER.info("[PaymentDetector] Transaktion geloggt: {} → {} | {}",
                detectedPayment.fromPlayer(), detectedPayment.toPlayer(), FormatUtil.formatAmountMinor(detectedPayment.amountMinor()));
    }

    private void handleDealDetection(TransactionEntry entry) {
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
        boolean overpayClosedDeal = automaticallyLinked && linkResult.remainingPaylogMinor() > 0L;
        boolean ambiguous = linkResult != null && linkResult.status() == CreditManager.PaylogLinkResult.Status.AMBIGUOUS;
        PaylogDetectionOutcome outcome = new PaylogDetectionOutcome(entry, linkResult, !matchingDeals.isEmpty(),
                automaticallyLinked, overpayClosedDeal, ambiguous || matchingDeals.size() > 1, null);
        String message = detectionMessage(outcome, matchingDeals);
        if (message == null) return;
        if (outcome.automaticallyLinked()) ModernToastManager.getInstance().showSuccess(message);
        else if (outcome.multipleMatchingDeals()) ModernToastManager.getInstance().showWarning(message);
        else ModernToastManager.getInstance().showInfo(message);
    }

    private String detectionMessage(PaylogDetectionOutcome outcome, List<CreditEntry> matchingDeals) {
        if (outcome.automaticallyLinked()) {
            String linked = "Paylog automatisch mit " + outcome.linkResult().credit().getDealName()
                    + " verknüpft: " + FormatUtil.formatAmountMinor(outcome.linkResult().payment().getAmountMinor());
            if (outcome.overpayClosedDeal()) {
                return linked + ". Deal abgeschlossen; " + FormatUtil.formatAmountMinor(outcome.linkResult().remainingPaylogMinor())
                        + " bleibt im Paylog.";
            }
            return linked;
        }
        if (!outcome.matchingDealFound()) {
            return ClientConfigManager.isNotifyWhenPaylogHasNoMatchingDeal() ? "Kein passender offener Deal gefunden." : null;
        }
        if (outcome.multipleMatchingDeals() && ClientConfigManager.isNotifyWhenPaylogHasMultipleMatchingDeals()) {
            return "Mehrere passende Deals gefunden. Bitte manuell verknüpfen.";
        }
        if (!ClientConfigManager.isNotifyWhenPaylogHasMatchingDeal()) return null;
        CreditEntry match = matchingDeals.getFirst();
        if (outcome.linkResult() != null
                && outcome.linkResult().status() == CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS) {
            return "Paylog passt zu " + match.getDealName() + ", wurde aber wegen der Betragsregel nicht automatisch gebucht.";
        }
        return "Passender Deal gefunden: " + match.getDealName() + " · "
                + FormatUtil.formatAmountMinor(match.getRemainingAmountMinor()) + " offen";
    }

    @FunctionalInterface
    interface PaylogSink {
        boolean persist(TransactionEntry entry);
    }
}

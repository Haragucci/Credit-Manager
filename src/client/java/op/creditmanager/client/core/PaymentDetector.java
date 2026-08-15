package op.creditmanager.client.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.PaylogAutoLinkMode;
import op.creditmanager.client.core.service.MutationCommitResult;
import op.creditmanager.client.core.service.PaylogDetectionOutcome;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.paylog.DetectedPayment;
import op.creditmanager.client.paylog.PaymentDetectionDeduplicator;
import op.creditmanager.client.paylog.PaymentDetectionEvent;
import op.creditmanager.client.paylog.PaymentMessageParser;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Environment(EnvType.CLIENT)
public class PaymentDetector implements PaymentMessageRouter.Handler {
    private final PaymentMessageParser parser;
    private final PaymentDetectionDeduplicator deduplicator;
    private final CreditManager creditManager;
    private final BooleanSupplier writable;
    private final PaylogSink paylogSink;
    private final CreditManagerMutationExecutor mutationExecutor;
    private final Consumer<Runnable> completionExecutor;
    private final Supplier<DetectionOptions> options;
    private final Consumer<ProcessingCompletion> completionSink;
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
        this(creditManager, parser, deduplicator, writable, paylogSink,
                CreditManagerMutationExecutor.getInstance(), PaymentDetector::dispatchToClient,
                DetectionOptions::capture, null);
    }

    PaymentDetector(CreditManager creditManager, PaymentMessageParser parser,
                    PaymentDetectionDeduplicator deduplicator, BooleanSupplier writable, PaylogSink paylogSink,
                    CreditManagerMutationExecutor mutationExecutor, Consumer<Runnable> completionExecutor,
                    Supplier<DetectionOptions> options, Consumer<ProcessingCompletion> completionSink) {
        this.creditManager = creditManager;
        this.parser = Objects.requireNonNull(parser, "parser");
        this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
        this.writable = Objects.requireNonNull(writable, "writable");
        this.paylogSink = Objects.requireNonNull(paylogSink, "paylogSink");
        this.mutationExecutor = Objects.requireNonNull(mutationExecutor, "mutationExecutor");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.options = Objects.requireNonNull(options, "options");
        this.completionSink = completionSink == null ? this::publishCompletion : completionSink;
    }

    public void process(String message) {
        process(message, "UNKNOWN", System.currentTimeMillis(), null);
    }

    @Override
    public void process(String message, String channel, long receptionTimestamp, String stableEventId) {
        if (message == null || message.isBlank()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        processCaptured(new IncomingMessage(message, client.player.getName().getString(), channel,
                connectionId, serverId, receptionTimestamp, stableEventId,
                client.world == null ? -1L : client.world.getTime()));
    }

    void processCaptured(IncomingMessage incoming) {
        if (incoming == null || incoming.message().isBlank() || incoming.playerName().isBlank()) return;
        DetectedPayment payment = parser.parse(incoming.message(), incoming.playerName()).orElse(null);
        if (payment == null || !isDetectionActive()) return;
        PaymentDetectionEvent event = new PaymentDetectionEvent(payment, incoming.channel(), incoming.connectionId(),
                incoming.serverId(), incoming.receptionTimestamp(), incoming.stableEventId(), incoming.clientTick());
        DetectionOptions capturedOptions = options.get();
        mutationExecutor.submit(() -> processPayment(event, capturedOptions)).whenComplete((result, failure) -> {
            ProcessingCompletion completion = new ProcessingCompletion(result, unwrap(failure));
            try {
                completionExecutor.accept(() -> {
                    try {
                        completionSink.accept(completion);
                    } catch (RuntimeException publicationFailure) {
                        CreditManagerClient.LOGGER.error(
                                "[PaymentDetector] Completion konnte nicht verarbeitet werden.", publicationFailure);
                    }
                });
            } catch (RuntimeException dispatchFailure) {
                CreditManagerClient.LOGGER.error("[PaymentDetector] Completion konnte nicht veröffentlicht werden.",
                        dispatchFailure);
            }
        });
    }

    @Override
    public void rotateConnectionContext(String serverIdentity) {
        connectionId = UUID.randomUUID().toString();
        serverId = serverIdentity == null || serverIdentity.isBlank() ? "unknown" : serverIdentity;
        deduplicator.rotateConnection(connectionId);
    }

    boolean isDetectionActive() {
        return writable.getAsBoolean();
    }

    private ProcessingResult processPayment(PaymentDetectionEvent event, DetectionOptions capturedOptions) {
        long now = System.currentTimeMillis();
        PaymentDetectionDeduplicator.Reservation reservation = deduplicator.reserve(event, now);
        if (reservation == null) return ProcessingResult.duplicateResult();
        TransactionEntry entry = transaction(event);
        try {
            if (!paylogSink.persist(entry)) {
                deduplicator.rollback(reservation);
                return ProcessingResult.persistenceFailure(entry);
            }
        } catch (RuntimeException | Error failure) {
            deduplicator.rollback(reservation);
            throw failure;
        }
        deduplicator.commit(reservation, now);

        List<Notification> notifications = new ArrayList<>();
        DetectedPayment payment = event.payment();
        if (capturedOptions.showAnyPaylogFlyIns()) {
            notifications.add(Notification.info("Paylog erkannt: " + payment.fromPlayer() + " → "
                    + payment.toPlayer() + " (" + FormatUtil.formatAmountMinor(payment.amountMinor()) + ")"));
        }
        if (creditManager != null) handleDealDetection(entry, capturedOptions, notifications);
        return ProcessingResult.saved(entry, notifications);
    }

    private TransactionEntry transaction(PaymentDetectionEvent event) {
        DetectedPayment payment = event.payment();
        TransactionEntry entry = new TransactionEntry(payment.fromPlayer(), payment.toPlayer(), payment.amountMinor());
        entry.setTimestamp(event.receptionTimestamp());
        entry.setRawText(payment.rawMessage());
        entry.setSource("DETECTED");
        entry.setMetadata("channel=" + event.channel() + ";connection=" + event.connectionId()
                + ";server=" + event.serverId());
        return entry;
    }

    private void handleDealDetection(TransactionEntry entry, DetectionOptions capturedOptions,
                                     List<Notification> notifications) {
        List<CreditEntry> matchingDeals = capturedOptions.showDealDetectionPaylogFlyIns()
                ? creditManager.getLinkableCreditsForPaylog(entry) : List.of();
        CreditManager.PaylogLinkResult linkResult = null;
        if (capturedOptions.autoLinkMode() != PaylogAutoLinkMode.OFF) {
            creditManager.consumeLastMutationCommit();
            try {
                linkResult = creditManager.autoLinkDetectedPaylog(entry.getId(), capturedOptions.autoLinkMode(),
                        capturedOptions.completeDealOnOverpay());
                addMutationStatus(creditManager.consumeLastMutationCommit(), notifications);
            } catch (CreditManager.CreditException exception) {
                creditManager.consumeLastMutationCommit();
                notifications.add(Notification.warning(
                        "Paylog erkannt, automatische Buchung nicht möglich: " + exception.getMessage()));
                return;
            } catch (RuntimeException exception) {
                creditManager.consumeLastMutationCommit();
                CreditManagerClient.LOGGER.warn("Paylog was saved, but optional auto-linking failed", exception);
                notifications.add(Notification.warning(
                        "Paylog erkannt, automatische Verknüpfung nicht möglich. Der Paylog bleibt gespeichert."));
                return;
            }
        }
        if (!capturedOptions.showDealDetectionPaylogFlyIns()) return;

        boolean automaticallyLinked = linkResult != null && linkResult.linked();
        boolean overpayClosedDeal = automaticallyLinked && linkResult.remainingPaylogMinor() > 0L;
        boolean ambiguous = linkResult != null
                && linkResult.status() == CreditManager.PaylogLinkResult.Status.AMBIGUOUS;
        PaylogDetectionOutcome outcome = new PaylogDetectionOutcome(entry, linkResult, !matchingDeals.isEmpty(),
                automaticallyLinked, overpayClosedDeal, ambiguous || matchingDeals.size() > 1, null);
        String message = detectionMessage(outcome, matchingDeals, capturedOptions);
        if (message == null) return;
        if (outcome.automaticallyLinked()) notifications.add(Notification.success(message));
        else if (outcome.multipleMatchingDeals()) notifications.add(Notification.warning(message));
        else notifications.add(Notification.info(message));
    }

    private String detectionMessage(PaylogDetectionOutcome outcome, List<CreditEntry> matchingDeals,
                                    DetectionOptions capturedOptions) {
        if (outcome.automaticallyLinked()) {
            String linked = "Paylog automatisch mit " + outcome.linkResult().credit().getDealName()
                    + " verknüpft: " + FormatUtil.formatAmountMinor(outcome.linkResult().payment().getAmountMinor());
            if (outcome.overpayClosedDeal()) {
                return linked + ". Deal abgeschlossen; "
                        + FormatUtil.formatAmountMinor(outcome.linkResult().remainingPaylogMinor())
                        + " bleibt im Paylog.";
            }
            return linked;
        }
        if (!outcome.matchingDealFound()) {
            return capturedOptions.notifyNoMatchingDeal() ? "Kein passender offener Deal gefunden." : null;
        }
        if (outcome.multipleMatchingDeals() && capturedOptions.notifyMultipleMatchingDeals()) {
            return "Mehrere passende Deals gefunden. Bitte manuell verknüpfen.";
        }
        if (!capturedOptions.notifyMatchingDeal()) return null;
        CreditEntry match = matchingDeals.getFirst();
        if (outcome.linkResult() != null
                && outcome.linkResult().status() == CreditManager.PaylogLinkResult.Status.NO_SINGLE_DEAL_FITS) {
            return "Paylog passt zu " + match.getDealName()
                    + ", wurde aber wegen der Betragsregel nicht automatisch gebucht.";
        }
        return "Passender Deal gefunden: " + match.getDealName() + " · "
                + FormatUtil.formatAmountMinor(match.getRemainingAmountMinor()) + " offen";
    }

    private void addMutationStatus(MutationCommitResult result, List<Notification> notifications) {
        if (result == null || result.status() == MutationCommitResult.Status.COMMITTED_SYNCED
                || result.status() == MutationCommitResult.Status.NOT_COMMITTED) return;
        notifications.add(Notification.warning(result.userMessage()));
    }

    private void publishCompletion(ProcessingCompletion completion) {
        if (completion.failure() != null) {
            CreditManagerClient.LOGGER.error("[PaymentDetector] Erkannter Paylog konnte nicht verarbeitet werden.",
                    completion.failure());
            ModernToastManager.getInstance().showError("Erkannter Paylog konnte nicht gespeichert werden.");
            return;
        }
        ProcessingResult result = completion.result();
        if (result == null || result.duplicate()) {
            if (result != null) CreditManagerClient.LOGGER.debug("[PaymentDetector] Duplikat ignoriert.");
            return;
        }
        if (!result.persisted()) {
            CreditManagerClient.LOGGER.error("[PaymentDetector] Paylog konnte nicht sicher gespeichert werden.");
            ModernToastManager.getInstance().showError("Erkannter Paylog konnte nicht gespeichert werden.");
            return;
        }
        for (Notification notification : result.notifications()) {
            switch (notification.type()) {
                case INFO -> ModernToastManager.getInstance().showInfo(notification.message());
                case SUCCESS -> ModernToastManager.getInstance().showSuccess(notification.message());
                case WARNING -> ModernToastManager.getInstance().showWarning(notification.message());
            }
        }
        TransactionEntry entry = result.entry();
        CreditManagerClient.LOGGER.info("[PaymentDetector] Transaktion geloggt: {} → {} | {}",
                entry.getFromPlayer(), entry.getToPlayer(), FormatUtil.formatAmountMinor(entry.getAmountMinor()));
    }

    private static void dispatchToClient(Runnable completion) {
        MinecraftClient.getInstance().execute(completion);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    @FunctionalInterface
    interface PaylogSink {
        boolean persist(TransactionEntry entry);
    }

    record IncomingMessage(String message, String playerName, String channel, String connectionId, String serverId,
                           long receptionTimestamp, String stableEventId, long clientTick) {
        IncomingMessage {
            message = message == null ? "" : message;
            playerName = playerName == null ? "" : playerName;
            channel = channel == null || channel.isBlank() ? "UNKNOWN" : channel;
            connectionId = connectionId == null || connectionId.isBlank() ? "disconnected" : connectionId;
            serverId = serverId == null || serverId.isBlank() ? "unknown" : serverId;
            receptionTimestamp = receptionTimestamp > 0L ? receptionTimestamp : System.currentTimeMillis();
        }
    }

    record DetectionOptions(boolean showAnyPaylogFlyIns, boolean showDealDetectionPaylogFlyIns,
                            PaylogAutoLinkMode autoLinkMode, boolean completeDealOnOverpay,
                            boolean notifyMatchingDeal, boolean notifyNoMatchingDeal,
                            boolean notifyMultipleMatchingDeals) {
        DetectionOptions {
            autoLinkMode = autoLinkMode == null ? PaylogAutoLinkMode.OFF : autoLinkMode;
        }

        static DetectionOptions capture() {
            return new DetectionOptions(ClientConfigManager.isShowAnyPaylogFlyIns(),
                    ClientConfigManager.isShowDealDetectionPaylogFlyIns(),
                    ClientConfigManager.getPaylogAutoLinkMode(),
                    ClientConfigManager.isCompleteDealOnPaylogOverpay(),
                    ClientConfigManager.isNotifyWhenPaylogHasMatchingDeal(),
                    ClientConfigManager.isNotifyWhenPaylogHasNoMatchingDeal(),
                    ClientConfigManager.isNotifyWhenPaylogHasMultipleMatchingDeals());
        }

        static DetectionOptions quiet() {
            return new DetectionOptions(false, false, PaylogAutoLinkMode.OFF,
                    false, false, false, false);
        }
    }

    record ProcessingCompletion(ProcessingResult result, Throwable failure) { }

    record ProcessingResult(TransactionEntry entry, boolean persisted, boolean duplicate,
                            List<Notification> notifications) {
        ProcessingResult {
            notifications = notifications == null ? List.of() : List.copyOf(notifications);
        }

        static ProcessingResult duplicateResult() {
            return new ProcessingResult(null, false, true, List.of());
        }

        static ProcessingResult persistenceFailure(TransactionEntry entry) {
            return new ProcessingResult(entry, false, false, List.of());
        }

        static ProcessingResult saved(TransactionEntry entry, List<Notification> notifications) {
            return new ProcessingResult(entry, true, false, notifications);
        }
    }

    record Notification(NotificationType type, String message) {
        Notification {
            type = type == null ? NotificationType.INFO : type;
            message = message == null ? "" : message;
        }

        static Notification info(String message) { return new Notification(NotificationType.INFO, message); }
        static Notification success(String message) { return new Notification(NotificationType.SUCCESS, message); }
        static Notification warning(String message) { return new Notification(NotificationType.WARNING, message); }
    }

    enum NotificationType { INFO, SUCCESS, WARNING }
}

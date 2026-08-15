package op.creditmanager.client.paylog.importer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditManagerMutationExecutor;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.modern.ModernMainScreen;
import op.creditmanager.client.gui.modern.ModernPaylogScreen;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

public final class BankPaylogImportController {
    private static final BankPaylogImportController INSTANCE = new BankPaylogImportController();
    private final BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();
    private final BankPaylogImportService importService = new BankPaylogImportService();
    private final CreditManagerMutationExecutor mutationExecutor = CreditManagerMutationExecutor.getInstance();
    private CreditManager manager;
    private boolean connected;
    private long sessionGeneration;

    private BankPaylogImportController() { }

    public static BankPaylogImportController getInstance() { return INSTANCE; }

    public void initialize(CreditManager creditManager) {
        sessionGeneration++;
        manager = creditManager;
        connected = false;
        machine.reset();
    }

    public void onJoin() {
        connected = true;
    }

    public void onDisconnect() {
        connected = false;
        sessionGeneration++;
        if (!machine.isRunning()) return;
        if (machine.state() == BankPaylogImportStateMachine.State.COMMITTING) return;
        machine.abortDisconnected();
        finishAbort(MinecraftClient.getInstance());
    }

    public void shutdown() {
        connected = false;
        sessionGeneration++;
        machine.abortForShutdown();
        machine.reset();
    }

    public boolean isRunning() {
        return machine.isRunning();
    }

    public boolean isBankCommandAvailable(MinecraftClient client) {
        if (client == null || client.getNetworkHandler() == null) return false;
        try {
            return client.getNetworkHandler().getCommandDispatcher().getRoot().getChild("bank") != null;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public boolean shouldOfferOnboardingInMenu() {
        MinecraftClient client = MinecraftClient.getInstance();
        return shouldOfferOnboarding(connectedSessionReady(client), isBankCommandAvailable(client),
                ClientConfigManager.isBankPaylogImportOnboardingPending(), ClientConfigManager.isWritable(),
                TransactionRepository.getInstance().isWritable(), machine.isRunning());
    }

    static boolean shouldOfferOnboarding(boolean sessionReady, boolean bankCommandAvailable,
                                         boolean onboardingPending, boolean configWritable,
                                         boolean repositoryWritable, boolean importRunning) {
        return sessionReady && bankCommandAvailable && onboardingPending && configWritable
                && repositoryWritable && !importRunning;
    }

    public void requestFromPaylogScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (machine.isRunning()) {
            ModernToastManager.getInstance().showWarning("Import läuft bereits.");
            return;
        }
        if (!connectedSessionReady(client) || !isBankCommandAvailable(client)) {
            ModernToastManager.getInstance().showWarning("/bank ist in dieser Sitzung nicht verfügbar.");
            return;
        }
        if (!TransactionRepository.getInstance().isWritable()) {
            ModernToastManager.getInstance().showError("Paylog-Datenbank ist nicht beschreibbar.");
            return;
        }
        if (ClientConfigManager.isBankPaylogImportOnboardingPending()
                && !ClientConfigManager.markBankPaylogImportOnboardingHandled()) {
            ModernToastManager.getInstance().showError("Import-Entscheidung konnte nicht gespeichert werden.");
            return;
        }
        if (!startSession(BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN, client)) {
            ModernToastManager.getInstance().showWarning("Bank-Import konnte nicht gestartet werden.");
            return;
        }
        client.setScreen(null);
    }

    public boolean acceptOnboarding() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!onboardingImportReady(client)) {
            ModernToastManager.getInstance().showWarning("Bank-Import ist in dieser Sitzung nicht verfügbar.");
            return false;
        }
        if (!ClientConfigManager.markBankPaylogImportOnboardingHandled()) {
            ModernToastManager.getInstance().showError("Import-Entscheidung konnte nicht gespeichert werden.");
            return false;
        }
        if (!startSession(BankPaylogImportStateMachine.Origin.ONBOARDING, client)) {
            ModernToastManager.getInstance().showError("Bank-Import konnte nicht gestartet werden.");
            client.setScreen(new ModernMainScreen(manager));
            return false;
        }
        client.setScreen(null);
        return true;
    }

    public boolean declineOnboarding() {
        if (!ClientConfigManager.markBankPaylogImportOnboardingHandled()) {
            ModernToastManager.getInstance().showError("Entscheidung konnte nicht gespeichert werden.");
            return false;
        }
        return true;
    }

    public void tick(MinecraftClient client) {
        try {
            tickSafely(client);
        } catch (RuntimeException exception) {
            CreditManagerClient.LOGGER.error("[BankImport] Client-tick boundary contained an import failure", exception);
            if (machine.isRunning()) {
                machine.fail(BankPaylogImportStateMachine.AbortReason.ACTION_FAILED,
                        "Unerwarteter Fehler im Import-Ablauf");
                finishAbort(client);
            }
        }
    }

    private void tickSafely(MinecraftClient client) {
        if (client == null || manager == null) return;
        if (!machine.isRunning()) return;
        if (machine.state() == BankPaylogImportStateMachine.State.COMMITTING) return;
        boolean ready = connectedSessionReady(client);
        BankPaylogContainerSnapshot snapshot = snapshot(client);
        int previousPages = machine.pagesScanned();
        int previousCandidates = machine.candidateCount();
        BankPaylogImportStateMachine.Action action = machine.tick(new BankPaylogImportStateMachine.TickInput(
                System.currentTimeMillis(), ready, isBankCommandAvailable(client), snapshot));
        if (machine.pagesScanned() > previousPages) {
            CreditManagerClient.LOGGER.info("[BankImport] Page {} stable, {} transfer items collected",
                    machine.pagesScanned(), machine.candidateCount() - previousCandidates);
        }
        handleAction(client, action);
        if (machine.state() == BankPaylogImportStateMachine.State.ABORTED) finishAbort(client);
    }

    private boolean startSession(BankPaylogImportStateMachine.Origin origin, MinecraftClient client) {
        if (machine.isRunning() || !connectedSessionReady(client) || client.player == null) return false;
        String self = client.player.getName().getString().trim().toLowerCase(Locale.ROOT);
        boolean started = machine.requestStart(origin, self, System.currentTimeMillis());
        if (started) {
            sessionGeneration++;
            CreditManagerClient.LOGGER.info("[BankImport] Session started (origin={})", origin);
        }
        return started;
    }

    private boolean onboardingImportReady(MinecraftClient client) {
        return shouldOfferOnboarding(connectedSessionReady(client), isBankCommandAvailable(client), true,
                ClientConfigManager.isWritable(), TransactionRepository.getInstance().isWritable(),
                machine.isRunning());
    }

    private void handleAction(MinecraftClient client, BankPaylogImportStateMachine.Action action) {
        if (action.type() == BankPaylogImportStateMachine.ActionType.NONE) return;
        long now = System.currentTimeMillis();
        if (action.type() == BankPaylogImportStateMachine.ActionType.SEND_BANK) {
            try {
                if (client.getNetworkHandler() == null) throw new IllegalStateException("NetworkHandler fehlt");
                client.getNetworkHandler().sendChatCommand("bank");
                machine.actionSucceeded(action, now);
                CreditManagerClient.LOGGER.info("[BankImport] /bank sent");
            } catch (RuntimeException exception) {
                CreditManagerClient.LOGGER.warn("[BankImport] /bank could not be sent", exception);
                machine.actionFailed(action, BankPaylogImportStateMachine.AbortReason.ACTION_FAILED,
                        "/bank konnte nicht gesendet werden");
            }
            return;
        }
        if (action.type() == BankPaylogImportStateMachine.ActionType.CLICK_PROFILE_SETTINGS
                || action.type() == BankPaylogImportStateMachine.ActionType.CLICK_NEXT) {
            clickControl(client, action, now);
            return;
        }
        if (action.type() == BankPaylogImportStateMachine.ActionType.FINALIZE) finalizeImport(client, action);
    }

    private void clickControl(MinecraftClient client, BankPaylogImportStateMachine.Action action, long now) {
        BankPaylogContainerSnapshot current = snapshot(client);
        if (current == null || !current.containerFingerprint().equals(action.expectedContainerFingerprint())) {
            machine.actionRejectedAsStale(action, now);
            return;
        }
        if (!(client.currentScreen instanceof HandledScreen<?> handled)
                || client.player == null || client.interactionManager == null) {
            machine.actionFailed(action, BankPaylogImportStateMachine.AbortReason.GUI_CLOSED,
                    "Erwartetes Server-GUI ist nicht mehr geöffnet");
            return;
        }
        ScreenHandler handler = handled.getScreenHandler();
        Slot selected = null;
        for (Slot slot : handler.slots) {
            if (slot.id == action.slotId() && !(slot.inventory instanceof PlayerInventory)) {
                if (selected != null) {
                    machine.actionFailed(action, BankPaylogImportStateMachine.AbortReason.AMBIGUOUS_CONTROL,
                            "Slot-ID ist mehrdeutig");
                    return;
                }
                selected = slot;
            }
        }
        if (selected == null) {
            machine.actionRejectedAsStale(action, now);
            return;
        }
        try {
            client.interactionManager.clickSlot(handler.syncId, selected.id, 0, SlotActionType.PICKUP, client.player);
            machine.actionSucceeded(action, now);
            String control = action.type() == BankPaylogImportStateMachine.ActionType.CLICK_PROFILE_SETTINGS
                    ? BankPaylogImportStateMachine.PROFILE_SETTINGS : BankPaylogImportStateMachine.NEXT_PAGE;
            CreditManagerClient.LOGGER.info("[BankImport] {} clicked (slot={})", control, selected.id);
        } catch (RuntimeException exception) {
            CreditManagerClient.LOGGER.warn("[BankImport] Control click failed", exception);
            machine.actionFailed(action, BankPaylogImportStateMachine.AbortReason.ACTION_FAILED,
                    "Server-GUI konnte nicht sicher angeklickt werden");
        }
    }

    private void finalizeImport(MinecraftClient client, BankPaylogImportStateMachine.Action action) {
        if (!connectedSessionReady(client) || !TransactionRepository.getInstance().isWritable()) {
            machine.fail(BankPaylogImportStateMachine.AbortReason.DATABASE_NOT_WRITABLE,
                    "Paylog-Datenbank ist vor dem Commit nicht beschreibbar");
            return;
        }
        int pagesScanned = machine.pagesScanned();
        List<BankPaylogImportCandidate> candidates = List.copyOf(action.candidates());
        if (!machine.beginCommitting()) return;
        long generation = sessionGeneration;
        CreditManagerClient.LOGGER.info("[BankImport] Finished scan: pages={}, candidates={}",
                pagesScanned, candidates.size());
        mutationExecutor.submit(() -> importService.importCandidates(pagesScanned, candidates))
                .whenComplete((result, failure) -> client.execute(
                        () -> completeFinalize(client, generation, result, unwrap(failure))));
    }

    private void completeFinalize(MinecraftClient client, long generation, BankPaylogImportResult result,
                                  Throwable failure) {
        if (machine.state() != BankPaylogImportStateMachine.State.COMMITTING) return;
        if (generation != sessionGeneration) {
            if (failure == null) {
                CreditManagerClient.LOGGER.info("[BankImport] Stale session commit completed successfully");
                machine.markSuccess();
            } else {
                CreditManagerClient.LOGGER.error("[BankImport] Stale session commit failed", failure);
                machine.fail(BankPaylogImportStateMachine.AbortReason.DATABASE_FAILURE,
                        failure.getMessage() == null ? "Datenbank-Batch ist fehlgeschlagen" : failure.getMessage());
            }
            machine.reset();
            return;
        }
        if (failure != null) {
            CreditManagerClient.LOGGER.error("[BankImport] Import commit failed", failure);
            machine.fail(BankPaylogImportStateMachine.AbortReason.DATABASE_FAILURE,
                    failure.getMessage() == null ? "Datenbank-Batch ist fehlgeschlagen" : failure.getMessage());
            finishAbort(client);
            return;
        }
        machine.markSuccess();
        finishSuccess(client, result);
    }

    private BankPaylogContainerSnapshot snapshot(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return null;
        ScreenHandler handler = handled.getScreenHandler();
        if (handler == null) return null;
        List<BankPaylogContainerSnapshot.SlotSnapshot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            String itemId = stack.isEmpty() ? "" : Registries.ITEM.getId(stack.getItem()).toString();
            String visibleName = stack.isEmpty() ? "" : stack.getName().getString();
            LoreComponent lore = stack.isEmpty() ? null : stack.get(DataComponentTypes.LORE);
            List<String> loreLines = lore == null ? List.of()
                    : lore.lines().stream().map(text -> text.getString()).toList();
            slots.add(new BankPaylogContainerSnapshot.SlotSnapshot(slot.id, itemId,
                    stack.isEmpty() ? 0 : stack.getCount(), visibleName, loreLines));
        }
        return BankPaylogContainerSnapshot.create(System.identityHashCode(handler), handler.syncId,
                handled.getTitle().getString(), slots);
    }

    private void finishSuccess(MinecraftClient client, BankPaylogImportResult result) {
        BankPaylogImportStateMachine.Origin origin = machine.origin();
        CreditManagerClient.LOGGER.info("[BankImport] Dedup: exact={}, live-match={}, merged-covered={}, insert={}",
                result.skippedExact(), result.skippedExistingLive(), result.skippedMergedCovered(), result.imported());
        CreditManagerClient.LOGGER.info("[BankImport] Commit success: inserted={}, skipped={}",
                result.imported(), result.skippedTotal());
        closeHandledScreen(client);
        machine.reset();
        if (origin == BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN && connectedSessionReady(client)) {
            client.setScreen(new ModernPaylogScreen(manager, null));
        }
        ModernToastManager.getInstance().showSuccess("Bank-Import abgeschlossen: " + result.transferItemsFound()
                + " gefunden, " + result.imported() + " importiert, " + result.skippedTotal()
                + " bereits vorhanden.");
    }

    private void finishAbort(MinecraftClient client) {
        BankPaylogImportStateMachine.AbortReason reason = machine.abortReason();
        BankPaylogImportStateMachine.Origin origin = machine.origin();
        String detail = machine.abortDetail();
        CreditManagerClient.LOGGER.warn("[BankImport] Session aborted (reason={}, detail={})", reason, detail);
        boolean disconnectedAbort = reason == BankPaylogImportStateMachine.AbortReason.DISCONNECTED;
        if (!disconnectedAbort) closeHandledScreen(client);
        machine.reset();
        if (disconnectedAbort) return;
        if (origin == BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN && connectedSessionReady(client)) {
            client.setScreen(new ModernPaylogScreen(manager, null));
        }
        ModernToastManager.getInstance().showError("Bank-Import abgebrochen: " + userMessage(reason, detail));
    }

    private void closeHandledScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) return;
        try {
            handled.close();
        } catch (RuntimeException exception) {
            CreditManagerClient.LOGGER.warn("[BankImport] Server-GUI could not be closed normally", exception);
            client.setScreen(null);
        }
    }

    private boolean connectedSessionReady(MinecraftClient client) {
        return connected && client != null && !client.isInSingleplayer()
                && client.player != null && client.getNetworkHandler() != null;
    }

    private String userMessage(BankPaylogImportStateMachine.AbortReason reason, String detail) {
        if (reason == BankPaylogImportStateMachine.AbortReason.PARSER_ERROR) return detail;
        if (reason == BankPaylogImportStateMachine.AbortReason.BANK_COMMAND_UNAVAILABLE) return "/bank ist nicht verfügbar.";
        if (reason == BankPaylogImportStateMachine.AbortReason.DATABASE_NOT_WRITABLE
                || reason == BankPaylogImportStateMachine.AbortReason.DATABASE_FAILURE) {
            return "Datenbank konnte nicht atomar aktualisiert werden.";
        }
        if (detail != null && !detail.isBlank()) return detail + '.';
        return "Der Server-GUI-Ablauf war nicht eindeutig.";
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }
}

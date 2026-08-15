package op.creditmanager.client.paylog.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BankPaylogImportStateMachine {
    public static final String PROFILE_SETTINGS = "Profil & Einstellungen";
    public static final String NEXT_PAGE = "Weiter";
    private static final int REQUIRED_STABLE_TICKS = 3;
    private static final int POST_CLICK_DELAY_TICKS = 2;
    private static final int MISSING_SCREEN_GRACE_TICKS = 20;
    private static final int MAX_PAGES = 1_000;
    private static final long SCREEN_TIMEOUT_MILLIS = 10_000L;
    private static final long GLOBAL_TIMEOUT_MILLIS = 30L * 60L * 1_000L;

    private final BankPaylogItemParser parser;
    private final List<BankPaylogImportCandidate> candidates = new ArrayList<>();
    private final Set<String> processedPageFingerprints = new HashSet<>();
    private State state = State.IDLE;
    private Origin origin;
    private String selfPlayer;
    private long globalStartedAt;
    private long stateStartedAt;
    private long tickIndex;
    private long stabilizationAllowedAtTick;
    private long scanOrder;
    private String stableFingerprint;
    private int stableTicks;
    private String transitionFromContainerFingerprint;
    private String previousPageFingerprint;
    private BankPaylogContainerSnapshot scrapedPageSnapshot;
    private Action pendingAction;
    private boolean finalizeIssued;
    private int pagesScanned;
    private int missingScreenTicks;
    private AbortReason abortReason;
    private String abortDetail = "";

    public BankPaylogImportStateMachine() {
        this(new BankPaylogItemParser());
    }

    BankPaylogImportStateMachine(BankPaylogItemParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public boolean requestStart(Origin requestedOrigin, String requestedSelfPlayer, long now) {
        if (state != State.IDLE || requestedOrigin == null || requestedSelfPlayer == null
                || requestedSelfPlayer.isBlank()) return false;
        origin = requestedOrigin;
        selfPlayer = requestedSelfPlayer;
        globalStartedAt = now;
        stateStartedAt = now;
        state = State.START_REQUESTED;
        return true;
    }

    public Action tick(TickInput input) {
        Objects.requireNonNull(input, "input");
        tickIndex++;
        if (state == State.IDLE || state == State.SUCCESS || state == State.ABORTED) return Action.none();
        if (state == State.COMMITTING) return Action.none();
        if (!input.connected()) return abort(AbortReason.DISCONNECTED, "Verbindung wurde getrennt");
        if (input.now() - globalStartedAt > GLOBAL_TIMEOUT_MILLIS) {
            return abort(AbortReason.GLOBAL_TIMEOUT, "Globales Import-Zeitlimit überschritten");
        }
        if (pendingAction != null) return Action.none();

        return switch (state) {
            case START_REQUESTED -> {
                enter(State.OPEN_BANK, input.now());
                yield Action.none();
            }
            case OPEN_BANK -> openBank(input);
            case WAIT_BANK_GUI -> waitForBankGui(input);
            case CLICK_PROFILE_SETTINGS -> stabilizeAndClickProfile(input);
            case WAIT_TRANSACTION_GUI -> waitForTransactionGui(input);
            case STABILIZE_PAGE -> stabilizePage(input);
            case SCRAPE_PAGE -> scrapePage(input);
            case CLICK_NEXT -> clickNext(input);
            case WAIT_NEXT_PAGE -> waitForNextPage(input);
            case FINALIZE -> finalizeAction();
            default -> Action.none();
        };
    }

    public void actionSucceeded(Action action, long now) {
        if (pendingAction == null || !pendingAction.equals(action)) return;
        pendingAction = null;
        if (action.type() == ActionType.SEND_BANK && state == State.OPEN_BANK) {
            enter(State.WAIT_BANK_GUI, now);
            return;
        }
        if (action.type() == ActionType.CLICK_PROFILE_SETTINGS && state == State.CLICK_PROFILE_SETTINGS) {
            transitionFromContainerFingerprint = action.expectedContainerFingerprint();
            missingScreenTicks = 0;
            enter(State.WAIT_TRANSACTION_GUI, now);
            return;
        }
        if (action.type() == ActionType.CLICK_NEXT && state == State.CLICK_NEXT) {
            previousPageFingerprint = scrapedPageSnapshot.contentFingerprint();
            missingScreenTicks = 0;
            enter(State.WAIT_NEXT_PAGE, now);
        }
    }

    public void actionFailed(Action action, AbortReason reason, String detail) {
        if (pendingAction == null || !pendingAction.equals(action)) return;
        pendingAction = null;
        abort(reason == null ? AbortReason.ACTION_FAILED : reason, detail);
    }

    public void actionRejectedAsStale(Action action, long now) {
        if (pendingAction == null || !pendingAction.equals(action)) return;
        pendingAction = null;
        resetStability();
        if (state == State.CLICK_PROFILE_SETTINGS) {
            stateStartedAt = now;
            return;
        }
        if (state == State.CLICK_NEXT) rollbackLastScrapedPage(now);
    }

    public void markSuccess() {
        if (state == State.COMMITTING) state = State.SUCCESS;
    }

    public boolean beginCommitting() {
        if (state != State.FINALIZE || !finalizeIssued) return false;
        state = State.COMMITTING;
        return true;
    }

    public void abortDisconnected() {
        if (isRunning() && state != State.COMMITTING) {
            abort(AbortReason.DISCONNECTED, "Verbindung wurde getrennt");
        }
    }

    public void abortForShutdown() {
        if (isRunning()) abort(AbortReason.SHUTDOWN, "Client wird beendet");
    }

    public void fail(AbortReason reason, String detail) {
        if (isRunning()) abort(reason, detail);
    }

    public void reset() {
        state = State.IDLE;
        origin = null;
        selfPlayer = null;
        globalStartedAt = 0L;
        stateStartedAt = 0L;
        tickIndex = 0L;
        stabilizationAllowedAtTick = 0L;
        scanOrder = 0L;
        resetStability();
        transitionFromContainerFingerprint = null;
        previousPageFingerprint = null;
        scrapedPageSnapshot = null;
        pendingAction = null;
        finalizeIssued = false;
        pagesScanned = 0;
        missingScreenTicks = 0;
        abortReason = null;
        abortDetail = "";
        candidates.clear();
        processedPageFingerprints.clear();
    }

    public boolean isRunning() {
        return state != State.IDLE && state != State.SUCCESS && state != State.ABORTED;
    }

    public State state() { return state; }
    public Origin origin() { return origin; }
    public int pagesScanned() { return pagesScanned; }
    public int candidateCount() { return candidates.size(); }
    public List<BankPaylogImportCandidate> candidates() { return List.copyOf(candidates); }
    public AbortReason abortReason() { return abortReason; }
    public String abortDetail() { return abortDetail; }

    private Action openBank(TickInput input) {
        if (!input.bankCommandAvailable()) {
            return abort(AbortReason.BANK_COMMAND_UNAVAILABLE, "/bank ist in dieser Sitzung nicht verfügbar");
        }
        pendingAction = Action.sendBank();
        return pendingAction;
    }

    private Action waitForBankGui(TickInput input) {
        if (input.snapshot() != null) {
            resetStability();
            enter(State.CLICK_PROFILE_SETTINGS, input.now());
            return Action.none();
        }
        return timeout(input.now(), AbortReason.BANK_GUI_TIMEOUT, "Bank-GUI wurde nicht geöffnet");
    }

    private Action stabilizeAndClickProfile(TickInput input) {
        if (input.snapshot() == null) {
            return abort(AbortReason.GUI_CLOSED, "Bank-GUI wurde geschlossen");
        }
        if (!stable(input.snapshot())) {
            return timeout(input.now(), AbortReason.BANK_GUI_TIMEOUT, "Bank-GUI wurde nicht stabil");
        }
        List<BankPaylogContainerSnapshot.SlotSnapshot> controls = controls(input.snapshot(), PROFILE_SETTINGS);
        if (controls.isEmpty()) {
            return abort(AbortReason.PROFILE_CONTROL_MISSING,
                    "Item „Profil & Einstellungen“ wurde im Bank-GUI nicht gefunden");
        }
        if (controls.size() != 1) return abort(AbortReason.AMBIGUOUS_CONTROL, "Profil & Einstellungen ist mehrdeutig");
        BankPaylogContainerSnapshot.SlotSnapshot control = controls.getFirst();
        pendingAction = Action.clickProfile(control.slotId(), input.snapshot().containerFingerprint());
        return pendingAction;
    }

    private Action waitForTransactionGui(TickInput input) {
        BankPaylogContainerSnapshot snapshot = input.snapshot();
        if (snapshot == null) {
            if (++missingScreenTicks > MISSING_SCREEN_GRACE_TICKS) {
                return abort(AbortReason.GUI_CLOSED, "Transaktions-GUI wurde geschlossen");
            }
            return timeout(input.now(), AbortReason.TRANSACTION_GUI_TIMEOUT, "Transaktions-GUI wurde nicht geöffnet");
        }
        missingScreenTicks = 0;
        if (!snapshot.containerFingerprint().equals(transitionFromContainerFingerprint)) {
            preparePageStabilization(input.now());
        }
        return timeout(input.now(), AbortReason.TRANSACTION_GUI_TIMEOUT, "Transaktions-GUI wurde nicht geöffnet");
    }

    private Action stabilizePage(TickInput input) {
        if (input.snapshot() == null) return abort(AbortReason.GUI_CLOSED, "Transaktions-GUI wurde geschlossen");
        if (tickIndex < stabilizationAllowedAtTick || !stable(input.snapshot())) {
            return timeout(input.now(), AbortReason.PAGE_STABILITY_TIMEOUT, "Transaktionsseite wurde nicht stabil");
        }
        enter(State.SCRAPE_PAGE, input.now());
        return Action.none();
    }

    private Action scrapePage(TickInput input) {
        BankPaylogContainerSnapshot snapshot = input.snapshot();
        if (snapshot == null) return abort(AbortReason.GUI_CLOSED, "Transaktions-GUI wurde geschlossen");
        if (!snapshot.containerFingerprint().equals(stableFingerprint)) {
            resetStability();
            enter(State.STABILIZE_PAGE, input.now());
            return Action.none();
        }
        if (processedPageFingerprints.contains(snapshot.contentFingerprint())) {
            return abort(AbortReason.PAGINATION_LOOP, "Eine bereits verarbeitete Seite wurde erneut erreicht");
        }
        if (pagesScanned >= MAX_PAGES) return abort(AbortReason.PAGE_LIMIT, "Maximale Seitenzahl überschritten");

        int pageNumber = pagesScanned + 1;
        for (BankPaylogContainerSnapshot.SlotSnapshot slot : snapshot.slots()) {
            BankPaylogItemParser.ParseResult parsed = parser.parse(slot.visibleName(), slot.loreLines(), selfPlayer,
                    scanOrder++, pageNumber, slot.slotId());
            if (parsed.status() == BankPaylogItemParser.Status.ERROR) {
                return abort(AbortReason.PARSER_ERROR, "Seite " + pageNumber + ", Slot " + slot.slotId()
                        + ": " + parsed.error());
            }
            if (parsed.status() == BankPaylogItemParser.Status.CANDIDATE) candidates.add(parsed.candidate());
        }
        processedPageFingerprints.add(snapshot.contentFingerprint());
        pagesScanned++;
        scrapedPageSnapshot = snapshot;

        List<BankPaylogContainerSnapshot.SlotSnapshot> nextControls = controls(snapshot, NEXT_PAGE);
        if (nextControls.size() > 1) return abort(AbortReason.AMBIGUOUS_CONTROL, "Weiter ist mehrdeutig");
        if (nextControls.isEmpty()) {
            enter(State.FINALIZE, input.now());
            return Action.none();
        }
        enter(State.CLICK_NEXT, input.now());
        return Action.none();
    }

    private Action clickNext(TickInput input) {
        BankPaylogContainerSnapshot snapshot = input.snapshot();
        if (snapshot == null) return abort(AbortReason.GUI_CLOSED, "Transaktions-GUI wurde geschlossen");
        if (scrapedPageSnapshot == null
                || !snapshot.containerFingerprint().equals(scrapedPageSnapshot.containerFingerprint())) {
            rollbackLastScrapedPage(input.now());
            return Action.none();
        }
        List<BankPaylogContainerSnapshot.SlotSnapshot> controls = controls(snapshot, NEXT_PAGE);
        if (controls.size() != 1) {
            return abort(controls.isEmpty() ? AbortReason.CONTROL_CHANGED : AbortReason.AMBIGUOUS_CONTROL,
                    controls.isEmpty() ? "Weiter ist vor dem Klick verschwunden" : "Weiter ist mehrdeutig");
        }
        pendingAction = Action.clickNext(controls.getFirst().slotId(), snapshot.containerFingerprint());
        return pendingAction;
    }

    private Action waitForNextPage(TickInput input) {
        BankPaylogContainerSnapshot snapshot = input.snapshot();
        if (snapshot == null) {
            if (++missingScreenTicks > MISSING_SCREEN_GRACE_TICKS) {
                return abort(AbortReason.GUI_CLOSED, "Transaktions-GUI wurde geschlossen");
            }
            return timeout(input.now(), AbortReason.NEXT_PAGE_TIMEOUT, "Nächste Seite wurde nicht geladen");
        }
        missingScreenTicks = 0;
        if (!snapshot.contentFingerprint().equals(previousPageFingerprint)) {
            if (processedPageFingerprints.contains(snapshot.contentFingerprint())) {
                return abort(AbortReason.PAGINATION_LOOP, "Pagination führte zu einer bereits verarbeiteten Seite");
            }
            preparePageStabilization(input.now());
        }
        return timeout(input.now(), AbortReason.NEXT_PAGE_TIMEOUT, "Nächste Seite wurde nicht geladen");
    }

    private Action finalizeAction() {
        if (finalizeIssued) return Action.none();
        finalizeIssued = true;
        return Action.finalizeImport(candidates);
    }

    private void preparePageStabilization(long now) {
        resetStability();
        stabilizationAllowedAtTick = tickIndex + POST_CLICK_DELAY_TICKS;
        enter(State.STABILIZE_PAGE, now);
    }

    private void rollbackLastScrapedPage(long now) {
        if (scrapedPageSnapshot == null) {
            abort(AbortReason.CONTROL_CHANGED, "Seitenzustand ist vor dem Klick veraltet");
            return;
        }
        String fingerprint = scrapedPageSnapshot.contentFingerprint();
        candidates.removeIf(candidate -> candidate.pageNumber() == pagesScanned);
        processedPageFingerprints.remove(fingerprint);
        pagesScanned = Math.max(0, pagesScanned - 1);
        scrapedPageSnapshot = null;
        resetStability();
        stabilizationAllowedAtTick = tickIndex;
        enter(State.STABILIZE_PAGE, now);
    }

    private boolean stable(BankPaylogContainerSnapshot snapshot) {
        String fingerprint = snapshot.containerFingerprint();
        if (fingerprint.equals(stableFingerprint)) stableTicks++;
        else {
            stableFingerprint = fingerprint;
            stableTicks = 1;
        }
        return stableTicks >= REQUIRED_STABLE_TICKS;
    }

    private List<BankPaylogContainerSnapshot.SlotSnapshot> controls(
            BankPaylogContainerSnapshot snapshot, String expectedName) {
        return snapshot.slots().stream()
                .filter(slot -> BankPaylogControlName.matches(slot.visibleName(), expectedName))
                .toList();
    }

    private Action timeout(long now, AbortReason reason, String detail) {
        return now - stateStartedAt > SCREEN_TIMEOUT_MILLIS ? abort(reason, detail) : Action.none();
    }

    private Action abort(AbortReason reason, String detail) {
        abortReason = reason == null ? AbortReason.ACTION_FAILED : reason;
        abortDetail = detail == null ? "" : detail;
        state = State.ABORTED;
        pendingAction = null;
        finalizeIssued = false;
        candidates.clear();
        processedPageFingerprints.clear();
        return Action.none();
    }

    private void enter(State next, long now) {
        state = next;
        stateStartedAt = now;
    }

    private void resetStability() {
        stableFingerprint = null;
        stableTicks = 0;
    }

    public record TickInput(long now, boolean connected, boolean bankCommandAvailable,
                            BankPaylogContainerSnapshot snapshot) { }

    public record Action(ActionType type, int slotId, String expectedContainerFingerprint,
                         List<BankPaylogImportCandidate> candidates) {
        public Action {
            type = type == null ? ActionType.NONE : type;
            expectedContainerFingerprint = expectedContainerFingerprint == null ? "" : expectedContainerFingerprint;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static Action none() { return new Action(ActionType.NONE, -1, "", List.of()); }
        public static Action sendBank() { return new Action(ActionType.SEND_BANK, -1, "", List.of()); }
        public static Action clickProfile(int slotId, String fingerprint) {
            return new Action(ActionType.CLICK_PROFILE_SETTINGS, slotId, fingerprint, List.of());
        }
        public static Action clickNext(int slotId, String fingerprint) {
            return new Action(ActionType.CLICK_NEXT, slotId, fingerprint, List.of());
        }
        public static Action finalizeImport(List<BankPaylogImportCandidate> candidates) {
            return new Action(ActionType.FINALIZE, -1, "", candidates);
        }
    }

    public enum State {
        IDLE,
        START_REQUESTED,
        OPEN_BANK,
        WAIT_BANK_GUI,
        CLICK_PROFILE_SETTINGS,
        WAIT_TRANSACTION_GUI,
        STABILIZE_PAGE,
        SCRAPE_PAGE,
        CLICK_NEXT,
        WAIT_NEXT_PAGE,
        FINALIZE,
        COMMITTING,
        SUCCESS,
        ABORTED
    }

    public enum ActionType {
        NONE,
        SEND_BANK,
        CLICK_PROFILE_SETTINGS,
        CLICK_NEXT,
        FINALIZE
    }

    public enum Origin {
        ONBOARDING,
        PAYLOG_SCREEN
    }

    public enum AbortReason {
        DISCONNECTED,
        SHUTDOWN,
        GLOBAL_TIMEOUT,
        BANK_COMMAND_UNAVAILABLE,
        BANK_GUI_TIMEOUT,
        TRANSACTION_GUI_TIMEOUT,
        PAGE_STABILITY_TIMEOUT,
        NEXT_PAGE_TIMEOUT,
        GUI_CLOSED,
        PROFILE_CONTROL_MISSING,
        AMBIGUOUS_CONTROL,
        CONTROL_CHANGED,
        PAGINATION_LOOP,
        PAGE_LIMIT,
        PARSER_ERROR,
        DATABASE_NOT_WRITABLE,
        DATABASE_FAILURE,
        ACTION_FAILED
    }
}

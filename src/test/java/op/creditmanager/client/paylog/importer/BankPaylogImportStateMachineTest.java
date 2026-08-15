package op.creditmanager.client.paylog.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPaylogImportStateMachineTest {
    @Test
    void completePaginationSendsAndClicksExactlyOnceAndScansOnlyStablePages() {
        BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();
        long[] now = {1_000L};
        BankPaylogContainerSnapshot bank = bankSnapshot();
        BankPaylogContainerSnapshot first = pageSnapshot(2, true, "13.08.2026 22:44:59", "1.000$");
        BankPaylogContainerSnapshot last = pageSnapshot(2, false, "13.08.2026 22:45:42", "1.100$");

        assertTrue(machine.requestStart(BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN,
                "05Haragucci", now[0]));
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, null).type());
        BankPaylogImportStateMachine.Action send = tick(machine, now, null);
        assertEquals(BankPaylogImportStateMachine.ActionType.SEND_BANK, send.type());
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, null).type());
        machine.actionSucceeded(send, now[0]);

        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, bank).type());
        assertEquals(0, machine.pagesScanned());
        tick(machine, now, bank);
        tick(machine, now, bank);
        BankPaylogImportStateMachine.Action profile = tick(machine, now, bank);
        assertEquals(BankPaylogImportStateMachine.ActionType.CLICK_PROFILE_SETTINGS, profile.type());
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, bank).type());
        machine.actionSucceeded(profile, now[0]);

        tick(machine, now, first);
        while (machine.state() != BankPaylogImportStateMachine.State.CLICK_NEXT) {
            assertEquals(0, machine.pagesScanned());
            tick(machine, now, first);
        }
        assertEquals(1, machine.pagesScanned());
        BankPaylogImportStateMachine.Action next = tick(machine, now, first);
        assertEquals(BankPaylogImportStateMachine.ActionType.CLICK_NEXT, next.type());
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, first).type());
        machine.actionSucceeded(next, now[0]);

        tick(machine, now, last);
        while (machine.state() != BankPaylogImportStateMachine.State.FINALIZE) tick(machine, now, last);
        BankPaylogImportStateMachine.Action finalize = tick(machine, now, last);

        assertEquals(BankPaylogImportStateMachine.ActionType.FINALIZE, finalize.type());
        assertEquals(2, machine.pagesScanned());
        assertEquals(2, finalize.candidates().size());
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, tick(machine, now, last).type());
        assertTrue(machine.beginCommitting());
        assertEquals(BankPaylogImportStateMachine.State.COMMITTING, machine.state());
        assertFalse(machine.requestStart(BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN,
                "05Haragucci", now[0]));
        assertEquals(BankPaylogImportStateMachine.ActionType.NONE,
                machine.tick(input(now[0] + 31L * 60L * 1_000L, false, null)).type());
        assertEquals(BankPaylogImportStateMachine.State.COMMITTING, machine.state());
        machine.abortDisconnected();
        assertEquals(BankPaylogImportStateMachine.State.COMMITTING, machine.state());
        machine.markSuccess();
        assertEquals(BankPaylogImportStateMachine.State.SUCCESS, machine.state());
    }

    @Test
    void finalizeCannotReportSuccessBeforeCommitWasAccepted() {
        BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();

        machine.markSuccess();

        assertEquals(BankPaylogImportStateMachine.State.IDLE, machine.state());
        assertFalse(machine.beginCommitting());
    }

    @Test
    void processedPageFingerprintCannotBeCollectedTwice() {
        BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();
        long[] now = {2_000L};
        BankPaylogContainerSnapshot first = pageSnapshot(2, true, "13.08.2026 22:44:59", "1.000$");
        BankPaylogContainerSnapshot second = pageSnapshot(2, true, "13.08.2026 22:45:42", "1.100$");
        openTransactionGui(machine, now, first);
        BankPaylogImportStateMachine.Action firstNext = scanUntilNext(machine, now, first);
        machine.actionSucceeded(firstNext, now[0]);
        tick(machine, now, second);
        BankPaylogImportStateMachine.Action secondNext = scanUntilNext(machine, now, second);
        machine.actionSucceeded(secondNext, now[0]);

        tick(machine, now, first);

        assertEquals(BankPaylogImportStateMachine.State.ABORTED, machine.state());
        assertEquals(BankPaylogImportStateMachine.AbortReason.PAGINATION_LOOP, machine.abortReason());
        assertEquals(0, machine.candidateCount());
    }

    @Test
    void timeoutAndDisconnectAbortWithoutFinalize() {
        BankPaylogImportStateMachine timeout = new BankPaylogImportStateMachine();
        assertTrue(timeout.requestStart(BankPaylogImportStateMachine.Origin.ONBOARDING,
                "05Haragucci", 1_000L));
        timeout.tick(input(1_001L, true, null));
        BankPaylogImportStateMachine.Action send = timeout.tick(input(1_002L, true, null));
        timeout.actionSucceeded(send, 1_002L);
        timeout.tick(input(12_003L, true, null));

        assertEquals(BankPaylogImportStateMachine.State.ABORTED, timeout.state());
        assertEquals(BankPaylogImportStateMachine.AbortReason.BANK_GUI_TIMEOUT, timeout.abortReason());

        BankPaylogImportStateMachine disconnected = new BankPaylogImportStateMachine();
        assertTrue(disconnected.requestStart(BankPaylogImportStateMachine.Origin.ONBOARDING,
                "05Haragucci", 1_000L));
        disconnected.tick(input(1_001L, false, null));

        assertEquals(BankPaylogImportStateMachine.State.ABORTED, disconnected.state());
        assertEquals(BankPaylogImportStateMachine.AbortReason.DISCONNECTED, disconnected.abortReason());
    }

    @Test
    void parserErrorOnLaterPageDiscardsEverythingBeforeCommit() {
        BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();
        long[] now = {3_000L};
        BankPaylogContainerSnapshot first = pageSnapshot(2, true, "13.08.2026 22:44:59", "1.000$");
        BankPaylogContainerSnapshot broken = pageSnapshot(2, false, "kaputt", "1.100$");
        openTransactionGui(machine, now, first);
        BankPaylogImportStateMachine.Action next = scanUntilNext(machine, now, first);
        machine.actionSucceeded(next, now[0]);
        tick(machine, now, broken);
        while (machine.state() != BankPaylogImportStateMachine.State.ABORTED) tick(machine, now, broken);

        assertEquals(BankPaylogImportStateMachine.AbortReason.PARSER_ERROR, machine.abortReason());
        assertEquals(0, machine.candidateCount());
        assertFalse(machine.state() == BankPaylogImportStateMachine.State.FINALIZE);
    }

    @Test
    void changedSnapshotBeforeControlClickIsRestabilizedWithoutUsingStaleSlot() {
        BankPaylogImportStateMachine machine = new BankPaylogImportStateMachine();
        long[] now = {4_000L};
        BankPaylogContainerSnapshot first = pageSnapshot(2, true, "13.08.2026 22:44:59", "1.000$");
        BankPaylogContainerSnapshot changed = pageSnapshot(2, true, "13.08.2026 22:44:59", "1.001$");
        openTransactionGui(machine, now, first);
        while (machine.state() != BankPaylogImportStateMachine.State.CLICK_NEXT) tick(machine, now, first);

        BankPaylogImportStateMachine.Action action = tick(machine, now, changed);

        assertEquals(BankPaylogImportStateMachine.ActionType.NONE, action.type());
        assertEquals(BankPaylogImportStateMachine.State.STABILIZE_PAGE, machine.state());
        assertEquals(0, machine.candidateCount());
        BankPaylogImportStateMachine.Action next = scanUntilNext(machine, now, changed);
        assertEquals(BankPaylogImportStateMachine.ActionType.CLICK_NEXT, next.type());
        assertEquals(1, machine.candidateCount());
    }

    private void openTransactionGui(BankPaylogImportStateMachine machine, long[] now,
                                    BankPaylogContainerSnapshot transaction) {
        assertTrue(machine.requestStart(BankPaylogImportStateMachine.Origin.PAYLOG_SCREEN,
                "05Haragucci", now[0]));
        tick(machine, now, null);
        BankPaylogImportStateMachine.Action send = tick(machine, now, null);
        machine.actionSucceeded(send, now[0]);
        BankPaylogContainerSnapshot bank = bankSnapshot();
        tick(machine, now, bank);
        tick(machine, now, bank);
        tick(machine, now, bank);
        BankPaylogImportStateMachine.Action profile = tick(machine, now, bank);
        machine.actionSucceeded(profile, now[0]);
        tick(machine, now, transaction);
    }

    private BankPaylogImportStateMachine.Action scanUntilNext(BankPaylogImportStateMachine machine,
                                                              long[] now,
                                                              BankPaylogContainerSnapshot page) {
        while (machine.state() != BankPaylogImportStateMachine.State.CLICK_NEXT) tick(machine, now, page);
        BankPaylogImportStateMachine.Action action = tick(machine, now, page);
        assertEquals(BankPaylogImportStateMachine.ActionType.CLICK_NEXT, action.type());
        return action;
    }

    private BankPaylogImportStateMachine.Action tick(BankPaylogImportStateMachine machine, long[] now,
                                                     BankPaylogContainerSnapshot snapshot) {
        return machine.tick(input(++now[0], true, snapshot));
    }

    private BankPaylogImportStateMachine.TickInput input(long now, boolean connected,
                                                         BankPaylogContainerSnapshot snapshot) {
        return new BankPaylogImportStateMachine.TickInput(now, connected, true, snapshot);
    }

    private BankPaylogContainerSnapshot bankSnapshot() {
        return BankPaylogContainerSnapshot.create(1, 4, "Bank", List.of(
                new BankPaylogContainerSnapshot.SlotSnapshot(7, "minecraft:player_head", 1,
                        "\u00a7c\u00a7lProfil & Einstellungen", List.of())));
    }

    private BankPaylogContainerSnapshot pageSnapshot(int identity, boolean next,
                                                      String timestamp, String amount) {
        List<BankPaylogContainerSnapshot.SlotSnapshot> slots = new java.util.ArrayList<>();
        slots.add(new BankPaylogContainerSnapshot.SlotSnapshot(3, "minecraft:paper", 1, amount,
                List.of(timestamp, "Beschreibung: Überweisung von Jerry237")));
        if (next) slots.add(new BankPaylogContainerSnapshot.SlotSnapshot(8, "minecraft:arrow", 1,
                BankPaylogImportStateMachine.NEXT_PAGE, List.of()));
        return BankPaylogContainerSnapshot.create(identity, 9, "Transaktionen", slots);
    }
}

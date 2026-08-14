package op.creditmanager.client.core;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditStatisticsSnapshotTest {
    @Test
    void snapshotKeepsOneImmutableRepositoryRevision() {
        CreditRepository repository = new CreditRepository();
        CreditEntry claim = new CreditEntry(UUID.randomUUID(), "claim", "player", "other", 10_000L, null, "");
        CreditEntry debt = new CreditEntry(UUID.randomUUID(), "debt", "other", "player", 20_000L, null, "");
        repository.putCredit(claim);
        repository.putCredit(debt);

        CreditStatisticsSnapshot snapshot = repository.snapshotOpenCredits(" PLAYER ");
        claim.setPaidAmountMinor(10_000L);
        claim.setStatus("PAID");
        repository.putCredit(new CreditEntry(UUID.randomUUID(), "later", "player", "third", 30_000L, null, ""));

        assertEquals("player", snapshot.player());
        assertEquals(1, snapshot.claims().size());
        assertEquals(10_000L, snapshot.claims().getFirst().remainingAmountMinor());
        assertEquals(1, snapshot.debts().size());
        assertEquals(20_000L, snapshot.debts().getFirst().remainingAmountMinor());
        assertNotEquals(repository.getRevision(), snapshot.revision());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.claims().add(new CreditStatisticsSnapshot.OpenCredit(UUID.randomUUID(), 1L)));
    }

    @Test
    void snapshotExcludesClosedAndArchivedCredits() {
        CreditRepository repository = new CreditRepository();
        CreditEntry closed = new CreditEntry(UUID.randomUUID(), "closed", "player", "other", 10_000L, null, "");
        closed.setStatus("CLOSED");
        CreditEntry archived = new CreditEntry(UUID.randomUUID(), "archived", "other", "player", 20_000L, null, "");
        archived.setArchived(true);
        repository.putCredit(closed);
        repository.putCredit(archived);

        CreditStatisticsSnapshot snapshot = repository.snapshotOpenCredits("player");

        assertEquals(0, snapshot.claims().size());
        assertEquals(0, snapshot.debts().size());
    }
}

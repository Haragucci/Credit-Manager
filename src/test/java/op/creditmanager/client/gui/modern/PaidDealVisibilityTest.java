package op.creditmanager.client.gui.modern;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaidDealVisibilityTest {
    @Test
    void paidAndArchivedDealsRemainReachableThroughExplicitFilters() {
        CreditEntry paid = credit("PAID", false);
        assertTrue(CreditStatusFilter.PAID.matches(paid));
        assertTrue(CreditStatusFilter.ALL.matches(paid));
        assertFalse(CreditStatusFilter.OPEN.matches(paid));
        assertFalse(CreditStatusFilter.ACTIVE.matches(paid));

        CreditEntry archived = credit("CLOSED", true);
        assertTrue(CreditStatusFilter.CLOSED.matches(archived));
        assertTrue(CreditStatusFilter.ALL.matches(archived));
        assertFalse(CreditStatusFilter.ACTIVE.matches(archived));
        assertTrue(CreditStatusFilter.CANCELLED.matches(credit("CANCELLED", false)));
    }

    private CreditEntry credit(String status, boolean archived) {
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), "deal", "alice", "bob", 100L, null, null);
        entry.setStatus(status);
        entry.setArchived(archived);
        return entry;
    }
}

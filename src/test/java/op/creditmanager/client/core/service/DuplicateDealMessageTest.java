package op.creditmanager.client.core.service;

import op.creditmanager.client.model.CreditEntry;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DuplicateDealMessageTest {
    @Test
    void duplicateMessageReflectsPersistedDealState() {
        assertEquals("Ein bereits bezahlter Deal mit diesem Namen existiert.",
                CreditApplicationService.duplicateMessage(credit("PAID", false)));
        assertEquals("Ein bereits abgeschlossener Deal mit diesem Namen existiert.",
                CreditApplicationService.duplicateMessage(credit("CLOSED", false)));
        assertEquals("Ein stornierter Deal mit diesem Namen existiert bereits.",
                CreditApplicationService.duplicateMessage(credit("CANCELLED", false)));
        assertEquals("Ein archivierter Deal mit diesem Namen existiert bereits.",
                CreditApplicationService.duplicateMessage(credit("OPEN", true)));
    }

    private CreditEntry credit(String status, boolean archived) {
        CreditEntry entry = new CreditEntry(UUID.randomUUID(), "deal", "alice", "bob", 100L, null, null);
        entry.setStatus(status);
        entry.setArchived(archived);
        return entry;
    }
}

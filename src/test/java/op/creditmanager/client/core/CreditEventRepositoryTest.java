package op.creditmanager.client.core;

import org.junit.jupiter.api.Test;
import op.creditmanager.client.model.CreditEventEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreditEventRepositoryTest {
    @Test
    void committedEventCacheRemainsBoundedAndKeepsNewestEntries() {
        List<CreditEventEntry> committed = new ArrayList<>();
        for (int index = 0; index < CreditEventRepository.CACHE_LIMIT + 100; index++) {
            CreditEventEntry event = new CreditEventEntry();
            event.setId(new UUID(7L, index + 1L));
            event.setTimestamp(index + 1L);
            committed.add(event);
        }

        CreditEventRepository repository = CreditEventRepository.getInstance();
        repository.acceptCommittedEvents(committed, 91L);
        List<CreditEventEntry> cached = repository.getRecentEvents();

        assertEquals(CreditEventRepository.CACHE_LIMIT, cached.size());
        assertEquals(CreditEventRepository.CACHE_LIMIT + 100L, cached.getFirst().getTimestamp());
        assertEquals(101L, cached.getLast().getTimestamp());
        assertEquals(91L, repository.getRevision());
    }

    @Test
    void pageQueriesRejectAmbiguousUnscopedRequests() {
        CreditEventRepository repository = CreditEventRepository.getInstance();

        assertThrows(IllegalArgumentException.class, () -> repository.queryPlayerPage(" ", 100, 0));
        assertThrows(IllegalArgumentException.class, () -> repository.queryCreditPage(null, 100, 0));
    }
}

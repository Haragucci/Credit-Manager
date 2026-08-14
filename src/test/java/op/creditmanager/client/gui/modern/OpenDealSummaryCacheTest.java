package op.creditmanager.client.gui.modern;

import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.money.MoneyRules;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class OpenDealSummaryCacheTest {

    @Test
    void reusesSnapshotUntilRepositoryRevisionChangesAndKeepsOverflowSafeTotals() {
        CreditRepository repository = new CreditRepository();
        for (int index = 0; index < 93; index++) {
            repository.putCredit(new CreditEntry(UUID.randomUUID(), "claim-" + index, "me", "debtor" + index,
                    MoneyRules.MAX_MINOR, null, null));
        }
        CreditManager manager = new CreditManager(repository);
        OpenDealSummaryCache cache = new OpenDealSummaryCache();

        OpenDealSummaryCache.OpenDealSummary first = cache.get(manager, "me");
        OpenDealSummaryCache.OpenDealSummary repeated = cache.get(manager, "ME");

        assertSame(first, repeated);
        assertEquals(BigInteger.valueOf(MoneyRules.MAX_MINOR).multiply(BigInteger.valueOf(93L)),
                first.claimTotalMinor());

        repository.putCredit(new CreditEntry(UUID.randomUUID(), "debt", "other", "me", 1_000L, null, null));
        OpenDealSummaryCache.OpenDealSummary refreshed = cache.get(manager, "me");

        assertNotSame(first, refreshed);
        assertEquals(BigInteger.valueOf(1_000L), refreshed.debtTotalMinor());
    }
}

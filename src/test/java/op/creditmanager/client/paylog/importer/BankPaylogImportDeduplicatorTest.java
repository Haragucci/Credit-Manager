package op.creditmanager.client.paylog.importer;

import op.creditmanager.client.model.TransactionEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankPaylogImportDeduplicatorTest {
    private static final long BASE_TIME = 1_723_669_826_000L;
    private final BankPaylogImportDeduplicator deduplicator = new BankPaylogImportDeduplicator();

    @Test
    void exactPreviouslyImportedPaylogIsSkipped() {
        BankPaylogImportCandidate candidate = candidate(110_000L, BASE_TIME, 1, 0);
        TransactionEntry existing = entry(110_000L, BASE_TIME, BankPaylogImportDeduplicator.SOURCE);
        existing.setRawText(candidate.deterministicRawText());

        BankPaylogImportDeduplicator.DeduplicationResult result =
                deduplicator.deduplicate(List.of(candidate), List.of(existing));

        assertEquals(0, result.candidatesToInsert().size());
        assertEquals(1, result.skippedExact());
    }

    @Test
    void samePlayerAndAmountThirtySecondsApartAreBothRetained() {
        BankPaylogImportCandidate candidate = candidate(110_000L, BASE_TIME, 1, 0);
        TransactionEntry existing = entry(110_000L, BASE_TIME + 30_000L, "DETECTED");

        BankPaylogImportDeduplicator.DeduplicationResult result =
                deduplicator.deduplicate(List.of(candidate), List.of(existing));

        assertEquals(List.of(candidate), result.candidatesToInsert());
    }

    @Test
    void oneExactLiveMatchWithinFiveSecondsIsSkipped() {
        BankPaylogImportCandidate candidate = candidate(110_000L, BASE_TIME, 1, 0);

        BankPaylogImportDeduplicator.DeduplicationResult result = deduplicator.deduplicate(
                List.of(candidate), List.of(entry(110_000L, BASE_TIME + 5_000L, "DETECTED")));

        assertEquals(0, result.candidatesToInsert().size());
        assertEquals(1, result.skippedExistingLive());
    }

    @Test
    void twoPlausibleLiveMatchesAreNotAggressivelyCollapsed() {
        BankPaylogImportCandidate candidate = candidate(110_000L, BASE_TIME, 1, 0);

        BankPaylogImportDeduplicator.DeduplicationResult result = deduplicator.deduplicate(
                List.of(candidate), List.of(
                        entry(110_000L, BASE_TIME - 2_000L, "DETECTED"),
                        entry(110_000L, BASE_TIME + 2_000L, "DETECTED")));

        assertEquals(List.of(candidate), result.candidatesToInsert());
        assertEquals(0, result.skippedExistingLive());
    }

    @Test
    void mergedAggregateCoveredByExactlyTwoLiveEntriesIsSkipped() {
        BankPaylogImportCandidate candidate = candidate(220_000L, BASE_TIME, 2, 0);

        BankPaylogImportDeduplicator.DeduplicationResult result = deduplicator.deduplicate(
                List.of(candidate), List.of(
                        entry(110_000L, BASE_TIME - 20_000L, "DETECTED"),
                        entry(110_000L, BASE_TIME + 20_000L, "DETECTED")));

        assertEquals(0, result.candidatesToInsert().size());
        assertEquals(1, result.skippedMergedCovered());
    }

    @Test
    void additionalPlausibleLiveEntryKeepsMergedAggregate() {
        BankPaylogImportCandidate candidate = candidate(220_000L, BASE_TIME, 2, 0);

        BankPaylogImportDeduplicator.DeduplicationResult result = deduplicator.deduplicate(
                List.of(candidate), List.of(
                        entry(110_000L, BASE_TIME - 20_000L, "DETECTED"),
                        entry(110_000L, BASE_TIME + 20_000L, "DETECTED"),
                        entry(50_000L, BASE_TIME + 30_000L, "DETECTED")));

        assertEquals(List.of(candidate), result.candidatesToInsert());
        assertEquals(0, result.skippedMergedCovered());
    }

    @Test
    void mergedItemWithoutExistingIndividualsRemainsOneAggregate() {
        BankPaylogImportCandidate candidate = candidate(220_000L, BASE_TIME, 2, 0);

        BankPaylogImportDeduplicator.DeduplicationResult result =
                deduplicator.deduplicate(List.of(candidate), List.of());

        assertEquals(List.of(candidate), result.candidatesToInsert());
        assertEquals(220_000L, result.candidatesToInsert().getFirst().amountMinor());
    }

    @Test
    void genuinePaymentsWithDifferentSecondsRemainSeparate() {
        BankPaylogImportCandidate first = candidate(110_000L, BASE_TIME, 1, 0);
        BankPaylogImportCandidate second = candidate(110_000L, BASE_TIME + 37_000L, 1, 1);

        BankPaylogImportDeduplicator.DeduplicationResult result =
                deduplicator.deduplicate(List.of(first, second), List.of());

        assertEquals(List.of(first, second), result.candidatesToInsert());
    }

    @Test
    void identicalSlotOccurrencesWithinOneScanRemainSeparate() {
        BankPaylogImportCandidate first = candidate(110_000L, BASE_TIME, 1, 0);
        BankPaylogImportCandidate second = candidate(110_000L, BASE_TIME, 1, 1);

        BankPaylogImportDeduplicator.DeduplicationResult result =
                deduplicator.deduplicate(List.of(first, second), List.of());

        assertEquals(2, result.candidatesToInsert().size());
    }

    private BankPaylogImportCandidate candidate(long amount, long timestamp, int merged, long scanOrder) {
        return new BankPaylogImportCandidate("gerry237", "05haragucci", amount, timestamp,
                "14.08.2026 22:50:26", "Gerry237", BankPaylogImportCandidate.Direction.INCOMING,
                merged, scanOrder, 1, (int) scanOrder + 1);
    }

    private TransactionEntry entry(long amount, long timestamp, String source) {
        TransactionEntry entry = new TransactionEntry("Gerry237", "05Haragucci", amount);
        entry.setTimestamp(timestamp);
        entry.setSource(source);
        entry.setRawText("existing");
        return entry;
    }
}

package op.creditmanager.client.paylog.importer;

import op.creditmanager.client.model.TransactionEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class BankPaylogImportDeduplicator {
    public static final String SOURCE = "OPSUCHT_BANK_IMPORT";
    private static final long LIVE_MATCH_WINDOW_MILLIS = 5_000L;
    private static final long MERGED_MATCH_WINDOW_MILLIS = 60_000L;

    public DeduplicationResult deduplicate(List<BankPaylogImportCandidate> candidates,
                                           List<TransactionEntry> existingEntries) {
        List<BankPaylogImportCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        List<TransactionEntry> existing = existingEntries == null ? List.of() : List.copyOf(existingEntries);
        boolean[] skipped = new boolean[values.size()];
        Set<Integer> consumedExact = new HashSet<>();
        int skippedExact = 0;

        for (int candidateIndex = 0; candidateIndex < values.size(); candidateIndex++) {
            BankPaylogImportCandidate candidate = values.get(candidateIndex);
            for (int existingIndex = 0; existingIndex < existing.size(); existingIndex++) {
                if (consumedExact.contains(existingIndex)
                        || !isExactImported(candidate, existing.get(existingIndex))) continue;
                consumedExact.add(existingIndex);
                skipped[candidateIndex] = true;
                skippedExact++;
                break;
            }
        }

        Map<Integer, List<Integer>> liveMatches = new HashMap<>();
        Map<Integer, Integer> liveMatchUsage = new HashMap<>();
        for (int candidateIndex = 0; candidateIndex < values.size(); candidateIndex++) {
            if (skipped[candidateIndex]) continue;
            List<Integer> matches = matchingLiveEntries(values.get(candidateIndex), existing, LIVE_MATCH_WINDOW_MILLIS, true);
            liveMatches.put(candidateIndex, matches);
            if (matches.size() == 1) liveMatchUsage.merge(matches.getFirst(), 1, Integer::sum);
        }

        Set<Integer> consumedLive = new HashSet<>();
        int skippedExistingLive = 0;
        for (Map.Entry<Integer, List<Integer>> match : liveMatches.entrySet()) {
            if (match.getValue().size() != 1) continue;
            int existingIndex = match.getValue().getFirst();
            if (liveMatchUsage.getOrDefault(existingIndex, 0) != 1) continue;
            skipped[match.getKey()] = true;
            consumedLive.add(existingIndex);
            skippedExistingLive++;
        }

        Map<Integer, List<Integer>> mergedMatches = new HashMap<>();
        Map<Integer, Integer> mergedMatchUsage = new HashMap<>();
        for (int candidateIndex = 0; candidateIndex < values.size(); candidateIndex++) {
            BankPaylogImportCandidate candidate = values.get(candidateIndex);
            if (skipped[candidateIndex] || candidate.mergedTransactionCount() <= 1) continue;
            List<Integer> matches = matchingLiveEntries(candidate, existing, MERGED_MATCH_WINDOW_MILLIS, false);
            if (matches.stream().anyMatch(consumedLive::contains)
                    || matches.size() != candidate.mergedTransactionCount()
                    || sum(existing, matches) != candidate.amountMinor()) continue;
            mergedMatches.put(candidateIndex, matches);
            for (int existingIndex : matches) mergedMatchUsage.merge(existingIndex, 1, Integer::sum);
        }

        int skippedMergedCovered = 0;
        for (Map.Entry<Integer, List<Integer>> match : mergedMatches.entrySet()) {
            if (match.getValue().stream().anyMatch(index -> mergedMatchUsage.getOrDefault(index, 0) != 1)) continue;
            skipped[match.getKey()] = true;
            consumedLive.addAll(match.getValue());
            skippedMergedCovered++;
        }

        List<BankPaylogImportCandidate> kept = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            if (!skipped[index]) kept.add(values.get(index));
        }
        return new DeduplicationResult(List.copyOf(kept), skippedExact, skippedExistingLive,
                skippedMergedCovered);
    }

    private boolean isExactImported(BankPaylogImportCandidate candidate, TransactionEntry existing) {
        return SOURCE.equalsIgnoreCase(safe(existing.getSource()))
                && sameParties(candidate, existing)
                && candidate.amountMinor() == existing.getAmountMinor()
                && candidate.timestamp() == existing.getTimestamp()
                && candidate.deterministicRawText().equals(existing.getRawText());
    }

    private List<Integer> matchingLiveEntries(BankPaylogImportCandidate candidate,
                                              List<TransactionEntry> existing,
                                              long windowMillis, boolean exactAmount) {
        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < existing.size(); index++) {
            TransactionEntry entry = existing.get(index);
            if (!"DETECTED".equalsIgnoreCase(safe(entry.getSource())) || !sameParties(candidate, entry)
                    || !within(candidate.timestamp(), entry.getTimestamp(), windowMillis)
                    || exactAmount && candidate.amountMinor() != entry.getAmountMinor()) continue;
            matches.add(index);
        }
        return List.copyOf(matches);
    }

    private boolean sameParties(BankPaylogImportCandidate candidate, TransactionEntry existing) {
        return candidate.fromPlayer().equalsIgnoreCase(safe(existing.getFromPlayer()))
                && candidate.toPlayer().equalsIgnoreCase(safe(existing.getToPlayer()));
    }

    private long sum(List<TransactionEntry> existing, List<Integer> indices) {
        try {
            long result = 0L;
            for (int index : indices) result = Math.addExact(result, existing.get(index).getAmountMinor());
            return result;
        } catch (ArithmeticException exception) {
            return Long.MIN_VALUE;
        }
    }

    private boolean within(long left, long right, long windowMillis) {
        if (left < 0L || right < 0L || windowMillis < 0L) return false;
        long minimum = left < windowMillis ? 0L : left - windowMillis;
        long maximum = left > Long.MAX_VALUE - windowMillis ? Long.MAX_VALUE : left + windowMillis;
        return right >= minimum && right <= maximum;
    }

    private String safe(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record DeduplicationResult(List<BankPaylogImportCandidate> candidatesToInsert,
                                      int skippedExact,
                                      int skippedExistingLive,
                                      int skippedMergedCovered) {
        public DeduplicationResult {
            candidatesToInsert = candidatesToInsert == null ? List.of() : List.copyOf(candidatesToInsert);
        }
    }
}

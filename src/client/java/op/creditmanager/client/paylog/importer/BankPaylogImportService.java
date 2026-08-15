package op.creditmanager.client.paylog.importer;

import com.google.gson.JsonObject;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BankPaylogImportService {
    private static final long EXISTING_QUERY_MARGIN_MILLIS = 60_000L;
    private final TransactionRepository repository;
    private final BankPaylogImportDeduplicator deduplicator;

    public BankPaylogImportService() {
        this(TransactionRepository.getInstance(), new BankPaylogImportDeduplicator());
    }

    BankPaylogImportService(TransactionRepository repository, BankPaylogImportDeduplicator deduplicator) {
        this.repository = repository;
        this.deduplicator = deduplicator;
    }

    public BankPaylogImportResult importCandidates(int pagesScanned,
                                                   List<BankPaylogImportCandidate> candidates) {
        List<BankPaylogImportCandidate> values = candidates == null ? List.of() : List.copyOf(candidates);
        if (pagesScanned < 1) throw new ImportException("Es wurde keine vollständige Bankseite verarbeitet.");
        if (!repository.isWritable()) throw new ImportException("Paylog-Datenbank ist nicht beschreibbar.");
        for (BankPaylogImportCandidate candidate : values) {
            if (candidate == null) throw new ImportException("Ungültiger Import-Kandidat.");
        }

        List<TransactionEntry> existing = values.isEmpty() ? List.of()
                : repository.findPaylogCandidates(minTimestamp(values), maxTimestamp(values));
        BankPaylogImportDeduplicator.DeduplicationResult deduplication =
                deduplicator.deduplicate(values, existing);
        Map<BankPaylogImportCandidate, Integer> occurrences = occurrenceIndices(values);
        List<PendingEntry> pending = new ArrayList<>();
        for (BankPaylogImportCandidate candidate : deduplication.candidatesToInsert()) {
            int occurrence = occurrences.getOrDefault(candidate, 0);
            pending.add(new PendingEntry(toEntry(candidate, occurrence), candidate.scanOrder(),
                    candidate.mergedTransactionCount() > 1));
        }
        pending.sort(Comparator.comparingLong((PendingEntry value) -> value.entry().getTimestamp())
                .thenComparing(value -> value.entry().getFromPlayer())
                .thenComparing(value -> value.entry().getToPlayer())
                .thenComparingLong(value -> value.entry().getAmountMinor())
                .thenComparingLong(PendingEntry::scanOrder));
        List<TransactionEntry> entries = pending.stream().map(PendingEntry::entry).toList();

        DatabaseManager.BatchInsertResult batch = entries.isEmpty()
                ? new DatabaseManager.BatchInsertResult(0, 0, 0, 0, List.of())
                : repository.addBatchDetailed(entries);
        if (batch.failed() != 0 || batch.inserted() + batch.skipped() != entries.size()
                || !batch.warnings().isEmpty()) {
            throw new ImportException("Paylog-Batch wurde vollständig zurückgerollt.");
        }
        int mergedImported = batch.skipped() == 0
                ? (int) pending.stream().filter(PendingEntry::merged).count() : 0;
        return new BankPaylogImportResult(pagesScanned, values.size(), batch.inserted(),
                deduplication.skippedExact() + batch.skipped(), deduplication.skippedExistingLive(),
                deduplication.skippedMergedCovered(), mergedImported, 0, batch.warnings());
    }

    private TransactionEntry toEntry(BankPaylogImportCandidate candidate, int occurrence) {
        TransactionEntry entry = new TransactionEntry(candidate.fromPlayer(), candidate.toPlayer(), candidate.amountMinor());
        entry.setId(UUID.randomUUID());
        entry.setTimestamp(candidate.timestamp());
        entry.setSource(BankPaylogImportDeduplicator.SOURCE);
        entry.setRawText(candidate.deterministicRawText());
        entry.setMetadata(metadata(candidate));
        entry.setHash(hash(candidate, occurrence));
        return entry;
    }

    private String metadata(BankPaylogImportCandidate candidate) {
        JsonObject metadata = new JsonObject();
        metadata.addProperty("importer", "opsucht_bank");
        metadata.addProperty("version", 1);
        metadata.addProperty("bankTimestamp", candidate.rawTimestamp());
        metadata.addProperty("direction", candidate.direction().name());
        metadata.addProperty("counterparty", candidate.counterparty());
        metadata.addProperty("mergedTransactionCount", candidate.mergedTransactionCount());
        return metadata.toString();
    }

    private String hash(BankPaylogImportCandidate candidate, int occurrence) {
        String canonical = BankPaylogImportDeduplicator.SOURCE + '\u0000'
                + candidate.fromPlayer() + '\u0000' + candidate.toPlayer() + '\u0000'
                + candidate.amountMinor() + '\u0000' + candidate.timestamp() + '\u0000'
                + candidate.deterministicRawText() + '\u0000' + occurrence;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", exception);
        }
    }

    private Map<BankPaylogImportCandidate, Integer> occurrenceIndices(List<BankPaylogImportCandidate> candidates) {
        List<BankPaylogImportCandidate> ordered = candidates.stream()
                .sorted(Comparator.comparingLong(BankPaylogImportCandidate::scanOrder))
                .toList();
        Map<SemanticIdentity, Integer> counts = new HashMap<>();
        Map<BankPaylogImportCandidate, Integer> occurrences = new LinkedHashMap<>();
        for (BankPaylogImportCandidate candidate : ordered) {
            SemanticIdentity identity = new SemanticIdentity(candidate.fromPlayer(), candidate.toPlayer(),
                    candidate.amountMinor(), candidate.timestamp(), candidate.deterministicRawText());
            int occurrence = counts.getOrDefault(identity, 0);
            counts.put(identity, occurrence + 1);
            occurrences.put(candidate, occurrence);
        }
        return Map.copyOf(occurrences);
    }

    private long minTimestamp(List<BankPaylogImportCandidate> candidates) {
        long minimum = candidates.stream().mapToLong(BankPaylogImportCandidate::timestamp).min().orElseThrow();
        return minimum < EXISTING_QUERY_MARGIN_MILLIS ? 0L : minimum - EXISTING_QUERY_MARGIN_MILLIS;
    }

    private long maxTimestamp(List<BankPaylogImportCandidate> candidates) {
        long maximum = candidates.stream().mapToLong(BankPaylogImportCandidate::timestamp).max().orElseThrow();
        return maximum > Long.MAX_VALUE - EXISTING_QUERY_MARGIN_MILLIS
                ? Long.MAX_VALUE : maximum + EXISTING_QUERY_MARGIN_MILLIS;
    }

    private record PendingEntry(TransactionEntry entry, long scanOrder, boolean merged) { }
    private record SemanticIdentity(String fromPlayer, String toPlayer, long amountMinor,
                                    long timestamp, String rawText) { }

    public static final class ImportException extends RuntimeException {
        public ImportException(String message) {
            super(message);
        }
    }
}

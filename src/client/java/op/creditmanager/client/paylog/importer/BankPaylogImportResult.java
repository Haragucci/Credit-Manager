package op.creditmanager.client.paylog.importer;

import java.util.List;

public record BankPaylogImportResult(
        int pagesScanned,
        int transferItemsFound,
        int imported,
        int skippedExact,
        int skippedExistingLive,
        int skippedMergedCovered,
        int mergedItemsImported,
        int invalid,
        List<String> warnings
) {
    public BankPaylogImportResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public int skippedTotal() {
        return skippedExact + skippedExistingLive + skippedMergedCovered;
    }
}

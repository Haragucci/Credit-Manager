package op.creditmanager.client.paylog.importer;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPaylogItemParserTest {
    private final BankPaylogItemParser parser = new BankPaylogItemParser();

    @Test
    void parsesIncomingTransferWithSecondsInBerlinTime() {
        BankPaylogItemParser.ParseResult result = parse("1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry237");

        assertEquals(BankPaylogItemParser.Status.CANDIDATE, result.status());
        BankPaylogImportCandidate candidate = result.candidate();
        assertNotNull(candidate);
        assertEquals("jerry237", candidate.fromPlayer());
        assertEquals("05haragucci", candidate.toPlayer());
        assertEquals(100_000L, candidate.amountMinor());
        assertEquals(LocalDateTime.of(2026, 8, 13, 22, 44, 59)
                .atZone(BankPaylogItemParser.OPSUCHT_BANK_ZONE).toInstant().toEpochMilli(), candidate.timestamp());
        assertEquals(1, candidate.mergedTransactionCount());
        assertEquals(BankPaylogImportCandidate.Direction.INCOMING, candidate.direction());
    }

    @Test
    void parsesOutgoingTransferAsPositiveMinorAmount() {
        BankPaylogImportCandidate candidate = parse("-1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung an Jerry237").candidate();

        assertEquals("05haragucci", candidate.fromPlayer());
        assertEquals("jerry237", candidate.toPlayer());
        assertEquals(100_000L, candidate.amountMinor());
        assertEquals(BankPaylogImportCandidate.Direction.OUTGOING, candidate.direction());
        assertTrue(candidate.deterministicRawText().endsWith("Überweisung an Jerry237"));
    }

    @Test
    void outgoingDescriptionDeterminesDirectionWithoutDependingOnAmountSign() {
        BankPaylogImportCandidate candidate = parse("1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung an Jerry237").candidate();

        assertEquals("05haragucci", candidate.fromPlayer());
        assertEquals("jerry237", candidate.toPlayer());
        assertEquals(BankPaylogImportCandidate.Direction.OUTGOING, candidate.direction());
    }

    @Test
    void negativeLegacyIncomingMarkerRemainsOutgoingForBackwardCompatibility() {
        BankPaylogImportCandidate candidate = parse("-1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry237").candidate();

        assertEquals("05haragucci", candidate.fromPlayer());
        assertEquals("jerry237", candidate.toPlayer());
        assertEquals(BankPaylogImportCandidate.Direction.OUTGOING, candidate.direction());
    }

    @Test
    void acceptsExplicitPlusPrefix() {
        BankPaylogImportCandidate candidate = parse("+1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry237").candidate();

        assertEquals(100_000L, candidate.amountMinor());
        assertEquals(BankPaylogImportCandidate.Direction.INCOMING, candidate.direction());
    }

    @Test
    void parsesCentAmountWithoutFloatingPoint() {
        BankPaylogImportCandidate candidate = parse("1.000,50$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry237").candidate();

        assertEquals(100_050L, candidate.amountMinor());
    }

    @Test
    void mergedServerItemCreatesOneAggregateCandidate() {
        BankPaylogItemParser.ParseResult result = parse("2.200$",
                "Zusammengeführte Transaktionen: 2",
                "14.08.2026 22:50:26",
                "Beschreibung: Überweisung von Gerry237");

        assertEquals(BankPaylogItemParser.Status.CANDIDATE, result.status());
        assertEquals(220_000L, result.candidate().amountMinor());
        assertEquals(2, result.candidate().mergedTransactionCount());
    }

    @Test
    void ignoresItemsWithoutTransferMarker() {
        BankPaylogItemParser.ParseResult result = parse("1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Einzahlung");

        assertEquals(BankPaylogItemParser.Status.IGNORED, result.status());
    }

    @Test
    void relevantItemWithBrokenTimestampIsAnError() {
        BankPaylogItemParser.ParseResult result = parse("1.000$",
                "99.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry237");

        assertEquals(BankPaylogItemParser.Status.ERROR, result.status());
    }

    @Test
    void invalidCounterpartyIsAnError() {
        BankPaylogItemParser.ParseResult result = parse("1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung von Jerry-237");

        assertEquals(BankPaylogItemParser.Status.ERROR, result.status());
    }

    @Test
    void conflictingRequiredFieldsAreAnError() {
        BankPaylogItemParser.ParseResult result = parse("1.000$",
                "13.08.2026 22:44:59",
                "14.08.2026 22:44:59",
                "Beschreibung: Überweisung von Jerry237",
                "Beschreibung: Überweisung von Gerry237");

        assertEquals(BankPaylogItemParser.Status.ERROR, result.status());
    }

    @Test
    void conflictingIncomingAndOutgoingDescriptionsAreAnError() {
        BankPaylogItemParser.ParseResult result = parse("-1.000$",
                "13.08.2026 22:44:59",
                "Beschreibung: Überweisung von Jerry237",
                "Beschreibung: Überweisung an Jerry237");

        assertEquals(BankPaylogItemParser.Status.ERROR, result.status());
    }

    @Test
    void outgoingDescriptionWithoutCounterpartyIsAnError() {
        BankPaylogItemParser.ParseResult result = parse("-1.000$",
                "13.08.2026 22:44:59", "Beschreibung: Überweisung an");

        assertEquals(BankPaylogItemParser.Status.ERROR, result.status());
    }

    private BankPaylogItemParser.ParseResult parse(String name, String... lore) {
        return parser.parse(name, List.of(lore), "05Haragucci", 0L, 1, 4);
    }
}

package op.creditmanager.client.paylog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentMessageParserTest {
    private final PaymentMessageParser parser = new PaymentMessageParser();

    @Test
    void parsesOutgoingIncomingAndFormattedMessages() {
        DetectedPayment outgoing = parser.parse("OPSUCHT » Du hast Spieler 1.234,50$ gegeben.", "Ich").orElseThrow();
        assertEquals("ich", outgoing.fromPlayer());
        assertEquals("spieler", outgoing.toPlayer());
        assertEquals(123_450L, outgoing.amountMinor());

        DetectedPayment incoming = parser.parse("§aOPSUCHT » Spieler hat dir 12.50$ gegeben.", "Ich").orElseThrow();
        assertEquals("spieler", incoming.fromPlayer());
        assertEquals("ich", incoming.toPlayer());
        assertEquals(1_250L, incoming.amountMinor());

        DetectedPayment million = parser.parse("OPSUCHT » Spieler hat dir 1.000.000$ gegeben.", "Ich").orElseThrow();
        assertEquals(100_000_000L, million.amountMinor());

        DetectedPayment tenPointOneMillion = parser.parse("OPSUCHT » Spieler hat dir 10.100.000$ gegeben.", "Ich").orElseThrow();
        assertEquals(1_010_000_000L, tenPointOneMillion.amountMinor());

        DetectedPayment formattedName = parser.parse("§aOPSUCHT » §bSpieler_42 hat dir 10.000$ gegeben.", "Ich").orElseThrow();
        assertEquals("spieler_42", formattedName.fromPlayer());
        assertEquals(1_000_000L, formattedName.amountMinor());
    }

    @Test
    void rejectsInvalidAndOversizedMessages() {
        assertTrue(parser.parse("unbekannt", "Ich").isEmpty());
        assertTrue(parser.parse("OPSUCHT » Spieler hat dir 0$ gegeben.", "Ich").isEmpty());
        assertTrue(parser.parse("OPSUCHT » Spieler hat dir 1.00.000$ gegeben.", "Ich").isEmpty());
        assertTrue(parser.parse("x".repeat(16_385), "Ich").isEmpty());
    }

    @Test
    void parsesTheExactObservedOpsuchtFixtures() {
        DetectedPayment outgoing = parser.parse("OPSUCHT \u00bb Du hast Jerry237 1.000$ gegeben.", "TillJ").orElseThrow();
        assertEquals("tillj", outgoing.fromPlayer());
        assertEquals("jerry237", outgoing.toPlayer());
        assertEquals(100_000L, outgoing.amountMinor());

        DetectedPayment incoming = parser.parse("OPSUCHT \u00bb Jerry237 hat dir 1.000$ gegeben.", "TillJ").orElseThrow();
        assertEquals("jerry237", incoming.fromPlayer());
        assertEquals("tillj", incoming.toPlayer());
        assertEquals(100_000L, incoming.amountMinor());
    }
}

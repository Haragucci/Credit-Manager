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
        assertEquals(1234.5D, outgoing.amount());

        DetectedPayment incoming = parser.parse("§aOPSUCHT » Spieler hat dir 12.50$ gegeben.", "Ich").orElseThrow();
        assertEquals("spieler", incoming.fromPlayer());
        assertEquals("ich", incoming.toPlayer());
        assertEquals(12.5D, incoming.amount());

        DetectedPayment million = parser.parse("OPSUCHT » Spieler hat dir 1.000.000$ gegeben.", "Ich").orElseThrow();
        assertEquals(1_000_000D, million.amount());

        DetectedPayment tenPointOneMillion = parser.parse("OPSUCHT » Spieler hat dir 10.100.000$ gegeben.", "Ich").orElseThrow();
        assertEquals(10_100_000D, tenPointOneMillion.amount());

        DetectedPayment formattedName = parser.parse("§aOPSUCHT » §bSpieler_42 hat dir 10.000$ gegeben.", "Ich").orElseThrow();
        assertEquals("spieler_42", formattedName.fromPlayer());
        assertEquals(10_000D, formattedName.amount());
    }

    @Test
    void rejectsInvalidAndOversizedMessages() {
        assertTrue(parser.parse("unbekannt", "Ich").isEmpty());
        assertTrue(parser.parse("OPSUCHT » Spieler hat dir 0$ gegeben.", "Ich").isEmpty());
        assertTrue(parser.parse("OPSUCHT » Spieler hat dir 1.00.000$ gegeben.", "Ich").isEmpty());
        assertTrue(parser.parse("x".repeat(16_385), "Ich").isEmpty());
    }
}

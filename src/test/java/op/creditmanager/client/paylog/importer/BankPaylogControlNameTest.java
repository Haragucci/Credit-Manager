package op.creditmanager.client.paylog.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankPaylogControlNameTest {
    @Test
    void acceptsPlayerHeadCustomNameWithLegacyColorAndBoldFormatting() {
        assertTrue(BankPaylogControlName.matches(
                "\u00a7c\u00a7lProfil & Einstellungen",
                BankPaylogImportStateMachine.PROFILE_SETTINGS));
    }

    @Test
    void acceptsPlainTextAfterMinecraftHasRemovedFormatting() {
        assertTrue(BankPaylogControlName.matches(
                "  Profil & Einstellungen  ",
                BankPaylogImportStateMachine.PROFILE_SETTINGS));
    }

    @Test
    void keepsTheUnformattedSemanticNameExact() {
        assertFalse(BankPaylogControlName.matches(
                "Öffne Profil & Einstellungen",
                BankPaylogImportStateMachine.PROFILE_SETTINGS));
    }
}

package op.creditmanager.client.core.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditValidationRulesTest {
    @Test
    void validatesPlayerNamesAndTextLimits() {
        assertTrue(CreditValidationRules.isValidPlayerName("Valid_Player1"));
        assertFalse(CreditValidationRules.isValidPlayerName("invalid-name"));
        assertFalse(CreditValidationRules.isValidPlayerName("x".repeat(CreditValidationRules.MAX_PLAYER_NAME_LENGTH + 1)));
        assertTrue(CreditValidationRules.isValidLabel("x".repeat(CreditValidationRules.MAX_LABEL_LENGTH)));
        assertFalse(CreditValidationRules.isValidLabel("x".repeat(CreditValidationRules.MAX_LABEL_LENGTH + 1)));
        assertTrue(CreditValidationRules.isValidNote("x".repeat(CreditValidationRules.MAX_NOTE_LENGTH)));
        assertFalse(CreditValidationRules.isValidNote("x".repeat(CreditValidationRules.MAX_NOTE_LENGTH + 1)));
    }
}

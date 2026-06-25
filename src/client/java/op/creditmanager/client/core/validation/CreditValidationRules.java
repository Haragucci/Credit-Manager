package op.creditmanager.client.core.validation;

import op.creditmanager.client.money.MoneyRules;

public final class CreditValidationRules {
    public static final int MAX_PLAYER_NAME_LENGTH = 32;
    public static final int MAX_LABEL_LENGTH = 128;
    public static final int MAX_NOTE_LENGTH = 4_096;
    public static final int MAX_PAYLOG_RAW_TEXT_LENGTH = 16_384;
    public static final int MAX_METADATA_LENGTH = 16_384;
    public static final double MAX_AMOUNT = MoneyRules.MAX_AMOUNT;
    public static final double EPSILON = MoneyRules.EPSILON;

    private CreditValidationRules() { }

    public static boolean isValidPlayerName(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{1," + MAX_PLAYER_NAME_LENGTH + "}");
    }

    public static boolean isValidLabel(String value) {
        return value == null || value.trim().length() <= MAX_LABEL_LENGTH;
    }

    public static boolean isValidNote(String value) {
        return value == null || value.trim().length() <= MAX_NOTE_LENGTH;
    }
}

package op.creditmanager.client.paylog.importer;

final class BankPaylogControlName {
    private BankPaylogControlName() { }

    static boolean matches(String actual, String expected) {
        return plain(actual).equals(plain(expected));
    }

    static String plain(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder plain = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\u00a7' && index + 1 < value.length() && isFormattingCode(value.charAt(index + 1))) {
                index++;
            } else {
                plain.append(current);
            }
        }
        return plain.toString().trim();
    }

    private static boolean isFormattingCode(char value) {
        char normalized = Character.toLowerCase(value);
        return normalized >= '0' && normalized <= '9'
                || normalized >= 'a' && normalized <= 'f'
                || normalized == 'k' || normalized == 'l' || normalized == 'm'
                || normalized == 'n' || normalized == 'o' || normalized == 'r'
                || normalized == 'x';
    }
}

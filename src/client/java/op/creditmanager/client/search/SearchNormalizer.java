package op.creditmanager.client.search;

import java.text.Normalizer;
import java.util.Locale;

public final class SearchNormalizer {
    private SearchNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("\u00DF", "ss")
                .replace("ß", "ss")
                .replaceAll("[\\s_-]+", " ")
                .replaceAll("[^a-z0-9 ]", "")
                .trim();
        return normalized;
    }
}

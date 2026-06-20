package op.creditmanager.client.search;

/** Lightweight ranking suitable for the small lists shown by Minecraft client screens. */
public final class FuzzySearch {
    private FuzzySearch() {
    }

    public static int score(String candidate, String query) {
        String value = SearchNormalizer.normalize(candidate);
        String wanted = SearchNormalizer.normalize(query);
        if (wanted.isEmpty()) return 1;
        if (value.equals(wanted)) return 1_000;
        if (value.startsWith(wanted)) return 800 - Math.min(200, value.length() - wanted.length());
        if (value.contains(wanted)) return 600 - Math.min(180, value.indexOf(wanted));
        int best = 0;
        for (String token : value.split(" ")) {
            if (token.startsWith(wanted)) best = Math.max(best, 500);
            else if (token.contains(wanted)) best = Math.max(best, 400);
            else if (!token.isEmpty()) {
                int distance = damerauLevenshtein(token, wanted);
                int tolerance = wanted.length() <= 4 ? 1 : Math.max(2, wanted.length() / 3);
                if (distance <= tolerance) best = Math.max(best, 300 - distance * 45);
            }
        }
        return best;
    }

    public static boolean matches(String candidate, String query) {
        return score(candidate, query) > 0;
    }

    private static int damerauLevenshtein(String first, String second) {
        int[][] distance = new int[first.length() + 1][second.length() + 1];
        for (int i = 0; i <= first.length(); i++) distance[i][0] = i;
        for (int j = 0; j <= second.length(); j++) distance[0][j] = j;
        for (int i = 1; i <= first.length(); i++) {
            for (int j = 1; j <= second.length(); j++) {
                int cost = first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1;
                distance[i][j] = Math.min(Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1),
                        distance[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && first.charAt(i - 1) == second.charAt(j - 2)
                        && first.charAt(i - 2) == second.charAt(j - 1)) {
                    distance[i][j] = Math.min(distance[i][j], distance[i - 2][j - 2] + cost);
                }
            }
        }
        return distance[first.length()][second.length()];
    }
}

package op.creditmanager.client.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzySearchTest {
    @Test
    void everyLeadingFragmentMatchesAPlayerName() {
        for (String query : new String[]{"ge", "ger", "gerr", "gerry237"}) {
            assertTrue(FuzzySearch.matches("gerry237", query));
        }
    }

    @Test
    void multipleFragmentsMayMatchDifferentWords() {
        assertTrue(FuzzySearch.matches("gerry237 premium handel", "gerr prem han"));
        assertEquals(0, FuzzySearch.score("gerry237 premium handel", "gerr verk"));
    }

    @Test
    void shortInputsDoNotCreateEditDistanceFalsePositives() {
        assertEquals(0, FuzzySearch.score("hans237", "ga"));
        assertEquals(0, FuzzySearch.score("gerry237", "erry"));
    }
}

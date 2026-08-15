package op.creditmanager.client.gui.modern;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModernNavigationCacheTest {

    @Test
    void navigationOrderAndIdsRemainStable() {
        assertEquals(List.of("overview", "claims", "debts", "paylogs", "history", "info", "settings"),
                ModernBaseScreen.navigationIds());
    }
}

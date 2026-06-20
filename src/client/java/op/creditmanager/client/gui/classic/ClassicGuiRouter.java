package op.creditmanager.client.gui.classic;

import net.minecraft.client.gui.screen.Screen;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CreditHauptmenüScreen;

/** Keeps the legacy inventory GUI isolated behind the common router. */
public final class ClassicGuiRouter {

    private ClassicGuiRouter() {
    }

    public static Screen createMainScreen(CreditManager manager) {
        return new CreditHauptmenüScreen(manager);
    }
}

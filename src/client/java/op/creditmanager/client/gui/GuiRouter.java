package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.ModernMainScreen;
import op.creditmanager.client.gui.modern.ModernRecoveryScreen;

public final class GuiRouter {

    private GuiRouter() {
    }

    public static void openMain(CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(manager.requiresRecovery() ? new ModernRecoveryScreen(manager) : new ModernMainScreen(manager)));
    }
}

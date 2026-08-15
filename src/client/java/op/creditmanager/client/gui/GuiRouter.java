package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.ModernMainScreen;
import op.creditmanager.client.gui.modern.ModernRecoveryScreen;
import op.creditmanager.client.paylog.importer.BankPaylogImportController;
import op.creditmanager.client.paylog.importer.BankPaylogImportOnboardingScreen;

public final class GuiRouter {

    private GuiRouter() {
    }

    public static void openMain(CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(mainScreen(manager)));
    }

    private static Screen mainScreen(CreditManager manager) {
        if (manager.requiresRecovery()) return new ModernRecoveryScreen(manager);
        BankPaylogImportController controller = BankPaylogImportController.getInstance();
        return controller.shouldOfferOnboardingInMenu()
                ? new BankPaylogImportOnboardingScreen(manager, controller)
                : new ModernMainScreen(manager);
    }
}

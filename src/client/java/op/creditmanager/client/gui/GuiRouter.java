package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiMode;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.classic.ClassicGuiRouter;
import op.creditmanager.client.gui.modern.ModernMainScreen;
import op.creditmanager.client.gui.select.GuiModeSelectionScreen;

/** Single entry point for all commands which open the client GUI. */
public final class GuiRouter {

    private GuiRouter() {
    }

    public static void openMain(CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> client.setScreen(createMainScreen(manager)));
    }

    public static void selectModeAndOpen(CreditManager manager, GuiMode mode) {
        ClientConfigManager.setGuiMode(mode);
        openMain(manager);
    }

    private static Screen createMainScreen(CreditManager manager) {
        return switch (ClientConfigManager.getGuiMode()) {
            case CLASSIC -> ClassicGuiRouter.createMainScreen(manager);
            case MODERN -> new ModernMainScreen(manager);
            case UNSELECTED -> new GuiModeSelectionScreen(manager);
        };
    }
}

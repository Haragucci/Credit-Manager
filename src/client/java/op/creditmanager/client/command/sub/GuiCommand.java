package op.creditmanager.client.command.sub;

import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CreditHauptmenüScreen;

public class GuiCommand {

    public static int openGui(CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();

        client.execute(() -> {
            client.setScreen(new CreditHauptmenüScreen(manager));
        });

        return 1;
    }
}
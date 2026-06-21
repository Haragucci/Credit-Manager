package op.creditmanager.client.command;

import com.mojang.brigadier.CommandDispatcher;
import op.creditmanager.client.core.CreditManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import op.creditmanager.client.gui.GuiRouter;

public class CreditManagerCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CreditManager manager) {
        registerCommand(dispatcher, manager, "CreditManager");
        registerCommand(dispatcher, manager, "cm");
        registerCommand(dispatcher, manager, "OpCreditManager");
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CreditManager manager, String name) {
        dispatcher.register(
                ClientCommandManager.literal(name)
                        .executes(ctx -> openGui(manager))
        );
    }

    private static int openGui(CreditManager manager) {
        GuiRouter.openMain(manager);
        return 1;
    }
}

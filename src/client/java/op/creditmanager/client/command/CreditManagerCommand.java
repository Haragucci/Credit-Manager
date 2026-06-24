package op.creditmanager.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.GuiRouter;

public class CreditManagerCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CreditManager manager) {
        registerCommand(dispatcher, manager, "CreditManager");
        registerCommand(dispatcher, manager, "cm");
        registerCommand(dispatcher, manager, "OpCreditManager");
        dispatcher.register(
                ClientCommandManager.literal("test-paylog")
                        .then(ClientCommandManager.argument("betrag", StringArgumentType.word())
                                .executes(ctx -> {
                                    String betrag = StringArgumentType.getString(ctx, "betrag");

                                    MinecraftClient client = MinecraftClient.getInstance();
                                    if (client != null && client.player != null) {
                                        MutableText finalText = Text.literal("OPSUCHT » leRqven hat dir " + betrag + "$ gegeben.");

                                        client.player.sendMessage(finalText, false);
                                    }

                                    return 1;
                                })
                        )
        );
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

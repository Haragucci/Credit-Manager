package op.creditmanager.client.command.sub;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CreditHauptmenüScreen;

public class GuiCommand {

    public static LiteralArgumentBuilder<FabricClientCommandSource> build(CreditManager manager) {
        return ClientCommandManager.literal("gui")
                .executes(ctx -> openGui(manager));
    }

    public static int openGui(CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();

        client.execute(() -> {
            client.setScreen(new CreditHauptmenüScreen(manager));
        });

        return 1;
    }
}
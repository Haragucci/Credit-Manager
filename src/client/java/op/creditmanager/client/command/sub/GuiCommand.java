package op.creditmanager.client.command.sub;

import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.GuiRouter;

public class GuiCommand {

    public static int openGui(CreditManager manager) {
        GuiRouter.openMain(manager);
        return 1;
    }
}

package op.creditmanager.client.gui.modern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;

/** Read-only product information page for the modern interface. */
public class ModernInfoScreen extends ModernBaseScreen {

    public ModernInfoScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Info", "info");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        int y = contentY + 8;
        ModernUi.card(context, contentX, y, contentWidth, 128, false);
        context.drawText(textRenderer, Text.literal("CreditManager"), contentX + 14, y + 14, ModernUi.TEXT, false);
        context.drawText(textRenderer, Text.literal("Version " + modVersion()), contentX + 14, y + 31, ModernUi.BLUE, false);
        context.drawText(textRenderer, Text.literal("Entwickelt von 05Haragucci"), contentX + 14, y + 51, ModernUi.MUTED, false);
        ModernUi.drawTruncated(context, textRenderer, "Verwalte Forderungen, Schulden, Zahlungen und erkannte Paylogs direkt im Client.",
                contentX + 14, y + 69, contentWidth - 28, ModernUi.MUTED);
        ModernUi.drawTruncated(context, textRenderer, "Die Daten liegen lokal in CreditManagerLogs und werden nicht durch die GUI-Auswahl verändert.",
                contentX + 14, y + 85, contentWidth - 28, ModernUi.MUTED);
        ModernUi.drawTruncated(context, textRenderer, "GitHub: github.com/Haragucci/Credit-Manager", contentX + 14, y + 107,
                contentWidth - 28, ModernUi.YELLOW);
        super.render(context, mouseX, mouseY, delta);
    }

    private String modVersion() {
        return FabricLoader.getInstance().getModContainer("creditmanager")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unbekannt");
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        return super.mouseClicked(click, doubled);
    }
}

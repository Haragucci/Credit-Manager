package op.creditmanager.client.paylog.importer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.ModernLayout;
import op.creditmanager.client.gui.modern.ModernMainScreen;
import op.creditmanager.client.gui.modern.ModernUi;

import java.util.List;

public final class BankPaylogImportOnboardingScreen extends ModernMainScreen {
    private final BankPaylogImportController controller;
    private List<ModernLayout.Bounds> buttons = List.of();

    public BankPaylogImportOnboardingScreen(CreditManager manager, BankPaylogImportController controller) {
        super(manager);
        this.controller = controller;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, ModernUi.theme().overlay);
        int panelWidth = Math.max(1, Math.min(430, width - Math.min(24, Math.max(2, width / 8))));
        int panelHeight = Math.max(1, Math.min(174, height - Math.min(24, Math.max(2, height / 8))));
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;
        ModernUi.panel(context, panelX, panelY, panelWidth, panelHeight, ModernUi.theme().panel);
        ModernUi.drawCentered(context, textRenderer, "Vorhandene Paylogs importieren?",
                panelX + panelWidth / 2, panelY + 16, ModernUi.theme().text);
        int textX = panelX + 14;
        int textWidth = Math.max(1, panelWidth - 28);
        ModernUi.drawTruncated(context, textRenderer,
                "CreditManager kann bestehende Bank-Überweisungen automatisch aus /bank einlesen.",
                textX, panelY + 42, textWidth, ModernUi.theme().muted);
        ModernUi.drawTruncated(context, textRenderer,
                "„Überweisung von …“ und „Überweisung an …“ werden übernommen.",
                textX, panelY + 56, textWidth, ModernUi.theme().muted);
        ModernUi.drawTruncated(context, textRenderer,
                "Bereits eindeutig vorhandene Paylogs werden übersprungen.",
                textX, panelY + 70, textWidth, ModernUi.theme().muted);
        buttons = ModernLayout.buttonRow(panelX + 14, panelY + panelHeight - 40,
                Math.max(1, panelWidth - 28), 2, 104, 24, 8);
        ModernLayout.Bounds yes = buttons.getFirst();
        ModernLayout.Bounds no = buttons.get(1);
        ModernUi.button(context, textRenderer, yes.x(), yes.y(), yes.width(), yes.height(), "Ja, importieren",
                ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, yes.x(), yes.y(), yes.width(), yes.height()));
        ModernUi.button(context, textRenderer, no.x(), no.y(), no.width(), no.height(), "Nein",
                ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, no.x(), no.y(), no.width(), no.height()));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0 || buttons.size() != 2) return true;
        ModernLayout.Bounds yes = buttons.getFirst();
        ModernLayout.Bounds no = buttons.get(1);
        if (ModernUi.contains(click.x(), click.y(), yes.x(), yes.y(), yes.width(), yes.height())) {
            controller.acceptOnboarding();
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), no.x(), no.y(), no.width(), no.height())) {
            if (controller.declineOnboarding()) {
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    @Override
    public void close() { }

    @Override
    public boolean shouldPause() { return false; }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.ArrayList;
import java.util.List;

/** Visible, non-destructive recovery state for unreadable or unsafe local data. */
public final class ModernRecoveryScreen extends ModernBaseScreen {
    private final ModernScrollArea scroll = new ModernScrollArea();
    private List<ModernLayout.Bounds> recoveryButtons = List.of();
    private int viewportY, viewportHeight;

    public ModernRecoveryScreen(CreditManager manager) {
        super(manager, null, "Datenwiederherstellung", "settings");
    }

    @Override
    protected boolean handleSidebarClick(Click click) {
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        viewportY = contentY + 6;
        int buttonHeight = ModernLayout.stack(contentWidth, 3, 74, 8) ? 85 : 23;
        int buttonY = contentY + contentHeight - buttonHeight;
        recoveryButtons = ModernLayout.buttonRow(contentX, buttonY, contentWidth, 3, 74, 23, 8);
        viewportHeight = Math.max(34, buttonY - viewportY - 8);
        List<String> lines = lines();
        int lineHeight = textRenderer.fontHeight + 5;
        int contentHeight = 44 + lines.size() * lineHeight + 10;
        scroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, contentHeight);
        scroll.tick(mouseX, mouseY);
        int y = viewportY - scroll.offset();
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        ModernUi.card(context, contentX, y, contentWidth - (scroll.isScrollable() ? 8 : 0), contentHeight, false);
        ModernUi.drawGuiText(context, textRenderer, "Datenprüfung erforderlich", contentX + 12, y + 12, ModernUi.theme().danger);
        ModernUi.drawTruncated(context, textRenderer, "Keine Daten wurden gelöscht. Änderungen bleiben gesperrt, bis die Prüfung abgeschlossen ist.",
                contentX + 12, y + 28, Math.max(40, contentWidth - 28), ModernUi.theme().warning);
        int lineY = y + 50;
        for (String line : lines) {
            ModernUi.drawTruncated(context, textRenderer, line, contentX + 12, lineY, Math.max(40, contentWidth - 28), ModernUi.theme().muted);
            lineY += lineHeight;
        }
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);

        ModernLayout.Bounds restore = recoveryButtons.getFirst();
        ModernLayout.Bounds backup = recoveryButtons.get(1);
        ModernLayout.Bounds retry = recoveryButtons.get(2);
        ModernUi.button(context, textRenderer, restore.x(), restore.y(), restore.width(), restore.height(), "Backup laden", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, restore.x(), restore.y(), restore.width(), restore.height()));
        ModernUi.button(context, textRenderer, backup.x(), backup.y(), backup.width(), backup.height(), "Backup sichern", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, backup.x(), backup.y(), backup.width(), backup.height()));
        ModernUi.button(context, textRenderer, retry.x(), retry.y(), retry.width(), retry.height(), "Neu prüfen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, retry.x(), retry.y(), retry.width(), retry.height()));
        super.render(context, mouseX, mouseY, delta);
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Datenpfad: " + FileManager.getDataDirectory());
        lines.add("Datenbank: " + FileManager.getDatabaseStorageFile());
        lines.add("Sicherungen: " + DatabaseManager.getInstance().listBackups().size());
        for (String reason : DataHealth.reasons()) lines.add("• " + reason);
        for (CreditRepository.RecoveryRecord record : manager.getRecoveryRecords()) lines.add("• " + record.message());
        if (lines.size() == 3) lines.add("• Die Datenbank konnte noch nicht als schreibsicher bestätigt werden.");
        return lines;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (!recoveryButtons.isEmpty() && ModernUi.contains(click.x(), click.y(), recoveryButtons.getFirst().x(), recoveryButtons.getFirst().y(), recoveryButtons.getFirst().width(), recoveryButtons.getFirst().height())) {
            if (manager.restoreLatestSafetyBackup()) {
                toastSuccess("Valide Sicherung geladen.");
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            } else toastError("Keine valide Sicherung konnte wiederhergestellt werden.");
            return true;
        }
        if (recoveryButtons.size() > 1 && ModernUi.contains(click.x(), click.y(), recoveryButtons.get(1).x(), recoveryButtons.get(1).y(), recoveryButtons.get(1).width(), recoveryButtons.get(1).height())) {
            if (manager.createSafetyBackup()) toastSuccess("H2-Sicherung erstellt.");
            else toastError("Sicherung konnte nicht erstellt werden.");
            return true;
        }
        if (recoveryButtons.size() > 2 && ModernUi.contains(click.x(), click.y(), recoveryButtons.get(2).x(), recoveryButtons.get(2).y(), recoveryButtons.get(2).width(), recoveryButtons.get(2).height())) {
            manager.reloadData();
            if (!manager.requiresRecovery()) MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            else toastWarning("Datenprüfung ist weiterhin erforderlich.");
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scroll.contains(mouseX, mouseY)) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}

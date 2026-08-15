package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditRepository;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.gui.modern.recovery.RecoveryActionController;
import op.creditmanager.client.gui.modern.recovery.RecoveryActionExecutor;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.storage.DataHealth;
import op.creditmanager.client.storage.FileManager;
import op.creditmanager.client.storage.db.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ModernRecoveryScreen extends ModernBaseScreen {
    private final ModernScrollArea scroll = new ModernScrollArea();
    private List<ModernLayout.Bounds> recoveryButtons = List.of();
    private int viewportY, viewportHeight;
    private boolean confirmEmptyDatabase;
    private RecoveryViewState viewState = RecoveryViewState.loading();
    private CompletableFuture<RecoverySnapshot> pendingLoad;
    private long loadSequence;
    private boolean disposed;
    private volatile DatabaseManager.ManualBackupResult lastManualBackupResult;
    private final RecoveryActionController actionController = new RecoveryActionController();

    public ModernRecoveryScreen(CreditManager manager) {
        super(manager, null, "Datenwiederherstellung", "settings");
    }

    @Override
    protected boolean handleSidebarClick(Click click) {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        disposed = false;
        actionController.reopen();
        refreshViewState();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        viewportY = contentY + 6;
        boolean canCreateEmpty = DatabaseManager.getInstance().requiresUserRecovery();
        int buttonCount = canCreateEmpty ? 6 : 5;
        int buttonHeight = ModernLayout.stack(contentWidth, buttonCount, 74, 8) ? buttonCount * 23 + (buttonCount - 1) * 8 : 23;
        int buttonY = contentY + contentHeight - buttonHeight;
        recoveryButtons = ModernLayout.buttonRow(contentX, buttonY, contentWidth, buttonCount, 74, 23, 8);
        viewportHeight = Math.max(34, buttonY - viewportY - 8);
        List<String> lines = new ArrayList<>(viewState.lines());
        if (actionController.isRunning()) lines.addFirst("Aktion läuft: " + actionLabel(actionController.state().action()));
        int lineHeight = textRenderer.fontHeight + 5;
        int contentHeight = 44 + lines.size() * lineHeight + 10;
        scroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, contentHeight);
        scroll.tick(mouseX, mouseY);
        int y = viewportY - scroll.offset();
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        ModernUi.card(context, contentX, y, contentWidth - (scroll.isScrollable() ? 8 : 0), contentHeight, false);
        DatabaseManager.DatabaseAvailability availability = DatabaseManager.getInstance().availability();
        ModernUi.drawGuiText(context, textRenderer, stateTitle(availability), contentX + 12, y + 12, ModernUi.theme().danger);
        ModernUi.drawTruncated(context, textRenderer, stateDescription(availability),
                contentX + 12, y + 28, Math.max(40, contentWidth - 28), ModernUi.theme().warning);
        int lineY = y + 50;
        for (String line : lines) {
            ModernUi.drawTruncated(context, textRenderer, line, contentX + 12, lineY, Math.max(40, contentWidth - 28), ModernUi.theme().muted);
            lineY += lineHeight;
        }
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);

        ModernLayout.Bounds repair = recoveryButtons.getFirst();
        ModernLayout.Bounds retry = recoveryButtons.get(1);
        ModernLayout.Bounds healthyBackup = recoveryButtons.get(2);
        ModernLayout.Bounds backup = recoveryButtons.get(3);
        ModernLayout.Bounds restore = recoveryButtons.get(4);
        boolean actionsEnabled = !actionController.isRunning();
        boolean storageAccess = FileManager.databaseAccessAllowed();
        ModernUi.button(context, textRenderer, repair.x(), repair.y(), repair.width(), repair.height(), "Schema reparieren", ModernUi.theme().buttonPrimary,
                actionsEnabled && storageAccess && ModernUi.contains(mouseX, mouseY, repair.x(), repair.y(), repair.width(), repair.height()));
        ModernUi.button(context, textRenderer, retry.x(), retry.y(), retry.width(), retry.height(),
                availability == DatabaseManager.DatabaseAvailability.SECONDARY_INSTANCE ? "Primärzugriff erneut versuchen" : "Neu prüfen", ModernUi.theme().buttonNeutral,
                actionsEnabled && ModernUi.contains(mouseX, mouseY, retry.x(), retry.y(), retry.width(), retry.height()));
        ModernUi.button(context, textRenderer, healthyBackup.x(), healthyBackup.y(), healthyBackup.width(), healthyBackup.height(), "Gesundes Backup jetzt", ModernUi.theme().buttonPrimary,
                actionsEnabled && storageAccess && ModernUi.contains(mouseX, mouseY, healthyBackup.x(), healthyBackup.y(), healthyBackup.width(), healthyBackup.height()));
        ModernUi.button(context, textRenderer, backup.x(), backup.y(), backup.width(), backup.height(), "Snapshot sichern", ModernUi.theme().buttonNeutral,
                actionsEnabled && storageAccess && ModernUi.contains(mouseX, mouseY, backup.x(), backup.y(), backup.width(), backup.height()));
        ModernUi.button(context, textRenderer, restore.x(), restore.y(), restore.width(), restore.height(), "Backup laden", ModernUi.theme().buttonNeutral,
                actionsEnabled && storageAccess && ModernUi.contains(mouseX, mouseY, restore.x(), restore.y(), restore.width(), restore.height()));
        if (canCreateEmpty) {
            ModernLayout.Bounds empty = recoveryButtons.get(5);
            ModernUi.button(context, textRenderer, empty.x(), empty.y(), empty.width(), empty.height(), confirmEmptyDatabase ? "Erneut bestätigen" : "Neue leere DB", ModernUi.theme().buttonDanger,
                    actionsEnabled && ModernUi.contains(mouseX, mouseY, empty.x(), empty.y(), empty.width(), empty.height()));
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private RecoverySnapshot loadSnapshot() {
        List<String> lines = new ArrayList<>();
        DatabaseManager database = DatabaseManager.getInstance();
        lines.add("Status: " + database.availability());
        lines.add("Datenpfad: " + FileManager.getDataDirectory());
        lines.add("Datenbank: " + FileManager.getDatabaseStorageFile());
        List<DatabaseManager.AvailableBackup> available = database.listAvailableBackups();
        long restorable = available.stream().filter(value -> value.entry().automaticRestoreEligible()).count();
        long snapshots = available.stream().filter(value -> value.entry().artifactType() == DatabaseManager.BackupArtifactType.RECOVERY_SNAPSHOT).count();
        long local = available.stream().filter(value -> value.source() == DatabaseManager.BackupSource.LOCAL
                || value.source() == DatabaseManager.BackupSource.LOCAL_AND_MIRROR).count();
        long mirror = available.stream().filter(value -> value.source() == DatabaseManager.BackupSource.MIRROR
                || value.source() == DatabaseManager.BackupSource.LOCAL_AND_MIRROR).count();
        lines.add("Wiederherstellbare Backups: " + restorable);
        lines.add("Recovery-Snapshots: " + snapshots);
        lines.add("Backup-Quellen: lokal " + local + ", Mirror " + mirror);
        for (String reason : DataHealth.reasons()) lines.add("• " + reason);
        for (CreditRepository.RecoveryRecord record : manager.getRecoveryRecords()) lines.add("• " + record.message());
        DatabaseManager.BackupProtectionMetrics metrics = database.backupProtectionMetrics();
        lines.add("DB-Revision: " + metrics.currentRevision());
        lines.add("Lokales Backup: Rev. " + metrics.latestLocalBackupRevision() + ", Alter " + backupAge(metrics.latestLocalBackupAt()));
        lines.add("Mirror-Backup: Rev. " + metrics.latestMirrorBackupRevision() + ", Alter " + backupAge(metrics.latestMirrorBackupAt()));
        lines.add("Backup-Schutz: " + metrics.protectionState());
        lines.add("Backup-Lag: " + metrics.backupLagRevisions() + " Revision(en)");
        lines.add("Checkpoint ausstehend: " + (metrics.checkpointPending() ? "ja" : "nein")
                + ", Fehlerfolge: " + metrics.consecutiveBackupFailures());
        if (lines.size() == 6) lines.add("• Die Datenbank konnte noch nicht als schreibsicher bestätigt werden.");
        return new RecoverySnapshot(List.copyOf(lines));
    }

    private String stateTitle(DatabaseManager.DatabaseAvailability availability) {
        return switch (availability) {
            case SECONDARY_INSTANCE -> "Andere Minecraft-Instanz ist Primärinstanz";
            case STORAGE_LOCATION_UNRESOLVED -> "Persistenter Datenpfad nicht sicher bestimmbar";
            case MISSING_DATABASE -> "Aktive Datenbank fehlt";
            case STORAGE_IDENTITY_MISMATCH -> "Storage-Identität stimmt nicht überein";
            case STORAGE_CONFLICT -> "Mehrere unterschiedliche Datenstände gefunden";
            case BACKUP_PROTECTION_DEGRADED -> "Backup-Schutz ist degradiert";
            case BACKUP_PROTECTION_CRITICAL -> "Backup-Schutz ist kritisch";
            default -> "Datenprüfung erforderlich";
        };
    }

    private String stateDescription(DatabaseManager.DatabaseAvailability availability) {
        return switch (availability) {
            case SECONDARY_INSTANCE -> "CreditManager läuft bereits in einer anderen Minecraft-Instanz. Diese Instanz öffnet die Datenbank nicht.";
            case STORAGE_LOCATION_UNRESOLVED -> "Zum Schutz deiner Daten wurde weder ein Overlay-Pfad beschrieben noch eine Datenbank geöffnet.";
            case MISSING_DATABASE -> "Es wurde keine neue leere Datenbank erzeugt. Prüfe die vorhandenen Backups und Recovery-Artefakte.";
            case STORAGE_IDENTITY_MISMATCH -> "Eine andere Datenbasis wurde erkannt und deshalb nicht akzeptiert oder überschrieben.";
            case STORAGE_CONFLICT -> "Legacy- und Canonical-Storage bleiben unverändert, bis der Konflikt eindeutig geklärt ist.";
            case BACKUP_PROTECTION_DEGRADED -> "DB-Commits bleiben gültig, aber automatische Checkpoints sind wiederholt fehlgeschlagen.";
            case BACKUP_PROTECTION_CRITICAL -> "Letzte Commits sind gespeichert; neue Änderungen bleiben bis zu einem aktuellen validierten Backup gesperrt.";
            default -> "Keine Daten wurden gelöscht. Änderungen bleiben gesperrt, bis die Prüfung abgeschlossen ist.";
        };
    }

    private void refreshViewState() {
        long sequence = ++loadSequence;
        ModernQueryExecutor.cancel(this);
        if (pendingLoad != null) pendingLoad.cancel(true);
        viewState = RecoveryViewState.loading();
        pendingLoad = ModernQueryExecutor.submitLatest(this, this::loadSnapshot);
        pendingLoad.whenComplete((snapshot, error) -> MinecraftClient.getInstance().execute(() -> {
            if (disposed || sequence != loadSequence || MinecraftClient.getInstance().currentScreen != this) return;
            viewState = error == null ? RecoveryViewState.ready(snapshot.lines())
                    : RecoveryViewState.error("Recovery-Informationen konnten nicht geladen werden.");
            pendingLoad = null;
        }));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (actionController.isRunning()) return true;
        if (containsButton(0, click)) {
            if (!FileManager.databaseAccessAllowed()) return true;
            startRecoveryAction(RecoveryActionController.Action.REPAIR, manager::recheckAndRepairDatabase);
            return true;
        }
        if (containsButton(1, click)) {
            startRecoveryAction(RecoveryActionController.Action.RECHECK, manager::recheckAndRepairDatabase);
            return true;
        }
        if (containsButton(2, click)) {
            if (!FileManager.databaseAccessAllowed()) return true;
            startRecoveryAction(RecoveryActionController.Action.HEALTHY_BACKUP, () -> {
                lastManualBackupResult = manager.createHealthyBackupNow();
                return lastManualBackupResult.localSuccess();
            });
            return true;
        }
        if (containsButton(3, click)) {
            if (!FileManager.databaseAccessAllowed()) return true;
            startRecoveryAction(RecoveryActionController.Action.SNAPSHOT, manager::createSafetyBackup);
            return true;
        }
        if (containsButton(4, click)) {
            if (!FileManager.databaseAccessAllowed()) return true;
            startRecoveryAction(RecoveryActionController.Action.RESTORE, manager::restoreLatestSafetyBackup);
            return true;
        }
        if (containsButton(5, click)) {
            if (!confirmEmptyDatabase) {
                confirmEmptyDatabase = true;
                toastWarning("Die beschädigte Datei bleibt in Quarantäne. Zum Erstellen erneut klicken.");
                return true;
            }
            startRecoveryAction(RecoveryActionController.Action.CREATE_EMPTY, manager::createEmptyDatabaseAfterPhysicalRecovery);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean containsButton(int index, Click click) {
        if (index < 0 || index >= recoveryButtons.size()) return false;
        ModernLayout.Bounds bounds = recoveryButtons.get(index);
        return ModernUi.contains(click.x(), click.y(), bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private void startRecoveryAction(RecoveryActionController.Action action, Supplier<Boolean> operation) {
        actionController.start(action, operation, RecoveryActionExecutor.get(),
                runnable -> MinecraftClient.getInstance().execute(runnable), this::completeRecoveryAction);
    }

    private void completeRecoveryAction(RecoveryActionController.Result result) {
        if (result.error() != null) CreditManagerClient.LOGGER.error("Recovery action {} failed", result.action(), result.error());
        if (!result.success()) {
            toastError(switch (result.action()) {
                case REPAIR -> "Schema konnte noch nicht vollständig repariert werden.";
                case RECHECK -> "Datenprüfung ist weiterhin erforderlich.";
                case HEALTHY_BACKUP -> lastManualBackupResult == null
                        ? "Gesundes Backup konnte nicht erstellt werden." : lastManualBackupResult.message();
                case SNAPSHOT -> "Recovery-Snapshot konnte nicht erstellt werden.";
                case RESTORE -> "Keine valide Sicherung konnte wiederhergestellt werden.";
                case CREATE_EMPTY -> "Neue Datenbank konnte nicht sicher erstellt werden.";
            });
            refreshViewState();
            return;
        }
        switch (result.action()) {
            case HEALTHY_BACKUP -> {
                DatabaseManager.ManualBackupResult backup = lastManualBackupResult;
                if (backup != null && backup.mirrorSuccess()) toastSuccess(backup.message());
                else if (backup != null) toastWarning(backup.message());
                refreshViewState();
            }
            case SNAPSHOT -> {
                toastSuccess("Recovery-Snapshot wurde erstellt und ist nicht automatisch restore-fähig.");
                refreshViewState();
            }
            case REPAIR -> {
                toastSuccess("Schema erfolgreich geprüft und repariert.");
                CreditManagerClient.rebindAfterStorageRecovery();
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            }
            case RECHECK -> {
                toastSuccess("Datenbankprüfung abgeschlossen.");
                CreditManagerClient.rebindAfterStorageRecovery();
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            }
            case RESTORE -> {
                toastSuccess("Valide Sicherung geladen.");
                CreditManagerClient.rebindAfterStorageRecovery();
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            }
            case CREATE_EMPTY -> {
                toastSuccess("Neue leere Datenbank erstellt. Die beschädigte Datei bleibt in Quarantäne.");
                CreditManagerClient.rebindAfterStorageRecovery();
                MinecraftClient.getInstance().setScreen(new ModernMainScreen(manager));
            }
        }
    }

    private String actionLabel(RecoveryActionController.Action action) {
        if (action == null) return "Recovery";
        return switch (action) {
            case REPAIR -> "Schema reparieren";
            case RECHECK -> "Datenbank prüfen";
            case HEALTHY_BACKUP -> "Gesundes Backup erstellen";
            case SNAPSHOT -> "Recovery-Snapshot sichern";
            case RESTORE -> "Backup wiederherstellen";
            case CREATE_EMPTY -> "Neue Datenbank erstellen";
        };
    }

    private String backupAge(long createdAt) {
        if (createdAt <= 0L) return "unbekannt";
        long seconds = Math.max(0L, (System.currentTimeMillis() - createdAt) / 1_000L);
        if (seconds < 60L) return seconds + " s";
        long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + " min";
        return minutes / 60L + " h";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scroll.contains(mouseX, mouseY)) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void clearTransientState() {
        disposed = true;
        loadSequence++;
        ModernQueryExecutor.cancel(this);
        if (pendingLoad != null) pendingLoad.cancel(true);
        pendingLoad = null;
        viewState = RecoveryViewState.loading();
        actionController.close();
        scroll.reset();
        super.clearTransientState();
    }

    private record RecoverySnapshot(List<String> lines) { }

    private record RecoveryViewState(Status status, List<String> lines) {
        private static RecoveryViewState loading() { return new RecoveryViewState(Status.LOADING, List.of("Recovery-Informationen werden geladen…")); }
        private static RecoveryViewState ready(List<String> lines) { return new RecoveryViewState(Status.READY, List.copyOf(lines)); }
        private static RecoveryViewState error(String message) { return new RecoveryViewState(Status.ERROR, List.of(message)); }
        private enum Status { LOADING, READY, ERROR }
    }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.Locale;


public final class ModernEditCreditScreen extends ModernBaseScreen {
    private static final int FORM_HEIGHT = 496;

    private CreditEntry entry;
    private final boolean debts;
    private final ModernScrollArea scroll = new ModernScrollArea();

    private TextFieldWidget playerField;
    private TextFieldWidget amountField;
    private TextFieldWidget dueField;
    private TextFieldWidget labelField;
    private TextFieldWidget noteField;

    private String originalPlayer;
    private double originalAmount;
    private String originalDue;
    private String originalLabel;
    private String originalNote;
    private boolean discardArmed;
    private int viewportY;
    private int viewportHeight;
    private int saveY;
    private int resetY;
    private int cancelY;

    public ModernEditCreditScreen(CreditManager manager, CreditEntry entry, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Schuld bearbeiten" : "Forderung bearbeiten", debts ? "debts" : "claims");
        this.entry = entry;
        this.debts = debts;
    }

    @Override
    protected void init() {
        String draftPlayer = playerField == null ? null : playerField.getText();
        String draftAmount = amountField == null ? null : amountField.getText();
        String draftDue = dueField == null ? null : dueField.getText();
        String draftLabel = labelField == null ? null : labelField.getText();
        String draftNote = noteField == null ? null : noteField.getText();
        super.init();
        clearChildren();
        if (draftPlayer == null) captureOriginalValues();
        int fieldWidth = Math.max(88, contentWidth - 22);
        playerField = field(fieldWidth, 32);
        amountField = field(fieldWidth, 20);
        dueField = field(fieldWidth, 10);
        labelField = field(fieldWidth, 32);
        noteField = field(fieldWidth, 128);
        if (draftPlayer == null) restoreOriginalValues();
        else {
            playerField.setText(draftPlayer);
            amountField.setText(draftAmount);
            dueField.setText(draftDue);
            labelField.setText(draftLabel);
            noteField.setText(draftNote);
        }
    }

    private TextFieldWidget field(int width, int maxLength) {
        TextFieldWidget field = ModernUi.configureGuiTextField(
                new TextFieldWidget(textRenderer, contentX + 10, contentY, width, 18, Text.empty()));
        field.setMaxLength(maxLength);
        addDrawableChild(field);
        return field;
    }

    private void captureOriginalValues() {
        originalPlayer = debts ? safe(entry.getCreditor()) : safe(entry.getDebtor());
        originalAmount = entry.getAmount();
        originalDue = entry.getDueDate() == null ? "" : TimeUtil.formatDate(entry.getDueDate());
        originalLabel = existingLabel();
        originalNote = entry.getNote() == null ? "" : entry.getNote();
    }

    private String existingLabel() {
        String prefix = safe(entry.getDebtor()) + "-" + safe(entry.getCreditor());
        String name = entry.getDealName() == null ? "" : entry.getDealName();
        return name.startsWith(prefix + "-") ? name.substring(prefix.length() + 1) : "";
    }

    private void restoreOriginalValues() {
        playerField.setText(originalPlayer);
        amountField.setText(String.valueOf(originalAmount));
        dueField.setText(originalDue);
        labelField.setText(originalLabel);
        noteField.setText(originalNote);
        discardArmed = false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        refreshEntry();
        renderShell(context, mouseX, mouseY);

        viewportY = contentY + 4;
        viewportHeight = Math.max(40, contentHeight - 8);
        scroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, FORM_HEIGHT);
        scroll.tick(mouseX, mouseY);
        int y = viewportY - scroll.offset();
        FormValidation validation = validateForm();
        boolean changed = isDirty();

        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        drawSummary(context, y);
        drawSectionTitle(context, "Deal-Daten", y + 99);

        int fieldY = y + 115;
        drawField(context, mouseX, mouseY, debts ? "Gläubiger (du bist Schuldner)" : "Schuldner (du bist Gläubiger)", playerField, fieldY, validation.playerError());
        drawField(context, mouseX, mouseY, "Betrag", amountField, fieldY + 54, validation.amountError());
        drawField(context, mouseX, mouseY, "Fälligkeit (optional, TT.MM.JJJJ)", dueField, fieldY + 108, validation.dueError());
        drawField(context, mouseX, mouseY, "Bezeichnung (optional)", labelField, fieldY + 162, null);
        drawField(context, mouseX, mouseY, "Notiz (optional, " + noteField.getText().length() + "/128)", noteField, fieldY + 216, null);

        drawPreview(context, y + 386, validation);
        drawActions(context, mouseX, mouseY, y + 465, validation.valid() && changed, changed);
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSummary(DrawContext context, int y) {
        ModernUi.card(context, contentX, y, contentWidth, 87, false);
        String type = debts ? "Schuld" : "Forderung";
        String shortId = entry.getId() == null ? "unbekannt" : entry.getId().toString().substring(0, 8);
        ModernUi.drawTruncated(context, textRenderer, type + " · " + statusLabel(entry.getStatus()) + " · " + shortId,
                contentX + 10, y + 9, contentWidth - 20, statusColor(entry.getStatus()));
        ModernUi.drawTruncated(context, textRenderer, safe(entry.getDebtor()) + "  →  " + safe(entry.getCreditor()),
                contentX + 10, y + 26, contentWidth - 20, ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, "Gesamt: " + FormatUtil.formatAmount(entry.getAmount()),
                contentX + 10, y + 43, contentWidth - 20, ModernUi.theme().muted);
        ModernUi.drawTruncated(context, textRenderer, "Bezahlt: " + FormatUtil.formatAmount(entry.getPaidAmount()),
                contentX + 10, y + 57, contentWidth - 20, ModernUi.theme().success);
        ModernUi.drawTruncated(context, textRenderer, "Offen: " + FormatUtil.formatAmount(entry.getRemainingAmount()),
                contentX + 10, y + 71, contentWidth - 20, debts ? ModernUi.theme().danger : ModernUi.theme().success);
    }

    private void drawSectionTitle(DrawContext context, String value, int y) {
        ModernUi.drawGuiText(context, textRenderer, value, contentX, y, ModernUi.theme().muted);
    }

    private void drawField(DrawContext context, int mouseX, int mouseY, String label, TextFieldWidget field, int y, String error) {
        ModernUi.drawTruncated(context, textRenderer, label, contentX, y, contentWidth - 8, ModernUi.theme().muted);
        ModernUi.card(context, contentX, y + 10, contentWidth - (scroll.isScrollable() ? 8 : 0), 26,
                ModernUi.contains(mouseX, mouseY, contentX, y + 10, contentWidth, 26));
        if (error != null) context.fill(contentX, y + 10, contentX + contentWidth - (scroll.isScrollable() ? 8 : 0), y + 11, ModernUi.theme().danger);
        field.setPosition(contentX + 10, y + 14);
        field.setVisible(y + 10 >= viewportY && y + 37 <= viewportY + viewportHeight);
        if (error != null) {
            ModernUi.drawTruncated(context, textRenderer, error, contentX + 2, y + 40,
                    contentWidth - 10, ModernUi.theme().danger);
        }
    }

    private void drawPreview(DrawContext context, int y, FormValidation validation) {
        ModernUi.card(context, contentX, y, contentWidth - (scroll.isScrollable() ? 8 : 0), 70, false);
        ModernUi.drawGuiText(context, textRenderer, "Vorschau", contentX + 9, y + 8, ModernUi.theme().muted);
        String player = playerField.getText().trim().toLowerCase(Locale.ROOT);
        String own = currentPlayerName();
        String creditor = debts ? player : own;
        String debtor = debts ? own : player;
        String name = validation.playerError() == null ? CreditEntry.buildDealName(debtor, creditor, blankToNull(labelField.getText())) : "–";
        ModernUi.drawTruncated(context, textRenderer, debtor + " → " + creditor + " · " + name,
                contentX + 9, y + 22, contentWidth - 18, ModernUi.theme().text);
        String amount = validation.amountError() == null ? FormatUtil.formatAmount(validation.amount()) : "ungültiger Betrag";
        ModernUi.drawTruncated(context, textRenderer, "Gesamt: " + amount, contentX + 9, y + 35, contentWidth - 18,
                validation.amountError() == null ? ModernUi.theme().success : ModernUi.theme().danger);
        ModernUi.drawTruncated(context, textRenderer, "Bezahlt: " + FormatUtil.formatAmount(entry.getPaidAmount()), contentX + 9, y + 47,
                contentWidth - 18, ModernUi.theme().success);
        ModernUi.drawTruncated(context, textRenderer, "Offen danach: " + (validation.amountError() == null
                        ? FormatUtil.formatAmount(Math.max(0, validation.amount() - entry.getPaidAmount())) : "–"),
                contentX + 9, y + 59, contentWidth - 18, validation.amountError() == null ? ModernUi.theme().text : ModernUi.theme().danger);
    }

    private void drawActions(DrawContext context, int mouseX, int mouseY, int y, boolean canSave, boolean changed) {
        saveY = y;
        int width = Math.max(48, (contentWidth - 16) / 3);
        resetY = y;
        cancelY = y;
        ModernUi.button(context, textRenderer, contentX, y, width, 23, canSave ? "Speichern" : changed ? "Eingaben prüfen" : "Keine Änderungen",
                canSave ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, y, width, 23));
        ModernUi.button(context, textRenderer, contentX + width + 8, y, width, 23, "Zurücksetzen", ModernUi.theme().buttonGold,
                ModernUi.contains(mouseX, mouseY, contentX + width + 8, y, width, 23));
        ModernUi.button(context, textRenderer, contentX + (width + 8) * 2, y, width, 23, "Abbrechen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX + (width + 8) * 2, y, width, 23));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && isLeaveControl(click) && !confirmDiscard()) return true;
        if (handleSidebarClick(click)) return true;
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && inViewport(saveY) && ModernUi.contains(click.x(), click.y(), contentX, saveY, actionWidth(), 23)) {
            save();
            return true;
        }
        if (click.button() == 0 && inViewport(resetY) && ModernUi.contains(click.x(), click.y(), contentX + actionWidth() + 8, resetY, actionWidth(), 23)) {
            restoreOriginalValues();
            return true;
        }
        if (click.button() == 0 && inViewport(cancelY) && ModernUi.contains(click.x(), click.y(), contentX + (actionWidth() + 8) * 2, cancelY, actionWidth(), 23)) {
            if (confirmDiscard()) closeToParent();
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

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == 256 && !confirmDiscard()) return true;
        return super.keyPressed(input);
    }

    private boolean confirmDiscard() {
        if (!isDirty()) return true;
        if (!discardArmed) {
            discardArmed = true;
            toastWarning("Ungespeicherte Änderungen: erneut verlassen, um sie zu verwerfen.");
            return false;
        }
        discardArmed = false;
        return true;
    }

    private boolean isLeaveControl(Click click) {
        if (ModernUi.contains(click.x(), click.y(), closeButtonX, closeButtonY, 18, 18)
                || (shouldShowBackButton() && ModernUi.contains(click.x(), click.y(), backButtonX, backButtonY, 52, 20))) return true;
        int y = panelY + 48;
        for (int index = 0; index < 6; index++, y += 27) {
            if (ModernUi.contains(click.x(), click.y(), panelX + 8, y, sidebarWidth - 16, 22)) return true;
        }
        return false;
    }

    private boolean inViewport(int y) {
        return y >= viewportY && y + 23 <= viewportY + viewportHeight;
    }

    private int actionWidth() {
        return Math.max(48, (contentWidth - 16) / 3);
    }

    private FormValidation validateForm() {
        String player = playerField == null ? "" : playerField.getText().trim();
        String playerError = null;
        String own = currentPlayerName();
        if (own.isBlank()) playerError = "Du musst mit einem Spieler verbunden sein.";
        else if (player.isBlank()) playerError = "Die andere Partei darf nicht leer sein.";
        else if (player.equalsIgnoreCase(own)) playerError = "Ein Deal mit dir selbst ist nicht möglich.";
        else if (!player.matches("[\\p{L}\\p{N}_-]{1,32}")) playerError = "Der Spieler enthält nicht unterstützte Zeichen.";
        else if (!entry.getPayments().isEmpty() && !player.equalsIgnoreCase(originalPlayer)) {
            playerError = "Mit vorhandenen Zahlungen kann die Gegenpartei nicht geändert werden.";
        }

        double parsedAmount = 0;
        String amountError = null;
        try {
            parsedAmount = FormatUtil.parseMoney(amountField == null ? "" : amountField.getText());
            if (parsedAmount + 0.0001D < entry.getPaidAmount()) amountError = "Der Betrag darf nicht kleiner als bereits bezahlt sein.";
        } catch (IllegalArgumentException exception) {
            amountError = "Bitte einen gültigen positiven Betrag eingeben.";
        }

        String dueError = null;
        String due = dueField == null ? "" : dueField.getText().trim();
        if (!due.isBlank() && TimeUtil.parseDueDate(due) == null) dueError = "Datum bitte als TT.MM.JJJJ eingeben.";
        return new FormValidation(playerError, amountError, dueError, parsedAmount);
    }

    private boolean isDirty() {
        if (playerField == null) return false;
        try {
            if (Math.abs(FormatUtil.parseMoney(amountField.getText()) - originalAmount) > 0.0001D) return true;
        } catch (IllegalArgumentException exception) {
            return true;
        }
        return !playerField.getText().trim().equalsIgnoreCase(originalPlayer)
                || !dueField.getText().trim().equals(originalDue)
                || !labelField.getText().trim().equals(originalLabel)
                || !noteField.getText().trim().equals(originalNote);
    }

    private void save() {
        FormValidation validation = validateForm();
        if (!isDirty()) {
            toastInfo("Es gibt keine Änderungen zu speichern.");
            return;
        }
        if (!validation.valid()) {
            toastError("Bitte die markierten Eingaben prüfen.");
            return;
        }
        try {
            Long dueDate = dueField.getText().isBlank() ? null : TimeUtil.parseDueDate(dueField.getText());
            String other = playerField.getText().trim();
            String own = currentPlayerName();
            entry = manager.updateCredit(entry.getId(), debts ? other : own, debts ? own : other, validation.amount(), dueDate,
                    blankToNull(labelField.getText()), blankToNull(noteField.getText()));
            toastSuccess("Deal aktualisiert.");
            closeToParent();
        } catch (CreditManager.CreditException exception) {
            toastError(exception.getMessage());
        }
    }

    private void refreshEntry() {
        if (entry != null && entry.getId() != null) manager.findCredit(entry.getId().toString()).ifPresent(value -> entry = value);
    }

    private String blankToNull(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private int statusColor(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> ModernUi.theme().success;
            case CreditManager.STATUS_PARTIAL -> ModernUi.theme().warning;
            case CreditManager.STATUS_CLOSED -> ModernUi.theme().muted;
            case CreditManager.STATUS_CANCELLED -> ModernUi.theme().muted;
            default -> ModernUi.theme().danger;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise bezahlt";
            case CreditManager.STATUS_CLOSED -> "Abgeschlossen";
            case CreditManager.STATUS_CANCELLED -> "Storniert";
            default -> "Offen";
        };
    }

    @Override
    protected void clearTransientState() {
        scroll.reset();
        discardArmed = false;
        super.clearTransientState();
    }

    private record FormValidation(String playerError, String amountError, String dueError, double amount) {
        private boolean valid() { return playerError == null && amountError == null && dueError == null; }
    }
}

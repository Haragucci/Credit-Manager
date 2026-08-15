package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.validation.CreditValidationRules;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.List;

public class ModernCreateCreditScreen extends ModernBaseScreen {

    private final boolean debts;
    private TextFieldWidget playerField;
    private TextFieldWidget amountField;
    private TextFieldWidget dueDateField;
    private TextFieldWidget labelField;
    private TextFieldWidget noteField;
    private final ModernScrollArea formScroll = new ModernScrollArea();
    private int fieldX;
    private int fieldWidth;
    private int viewportY;
    private int viewportHeight;
    private List<ModernLayout.Bounds> actionButtons = java.util.List.of();
    private final MutationSubmissionGuard submissionGuard = new MutationSubmissionGuard();

    public ModernCreateCreditScreen(CreditManager manager, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Neue Schuld" : "Neue Forderung", debts ? "debts" : "claims");
        this.debts = debts;
    }

    @Override
    protected void init() {
        String playerDraft = playerField == null ? "" : playerField.getText();
        String amountDraft = amountField == null ? "" : amountField.getText();
        String dueDraft = dueDateField == null ? "" : dueDateField.getText();
        String labelDraft = labelField == null ? "" : labelField.getText();
        String noteDraft = noteField == null ? "" : noteField.getText();
        super.init();
        clearChildren();
        fieldX = contentX + Math.min(8, Math.max(0, contentWidth / 4));
        fieldWidth = Math.max(1, contentWidth - (fieldX - contentX) * 2);
        playerField = addField(debts ? "Gläubiger *" : "Schuldner *", 32);
        amountField = addField("Betrag * (z.B. 2.5k)", 20);
        dueDateField = addField("Fällig am (TT.MM.JJJJ, optional)", 10);
        labelField = addField("Bezeichnung (optional)", CreditValidationRules.MAX_LABEL_LENGTH);
        noteField = addField("Notiz (optional)", CreditValidationRules.MAX_NOTE_LENGTH);
        playerField.setText(playerDraft);
        amountField.setText(amountDraft);
        dueDateField.setText(dueDraft);
        labelField.setText(labelDraft);
        noteField.setText(noteDraft);
    }

    private TextFieldWidget addField(String placeholder, int maxLength) {
        TextFieldWidget field = ModernUi.configureGuiTextField(
                new CenteredTextFieldWidget(textRenderer, fieldX + 5, contentY, Math.max(1, fieldWidth - 10), 19, Text.empty()));
        field.setMaxLength(maxLength);
        ModernUi.setGuiPlaceholder(field, placeholder);
        addDrawableChild(field);
        return field;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        viewportY = contentY + 4;
        viewportHeight = Math.max(24, contentHeight - 8);
        int actionHeight = ModernLayout.stack(fieldWidth, 2, 74, 8) ? 54 : 23;
        int formHeight = 5 * 45 + actionHeight + 8;
        formScroll.setBounds(fieldX, viewportY, fieldWidth, viewportHeight, formHeight);
        formScroll.tick(mouseX, mouseY);
        int y = viewportY - formScroll.offset();
        context.enableScissor(fieldX, viewportY, fieldX + fieldWidth, viewportY + viewportHeight);
        drawField(context, "Spieler", playerField, y);
        drawField(context, "Betrag", amountField, y + 45);
        drawField(context, "Fälligkeit", dueDateField, y + 90);
        drawField(context, "Bezeichnung", labelField, y + 135);
        drawField(context, "Notiz", noteField, y + 180);
        actionButtons = ModernLayout.buttonRow(fieldX, y + 225, fieldWidth, 2, 74, 23, 8);
        ModernLayout.Bounds save = actionButtons.getFirst();
        ModernLayout.Bounds cancel = actionButtons.get(1);
        ModernUi.button(context, textRenderer, save.x(), save.y(), save.width(), save.height(),
                submissionGuard.isActive() ? "Speichert…" : "Speichern",
                debts ? ModernUi.theme().buttonDanger : ModernUi.theme().buttonPrimary,
                !submissionGuard.isActive() && ModernUi.contains(mouseX, mouseY, save.x(), save.y(), save.width(), save.height()));
        ModernUi.button(context, textRenderer, cancel.x(), cancel.y(), cancel.width(), cancel.height(), "Abbrechen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, cancel.x(), cancel.y(), cancel.width(), cancel.height()));
        context.disableScissor();
        formScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawField(DrawContext context, String label, TextFieldWidget field, int y) {
        ModernUi.drawGuiText(context, textRenderer, label, fieldX, y, ModernUi.theme().muted);
        ModernUi.card(context, fieldX, y + 11, fieldWidth, 22, false);
        ModernLayout.positionTextField(field, fieldX + 5, y + 13, Math.max(1, fieldWidth - 10), viewportY, viewportHeight, true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (formScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0) {
            if (!actionButtons.isEmpty() && inViewport(actionButtons.getFirst()) && contains(click, actionButtons.getFirst())) {
                save();
                return true;
            }
            if (actionButtons.size() > 1 && inViewport(actionButtons.get(1)) && contains(click, actionButtons.get(1))) {
                closeToParent();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (formScroll.contains(mouseX, mouseY)) {
            formScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean contains(Click click, ModernLayout.Bounds bounds) {
        return ModernUi.contains(click.x(), click.y(), bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private boolean inViewport(ModernLayout.Bounds bounds) {
        return bounds.y() >= viewportY && bounds.bottom() <= viewportY + viewportHeight;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if ((input.getKeycode() == 257 || input.getKeycode() == 335) && !isTextInputFocused()) {
            save();
            return true;
        }
        return super.keyPressed(input);
    }

    private void save() {
        String otherPlayer = playerField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        String ownPlayer = currentPlayerName();
        if (ownPlayer.isBlank()) {
            toastError("Du musst mit einem Spieler verbunden sein.");
            return;
        }
        if (otherPlayer.isBlank()) {
            toastError("Der Spieler darf nicht leer sein.");
            return;
        }
        if (otherPlayer.equals(ownPlayer)) {
            toastError("Ein Deal mit dir selbst ist nicht möglich.");
            return;
        }
        long amountMinor;
        try {
            amountMinor = FormatUtil.parseMoneyMinor(amountField.getText());
        } catch (IllegalArgumentException exception) {
            toastError("Bitte einen gültigen positiven Betrag eingeben.");
            return;
        }
        Long dueDate = null;
        String dueDateText = dueDateField.getText().trim();
        if (!dueDateText.isEmpty()) {
            dueDate = TimeUtil.parseDueDate(dueDateText);
            if (dueDate == null) {
                toastError("Datum bitte als TT.MM.JJJJ eingeben.");
                return;
            }
        }
        String creditor = debts ? otherPlayer : ownPlayer;
        String debtor = debts ? ownPlayer : otherPlayer;
        String label = blankToNull(labelField.getText());
        String note = blankToNull(noteField.getText());
        Long requestedDueDate = dueDate;
        submitMutation(submissionGuard,
                () -> manager.createCreditMinor(creditor, debtor, amountMinor, requestedDueDate, label, note),
                (result, failure, screenCurrent) -> {
                    if (failure != null) {
                        toastError(failure.getMessage() == null ? "Deal konnte nicht gespeichert werden." : failure.getMessage());
                        return;
                    }
                    if (!showMutationCommitNotice(result.commitResult())) toastSuccess("Deal erstellt.");
                    if (!screenCurrent) return;
                    if (parent instanceof ModernCreditListScreen) closeToParent();
                    else open(new ModernCreditListScreen(manager, debts, null));
                });
    }

    private String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    protected void clearTransientState() {
        clearField(playerField);
        clearField(amountField);
        clearField(dueDateField);
        clearField(labelField);
        clearField(noteField);
        formScroll.reset();
        actionButtons = java.util.List.of();
        super.clearTransientState();
    }

    private void clearField(TextFieldWidget field) {
        if (field != null) field.setText("");
    }
}

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
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

public class ModernCreateCreditScreen extends ModernBaseScreen {

    private final boolean debts;
    private TextFieldWidget playerField;
    private TextFieldWidget amountField;
    private TextFieldWidget dueDateField;
    private TextFieldWidget labelField;
    private TextFieldWidget noteField;
    private int fieldX;
    private int fieldWidth;
    private int fieldStartY;
    private int fieldGap;
    private int actionY;

    public ModernCreateCreditScreen(CreditManager manager, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Neue Schuld" : "Neue Forderung", debts ? "debts" : "claims");
        this.debts = debts;
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        fieldX = contentX + 8;
        fieldWidth = Math.max(100, contentWidth - 16);
        fieldStartY = contentY + 6;
        fieldGap = Math.max(20, Math.min(29, (contentHeight - 59) / 4));
        playerField = addField(fieldX, fieldStartY, debts ? "Gläubiger *" : "Schuldner *", 32);
        amountField = addField(fieldX, fieldStartY + fieldGap, "Betrag * (z.B. 2.5k)", 20);
        dueDateField = addField(fieldX, fieldStartY + fieldGap * 2, "Fällig am (TT.MM.JJJJ, optional)", 10);
        labelField = addField(fieldX, fieldStartY + fieldGap * 3, "Bezeichnung (optional)", CreditValidationRules.MAX_LABEL_LENGTH);
        noteField = addField(fieldX, fieldStartY + fieldGap * 4, "Notiz (optional)", CreditValidationRules.MAX_NOTE_LENGTH);
        actionY = fieldStartY + fieldGap * 4 + 28;
    }

    private TextFieldWidget addField(int x, int y, String placeholder, int maxLength) {
        TextFieldWidget field = ModernUi.configureGuiTextField(
                new CenteredTextFieldWidget(textRenderer, x + 5, y + 8, fieldWidth - 10, 19, Text.empty()));
        field.setMaxLength(maxLength);
        ModernUi.setGuiPlaceholder(field, placeholder);
        addDrawableChild(field);
        return field;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        drawField(context, "Spieler", fieldX, fieldStartY);
        drawField(context, "Betrag", fieldX, fieldStartY + fieldGap);
        drawField(context, "Fälligkeit", fieldX, fieldStartY + fieldGap * 2);
        drawField(context, "Bezeichnung", fieldX, fieldStartY + fieldGap * 3);
        drawField(context, "Notiz", fieldX, fieldStartY + fieldGap * 4);
        int buttonWidth = Math.max(42, Math.min(132, (fieldWidth - 8) / 2));
        ModernUi.button(context, textRenderer, fieldX, actionY, buttonWidth, 23, "Speichern", debts ? ModernUi.theme().buttonDanger : ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, fieldX, actionY, buttonWidth, 23));
        ModernUi.button(context, textRenderer, fieldX + buttonWidth + 8, actionY, buttonWidth, 23, "Abbrechen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, fieldX + buttonWidth + 8, actionY, buttonWidth, 23));
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawField(DrawContext context, String label, int x, int y) {
        ModernUi.drawGuiText(context, textRenderer, label, x, y, ModernUi.theme().muted);
        ModernUi.card(context, x, y + 8, fieldWidth, 19, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0) {
            int buttonWidth = Math.max(42, Math.min(132, (fieldWidth - 8) / 2));
            if (ModernUi.contains(click.x(), click.y(), fieldX, actionY, buttonWidth, 23)) {
                save();
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), fieldX + buttonWidth + 8, actionY, buttonWidth, 23)) {
                closeToParent();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
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
        double amount;
        try {
            amount = FormatUtil.parseMoney(amountField.getText());
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
        try {
            manager.createCredit(debts ? otherPlayer : ownPlayer, debts ? ownPlayer : otherPlayer, amount, dueDate,
                    blankToNull(labelField.getText()), blankToNull(noteField.getText()));
            toastSuccess("Deal erstellt.");
            if (parent instanceof ModernCreditListScreen) {
                closeToParent();
            } else {
                open(new ModernCreditListScreen(manager, debts, null));
            }
        } catch (CreditManager.CreditException exception) {
            toastError(exception.getMessage());
        }
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
        super.clearTransientState();
    }

    private void clearField(TextFieldWidget field) {
        if (field != null) field.setText("");
    }
}

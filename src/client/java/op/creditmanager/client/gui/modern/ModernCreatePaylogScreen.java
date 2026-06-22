package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.Locale;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class ModernCreatePaylogScreen extends ModernBaseScreen {
    private TextFieldWidget payerField;
    private TextFieldWidget receiverField;
    private TextFieldWidget amountField;
    private TextFieldWidget dateField;
    private TextFieldWidget timeField;
    private TextFieldWidget noteField;
    private int formX, formWidth, saveY;

    public ModernCreatePaylogScreen(op.creditmanager.client.core.CreditManager manager, Screen parent) {
        super(manager, parent, "Paylog erfassen", "paylogs");
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        formX = contentX + 8;
        formWidth = Math.max(120, contentWidth - 16);
        payerField = field("Zahler", contentY + 33, "Spielername");
        receiverField = field("Empfänger", contentY + 82, "Spielername");
        amountField = field("Betrag", contentY + 131, "Betrag eingeben");
        amountField.setMaxLength(20);
        dateField = field("Datum", contentY + 180, "TT.MM.JJJJ");
        timeField = field("Uhrzeit", contentY + 229, "HH:MM");
        noteField = field("Notiz", contentY + 278, "Optionaler Rohtext");
        noteField.setMaxLength(256);
    }

    private TextFieldWidget field(String label, int y, String placeholder) {
        TextFieldWidget field = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, formX + 5, y, formWidth - 10, 20, Text.empty()));
        field.setMaxLength(32);
        ModernUi.setGuiPlaceholder(field, placeholder);
        addDrawableChild(field);
        return field;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        drawField(context, "Zahler", payerField.getY() - 20);
        drawField(context, "Empfänger", receiverField.getY() - 20);
        drawField(context, "Betrag", amountField.getY() - 20);
        drawField(context, "Datum", dateField.getY() - 20);
        drawField(context, "Uhrzeit", timeField.getY() - 20);
        drawField(context, "Notiz", noteField.getY() - 20);
        saveY = noteField.getY() + 32;
        int buttonWidth = Math.max(80, (formWidth - 8) / 2);
        ModernUi.button(context, textRenderer, formX, saveY, buttonWidth, 23, "Paylog speichern", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, formX, saveY, buttonWidth, 23));
        ModernUi.button(context, textRenderer, formX + buttonWidth + 8, saveY, buttonWidth, 23, "Abbrechen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, formX + buttonWidth + 8, saveY, buttonWidth, 23));
        ModernUi.drawTruncated(context, textRenderer, "Manuelle Paylogs werden nicht automatisch zugeordnet.", formX, saveY + 34, formWidth, ModernUi.theme().muted);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawField(DrawContext context, String label, int labelY) {
        ModernUi.drawGuiText(context, textRenderer, label, formX, labelY, ModernUi.theme().muted);
        ModernUi.card(context, formX, labelY + 12, formWidth, 25, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0) {
            int buttonWidth = Math.max(80, (formWidth - 8) / 2);
            if (ModernUi.contains(click.x(), click.y(), formX, saveY, buttonWidth, 23)) {
                save();
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), formX + buttonWidth + 8, saveY, buttonWidth, 23)) {
                closeToParent();
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void save() {
        String payer = payerField.getText().trim();
        String receiver = receiverField.getText().trim();
        if (!isValidPlayerName(payer) || !isValidPlayerName(receiver) || payer.equalsIgnoreCase(receiver)) {
            toastError("Zahler und Empfänger müssen gültige, verschiedene Spielernamen sein.");
            return;
        }
        try {
            double amount = FormatUtil.parseMoney(amountField.getText());
            TransactionEntry entry = new TransactionEntry(payer.toLowerCase(Locale.ROOT), receiver.toLowerCase(Locale.ROOT), amount);
            entry.setTimestamp(parseTimestamp());
            entry.setSource("MANUAL");
            String note = noteField.getText().trim();
            entry.setRawText("[Manuell] " + payer + " -> " + receiver + ": " + FormatUtil.formatAmount(amount)
                    + " am " + DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").format(java.time.Instant.ofEpochMilli(entry.getTimestamp()).atZone(ZoneId.systemDefault()))
                    + (note.isBlank() ? "" : " | " + note));
            if (!TransactionRepository.getInstance().add(entry)) {
                toastError("Paylog konnte nicht gespeichert werden.");
                return;
            }
            toastSuccess("Manueller Paylog gespeichert.");
            closeToParent();
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            toastError("Bitte Betrag, Datum und Uhrzeit prüfen.");
        }
    }

    private long parseTimestamp() {
        LocalDate date = dateField.getText().isBlank() ? LocalDate.now() : parseDate(dateField.getText().trim());
        LocalTime time = timeField.getText().isBlank() ? LocalTime.now().withSecond(0).withNano(0) : LocalTime.parse(timeField.getText().trim(), DateTimeFormatter.ofPattern("H:mm"));
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDate parseDate(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ignored) { return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd.MM.uuuu")); }
    }

    private boolean isValidPlayerName(String value) {
        if (value.isBlank() || value.length() > 32) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean asciiLetter = character >= 'A' && character <= 'Z' || character >= 'a' && character <= 'z';
            boolean digit = character >= '0' && character <= '9';
            if (!asciiLetter && !digit && character != '_') return false;
        }
        return true;
    }
}

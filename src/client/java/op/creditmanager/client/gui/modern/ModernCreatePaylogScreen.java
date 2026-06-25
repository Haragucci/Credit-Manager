package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class ModernCreatePaylogScreen extends ModernBaseScreen {
    private static final int FIELD_HEIGHT = 20;
    private static final int FIELD_STEP = 49;
    private final ModernScrollArea formScroll = new ModernScrollArea();
    private TextFieldWidget payerField;
    private TextFieldWidget receiverField;
    private TextFieldWidget amountField;
    private TextFieldWidget dateField;
    private TextFieldWidget timeField;
    private TextFieldWidget noteField;
    private int formX, formWidth, viewportY, viewportHeight, buttonY;
    private List<ModernLayout.Bounds> buttons = List.of();

    public ModernCreatePaylogScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Paylog erfassen", "paylogs");
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        payerField = field("Spielername", 32);
        receiverField = field("Spielername", 32);
        amountField = field("Betrag eingeben", 20);
        dateField = field("TT.MM.JJJJ", 32);
        timeField = field("HH:MM", 16);
        noteField = field("Optionaler Rohtext", 256);
    }

    private TextFieldWidget field(String placeholder, int maxLength) {
        TextFieldWidget field = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, 0, 0, 1, FIELD_HEIGHT, Text.empty()));
        field.setMaxLength(maxLength);
        ModernUi.setGuiPlaceholder(field, placeholder);
        addDrawableChild(field);
        return field;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        formX = contentX;
        formWidth = Math.max(1, contentWidth);
        buttons = ModernLayout.buttonRow(formX, 0, formWidth, 2, 92, 23, 8);
        int buttonHeight = ModernLayout.rowHeight(buttons, 0);
        buttonY = contentY + contentHeight - buttonHeight;
        viewportY = contentY + 4;
        viewportHeight = Math.max(28, buttonY - viewportY - 8);
        formScroll.setBounds(formX, viewportY, formWidth, viewportHeight, FIELD_STEP * 6 + 8);
        formScroll.tick(mouseX, mouseY);
        int top = viewportY - formScroll.offset();
        context.enableScissor(formX, viewportY, formX + formWidth, viewportY + viewportHeight);
        drawField(context, mouseX, mouseY, "Zahler", payerField, top);
        drawField(context, mouseX, mouseY, "Empfänger", receiverField, top + FIELD_STEP);
        drawField(context, mouseX, mouseY, "Betrag", amountField, top + FIELD_STEP * 2);
        drawField(context, mouseX, mouseY, "Datum", dateField, top + FIELD_STEP * 3);
        drawField(context, mouseX, mouseY, "Uhrzeit", timeField, top + FIELD_STEP * 4);
        drawField(context, mouseX, mouseY, "Notiz / Rohtext", noteField, top + FIELD_STEP * 5);
        context.disableScissor();
        formScroll.renderScrollbar(context, mouseX, mouseY);

        for (int index = 0; index < buttons.size(); index++) {
            ModernLayout.Bounds bounds = buttons.get(index);
            ModernLayout.Bounds placed = new ModernLayout.Bounds(bounds.x(), buttonY + bounds.y(), bounds.width(), bounds.height());
            String label = index == 0 ? "Paylog speichern" : "Abbrechen";
            int color = index == 0 ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral;
            ModernUi.button(context, textRenderer, placed.x(), placed.y(), placed.width(), placed.height(), label, color,
                    ModernUi.contains(mouseX, mouseY, placed.x(), placed.y(), placed.width(), placed.height()));
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawField(DrawContext context, int mouseX, int mouseY, String label, TextFieldWidget field, int y) {
        ModernUi.drawGuiText(context, textRenderer, label, formX + 7, y, ModernUi.theme().muted);
        ModernUi.card(context, formX, y + 12, formWidth - (formScroll.isScrollable() ? 8 : 0), 28, false);
        ModernLayout.positionTextField(field, formX + 5, y + 16, Math.max(1, formWidth - 10 - (formScroll.isScrollable() ? 8 : 0)), viewportY, viewportHeight, true);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (formScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0) {
            for (int index = 0; index < buttons.size(); index++) {
                ModernLayout.Bounds bounds = buttons.get(index);
                ModernLayout.Bounds placed = new ModernLayout.Bounds(bounds.x(), buttonY + bounds.y(), bounds.width(), bounds.height());
                if (!ModernUi.contains(click.x(), click.y(), placed.x(), placed.y(), placed.width(), placed.height())) continue;
                if (index == 0) save(); else closeToParent();
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

    @Override
    protected void clearTransientState() {
        formScroll.reset();
        super.clearTransientState();
    }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.ItemStackStorage;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modern payment form with multi-item inventory selection and one shared value. */
public class ModernPaymentScreen extends ModernBaseScreen {

    private static final int INVENTORY_COLUMNS = 9;
    private static final int INVENTORY_ROWS = 4;
    private static final int INVENTORY_SLOT_SIZE = 22;

    private final CreditEntry entry;
    private final boolean debts;
    private final Map<Integer, Integer> selectedInventorySlots = new LinkedHashMap<>();
    private final ModernScrollArea formScroll = new ModernScrollArea();

    private TextFieldWidget amountField;
    private boolean itemMode;
    private int fieldX;
    private int fieldWidth;
    private int modeY;
    private int saveY;
    private int inventoryX;
    private int inventoryY;
    private int visibleInventoryRows;
    private int inventoryScrollRows;
    private String itemHint;
    private int formViewportY;
    private int formViewportHeight;

    public ModernPaymentScreen(CreditManager manager, CreditEntry entry, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Zahlung leisten" : "Zahlung empfangen", debts ? "debts" : "claims");
        this.entry = entry;
        this.debts = debts;
    }

    @Override
    protected void init() {
        super.init();
        if (contentHeight < 184) {
            panelY = 2;
            panelHeight = height - 4;
            contentY = panelY + 38;
            contentHeight = panelHeight - 42;
        }
        clearChildren();
        fieldX = contentX + 8;
        fieldWidth = Math.max(100, contentWidth - 16);
        modeY = contentY;
        amountField = ModernUi.configureGuiTextField(
                new CenteredTextFieldWidget(textRenderer, fieldX + 5, modeY + 63, fieldWidth - 10, 19, Text.empty()));
        amountField.setMaxLength(20);
        ModernUi.setGuiPlaceholder(amountField, itemMode ? "Gemeinsamer Wert aller Items" : "Betrag eingeben");
        addDrawableChild(amountField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        formViewportY = contentY;
        formViewportHeight = Math.max(28, contentHeight);
        int formContentHeight = itemMode ? 204 : 122;
        formScroll.setBounds(fieldX, formViewportY, fieldWidth, formViewportHeight, formContentHeight);
        formScroll.tick(mouseX, mouseY);
        modeY = formViewportY - formScroll.offset();
        int fieldY = modeY + 63;
        amountField.setPosition(fieldX + 5, fieldY);
        amountField.setVisible(fieldY + 19 > formViewportY && fieldY < formViewportY + formViewportHeight);
        context.enableScissor(fieldX, formViewportY, fieldX + fieldWidth, formViewportY + formViewportHeight);
        drawModeAndAmount(context, mouseX, mouseY);

        if (itemMode) {
            drawItemSelection(context, mouseX, mouseY);
        } else {
            drawMoneyActions(context, mouseX, mouseY);
        }
        context.disableScissor();
        formScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawModeAndAmount(DrawContext context, int mouseX, int mouseY) {
        ModernUi.card(context, fieldX, modeY, fieldWidth, 49, false);
        ModernUi.drawGuiText(context, textRenderer, "Zahlungsart", fieldX + 10, modeY + 9, ModernUi.theme().muted);
        int tabWidth = Math.max(40, (fieldWidth - 30) / 2);
        ModernUi.button(context, textRenderer, fieldX + 10, modeY + 22, tabWidth, 20, "Geld", itemMode ? ModernUi.theme().buttonNeutral : ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, fieldX + 10, modeY + 22, tabWidth, 20));
        ModernUi.button(context, textRenderer, fieldX + 20 + tabWidth, modeY + 22, tabWidth, 20, "Items", itemMode ? ModernUi.theme().buttonGold : ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, fieldX + 20 + tabWidth, modeY + 22, tabWidth, 20));
        String amountLabel = itemMode && itemHint != null ? itemHint : itemMode ? "Gemeinsamer Item-Wert" : "Betrag";
        ModernUi.drawTruncated(context, textRenderer, amountLabel, fieldX, modeY + 55, fieldWidth,
                itemMode && itemHint != null ? ModernUi.theme().warning : ModernUi.theme().muted);
        ModernUi.card(context, fieldX, modeY + 63, fieldWidth, 19, false);
    }

    private void drawMoneyActions(DrawContext context, int mouseX, int mouseY) {
        saveY = modeY + 96;
        int buttonWidth = Math.max(42, Math.min(132, (fieldWidth - 8) / 2));
        ModernUi.button(context, textRenderer, fieldX, saveY, buttonWidth, 23, "Zahlung speichern", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, fieldX, saveY, buttonWidth, 23));
        ModernUi.button(context, textRenderer, fieldX + buttonWidth + 8, saveY, buttonWidth, 23, "Abbrechen", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, fieldX + buttonWidth + 8, saveY, buttonWidth, 23));
    }

    private void drawItemSelection(DrawContext context, int mouseX, int mouseY) {
        saveY = modeY + 88;
        int buttonWidth = Math.max(42, Math.min(132, (fieldWidth - 8) / 2));
        ModernUi.button(context, textRenderer, fieldX, saveY, buttonWidth, 20,
                "Speichern (" + selectedInventorySlots.size() + ")", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, fieldX, saveY, buttonWidth, 20));
        ModernUi.button(context, textRenderer, fieldX + buttonWidth + 8, saveY, buttonWidth, 20, "Auswahl leeren", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, fieldX + buttonWidth + 8, saveY, buttonWidth, 20));

        inventoryY = saveY + 24;
        inventoryX = fieldX + Math.max(0, (fieldWidth - INVENTORY_COLUMNS * INVENTORY_SLOT_SIZE) / 2);
        visibleInventoryRows = INVENTORY_ROWS;

        PlayerInventory inventory = playerInventory();
        if (inventory == null) {
            ModernUi.drawTruncated(context, textRenderer, "Inventar nicht verfügbar.", fieldX, inventoryY, fieldWidth, ModernUi.theme().danger);
            return;
        }

        for (int row = 0; row < visibleInventoryRows; row++) {
            int inventoryRow = row + inventoryScrollRows;
            for (int column = 0; column < INVENTORY_COLUMNS; column++) {
                int slot = inventoryRow * INVENTORY_COLUMNS + column;
                int x = inventoryX + column * INVENTORY_SLOT_SIZE;
                int y = inventoryY + row * INVENTORY_SLOT_SIZE;
                drawInventorySlot(context, mouseX, mouseY, inventory.getStack(slot), slot, x, y);
            }
        }
    }

    private void drawInventorySlot(DrawContext context, int mouseX, int mouseY, ItemStack stack, int slot, int x, int y) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, INVENTORY_SLOT_SIZE, INVENTORY_SLOT_SIZE);
        ModernUi.card(context, x, y, INVENTORY_SLOT_SIZE, INVENTORY_SLOT_SIZE, hovered);
        if (!stack.isEmpty()) {
            context.drawItem(stack, x + 3, y + 3);
        }
        if (selectedInventorySlots.containsKey(slot)) {
            context.fill(x + 2, y + 2, x + INVENTORY_SLOT_SIZE - 2, y + INVENTORY_SLOT_SIZE - 2, ModernUi.theme().selection);
            context.fill(x + 2, y + 2, x + INVENTORY_SLOT_SIZE - 2, y + 3, ModernUi.theme().accent);
        }
        if (hovered && !stack.isEmpty()) {
            context.drawItemTooltip(textRenderer, stack, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (formScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (!formScroll.contains(click.x(), click.y())) return super.mouseClicked(click, doubled);
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        int tabWidth = Math.max(40, (fieldWidth - 30) / 2);
        if (ModernUi.contains(click.x(), click.y(), fieldX + 10, modeY + 22, tabWidth, 20)) {
            itemMode = false;
            itemHint = null;
            ModernUi.setGuiPlaceholder(amountField, "Betrag eingeben");
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), fieldX + 20 + tabWidth, modeY + 22, tabWidth, 20)) {
            itemMode = true;
            itemHint = null;
            ModernUi.setGuiPlaceholder(amountField, "Gemeinsamer Wert aller Items");
            return true;
        }

        int buttonWidth = Math.max(42, Math.min(132, (fieldWidth - 8) / 2));
        int itemButtonHeight = itemMode ? 20 : 23;
        if (ModernUi.contains(click.x(), click.y(), fieldX, saveY, buttonWidth, itemButtonHeight)) {
            savePayment();
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), fieldX + buttonWidth + 8, saveY, buttonWidth, itemButtonHeight)) {
            if (itemMode) {
                selectedInventorySlots.clear();
                itemHint = null;
            } else {
                closeToParent();
            }
            return true;
        }
        if (itemMode && ModernUi.contains(click.x(), click.y(), inventoryX, inventoryY,
                INVENTORY_COLUMNS * INVENTORY_SLOT_SIZE, visibleInventoryRows * INVENTORY_SLOT_SIZE)) {
            int column = (int) ((click.x() - inventoryX) / INVENTORY_SLOT_SIZE);
            int row = (int) ((click.y() - inventoryY) / INVENTORY_SLOT_SIZE) + inventoryScrollRows;
            int slot = row * INVENTORY_COLUMNS + column;
            PlayerInventory inventory = playerInventory();
            if (inventory != null) {
                toggleInventorySlot(slot, inventory.getStack(slot));
            }
            return true;
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

    private void toggleInventorySlot(int slot, ItemStack stack) {
        if (stack.isEmpty()) return;
        if (selectedInventorySlots.containsKey(slot)) {
            selectedInventorySlots.remove(slot);
        } else {
            selectedInventorySlots.put(slot, stack.getCount());
        }
        itemHint = null;
    }

    private void savePayment() {
        if (currentPlayerName().isBlank()) {
            toastError("Du musst mit einem Spieler verbunden sein.");
            return;
        }
        double amount;
        try {
            amount = FormatUtil.parseMoney(amountField.getText());
        } catch (IllegalArgumentException exception) {
            toastError("Bitte einen gültigen positiven Betrag eingeben.");
            return;
        }
        try {
            if (itemMode) {
                saveSelectedItems(amount);
            } else {
                manager.addMoneyPayment(entry.getId(), currentPlayerName(), amount);
            }
            toastSuccess(itemMode ? "Item-Zahlung gespeichert." : "Zahlung gespeichert.");
            closeToParent();
        } catch (CreditManager.CreditException exception) {
            toastError(exception.getMessage());
        }
    }

    private void saveSelectedItems(double sharedValue) throws CreditManager.CreditException {
        PlayerInventory inventory = playerInventory();
        if (inventory == null || selectedInventorySlots.isEmpty()) {
            throw new CreditManager.CreditException("Bitte mindestens ein Item aus dem Inventar auswählen.");
        }

        List<String> descriptions = new ArrayList<>();
        List<String> serializedStacks = new ArrayList<>();
        for (Map.Entry<Integer, Integer> selection : selectedInventorySlots.entrySet()) {
            ItemStack stack = inventory.getStack(selection.getKey());
            if (stack.isEmpty()) continue;
            int count = Math.max(1, Math.min(selection.getValue(), stack.getCount()));
            String serialized = ItemStackStorage.serialize(stack, count);
            if (serialized == null || serialized.isBlank()) continue;
            descriptions.add(count + "x " + stack.getName().getString());
            serializedStacks.add(serialized);
        }
        if (descriptions.isEmpty()) {
            throw new CreditManager.CreditException("Die ausgewählten Items sind nicht mehr im Inventar.");
        }
        manager.addItemPayment(entry.getId(), currentPlayerName(), descriptions, sharedValue, serializedStacks);
    }

    private PlayerInventory playerInventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? null : client.player.getInventory();
    }

    @Override
    protected void clearTransientState() {
        itemMode = false;
        itemHint = null;
        inventoryScrollRows = 0;
        formScroll.reset();
        selectedInventorySlots.clear();
        if (amountField != null) amountField.setText("");
        super.clearTransientState();
    }
}

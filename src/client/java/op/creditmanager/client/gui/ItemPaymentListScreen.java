package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;

/** Classic item-payment overview which lets the player choose an item to inspect. */
public class ItemPaymentListScreen extends BasisScreen {

    private static final int ITEMS_PER_PAGE = 28;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;

    private final Payment payment;
    private final net.minecraft.client.gui.screen.Screen parent;
    private final List<ItemStack> stacks;
    private int page;

    public ItemPaymentListScreen(Payment payment, net.minecraft.client.gui.screen.Screen parent) {
        super(Text.literal("CreditManager » Item-Zahlung"), 6);
        this.payment = payment;
        this.parent = parent;
        this.stacks = ItemInspektionScreen.resolvePaymentStacks(payment);
    }

    @Override
    protected void fülleSlots() {
        for (int slot = 0; slot < anzahlSlots; slot++) {
            setSlot(slot, GuiHelper.schwarzGlas());
        }

        setSlot(4, summaryItem());
        int start = page * ITEMS_PER_PAGE;
        for (int index = start; index < stacks.size() && index < start + ITEMS_PER_PAGE; index++) {
            int visibleIndex = index - start;
            int row = visibleIndex / 7 + 1;
            int column = visibleIndex % 7 + 1;
            setSlot(row * 9 + column, itemForSlot(stacks.get(index), index));
        }

        setSlot(SLOT_BACK, GuiHelper.zurückButton());
        if (page > 0) {
            setSlot(SLOT_PREVIOUS, GuiHelper.erstelleItem(new ItemStack(Items.ARROW), "§a← Zurück", "§7Vorherige Seite"));
        }
        setSlot(SLOT_PAGE, GuiHelper.erstelleItem(new ItemStack(Items.BOOK), "§7Seite " + (page + 1) + " / " + pageCount(),
                "§7Klicke ein Item zum Inspizieren an."));
        if (page < pageCount() - 1) {
            setSlot(SLOT_NEXT, GuiHelper.erstelleItem(new ItemStack(Items.ARROW), "§aNächste Seite →", "§7Weitere Items anzeigen"));
        }
    }

    private ItemStack summaryItem() {
        double value = payment.getAmount() == null ? 0.0 : payment.getAmount();
        return GuiHelper.erstelleItem(new ItemStack(Items.CHEST), "§e§lItem-Zahlung",
                "§7Ausgewählte Items: §f" + stacks.size(),
                "§7Gemeinsamer Wert: §6" + FormatUtil.formatAmount(value),
                "", "§eKlicke ein Item zum Inspizieren an.");
    }

    private ItemStack itemForSlot(ItemStack source, int index) {
        ItemStack stack = source.copy();
        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7Item " + (index + 1) + " von " + stacks.size()));
        lore.add(Text.literal("§7Anzahl: §f" + stack.getCount()));
        lore.add(Text.literal(""));
        lore.add(Text.literal("§eKlicken zum Inspizieren"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        if (slot == SLOT_BACK) {
            MinecraftClient.getInstance().setScreen(parent);
            return true;
        }
        if (slot == SLOT_PREVIOUS && page > 0) {
            page--;
            fülleSlots();
            return true;
        }
        if (slot == SLOT_NEXT && page < pageCount() - 1) {
            page++;
            fülleSlots();
            return true;
        }

        int row = slot / 9 - 1;
        int column = slot % 9 - 1;
        if (row >= 0 && row < 4 && column >= 0 && column < 7) {
            int index = page * ITEMS_PER_PAGE + row * 7 + column;
            if (index < stacks.size()) {
                MinecraftClient.getInstance().setScreen(new ItemInspektionScreen(payment, index, this));
                return true;
            }
        }
        return false;
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil((double) stacks.size() / ITEMS_PER_PAGE));
    }
}

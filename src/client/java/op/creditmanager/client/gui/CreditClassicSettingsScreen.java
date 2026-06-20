package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.config.GuiMode;
import op.creditmanager.client.core.CreditManager;

/** Classic settings access for GUI and automatic Paylog behaviour. */
public class CreditClassicSettingsScreen extends BasisScreen {

    private static final int SLOT_MODE = 10;
    private static final int SLOT_RESET = 12;
    private static final int SLOT_AUTODETECT = 14;
    private static final int SLOT_OVERLAY = 16;
    private static final int SLOT_NOTIFICATIONS = 22;
    private static final int SLOT_BACK = 24;

    private final CreditManager manager;
    private final net.minecraft.client.gui.screen.Screen parent;

    public CreditClassicSettingsScreen(CreditManager manager, net.minecraft.client.gui.screen.Screen parent) {
        super(Text.literal("CreditManager » Einstellungen"), 3);
        this.manager = manager;
        this.parent = parent;
    }

    @Override
    protected void fülleSlots() {
        for (int i = 0; i < anzahlSlots; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        setSlot(SLOT_MODE, item(Items.COMPARATOR, "§b§lModern GUI aktivieren",
                "§7Wechselt direkt zur modernen Oberfläche.", "", "§7Aktuell: §f" + modeLabel(ClientConfigManager.getGuiMode())));
        setSlot(SLOT_RESET, item(Items.CLOCK, "§e§lGUI-Auswahl zurücksetzen",
                "§7Beim nächsten Öffnen erscheint die Auswahl erneut."));
        setSlot(SLOT_AUTODETECT, toggleItem(Items.COMPASS, "§d§lPaylogs automatisch erkennen",
                ClientConfigManager.isAutomaticPaylogDetection()));
        setSlot(SLOT_OVERLAY, toggleItem(Items.FEATHER, "§b§lOverlay-Nachrichten prüfen",
                ClientConfigManager.isDetectPaylogsInOverlay()));
        setSlot(SLOT_NOTIFICATIONS, toggleItem(Items.BELL, "§a§lPaylog-Benachrichtigungen",
                ClientConfigManager.isShowPaylogNotifications()));
        setSlot(SLOT_BACK, GuiHelper.zurückButton());
    }

    private ItemStack toggleItem(net.minecraft.item.Item type, String title, boolean enabled) {
        return item(type, title, "§7Status: " + (enabled ? "§aAN" : "§cAUS"), "", "§eKlicken zum Umschalten");
    }

    private ItemStack item(net.minecraft.item.Item type, String title, String... lore) {
        ItemStack stack = new ItemStack(type);
        stack.set(DataComponentTypes.ITEM_NAME, Text.literal(title));
        stack.set(DataComponentTypes.LORE, new LoreComponent(java.util.Arrays.stream(lore).<Text>map(Text::literal).toList()));
        return stack;
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        if (slot == SLOT_BACK) {
            MinecraftClient.getInstance().setScreen(parent);
            return true;
        }
        if (slot == SLOT_MODE) {
            GuiRouter.selectModeAndOpen(manager, GuiMode.MODERN);
            return true;
        }
        if (slot == SLOT_RESET) {
            ClientConfigManager.setGuiMode(GuiMode.UNSELECTED);
            fülleSlots();
            return true;
        }
        if (slot == SLOT_AUTODETECT) {
            ClientConfigManager.setAutomaticPaylogDetection(!ClientConfigManager.isAutomaticPaylogDetection());
            fülleSlots();
            return true;
        }
        if (slot == SLOT_OVERLAY) {
            ClientConfigManager.setDetectPaylogsInOverlay(!ClientConfigManager.isDetectPaylogsInOverlay());
            fülleSlots();
            return true;
        }
        if (slot == SLOT_NOTIFICATIONS) {
            ClientConfigManager.setShowPaylogNotifications(!ClientConfigManager.isShowPaylogNotifications());
            fülleSlots();
            return true;
        }
        return false;
    }

    private String modeLabel(GuiMode mode) {
        return switch (mode) {
            case CLASSIC -> "Classic GUI";
            case MODERN -> "Modern GUI";
            case UNSELECTED -> "Nicht ausgewählt";
        };
    }
}

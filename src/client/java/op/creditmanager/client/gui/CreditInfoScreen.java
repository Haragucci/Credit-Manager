package op.creditmanager.client.gui;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

public class CreditInfoScreen extends BasisScreen {

    private static final int SLOT_VERSION   = 11;
    private static final int SLOT_ERSTELLER = 13;
    private static final int SLOT_KONTAKT   = 15;
    private static final int SLOT_HINWEIS   = 20;
    private static final int SLOT_STATUS    = 22;
    private static final int SLOT_ZURÜCK    = 18;

    private final Screen elternScreen;

    public CreditInfoScreen(Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fInfo"), 3);
        this.elternScreen = elternScreen;
    }

    @Override
    protected void fülleSlots() {
        for (int i = 0; i < anzahlSlots; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        setSlot(SLOT_VERSION, erstelleVersionItem());
        setSlot(SLOT_ERSTELLER, erstelleErstellerItem());
        setSlot(SLOT_KONTAKT, erstelleKontaktItem());
        setSlot(SLOT_HINWEIS, erstelleHinweisItem());
        setSlot(SLOT_STATUS, erstelleStatusItem());
        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
    }

    private ItemStack erstelleVersionItem() {
        String modName = getModName();
        String modVersion = getModVersion();
        String minecraftVersion = getCurrentMinecraftVersion();

        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lVersion"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("7.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Mod: §f" + modName),
                Text.literal("§7Version: §f" + modVersion),
                Text.literal("§7Minecraft: §f" + minecraftVersion),
                Text.literal("")
        )));

        return item;
    }

    private ItemStack erstelleErstellerItem() {
        ItemStack item = new ItemStack(Items.PLAYER_HEAD);

        SkinHeadUtil.setzeSkin(item, "05Haragucci", this::fülleSlots);
        SkinHeadUtil.versteckeProfilTooltip(item);

        item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§d§lErsteller"));
        item.remove(DataComponentTypes.ITEM_NAME);

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Entwickelt von: §f05Haragucci"),
                Text.literal("§7Projekt: §fCreditManager"),
                Text.literal(""),
                Text.literal("§8Inoffizielles Projekt")
        )));

        return item;
    }

    private ItemStack erstelleKontaktItem() {
        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§a§lKontakt"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("7.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Ingame: §f05Haragucci"),
                Text.literal("§7Discord: §fharagucci"),
                Text.literal(""),
                Text.literal("§7GitHub:"),
                Text.literal("§9github.com/Haragucci/Credit-Manager")
        )));

        return item;
    }

    private ItemStack erstelleHinweisItem() {
        ItemStack item = new ItemStack(Items.BOOK);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lHinweis"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7CreditManager wurde für die"),
                Text.literal("§7Nutzung auf §fOPSUCHT.net §7entwickelt."),
                Text.literal(""),
                Text.literal("§cNicht offiziell verbunden mit:"),
                Text.literal("§8- §7OPSUCHT.net"),
                Text.literal("§8- §7Mojang"),
                Text.literal("§8- §7Microsoft"),
                Text.literal("§8- §7Fabric")
        )));

        return item;
    }

    private ItemStack erstelleStatusItem() {
        ItemStack item = new ItemStack(Items.RED_DYE);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§e§lStatus"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Aktueller Status: §eBeta"),
                Text.literal(""),
                Text.literal("§7Die Mod ist grundsätzlich"),
                Text.literal("§7funktionsfähig, kann aber"),
                Text.literal("§7noch Fehler enthalten.")
        )));

        return item;
    }

    private String getModName() {
        return FabricLoader.getInstance()
                .getModContainer("creditmanager")
                .map(container -> container.getMetadata().getName())
                .orElse("CreditManager");
    }

    private String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("creditmanager")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("Unbekannt");
    }

    private String getCurrentMinecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("Unbekannt");
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        if (slot == SLOT_ZURÜCK) {
            MinecraftClient.getInstance().setScreen(elternScreen);
            return true;
        }

        return false;
    }
}
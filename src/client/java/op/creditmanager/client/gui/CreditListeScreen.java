package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class CreditListeScreen extends BasisScreen {

    private static final int DEAL_SLOTS_PRO_SEITE = 4 * 7;

    private static final int SLOT_ZURÜCK = 45;
    private static final int SLOT_NEU    = 47;
    private static final int SLOT_FILTER = 49;
    private static final int SLOT_PREV   = 51;
    private static final int SLOT_NEXT   = 53;

    private enum StatusFilter {
        ALLE("§7Alle", Items.HOPPER),
        OFFEN("§cOffen", Items.HOPPER),
        TEILWEISE("§eTeilweise", Items.HOPPER),
        BEZAHLT("§aBezahlt", Items.HOPPER),
        STORNIERT("§8Storniert", Items.HOPPER);

        final String label;
        final net.minecraft.item.Item icon;

        StatusFilter(String label, net.minecraft.item.Item icon) {
            this.label = label;
            this.icon  = icon;
        }
    }

    private final CreditManager manager;
    private final boolean        istSchulden;
    private final Screen         elternScreen;

    private List<CreditEntry> alleEinträge = new ArrayList<>();
    private List<CreditEntry> einträge     = new ArrayList<>();
    private int               seite        = 0;
    private StatusFilter      statusFilter = StatusFilter.ALLE;

    public CreditListeScreen(CreditManager manager, boolean istSchulden, Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §f" + (istSchulden ? "Schulden" : "Forderungen")), 6);
        this.manager      = manager;
        this.istSchulden  = istSchulden;
        this.elternScreen = elternScreen;
    }

    @Override
    protected void fülleSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        String ich = client.player != null ? client.player.getName().getString().toLowerCase() : "";

        alleEinträge = istSchulden
                ? manager.getAllCreditsAsDebtor(ich)
                : manager.getAllCreditsAsCreditor(ich);

        alleEinträge.sort(Comparator.comparingLong(CreditEntry::getCreatedAt).reversed());

        anwenden();
    }

    private void anwenden() {
        einträge = switch (statusFilter) {
            case OFFEN     -> alleEinträge.stream()
                    .filter(e -> "OPEN".equals(e.getStatus()))
                    .collect(Collectors.toList());
            case TEILWEISE -> alleEinträge.stream()
                    .filter(e -> "PARTIAL".equals(e.getStatus()))
                    .collect(Collectors.toList());
            case BEZAHLT   -> alleEinträge.stream()
                    .filter(e -> "PAID".equals(e.getStatus()))
                    .collect(Collectors.toList());
            case STORNIERT -> alleEinträge.stream()
                    .filter(e -> "CANCELLED".equals(e.getStatus()))
                    .collect(Collectors.toList());
            default        -> new ArrayList<>(alleEinträge);
        };

        for (int i = 0; i < anzahlSlots; i++) setSlot(i, null);

        for (int i = 0;  i < 9;  i++) setSlot(i,  GuiHelper.schwarzGlas());
        for (int i = 45; i < 54; i++) setSlot(i,  GuiHelper.schwarzGlas());

        for (int r = 1; r <= 4; r++) {
            setSlot(r * 9,     GuiHelper.schwarzGlas());
            setSlot(r * 9 + 8, GuiHelper.schwarzGlas());
        }

        setSlot(4, erstelleKopfItem());

        int start   = seite * DEAL_SLOTS_PRO_SEITE;
        int slotIdx = 0;
        for (int i = start; i < einträge.size() && slotIdx < DEAL_SLOTS_PRO_SEITE; i++) {
            int reihe  = slotIdx / 7;
            int spalte = slotIdx % 7;
            int slot   = (reihe + 1) * 9 + spalte + 1;
            setSlot(slot, erstelleDealKopf(einträge.get(i)));
            slotIdx++;
        }

        if (einträge.isEmpty()) {
            setSlot(22, erstelleLeerItem());
        }

        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
        setSlot(SLOT_NEU, erstelleNeuItem());
        setSlot(SLOT_FILTER, erstelleFilterItem());

        if (seite > 0) {
            setSlot(SLOT_PREV, GuiHelper.erstelleItem(
                    new ItemStack(Items.LIME_STAINED_GLASS_PANE),
                    "§a◀ Vorherige Seite",
                    "§7Seite " + seite + " von " + maxSeiten()));
        }
        if (seite < maxSeiten() - 1) {
            setSlot(SLOT_NEXT, GuiHelper.erstelleItem(
                    new ItemStack(Items.LIME_STAINED_GLASS_PANE),
                    "§aNächste Seite ▶",
                    "§7Seite " + (seite + 2) + " von " + maxSeiten()));
        }
    }


    private ItemStack erstelleKopfItem() {
        String titel = istSchulden ? "§c§lSchulden" : "§a§lForderungen";

        long offen = einträge.stream()
                .filter(e -> !"PAID".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
                .count();
        double gesamtOffen = einträge.stream()
                .filter(e -> !"PAID".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
                .mapToDouble(CreditEntry::getRemainingAmount).sum();

        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of(istSchulden ? "2.0" : "1.0"), List.of()));

        return GuiHelper.erstelleItem(
                item,
                titel,
                "§7Gesamt: §f" + einträge.size() + " Deals",
                "§7Offen:  §f" + offen + " Deals",
                "§7Summe:  " + (istSchulden ? "§c" : "§a") + FormatUtil.formatiereBetrag(gesamtOffen),
                "",
                "§7Filter: " + statusFilter.label
        );
    }


    private ItemStack erstelleNeuItem() {
        ItemStack neuItem = new ItemStack(Items.FEATHER);
        neuItem.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("6.0"), List.of()));

        if (istSchulden) {
            return GuiHelper.erstelleItem(
                    neuItem,
                    "§c§l+ Neue Schuld eintragen",
                    "§7Öffnet das Formular zum",
                    "§7Eintragen einer neuen Schuld",
                    "",
                    "§eKlicken");
        } else {
            return GuiHelper.erstelleItem(
                    neuItem,
                    "§a§l+ Neue Forderung eintragen",
                    "§7Öffnet das Formular zum",
                    "§7Eintragen einer neuen Forderung",
                    "",
                    "§eKlicken");
        }
    }

    private ItemStack erstelleFilterItem() {
        List<String> zeilen = new ArrayList<>();
        zeilen.add("§7Klicken zum Durchschalten");
        zeilen.add("");
        for (StatusFilter f : StatusFilter.values()) {
            String markierung = (f == statusFilter) ? "§8▸ " : "§7  ";
            zeilen.add(markierung + f.label);
        }
        zeilen.add("");
        zeilen.add("§eKlicken zum Wechseln");

        ItemStack item = new ItemStack(statusFilter.icon);
        item.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of()));

        item.set(DataComponentTypes.ITEM_NAME,
                Text.literal("§7Filter: " + statusFilter.label));
        return item;
    }

    private ItemStack erstelleLeerItem() {
        String beschreibung = switch (statusFilter) {
            case OFFEN     -> "§7Keine offenen Deals vorhanden.";
            case TEILWEISE -> "§7Keine teilweise bezahlten Deals.";
            case BEZAHLT   -> "§7Noch keine abgeschlossenen Deals.";
            case STORNIERT -> "§7Keine stornierten Deals.";
            default        -> istSchulden
                    ? "§7Du hast keine eingetragenen Schulden."
                    : "§7Du hast keine eingetragenen Forderungen.";
        };
        return GuiHelper.erstelleItem(
                new ItemStack(Items.BARRIER),
                "§cKeine Einträge",
                beschreibung,
                "",
                "§8Nutze §f+ Neu§8 um einen Deal anzulegen."
        );
    }

    private ItemStack erstelleDealKopf(CreditEntry e) {
        String gegenspieler = istSchulden ? e.getCreditor() : e.getDebtor();

        ItemStack kopf = new ItemStack(Items.PLAYER_HEAD);

        SkinHeadUtil.setzeSkin(kopf, gegenspieler, this::anwenden);
        kopf.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§f" + e.getDealName()));
        SkinHeadUtil.versteckeProfilTooltip(kopf);

        String statusText = switch (e.getStatus()) {
            case "PAID"      -> "§aBezahlt ✔";
            case "PARTIAL"   -> "§eTeilweise bezahlt";
            case "CANCELLED" -> "§8Storniert";
            default          -> "§cOffen";
        };

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(""));

        if (istSchulden) {
            lore.add(Text.literal("§7Du schuldest:"));
            lore.add(Text.literal("  §c§l" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
        } else {
            lore.add(Text.literal("§7Ausstehend:"));
            lore.add(Text.literal("  §a§l" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
        }

        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Bezahlt: §f" + FormatUtil.formatiereBetrag(e.getPaidAmount())));
        lore.add(Text.literal("§7Gesamt:  §f" + FormatUtil.formatiereBetrag(e.getAmount())));
        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Status: " + statusText));

        if (e.getDueDate() != null) {
            lore.add(Text.literal("§7Fällig: " + TimeUtil.getDueDateDisplay(e.getDueDate())));
        }
        if (e.getNote() != null && !e.getNote().isBlank()) {
            lore.add(Text.literal("§7Notiz:  §9" + e.getNote()));
        }
        lore.add(Text.literal(""));
        lore.add(Text.literal("§eKlicken für Details & Aktionen"));

        kopf.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return kopf;
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_NEU) {
            client.setScreen(new CreditNeuScreen(manager, istSchulden, this));
            return true;
        }

        if (slot == SLOT_FILTER) {
            StatusFilter[] values = StatusFilter.values();
            statusFilter = values[(statusFilter.ordinal() + 1) % values.length];
            seite = 0;
            anwenden();
            return true;
        }

        if (slot == SLOT_PREV && seite > 0) {
            seite--;
            anwenden();
            return true;
        }

        if (slot == SLOT_NEXT && seite < maxSeiten() - 1) {
            seite++;
            anwenden();
            return true;
        }

        int reihe  = slot / 9 - 1;
        int spalte = slot % 9 - 1;
        if (reihe >= 0 && reihe <= 3 && spalte >= 0 && spalte <= 6) {
            int dealIdx = seite * DEAL_SLOTS_PRO_SEITE + reihe * 7 + spalte;
            if (dealIdx < einträge.size()) {
                client.setScreen(new CreditDealScreen(manager, einträge.get(dealIdx), istSchulden, this));
                return true;
            }
        }

        return false;
    }

    private int maxSeiten() {
        return Math.max(1, (int) Math.ceil((double) einträge.size() / DEAL_SLOTS_PRO_SEITE));
    }
}
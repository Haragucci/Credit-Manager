package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

public class CreditPaylogFilterScreen extends BasisScreen {

    private static final int SLOT_SPIELER  = 11;
    private static final int SLOT_ZEITRAUM = 13;
    private static final int SLOT_ANWENDEN = 15;
    private static final int SLOT_RESET    = 22;
    private static final int SLOT_ZURÜCK   = 18;

    private final CreditPaylogScreen elternScreen;

    private List<String> spielerListe = new ArrayList<>();
    private String ausgewählterSpielerFilter;
    private ZeitraumPreset zeitraumPreset = ZeitraumPreset.ALLE;

    private enum ZeitraumPreset {
        ALLE("Alle", null),
        HEUTE("Heute", "heute"),
        DREI_TAGE("Letzte 3 Tage", "3t"),
        EINE_WOCHE("Letzte 7 Tage", "7t"),
        ZWEI_WOCHEN("Letzte 2 Wochen", "2w"),
        EIN_MONAT("Letzter Monat", "1m");

        final String label;
        final String commandWert;

        ZeitraumPreset(String label, String commandWert) {
            this.label = label;
            this.commandWert = commandWert;
        }

        ZeitraumPreset next() {
            ZeitraumPreset[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public CreditPaylogFilterScreen(CreditPaylogScreen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fFilter"), 3);
        this.elternScreen = elternScreen;
        this.ausgewählterSpielerFilter = elternScreen.getErweiterterSpielerFilter();

        String aktiverZeitraum = elternScreen.getErweiterterZeitraumLabel();
        for (ZeitraumPreset preset : ZeitraumPreset.values()) {
            if (preset.label.equalsIgnoreCase(aktiverZeitraum)) {
                zeitraumPreset = preset;
                break;
            }
        }
    }

    @Override
    protected void init() {
        spielerListe = elternScreen.getBekanntePaylogSpieler();
        super.init();
    }

    @Override
    protected void fülleSlots() {
        for (int i = 0; i < anzahlSlots; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        setSlot(SLOT_SPIELER, erstelleSpielerItem());
        setSlot(SLOT_ZEITRAUM, erstelleZeitraumItem());
        setSlot(SLOT_ANWENDEN, erstelleAnwendenItem());
        setSlot(SLOT_RESET, erstelleResetItem());
        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
    }

    private ItemStack erstelleSpielerItem() {
        String spielerAnzeige = getAktuellerSpielerAnzeige();

        ItemStack item = new ItemStack(Items.PLAYER_HEAD);

        if (ausgewählterSpielerFilter == null) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                item.set(
                        DataComponentTypes.PROFILE,
                        net.minecraft.component.type.ProfileComponent.ofStatic(client.player.getGameProfile())
                );
            }
            SkinHeadUtil.versteckeProfilTooltip(item);
        } else {
            SkinHeadUtil.setzeSkin(item, ausgewählterSpielerFilter, this::fülleSlots);
            SkinHeadUtil.versteckeProfilTooltip(item);
        }

        item.set(DataComponentTypes.CUSTOM_NAME, Text.literal("§b§lSpieler-Filter"));
        item.remove(DataComponentTypes.ITEM_NAME);

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Aktuell: §f" + spielerAnzeige),
                Text.literal(""),
                Text.literal("§7Filtert nach Transaktionen,"),
                Text.literal("§7bei denen dieser Spieler beteiligt ist."),
                Text.literal(""),
                Text.literal("§eKlicken zum Suchen")
        )));

        return item;
    }

    private ItemStack erstelleZeitraumItem() {
        ItemStack item = new ItemStack(Items.CLOCK);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§e§lZeitraum"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Aktuell: §f" + zeitraumPreset.label),
                Text.literal(""),
                Text.literal("§7Wert: §f" + (zeitraumPreset.commandWert == null ? "kein Zeitraum" : zeitraumPreset.commandWert)),
                Text.literal(""),
                Text.literal("§eKlicken zum Wechseln")
        )));

        return item;
    }

    private ItemStack erstelleAnwendenItem() {
        ItemStack item = new ItemStack(Items.LIME_DYE);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§a§lFilter anwenden"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Spieler: §f" + getAktuellerSpielerAnzeige()),
                Text.literal("§7Zeitraum: §f" + zeitraumPreset.label),
                Text.literal(""),
                Text.literal("§eKlicken zum Anwenden")
        )));

        return item;
    }

    private ItemStack erstelleResetItem() {
        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lFilter zurücksetzen"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("8.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Setzt Spieler und Zeitraum"),
                Text.literal("§7wieder auf Standard zurück."),
                Text.literal(""),
                Text.literal("§eKlicken zum Zurücksetzen")
        )));

        return item;
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_SPIELER) {
            client.setScreen(new CreditPaylogSpielerSuchScreen(this, spielerListe, ausgewählterSpielerFilter));
            return true;
        }

        if (slot == SLOT_ZEITRAUM) {
            zeitraumPreset = zeitraumPreset.next();
            fülleSlots();
            return true;
        }

        if (slot == SLOT_ANWENDEN) {
            FilterZeitraum zeitraum = berechneZeitraum(zeitraumPreset);

            elternScreen.setErweiterterFilter(
                    ausgewählterSpielerFilter,
                    zeitraum.vonMs,
                    zeitraum.bisMs,
                    zeitraumPreset.label
            );

            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_RESET) {
            ausgewählterSpielerFilter = null;
            zeitraumPreset = ZeitraumPreset.ALLE;
            elternScreen.resetErweiterterFilter();
            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        return false;
    }

    public void setSpielerFilterAuswahl(String spielerName) {
        if (spielerName == null || spielerName.isBlank()
                || spielerName.equalsIgnoreCase("Du selbst")
                || spielerName.equalsIgnoreCase("ich")
                || spielerName.equalsIgnoreCase("me")) {
            ausgewählterSpielerFilter = null;
        } else {
            ausgewählterSpielerFilter = spielerName;
        }

        spielerListe = elternScreen.getBekanntePaylogSpieler();
        fülleSlots();
    }

    private String getAktuellerSpielerAnzeige() {
        return ausgewählterSpielerFilter == null
                ? "Du selbst"
                : ausgewählterSpielerFilter;
    }

    private FilterZeitraum berechneZeitraum(ZeitraumPreset preset) {
        long now = System.currentTimeMillis();

        if (preset == ZeitraumPreset.ALLE) {
            return new FilterZeitraum(null, null);
        }

        if (preset == ZeitraumPreset.HEUTE) {
            long start = LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();

            long ende = start + 86_400_000L - 1;
            return new FilterZeitraum(start, ende);
        }

        long dauer = switch (preset) {
            case DREI_TAGE -> 3L * 86_400_000L;
            case EINE_WOCHE -> 7L * 86_400_000L;
            case ZWEI_WOCHEN -> 14L * 86_400_000L;
            case EIN_MONAT -> 30L * 86_400_000L;
            default -> 0L;
        };

        if (dauer <= 0L) {
            return new FilterZeitraum(null, null);
        }

        return new FilterZeitraum(now - dauer, now);
    }

    private record FilterZeitraum(Long vonMs, Long bisMs) {
    }
}
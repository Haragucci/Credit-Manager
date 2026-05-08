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
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.*;
import java.util.stream.Collectors;

public class CreditPaylogScreen extends BasisScreen {

    private static final int EINTRÄGE_PRO_SEITE = 4 * 7;
    private static final int MAX_EINTRÄGE       = 500;

    private static final int SLOT_ZURÜCK  = 45;
    private static final int SLOT_FILTER  = 49;
    private static final int SLOT_PREV    = 48;
    private static final int SLOT_NEXT    = 50;
    private static final int SLOT_ERWEITERTER_FILTER = 52;

    private List<TransactionEntry> alleTransaktionen      = new ArrayList<>();

    private enum Filter { ALLE, EINGEHEND, AUSGEHEND }

    private final CreditManager manager;
    private final Screen elternScreen;
    private List<TransactionEntry> gefilterteTransaktionen = new ArrayList<>();
    private int    seite  = 0;
    private Filter filter = Filter.ALLE;
    private String ich;
    private String erweiterterSpielerFilter = null;
    private Long erweiterterVonMs = null;
    private Long erweiterterBisMs = null;
    private String erweiterterZeitraumLabel = "Alle";

    public CreditPaylogScreen(CreditManager manager, Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fPaylogs"), 6);
        this.manager      = manager;
        this.elternScreen = elternScreen;
    }

    @Override
    protected void fülleSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        ich = client.player != null ? client.player.getName().getString().toLowerCase() : "";

        alleTransaktionen = TransactionRepository.getInstance().getAll().stream()
                .sorted(Comparator.comparingLong(TransactionEntry::getTimestamp).reversed())
                .limit(MAX_EINTRÄGE)
                .collect(Collectors.toList());

        anwenden();
    }

    private void anwenden() {
        List<TransactionEntry> basis = alleTransaktionen.stream()
                .filter(t -> {
                    if (erweiterterSpielerFilter != null && !erweiterterSpielerFilter.isBlank()) {
                        return t.getFromPlayer().equalsIgnoreCase(erweiterterSpielerFilter)
                                || t.getToPlayer().equalsIgnoreCase(erweiterterSpielerFilter);
                    }

                    return t.getFromPlayer().equalsIgnoreCase(ich)
                            || t.getToPlayer().equalsIgnoreCase(ich);
                })
                .filter(t -> erweiterterVonMs == null || t.getTimestamp() >= erweiterterVonMs)
                .filter(t -> erweiterterBisMs == null || t.getTimestamp() <= erweiterterBisMs)
                .collect(Collectors.toList());

        gefilterteTransaktionen = switch (filter) {
            case EINGEHEND -> basis.stream()
                    .filter(t -> t.getToPlayer().equalsIgnoreCase(ich))
                    .collect(Collectors.toList());
            case AUSGEHEND -> basis.stream()
                    .filter(t -> t.getFromPlayer().equalsIgnoreCase(ich))
                    .collect(Collectors.toList());
            default -> new ArrayList<>(basis);
        };

        for (int i = 0; i < anzahlSlots; i++) setSlot(i, null);

        for (int i = 0;  i < 9;  i++) setSlot(i,  GuiHelper.schwarzGlas());
        for (int i = 45; i < 54; i++) setSlot(i,  GuiHelper.schwarzGlas());
        for (int r = 1;  r <= 4; r++) {
            setSlot(r * 9,     GuiHelper.schwarzGlas());
            setSlot(r * 9 + 8, GuiHelper.schwarzGlas());
        }

        setSlot(4, erstelleKopfItem());

        int start   = seite * EINTRÄGE_PRO_SEITE;
        int slotIdx = 0;

        for (int i = start; i < gefilterteTransaktionen.size() && slotIdx < EINTRÄGE_PRO_SEITE; i++) {
            int reihe  = slotIdx / 7;
            int spalte = slotIdx % 7;
            int slot   = (reihe + 1) * 9 + spalte + 1;

            setSlot(slot, erstelleTransaktionItem(gefilterteTransaktionen.get(i)));
            slotIdx++;
        }

        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
        setSlot(SLOT_FILTER, erstelleFilterItem());
        setSlot(SLOT_ERWEITERTER_FILTER, erstelleErweiterterFilterItem());

        if (seite > 0) {
            ItemStack prev = new ItemStack(Items.ARROW);

            prev.set(DataComponentTypes.ITEM_NAME,
                    Text.literal("§a◀ Vorherige Seite"));

            prev.set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("2.0"), List.of())
            );

            prev.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("§7Seite " + seite + " von " + maxSeiten())
            )));

            setSlot(SLOT_PREV, prev);
        }

        if (seite < maxSeiten() - 1) {
            ItemStack next = new ItemStack(Items.ARROW);

            next.set(DataComponentTypes.ITEM_NAME,
                    Text.literal("§aNächste Seite ▶"));

            next.set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of())
            );

            next.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("§7Seite " + (seite + 2) + " von " + maxSeiten())
            )));

            setSlot(SLOT_NEXT, next);
        }

        if (gefilterteTransaktionen.isEmpty()) {
            setSlot(22, erstelleLeerItem());
        }
    }

    private ItemStack erstelleErweiterterFilterItem() {
        ItemStack item = new ItemStack(Items.FEATHER);

        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§d§lErweiterter Filter"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("8.0"), List.of())
        );

        String spieler = erweiterterSpielerFilter == null
                ? "§7Du selbst"
                : "§f" + erweiterterSpielerFilter;

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Filtert Paylogs genauer."),
                Text.literal(""),
                Text.literal("§7Spieler: §f" + spieler),
                Text.literal("§7Zeitraum: §f" + erweiterterZeitraumLabel),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));

        return item;
    }

    private ItemStack erstelleKopfItem() {
        long gesamt   = alleTransaktionen.size();
        long sichtbar = gefilterteTransaktionen.size();

        String filterAnzeige = switch (filter) {
            case EINGEHEND -> "§aEingehend";
            case AUSGEHEND -> "§cAusgehend";
            default        -> "§7Alle";
        };

        double sumEin = gefilterteTransaktionen.stream()
                .filter(t -> t.getToPlayer().equalsIgnoreCase(ich))
                .mapToDouble(TransactionEntry::getAmount).sum();
        double sumAus = gefilterteTransaktionen.stream()
                .filter(t -> t.getFromPlayer().equalsIgnoreCase(ich))
                .mapToDouble(TransactionEntry::getAmount).sum();

        return GuiHelper.erstelleItem(
                new ItemStack(Items.BOOK),
                "§b§lPaylogs",
                "§7Automatisch erkannte Zahlungen",
                "",
                "§7Einträge: §f" + sichtbar + " §8/ " + gesamt,
                "§7Filter:   " + filterAnzeige,
                "§7Spieler:  " + (erweiterterSpielerFilter == null ? "§fDu selbst" : "§f" + erweiterterSpielerFilter),
                "§7Zeitraum: §f" + erweiterterZeitraumLabel,
                "",
                "§7Eingehend: §a+" + FormatUtil.formatiereBetrag(sumEin),
                "§7Ausgehend: §c-" + FormatUtil.formatiereBetrag(sumAus)
        );
    }

    private ItemStack erstelleFilterItem() {
        ItemStack stack = switch (filter) {
            case ALLE -> GuiHelper.erstelleItem(
                    new ItemStack(Items.HOPPER),
                    "§7Filter: §fAlle",
                    "§7Zeigt alle Transaktionen",
                    "",
                    "§8▸ Alle",
                    "§7  Eingehend",
                    "§7  Ausgehend",
                    "",
                    "§eKlicken zum Wechseln");

            case EINGEHEND -> GuiHelper.erstelleItem(
                    new ItemStack(Items.HOPPER),
                    "§aFilter: §fEingehend",
                    "§7Zeigt nur empfangene Zahlungen",
                    "",
                    "§7  Alle",
                    "§8▸ Eingehend",
                    "§7  Ausgehend",
                    "",
                    "§eKlicken zum Wechseln");

            case AUSGEHEND -> GuiHelper.erstelleItem(
                    new ItemStack(Items.HOPPER),
                    "§cFilter: §fAusgehend",
                    "§7Zeigt nur gesendete Zahlungen",
                    "",
                    "§7  Alle",
                    "§7  Eingehend",
                    "§8▸ Ausgehend",
                    "",
                    "§eKlicken zum Wechseln");
        };

        stack.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of())
        );

        return stack;
    }

    private ItemStack erstelleTransaktionItem(TransactionEntry t) {
        boolean ichSender = t.getFromPlayer().equalsIgnoreCase(ich);

        ItemStack stack = new ItemStack(Items.FEATHER);

        String richtung     = ichSender ? "§c▶ Gesendet" : "§a◀ Empfangen";
        String gegenspieler = ichSender ? t.getToPlayer() : t.getFromPlayer();
        String betragFarbe  = ichSender ? "§c-" : "§a+";
        String zeitStr      = TimeUtil.formatDatumZeit(t.getTimestamp());

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(richtung));
        lore.add(Text.literal(""));
        lore.add(Text.literal("§7" + (ichSender ? "An:    " : "Von:   ") + "§f" + gegenspieler));
        lore.add(Text.literal("§7Betrag: " + betragFarbe + "§l" + FormatUtil.formatiereBetrag(t.getAmount())));
        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Zeit: §8" + zeitStr));

        stack.set(DataComponentTypes.ITEM_NAME,
                Text.literal(betragFarbe + FormatUtil.formatiereBetrag(t.getAmount())));

        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));

        if (ichSender) {
            stack.set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("2.0"), List.of())
            );
        } else {
            stack.set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of())
            );
        }

        return stack;
    }

    private ItemStack erstelleLeerItem() {
        String filterHinweis = switch (filter) {
            case EINGEHEND -> "§7Keine eingehenden Transaktionen gefunden.";
            case AUSGEHEND -> "§7Keine ausgehenden Transaktionen gefunden.";
            default        -> "§7Noch keine Transaktionen aufgezeichnet.";
        };
        return GuiHelper.erstelleItem(
                new ItemStack(Items.BARRIER),
                "§cKeine Einträge",
                filterHinweis,
                "",
                "§8Transaktionen werden automatisch",
                "§8beim Zahlen im Chat erkannt."
        );
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_FILTER) {
            filter = switch (filter) {
                case ALLE      -> Filter.EINGEHEND;
                case EINGEHEND -> Filter.AUSGEHEND;
                case AUSGEHEND -> Filter.ALLE;
            };
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

        if (slot == SLOT_ERWEITERTER_FILTER) {
            client.setScreen(new CreditPaylogFilterScreen(this));
            return true;
        }

        return false;
    }

    public void setErweiterterFilter(String spielerFilter, Long vonMs, Long bisMs, String zeitraumLabel) {
        this.erweiterterSpielerFilter = spielerFilter == null || spielerFilter.isBlank()
                ? null
                : spielerFilter;

        this.erweiterterVonMs = vonMs;
        this.erweiterterBisMs = bisMs;

        this.erweiterterZeitraumLabel = zeitraumLabel == null || zeitraumLabel.isBlank()
                ? "Alle"
                : zeitraumLabel;

        this.seite = 0;
        anwenden();
    }

    public void resetErweiterterFilter() {
        this.erweiterterSpielerFilter = null;
        this.erweiterterVonMs = null;
        this.erweiterterBisMs = null;
        this.erweiterterZeitraumLabel = "Alle";

        this.seite = 0;
        anwenden();
    }

    public String getErweiterterSpielerFilter() {
        return erweiterterSpielerFilter;
    }

    public String getErweiterterZeitraumLabel() {
        return erweiterterZeitraumLabel;
    }

    public List<String> getBekanntePaylogSpieler() {
        java.util.Map<String, String> namen = new java.util.LinkedHashMap<>();

        for (TransactionEntry t : TransactionRepository.getInstance().getAll()) {
            addPaylogSpielerName(namen, t.getFromPlayer());
            addPaylogSpielerName(namen, t.getToPlayer());
        }

        return namen.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private void addPaylogSpielerName(java.util.Map<String, String> namen, String name) {
        if (name == null || name.isBlank()) return;

        String clean = name.trim();

        if (clean.equalsIgnoreCase("ich")
                || clean.equalsIgnoreCase("me")
                || clean.equalsIgnoreCase("Du selbst")) {
            return;
        }

        String key = clean.toLowerCase(java.util.Locale.ROOT);
        namen.putIfAbsent(key, clean);
    }


    private int maxSeiten() {
        return Math.max(1, (int) Math.ceil((double) gefilterteTransaktionen.size() / EINTRÄGE_PRO_SEITE));
    }
}
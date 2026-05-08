package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreditÜbersichtScreen extends BasisScreen {

    private static final Pattern BETRAG_PATTERN = Pattern.compile("[\\d][\\d.,]*");

    private static final int SLOT_HEADER       = 4;

    private static final int SLOT_FORDERUNGEN  = 11;
    private static final int SLOT_SALDO        = 13;
    private static final int SLOT_SCHULDEN     = 15;

    private static final int SLOT_KONTOSTAND   = 20;
    private static final int SLOT_PROGNOSE     = 22;
    private static final int SLOT_STATUS       = 24;

    private static final int SLOT_LISTE_FORD   = 29;
    private static final int SLOT_LISTE_SCHULD = 31;
    private static final int SLOT_PAYLOGS      = 33;

    private static final int SLOT_TIPP         = 40;
    private static final int SLOT_ZURÜCK       = 45;

    private final CreditManager manager;
    private final Screen elternScreen;

    public CreditÜbersichtScreen(CreditManager manager, Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fÜbersicht"), 6);
        this.manager = manager;
        this.elternScreen = elternScreen;
    }

    @Override
    protected void fülleSlots() {
        MinecraftClient client = MinecraftClient.getInstance();

        String ich = client.player != null
                ? client.player.getName().getString().toLowerCase()
                : "";

        List<CreditEntry> schulden = manager.getOpenCreditsAsDebtor(ich);
        List<CreditEntry> forderungen = manager.getOpenCreditsAsCreditor(ich);

        double gesamtSchulden = schulden.stream()
                .mapToDouble(CreditEntry::getRemainingAmount)
                .sum();

        double gesamtForderungen = forderungen.stream()
                .mapToDouble(CreditEntry::getRemainingAmount)
                .sum();

        double saldo = gesamtForderungen - gesamtSchulden;

        Double kontostand = getKontostand(client);
        Double nachVerrechnung = kontostand != null
                ? kontostand + saldo
                : null;

        fülleGrundLayout();

        setSlot(SLOT_HEADER, erstelleHeaderItem(
                forderungen,
                schulden,
                gesamtForderungen,
                gesamtSchulden,
                saldo
        ));

        setSlot(SLOT_FORDERUNGEN, erstelleForderungenItem(forderungen, gesamtForderungen));
        setSlot(SLOT_SALDO, erstelleSaldoItem(gesamtForderungen, gesamtSchulden, saldo));
        setSlot(SLOT_SCHULDEN, erstelleSchuldenItem(schulden, gesamtSchulden));

        setSlot(SLOT_KONTOSTAND, erstelleKontostandItem(kontostand));
        setSlot(SLOT_PROGNOSE, erstellePrognoseItem(kontostand, saldo, nachVerrechnung));
        setSlot(SLOT_STATUS, erstelleStatusItem(
                saldo,
                gesamtForderungen,
                gesamtSchulden,
                kontostand,
                nachVerrechnung
        ));

        setSlot(SLOT_LISTE_FORD, erstelleShortcutItem(
                new ItemStack(Items.LIME_DYE),
                "§a§lForderungen öffnen",
                "§7Alle offenen Forderungen",
                "§7übersichtlich anzeigen.",
                "",
                "§eKlicken zum Öffnen"
        ));

        setSlot(SLOT_LISTE_SCHULD, erstelleShortcutItem(
                new ItemStack(Items.RED_DYE),
                "§c§lSchulden öffnen",
                "§7Alle offenen Schulden",
                "§7übersichtlich anzeigen.",
                "",
                "§eKlicken zum Öffnen"
        ));

        setSlot(SLOT_PAYLOGS, erstellePaylogShortcutItem());

        setSlot(SLOT_TIPP, erstelleTippItem(forderungen, schulden, saldo));
        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
    }

    private void fülleGrundLayout() {
        for (int i = 0; i < anzahlSlots; i++) {
            setSlot(i, null);
        }

        for (int i = 0; i < 9; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        for (int i = 36; i < 45; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        for (int i = 45; i < 54; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        for (int r = 1; r <= 4; r++) {
            setSlot(r * 9, GuiHelper.schwarzGlas());
            setSlot(r * 9 + 8, GuiHelper.schwarzGlas());
        }

        setSlot(10, GuiHelper.trennGlas());
        setSlot(12, GuiHelper.trennGlas());
        setSlot(14, GuiHelper.trennGlas());
        setSlot(16, GuiHelper.trennGlas());

        setSlot(19, GuiHelper.trennGlas());
        setSlot(21, GuiHelper.trennGlas());
        setSlot(23, GuiHelper.trennGlas());
        setSlot(25, GuiHelper.trennGlas());

        setSlot(28, GuiHelper.trennGlas());
        setSlot(30, GuiHelper.trennGlas());
        setSlot(32, GuiHelper.trennGlas());
        setSlot(34, GuiHelper.trennGlas());

        setSlot(39, GuiHelper.trennGlas());
        setSlot(41, GuiHelper.trennGlas());
    }

    private ItemStack erstelleHeaderItem(List<CreditEntry> forderungen,
                                         List<CreditEntry> schulden,
                                         double gesamtForderungen,
                                         double gesamtSchulden,
                                         double saldo) {
        int offeneDeals = forderungen.size() + schulden.size();

        String saldoFarbe = saldo >= 0 ? "§a" : "§c";
        String saldoPrefix = saldo >= 0 ? "+" : "";

        ItemStack item = new ItemStack(Items.BOOK);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lFinanzübersicht"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Offene Deals: §f" + offeneDeals),
                Text.literal("§7Forderungen: §a+" + FormatUtil.formatiereBetrag(gesamtForderungen)),
                Text.literal("§7Schulden:    §c-" + FormatUtil.formatiereBetrag(gesamtSchulden)),
                Text.literal(""),
                Text.literal("§7Saldo: " + saldoFarbe + "§l" + saldoPrefix + FormatUtil.formatiereBetrag(saldo)),
                Text.literal(""),
                Text.literal("§8Forderungen − Schulden")
        )));

        return item;
    }

    private ItemStack erstelleForderungenItem(List<CreditEntry> forderungen, double gesamtForderungen) {
        List<Text> lore = new ArrayList<>();

        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Offen: §f" + forderungen.size()));
        lore.add(Text.literal("§7Gesamt: §a§l" + FormatUtil.formatiereBetrag(gesamtForderungen)));
        lore.add(Text.literal(""));

        if (forderungen.isEmpty()) {
            lore.add(Text.literal("§7Keine offenen Forderungen."));
            lore.add(Text.literal("§8Du wartest aktuell auf kein Geld."));
        } else {
            lore.add(Text.literal("§7Größte Forderungen:"));

            for (CreditEntry e : topEinträge(forderungen, 4)) {
                lore.add(Text.literal("§8▸ §f" + kürze(e.getDealName(), 18)));
                lore.add(Text.literal("  §7von §f" + e.getDebtor()
                        + " §a" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
            }

            if (forderungen.size() > 4) {
                lore.add(Text.literal("§8… und " + (forderungen.size() - 4) + " weitere"));
            }
        }

        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§a§lForderungen"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return item;
    }

    private ItemStack erstelleSchuldenItem(List<CreditEntry> schulden, double gesamtSchulden) {
        List<Text> lore = new ArrayList<>();

        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Offen: §f" + schulden.size()));
        lore.add(Text.literal("§7Gesamt: §c§l" + FormatUtil.formatiereBetrag(gesamtSchulden)));
        lore.add(Text.literal(""));

        if (schulden.isEmpty()) {
            lore.add(Text.literal("§a✔ Keine offenen Schulden."));
            lore.add(Text.literal("§8Du bist aktuell schuldenfrei."));
        } else {
            lore.add(Text.literal("§7Größte Schulden:"));

            for (CreditEntry e : topEinträge(schulden, 4)) {
                lore.add(Text.literal("§8▸ §f" + kürze(e.getDealName(), 18)));
                lore.add(Text.literal("  §7an §f" + e.getCreditor()
                        + " §c" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
            }

            if (schulden.size() > 4) {
                lore.add(Text.literal("§8… und " + (schulden.size() - 4) + " weitere"));
            }
        }

        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lSchulden"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("2.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return item;
    }

    private ItemStack erstelleSaldoItem(double gesamtForderungen, double gesamtSchulden, double saldo) {
        String saldoFarbe = saldo >= 0 ? "§a" : "§c";
        String saldoPrefix = saldo >= 0 ? "+" : "";

        ItemStack item = new ItemStack(saldo >= 0 ? Items.EMERALD : Items.REDSTONE);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lSaldo"));

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Forderungen: §a+" + FormatUtil.formatiereBetrag(gesamtForderungen)),
                Text.literal("§7Schulden:    §c-" + FormatUtil.formatiereBetrag(gesamtSchulden)),
                Text.literal(""),
                Text.literal("§7Ergebnis: " + saldoFarbe + "§l" + saldoPrefix + FormatUtil.formatiereBetrag(saldo)),
                Text.literal(""),
                saldo >= 0
                        ? Text.literal("§aDu bist insgesamt im Plus.")
                        : Text.literal("§cDu bist insgesamt im Minus.")
        )));

        return item;
    }

    private ItemStack erstelleKontostandItem(Double kontostand) {
        ItemStack item = new ItemStack(Items.GOLD_INGOT);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lKontostand"));

        if (kontostand == null) {
            item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal(""),
                    Text.literal("§cNicht gefunden"),
                    Text.literal(""),
                    Text.literal("§7Der Kontostand konnte nicht"),
                    Text.literal("§7aus dem Scoreboard gelesen werden."),
                    Text.literal(""),
                    Text.literal("§8Nur möglich, wenn der Server"),
                    Text.literal("§8den Kontostand in der Sidebar zeigt.")
            )));
        } else {
            item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal(""),
                    Text.literal("§7Aktuell: §6§l" + FormatUtil.formatiereBetrag(kontostand)),
                    Text.literal(""),
                    Text.literal("§8Aus dem Scoreboard gelesen")
            )));
        }

        return item;
    }

    private ItemStack erstellePrognoseItem(Double kontostand, double saldo, Double nachVerrechnung) {
        ItemStack item = new ItemStack(Items.COMPASS);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lNach Verrechnung"));

        if (kontostand == null || nachVerrechnung == null) {
            item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal(""),
                    Text.literal("§cKeine Prognose möglich"),
                    Text.literal(""),
                    Text.literal("§7Dafür muss dein Kontostand"),
                    Text.literal("§7im Scoreboard sichtbar sein.")
            )));
            return item;
        }

        String farbe = nachVerrechnung >= 0 ? "§a" : "§c";
        String saldoFarbe = saldo >= 0 ? "§a+" : "§c";

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Kontostand: §6" + FormatUtil.formatiereBetrag(kontostand)),
                Text.literal("§7Saldo:      " + saldoFarbe + FormatUtil.formatiereBetrag(saldo)),
                Text.literal(""),
                Text.literal("§7Danach:     " + farbe + "§l" + FormatUtil.formatiereBetrag(nachVerrechnung)),
                Text.literal(""),
                Text.literal("§8Kontostand + Forderungen − Schulden")
        )));

        return item;
    }

    private ItemStack erstelleStatusItem(double saldo,
                                         double gesamtForderungen,
                                         double gesamtSchulden,
                                         Double kontostand,
                                         Double nachVerrechnung) {
        ItemStack item;
        String titel;

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(""));

        if (gesamtForderungen == 0 && gesamtSchulden == 0) {
            item = new ItemStack(Items.NETHER_STAR);
            titel = "§a§lAlles ausgeglichen";

            lore.add(Text.literal("§7Du hast aktuell keine offenen"));
            lore.add(Text.literal("§7Schulden oder Forderungen."));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§aPerfekter Zustand."));
        } else if (saldo >= 0) {
            item = new ItemStack(Items.EMERALD);
            titel = "§a§lPositiver Stand";

            lore.add(Text.literal("§7Du bekommst insgesamt mehr zurück,"));
            lore.add(Text.literal("§7als du anderen schuldest."));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§7Plus: §a+" + FormatUtil.formatiereBetrag(saldo)));
        } else {
            item = new ItemStack(Items.REDSTONE);
            titel = "§c§lNegativer Stand";

            lore.add(Text.literal("§7Du schuldest insgesamt mehr,"));
            lore.add(Text.literal("§7als du noch zurückbekommst."));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§7Minus: §c" + FormatUtil.formatiereBetrag(saldo)));

            if (kontostand != null && nachVerrechnung != null && nachVerrechnung < 0) {
                lore.add(Text.literal(""));
                lore.add(Text.literal("§cAchtung: Nach Verrechnung"));
                lore.add(Text.literal("§cwärst du im Minus."));
            }
        }

        item.set(DataComponentTypes.ITEM_NAME, Text.literal(titel));
        item.set(DataComponentTypes.LORE, new LoreComponent(lore));

        return item;
    }

    private ItemStack erstellePaylogShortcutItem() {
        ItemStack item = new ItemStack(Items.FEATHER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§d§lPaylogs öffnen"));

        item.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("8.0"), List.of())
        );

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Öffnet deine Transaktionshistorie."),
                Text.literal("§7Dort siehst du erkannte Zahlungen"),
                Text.literal("§7und kannst nach Spielern filtern."),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));

        return item;
    }

    private ItemStack erstelleShortcutItem(ItemStack item, String titel, String... lore) {
        item.set(DataComponentTypes.ITEM_NAME, Text.literal(titel));

        List<Text> lines = new ArrayList<>();

        for (String line : lore) {
            lines.add(Text.literal(line));
        }

        item.set(DataComponentTypes.LORE, new LoreComponent(lines));
        return item;
    }

    private ItemStack erstelleTippItem(List<CreditEntry> forderungen,
                                       List<CreditEntry> schulden,
                                       double saldo) {
        ItemStack item = new ItemStack(Items.PAPER);
        item.set(DataComponentTypes.ITEM_NAME, Text.literal("§e§lKurzer Hinweis"));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal(""));

        if (!schulden.isEmpty()) {
            CreditEntry größteSchuld = topEinträge(schulden, 1).get(0);

            lore.add(Text.literal("§7Größte offene Schuld:"));
            lore.add(Text.literal("§8▸ §f" + kürze(größteSchuld.getDealName(), 24)));
            lore.add(Text.literal("  §7an §f" + größteSchuld.getCreditor()
                    + " §c" + FormatUtil.formatiereBetrag(größteSchuld.getRemainingAmount())));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§8Tipp: Erst große Schulden abbauen,"));
            lore.add(Text.literal("§8damit dein Saldo schneller steigt."));
        } else if (!forderungen.isEmpty()) {
            CreditEntry größteForderung = topEinträge(forderungen, 1).get(0);

            lore.add(Text.literal("§7Größte offene Forderung:"));
            lore.add(Text.literal("§8▸ §f" + kürze(größteForderung.getDealName(), 24)));
            lore.add(Text.literal("  §7von §f" + größteForderung.getDebtor()
                    + " §a" + FormatUtil.formatiereBetrag(größteForderung.getRemainingAmount())));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§8Tipp: Prüfe regelmäßig deine Paylogs,"));
            lore.add(Text.literal("§8falls Zahlungen schon eingegangen sind."));
        } else {
            lore.add(Text.literal("§7Keine offenen Deals vorhanden."));
            lore.add(Text.literal(""));
            lore.add(Text.literal("§8Neue Deals kannst du im Hauptmenü"));
            lore.add(Text.literal("§8oder per Befehl eintragen."));
        }

        lore.add(Text.literal(""));
        lore.add(saldo >= 0
                ? Text.literal("§7Saldo: §a+" + FormatUtil.formatiereBetrag(saldo))
                : Text.literal("§7Saldo: §c" + FormatUtil.formatiereBetrag(saldo)));

        item.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return item;
    }

    private List<CreditEntry> topEinträge(List<CreditEntry> einträge, int limit) {
        return einträge.stream()
                .sorted(Comparator.comparingDouble(CreditEntry::getRemainingAmount).reversed())
                .limit(limit)
                .toList();
    }

    private String kürze(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_LISTE_FORD) {
            client.setScreen(new CreditListeScreen(manager, false, this));
            return true;
        }

        if (slot == SLOT_LISTE_SCHULD) {
            client.setScreen(new CreditListeScreen(manager, true, this));
            return true;
        }

        if (slot == SLOT_PAYLOGS) {
            client.setScreen(new CreditPaylogScreen(manager,this));
            return true;
        }

        return false;
    }

    private Double getKontostand(MinecraftClient client) {
        if (client.world == null) return null;

        try {
            Scoreboard scoreboard = client.world.getScoreboard();

            ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(
                    net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR
            );

            if (sidebar == null) return null;

            List<String> zeilen = scoreboard.getKnownScoreHolders().stream()
                    .filter(h -> scoreboard.getScore(h, sidebar) != null)
                    .sorted((a, b) -> Integer.compare(
                            scoreboard.getScore(b, sidebar).getScore(),
                            scoreboard.getScore(a, sidebar).getScore()))
                    .map(h -> getZeilenText(scoreboard, h))
                    .filter(s -> s != null && !s.isBlank())
                    .toList();

            for (int i = 0; i < zeilen.size() - 1; i++) {
                String bereinigt = zeilen.get(i)
                        .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                        .strip();

                if (bereinigt.contains("Konto")) {
                    String nächste = zeilen.get(i + 1)
                            .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                            .strip();

                    return parseBetrag(nächste);
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String getZeilenText(Scoreboard scoreboard, ScoreHolder holder) {
        try {
            Team team = scoreboard.getScoreHolderTeam(holder.getNameForScoreboard());

            if (team != null) {
                String prefix = team.getPrefix() != null ? team.getPrefix().getString() : "";
                String suffix = team.getSuffix() != null ? team.getSuffix().getString() : "";
                return prefix + holder.getNameForScoreboard() + suffix;
            }

            return holder.getNameForScoreboard();

        } catch (Exception e) {
            return null;
        }
    }

    private Double parseBetrag(String text) {
        if (text == null || text.isBlank()) return null;

        Matcher matcher = BETRAG_PATTERN.matcher(text);

        if (matcher.find()) {
            try {
                return Double.parseDouble(
                        matcher.group()
                                .replace(".", "")
                                .replace(",", "")
                );
            } catch (NumberFormatException ignored) {
            }
        }

        return null;
    }
}
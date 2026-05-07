package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreditÜbersichtScreen extends BasisScreen {

    private static final Pattern BETRAG_PATTERN = Pattern.compile("[\\d][\\d.,]*");

    private static final int SLOT_ZURÜCK        = 27;
    private static final int SLOT_SCHULDEN_KOPF = 6;
    private static final int SLOT_FORD_KOPF     = 2;
    private static final int SLOT_SALDO         = 13;
    private static final int SLOT_KONTOSTAND    = 22;

    private final CreditManager manager;
    private final Screen elternScreen;

    public CreditÜbersichtScreen(CreditManager manager, Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fÜbersicht"), 4);
        this.manager      = manager;
        this.elternScreen = elternScreen;
    }

    @Override
    protected void fülleSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        String ich = client.player != null ? client.player.getName().getString().toLowerCase() : "";

        List<CreditEntry> schulden    = manager.getOpenCreditsAsDebtor(ich);
        List<CreditEntry> forderungen = manager.getOpenCreditsAsCreditor(ich);

        double gesamtSchulden    = schulden.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double gesamtForderungen = forderungen.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double saldo             = gesamtForderungen - gesamtSchulden;

        for (int i = 0;  i < 9;  i++) setSlot(i,  GuiHelper.schwarzGlas());
        for (int i = 27; i < 36; i++) setSlot(i,  GuiHelper.schwarzGlas());

        for (int r = 1; r <= 2; r++) setSlot(r * 9 + 4, GuiHelper.trennGlas());

        List<Text> forderungsLore = new ArrayList<>();
        forderungsLore.add(Text.literal("§7" + forderungen.size() + " offene Forderung" + (forderungen.size() != 1 ? "en" : "")));
        forderungsLore.add(Text.literal(""));
        if (forderungen.isEmpty()) {
            forderungsLore.add(Text.literal("§7Keine offenen Forderungen"));
        } else {
            for (CreditEntry e : forderungen) {
                forderungsLore.add(Text.literal("§8▸ §f" + e.getDealName()));
                forderungsLore.add(Text.literal("  §7von §f" + e.getDebtor()
                        + " §a" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
            }
        }
        forderungsLore.add(Text.literal(""));
        forderungsLore.add(Text.literal("§7Gesamt: §a§l" + FormatUtil.formatiereBetrag(gesamtForderungen)));

        ItemStack forderungsItem = new ItemStack(Items.LIME_WOOL);
        forderungsItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§a§lForderungen"));
        forderungsItem.set(DataComponentTypes.LORE, new LoreComponent(forderungsLore));
        setSlot(SLOT_FORD_KOPF, forderungsItem);

        List<Text> schuldenLore = new ArrayList<>();
        schuldenLore.add(Text.literal("§7" + schulden.size() + " offene Schuld" + (schulden.size() != 1 ? "en" : "")));
        schuldenLore.add(Text.literal(""));
        if (schulden.isEmpty()) {
            schuldenLore.add(Text.literal("§a✔ Keine offenen Schulden"));
        } else {
            for (CreditEntry e : schulden) {
                schuldenLore.add(Text.literal("§8▸ §f" + e.getDealName()));
                schuldenLore.add(Text.literal("  §7an §f" + e.getCreditor()
                        + " §c" + FormatUtil.formatiereBetrag(e.getRemainingAmount())));
            }
        }
        schuldenLore.add(Text.literal(""));
        schuldenLore.add(Text.literal("§7Gesamt: §c§l" + FormatUtil.formatiereBetrag(gesamtSchulden)));

        ItemStack schuldenItem = new ItemStack(Items.RED_WOOL);
        schuldenItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lSchulden"));
        schuldenItem.set(DataComponentTypes.LORE, new LoreComponent(schuldenLore));
        setSlot(SLOT_SCHULDEN_KOPF, schuldenItem);

        String saldoFarbe  = saldo >= 0 ? "§a" : "§c";
        String saldoPrefix = saldo >= 0 ? "+" : "";
        ItemStack saldoItem = new ItemStack(saldo >= 0 ? Items.EMERALD : Items.REDSTONE);
        saldoItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lSaldo"));
        saldoItem.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Forderungen − Schulden"),
                Text.literal(""),
                Text.literal("§7Forderungen: §a+" + FormatUtil.formatiereBetrag(gesamtForderungen)),
                Text.literal("§7Schulden:    §c-"  + FormatUtil.formatiereBetrag(gesamtSchulden)),
                Text.literal(""),
                Text.literal("§7Saldo: " + saldoFarbe + "§l" + saldoPrefix + FormatUtil.formatiereBetrag(saldo))
        )));
        setSlot(SLOT_SALDO, saldoItem);

        Double kontostand = getKontostand(client);
        if (kontostand != null) {
            double nachVerrechnung = kontostand + gesamtForderungen - gesamtSchulden;
            String nvFarbe = nachVerrechnung >= 0 ? "§a" : "§c";

            ItemStack kontoItem = new ItemStack(Items.GOLD_INGOT);
            kontoItem.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lKontostand"));
            kontoItem.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    Text.literal("§7Aus Scoreboard gelesen"),
                    Text.literal(""),
                    Text.literal("§7Aktuell:          §6" + FormatUtil.formatiereBetrag(kontostand)),
                    Text.literal("§7Nach Verrechnung: " + nvFarbe + FormatUtil.formatiereBetrag(nachVerrechnung)),
                    Text.literal(""),
                    Text.literal("§8(Kontostand + Forderungen − Schulden)")
            )));
            setSlot(SLOT_KONTOSTAND, kontoItem);
        }

        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        if (slot == SLOT_ZURÜCK) {
            MinecraftClient.getInstance().setScreen(elternScreen);
            return true;
        }
        return false;
    }

    private Double getKontostand(MinecraftClient client) {
        if (client.world == null) return null;
        try {
            Scoreboard scoreboard = client.world.getScoreboard();
            ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(
                    net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
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
                        .replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
                if (bereinigt.contains("Konto")) {
                    String nächste = zeilen.get(i + 1)
                            .replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
                    return parseBetrag(nächste);
                }
            }
        } catch (Exception ignored) {}
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
        Matcher m = BETRAG_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group().replace(".", "").replace(",", ""));
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
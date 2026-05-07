package op.creditmanager.client.command.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.ChatUtil;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.*;

public class SchuldenCommand {

    private static final Map<String, Long> ausstehendeLöschungen = new HashMap<>();
    private static final long BESTÄTIGUNG_TIMEOUT_MS = 30_000;

    private static SuggestionProvider<FabricClientCommandSource> eigeneDealVorschläge(CreditManager manager) {
        return (ctx, builder) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return builder.buildFuture();
            String spieler = client.player.getName().getString().toLowerCase();
            manager.getAllCreditsAsDebtor(spieler).stream()
                    .filter(e -> !"CANCELLED".equals(e.getStatus()))
                    .map(e -> e.getDealName() != null ? e.getDealName() : e.getId().toString().substring(0, 8))
                    .filter(n -> n.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build(CreditManager manager) {
        return ClientCommandManager.literal("schulden")
                .then(ClientCommandManager.literal("eintragen")
                        .then(ClientCommandManager.argument("gläubiger", StringArgumentType.word())
                                .suggests(spielerVorschlaege())
                                .then(ClientCommandManager.argument("betrag", StringArgumentType.word())
                                        .executes(ctx -> eintragenAusfuehren(ctx, manager, null, null, null))
                                        .then(ClientCommandManager.argument("fälligkeitsdatum", StringArgumentType.word())
                                                .executes(ctx -> eintragenAusfuehren(ctx, manager,
                                                        StringArgumentType.getString(ctx, "fälligkeitsdatum"), null, null))
                                                .then(ClientCommandManager.argument("bezeichnung", StringArgumentType.word())
                                                        .executes(ctx -> eintragenAusfuehren(ctx, manager,
                                                                StringArgumentType.getString(ctx, "fälligkeitsdatum"),
                                                                StringArgumentType.getString(ctx, "bezeichnung"), null))
                                                        .then(ClientCommandManager.argument("notiz", StringArgumentType.greedyString())
                                                                .executes(ctx -> eintragenAusfuehren(ctx, manager,
                                                                        StringArgumentType.getString(ctx, "fälligkeitsdatum"),
                                                                        StringArgumentType.getString(ctx, "bezeichnung"),
                                                                        StringArgumentType.getString(ctx, "notiz")))
                                                        )
                                                )
                                        )
                                )
                        )
                )
                .then(ClientCommandManager.literal("liste")
                        .executes(ctx -> listeAusfuehren(ctx, manager))
                )
                .then(ClientCommandManager.literal("zahlen")
                        .then(ClientCommandManager.argument("deal", StringArgumentType.word())
                                .suggests(eigeneDealVorschläge(manager))
                                .then(ClientCommandManager.argument("betrag", StringArgumentType.word())
                                        .executes(ctx -> zahlenAusfuehren(ctx, manager))
                                )
                        )
                )
                .then(ClientCommandManager.literal("itemzahlung")
                        .then(ClientCommandManager.argument("deal", StringArgumentType.word())
                                .suggests(eigeneDealVorschläge(manager))
                                .then(ClientCommandManager.argument("verrechnungswert", StringArgumentType.word())
                                        .executes(ctx -> itemZahlungAusfuehren(ctx, manager))
                                )
                        )
                )
                .then(ClientCommandManager.literal("löschen")
                        .then(ClientCommandManager.argument("deal", StringArgumentType.word())
                                .suggests(eigeneDealVorschläge(manager))
                                .executes(ctx -> loeschenAusfuehren(ctx, manager))
                        )
                )
                .then(ClientCommandManager.literal("bestätigen")
                        .then(ClientCommandManager.argument("deal", StringArgumentType.word())
                                .suggests(eigeneDealVorschläge(manager))
                                .executes(ctx -> bestätigenAusfuehren(ctx, manager))
                        )
                );
    }

    private static SuggestionProvider<FabricClientCommandSource> spielerVorschlaege() {
        return (ctx, builder) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() == null) return builder.buildFuture();

            String input = builder.getRemaining().toLowerCase();

            client.getNetworkHandler().getPlayerList().stream()
                    .map(playerInfo -> bereinigen(playerInfo.getProfile().name()))
                    .filter(name -> name != null && !name.isBlank())
                    .filter(name -> {
                        String lower = name.toLowerCase();
                        if (input.isBlank()) return true;
                        if (lower.startsWith(input)) return true;
                        if (lower.contains(input)) return true;
                        return fuzzyMatch(lower, input);
                    })
                    .sorted(Comparator.comparingInt(name -> matchScore(name.toLowerCase(), input)))
                    .forEach(builder::suggest);

            return builder.buildFuture();
        };
    }

    private static String bereinigen(String name) {
        if (name == null) return null;

        name = name.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

        if (name.contains("[")) {
            name = name.substring(0, name.indexOf("["));
        }

        String[] teile = name.split("[|\\s]+");
        for (int i = teile.length - 1; i >= 0; i--) {
            String bereinigt = teile[i].replaceAll("[^a-zA-Z0-9_]", "").strip();
            if (bereinigt.length() >= 3) {
                return bereinigt;
            }
        }

        return null;
    }

    private static int matchScore(String name, String input) {
        if (input.isBlank()) return 0;
        if (name.startsWith(input)) return 0;
        if (name.contains(input)) return 1;
        return 2;
    }

    private static boolean fuzzyMatch(String name, String input) {
        int nameIdx = 0;
        int inputIdx = 0;
        while (nameIdx < name.length() && inputIdx < input.length()) {
            if (name.charAt(nameIdx) == input.charAt(inputIdx)) {
                inputIdx++;
            }
            nameIdx++;
        }
        return inputIdx == input.length();
    }

    private static int eintragenAusfuehren(CommandContext<FabricClientCommandSource> ctx,
                                           CreditManager manager,
                                           String datum, String bezeichnung, String notiz) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        String glaeubiger = StringArgumentType.getString(ctx, "gläubiger").toLowerCase();

        double betrag;
        try {
            String raw = StringArgumentType.getString(ctx, "betrag");
            betrag = FormatUtil.parseMoney(raw);
        } catch (Exception e) {
            ChatUtil.fehler("Ungültiger Betrag!");
            ChatUtil.info("Beispiele: §f1000 §e| §f10k §e| §f2.5m §e| §f1mio §e| §f1mrd");
            return 0;
        }

        if (ich.equals(glaeubiger)) {
            ChatUtil.fehler("Du kannst keine Schulden mit dir selbst erstellen.");
            return 0;
        }

        Long faelligkeitMs = null;
        if (datum != null && !datum.equals("-")) {
            faelligkeitMs = TimeUtil.parseDueDate(datum);
            if (faelligkeitMs == null) {
                ChatUtil.fehler("Ungültiges Datum! Format: TT.MM.JJJJ");
                return 0;
            }
        }

        try {
            CreditEntry eintrag = manager.createCredit(glaeubiger, ich, betrag, faelligkeitMs, bezeichnung, notiz, true);

            ChatUtil.separator();
            ChatUtil.erfolg("Schuld erfolgreich eingetragen!");
            ChatUtil.sendRaw("  §7Deal-Name: §f" + eintrag.getDealName());
            ChatUtil.sendRaw("  §7Gläubiger: §f" + glaeubiger);
            ChatUtil.sendRaw("  §7Betrag:    §6" + FormatUtil.formatiereBetrag(betrag));

            if (bezeichnung != null) ChatUtil.sendRaw("  §7Bezeichnung: §f" + bezeichnung);
            if (notiz != null) ChatUtil.sendRaw("  §7Notiz:    §f" + notiz);
            if (faelligkeitMs != null) ChatUtil.sendRaw("  §7Fällig bis: §f" + TimeUtil.formatDatum(faelligkeitMs));

            ChatUtil.boxEnd();
        } catch (CreditManager.CreditException e) {
            ChatUtil.fehler(e.getMessage());
        }

        return 1;
    }

    private static int listeAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        List<CreditEntry> schulden = manager.getAllCreditsAsDebtor(ich);

        ChatUtil.box("Meine Schulden");

        if (schulden.isEmpty()) {
            ChatUtil.nachricht("§7Du hast aktuell keine Schulden.");
        } else {

            long offene = schulden.stream()
                    .filter(e -> !"PAID".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
                    .count();

            ChatUtil.sendRaw("  §7Gesamt: §f" + schulden.size() + " §7| Offen: §c" + offene);
            ChatUtil.separator();

            for (CreditEntry e : schulden) {

                String formatted = FormatUtil.formatCreditSummary(e);

                String[] lines = formatted.split("\n");
                for (String line : lines) {
                    ChatUtil.sendRaw("  " + line);
                }

                if (e.getDueDate() != null) {
                    ChatUtil.sendRaw("    §7Fällig: §f" + TimeUtil.getDueDateDisplay(e.getDueDate()));
                }

                if (e.getNote() != null && !e.getNote().isBlank()) {
                    ChatUtil.sendRaw("    §7Notiz: §f" + e.getNote());
                }

                ChatUtil.separator();
            }
        }

        ChatUtil.boxEnd();
        return 1;
    }

    private static int zahlenAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        String dealId = StringArgumentType.getString(ctx, "deal");
        double betrag;
        try {
            String raw = StringArgumentType.getString(ctx, "betrag");
            betrag = FormatUtil.parseMoney(raw);
        } catch (Exception e) {
            ChatUtil.fehler("Ungültiger Betrag!");
            ChatUtil.info("Beispiele: §f1000 §e| §f10k §e| §f2.5m §e| §f1mio §e| §f1mrd");
            return 0;
        }

        Optional<CreditEntry> opt = manager.findCredit(dealId);
        if (opt.isEmpty()) {
            ChatUtil.fehler("Deal nicht gefunden: §f" + dealId);
            ChatUtil.info("Tipp: /cm schulden liste");
            return 0;
        }

        CreditEntry eintrag = opt.get();

        if (!eintrag.getDebtor().equalsIgnoreCase(ich)) {
            ChatUtil.fehler("Du bist nicht der Schuldner dieses Deals.");
            return 0;
        }

        if ("PAID".equals(eintrag.getStatus()) || "CANCELLED".equals(eintrag.getStatus())) {
            ChatUtil.fehler("Dieser Deal ist bereits abgeschlossen oder storniert.");
            return 0;
        }

        try {
            Payment zahlung = manager.addMoneyPayment(eintrag.getId(), ich, betrag);

            ChatUtil.separator();
            ChatUtil.erfolg("Zahlung eingetragen!");
            ChatUtil.sendRaw("  §7Deal:        §f" + eintrag.getDealName());
            ChatUtil.sendRaw("  §7An Gläubiger: §f" + eintrag.getCreditor());
            ChatUtil.sendRaw("  §7Betrag:      §6" + FormatUtil.formatiereBetrag(zahlung.getAmount()));
            ChatUtil.sendRaw("  §7Noch offen:  §c" + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()));
            ChatUtil.sendRaw("  §7Status:      " + FormatUtil.getStatusAnzeige(eintrag.getStatus()));
            ChatUtil.separator();

        } catch (CreditManager.CreditException e) {
            ChatUtil.fehler(e.getMessage());
        }

        return 1;
    }

    private static MutableText createItemHover(ItemStack stack) {
        return Text.literal("[")
                .styled(style -> style.withColor(0xFFFFFF))
                .append(stack.getName())
                .append(Text.literal("]")
                        .styled(style -> style.withColor(0xFFFFFF)))
                .styled(style -> style.withHoverEvent(
                        new HoverEvent.ShowItem(stack)
                ));
    }

    private static int itemZahlungAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        String dealId = StringArgumentType.getString(ctx, "deal");
        double verrechnungswert;
        try {
            String raw = StringArgumentType.getString(ctx, "verrechnungswert");
            verrechnungswert = FormatUtil.parseMoney(raw);
        } catch (Exception e) {
            ChatUtil.fehler("Ungültiger Betrag!");
            return 0;
        }

        Optional<CreditEntry> opt = manager.findCredit(dealId);
        if (opt.isEmpty()) {
            ChatUtil.fehler("Deal nicht gefunden: §f" + dealId);
            ChatUtil.info("Tipp: /cm schulden liste");
            return 0;
        }

        CreditEntry eintrag = opt.get();

        if (!eintrag.getDebtor().equalsIgnoreCase(ich)) {
            ChatUtil.fehler("Du bist nicht der Schuldner dieses Deals.");
            return 0;
        }

        if ("PAID".equals(eintrag.getStatus()) || "CANCELLED".equals(eintrag.getStatus())) {
            ChatUtil.fehler("Dieser Deal ist bereits abgeschlossen oder storniert.");
            return 0;
        }

        ItemStack item = client.player.getMainHandStack();
        if (item.isEmpty()) {
            ChatUtil.fehler("Du hältst kein Item in der Hand!");
            return 0;
        }

        String itemName = item.getName().getString();
        int anzahl = item.getCount();
        String itemBeschreibung = anzahl + "x " + itemName;

        String nbtString = null;
        try {
            RegistryOps<NbtElement> ops = client.player.getRegistryManager().getOps(NbtOps.INSTANCE);
            NbtCompound nbt = (NbtCompound) ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
            nbtString = nbt.toString();
        } catch (Exception ignored) {}

        try {
            manager.addItemPayment(eintrag.getId(), ich,
                    List.of(itemBeschreibung),
                    verrechnungswert,
                    nbtString
            );

            ChatUtil.separator();
            ChatUtil.erfolg("Item-Zahlung dokumentiert!");
            ChatUtil.sendRaw("  §7Deal:             §f" + eintrag.getDealName());

            ChatUtil.send(
                    Text.literal("  §7Item:             ")
                            .append(createItemHover(item))
            );

            ChatUtil.sendRaw("  §7Wert:             §6" + FormatUtil.formatiereBetrag(verrechnungswert));
            ChatUtil.sendRaw("  §7Noch offen:     §c" + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()));
            ChatUtil.sendRaw("  §7Status:           " + FormatUtil.getStatusAnzeige(eintrag.getStatus()));
            ChatUtil.separator();

        } catch (CreditManager.CreditException e) {
            ChatUtil.fehler(e.getMessage());
        }

        return 1;
    }

    private static int loeschenAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        String dealId = StringArgumentType.getString(ctx, "deal");

        Optional<CreditEntry> opt = manager.findCredit(dealId);
        if (opt.isEmpty()) {
            ChatUtil.fehler("Deal nicht gefunden: §f" + dealId);
            return 0;
        }

        CreditEntry eintrag = opt.get();

        if (!eintrag.getDebtor().equalsIgnoreCase(ich)
                && !eintrag.getCreditor().equalsIgnoreCase(ich)) {
            ChatUtil.fehler("Du hast keinen Zugriff auf diesen Deal.");
            return 0;
        }

        String key = ich + ":" + eintrag.getId().toString();
        ausstehendeLöschungen.put(key, System.currentTimeMillis());

        ChatUtil.separator();
        ChatUtil.sendRaw("  §c§lForderung wirklich löschen?");
        ChatUtil.sendRaw("  §7Name:      §f" + eintrag.getDealName());
        ChatUtil.sendRaw("  §7Betrag:    §6" + FormatUtil.formatiereBetrag(eintrag.getAmount()));
        ChatUtil.sendRaw("  §7Status:    " + FormatUtil.getStatusAnzeige(eintrag.getStatus()));
        ChatUtil.sendRaw("  §7Zahlungen: §f" + eintrag.getPayments().size() + " Einträge");
        ChatUtil.sendRaw(" ");
        ChatUtil.sendRaw("  §eZum Bestätigen: §f/cm schulden bestätigen " + eintrag.getDealName());
        ChatUtil.sendRaw("  §8(Bestätigung verfällt nach 30 Sekunden)");
        ChatUtil.separator();

        return 1;
    }

    private static int bestätigenAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        String dealId = StringArgumentType.getString(ctx, "deal");

        Optional<CreditEntry> opt = manager.findCredit(dealId);
        if (opt.isEmpty()) {
            ChatUtil.fehler("Deal nicht gefunden: §f" + dealId);
            return 0;
        }

        CreditEntry eintrag = opt.get();
        String schlüssel = ich + ":" + eintrag.getId().toString();
        Long zeitstempel = ausstehendeLöschungen.get(schlüssel);

        if (zeitstempel == null) {
            ChatUtil.fehler("Keine ausstehende Löschanfrage für diesen Deal.");
            ChatUtil.info("Starte mit: /cm schulden löschen " + eintrag.getDealName());
            return 0;
        }
        if (System.currentTimeMillis() - zeitstempel > BESTÄTIGUNG_TIMEOUT_MS) {
            ausstehendeLöschungen.remove(schlüssel);
            ChatUtil.fehler("Die Bestätigung ist abgelaufen (30 Sekunden).");
            ChatUtil.info("Bitte erneut starten: /cm schulden löschen " + eintrag.getDealName());
            return 0;
        }

        ausstehendeLöschungen.remove(schlüssel);
        try {
            manager.deleteCredit(eintrag.getId());
            ChatUtil.erfolg("Deal \"" + eintrag.getDealName() + "\" wurde gelöscht.");
        } catch (CreditManager.CreditException e) {
            ChatUtil.fehler(e.getMessage());
        }
        return 1;
    }
}
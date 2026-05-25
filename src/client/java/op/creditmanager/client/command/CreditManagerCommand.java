package op.creditmanager.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import op.creditmanager.client.command.sub.*;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.util.ChatUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

public class CreditManagerCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CreditManager manager) {
        registerCommand(dispatcher, manager, "CreditManager");
        registerCommand(dispatcher, manager, "cm");
        registerCommand(dispatcher, manager, "OpCreditmanager");
        registerCommand(dispatcher, manager, "OpCM");
    }

    private static void registerCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, CreditManager manager, String name) {
        dispatcher.register(
                ClientCommandManager.literal(name)
                        .executes(ctx -> guiAusführen(ctx, manager))
                        .then(ClientCommandManager.literal("befehle")
                                .executes(ctx -> hilfeAusführen(ctx)))
                        .then(SchuldenCommand.build(manager))
                        .then(ForderungCommand.build(manager))
                        .then(InfoCommand.build(manager))
                        .then(PaylogsCommand.build(manager))
                        .then(ÜbersichtCommand.build(manager))
        );
    }

    private static int guiAusführen(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        return GuiCommand.openGui(manager);
    }

    private static int hilfeAusführen(CommandContext<FabricClientCommandSource> ctx) {
        ChatUtil.box("CreditManager – Befehlsübersicht");

        ChatUtil.sendRaw("  §e§lMeine Schulden §8(ich schulde jemandem)");
        ChatUtil.sendRaw("  §b/cm schulden eintragen §7<spieler> <betrag> [datum] [bezeichnung] [notiz]");
        ChatUtil.sendRaw("  §b/cm schulden liste        §7Alle deine Schulden anzeigen");
        ChatUtil.sendRaw("  §b/cm schulden zahlen       §7<deal> <betrag>  – Geldzahlung leisten");
        ChatUtil.sendRaw("  §b/cm schulden itemzahlung  §7<deal> <wert>    – Item in der Hand als Zahlung");
        ChatUtil.sendRaw("  §b/cm schulden löschen      §7<deal>           – Deal löschen (mit Bestätigung)");
        ChatUtil.sendRaw("  §b/cm schulden bestätigen   §7<deal>           – Löschung bestätigen");

        ChatUtil.separator();

        ChatUtil.sendRaw("  §e§lMeine Forderungen §8(jemand schuldet mir)");
        ChatUtil.sendRaw("  §a/cm forderung eintragen §7<spieler> <betrag> [datum] [bezeichnung] [notiz]");
        ChatUtil.sendRaw("  §a/cm forderung liste       §7Alle deine Forderungen anzeigen");
        ChatUtil.sendRaw("  §a/cm forderung empfangen   §7<deal> <betrag>  – Empfangene Zahlung eintragen");
        ChatUtil.sendRaw("  §a/cm forderung löschen     §7<deal>           – Deal löschen (mit Bestätigung)");
        ChatUtil.sendRaw("  §a/cm forderung bestätigen  §7<deal>           – Löschung bestätigen");

        ChatUtil.separator();

        ChatUtil.sendRaw("  §e§lÜbersicht");
        ChatUtil.sendRaw("  §6/cm übersicht             §7Saldo + Kontostand (allgemein)");
        ChatUtil.sendRaw("  §6/cm übersicht schulden    §7Nur Schulden + Kontostand danach");
        ChatUtil.sendRaw("  §6/cm übersicht forderung   §7Nur Forderungen + Kontostand danach");

        ChatUtil.separator();

        ChatUtil.sendRaw("  §e§lGUI");
        ChatUtil.sendRaw("  §d/cm                       §7Grafisches Menü öffnen");

        ChatUtil.separator();

        ChatUtil.sendRaw("  §e/cm info §7<deal-name oder spieler>  – Detailansicht");
        ChatUtil.sendRaw("  §e/cm paylogs §7[spieler|ich] [3t|2w|1m|TT.MM.JJJJ]");
        ChatUtil.sendRaw("  §e/cm befehle §7– Diese Befehlsübersicht anzeigen");

        ChatUtil.separator();

        ChatUtil.sendRaw("  §7§lHinweise:");
        ChatUtil.sendRaw("  §7Datum-Format:   §fTT.MM.JJJJ §8(z.B. 25.12.2025)");
        ChatUtil.sendRaw("  §7Bezeichnung:    §fKurzer Name §8(z.B. Spawner, Diamond-Deal)");
        ChatUtil.sendRaw("  §7Item-Zahlung:   §fItem in die Hand nehmen, dann Befehl ausführen");
        ChatUtil.sendRaw("  §7Tab-Completion: §fBei allen Befehlen Tab §7drücken für Vorschläge!");

        ChatUtil.boxEnd();
        return 1;
    }
}
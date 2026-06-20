package op.creditmanager.client.command.sub;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.ChatUtil;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.BalanceReader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.List;

public class ÜbersichtCommand {

    public static LiteralArgumentBuilder<FabricClientCommandSource> build(CreditManager manager) {
        return ClientCommandManager.literal("übersicht")
                .executes(ctx -> allgemeinAusführen(ctx, manager))
                .then(ClientCommandManager.literal("allgemein")
                        .executes(ctx -> allgemeinAusführen(ctx, manager))
                )
                .then(ClientCommandManager.literal("schulden")
                        .executes(ctx -> schuldenAusführen(ctx, manager))
                )
                .then(ClientCommandManager.literal("forderung")
                        .executes(ctx -> forderungAusführen(ctx, manager))
                );
    }

    private static int schuldenAusführen(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        List<CreditEntry> schulden = manager.getOpenCreditsAsDebtor(ich);
        List<CreditEntry> alleSchulden = manager.getAllCreditsAsDebtor(ich);
        double gesamtOffen = schulden.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();

        ChatUtil.box("Meine Schulden");

        if (schulden.isEmpty()) {
            ChatUtil.sendRaw("  §a✔ Keine offenen Schulden");
        } else {
            ChatUtil.sendRaw("  §7" + schulden.size() + " offen  §8|  §7" + alleSchulden.size() + " gesamt");
            ChatUtil.separator();
            for (CreditEntry e : schulden) {
                schuldenZeile(e);
            }
            ChatUtil.separator();
            ChatUtil.sendRaw("  §7Gesamt offen");
            ChatUtil.sendRaw("  §c§l" + FormatUtil.formatiereBetrag(gesamtOffen));
        }

        java.util.OptionalDouble kontostand = BalanceReader.readCurrentBalance(client);
        if (kontostand.isPresent()) {
            ChatUtil.separator();
            zeigeKontostandBlock(kontostand.getAsDouble(), 0, gesamtOffen);
        }

        ChatUtil.boxEnd();
        return 1;
    }

    private static int forderungAusführen(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        List<CreditEntry> forderungen = manager.getOpenCreditsAsCreditor(ich);
        List<CreditEntry> alleForderungen = manager.getAllCreditsAsCreditor(ich);
        double gesamtOffen = forderungen.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();

        ChatUtil.box("Meine Forderungen");

        if (forderungen.isEmpty()) {
            ChatUtil.sendRaw("  §7Keine offenen Forderungen");
        } else {
            ChatUtil.sendRaw("  §7" + forderungen.size() + " offen  §8|  §7" + alleForderungen.size() + " gesamt");
            ChatUtil.separator();
            for (CreditEntry e : forderungen) {
                forderungZeile(e);
            }
            ChatUtil.separator();
            ChatUtil.sendRaw("  §7Gesamt ausstehend");
            ChatUtil.sendRaw("  §a§l" + FormatUtil.formatiereBetrag(gesamtOffen));
        }

        java.util.OptionalDouble kontostand = BalanceReader.readCurrentBalance(client);
        if (kontostand.isPresent()) {
            ChatUtil.separator();
            zeigeKontostandBlock(kontostand.getAsDouble(), gesamtOffen, 0);
        }

        ChatUtil.boxEnd();
        return 1;
    }

    private static int allgemeinAusführen(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ich = client.player.getName().getString().toLowerCase();
        List<CreditEntry> schulden    = manager.getOpenCreditsAsDebtor(ich);
        List<CreditEntry> forderungen = manager.getOpenCreditsAsCreditor(ich);

        double gesamtSchulden    = schulden.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double gesamtForderungen = forderungen.stream().mapToDouble(CreditEntry::getRemainingAmount).sum();
        double saldo             = gesamtForderungen - gesamtSchulden;

        ChatUtil.box("Übersicht");

        ChatUtil.sendRaw("  §c§lSchulden  §8(" + schulden.size() + " offen)");
        if (schulden.isEmpty()) {
            ChatUtil.sendRaw("  §7Keine offenen Schulden");
        } else {
            for (CreditEntry e : schulden) {
                schuldenZeile(e);
            }
            ChatUtil.sendRaw("  §7Gesamt:  §c" + FormatUtil.formatiereBetrag(gesamtSchulden));
        }

        ChatUtil.separator();

        ChatUtil.sendRaw("  §a§lForderungen  §8(" + forderungen.size() + " offen)");
        if (forderungen.isEmpty()) {
            ChatUtil.sendRaw("  §7Keine offenen Forderungen");
        } else {
            for (CreditEntry e : forderungen) {
                forderungZeile(e);
            }
            ChatUtil.sendRaw("  §7Gesamt:  §a" + FormatUtil.formatiereBetrag(gesamtForderungen));
        }

        ChatUtil.separator();

        String saldoFarbe  = saldo >= 0 ? "§a" : "§c";
        String saldoPrefix = saldo >= 0 ? "+" : "";
        ChatUtil.sendRaw("  §7Saldo");
        ChatUtil.sendRaw("  " + saldoFarbe + "§l" + saldoPrefix + FormatUtil.formatiereBetrag(saldo));

        java.util.OptionalDouble kontostand = BalanceReader.readCurrentBalance(client);
        if (kontostand.isPresent()) {
            ChatUtil.separator();
            zeigeKontostandBlock(kontostand.getAsDouble(), gesamtForderungen, gesamtSchulden);
        }

        ChatUtil.boxEnd();
        return 1;
    }

    private static void schuldenZeile(CreditEntry e) {
        ChatUtil.sendRaw("  §8▸ §f" + e.getDealName());
        ChatUtil.sendRaw("    §7an §f" + e.getCreditor()
                + "  §c" + FormatUtil.formatiereBetrag(e.getRemainingAmount()));
    }

    private static void forderungZeile(CreditEntry e) {
        ChatUtil.sendRaw("  §8▸ §f" + e.getDealName());
        ChatUtil.sendRaw("    §7von §f" + e.getDebtor()
                + "  §a" + FormatUtil.formatiereBetrag(e.getRemainingAmount()));
    }


    private static void zeigeKontostandBlock(double kontostand, double forderungen, double schulden) {
        double nach = kontostand + forderungen - schulden;
        String nachFarbe = nach >= 0 ? "§a" : "§c";

        ChatUtil.sendRaw("  §7Kontostand");
        ChatUtil.sendRaw("  §6" + FormatUtil.formatiereBetrag(kontostand));
        ChatUtil.sendRaw("  §7Nach Verrechnung");
        ChatUtil.sendRaw("  " + nachFarbe + FormatUtil.formatiereBetrag(nach));
    }

}

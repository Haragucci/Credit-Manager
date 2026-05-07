package op.creditmanager.client.command.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryOps;
import net.minecraft.item.ItemStack;
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

import java.util.List;

public class InfoCommand {

    private static SuggestionProvider<FabricClientCommandSource> dealVorschläge(CreditManager manager) {
        return (ctx, builder) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return builder.buildFuture();
            String spieler = client.player.getName().getString().toLowerCase();
            manager.getDealNamesForPlayer(spieler).stream()
                    .filter(n -> n.toLowerCase().startsWith(builder.getRemaining().toLowerCase()))
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    public static LiteralArgumentBuilder<FabricClientCommandSource> build(CreditManager manager) {
        return ClientCommandManager.literal("info")
                .then(ClientCommandManager.argument("deal-oder-spieler", StringArgumentType.word())
                        .suggests(dealVorschläge(manager))
                        .executes(ctx -> infoAusfuehren(ctx, manager))
                );
    }

    private static int infoAusfuehren(CommandContext<FabricClientCommandSource> ctx, CreditManager manager) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String eingabe = StringArgumentType.getString(ctx, "deal-oder-spieler");

        var dealOpt = manager.findCredit(eingabe);
        if (dealOpt.isPresent()) {
            dealInfoAnzeigen(dealOpt.get(), manager, client);
        } else {
            List<CreditEntry> alleDeals = manager.getCreditsForPlayer(eingabe);
            if (alleDeals.isEmpty()) {
                ChatUtil.fehler("Kein Deal und kein Spieler gefunden: §f" + eingabe);
                ChatUtil.info("Tipp: Mit §f/cm schulden liste §eoder §f/cm forderung liste §edeine Deals anzeigen.");
                return 0;
            }
            spielerInfoAnzeigen(eingabe, alleDeals, client);
        }
        return 1;
    }

    private static void dealInfoAnzeigen(CreditEntry eintrag, CreditManager manager, MinecraftClient client) {
        ChatUtil.boxEnd();
        ChatUtil.box("Deal: " + eintrag.getDealName());
        ChatUtil.sendRaw("  §7Schuldner:  §c§l" + eintrag.getDebtor());
        ChatUtil.sendRaw("  §7Gläubiger:   §6§l" + eintrag.getCreditor());
        ChatUtil.sendRaw("  §7Betrag:      §6" + FormatUtil.formatiereBetrag(eintrag.getAmount()));
        ChatUtil.sendRaw("  §7Bezahlt:     §a" + FormatUtil.formatiereBetrag(eintrag.getPaidAmount()));
        ChatUtil.sendRaw("  §7Noch offen:§c" + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()));
        ChatUtil.sendRaw("  §7Status:       " + FormatUtil.getStatusAnzeige(eintrag.getStatus()));
        ChatUtil.sendRaw("  §7Erstellt am: §f" + TimeUtil.formatDatumZeit(eintrag.getCreatedAt()));
        if (eintrag.getDueDate() != null) {
            ChatUtil.sendRaw("  §7Fällig bis:    " + TimeUtil.getDueDateDisplay(eintrag.getDueDate()));
        }
        if (eintrag.getNote() != null && !eintrag.getNote().isBlank()) {
            ChatUtil.sendRaw("  §7Notiz:       §9" + eintrag.getNote());
        }
        List<Payment> zahlungen = manager.getPaymentsForCredit(eintrag.getId());
        if (!zahlungen.isEmpty()) {
            ChatUtil.separator();
            ChatUtil.sendRaw("  §7Zahlungshistorie §8(" + zahlungen.size() + " Einträge):");
            for (Payment p : zahlungen) {
                zahlungszeileDrucken(p, client);
            }
        } else {
            ChatUtil.separator();
            ChatUtil.sendRaw("  §7Noch keine Zahlungen eingetragen.");
        }
        ChatUtil.boxEnd();
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

    private static void zahlungszeileDrucken(Payment p, MinecraftClient client) {

        if (p.getItems() != null && !p.getItems().isEmpty()) {

            String zeitStr = TimeUtil.formatDatumZeit(p.getTimestamp());

            String wertAnzeige = "";
            if (p.getAmount() != null && p.getAmount() > 0) {
                wertAnzeige = " §8(≙ §6" + FormatUtil.formatiereBetrag(p.getAmount()) + "§8)";
            }

            if (p.getItemNbt() != null && !p.getItemNbt().isBlank() && client != null && client.player != null) {
                try {
                    NbtCompound nbt = NbtHelper.fromNbtProviderString(p.getItemNbt());
                    RegistryOps<NbtElement> ops = client.player.getRegistryManager().getOps(NbtOps.INSTANCE);

                    ItemStack stack = ItemStack.CODEC.parse(ops, nbt).result().orElse(null);

                    if (stack != null && !stack.isEmpty()) {

                        MutableText message = Text.literal("  §dItem: ")
                                .append(createItemHover(stack))
                                .append(Text.literal(wertAnzeige))
                                .append(Text.literal(" §7[" + zeitStr + "]"));

                        ChatUtil.send(message);
                        return;
                    }

                } catch (Exception ignored) {}
            }

            String itemLabel = String.join(", ", p.getItems());

            String fallback = "  §dItem: §f[" + itemLabel + "]"
                    + wertAnzeige
                    + " §7[" + zeitStr + "]";

            ChatUtil.sendRaw(fallback);

        } else {

            String zeile =
                    "§f" + p.getFromPlayer() + " §7→ §f" + p.getToPlayer()
                    + " §7| §6" + FormatUtil.formatiereBetrag(p.getAmount())
                    + "  §7[" + TimeUtil.formatDatumZeit(p.getTimestamp()) + "] ";

            ChatUtil.sendRaw(zeile);
        }
    }


    private static void spielerInfoAnzeigen(String zielSpieler, List<CreditEntry> alleDeals, MinecraftClient client) {
        ChatUtil.box("Spielerinfo: " + zielSpieler);

        List<CreditEntry> alsSchuldner = alleDeals.stream()
                .filter(e -> e.getDebtor().equalsIgnoreCase(zielSpieler)).toList();
        List<CreditEntry> alsGläubiger = alleDeals.stream()
                .filter(e -> e.getCreditor().equalsIgnoreCase(zielSpieler)).toList();

        if (!alsSchuldner.isEmpty()) {
            ChatUtil.sendRaw("  §c§lSchulden §8(" + zielSpieler + " schuldet):");
            for (CreditEntry e : alsSchuldner) {
                ChatUtil.sendRaw("  " + FormatUtil.formatiereDealZeile(e));
            }
        }

        if (!alsGläubiger.isEmpty()) {
            if (!alsSchuldner.isEmpty()) ChatUtil.separator();
            ChatUtil.sendRaw("  §a§lForderungen §8(" + zielSpieler + " bekommt):");
            for (CreditEntry e : alsGläubiger) {
                ChatUtil.sendRaw("  " + FormatUtil.formatiereDealZeile(e));
            }
        }

        double gesamtSchulden = alsSchuldner.stream()
                .filter(e -> !"PAID".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
                .mapToDouble(CreditEntry::getRemainingAmount).sum();
        double gesamtForderungen = alsGläubiger.stream()
                .filter(e -> !"PAID".equals(e.getStatus()) && !"CANCELLED".equals(e.getStatus()))
                .mapToDouble(CreditEntry::getRemainingAmount).sum();

        ChatUtil.separator();
        ChatUtil.sendRaw("  §7Offene Schulden:     §c" + FormatUtil.formatiereBetrag(gesamtSchulden));
        ChatUtil.sendRaw("  §7Offene Forderungen:  §a" + FormatUtil.formatiereBetrag(gesamtForderungen));
        ChatUtil.boxEnd();
    }
}
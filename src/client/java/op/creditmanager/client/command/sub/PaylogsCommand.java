package op.creditmanager.client.command.sub;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.ChatUtil;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import op.creditmanager.client.core.CreditManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

public class PaylogsCommand {

    private static final DateTimeFormatter DATUM_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static LiteralArgumentBuilder<FabricClientCommandSource> build(CreditManager manager) {
        return ClientCommandManager.literal("paylogs")

                .executes(ctx -> logAusfuehren(ctx, null, null))

                .then(ClientCommandManager.argument("filter", StringArgumentType.word())
                        .suggests(spielerVorschlaege())
                        .executes(ctx -> logAusfuehren(ctx,
                                StringArgumentType.getString(ctx, "filter"), null))

                        .then(ClientCommandManager.argument("zeitraum", StringArgumentType.word())
                                .executes(ctx -> logAusfuehren(ctx,
                                        StringArgumentType.getString(ctx, "filter"),
                                        StringArgumentType.getString(ctx, "zeitraum")))
                        )
                );
    }

    private static SuggestionProvider<FabricClientCommandSource> spielerVorschlaege() {
        return (ctx, builder) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) return builder.buildFuture();

            String input = builder.getRemaining().toLowerCase();
            String ich = client.player.getName().getString().toLowerCase();

            Set<String> kandidaten = new LinkedHashSet<>();
            kandidaten.add("ich");
            kandidaten.add("me");

            List<TransactionEntry> alle = TransactionRepository.getInstance().getAll();
            for (TransactionEntry t : alle) {
                if (t.getFromPlayer() != null) kandidaten.add(t.getFromPlayer().toLowerCase());
                if (t.getToPlayer() != null)   kandidaten.add(t.getToPlayer().toLowerCase());
            }

            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().getPlayerList().forEach(info ->
                        kandidaten.add(info.getProfile().name().toLowerCase()));
            }

            kandidaten.remove(ich);

            if (input.isBlank()) {
                kandidaten.forEach(builder::suggest);
            } else {
                kandidaten.stream()
                        .filter(name -> matchet(name, input))
                        .sorted(Comparator.comparingInt(name -> matchScore(name, input)))
                        .forEach(builder::suggest);
            }

            return builder.buildFuture();
        };
    }

    private static boolean matchet(String name, String input) {
        if (name.startsWith(input))   return true;
        if (name.contains(input))     return true;
        return fuzzyMatch(name, input);
    }

    private static int matchScore(String name, String input) {
        if (name.equals(input))       return 0;
        if (name.startsWith(input))   return 1;
        if (name.contains(input))     return 2;
        return 3;
    }

    private static boolean fuzzyMatch(String name, String input) {
        int ni = 0, ii = 0;
        while (ni < name.length() && ii < input.length()) {
            if (name.charAt(ni) == input.charAt(ii)) ii++;
            ni++;
        }
        return ii == input.length();
    }

    private static int logAusfuehren(CommandContext<FabricClientCommandSource> ctx,
                                     String filter, String zeitraum) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        String ichSpieler = client.player.getName().getString().toLowerCase();
        List<TransactionEntry> alle = TransactionRepository.getInstance().getAll();

        String spielerFilter = null;
        Long vonMs = null;
        Long bisMs = null;

        if (filter != null) {
            Long einzeldatum = parseDatum(filter);
            if (einzeldatum != null) {
                vonMs = einzeldatum;
                bisMs = einzeldatum + 86_400_000L - 1;
            } else {
                Long relativ = parseRelativerZeitraum(filter);
                if (relativ != null) {
                    vonMs = System.currentTimeMillis() - relativ;
                    bisMs = System.currentTimeMillis();
                } else {
                    spielerFilter = (filter.equalsIgnoreCase("ich") || filter.equalsIgnoreCase("me"))
                            ? ichSpieler : filter.toLowerCase();
                }
            }
        }

        if (zeitraum != null && spielerFilter != null) {
            Long einzeldatum = parseDatum(zeitraum);
            if (einzeldatum != null) {
                vonMs = einzeldatum;
                bisMs = einzeldatum + 86_400_000L - 1;
            } else {
                Long relativ = parseRelativerZeitraum(zeitraum);
                if (relativ != null) {
                    vonMs = System.currentTimeMillis() - relativ;
                    bisMs = System.currentTimeMillis();
                } else {
                    ChatUtil.fehler("Ungültiger Zeitraum: §f" + zeitraum);
                    ChatUtil.info("Erlaubte Formate: §fTT.MM.JJJJ §eoder §f3t §e(Tage), §f2w §e(Wochen), §f1m §e(Monate)");
                    return 0;
                }
            }
        }

        final String finalSpielerFilter = spielerFilter;
        final Long finalVon = vonMs;
        final Long finalBis = bisMs;

        List<TransactionEntry> gefiltert = alle.stream()
                .filter(t -> {
                    if (finalSpielerFilter != null) {
                        return t.getFromPlayer().equalsIgnoreCase(finalSpielerFilter)
                                || t.getToPlayer().equalsIgnoreCase(finalSpielerFilter);
                    }
                    return t.getFromPlayer().equalsIgnoreCase(ichSpieler)
                            || t.getToPlayer().equalsIgnoreCase(ichSpieler);
                })
                .filter(t -> finalVon == null || t.getTimestamp() >= finalVon)
                .filter(t -> finalBis == null || t.getTimestamp() <= finalBis)
                .sorted(Comparator.comparingLong(TransactionEntry::getTimestamp).reversed())
                .limit(20)
                .collect(Collectors.toList());

        StringBuilder titel = new StringBuilder("Paylogs");
        if (spielerFilter != null) titel.append(" – ").append(spielerFilter);
        if (vonMs != null) {
            titel.append(" – ").append(TimeUtil.formatDatum(vonMs));
            if (bisMs != null && !TimeUtil.formatDatum(vonMs).equals(TimeUtil.formatDatum(bisMs))) {
                titel.append(" bis ").append(TimeUtil.formatDatum(bisMs));
            }
        }

        ChatUtil.box(titel.toString());

        if (filter == null) {
            ChatUtil.sendRaw("  §8Zeigt deine letzten 20 Transaktionen");
        } else {
            String hinweis = "  §8Filter: ";
            if (spielerFilter != null) hinweis += "Spieler=" + spielerFilter + " ";
            if (vonMs != null) hinweis += "ab " + TimeUtil.formatDatum(vonMs);
            ChatUtil.sendRaw(hinweis.trim());
        }

        if (gefiltert.isEmpty()) {
            ChatUtil.nachricht("§7Keine Transaktionen für diesen Filter gefunden.");
        } else {
            ChatUtil.separator();
            for (TransactionEntry t : gefiltert) {
                transaktionDrucken(t, ichSpieler);
            }
        }

        ChatUtil.boxEnd();
        return 1;
    }

    private static void transaktionDrucken(TransactionEntry t, String ichSpieler) {
        boolean ichSender = t.getFromPlayer().equalsIgnoreCase(ichSpieler);
        String vonFarbe = ichSender ? "§c" : "§f";
        String zuFarbe = ichSender ? "§f" : "§a";

        String zeile =
                vonFarbe + t.getFromPlayer()
                + " §7→ "
                + zuFarbe + t.getToPlayer()
                + " §7| §6" + FormatUtil.formatiereBetrag(t.getAmount())
                + " §7[" + TimeUtil.formatDatumZeit(t.getTimestamp()) + "]";

        ChatUtil.sendRaw("  " + zeile);
    }

    private static Long parseRelativerZeitraum(String eingabe) {
        if (eingabe == null || eingabe.length() < 2) return null;
        String klein = eingabe.toLowerCase();
        char einheit = klein.charAt(klein.length() - 1);
        String zahlTeil = klein.substring(0, klein.length() - 1);
        int wert;
        try {
            wert = Integer.parseInt(zahlTeil);
        } catch (NumberFormatException e) {
            return null;
        }
        if (wert <= 0) return null;
        return switch (einheit) {
            case 't' -> (long) wert * 86_400_000L;
            case 'w' -> (long) wert * 7 * 86_400_000L;
            case 'm' -> (long) wert * 30 * 86_400_000L;
            case 'j' -> (long) wert * 365 * 86_400_000L;
            default -> null;
        };
    }

    private static Long parseDatum(String eingabe) {
        if (eingabe == null) return null;
        try {
            LocalDate datum = LocalDate.parse(eingabe, DATUM_FORMAT);
            return datum.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
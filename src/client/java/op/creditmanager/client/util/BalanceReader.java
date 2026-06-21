package op.creditmanager.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BalanceReader {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("[\\d][\\d.,]*(?:mrd|mio|kk|[kmb])?", Pattern.CASE_INSENSITIVE);

    private BalanceReader() {
    }

    public static OptionalDouble readCurrentBalance(MinecraftClient client) {
        if (client == null || client.world == null) return OptionalDouble.empty();
        try {
            Scoreboard scoreboard = client.world.getScoreboard();
            ScoreboardObjective sidebar = scoreboard.getObjectiveForSlot(net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
            if (sidebar == null) return OptionalDouble.empty();
            List<String> lines = scoreboard.getKnownScoreHolders().stream()
                    .filter(holder -> scoreboard.getScore(holder, sidebar) != null)
                    .sorted((first, second) -> Integer.compare(scoreboard.getScore(second, sidebar).getScore(),
                            scoreboard.getScore(first, sidebar).getScore()))
                    .map(holder -> lineText(scoreboard, holder))
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            for (int index = 0; index < lines.size() - 1; index++) {
                if (stripFormatting(lines.get(index)).toLowerCase(Locale.ROOT).contains("konto")) {
                    Double amount = parseAmount(stripFormatting(lines.get(index + 1)));
                    if (amount != null) return OptionalDouble.of(amount);
                }
            }
        } catch (Exception ignored) {
        }
        return OptionalDouble.empty();
    }

    private static String lineText(Scoreboard scoreboard, ScoreHolder holder) {
        Team team = scoreboard.getScoreHolderTeam(holder.getNameForScoreboard());
        if (team == null) return holder.getNameForScoreboard();
        String prefix = team.getPrefix() == null ? "" : team.getPrefix().getString();
        String suffix = team.getSuffix() == null ? "" : team.getSuffix().getString();
        return prefix + holder.getNameForScoreboard() + suffix;
    }

    private static String stripFormatting(String value) {
        return value == null ? "" : value.replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
    }

    private static Double parseAmount(String text) {
        Matcher matcher = AMOUNT_PATTERN.matcher(text == null ? "" : text);
        if (!matcher.find()) return null;
        try {
            return FormatUtil.parseDisplayAmount(matcher.group());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}

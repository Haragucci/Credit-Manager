package op.creditmanager.client.core;

import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.ChatUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public class PaymentDetector {

    private static final Pattern FORMATIERUNG_ENTFERNEN = Pattern.compile("§[0-9a-fk-or]");

    private static final Pattern GESENDET_PATTERN =
            Pattern.compile("OPSUCHT » Du hast ([^ ]+) ([\\d.,]+)\\$ gegeben\\.?");

    private static final Pattern EMPFANGEN_PATTERN =
            Pattern.compile("OPSUCHT » ([^ ]+) hat dir ([\\d.,]+)\\$ gegeben\\.?");

    private final Map<String, Long> kürzlicheTransaktionen = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> ältesterEintrag) {
            return size() > 100;
        }
    };

    private static final long DUPLIKAT_COOLDOWN_MS = 0_500;

    public void process(String nachricht) {
        if (nachricht == null || nachricht.isBlank()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        String bereinigt = FORMATIERUNG_ENTFERNEN.matcher(nachricht).replaceAll("");
        String ichSpieler = client.player.getName().getString().toLowerCase();

        CreditManagerClient.LOGGER.debug("[PaymentDetector] Verarbeite: " + bereinigt);

        Matcher gesendetMatcher = GESENDET_PATTERN.matcher(bereinigt);
        if (gesendetMatcher.find()) {
            String anSpieler = gesendetMatcher.group(1).toLowerCase();
            double betrag = parseBetrag(gesendetMatcher.group(2));
            if (betrag > 0) {
                transaktioneintragen(ichSpieler, anSpieler, betrag);
            }
            return;
        }

        Matcher empfangenMatcher = EMPFANGEN_PATTERN.matcher(bereinigt);
        if (empfangenMatcher.find()) {
            String vonSpieler = empfangenMatcher.group(1).toLowerCase();
            double betrag = parseBetrag(empfangenMatcher.group(2));
            if (betrag > 0) {
                transaktioneintragen(vonSpieler, ichSpieler, betrag);
            }
        }
    }

    private void transaktioneintragen(String vonSpieler, String anSpieler, double betrag) {
        String schlüssel = vonSpieler + "->" + anSpieler + ":" + betrag;
        long jetzt = System.currentTimeMillis();

        if (kürzlicheTransaktionen.containsKey(schlüssel)
                && (jetzt - kürzlicheTransaktionen.get(schlüssel)) < DUPLIKAT_COOLDOWN_MS) {
            CreditManagerClient.LOGGER.debug("[PaymentDetector] Duplikat ignoriert: " + schlüssel);
            return;
        }
        kürzlicheTransaktionen.put(schlüssel, jetzt);

        TransactionEntry eintrag = new TransactionEntry(vonSpieler, anSpieler, betrag);
        TransactionRepository.getInstance().add(eintrag);

        NumberFormat nf = NumberFormat.getNumberInstance(Locale.GERMANY);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);

        CreditManagerClient.LOGGER.info("[PaymentDetector] Transaktion geloggt: "
                + vonSpieler + " → " + anSpieler + " | " + nf.format(betrag) + "$");

        ChatUtil.erfolg("Transaktion: §f" + vonSpieler + " §7→ §f" + anSpieler
                + " §7| §6" + nf.format(betrag) + "$");
    }

    private double parseBetrag(String roh) {
        try {
            return Double.parseDouble(roh.replace(".", "").replace(",", "."));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
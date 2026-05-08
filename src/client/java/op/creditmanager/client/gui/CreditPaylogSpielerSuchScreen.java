package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CreditPaylogSpielerSuchScreen extends BasisScreen {

    private static final int SLOT_KEIN_FILTER = 45;
    private static final int SLOT_ZURÜCK      = 53;

    private static final int MAX_TREFFER = 28;

    private final CreditPaylogFilterScreen elternScreen;
    private final List<String> alleSpieler;
    private final String aktuellAusgewählt;

    private TextFieldWidget suchfeld;
    private List<String> treffer = new ArrayList<>();
    private final Map<Integer, String> slotZuSpieler = new HashMap<>();

    public CreditPaylogSpielerSuchScreen(CreditPaylogFilterScreen elternScreen,
                                         List<String> alleSpieler,
                                         String aktuellAusgewählt) {
        super(Text.literal("§8CreditManager §7» §fSuchen"), 6);
        this.elternScreen = elternScreen;
        this.alleSpieler = bereinigeSpielerListe(alleSpieler);
        this.aktuellAusgewählt = aktuellAusgewählt;
    }

    @Override
    protected void init() {
        super.init();

        int feldX = guiX + RAND;
        int feldY = guiY + 23;
        int feldW = guiBreite - RAND * 2;

        suchfeld = new TextFieldWidget(
                textRenderer,
                feldX,
                feldY,
                feldW,
                16,
                Text.literal("Spieler suchen")
        );

        suchfeld.setPlaceholder(Text.literal("Spielername eingeben..."));

        suchfeld.setChangedListener(text -> {
            aktualisiereTreffer();
            fülleSlots();
        });

        addDrawableChild(suchfeld);
        setFocused(suchfeld);
        suchfeld.setFocused(true);

        aktualisiereTreffer();
        fülleSlots();
    }

    @Override
    protected void fülleSlots() {
        slotZuSpieler.clear();

        for (int i = 0; i < anzahlSlots; i++) {
            setSlot(i, GuiHelper.schwarzGlas());
        }

        setSlot(SLOT_KEIN_FILTER, erstelleKeinFilterItem());
        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());

        int slotIdx = 0;

        for (String spieler : treffer) {
            if (slotIdx >= MAX_TREFFER) break;

            int reihe = slotIdx / 7;
            int spalte = slotIdx % 7;
            int slot = (reihe + 1) * 9 + spalte + 1;

            slotZuSpieler.put(slot, spieler);
            setSlot(slot, erstelleSpielerItem(spieler));

            slotIdx++;
        }

        if (treffer.isEmpty()) {
            setSlot(22, GuiHelper.erstelleItem(
                    new ItemStack(Items.BARRIER),
                    "§cKeine Spieler gefunden",
                    "§7Versuche einen anderen Suchbegriff.",
                    "",
                    "§8Beispiel: §fhar"
            ));
        }
    }

    private void aktualisiereTreffer() {
        String input = suchfeld == null ? "" : suchfeld.getText().trim().toLowerCase();

        treffer = alleSpieler.stream()
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> {
                    if (input.isBlank()) return true;

                    String lower = name.toLowerCase();

                    return lower.startsWith(input)
                            || lower.contains(input)
                            || fuzzyMatch(lower, input);
                })
                .sorted(Comparator
                        .comparingInt((String name) -> matchScore(name.toLowerCase(), input))
                        .thenComparing(String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_TREFFER)
                .collect(Collectors.toList());
    }

    private List<String> bereinigeSpielerListe(List<String> input) {
        Map<String, String> unique = new java.util.LinkedHashMap<>();

        if (input != null) {
            for (String name : input) {
                if (name == null || name.isBlank()) continue;

                String clean = name.trim();

                if (clean.equalsIgnoreCase("ich")
                        || clean.equalsIgnoreCase("me")
                        || clean.equalsIgnoreCase("Du selbst")) {
                    continue;
                }

                unique.putIfAbsent(clean.toLowerCase(), clean);
            }
        }

        return unique.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private ItemStack erstelleSpielerItem(String spieler) {
        ItemStack item = new ItemStack(Items.PLAYER_HEAD);

        SkinHeadUtil.setzeSkin(item, spieler, this::refreshWennAktiv);
        SkinHeadUtil.versteckeProfilTooltip(item);

        boolean ausgewählt = istAktuellAusgewählt(spieler);

        item.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal((ausgewählt ? "§a§l" : "§f§l") + spieler));

        item.remove(DataComponentTypes.ITEM_NAME);

        item.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                ausgewählt
                        ? Text.literal("§aAktuell ausgewählt")
                        : Text.literal("§7Diesen Spieler als"),
                ausgewählt
                        ? Text.literal("§7Dieser Spieler ist bereits aktiv.")
                        : Text.literal("§7Paylog-Filter auswählen."),
                Text.literal(""),
                Text.literal("§eKlicken zum Auswählen")
        )));

        return item;
    }

    private boolean istAktuellAusgewählt(String spieler) {
        if (aktuellAusgewählt == null) {
            return false;
        }

        return spieler.equalsIgnoreCase(aktuellAusgewählt);
    }

    private void refreshWennAktiv() {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.currentScreen == this) {
            fülleSlots();
        }
    }

    private ItemStack erstelleKeinFilterItem() {
        return GuiHelper.erstelleItem(
                new ItemStack(Items.BARRIER),
                "§cSpielerfilter entfernen",
                "§7Zeigt wieder deine eigenen Paylogs.",
                "",
                "§eKlicken"
        );
    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        if (slot == SLOT_KEIN_FILTER) {
            elternScreen.setSpielerFilterAuswahl(null);
            client.setScreen(elternScreen);
            return true;
        }

        String spieler = slotZuSpieler.get(slot);
        if (spieler != null) {
            elternScreen.setSpielerFilterAuswahl(spieler);
            client.setScreen(elternScreen);
            return true;
        }

        return false;
    }

    private boolean fuzzyMatch(String name, String input) {
        int ni = 0;
        int ii = 0;

        while (ni < name.length() && ii < input.length()) {
            if (name.charAt(ni) == input.charAt(ii)) {
                ii++;
            }
            ni++;
        }

        return ii == input.length();
    }

    private int matchScore(String name, String input) {
        if (input.isBlank()) return 10;
        if (name.equals(input)) return 0;
        if (name.startsWith(input)) return 1;
        if (name.contains(input)) return 2;
        return 3;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == 256) {
            MinecraftClient.getInstance().setScreen(elternScreen);
            return true;
        }

        return super.keyPressed(input);
    }
}
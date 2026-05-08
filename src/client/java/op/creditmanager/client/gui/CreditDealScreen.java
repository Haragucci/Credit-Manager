package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import java.util.Map;
import java.util.UUID;

public class CreditDealScreen extends BasisScreen {

    private static final int SLOT_SPIELERKOPF    = 4;
    private static final int SLOT_STATUS         = 1;
    private static final int SLOT_BETRAG         = 7;
    private static final int SLOT_ZAHLUNGEN_START = 10;
    private static final int SLOT_AKTION_ZAHLEN  = 20;
    private static final int SLOT_AKTION_LÖSCHEN = 24;
    private static final int SLOT_ZURÜCK         = 36;

    private boolean löschungAusstehend = false;
    private long    löschungTimestamp  = 0;
    private static final long BESTÄTIGUNG_TIMEOUT_MS = 30_000;

    private final CreditManager manager;
    private       CreditEntry   eintrag;
    private final boolean       istSchulden;
    private final Screen        elternScreen;
    private final String        ich;

    public static final Logger LOGGER = LoggerFactory.getLogger("assets/creditmanager");

    private final Map<Integer, Payment> slotZuLöschen = new HashMap<>();
    private boolean zahlungLöschungAusstehend = false;
    private UUID    zahlungLöschungId         = null;
    private long    zahlungLöschungTimestamp  = 0;

    private final Map<Integer, Payment> slotZuZahlung = new HashMap<>();

    public CreditDealScreen(CreditManager manager, CreditEntry eintrag,
                            boolean istSchulden, Screen elternScreen) {
        super(Text.literal("§8CreditManager §7» §fDeal Menü"), 5);
        this.manager      = manager;
        this.eintrag      = eintrag;
        this.istSchulden  = istSchulden;
        this.elternScreen = elternScreen;

        MinecraftClient client = MinecraftClient.getInstance();
        this.ich = client.player != null
                ? client.player.getName().getString().toLowerCase() : "";
    }

    @Override
    protected void fülleSlots() {
        manager.findCredit(eintrag.getDealName())
                .ifPresent(frisch -> eintrag = frisch);

        slotZuZahlung.clear();

        slotZuZahlung.clear();
        slotZuLöschen.clear();

        for (int i = 0; i < anzahlSlots; i++) setSlot(i, GuiHelper.schwarzGlas());
        for (int i = 36; i < 45; i++)         setSlot(i, GuiHelper.schwarzGlas());

        for (int i = 10; i <= 16; i++) setSlot(i, GuiHelper.trennGlas());


        setSlot(SLOT_SPIELERKOPF, erstelleSpielerKopf());
        setSlot(SLOT_STATUS,      erstelleStatusItem());
        setSlot(SLOT_BETRAG,      erstelleBetragItem());

        List<Payment> zahlungen = manager.getPaymentsForCredit(eintrag.getId());
        if (zahlungen.isEmpty()) {
            setSlot(13, GuiHelper.erstelleItem(
                    new ItemStack(Items.PAPER),
                    "§7Zahlungshistorie",
                    "§8Noch keine Zahlungen eingetragen."));
        } else {
            int start   = Math.max(0, zahlungen.size() - 7);
            int slotIdx = 0;
            for (int i = start; i < zahlungen.size(); i++) {
                int zielSlot = SLOT_ZAHLUNGEN_START + slotIdx;
                Payment p = zahlungen.get(i);

                if (zahlungLöschungAusstehend && p.getId().equals(zahlungLöschungId)) {
                    long verbleibend = Math.max(0,
                            BESTÄTIGUNG_TIMEOUT_MS - (System.currentTimeMillis() - zahlungLöschungTimestamp));
                    setSlot(zielSlot, GuiHelper.erstelleItem(
                            new ItemStack(Items.TNT),
                            "§c§l⚠ Zahlung löschen?",
                            "§7Betrag: §6" + FormatUtil.formatiereBetrag(p.getAmount()),
                            "",
                            "§cNicht rückgängig machbar!",
                            "§7Timeout: §e" + (verbleibend / 1000) + "s",
                            "",
                            "§c§lNochmals klicken zum Bestätigen"));
                    slotZuLöschen.put(zielSlot, p);
                } else {
                    setSlot(zielSlot, erstelleZahlungsItem(p));
                    if (p.getItems() != null && !p.getItems().isEmpty()) {
                        slotZuZahlung.put(zielSlot, p);
                    }
                    slotZuLöschen.put(zielSlot, p);
                }
                slotIdx++;
            }
            if (zahlungen.size() > 7) {
                setSlot(9, GuiHelper.erstelleItem(
                        new ItemStack(Items.PAPER),
                        "§7+" + (zahlungen.size() - 7) + " weitere",
                        "§8Ältere Zahlungen via §f/cm info " + eintrag.getDealName()));
            }
        }

        boolean abgeschlossen = "PAID".equals(eintrag.getStatus())
                || "CANCELLED".equals(eintrag.getStatus());

        if (!abgeschlossen) {
            setSlot(SLOT_AKTION_ZAHLEN, erstelleZahlenButton());
        } else {
            setSlot(SLOT_AKTION_ZAHLEN, GuiHelper.erstelleItem(
                    new ItemStack(Items.GRAY_DYE),
                    "§8Deal abgeschlossen",
                    "§8Keine weiteren Aktionen möglich."));
        }

        setSlot(SLOT_AKTION_LÖSCHEN, erstelleLöschenButton());
        setSlot(SLOT_ZURÜCK, GuiHelper.zurückButton());
    }

    private ItemStack erstelleSpielerKopf() {

        String gegenspieler = istSchulden
                ? eintrag.getCreditor()
                : eintrag.getDebtor();

        LOGGER.info("[DebtSystem] Schuldner: {}", eintrag.getDebtor());
        LOGGER.info("[DebtSystem] Gläubiger: {}", eintrag.getCreditor());
        LOGGER.info("[DebtSystem] Angezeigter Spieler (Kopf): {}", gegenspieler);

        ItemStack kopf = new ItemStack(Items.PLAYER_HEAD);

        try {
            SkinHeadUtil.setzeSkin(kopf, gegenspieler, this::fülleSlots);
            SkinHeadUtil.versteckeProfilTooltip(kopf);
        } catch (Exception e) {
            LOGGER.error("[DebtSystem] Fehler beim Erstellen des Spielerkopfs für {}", gegenspieler, e);
        }

        kopf.set(
                DataComponentTypes.CUSTOM_NAME,
                Text.literal("§f§l" + gegenspieler)
        );

        kopf.remove(DataComponentTypes.ITEM_NAME);

        List<Text> lore = new ArrayList<>();

        lore.add(Text.literal(""));

        lore.add(Text.literal(
                istSchulden
                        ? "§7Du schuldest diesem Spieler."
                        : "§7Dieser Spieler schuldet dir."
        ));

        lore.add(Text.literal(""));

        lore.add(Text.literal(
                "§7Erstellt: §8" + TimeUtil.formatDatumZeit(eintrag.getCreatedAt())
        ));

        if (eintrag.getDueDate() != null) {
            lore.add(Text.literal(
                    "§7Fällig:   " + TimeUtil.getDueDateDisplay(eintrag.getDueDate())
            ));
        }

        if (eintrag.getNote() != null && !eintrag.getNote().isBlank()) {
            lore.add(Text.literal(""));
            lore.add(Text.literal("§7Notiz: §9" + eintrag.getNote()));
        }

        kopf.set(
                DataComponentTypes.LORE,
                new LoreComponent(lore)
        );

        return kopf;
    }

    private ItemStack erstelleStatusItem() {
        String statusText = switch (eintrag.getStatus()) {
            case "PAID"      -> "§aBezahlt ✔";
            case "PARTIAL"   -> "§eTeilweise bezahlt";
            case "CANCELLED" -> "§8Storniert";
            default          -> "§cOffen";
        };
        net.minecraft.item.Item icon = switch (eintrag.getStatus()) {
            case "PAID"      -> Items.LIME_STAINED_GLASS_PANE;
            case "PARTIAL"   -> Items.YELLOW_STAINED_GLASS_PANE;
            case "CANCELLED" -> Items.GRAY_STAINED_GLASS_PANE;
            default          -> Items.RED_STAINED_GLASS_PANE;
        };

        double fortschritt = eintrag.getAmount() > 0
                ? eintrag.getPaidAmount() / eintrag.getAmount() * 100 : 0;

        return GuiHelper.erstelleItem(
                new ItemStack(icon),
                "§7Status: " + statusText,
                "",
                "§7Bezahlt:   §a" + FormatUtil.formatiereBetrag(eintrag.getPaidAmount()),
                "§7Offen:     §c" + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()),
                "§7Gesamt:    §f" + FormatUtil.formatiereBetrag(eintrag.getAmount()),
                "",
                "§7Fortschritt: §e" + String.format("%.1f", fortschritt) + "§7%"
        );
    }

    private ItemStack erstelleBetragItem() {
        String betragFarbe = istSchulden ? "§c" : "§a";
        return GuiHelper.erstelleItem(
                new ItemStack(istSchulden ? Items.REDSTONE : Items.EMERALD),
                betragFarbe + "§l" + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()),
                "§7Noch ausstehender Betrag",
                "",
                "§7Gesamt:  §f" + FormatUtil.formatiereBetrag(eintrag.getAmount()),
                "§7Bezahlt: §a" + FormatUtil.formatiereBetrag(eintrag.getPaidAmount())
        );
    }

    private ItemStack erstelleZahlungsItem(Payment p) {
        boolean ichWarSender = p.getFromPlayer().equalsIgnoreCase(ich);
        boolean istItemZahlung = p.getItems() != null && !p.getItems().isEmpty();
        net.minecraft.item.Item icon = istItemZahlung ? Items.CHEST : Items.FEATHER;

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§7Von: §f" + p.getFromPlayer()));
        lore.add(Text.literal("§7An:  §f" + p.getToPlayer()));
        lore.add(Text.literal(""));

        if (istItemZahlung) {
            lore.add(Text.literal("§dItem-Zahlung:"));
            for (String item : p.getItems()) {
                lore.add(Text.literal("  §f" + item));
            }
            if (p.getAmount() != null && p.getAmount() > 0) {
                lore.add(Text.literal("§7Wert: §6" + FormatUtil.formatiereBetrag(p.getAmount())));
            }
            lore.add(Text.literal(""));
            lore.add(Text.literal("§eKlicken zum Inspizieren"));
        } else {
            lore.add(Text.literal("§7Betrag: §6" + FormatUtil.formatiereBetrag(p.getAmount())));
        }

        lore.add(Text.literal(""));
        lore.add(Text.literal("§8" + TimeUtil.formatDatumZeit(p.getTimestamp())));

        String titel = istItemZahlung
                ? "§dItem-Zahlung"
                : (ichWarSender ? "§c-" : "§a+") + FormatUtil.formatiereBetrag(p.getAmount());

        lore.add(Text.literal(""));
        lore.add(Text.literal("§cLinksklick zum Löschen"));

        ItemStack stack = new ItemStack(icon);
        if (istItemZahlung) {
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of()));
        } else {
            stack.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(), List.of(), List.of("5.0"), List.of()));
        }
        stack.set(DataComponentTypes.ITEM_NAME, Text.literal(titel));
        stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return stack;
    }

    private ItemStack erstelleZahlenButton() {
        ItemStack zahlenItem = new ItemStack(Items.LIME_DYE);
        zahlenItem.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of()));

        if (istSchulden) {
            return GuiHelper.erstelleItem(
                    zahlenItem,
                    "§a§lZahlung eintragen",
                    "§7Trägt eine Teilzahlung oder",
                    "§7Vollzahlung für diesen Deal ein.",
                    "",
                    "§8Öffnet Eingabeformular",
                    "",
                    "§eKlicken");
        } else {
            return GuiHelper.erstelleItem(
                    zahlenItem,
                    "§a§lZahlung empfangen",
                    "§7Bestätigt eine erhaltene Zahlung",
                    "§7für diesen Deal.",
                    "",
                    "§8Öffnet Eingabeformular",
                    "",
                    "§eKlicken");
        }
    }

    private ItemStack erstelleLöschenButton() {
        if (löschungAusstehend) {
            long verbleibend = Math.max(0,
                    BESTÄTIGUNG_TIMEOUT_MS - (System.currentTimeMillis() - löschungTimestamp));
            return GuiHelper.erstelleItem(
                    new ItemStack(Items.TNT),
                    "§c§l⚠ WIRKLICH LÖSCHEN?",
                    "§7Deal: §f" + eintrag.getDealName(),
                    "§7Betrag: §6" + FormatUtil.formatiereBetrag(eintrag.getAmount()),
                    "",
                    "§cDies kann nicht rückgängig gemacht werden!",
                    "",
                    "§7Timeout: §e" + (verbleibend / 1000) + "s",
                    "",
                    "§c§lNochmals klicken zum Bestätigen");
        } else {
            return GuiHelper.erstelleItem(
                    new ItemStack(Items.BARRIER),
                    "§c§lDeal löschen",
                    "§7Löscht diesen Deal dauerhaft.",
                    "§7Alle Zahlungen werden entfernt.",
                    "",
                    "§8Erfordert Bestätigung",
                    "",
                    "§eKlicken");
        }
    }


    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (slot == SLOT_ZURÜCK) {
            client.setScreen(elternScreen);
            return true;
        }

        if (slotZuZahlung.containsKey(slot)) {
            client.setScreen(new ItemInspektionScreen(slotZuZahlung.get(slot), this));
            return true;
        }

        if (slot == SLOT_AKTION_ZAHLEN) {
            boolean abgeschlossen = "PAID".equals(eintrag.getStatus())
                    || "CANCELLED".equals(eintrag.getStatus());
            if (!abgeschlossen) {
                client.setScreen(new CreditZahlungScreen(manager, eintrag, istSchulden, this));
            }
            return true;
        }

        if (slot == SLOT_AKTION_LÖSCHEN) {
            if (!löschungAusstehend) {
                löschungAusstehend = true;
                löschungTimestamp  = System.currentTimeMillis();
                fülleSlots();
            } else {
                if (System.currentTimeMillis() - löschungTimestamp > BESTÄTIGUNG_TIMEOUT_MS) {
                    löschungAusstehend = false;
                    fülleSlots();
                } else {
                    try {
                        manager.deleteCredit(eintrag.getId());
                        client.setScreen(elternScreen);
                    } catch (CreditManager.CreditException e) {
                        löschungAusstehend = false;
                        fülleSlots();
                    }
                }
            }
            return true;
        }

        if (slotZuLöschen.containsKey(slot)) {
            Payment p = slotZuLöschen.get(slot);
            if (slotZuZahlung.containsKey(slot)) {
                client.setScreen(new ItemInspektionScreen(slotZuZahlung.get(slot), this));
                return true;
            }
            if (!zahlungLöschungAusstehend || !p.getId().equals(zahlungLöschungId)) {
                zahlungLöschungAusstehend = true;
                zahlungLöschungId        = p.getId();
                zahlungLöschungTimestamp = System.currentTimeMillis();
                fülleSlots();
            } else {
                if (System.currentTimeMillis() - zahlungLöschungTimestamp > BESTÄTIGUNG_TIMEOUT_MS) {
                    zahlungLöschungAusstehend = false;
                    fülleSlots();
                } else {
                    try {
                        manager.deletePayment(p.getId());
                        zahlungLöschungAusstehend = false;
                        fülleSlots();
                    } catch (CreditManager.CreditException ex) {
                        zahlungLöschungAusstehend = false;
                        fülleSlots();
                    }
                }
            }
            return true;
        }

        return false;
    }
}
package op.creditmanager.client.gui;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.util.FormatUtil;


import java.util.List;

public class CreditHauptmenüScreen extends BasisScreen {

    private final CreditManager manager;

    private static final int SLOT_FORDERUNG  = 2;
    private static final int SLOT_SCHULDEN   = 6;
    private static final int SLOT_ÜBERSICHT  = 20;
    private static final int SLOT_TRANSLOG   = 24;
    private static final int SLOT_KOPF = 13;

    public CreditHauptmenüScreen(CreditManager manager) {
        super(Text.literal("§8CreditManager §7» §fHauptmenü"), 3);
        this.manager = manager;
    }


    @Override
    protected void fülleSlots() {
        MinecraftClient client = MinecraftClient.getInstance();
        String ich = client.player != null
                ? client.player.getName().getString().toLowerCase()
                : "";

        for (int i = 0; i < 27; i++) setSlot(i, GuiHelper.schwarzGlas());

        long offeneForderungen = manager.getOpenCreditsAsCreditor(ich).size();
        long offeneSchulden    = manager.getOpenCreditsAsDebtor(ich).size();

        double gesamtForderungen = manager.getOpenCreditsAsCreditor(ich).stream()
                .mapToDouble(e -> e.getRemainingAmount()).sum();

        double gesamtSchulden = manager.getOpenCreditsAsDebtor(ich).stream()
                .mapToDouble(e -> e.getRemainingAmount()).sum();


        ItemStack forderung = new ItemStack(Items.FEATHER);
        forderung.set(DataComponentTypes.ITEM_NAME, Text.literal("§a§lForderungen"));
        forderung.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("1.0"), List.of())
        );

        forderung.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Jemand schuldet dir Geld"),
                Text.literal(""),
                Text.literal("§7Offen: §a§l" + offeneForderungen),
                Text.literal("§7Gesamt: §a" + FormatUtil.formatiereBetrag(gesamtForderungen)),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));
        setSlot(SLOT_FORDERUNG, forderung);



        ItemStack schulden = new ItemStack(Items.FEATHER);
        schulden.set(DataComponentTypes.ITEM_NAME, Text.literal("§c§lSchulden"));
        schulden.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("2.0"), List.of())
        );

        schulden.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Du schuldest jemandem Geld"),
                Text.literal(""),
                Text.literal("§7Offen: §c§l" + offeneSchulden),
                Text.literal("§7Gesamt: §c" + FormatUtil.formatiereBetrag(gesamtSchulden)),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));
        setSlot(SLOT_SCHULDEN, schulden);

        double saldo = gesamtForderungen - gesamtSchulden;
        String saldoFarbe = saldo >= 0 ? "§a" : "§c";

        ItemStack übersicht = new ItemStack(Items.FEATHER);
        übersicht.set(DataComponentTypes.ITEM_NAME, Text.literal("§6§lÜbersicht"));
        übersicht.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("3.0"), List.of())
        );

        übersicht.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Saldo & Kontostand"),
                Text.literal(""),
                Text.literal("§7Saldo: " + saldoFarbe + (saldo >= 0 ? "+" : "")
                        + FormatUtil.formatiereBetrag(saldo)),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));
        setSlot(SLOT_ÜBERSICHT, übersicht);

        ItemStack translog = new ItemStack(Items.FEATHER);
        translog.set(DataComponentTypes.ITEM_NAME, Text.literal("§b§lPaylogs"));

        translog.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("4.0"), List.of())
        );

        translog.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal("§7Automatisch erkannte Zahlungen"),
                Text.literal(""),
                Text.literal("§eKlicken zum Öffnen")
        )));
        setSlot(SLOT_TRANSLOG, translog);

        ItemStack kopf = new ItemStack(Items.PLAYER_HEAD);

        GameProfile profile = client.player.getGameProfile();
        kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(profile));

        kopf.set(DataComponentTypes.CUSTOM_NAME,
                Text.literal("§8Credit Manager §7» §f" + client.player.getName().getString()));

        String saldoAnzeige = saldo >= 0
                ? "§a§l+" + FormatUtil.formatiereBetrag(saldo) + " §7(Guthaben)"
                : "§c§l"  + FormatUtil.formatiereBetrag(saldo) + " §7(Schulden)";

        kopf.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Text.literal(""),
                Text.literal("§7Forderungen: §a" + FormatUtil.formatiereBetrag(gesamtForderungen)),
                Text.literal("§7Schulden:    §c" + FormatUtil.formatiereBetrag(gesamtSchulden)),
                Text.literal(""),
                Text.literal("§7Saldo: " + saldoAnzeige)
        )));

        kopf.remove(DataComponentTypes.ITEM_NAME);

        setSlot(SLOT_KOPF, kopf);

    }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (slot == SLOT_FORDERUNG) {
            client.setScreen(new CreditListeScreen(manager, false, this));
            return true;
        }
        if (slot == SLOT_SCHULDEN) {
            client.setScreen(new CreditListeScreen(manager, true, this));
            return true;
        }
        if (slot == SLOT_ÜBERSICHT) {
            client.setScreen(new CreditÜbersichtScreen(manager, this));
            return true;
        }
        if (slot == SLOT_TRANSLOG) {
            client.setScreen(new CreditPaylogScreen(manager, this));
            return true;
        }
        return false;
    }
}
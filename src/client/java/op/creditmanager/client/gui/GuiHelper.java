package op.creditmanager.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.List;

public class GuiHelper {

    public static ItemStack erstelleItem(ItemStack stack, String name, String... loreZeilen) {
        stack.set(DataComponentTypes.ITEM_NAME, Text.literal(name));
        if (loreZeilen.length > 0) {
            List<Text> lore = new java.util.ArrayList<>();
            for (String z : loreZeilen) lore.add(Text.literal(z));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lore));
        }
        return stack;
    }

    public static ItemStack trennGlas() {
        ItemStack g = new ItemStack(Items.GREEN_STAINED_GLASS_PANE);
        g.set(DataComponentTypes.ITEM_NAME, Text.literal("§8"));
        return g;
    }

    public static ItemStack schwarzGlas() {
        ItemStack g = new ItemStack(Items.BLACK_STAINED_GLASS_PANE);
        g.set(DataComponentTypes.ITEM_NAME, Text.literal("§8"));
        return g;
    }

    public static ItemStack zurückButton() {
        ItemStack stack = erstelleItem(
                new ItemStack(Items.ARROW),
                "§f◀ Zurück",
                "§7Zum vorherigen Menü"
        );

        stack.set(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                new CustomModelDataComponent(List.of(), List.of(), List.of("3.0"), List.of())
        );

        return stack;
    }
}

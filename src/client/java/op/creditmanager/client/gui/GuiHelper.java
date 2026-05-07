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

    public static final int GUI_WIDTH    = 176;
    public static final int SLOT_SIZE    = 18;
    public static final int RAND_LINKS   = 8;
    public static final int RAND_OBEN    = 18;
    public static final int TITEL_Y      = 6;
    public static final int COLS         = 9;

    public static final int FARBE_HINTERGRUND  = 0xFF8B8B8B;
    public static final int FARBE_SLOT_RAND    = 0xFF373737;
    public static final int FARBE_SLOT_INNEN   = 0xFF8B8B8B;
    public static final int FARBE_SLOT_HELL    = 0xFFFFFFFF;
    public static final int FARBE_HOVER        = 0x80FFFFFF;
    public static final int FARBE_TITEL_BG     = 0xFFc6c6c6;

    public static void drawInventarHintergrund(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x,     y,     x + w,     y + h,     0xFF373737);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFc6c6c6);
    }

    public static void drawSlot(DrawContext ctx, int sx, int sy) {
        ctx.fill(sx,                sy,                sx + SLOT_SIZE - 1, sy + 1,              0xFF555555);
        ctx.fill(sx,                sy,                sx + 1,             sy + SLOT_SIZE - 1,  0xFF555555);
        ctx.fill(sx + 1,      sy + SLOT_SIZE - 1, sx + SLOT_SIZE, sy + SLOT_SIZE,   0xFFFFFFFF);
        ctx.fill(sx + SLOT_SIZE - 1, sy + 1, sx + SLOT_SIZE, sy + SLOT_SIZE,        0xFFFFFFFF);
        ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, 0xFF8B8B8B);
    }

    public static void drawSlotHover(DrawContext ctx, int sx, int sy) {
        ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, FARBE_HOVER);
    }

    public static void drawSlotItem(DrawContext ctx, ItemStack stack, int sx, int sy) {
        if (stack == null || stack.isEmpty()) return;
        ctx.drawItem(stack, sx + 1, sy + 1);
    }

    public static ItemStack erstelleItem(net.minecraft.item.ItemConvertible item, String name, String... loreZeilen) {
        return erstelleItem(new ItemStack(item, 1), name, loreZeilen);
    }

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
        ItemStack g = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
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

    public static boolean isHovered(int sx, int sy, double mx, double my) {
        return mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE;
    }

    public static int slotX(int guiX, int slot) {
        return guiX + RAND_LINKS + (slot % COLS) * SLOT_SIZE;
    }

    public static int slotY(int guiY, int slot, int kopfHöhe) {
        return guiY + kopfHöhe + (slot / COLS) * SLOT_SIZE;
    }
}
package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

public abstract class BasisScreen extends Screen {

    protected static final int COLS      = 9;
    protected static final int SLOT_SIZE = 18;
    protected static final int RAND      = 8;

    private static final int NAV_HÖHE    = 20;
    private static final int KOPF_GESAMT = NAV_HÖHE + 2;

    private static final int C_PANEL_BG    = 0xFF1E1E2E;
    private static final int C_RAND_AUSSEN = 0xFF0D0D1A;
    private static final int C_RAND_ECKE   = 0xFF161626;
    private static final int C_HIGHLIGHT   = 0xFF2A2A3E;
    private static final int C_SCHATTEN    = 0xFF0A0A14;

    private static final int C_NAV_BG     = 0xFF12121F;
    private static final int C_NAV_BORDER = 0xFF2E2E4A;
    private static final int C_NAV_PREFIX = 0xFF666677;
    private static final int C_NAV_PFEIL  = 0xFF4A9E4A;
    private static final int C_NAV_SEITE  = 0xFFFFFFFF;

    private static final int C_SLOT_D    = 0xFF0D0D1A;
    private static final int C_SLOT_H    = 0xFF2E2E4A;
    private static final int C_SLOT_BG   = 0xFF181828;
    private static final int C_HOVER     = 0x40FFFFFF;

    private static final int C_TRENN_D   = 0xFF0A0A14;
    private static final int C_TRENN_H   = 0xFF2A2A3E;

    protected int guiX, guiY, guiBreite;
    protected int anzahlSlots;
    protected ItemStack[] slots;

    private static final int KOPF_GESAMT_LEGACY = NAV_HÖHE - 2;

    private final int guiReihen;
    protected int gesamtHöhe;

    private ItemStack tooltipStack;
    private int tooltipX, tooltipY;

    protected BasisScreen(Text titel, int reihen) {
        super(titel);
        this.guiReihen   = reihen;
        this.anzahlSlots = reihen * COLS;
        this.slots       = new ItemStack[anzahlSlots];
        this.guiBreite   = COLS * SLOT_SIZE + RAND * 2;
        this.gesamtHöhe  = KOPF_GESAMT + guiReihen * SLOT_SIZE + RAND;
    }

    protected BasisScreen(Text titel, int reihen, boolean legacyLayout) {
        super(titel);
        this.guiReihen   = reihen;
        this.anzahlSlots = reihen * COLS;
        this.slots       = new ItemStack[anzahlSlots];
        this.guiBreite   = COLS * SLOT_SIZE + RAND * 2;
        this.gesamtHöhe  = KOPF_GESAMT_LEGACY + guiReihen * SLOT_SIZE + 4;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.options.inventoryKey.matchesKey(input)) {
            if (istEingabeFeldAktiv()) {
                return super.keyPressed(input);
            }

            client.setScreen(null);
            return true;
        }

        return super.keyPressed(input);
    }

    protected boolean istEingabeFeldAktiv() {
        return getFocused() instanceof TextFieldWidget feld && feld.isFocused();
    }

    @Override
    protected void init() {
        guiX = (width  - guiBreite)  / 2;
        guiY = (height - gesamtHöhe) / 2;
        fülleSlots();
    }

    protected abstract void fülleSlots();

    protected void setSlot(int index, ItemStack stack) {
        if (index >= 0 && index < slots.length) slots[index] = stack;
    }

    protected int slotX(int slot) { return guiX + RAND + (slot % COLS) * SLOT_SIZE; }
    protected int slotY(int slot) { return guiY + KOPF_GESAMT + (slot / COLS) * SLOT_SIZE; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {

        tooltipStack = null;

        drawPanel(ctx);
        drawNav(ctx);
        drawTrennlinie(ctx);
        drawSlotVertiefungen(ctx);
        drawGuiSlots(ctx, mouseX, mouseY);

        super.render(ctx, mouseX, mouseY, delta);

        if (tooltipStack != null)
            ctx.drawItemTooltip(textRenderer, tooltipStack, tooltipX, tooltipY);
    }

    private void drawPanel(DrawContext ctx) {
        int x = guiX, y = guiY, w = guiBreite, h = gesamtHöhe;

        ctx.fill(x + 2, y,     x + w - 2, y + h,     C_PANEL_BG);
        ctx.fill(x,     y + 2, x + 2,     y + h - 2, C_PANEL_BG);
        ctx.fill(x + w - 2, y + 2, x + w, y + h - 2, C_PANEL_BG);

        ctx.fill(x + 2, y,     x + w - 2, y + 1,     C_RAND_AUSSEN);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, C_RAND_AUSSEN);
        ctx.fill(x,     y + 2, x + 1,     y + h - 2, C_RAND_AUSSEN);
        ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, C_RAND_AUSSEN);

        ctx.fill(x + 1, y + 1, x + 2, y + 2, C_RAND_ECKE);
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, C_RAND_ECKE);
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, C_RAND_ECKE);
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, C_RAND_ECKE);

        ctx.fill(x + 2, y + 1,     x + w - 2, y + 2,     C_HIGHLIGHT);
        ctx.fill(x + 1, y + 2,     x + 2,     y + h - 2, C_HIGHLIGHT);

        ctx.fill(x + 2,     y + h - 2, x + w - 2, y + h - 1, C_SCHATTEN);
        ctx.fill(x + w - 2, y + 2,     x + w - 1, y + h - 2, C_SCHATTEN);
    }

    private void drawNav(DrawContext ctx) {
        int x = guiX, y = guiY, w = guiBreite;

        ctx.fill(x + 2, y + 1,     x + w - 2, y + NAV_HÖHE, C_NAV_BG);
        ctx.fill(x + 1, y + 2,     x + w - 1, y + NAV_HÖHE, C_NAV_BG);
        ctx.fill(x + 2, y + NAV_HÖHE - 1, x + w - 2, y + NAV_HÖHE, C_NAV_BORDER);

        String roh      = getTitle().getString().replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        String seite    = roh.contains("»") ? roh.substring(roh.indexOf("»") + 2).trim() : roh;
        seite           = seite.replace("»", "").trim();
        if (seite.isEmpty()) seite = roh;

        int textY   = y + (NAV_HÖHE - 8) / 2 + 1;
        int curX    = x + RAND;
        String pre  = "§8Credit Manager";
        String arr  = " §7» ";

        ctx.drawText(textRenderer, Text.literal(pre), curX, textY, C_NAV_PREFIX, false);
        curX += textRenderer.getWidth(pre);
        ctx.drawText(textRenderer, Text.literal(arr), curX, textY, C_NAV_PFEIL, false);
        curX += textRenderer.getWidth(arr);
        ctx.drawText(textRenderer, Text.literal("§l" + seite), curX, textY, C_NAV_SEITE, false);
    }

    private void drawTrennlinie(DrawContext ctx) {
        int x = guiX, w = guiBreite;
        int tl = guiY + NAV_HÖHE;
        ctx.fill(x + 2, tl,     x + w - 2, tl + 1, C_TRENN_D);
        ctx.fill(x + 2, tl + 1, x + w - 2, tl + 2, C_TRENN_H);
    }

    private void drawSlotVertiefungen(DrawContext ctx) {
        for (int i = 0; i < anzahlSlots; i++)
            drawSlotVertiefung(ctx, slotX(i), slotY(i));
    }

    protected void drawSlotVertiefung(DrawContext ctx, int sx, int sy) {
        ctx.fill(sx,                 sy,                 sx + SLOT_SIZE,     sy + 1,              C_SLOT_D);
        ctx.fill(sx,                 sy,                 sx + 1,             sy + SLOT_SIZE,      C_SLOT_D);
        ctx.fill(sx + 1,             sy + SLOT_SIZE - 1, sx + SLOT_SIZE,     sy + SLOT_SIZE,      C_SLOT_H);
        ctx.fill(sx + SLOT_SIZE - 1, sy + 1,             sx + SLOT_SIZE,     sy + SLOT_SIZE,      C_SLOT_H);
        ctx.fill(sx + 1,             sy + 1,             sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1,  C_SLOT_BG);
    }

    private void drawGuiSlots(DrawContext ctx, int mouseX, int mouseY) {
        for (int i = 0; i < anzahlSlots; i++) {
            ItemStack s = slots[i];
            int sx = slotX(i), sy = slotY(i);
            if (isHov(sx, sy, mouseX, mouseY)) {
                ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, C_HOVER);
                if (s != null && !s.isEmpty()) { tooltipStack = s; tooltipX = mouseX; tooltipY = mouseY; }
            }
            if (s != null && !s.isEmpty()) {
                ctx.drawItem(s, sx + 1, sy + 1);
            }
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (click.button() == 0) {
            double mx = click.x(), my = click.y();
            for (int i = 0; i < anzahlSlots; i++) {
                if (slots[i] != null && !slots[i].isEmpty() && isHov(slotX(i), slotY(i), mx, my))
                    if (onSlotKlick(i, slots[i])) return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    protected boolean onSlotKlick(int slot, ItemStack stack) { return false; }

    protected boolean isHov(int sx, int sy, double mx, double my) {
        return mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE;
    }

    @Override
    public boolean shouldPause() { return false; }
}
package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CreditZahlungScreen extends BasisScreen {

    private static final int INV_COLS   = 9;
    private static final int NAV_H      = 22;
    private static final int FELD_HÖHE  = 14;
    private static final int BTN_BREITE = 110;
    private static final int BTN_HÖHE   = 20;
    private static final int PANEL_B    = INV_COLS * SLOT_SIZE + RAND * 2 + 60;

    private static final int INFO_H = 10 + 3 + 10;

    private static final int PANEL_H_GELD =
            NAV_H + 2
                    + 10
                    + INFO_H
                    + 10
                    + 20
                    + 12
                    + 10
                    + 4
                    + FELD_HÖHE
                    + 16
                    + BTN_HÖHE
                    + 14
                    + 8;

    private static final int PANEL_H_ITEM =
            NAV_H + 2
                    + 10
                    + INFO_H
                    + 10
                    + 20
                    + 12
                    + 10
                    + 4
                    + SLOT_SIZE
                    + 16
                    + 10
                    + 4
                    + FELD_HÖHE
                    + 10
                    + 10
                    + 3 * SLOT_SIZE
                    + 4
                    + 2
                    + SLOT_SIZE
                    + 14
                    + BTN_HÖHE
                    + 14
                    + 8;

    private static final int C_PANEL_BG    = ClassicUiColors.PANEL;
    private static final int C_RAND_AUSSEN = ClassicUiColors.OUTER_BORDER;
    private static final int C_RAND_ECKE   = ClassicUiColors.CORNER;
    private static final int C_HIGHLIGHT   = ClassicUiColors.HIGHLIGHT;
    private static final int C_DUNKEL      = ClassicUiColors.SHADOW;

    private static final int C_DROP_1 = 0x40000000;
    private static final int C_DROP_2 = 0x28000000;
    private static final int C_DROP_3 = 0x14000000;

    private static final int C_NAV_BG     = ClassicUiColors.NAVIGATION;
    private static final int C_NAV_PREFIX = ClassicUiColors.MUTED;
    private static final int C_NAV_PFEIL  = ClassicUiColors.LIME;
    private static final int C_NAV_SEITE  = ClassicUiColors.TEXT;

    private static final int C_TRENN_D = ClassicUiColors.SHADOW;
    private static final int C_TRENN_H = ClassicUiColors.HIGHLIGHT;
    private static final int C_LABEL   = ClassicUiColors.MUTED;

    private static final int C_SLOT_D  = ClassicUiColors.OUTER_BORDER;
    private static final int C_SLOT_H  = ClassicUiColors.SLOT_EDGE;
    private static final int C_SLOT_BG = ClassicUiColors.SLOT;
    private static final int C_HOVER   = ClassicUiColors.HOVER;

    private static final int C_TAB_GELD_AKT   = ClassicUiColors.PRIMARY;
    private static final int C_TAB_GELD_AKT_L = ClassicUiColors.PRIMARY_HOVER;
    private static final int C_TAB_ITEM_AKT   = ClassicUiColors.GOLD_DARK;
    private static final int C_TAB_ITEM_AKT_L = ClassicUiColors.GOLD;
    private static final int C_TAB_INK        = ClassicUiColors.SLOT;
    private static final int C_TAB_HOV        = ClassicUiColors.SLOT_EDGE;

    private static final int C_ZIEL_ITEM = ClassicUiColors.ITEM_GLOW;

    private static final int C_BTN_OK   = ClassicUiColors.PRIMARY;
    private static final int C_BTN_OK_H = ClassicUiColors.PRIMARY_HOVER;
    private static final int C_BTN_AB   = ClassicUiColors.DANGER;
    private static final int C_BTN_AB_H = ClassicUiColors.DANGER_HOVER;

    private static final int C_FEHLER = ClassicUiColors.ERROR;
    private static final int C_ERFOLG = ClassicUiColors.LIME;

    private static final int C_TEXT_ED = ClassicUiColors.TEXT;
    private static final int C_TEXT_PH = ClassicUiColors.MUTED_DARK;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "creditmanager-zahlung-scheduler");
                t.setDaemon(true);
                return t;
            });

    private enum Modus { GELD, ITEM }
    private Modus modus = Modus.GELD;

    private final CreditManager manager;
    private       CreditEntry   eintrag;
    private final boolean       istSchulden;
    private final net.minecraft.client.gui.screen.Screen elternScreen;
    private final String        ich;

    private TextFieldWidget feldBetrag;
    private TextFieldWidget feldVerrechnungswert;

    private String fehlerText = null;
    private String erfolgText = null;

    private ItemStack gewähltesItem  = ItemStack.EMPTY;
    private int       gewählteAnzahl = 1;
    private final Map<Integer, Integer> selectedInventorySlots = new LinkedHashMap<>();

    private ItemStack tooltipStack;
    private int tooltipX, tooltipY;

    private int pX, pY, pHöhe;
    private float panelScale = 1.0F;
    private int yInfo, yTabs, yEingabe, yInvLabel, yInv, yHotbar, yButtons, yStatus;

    public CreditZahlungScreen(CreditManager manager, CreditEntry eintrag,
                               boolean istSchulden,
                               net.minecraft.client.gui.screen.Screen elternScreen) {
        super(Text.literal(istSchulden ? "Zahlung leisten" : "Zahlung empfangen"), 0, true);
        this.manager      = manager;
        this.eintrag      = eintrag;
        this.istSchulden  = istSchulden;
        this.elternScreen = elternScreen;
        MinecraftClient mc = MinecraftClient.getInstance();
        this.ich = mc.player != null ? mc.player.getName().getString().toLowerCase() : "";
    }

    @Override
    protected void init() {
        pHöhe = (modus == Modus.GELD) ? PANEL_H_GELD : PANEL_H_ITEM;
        panelScale = GuiScaleUtil.compactPanelScale(width, height, PANEL_B, pHöhe);
        pX = GuiScaleUtil.centeredX(width, panelScale, PANEL_B);
        pY = GuiScaleUtil.centeredY(height, panelScale, pHöhe);

        guiX      = pX;
        guiY      = pY;
        guiBreite = PANEL_B;

        int cur  = pY + NAV_H + 2 + 12;
        yInfo    = cur; cur += 10 + 10;
        yTabs    = cur; cur += 20 + 14;
        yEingabe = cur;

        if (modus == Modus.GELD) {
            cur += 10 + 4 + FELD_HÖHE + 16;
        } else {
            cur += 10 + 4 + SLOT_SIZE + 16 + 10 + 4 + FELD_HÖHE + 10;
            yInvLabel = cur; cur += 10;
            yInv      = cur; cur += 3 * SLOT_SIZE + 4 + 2;
            yHotbar   = cur; cur += SLOT_SIZE + 14;
        }
        yButtons = cur; cur += BTN_HÖHE + 4;
        yStatus  = cur;

        clearChildren();

        if (modus == Modus.GELD) {
            int fx = pX + RAND;
            int fy = yEingabe + 10 + 4;
            feldBetrag = neuesFeld(fx, fy, PANEL_B - RAND * 2, FELD_HÖHE, 20);
            feldBetrag.setPlaceholder(Text.literal(
                    "Max: " + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount())));
            addDrawableChild(feldBetrag);
            setInitialFocus(feldBetrag);
        } else {
            int fx = pX + RAND;
            int fy = yEingabe + 10 + 4 + SLOT_SIZE + 16 + 10 + 4;
            feldVerrechnungswert = neuesFeld(fx, fy, PANEL_B - RAND * 2, FELD_HÖHE, 20);
            feldVerrechnungswert.setPlaceholder(Text.literal(
                    "Verrechnungswert, z.B. 500"));
            addDrawableChild(feldVerrechnungswert);
        }
    }

    @Override
    protected void fülleSlots() {}

    private TextFieldWidget neuesFeld(int x, int y, int breite, int höhe, int maxLen) {
        TextFieldWidget f = new CenteredTextFieldWidget(textRenderer, x, y - 2, breite, höhe + 4, Text.empty());
        f.setMaxLength(maxLen);
        f.setEditableColor(C_TEXT_ED);
        f.setUneditableColor(C_TEXT_PH);
        return f;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int layoutMouseX = GuiScaleUtil.toLayoutCoordinate(mouseX, panelScale);
        int layoutMouseY = GuiScaleUtil.toLayoutCoordinate(mouseY, panelScale);
        tooltipStack = null;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(panelScale, panelScale);

        drawPanel(ctx);
        drawNav(ctx);
        drawTrennlinie(ctx);
        drawInfo(ctx);
        drawTabs(ctx, layoutMouseX, layoutMouseY);
        drawEingabe(ctx, layoutMouseX, layoutMouseY);
        if (modus == Modus.ITEM) drawInventar(ctx, layoutMouseX, layoutMouseY);
        drawButtons(ctx, layoutMouseX, layoutMouseY);
        drawStatus(ctx);

        super.render(ctx, layoutMouseX, layoutMouseY, delta);
        ctx.getMatrices().popMatrix();

        if (tooltipStack != null && !tooltipStack.isEmpty())
            ctx.drawItemTooltip(textRenderer, tooltipStack, mouseX, mouseY);
    }

    private void drawPanel(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_B, h = pHöhe;

        ctx.fill(x + 2, y,     x + w - 2, y + h,     C_PANEL_BG);
        ctx.fill(x,     y + 2, x + 2,     y + h - 2, C_PANEL_BG);
        ctx.fill(x + w - 2, y + 2, x + w, y + h - 2, C_PANEL_BG);

        ctx.fill(x + 2, y,         x + w - 2, y + 1,     C_RAND_AUSSEN);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h,     C_RAND_AUSSEN);
        ctx.fill(x,     y + 2,     x + 1,     y + h - 2, C_RAND_AUSSEN);
        ctx.fill(x + w - 1, y + 2, x + w,     y + h - 2, C_RAND_AUSSEN);

        ctx.fill(x + 1,     y + 1,     x + 2,     y + 2,     C_RAND_ECKE);
        ctx.fill(x + w - 2, y + 1,     x + w - 1, y + 2,     C_RAND_ECKE);
        ctx.fill(x + 1,     y + h - 2, x + 2,     y + h - 1, C_RAND_ECKE);
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, C_RAND_ECKE);

        ctx.fill(x + 2, y + 1,     x + w - 2, y + 2,     C_HIGHLIGHT);
        ctx.fill(x + 1, y + 2,     x + 2,     y + h - 2, C_HIGHLIGHT);
        ctx.fill(x + 2,     y + h - 2, x + w - 2, y + h - 1, C_DUNKEL);
        ctx.fill(x + w - 2, y + 2,     x + w - 1, y + h - 2, C_DUNKEL);
    }

    private void drawNav(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_B;
        ctx.fill(x + 2, y + 1, x + w - 2, y + NAV_H, C_NAV_BG);
        ctx.fill(x + 1, y + 2, x + w - 1, y + NAV_H, C_NAV_BG);

        String seite = "Zahlung";
        int textY = y + (NAV_H - 8) / 2 + 1;
        int curX  = x + RAND;
        String pre = "Credit Manager";
        String arr = " » ";
        ctx.drawText(textRenderer, Text.literal(pre),          curX, textY, C_NAV_PREFIX, false);
        curX += textRenderer.getWidth(pre);
        ctx.drawText(textRenderer, Text.literal(arr),          curX, textY, C_NAV_PFEIL,  false);
        curX += textRenderer.getWidth(arr);
        ctx.drawText(textRenderer, Text.literal("§l" + seite), curX, textY, C_NAV_SEITE,  false);
    }

    private void drawTrennlinie(DrawContext ctx) {
        int x = pX, w = PANEL_B, tl = pY + NAV_H;
        ctx.fill(x + 2, tl,     x + w - 2, tl + 1, C_TRENN_D);
        ctx.fill(x + 2, tl + 1, x + w - 2, tl + 2, C_TRENN_H);
    }

    private void drawInfo(DrawContext ctx) {
        String restFarbe = istSchulden ? "§c" : "§a";
        String rest = FormatUtil.formatiereBetrag(eintrag.getRemainingAmount());
        String richtung = istSchulden ? "§cZahlung leisten" : "§aZahlung empfangen";
        ctx.drawText(textRenderer,
                Text.literal(richtung + " §8– §7" + eintrag.getDealName()),
                pX + RAND, yInfo, 0xFFFFFF, false);
        ctx.drawText(textRenderer,
                Text.literal("§8Offen: " + restFarbe + rest),
                pX + RAND, yInfo + 10, C_LABEL, false);
    }

    private void drawTabs(DrawContext ctx, int mx, int my) {
        int tabBreite = (PANEL_B - RAND * 2 - 6) / 2;
        int xGeld = pX + RAND;
        int xItem = pX + RAND + tabBreite + 6;
        boolean geldHov = isIn(mx, my, xGeld, yTabs, tabBreite, 20);
        boolean itemHov = isIn(mx, my, xItem, yTabs, tabBreite, 20);
        drawTab(ctx, xGeld, yTabs, tabBreite, 20, modus == Modus.GELD, geldHov,
                "✦ Geld", C_TAB_GELD_AKT, C_TAB_GELD_AKT_L, ClassicUiColors.GOLD);
        drawTab(ctx, xItem, yTabs, tabBreite, 20, modus == Modus.ITEM, itemHov,
                "✦ Item", C_TAB_ITEM_AKT, C_TAB_ITEM_AKT_L, ClassicUiColors.GOLD);
    }

    private void drawTab(DrawContext ctx, int x, int y, int w, int h,
                         boolean aktiv, boolean hov, String label,
                         int bgAktiv, int hlAktiv, int textFarbe) {
        int bg = aktiv ? bgAktiv : (hov ? C_TAB_HOV : C_TAB_INK);
        ctx.fill(x,     y,     x + w,     y + h,     C_RAND_AUSSEN);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        if (aktiv) {
            ctx.fill(x + 1, y + 1, x + w - 1, y + 3, hlAktiv);
        }
        int col = aktiv ? textFarbe : (hov ? ClassicUiColors.TEXT : ClassicUiColors.MUTED);
        int tw  = textRenderer.getWidth(label);
        ctx.drawText(textRenderer, Text.literal(label),
                x + (w - tw) / 2, y + (h - 8) / 2, col, false);
    }

    private void drawEingabe(DrawContext ctx, int mx, int my) {
        int fx = pX + RAND;

        if (modus == Modus.GELD) {
            ctx.drawText(textRenderer, Text.literal("§7Betrag eingeben:"),
                    fx, yEingabe, C_LABEL, false);
            int fy = yEingabe + 10 + 4;
            drawFeldHG(ctx, fx - 2, fy - 2, PANEL_B - RAND * 2 + 4, FELD_HÖHE + 4);
        } else {
            ctx.drawText(textRenderer, Text.literal("§7Item aus Inventar wählen:"),
                    fx, yEingabe, C_LABEL, false);

            int sy = yEingabe + 10 + 4;
            drawDarkSlot(ctx, fx, sy, gewähltesItem, mx, my, true);

            int infoX = fx + SLOT_SIZE + 8;
            int infoY = sy;
            if (gewähltesItem.isEmpty()) {
                ctx.drawText(textRenderer,
                        Text.literal("§8← Klicke unten ein Item an"),
                        infoX, infoY + 4, C_LABEL, false);
            } else {
                ctx.drawText(textRenderer,
                        Text.literal("§f" + selectedInventorySlots.size() + " Items ausgewählt"),
                        infoX, infoY + 2, 0xFFFFFF, false);
                ctx.drawText(textRenderer,
                        Text.literal("§7Vorschau: §f" + gewähltesItem.getName().getString()),
                        infoX, infoY + 11, C_LABEL, false);
                ctx.drawText(textRenderer,
                        Text.literal("§8Klicke markierte Slots zum Entfernen"),
                        infoX, infoY + 20, ClassicUiColors.MUTED_DARK, false);
            }

            int wertLabelY = sy + SLOT_SIZE + 16;
            int wertFeldY  = wertLabelY + 10 + 4;
            ctx.drawText(textRenderer,
                    Text.literal("§7Verrechnungswert:"),
                    fx, wertLabelY, C_LABEL, false);
            drawFeldHG(ctx, fx - 2, wertFeldY - 2, PANEL_B - RAND * 2 + 4, FELD_HÖHE + 4);
        }
    }

    private void drawFeldHG(DrawContext ctx, int sx, int sy, int w, int h) {
        ctx.fill(sx,         sy,         sx + w,     sy + 1,      C_SLOT_D);
        ctx.fill(sx,         sy,         sx + 1,     sy + h,      C_SLOT_D);
        ctx.fill(sx + 1,     sy + h - 1, sx + w,     sy + h,      C_SLOT_H);
        ctx.fill(sx + w - 1, sy + 1,     sx + w,     sy + h,      C_SLOT_H);
        ctx.fill(sx + 1,     sy + 1,     sx + w - 1, sy + h - 1,  C_SLOT_BG);
    }

    private void drawInventar(DrawContext ctx, int mx, int my) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        PlayerInventory inv = mc.player.getInventory();

        int invBreite = INV_COLS * SLOT_SIZE;
        int invStartX = pX + (PANEL_B - invBreite) / 2;

        ctx.drawText(textRenderer, Text.literal("§8Inventar"),
                invStartX, yInvLabel, C_LABEL, false);

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < INV_COLS; c++) {
                int sx = invStartX + c * SLOT_SIZE;
                int sy = yInv + r * SLOT_SIZE;
                int inventorySlot = 9 + r * INV_COLS + c;
                drawDarkSlot(ctx, sx, sy, inv.getStack(inventorySlot), mx, my, false,
                        selectedInventorySlots.containsKey(inventorySlot));
            }
        }

        int tl = yInv + 3 * SLOT_SIZE + 2;
        ctx.fill(invStartX, tl,     invStartX + invBreite, tl + 1, C_TRENN_D);
        ctx.fill(invStartX, tl + 1, invStartX + invBreite, tl + 2, C_TRENN_H);

        for (int c = 0; c < INV_COLS; c++) {
            int sx = invStartX + c * SLOT_SIZE;
            drawDarkSlot(ctx, sx, yHotbar, inv.getStack(c), mx, my, false,
                    selectedInventorySlots.containsKey(c));
        }
    }

    private void drawDarkSlot(DrawContext ctx, int sx, int sy, ItemStack stack,
                              int mx, int my, boolean zielSlot) {
        drawDarkSlot(ctx, sx, sy, stack, mx, my, zielSlot, false);
    }

    private void drawDarkSlot(DrawContext ctx, int sx, int sy, ItemStack stack,
                              int mx, int my, boolean zielSlot, boolean selected) {
        ctx.fill(sx,                 sy,                 sx + SLOT_SIZE,     sy + 1,             C_SLOT_D);
        ctx.fill(sx,                 sy,                 sx + 1,             sy + SLOT_SIZE,     C_SLOT_D);
        ctx.fill(sx + 1,             sy + SLOT_SIZE - 1, sx + SLOT_SIZE,     sy + SLOT_SIZE,     C_SLOT_H);
        ctx.fill(sx + SLOT_SIZE - 1, sy + 1,             sx + SLOT_SIZE,     sy + SLOT_SIZE,     C_SLOT_H);
        ctx.fill(sx + 1,             sy + 1,             sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, C_SLOT_BG);

        if ((zielSlot || selected) && !stack.isEmpty())
            ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, C_ZIEL_ITEM);

        if (isIn(mx, my, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
            ctx.fill(sx + 1, sy + 1, sx + SLOT_SIZE - 1, sy + SLOT_SIZE - 1, C_HOVER);
            if (!stack.isEmpty()) { tooltipStack = stack; tooltipX = mx; tooltipY = my; }
        }

        if (!stack.isEmpty())
            ctx.drawItem(stack, sx + 1, sy + 1);
    }

    private void drawButtons(DrawContext ctx, int mx, int my) {
        int okX = pX + RAND;
        int abX = pX + PANEL_B - RAND - BTN_BREITE;

        String okLabel   = modus == Modus.GELD ? "✔ Zahlung" : "✔ Item zahlen";
        int    okTextClr = modus == Modus.GELD ? ClassicUiColors.LIME : ClassicUiColors.GOLD;

        boolean okHov = isIn(mx, my, okX, yButtons, BTN_BREITE, BTN_HÖHE);
        boolean abHov = isIn(mx, my, abX, yButtons, BTN_BREITE, BTN_HÖHE);

        drawDarkBtn(ctx, okX, yButtons, BTN_BREITE, BTN_HÖHE,
                okHov ? C_BTN_OK_H : C_BTN_OK, okLabel, okTextClr);
        drawDarkBtn(ctx, abX, yButtons, BTN_BREITE, BTN_HÖHE,
                abHov ? C_BTN_AB_H : C_BTN_AB, "✖ Abbrechen", ClassicUiColors.ERROR);
    }

    private void drawDarkBtn(DrawContext ctx, int x, int y, int w, int h,
                             int bg, String label, int textFarbe) {
        ctx.fill(x,     y,     x + w,     y + h,     C_RAND_AUSSEN);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2,     aufhellen(bg, 30));
        ctx.fill(x + 1, y + 1, x + 2,     y + h - 1, aufhellen(bg, 20));
        ctx.fill(x + 1,     y + h - 2, x + w - 1, y + h - 1, abdunkeln(bg, 30));
        ctx.fill(x + w - 2, y + 2,     x + w - 1, y + h - 2, abdunkeln(bg, 20));
        int tw = textRenderer.getWidth(label);
        ctx.drawText(textRenderer, Text.literal(label),
                x + (w - tw) / 2, y + (h - 8) / 2, textFarbe, false);
    }

    private void drawStatus(DrawContext ctx) {
        if (fehlerText != null)
            ctx.drawText(textRenderer, Text.literal(fehlerText),
                    pX + RAND, yStatus, C_FEHLER, false);
        if (erfolgText != null)
            ctx.drawText(textRenderer, Text.literal(erfolgText),
                    pX + RAND, yStatus, C_ERFOLG, false);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        net.minecraft.client.gui.Click layoutClick = GuiScaleUtil.toLayoutClick(click, panelScale);
        double mx = layoutClick.x(), my = layoutClick.y();
        int btn = layoutClick.button();

        if (btn == 0) {
            int tabBreite = (PANEL_B - RAND * 2 - 6) / 2;
            int xGeld = pX + RAND;
            int xItem = pX + RAND + tabBreite + 6;
            if (isIn(mx, my, xGeld, yTabs, tabBreite, 20) && modus != Modus.GELD) {
                modus = Modus.GELD; fehlerText = null; erfolgText = null;
                selectedInventorySlots.clear();
                refreshSelectedItemPreview();
                init(); return true;
            }
            if (isIn(mx, my, xItem, yTabs, tabBreite, 20) && modus != Modus.ITEM) {
                modus = Modus.ITEM; fehlerText = null; erfolgText = null;
                init(); return true;
            }

            if (modus == Modus.ITEM) {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.player != null) {
                    PlayerInventory inv = mc.player.getInventory();

                    int invBreite = INV_COLS * SLOT_SIZE;
                    int invStartX = pX + (PANEL_B - invBreite) / 2;

                    for (int r = 0; r < 3; r++) {
                        for (int c = 0; c < INV_COLS; c++) {
                            int sx = invStartX + c * SLOT_SIZE;
                            int sy = yInv + r * SLOT_SIZE;
                            if (isIn(mx, my, sx, sy, SLOT_SIZE, SLOT_SIZE)) {
                                ItemStack s = inv.getStack(9 + r * INV_COLS + c);
                                toggleInventorySlot(9 + r * INV_COLS + c, s);
                                fehlerText = null;
                                return true;
                            }
                        }
                    }

                    for (int c = 0; c < INV_COLS; c++) {
                        int sx = invStartX + c * SLOT_SIZE;
                        if (isIn(mx, my, sx, yHotbar, SLOT_SIZE, SLOT_SIZE)) {
                            ItemStack s = inv.getStack(c);
                            toggleInventorySlot(c, s);
                            fehlerText = null;
                            return true;
                        }
                    }

                    int sy = yEingabe + 10 + 4;
                    if (isIn(mx, my, pX + RAND, sy, SLOT_SIZE, SLOT_SIZE)) {
                        selectedInventorySlots.clear();
                        refreshSelectedItemPreview();
                        return true;
                    }
                }
            }

            int okX = pX + RAND;
            int abX = pX + PANEL_B - RAND - BTN_BREITE;
            if (isIn(mx, my, okX, yButtons, BTN_BREITE, BTN_HÖHE)) { eintragen(); return true; }
            if (isIn(mx, my, abX, yButtons, BTN_BREITE, BTN_HÖHE)) {
                MinecraftClient.getInstance().setScreen(elternScreen); return true;
            }
        }

        if (btn == 1 && modus == Modus.ITEM && !gewähltesItem.isEmpty()) {
            int sy = yEingabe + 10 + 4;
            if (isIn(mx, my, pX + RAND, sy, SLOT_SIZE, SLOT_SIZE)) {
                gewählteAnzahl = Math.max(1, gewählteAnzahl / 2);
                return true;
            }
        }

        return super.mouseClicked(layoutClick, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        mx /= panelScale;
        my /= panelScale;
        if (modus == Modus.ITEM && !gewähltesItem.isEmpty()) {
            int sy = yEingabe + 10 + 4;
            if (isIn(mx, my, pX + RAND, sy, SLOT_SIZE, SLOT_SIZE)) {
                gewählteAnzahl = Math.max(1,
                        Math.min(gewähltesItem.getCount(), gewählteAnzahl + (v > 0 ? 1 : -1)));
                return true;
            }
        }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int k = input.getKeycode();
        if (k == 257 || k == 335) { eintragen(); return true; }
        if (k == 256) { MinecraftClient.getInstance().setScreen(elternScreen); return true; }
        return super.keyPressed(input);
    }

    private void eintragen() {
        fehlerText = null; erfolgText = null;
        if (modus == Modus.GELD) eintragenGeld(); else eintragenItem();
    }

    private void eintragenGeld() {
        if (feldBetrag == null) return;
        String raw = feldBetrag.getText().trim();
        if (raw.isBlank()) { fehlerText = "Bitte Betrag eingeben!"; return; }

        double betrag;
        try { betrag = FormatUtil.parseMoney(raw); }
        catch (IllegalArgumentException e) {
            fehlerText = "Ungültiger Betrag! z.B.: 500, 1k, 2.5m"; return;
        }
        if (betrag <= 0) { fehlerText = "Betrag muss größer als 0 sein!"; return; }
        if (betrag > eintrag.getRemainingAmount()) {
            fehlerText = "Überschreitet Restbetrag ("
                    + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()) + ")!";
            return;
        }
        try {
            manager.addMoneyPayment(eintrag.getId(), ich, betrag);
            manager.findCredit(eintrag.getDealName()).ifPresent(f -> eintrag = f);
            erfolgText = "Zahlung von " + FormatUtil.formatiereBetrag(betrag) + " eingetragen!";
            schedule();
        } catch (CreditManager.CreditException e) {
            fehlerText = e.getMessage();
        }
    }

    private void eintragenItem() {
        if (eintragenAusgewählteItems()) {
            return;
        }

        if (gewähltesItem.isEmpty()) {
            fehlerText = "Bitte zuerst ein Item aus dem Inventar wählen!";
            return;
        }

        double verrechnungswert = 0.0;

        if (feldVerrechnungswert != null) {
            String raw = feldVerrechnungswert.getText().trim();

            if (!raw.isBlank()) {
                try {
                    verrechnungswert = FormatUtil.parseMoney(raw);
                } catch (IllegalArgumentException e) {
                    fehlerText = "Ungültiger Verrechnungswert! z.B.: 500, 1k";
                    return;
                }

                if (verrechnungswert < 0) {
                    fehlerText = "Verrechnungswert darf nicht negativ sein!";
                    return;
                }

                if (verrechnungswert > eintrag.getRemainingAmount()) {
                    fehlerText = "Verrechnungswert überschreitet Restbetrag ("
                            + FormatUtil.formatiereBetrag(eintrag.getRemainingAmount()) + ")!";
                    return;
                }
            }
        }

        String itemName = gewähltesItem.getName().getString();

        List<String> items = new ArrayList<>();
        items.add(gewählteAnzahl + "x " + itemName);

        String itemNbt = serialisiereItemStack(gewähltesItem, gewählteAnzahl);

        if (itemNbt == null || itemNbt.isBlank()) {
            fehlerText = "Item konnte nicht vollständig gespeichert werden!";
            return;
        }

        try {
            manager.addItemPayment(eintrag.getId(), ich, items, verrechnungswert, itemNbt);

            manager.findCredit(eintrag.getDealName()).ifPresent(f -> eintrag = f);

            if (verrechnungswert > 0) {
                erfolgText = gewählteAnzahl + "× " + itemName
                        + " (≙ " + FormatUtil.formatiereBetrag(verrechnungswert) + ") eingetragen!";
            } else {
                erfolgText = gewählteAnzahl + "× " + itemName + " eingetragen!";
            }

            schedule();

        } catch (CreditManager.CreditException e) {
            fehlerText = e.getMessage();
        } catch (Exception e) {
            fehlerText = "Fehler: " + e.getMessage();
        }
    }

    private String serialisiereItemStack(ItemStack stack, int anzahl) {
        return ItemStackStorage.serialize(stack, anzahl);
    }

    private boolean eintragenAusgewählteItems() {
        if (selectedInventorySlots.isEmpty()) {
            fehlerText = "Bitte waehle mindestens ein Item aus.";
            return true;
        }

        if (feldVerrechnungswert == null || feldVerrechnungswert.getText().trim().isBlank()) {
            fehlerText = "Bitte den gemeinsamen Verrechnungswert eingeben.";
            return true;
        }

        double sharedValue;
        try {
            sharedValue = FormatUtil.parseMoney(feldVerrechnungswert.getText().trim());
        } catch (IllegalArgumentException exception) {
            fehlerText = "Bitte einen gueltigen positiven Verrechnungswert eingeben.";
            return true;
        }

        if (sharedValue > eintrag.getRemainingAmount()) {
            fehlerText = "Der gemeinsame Wert ueberschreitet den Restbetrag.";
            return true;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            fehlerText = "Kein Spieler-Inventar verfuegbar.";
            return true;
        }

        List<String> items = new ArrayList<>();
        List<String> serializedItems = new ArrayList<>();
        PlayerInventory inventory = client.player.getInventory();
        for (Map.Entry<Integer, Integer> selection : selectedInventorySlots.entrySet()) {
            ItemStack stack = inventory.getStack(selection.getKey());
            if (stack.isEmpty()) {
                continue;
            }
            int count = Math.max(1, Math.min(selection.getValue(), stack.getCount()));
            String serialized = serialisiereItemStack(stack, count);
            if (serialized == null || serialized.isBlank()) {
                fehlerText = "Ein ausgewaehltes Item konnte nicht gespeichert werden.";
                return true;
            }
            items.add(count + "x " + stack.getName().getString());
            serializedItems.add(serialized);
        }

        if (items.isEmpty()) {
            fehlerText = "Die ausgewaehlten Items sind nicht mehr im Inventar.";
            return true;
        }

        try {
            manager.addItemPayment(eintrag.getId(), ich, items, sharedValue, serializedItems);
            manager.findCredit(eintrag.getDealName()).ifPresent(f -> eintrag = f);
            erfolgText = items.size() + " Items mit gemeinsamem Wert von "
                    + FormatUtil.formatiereBetrag(sharedValue) + " eingetragen!";
            schedule();
        } catch (CreditManager.CreditException exception) {
            fehlerText = exception.getMessage();
        }
        return true;
    }

    private void toggleInventorySlot(int inventorySlot, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (selectedInventorySlots.containsKey(inventorySlot)) {
            selectedInventorySlots.remove(inventorySlot);
        } else {
            selectedInventorySlots.put(inventorySlot, stack.getCount());
        }
        refreshSelectedItemPreview();
    }

    private void refreshSelectedItemPreview() {
        if (selectedInventorySlots.isEmpty()) {
            gewähltesItem = ItemStack.EMPTY;
            gewählteAnzahl = 1;
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        Map.Entry<Integer, Integer> first = selectedInventorySlots.entrySet().iterator().next();
        ItemStack stack = client.player.getInventory().getStack(first.getKey());
        if (stack.isEmpty()) {
            selectedInventorySlots.remove(first.getKey());
            refreshSelectedItemPreview();
            return;
        }
        gewähltesItem = stack.copy();
        gewählteAnzahl = Math.min(first.getValue(), stack.getCount());
    }

    private void schedule() {
        MinecraftClient mc = MinecraftClient.getInstance();
        SCHEDULER.schedule(() -> mc.execute(() -> mc.setScreen(elternScreen)), 700, TimeUnit.MILLISECONDS);
    }

    private boolean isIn(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private int aufhellen(int c, int d) {
        int r = Math.min(255, ((c >> 16) & 0xFF) + d);
        int g = Math.min(255, ((c >>  8) & 0xFF) + d);
        int b = Math.min(255, ( c        & 0xFF) + d);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int abdunkeln(int c, int d) {
        int r = Math.max(0, ((c >> 16) & 0xFF) - d);
        int g = Math.max(0, ((c >>  8) & 0xFF) - d);
        int b = Math.max(0, ( c        & 0xFF) - d);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean shouldPause() { return false; }
}

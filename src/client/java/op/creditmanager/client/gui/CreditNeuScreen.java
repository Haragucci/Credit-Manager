package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class CreditNeuScreen extends BasisScreen {

    private static final int FELD_BREITE  = 210;
    private static final int FELD_HÖHE    = 14;
    private static final int ZEILEN_ABST  = 28;
    private static final int LABELS       = 5;

    private static final int PANEL_BREITE = 244;
    private static final int NAV_HÖHE_INT = 22;
    private static final int PANEL_HÖHE   =
            NAV_HÖHE_INT + 2
                    + 12
                    + LABELS * ZEILEN_ABST
                    + 10
                    + 20
                    + 14
                    + 8;

    private static final int C_PANEL_BG    = 0xFF1E1E2E;
    private static final int C_RAND_AUSSEN = 0xFF0D0D1A;
    private static final int C_RAND_ECKE   = 0xFF161626;
    private static final int C_HIGHLIGHT   = 0xFF2A2A3E;
    private static final int C_SCHATTEN    = 0xFF0A0A14;

    private static final int C_DROP_1     = 0x40000000;
    private static final int C_DROP_2     = 0x28000000;
    private static final int C_DROP_3     = 0x14000000;

    private static final int C_NAV_BG     = 0xFF12121F;
    private static final int C_NAV_BORDER = 0xFF2E2E4A;
    private static final int C_NAV_PREFIX = 0xFF666677;
    private static final int C_NAV_PFEIL  = 0xFF4A9E4A;
    private static final int C_NAV_SEITE  = 0xFFFFFFFF;

    private static final int C_TRENN_D   = 0xFF0A0A14;
    private static final int C_TRENN_H   = 0xFF2A2A3E;
    private static final int C_LABEL_CLR = 0xFF888899;

    private static final int C_SLOT_D  = 0xFF0D0D1A;
    private static final int C_SLOT_H  = 0xFF2E2E4A;
    private static final int C_SLOT_BG = 0xFF181828;

    private static final int C_BTN_OK   = 0xFF1A4A1A;
    private static final int C_BTN_OK_H = 0xFF226622;
    private static final int C_BTN_AB   = 0xFF4A1A1A;
    private static final int C_BTN_AB_H = 0xFF662222;

    private static final int C_FEHLER = 0xFFDD4444;
    private static final int C_ERFOLG = 0xFF44DD44;

    private static final int C_TEXT_EDITABLE   = 0xFFFFFFFF;
    private static final int C_TEXT_PLACEHOLDER = 0xFF555566;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "creditmanager-gui-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final CreditManager manager;
    private final boolean       istSchulden;
    private final net.minecraft.client.gui.screen.Screen elternScreen;

    private TextFieldWidget feldGegenspieler;
    private TextFieldWidget feldBetrag;
    private TextFieldWidget feldDatum;
    private TextFieldWidget feldBezeichnung;
    private TextFieldWidget feldNotiz;

    private String fehlerText = null;
    private String erfolgText = null;

    private int pX, pY;

    public CreditNeuScreen(CreditManager manager, boolean istSchulden,
                           net.minecraft.client.gui.screen.Screen elternScreen) {
        super(Text.literal(istSchulden ? "Neue Schuld" : "Neue Forderung"), 0, true);
        this.manager      = manager;
        this.istSchulden  = istSchulden;
        this.elternScreen = elternScreen;
    }

    @Override
    protected void init() {
        pX = (width  - PANEL_BREITE) / 2;
        pY = (height - PANEL_HÖHE)   / 2;

        guiX      = pX;
        guiY      = pY;
        guiBreite = PANEL_BREITE;

        int feldX  = pX + (PANEL_BREITE - FELD_BREITE) / 2;
        int startY = pY + NAV_HÖHE_INT + 2 + 16;

        feldGegenspieler = neuesFeld(feldX, startY,                   FELD_BREITE, FELD_HÖHE, 32);
        feldBetrag       = neuesFeld(feldX, startY + ZEILEN_ABST,     FELD_BREITE, FELD_HÖHE, 20);
        feldDatum        = neuesFeld(feldX, startY + ZEILEN_ABST * 2, FELD_BREITE, FELD_HÖHE, 10);
        feldBezeichnung  = neuesFeld(feldX, startY + ZEILEN_ABST * 3, FELD_BREITE, FELD_HÖHE, 32);
        feldNotiz        = neuesFeld(feldX, startY + ZEILEN_ABST * 4, FELD_BREITE, FELD_HÖHE, 64);

        feldGegenspieler.setPlaceholder(Text.literal(istSchulden ? "Gläubiger" : "Schuldner"));
        feldBetrag      .setPlaceholder(Text.literal("z.B. 1000, 2.5k, 1m"));
        feldDatum       .setPlaceholder(Text.literal("TT.MM.JJJJ  (optional)"));
        feldBezeichnung .setPlaceholder(Text.literal("Bezeichnung  (optional)"));
        feldNotiz       .setPlaceholder(Text.literal("Notiz  (optional)"));

        addDrawableChild(feldGegenspieler);
        addDrawableChild(feldBetrag);
        addDrawableChild(feldDatum);
        addDrawableChild(feldBezeichnung);
        addDrawableChild(feldNotiz);

        setInitialFocus(feldGegenspieler);
    }


    @Override
    protected void fülleSlots() { }

    private TextFieldWidget neuesFeld(int x, int y, int breite, int höhe, int maxLen) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, breite, höhe, Text.empty());
        f.setMaxLength(maxLen);
        f.setDrawsBackground(false);
        f.setEditableColor(C_TEXT_EDITABLE);
        f.setUneditableColor(C_TEXT_PLACEHOLDER);
        return f;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        drawDropShadowManuell(ctx);
        drawPanelManuell(ctx);
        drawNavManuell(ctx);
        drawTrennlinieManuell(ctx);
        drawFormularInhalt(ctx, mouseX, mouseY);

        super.render(ctx, mouseX, mouseY, delta);
    }


    private void drawDropShadowManuell(DrawContext ctx) {
        int x = pX + 4, y = pY + 4, w = PANEL_BREITE, h = PANEL_HÖHE;
        ctx.fill(x,     y,     x + w,     y + h,     C_DROP_1);
        ctx.fill(x + 1, y + 1, x + w + 1, y + h + 1, C_DROP_2);
        ctx.fill(x + 2, y + 2, x + w + 2, y + h + 2, C_DROP_3);
    }

    private void drawPanelManuell(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_BREITE, h = PANEL_HÖHE;

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

    private void drawNavManuell(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_BREITE;

        ctx.fill(x + 2, y + 1,     x + w - 2, y + NAV_HÖHE_INT, C_NAV_BG);
        ctx.fill(x + 1, y + 2,     x + w - 1, y + NAV_HÖHE_INT, C_NAV_BG);
        ctx.fill(x + 2, y + NAV_HÖHE_INT - 1, x + w - 2, y + NAV_HÖHE_INT, C_NAV_BORDER);

        String seite = istSchulden ? "Neue Schuld" : "Neue Forderung";
        int textY = y + (NAV_HÖHE_INT - 8) / 2 + 1;
        int curX  = x + RAND;

        String pre = "Credit Manager";
        String arr = " » ";
        ctx.drawText(textRenderer, Text.literal(pre),         curX, textY, C_NAV_PREFIX, false);
        curX += textRenderer.getWidth(pre);
        ctx.drawText(textRenderer, Text.literal(arr),         curX, textY, C_NAV_PFEIL,  false);
        curX += textRenderer.getWidth(arr);
        ctx.drawText(textRenderer, Text.literal("§l" + seite), curX, textY, C_NAV_SEITE,  false);
    }

    private void drawTrennlinieManuell(DrawContext ctx) {
        int x = pX, w = PANEL_BREITE;
        int tl = pY + NAV_HÖHE_INT;
        ctx.fill(x + 2, tl,     x + w - 2, tl + 1, C_TRENN_D);
        ctx.fill(x + 2, tl + 1, x + w - 2, tl + 2, C_TRENN_H);
    }


    private void drawFormularInhalt(DrawContext ctx, int mouseX, int mouseY) {
        int feldX  = pX + (PANEL_BREITE - FELD_BREITE) / 2;
        int startY = pY + NAV_HÖHE_INT + 2 + 16;

        String[] labels = {
                istSchulden ? "Gläubiger *" : "Schuldner *",
                "Betrag *",
                "Fällig bis",
                "Bezeichnung",
                "Notiz"
        };
        TextFieldWidget[] felder = {
                feldGegenspieler, feldBetrag, feldDatum, feldBezeichnung, feldNotiz
        };

        for (int i = 0; i < labels.length; i++) {
            int fy = startY + ZEILEN_ABST * i;
            ctx.drawText(textRenderer, Text.literal(labels[i]),
                    feldX, fy - 10, C_LABEL_CLR, false);
            drawFeldHintergrund(ctx, feldX - 2, fy - 2, FELD_BREITE + 4, FELD_HÖHE + 4);
        }

        int btnY = pY + PANEL_HÖHE - 8 - 20 - 14;
        ctx.drawText(textRenderer, Text.literal("§8* Pflichtfelder"),
                pX + RAND, btnY - 10, C_LABEL_CLR, false);

        int btnBreite = 100;
        boolean okHov = isIn(mouseX, mouseY, pX + RAND, btnY, btnBreite, 20);
        boolean abHov = isIn(mouseX, mouseY, pX + PANEL_BREITE - btnBreite - RAND, btnY, btnBreite, 20);
        drawDarkBtn(ctx, pX + RAND, btnY, btnBreite, 20,
                okHov ? C_BTN_OK_H : C_BTN_OK, "✔ Speichern",  0xFF66DD66);
        drawDarkBtn(ctx, pX + PANEL_BREITE - btnBreite - RAND, btnY, btnBreite, 20,
                abHov ? C_BTN_AB_H : C_BTN_AB, "✖ Abbrechen", 0xFFDD6666);

        int statusY = btnY + 24;
        if (fehlerText != null)
            ctx.drawText(textRenderer, Text.literal(fehlerText), pX + RAND, statusY, C_FEHLER, false);
        if (erfolgText != null)
            ctx.drawText(textRenderer, Text.literal(erfolgText), pX + RAND, statusY, C_ERFOLG, false);
    }

    private void drawFeldHintergrund(DrawContext ctx, int sx, int sy, int w, int h) {
        ctx.fill(sx,         sy,         sx + w,     sy + 1,      C_SLOT_D);
        ctx.fill(sx,         sy,         sx + 1,     sy + h,      C_SLOT_D);
        ctx.fill(sx + 1,     sy + h - 1, sx + w,     sy + h,      C_SLOT_H);
        ctx.fill(sx + w - 1, sy + 1,     sx + w,     sy + h,      C_SLOT_H);
        ctx.fill(sx + 1,     sy + 1,     sx + w - 1, sy + h - 1,  C_SLOT_BG);
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

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (click.button() == 0) {
            int mx = (int) click.x(), my = (int) click.y();
            int btnY      = pY + PANEL_HÖHE - 8 - 20 - 14;
            int btnBreite = 100;
            if (isIn(mx, my, pX + RAND, btnY, btnBreite, 20)) {
                speichern(); return true;
            }
            if (isIn(mx, my, pX + PANEL_BREITE - btnBreite - RAND, btnY, btnBreite, 20)) {
                MinecraftClient.getInstance().setScreen(elternScreen); return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int k = input.getKeycode();
        if (k == 257 || k == 335) { speichern(); return true; }
        if (k == 256) { MinecraftClient.getInstance().setScreen(elternScreen); return true; }
        return super.keyPressed(input);
    }

    private void speichern() {
        fehlerText = null; erfolgText = null;

        String gegenspieler = feldGegenspieler.getText().trim().toLowerCase();
        String betragRaw    = feldBetrag.getText().trim();
        String datumRaw     = feldDatum.getText().trim();
        String bezeichnung  = feldBezeichnung.getText().trim();
        String notiz        = feldNotiz.getText().trim();

        if (gegenspieler.isBlank()) {
            fehlerText = (istSchulden ? "Gläubiger" : "Schuldner") + " darf nicht leer sein!";
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        String ich = mc.player != null ? mc.player.getName().getString().toLowerCase() : "";

        if (gegenspieler.equals(ich)) {
            fehlerText = "Kein Deal mit dir selbst möglich!";
            return;
        }

        if (betragRaw.isBlank()) { fehlerText = "Betrag darf nicht leer sein!"; return; }

        double betrag;
        try { betrag = FormatUtil.parseMoney(betragRaw); }
        catch (IllegalArgumentException e) {
            fehlerText = "Ungültiger Betrag! z.B.: 1000, 2.5k, 1m"; return;
        }
        if (betrag <= 0) { fehlerText = "Betrag muss größer als 0 sein!"; return; }

        Long fälligkeitMs = null;
        if (!datumRaw.isBlank() && !datumRaw.equals("-")) {
            fälligkeitMs = TimeUtil.parseDueDate(datumRaw);
            if (fälligkeitMs == null) {
                fehlerText = "Ungültiges Datum! Format: TT.MM.JJJJ"; return;
            }
        }

        String creditor = istSchulden ? gegenspieler : ich;
        String debtor   = istSchulden ? ich           : gegenspieler;

        try {
            CreditEntry eintrag = manager.createCredit(
                    creditor, debtor, betrag, fälligkeitMs,
                    bezeichnung.isBlank() ? null : bezeichnung,
                    notiz.isBlank()       ? null : notiz,
                    istSchulden);
            erfolgText = "Deal \"" + eintrag.getDealName() + "\" erstellt!";
            SCHEDULER.schedule(
                    () -> mc.execute(() -> mc.setScreen(elternScreen)),
                    800, TimeUnit.MILLISECONDS);
        } catch (CreditManager.CreditException e) {
            fehlerText = e.getMessage();
        }
    }

    private boolean isIn(int mx, int my, int x, int y, int w, int h) {
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
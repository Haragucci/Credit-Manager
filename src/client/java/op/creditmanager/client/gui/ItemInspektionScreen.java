package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class ItemInspektionScreen extends BasisScreen {

    private static final int RAND          = 10;
    private static final int NAV_H         = 22;
    private static final int PANEL_B       = 260;
    private static final int SHOWCASE_SIZE = 48;

    private static final int PANEL_H = NAV_H + 2 + 10 + 10 + 6 + SHOWCASE_SIZE
            + 8 + 10 + 6 + 10 + 5 + 10 + 5 + 10 + 5 + 10 + 14 + 20 + 10;

    private static final int C_PANEL_BG  = ClassicUiColors.PANEL;
    private static final int C_RAND_AUSS = ClassicUiColors.OUTER_BORDER;
    private static final int C_RAND_ECKE = ClassicUiColors.CORNER;
    private static final int C_HIGHLIGHT = ClassicUiColors.HIGHLIGHT;
    private static final int C_DUNKEL    = ClassicUiColors.SHADOW;

    private static final int C_NAV_BG     = ClassicUiColors.NAVIGATION;
    private static final int C_NAV_BORDER = ClassicUiColors.HIGHLIGHT;
    private static final int C_NAV_PREFIX = ClassicUiColors.MUTED;
    private static final int C_NAV_PFEIL  = ClassicUiColors.LIME;
    private static final int C_NAV_SEITE  = ClassicUiColors.TEXT;

    private static final int C_TRENN_D = ClassicUiColors.SHADOW;
    private static final int C_TRENN_H = ClassicUiColors.HIGHLIGHT;
    private static final int C_LABEL   = ClassicUiColors.MUTED;

    private static final int C_SLOT_BG = ClassicUiColors.SLOT;
    private static final int C_SLOT_D  = ClassicUiColors.OUTER_BORDER;
    private static final int C_SLOT_H  = ClassicUiColors.SLOT_EDGE;
    private static final int C_ITEM_GL = ClassicUiColors.ITEM_GLOW;
    private static final int C_HOVER   = ClassicUiColors.HOVER;

    private static final int C_BTN_BG  = ClassicUiColors.PRIMARY;
    private static final int C_BTN_HOV = ClassicUiColors.PRIMARY_HOVER;
    private static final int C_WHITE   = ClassicUiColors.TEXT;
    private static final int C_GRAY    = ClassicUiColors.MUTED;
    private static final int C_DARK    = ClassicUiColors.MUTED_DARK;

    private final Payment   zahlung;
    private final Screen    elternScreen;
    private final ItemStack parsedStack;
    private final String    anzeigeVon;
    private final String    anzeigeAn;

    private int     pX, pY;
    private float   panelScale = 1.0F;
    private boolean btnHover = false;

    public ItemInspektionScreen(Payment zahlung, Screen elternScreen) {
        this(
                zahlung,
                elternScreen,
                zahlung != null ? zahlung.getFromPlayer() : "",
                zahlung != null ? zahlung.getToPlayer() : "",
                0
        );
    }

    public ItemInspektionScreen(Payment zahlung, CreditEntry eintrag, Screen elternScreen) {
        this(
                zahlung,
                elternScreen,
                eintrag != null ? eintrag.getDebtor() : (zahlung != null ? zahlung.getFromPlayer() : ""),
                eintrag != null ? eintrag.getCreditor() : (zahlung != null ? zahlung.getToPlayer() : ""),
                0
        );
    }

    public ItemInspektionScreen(Payment zahlung, int itemIndex, Screen elternScreen) {
        this(
                zahlung,
                elternScreen,
                zahlung != null ? zahlung.getFromPlayer() : "",
                zahlung != null ? zahlung.getToPlayer() : "",
                itemIndex
        );
    }

    private ItemInspektionScreen(Payment zahlung, Screen elternScreen, String von, String an, int itemIndex) {
        super(Text.literal("Item-Inspektion"), 0);

        this.zahlung = zahlung;
        this.elternScreen = elternScreen;
        this.anzeigeVon = safeName(von);
        this.anzeigeAn = safeName(an);
        List<ItemStack> itemStacks = resolvePaymentStacks(zahlung);
        this.parsedStack = itemStacks.get(Math.max(0, Math.min(itemIndex, itemStacks.size() - 1)));
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "Unbekannt" : name;
    }

    public static ItemStack resolvePaymentStack(Payment zahlung) {
        if (zahlung == null) {
            return new ItemStack(Items.BARRIER);
        }
        String nbtStr = zahlung.getItemNbt();

        if (nbtStr != null && !nbtStr.isBlank()) {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                RegistryWrapper.WrapperLookup lookup =
                        mc.player != null ? mc.player.getRegistryManager()
                                : mc.world != null ? mc.world.getRegistryManager() : null;

                NbtCompound nbt = StringNbtReader.readCompound(nbtStr);

                if (lookup != null) {
                    ItemStack fullStack = tryDecodeFullStack(nbt, lookup);
                    if (!fullStack.isEmpty()) return fullStack;

                    ItemStack componentStack = tryDecodeComponentOnlyStack(nbt, lookup);
                    if (!componentStack.isEmpty()) return componentStack;
                }

                ItemStack simpleStack = tryDecodeSimpleNbtStack(nbt);
                if (!simpleStack.isEmpty()) return simpleStack;

            } catch (Exception ignored) {
            }
        }

        return fallbackStack(zahlung);
    }

    public static List<ItemStack> resolvePaymentStacks(Payment zahlung) {
        if (zahlung == null) {
            return List.of(new ItemStack(Items.BARRIER));
        }

        List<String> serializedStacks = zahlung.getItemNbtEntries();
        List<String> descriptions = zahlung.getItems();
        int count = Math.max(serializedStacks.size(), descriptions.size());
        if (count == 0) {
            return List.of(resolvePaymentStack(zahlung));
        }

        List<ItemStack> stacks = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String description = index < descriptions.size() ? descriptions.get(index) : "Unbekanntes Item";
            String serialized = index < serializedStacks.size() ? serializedStacks.get(index)
                    : index == 0 ? zahlung.getItemNbt() : null;
            Payment singleStackPayment = new Payment(
                    zahlung.getCreditId(), zahlung.getFromPlayer(), zahlung.getToPlayer(), zahlung.getAmount(),
                    List.of(description), zahlung.getSource()
            );
            singleStackPayment.setItemNbt(serialized);
            stacks.add(resolvePaymentStack(singleStackPayment));
        }
        return stacks;
    }

    private static ItemStack tryDecodeFullStack(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        try {
            return ItemStack.CODEC.parse(
                    RegistryOps.of(NbtOps.INSTANCE, lookup),
                    nbt
            ).result().orElse(ItemStack.EMPTY);
        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack tryDecodeComponentOnlyStack(NbtCompound components, RegistryWrapper.WrapperLookup lookup) {
        try {
            Identifier itemId = findItemIdFromComponents(components);
            if (itemId == null) return ItemStack.EMPTY;

            NbtCompound wrapped = new NbtCompound();
            wrapped.putString("id", itemId.toString());
            wrapped.putInt("count", getCountFromComponentsOrDefault(components));
            wrapped.put("components", components.copy());

            return ItemStack.CODEC.parse(
                    RegistryOps.of(NbtOps.INSTANCE, lookup),
                    wrapped
            ).result().orElse(ItemStack.EMPTY);

        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack tryDecodeSimpleNbtStack(NbtCompound nbt) {
        try {
            String idStr = nbt.getString("id", "");

            if (idStr.isBlank()) {
                Identifier guessed = findItemIdFromComponents(nbt);
                if (guessed != null) idStr = guessed.toString();
            }

            if (idStr.isBlank()) return ItemStack.EMPTY;

            Identifier itemId = Identifier.of(idStr);
            net.minecraft.item.Item item = Registries.ITEM.get(itemId);

            if (item == null || item == Items.AIR) return ItemStack.EMPTY;

            int count = nbt.getInt("count", 1);
            if (count <= 0) count = 1;

            return new ItemStack(item, count);

        } catch (Exception ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static Identifier findItemIdFromComponents(NbtCompound components) {
        try {
            String id = components.getString("id", "");
            Identifier found = findItemIdByString(id);
            if (found != null) return found;
        } catch (Exception ignored) {}

        try {
            String itemModel = components.getString("minecraft:item_model", "");
            Identifier found = findItemIdByString(itemModel);
            if (found != null) return found;
        } catch (Exception ignored) {}

        try {
            if (components.contains("minecraft:profile")) {
                return Identifier.of("minecraft:player_head");
            }

            if (components.contains("minecraft:item_name")) {
                NbtCompound itemName = components.getCompoundOrEmpty("minecraft:item_name");
                String translate = itemName.getString("translate", "");

                if ("block.minecraft.player_head".equals(translate)) {
                    return Identifier.of("minecraft:player_head");
                }

                if (translate.startsWith("item.minecraft.")) {
                    return findItemIdByString("minecraft:" + translate.substring("item.minecraft.".length()));
                }

                if (translate.startsWith("block.minecraft.")) {
                    return findItemIdByString("minecraft:" + translate.substring("block.minecraft.".length()));
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static int getCountFromComponentsOrDefault(NbtCompound components) {
        try {
            int maxStack = components.getInt("minecraft:max_stack_size", 1);
            if (maxStack > 1) return 1;
        } catch (Exception ignored) {}

        return 1;
    }


    private static ItemStack fallbackStack(Payment zahlung) {
        if (zahlung == null) {
            return new ItemStack(Items.BARRIER);
        }
        List<String> items = zahlung.getItems();

        if (items == null || items.isEmpty()) {
            return new ItemStack(Items.BARRIER);
        }

        String raw = items.get(0);
        if (raw == null || raw.isBlank()) {
            return new ItemStack(Items.BARRIER);
        }

        int count = extractCount(raw);
        Identifier itemId = findItemIdByRawName(raw);

        if (itemId != null) {
            net.minecraft.item.Item item = Registries.ITEM.get(itemId);

            if (item != null && item != Items.AIR) {
                ItemStack stack = new ItemStack(item, count);
                return stack;
            }
        }

        ItemStack fallback = new ItemStack(Items.PAPER);
        fallback.set(DataComponentTypes.ITEM_NAME, Text.literal("§f" + raw));

        List<Text> lore = new ArrayList<>();
        lore.add(Text.literal("§8Item konnte nicht erkannt werden."));
        lore.add(Text.literal("§8Gespeicherter Name: §7" + raw));
        lore.add(Text.literal(""));
        lore.add(Text.literal("§7Tipp: Für volle NBT/Lore-Anzeige muss"));
        lore.add(Text.literal("§7das Item beim Speichern komplett serialisiert werden."));

        fallback.set(DataComponentTypes.LORE, new LoreComponent(lore));
        return fallback;
    }

    private static Identifier findItemIdByRawName(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String cleaned = cleanRawItemName(raw);

        Identifier direct = findItemIdByString(cleaned);
        if (direct != null) return direct;

        String normalizedPath = normalizeItemNameToPath(cleaned);

        Identifier pathId = findItemIdByString("minecraft:" + normalizedPath);
        if (pathId != null) return pathId;

        for (Identifier id : Registries.ITEM.getIds()) {
            net.minecraft.item.Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) continue;

            String idPath = id.getPath();
            String idPathSpaces = idPath.replace("_", " ");

            if (idPath.equalsIgnoreCase(normalizedPath)) {
                return id;
            }

            if (idPathSpaces.equalsIgnoreCase(cleaned)) {
                return id;
            }

            String translatedName = item.getName().getString();
            if (translatedName != null) {
                String translatedClean = cleanRawItemName(translatedName);
                if (translatedClean.equalsIgnoreCase(cleaned)) {
                    return id;
                }

                String translatedPath = normalizeItemNameToPath(translatedClean);
                if (translatedPath.equalsIgnoreCase(normalizedPath)) {
                    return id;
                }
            }
        }

        return null;
    }

    private static Identifier findItemIdByString(String value) {
        if (value == null || value.isBlank()) return null;

        String id = value.trim();

        if (id.contains("{")) {
            id = id.substring(0, id.indexOf("{")).trim();
        }

        if (id.contains(" ")) {
            id = normalizeItemNameToPath(id);
        }

        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }

        try {
            Identifier identifier = Identifier.of(id);
            net.minecraft.item.Item item = Registries.ITEM.get(identifier);

            if (item != null && item != Items.AIR) {
                return identifier;
            }
        } catch (Exception ignored) {}

        return null;
    }

    private static String cleanRawItemName(String raw) {
        String s = raw;

        s = s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        s = s.replaceAll("(?i)^\\s*\\d+\\s*(x|×)?\\s*", "");
        s = s.replaceAll("(?i)^\\s*x\\s*\\d+\\s*", "");

        if (s.contains("{")) {
            s = s.substring(0, s.indexOf("{"));
        }

        if (s.contains(" - ")) {
            s = s.substring(0, s.indexOf(" - "));
        }

        s = s.trim();

        while (s.startsWith("\"") || s.startsWith("'")) {
            s = s.substring(1).trim();
        }

        while (s.endsWith("\"") || s.endsWith("'")) {
            s = s.substring(0, s.length() - 1).trim();
        }

        return s;
    }

    private static String normalizeItemNameToPath(String name) {
        String s = cleanRawItemName(name).toLowerCase();

        s = s.replace("minecraft:", "");
        s = s.replace("-", "_");
        s = s.replace(" ", "_");
        s = s.replaceAll("[^a-z0-9_:/]", "");
        s = s.replaceAll("_+", "_");

        return s;
    }

    private static int extractCount(String raw) {
        if (raw == null) return 1;

        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^\\s*(\\d+)\\s*(x|×)?\\s+.*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(raw);

            if (matcher.matches()) {
                int count = Integer.parseInt(matcher.group(1));
                return Math.max(1, Math.min(64, count));
            }
        } catch (Exception ignored) {}

        return 1;
    }


    @Override
    protected void init() {
        panelScale = GuiScaleUtil.compactPanelScale(width, height, PANEL_B, PANEL_H);
        pX        = GuiScaleUtil.centeredX(width, panelScale, PANEL_B);
        pY        = GuiScaleUtil.centeredY(height, panelScale, PANEL_H);
        guiX      = pX;
        guiY      = pY;
        guiBreite = PANEL_B;
    }

    @Override
    protected void fülleSlots() { }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {

        int layoutMouseX = GuiScaleUtil.toLayoutCoordinate(mouseX, panelScale);
        int layoutMouseY = GuiScaleUtil.toLayoutCoordinate(mouseY, panelScale);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().scale(panelScale, panelScale);

        int btnX = pX + RAND;
        int btnY = pY + PANEL_H - 30;
        int btnW = PANEL_B - RAND * 2;
        int btnH = 14;
        btnHover = layoutMouseX >= btnX && layoutMouseX <= btnX + btnW
                && layoutMouseY >= btnY && layoutMouseY <= btnY + btnH;

        drawPanel(ctx);
        drawNav(ctx);
        drawTrennlinie(ctx);
        drawInhalt(ctx, layoutMouseX, layoutMouseY);
        ctx.getMatrices().popMatrix();

    }

    private void drawPanel(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_B, h = PANEL_H;

        ctx.fill(x, y, x + w, y + h, C_PANEL_BG);

        ctx.fill(x + 2, y,         x + w - 2, y + 1,         C_RAND_AUSS);
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h,         C_RAND_AUSS);
        ctx.fill(x,     y + 2,     x + 1,     y + h - 2,      C_RAND_AUSS);
        ctx.fill(x + w - 1, y + 2, x + w,     y + h - 2,      C_RAND_AUSS);

        ctx.fill(x + 1,     y + 1,     x + 2,     y + 2,     C_RAND_ECKE);
        ctx.fill(x + w - 2, y + 1,     x + w - 1, y + 2,     C_RAND_ECKE);
        ctx.fill(x + 1,     y + h - 2, x + 2,     y + h - 1, C_RAND_ECKE);
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, C_RAND_ECKE);

        ctx.fill(x + 2, y + 1, x + w - 2, y + 2,         C_HIGHLIGHT);
        ctx.fill(x + 1, y + 2, x + 2,     y + h - 2,     C_HIGHLIGHT);
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, C_DUNKEL);
        ctx.fill(x + w - 2, y + 2, x + w - 1, y + h - 2, C_DUNKEL);
    }

    private void drawNav(DrawContext ctx) {
        int x = pX, y = pY, w = PANEL_B;
        ctx.fill(x, y, x + w, y + NAV_H, C_NAV_BG);
        ctx.fill(x, y + NAV_H - 1, x + w, y + NAV_H, C_NAV_BORDER);

        int textY = y + (NAV_H - 8) / 2 + 1;
        int curX  = x + RAND;
        String pre   = "Credit Manager";
        String arr   = " » ";
        String seite = "Item-Zahlung ansehen";

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

    private void drawInhalt(DrawContext ctx, int mouseX, int mouseY) {
        int x    = pX + RAND;
        int maxW = PANEL_B - RAND * 2;
        int curY = pY + NAV_H + 2 + 10;

        ctx.drawText(textRenderer, Text.literal("§8▸ Item-Zahlung"), x, curY, C_LABEL, false);
        curY += 12;

        int showcaseX = pX + (PANEL_B - SHOWCASE_SIZE) / 2;
        int showcaseY = curY;

        ctx.fill(showcaseX - 2, showcaseY - 2,
                showcaseX + SHOWCASE_SIZE + 2, showcaseY + SHOWCASE_SIZE + 2, C_SLOT_D);
        ctx.fill(showcaseX - 1, showcaseY - 1,
                showcaseX + SHOWCASE_SIZE + 1, showcaseY + SHOWCASE_SIZE + 1, C_SLOT_BG);

        var matrices = ctx.getMatrices();

        matrices.pushMatrix();
        matrices.translate((float) showcaseX, (float) showcaseY);
        matrices.scale(3.0f, 3.0f);

        ctx.drawItem(parsedStack, 0, 0);

        matrices.popMatrix();

        ctx.fill(showcaseX, showcaseY,
                showcaseX + SHOWCASE_SIZE, showcaseY + SHOWCASE_SIZE, C_ITEM_GL);

        boolean showcaseHover = mouseX >= showcaseX && mouseX <= showcaseX + SHOWCASE_SIZE
                && mouseY >= showcaseY && mouseY <= showcaseY + SHOWCASE_SIZE;

        if (showcaseHover) {
            ctx.fill(showcaseX, showcaseY,
                    showcaseX + SHOWCASE_SIZE, showcaseY + SHOWCASE_SIZE, C_HOVER);
            ctx.drawItemTooltip(textRenderer, parsedStack, mouseX, mouseY);
        }

        curY += SHOWCASE_SIZE + 10;

        Text nameText = parsedStack.getName();
        int nameW = textRenderer.getWidth(nameText);

        ctx.drawText(textRenderer, nameText,
                pX + (PANEL_B - nameW) / 2, curY, C_WHITE, false);

        curY += 12;

        String regId = Registries.ITEM.getId(parsedStack.getItem()).toString();
        int regIdW = textRenderer.getWidth(regId);

        ctx.drawText(textRenderer, Text.literal(regId),
                pX + (PANEL_B - regIdW) / 2, curY, C_DARK, false);

        curY += 12;

        ctx.fill(pX + RAND, curY, pX + PANEL_B - RAND, curY + 1, C_TRENN_D);
        curY += 6;

        String betrag = (zahlung != null && zahlung.getAmount() != null && zahlung.getAmount() > 0)
                ? "§6" + FormatUtil.formatiereBetrag(zahlung.getAmount())
                : "§8– (Item-Tausch)";

        String valueLabel = resolvePaymentStacks(zahlung).size() > 1 ? "Gemeinsamer Wert:" : "Wert:";
        drawZeile(ctx, x, curY, maxW, valueLabel, betrag);
        curY += 12;

        drawZeile(ctx, x, curY, maxW, "Von:", "§f" + anzeigeVon);
        curY += 11;

        drawZeile(ctx, x, curY, maxW, "An:", "§f" + anzeigeAn);
        curY += 11;

        String zeitpunkt = zahlung != null
                ? TimeUtil.formatDatumZeit(zahlung.getTimestamp())
                : "Unbekannt";

        drawZeile(ctx, x, curY, maxW, "Zeitpunkt:", "§7" + zeitpunkt);

        int btnX = pX + RAND;
        int btnY = pY + PANEL_H - 30;
        int btnW = PANEL_B - RAND * 2;
        int btnH = 14;

        ctx.fill(btnX, btnY, btnX + btnW, btnY + btnH,
                btnHover ? C_BTN_HOV : C_BTN_BG);

        ctx.fill(btnX, btnY, btnX + btnW, btnY + 1, C_SLOT_H);
        ctx.fill(btnX, btnY, btnX + 1, btnY + btnH, C_SLOT_H);
        ctx.fill(btnX, btnY + btnH - 1, btnX + btnW, btnY + btnH, C_SLOT_D);
        ctx.fill(btnX + btnW - 1, btnY, btnX + btnW, btnY + btnH, C_SLOT_D);

        String btnText = "§7← Zurück";
        int btnTextW = textRenderer.getWidth(btnText);

        ctx.drawText(textRenderer, Text.literal(btnText),
                btnX + (btnW - btnTextW) / 2,
                btnY + (btnH - 8) / 2,
                btnHover ? C_WHITE : C_GRAY,
                false);
    }

    private void drawZeile(DrawContext ctx, int x, int y, int maxW,
                           String label, String wert) {
        ctx.drawText(textRenderer, Text.literal("§8" + label), x, y, C_LABEL, false);
        int wertW = textRenderer.getWidth(wert);
        ctx.drawText(textRenderer, Text.literal(wert), x + maxW - wertW, y, C_WHITE, false);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        net.minecraft.client.gui.Click layoutClick = GuiScaleUtil.toLayoutClick(click, panelScale);
        double mouseX = layoutClick.x();
        double mouseY = layoutClick.y();

        int btnX = pX + RAND;
        int btnY = pY + PANEL_H - 30;
        int btnW = PANEL_B - RAND * 2;
        int btnH = 14;

        boolean clicked = mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + btnH;

        if (clicked) {
            MinecraftClient.getInstance().setScreen(elternScreen);
            return true;
        }

        return super.mouseClicked(layoutClick, doubled);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.getKeycode();

        if (keyCode == 256 || keyCode == 69) {
            MinecraftClient.getInstance().setScreen(elternScreen);
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    protected boolean onSlotKlick(int slot, ItemStack stack) { return false; }
}

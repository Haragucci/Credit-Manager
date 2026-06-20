package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

/** Detail view used only by the modern payment-item flow. Vanilla item text and tooltips stay untouched. */
public final class ModernItemDetailScreen extends ModernBaseScreen {
    private final Payment payment;
    private final ItemStack stack;
    private final int itemIndex;
    private final int itemCount;
    private int itemX;
    private int itemY;

    public ModernItemDetailScreen(CreditManager manager, Payment payment, ItemStack stack, int itemIndex, int itemCount, Screen parent) {
        super(manager, parent, "Item-Details", "details");
        this.payment = payment;
        this.stack = stack.copy();
        this.itemIndex = itemIndex;
        this.itemCount = itemCount;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        int cardWidth = contentWidth;
        int cardY = contentY + 6;
        ModernUi.card(context, contentX, cardY, cardWidth, 166, false);
        ModernUi.drawGuiText(context, textRenderer, "Item " + (itemIndex + 1) + " von " + itemCount, contentX + 12, cardY + 10, theme.muted);

        itemX = contentX + 18;
        itemY = cardY + 33;
        ModernUi.card(context, itemX - 4, itemY - 4, 56, 56,
                ModernUi.contains(mouseX, mouseY, itemX - 4, itemY - 4, 56, 56));
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(itemX, itemY);
        context.getMatrices().scale(3.0F, 3.0F);
        context.drawItem(stack, 0, 0);
        context.getMatrices().popMatrix();

        int detailsX = itemX + 66;
        int detailsWidth = Math.max(40, contentX + cardWidth - detailsX - 12);
        // The item name is intentionally drawn as vanilla Text, so resource packs retain their own item font.
        context.enableScissor(detailsX, cardY + 28, detailsX + detailsWidth, cardY + 43);
        context.drawText(textRenderer, stack.getName(), detailsX, cardY + 30, theme.text, false);
        context.disableScissor();
        ModernUi.drawTruncated(context, textRenderer, Registries.ITEM.getId(stack.getItem()).toString(), detailsX, cardY + 46,
                detailsWidth, theme.muted);
        ModernUi.drawGuiText(context, textRenderer, "Anzahl: " + stack.getCount(), detailsX, cardY + 65, theme.accent);

        int rowY = cardY + 100;
        String amount = payment.getAmount() == null || payment.getAmount() <= 0.0 ? "Item-Tausch" : FormatUtil.formatAmount(payment.getAmount());
        drawRow(context, "Wert", amount, rowY, theme);
        drawRow(context, "Von", safe(payment.getFromPlayer()), rowY + 16, theme);
        drawRow(context, "An", safe(payment.getToPlayer()), rowY + 32, theme);
        drawRow(context, "Zeitpunkt", TimeUtil.formatDateTime(payment.getTimestamp()), rowY + 48, theme);

        if (ModernUi.contains(mouseX, mouseY, itemX - 4, itemY - 4, 56, 56)) {
            context.drawItemTooltip(textRenderer, stack, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRow(DrawContext context, String label, String value, int y, ModernThemePalette theme) {
        ModernUi.drawGuiText(context, textRenderer, label + ":", contentX + 12, y, theme.muted);
        ModernUi.drawTruncated(context, textRenderer, value, contentX + 92, y, Math.max(36, contentWidth - 104), theme.text);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }
}

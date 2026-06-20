package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.ItemInspektionScreen;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.List;

/** Modern item-payment list and modern detail inspector for the modern GUI flow. */
public class ModernItemInspectionScreen extends ModernBaseScreen {

    private static final int ROW_HEIGHT = 30;

    private final Payment payment;
    private final List<ItemStack> stacks;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private int listY;
    private int listHeight;

    public ModernItemInspectionScreen(CreditManager manager, Payment payment, Screen parent) {
        super(manager, parent, "Items der Zahlung", "details");
        this.payment = payment;
        this.stacks = ItemInspektionScreen.resolvePaymentStacks(payment);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);

        String amount = payment.getAmount() == null ? "Ohne Geldwert" : FormatUtil.formatAmount(payment.getAmount());
        ModernUi.drawTruncated(context, textRenderer, stacks.size() + " Items - gemeinsamer Wert: " + amount,
                contentX, contentY + 8, contentWidth, ModernUi.theme().warning);
        ModernUi.drawTruncated(context, textRenderer, "Klicke ein Item für die vollständige Inspektion an.",
                contentX, contentY + 23, contentWidth, ModernUi.theme().muted);

        listY = contentY + 39;
        listHeight = Math.max(34, contentY + contentHeight - listY - 4);
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        scroll.setBounds(contentX, listY, contentWidth, listHeight, stacks.size() * ROW_HEIGHT);
        scroll.tick(mouseX, mouseY);
        int offset = scroll.offset();
        int first = offset / ROW_HEIGHT;
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int row = first; row < Math.min(stacks.size(), first + visibleRows + 2); row++) {
            drawListEntry(context, mouseX, mouseY, stacks.get(row), row,
                    contentX, listY + row * ROW_HEIGHT - offset, contentWidth - (scroll.isScrollable() ? 8 : 0));
        }
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawListEntry(DrawContext context, int mouseX, int mouseY, ItemStack stack, int index, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, ROW_HEIGHT - 3);
        ModernUi.card(context, x, y, width, ROW_HEIGHT - 3, hovered);
        context.drawItem(stack, x + 8, y + 6);
        // Item names keep their vanilla/resource-pack styling. Only the surrounding mod labels use ModernUi's font.
        context.enableScissor(x + 30, y + 5, x + width - 12, y + 15);
        context.drawText(textRenderer, stack.getName(), x + 30, y + 5, ModernUi.theme().text, false);
        context.disableScissor();
        ModernUi.drawTruncated(context, textRenderer,
                "Anzahl: " + stack.getCount() + " - Item " + (index + 1) + "/" + stacks.size() + " - Klicken zum Inspizieren",
                x + 30, y + 16, width - 42, hovered ? ModernUi.theme().warning : ModernUi.theme().muted);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / ROW_HEIGHT);
            if (index >= 0 && index < stacks.size()) {
                open(new ModernItemDetailScreen(manager, payment, stacks.get(index), index, stacks.size(), this));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, listHeight) && verticalAmount != 0) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void clearTransientState() {
        scroll.reset();
        super.clearTransientState();
    }
}

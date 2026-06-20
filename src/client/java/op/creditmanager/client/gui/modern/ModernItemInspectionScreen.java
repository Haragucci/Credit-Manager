package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.ItemInspektionScreen;
import op.creditmanager.client.model.Payment;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;

/** Modern item-payment list which opens the detailed classic inspector only after a click. */
public class ModernItemInspectionScreen extends ModernBaseScreen {

    private static final int ROW_HEIGHT = 30;

    private final Payment payment;
    private final List<ItemStack> stacks;
    private int scrollOffset;
    private int listY;
    private int listHeight;
    private int backY;

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
                contentX, contentY + 8, contentWidth, ModernUi.YELLOW);
        ModernUi.drawTruncated(context, textRenderer, "Klicke ein Item für die vollständige Inspektion an.",
                contentX, contentY + 23, contentWidth, ModernUi.MUTED);

        listY = contentY + 39;
        listHeight = Math.max(34, contentY + contentHeight - listY - 36);
        int visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
        int maxOffset = Math.max(0, stacks.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxOffset));
        for (int row = 0; row < visibleRows && row + scrollOffset < stacks.size(); row++) {
            int itemIndex = row + scrollOffset;
            drawListEntry(context, mouseX, mouseY, stacks.get(itemIndex), itemIndex,
                    contentX, listY + row * ROW_HEIGHT, contentWidth);
        }

        backY = contentY + contentHeight - 25;
        ModernUi.button(context, textRenderer, contentX, backY, Math.min(140, contentWidth), 22, "Zurück", ModernUi.BUTTON_NEUTRAL,
                ModernUi.contains(mouseX, mouseY, contentX, backY, Math.min(140, contentWidth), 22));
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawListEntry(DrawContext context, int mouseX, int mouseY, ItemStack stack, int index, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, ROW_HEIGHT - 3);
        ModernUi.card(context, x, y, width, ROW_HEIGHT - 3, hovered);
        context.drawItem(stack, x + 8, y + 6);
        ModernUi.drawTruncated(context, textRenderer, stack.getName().getString(), x + 30, y + 5, width - 42, ModernUi.TEXT);
        ModernUi.drawTruncated(context, textRenderer,
                "Anzahl: " + stack.getCount() + " - Item " + (index + 1) + "/" + stacks.size() + " - Klicken zum Inspizieren",
                x + 30, y + 16, width - 42, hovered ? ModernUi.YELLOW : ModernUi.MUTED);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, backY, Math.min(140, contentWidth), 22)) {
            closeToParent();
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = scrollOffset + (int) ((click.y() - listY) / ROW_HEIGHT);
            if (index >= 0 && index < stacks.size()) {
                open(new ItemInspektionScreen(payment, index, this));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, listHeight) && verticalAmount != 0) {
            int maxOffset = Math.max(0, stacks.size() - Math.max(1, listHeight / ROW_HEIGHT));
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }
}

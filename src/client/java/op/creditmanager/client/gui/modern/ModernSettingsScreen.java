package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

/** Settings dashboard. Individual categories keep the main screen calm and easy to scan. */
public class ModernSettingsScreen extends ModernBaseScreen {
    private static final ModernSettingsDetailScreen.Category[] CATEGORIES = ModernSettingsDetailScreen.Category.values();
    private static final int CATEGORY_ROW_HEIGHT = 54;
    private final ModernScrollArea categoryScroll = new ModernScrollArea();
    private int listY;
    private int listHeight;

    public ModernSettingsScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Einstellungen", "settings");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        listY = contentY + 8;
        listHeight = Math.max(32, contentHeight - 12);
        categoryScroll.setBounds(contentX, listY, contentWidth, listHeight, CATEGORIES.length * CATEGORY_ROW_HEIGHT);
        categoryScroll.tick(mouseX, mouseY);
        int offset = categoryScroll.offset();
        int first = offset / CATEGORY_ROW_HEIGHT;
        int last = Math.min(CATEGORIES.length, first + listHeight / CATEGORY_ROW_HEIGHT + 3);
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int index = first; index < last; index++) {
            ModernSettingsDetailScreen.Category category = CATEGORIES[index];
            int y = listY + index * CATEGORY_ROW_HEIGHT - offset;
            boolean hovered = ModernUi.contains(mouseX, mouseY, contentX, y, contentWidth, CATEGORY_ROW_HEIGHT - 5);
            ModernUi.card(context, contentX, y, contentWidth - (categoryScroll.isScrollable() ? 8 : 0), CATEGORY_ROW_HEIGHT - 5, hovered);
            ModernUi.drawGuiText(context, textRenderer, category.label(), contentX + 13, y + 10, theme.text);
            ModernUi.drawTruncated(context, textRenderer, category.description(), contentX + 13, y + 24,
                    contentWidth - 44, theme.muted);
            ModernUi.drawGuiText(context, textRenderer, "›", contentX + contentWidth - 26, y + 21, theme.accent);
        }
        context.disableScissor();
        categoryScroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (categoryScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + categoryScroll.offset()) / CATEGORY_ROW_HEIGHT);
            if (index >= 0 && index < CATEGORIES.length) {
                open(new ModernSettingsDetailScreen(manager, this, CATEGORIES[index]));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (categoryScroll.contains(mouseX, mouseY)) {
            categoryScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void clearTransientState() {
        categoryScroll.reset();
        super.clearTransientState();
    }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;
import op.creditmanager.client.search.PaylogSearch;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.Comparator;
import java.util.List;

/** Paylog history with player search, direction filter and mouse-wheel scrolling. */
public class ModernPaylogScreen extends ModernBaseScreen {

    private TextFieldWidget searchField;
    private int directionIndex;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private int listY;
    private int listHeight;
    private List<TransactionEntry> renderedEntries = List.of();
    private int renderedStart;

    private static final String[] DIRECTION_FILTERS = {"Alle", "Eingehend", "Ausgehend"};

    public ModernPaylogScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Paylogs", "paylogs");
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        searchField = ModernUi.configureGuiTextField(
                new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 34, Text.empty()));
        searchField.setMaxLength(64);
        ModernUi.setGuiPlaceholder(searchField, "Spieler, Betrag, Datum oder Freitext...");
        addDrawableChild(searchField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        int toolbarY = contentY + 4;
        int searchWidth = Math.max(60, contentWidth - 16);
        int filterWidth = Math.max(72, Math.min(104, contentWidth));
        ModernUi.card(context, contentX, toolbarY, contentWidth, 34,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY, contentWidth, 34));
        ModernUi.button(context, textRenderer, contentX, toolbarY + 39, filterWidth, 24,
                DIRECTION_FILTERS[directionIndex], ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY + 39, filterWidth, 24));

        List<TransactionEntry> entries = filteredEntries();
        listY = toolbarY + 70;
        listHeight = Math.max(48, contentHeight - 78);
        int rowHeight = 39;
        int visibleRows = Math.max(1, listHeight / rowHeight);
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight);
        scroll.tick(mouseX, mouseY);
        int pixelOffset = scroll.offset();
        renderedStart = Math.max(0, pixelOffset / rowHeight);
        int end = Math.min(entries.size(), renderedStart + visibleRows + 2);
        renderedEntries = entries.subList(renderedStart, end);

        if (renderedEntries.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(65, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, "Keine Paylogs für diesen Filter gefunden.", contentX + contentWidth / 2,
                    listY + 25, ModernUi.theme().muted);
        } else {
            context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
            for (int i = 0; i < renderedEntries.size(); i++) {
                drawTransaction(context, mouseX, mouseY, renderedEntries.get(i), contentX,
                        listY + (renderedStart + i) * rowHeight - pixelOffset, contentWidth - (scroll.isScrollable() ? 8 : 0));
            }
            context.disableScissor();
            scroll.renderScrollbar(context, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private List<TransactionEntry> filteredEntries() {
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim();
        return TransactionRepository.getInstance().getAll().stream()
                .filter(entry -> player.isBlank() || equals(entry.getFromPlayer(), player) || equals(entry.getToPlayer(), player))
                .filter(entry -> directionIndex == 0
                        || (directionIndex == 1 && equals(entry.getToPlayer(), player))
                        || (directionIndex == 2 && equals(entry.getFromPlayer(), player)))
                .filter(entry -> PaylogSearch.matches(entry, query))
                .sorted(Comparator.<TransactionEntry>comparingInt(entry -> PaylogSearch.score(entry, query)).reversed()
                        .thenComparing(Comparator.comparingLong(TransactionEntry::getTimestamp).reversed()))
                .limit(500)
                .toList();
    }

    private void drawTransaction(DrawContext context, int mouseX, int mouseY, TransactionEntry entry, int x, int y, int width) {
        boolean outgoing = equals(entry.getFromPlayer(), currentPlayerName());
        int color = outgoing ? ModernUi.theme().danger : ModernUi.theme().success;
        ModernUi.card(context, x, y, width, 35, ModernUi.contains(mouseX, mouseY, x, y, width, 35));
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String otherPlayer = outgoing ? entry.getToPlayer() : entry.getFromPlayer();
        ModernUi.drawTruncated(context, textRenderer, (outgoing ? "An " : "Von ") + safe(otherPlayer), x + 19, y + 7,
                Math.max(40, width - 164), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, TimeUtil.formatDateTime(entry.getTimestamp()), x + 19, y + 20,
                Math.max(40, width - 164), ModernUi.theme().muted);
        String amount = (outgoing ? "-" : "+") + FormatUtil.formatAmount(entry.getAmount());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, amount, x + width - 12, y + 13, color);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0) {
            int toolbarY = contentY + 4;
            int filterWidth = Math.max(72, Math.min(104, contentWidth));
            if (ModernUi.contains(click.x(), click.y(), contentX, toolbarY + 39, filterWidth, 24)) {
                directionIndex = (directionIndex + 1) % DIRECTION_FILTERS.length;
                scroll.scrollToStart();
                return true;
            }
            if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
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

    private boolean equals(String value, String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }

    @Override
    protected void clearTransientState() {
        scroll.reset();
        if (searchField != null) searchField.setText("");
        super.clearTransientState();
    }
}

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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Paylog history with player search, direction filter and mouse-wheel scrolling. */
public class ModernPaylogScreen extends ModernBaseScreen {

    private TextFieldWidget searchField;
    private int directionIndex;
    private int scrollOffset;
    private int listY;
    private int listHeight;
    private List<TransactionEntry> renderedEntries = List.of();

    private static final String[] DIRECTION_FILTERS = {"Alle", "Eingehend", "Ausgehend"};

    public ModernPaylogScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Paylogs", "paylogs");
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        searchField = new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 34, Text.empty());
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Spieler suchen..."));
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
                DIRECTION_FILTERS[directionIndex], ModernUi.BUTTON_NEUTRAL,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY + 39, filterWidth, 24));

        List<TransactionEntry> entries = filteredEntries();
        listY = toolbarY + 70;
        listHeight = Math.max(48, contentHeight - 78);
        int rowHeight = 39;
        int visibleRows = Math.max(1, listHeight / rowHeight);
        int maxOffset = Math.max(0, entries.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        int end = Math.min(entries.size(), scrollOffset + visibleRows);
        renderedEntries = entries.subList(scrollOffset, end);

        if (renderedEntries.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(65, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, "Keine Paylogs für diesen Filter gefunden.", contentX + contentWidth / 2,
                    listY + 25, ModernUi.MUTED);
        } else {
            for (int i = 0; i < renderedEntries.size(); i++) {
                drawTransaction(context, mouseX, mouseY, renderedEntries.get(i), contentX, listY + i * rowHeight, contentWidth);
            }
            if (maxOffset > 0) {
                ModernUi.drawTruncated(context, textRenderer, (scrollOffset + 1) + "–" + end + " von " + entries.size() + " · Mausrad zum Scrollen",
                        contentX + 4, listY + visibleRows * rowHeight + 3, contentWidth - 8, ModernUi.MUTED);
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private List<TransactionEntry> filteredEntries() {
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        return TransactionRepository.getInstance().getAll().stream()
                .filter(entry -> player.isBlank() || equals(entry.getFromPlayer(), player) || equals(entry.getToPlayer(), player))
                .filter(entry -> directionIndex == 0
                        || (directionIndex == 1 && equals(entry.getToPlayer(), player))
                        || (directionIndex == 2 && equals(entry.getFromPlayer(), player)))
                .filter(entry -> query.isEmpty() || contains(entry.getFromPlayer(), query) || contains(entry.getToPlayer(), query))
                .sorted(Comparator.comparingLong(TransactionEntry::getTimestamp).reversed())
                .limit(500)
                .toList();
    }

    private void drawTransaction(DrawContext context, int mouseX, int mouseY, TransactionEntry entry, int x, int y, int width) {
        boolean outgoing = equals(entry.getFromPlayer(), currentPlayerName());
        int color = outgoing ? ModernUi.RED : ModernUi.GREEN;
        ModernUi.card(context, x, y, width, 35, ModernUi.contains(mouseX, mouseY, x, y, width, 35));
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String otherPlayer = outgoing ? entry.getToPlayer() : entry.getFromPlayer();
        ModernUi.drawTruncated(context, textRenderer, (outgoing ? "An " : "Von ") + safe(otherPlayer), x + 19, y + 7,
                Math.max(40, width - 164), ModernUi.TEXT);
        ModernUi.drawTruncated(context, textRenderer, TimeUtil.formatDateTime(entry.getTimestamp()), x + 19, y + 20,
                Math.max(40, width - 164), ModernUi.MUTED);
        String amount = (outgoing ? "-" : "+") + FormatUtil.formatAmount(entry.getAmount());
        int amountWidth = textRenderer.getWidth(amount);
        context.drawText(textRenderer, Text.literal(amount), x + width - amountWidth - 12, y + 13, color, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0) {
            int toolbarY = contentY + 4;
            int filterWidth = Math.max(72, Math.min(104, contentWidth));
            if (ModernUi.contains(click.x(), click.y(), contentX, toolbarY + 39, filterWidth, 24)) {
                directionIndex = (directionIndex + 1) % DIRECTION_FILTERS.length;
                scrollOffset = 0;
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, listHeight) && verticalAmount != 0) {
            int maxOffset = Math.max(0, filteredEntries().size() - Math.max(1, listHeight / 39));
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean equals(String value, String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }
}

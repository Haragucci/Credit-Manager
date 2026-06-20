package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.SkinHeadUtil;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Searchable, scrollable replacement for the legacy deal-slot list. */
public class ModernCreditListScreen extends ModernBaseScreen {

    private final boolean debts;
    private TextFieldWidget searchField;
    private int statusIndex;
    private int scrollOffset;
    private int listY;
    private int listHeight;
    private List<CreditEntry> renderedEntries = List.of();
    private final Map<String, ItemStack> playerHeads = new ConcurrentHashMap<>();

    private static final String[] FILTERS = {"Alle", "Offen", "Teilweise", "Bezahlt", "Storniert"};

    public ModernCreditListScreen(CreditManager manager, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Schulden" : "Forderungen", debts ? "debts" : "claims");
        this.debts = debts;
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        searchField = new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 34, Text.empty());
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal("Spieler oder Deal suchen..."));
        addDrawableChild(searchField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        int toolbarY = contentY + 4;
        int searchWidth = Math.max(60, contentWidth - 16);
        int filterWidth = Math.max(64, Math.min(92, contentWidth - 64));
        int newButtonX = contentX + filterWidth + 8;
        int newButtonWidth = Math.max(48, contentWidth - filterWidth - 8);
        ModernUi.card(context, contentX, toolbarY, contentWidth, 34,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY, contentWidth, 34));
        ModernUi.button(context, textRenderer, contentX, toolbarY + 39, filterWidth, 24,
                FILTERS[statusIndex], ModernUi.BUTTON_NEUTRAL,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY + 39, filterWidth, 24));
        ModernUi.button(context, textRenderer, newButtonX, toolbarY + 39, newButtonWidth, 24, "+ Neu",
                debts ? ModernUi.BUTTON_DANGER : ModernUi.BUTTON_PRIMARY,
                ModernUi.contains(mouseX, mouseY, newButtonX, toolbarY + 39, newButtonWidth, 24));

        List<CreditEntry> entries = filteredEntries();
        listY = toolbarY + 70;
        listHeight = Math.max(48, contentHeight - 78);
        int rowHeight = 48;
        int visibleRows = Math.max(1, listHeight / rowHeight);
        int maxOffset = Math.max(0, entries.size() - visibleRows);
        scrollOffset = Math.min(scrollOffset, maxOffset);
        int end = Math.min(entries.size(), scrollOffset + visibleRows);
        renderedEntries = entries.subList(scrollOffset, end);

        if (renderedEntries.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(74, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, "Keine passenden Deals gefunden.", contentX + contentWidth / 2,
                    listY + 24, ModernUi.MUTED);
            ModernUi.drawCentered(context, textRenderer, "Lege über '+ Neu' einen Deal an.", contentX + contentWidth / 2,
                    listY + 40, ModernUi.MUTED);
        } else {
            for (int i = 0; i < renderedEntries.size(); i++) {
                drawEntry(context, mouseX, mouseY, renderedEntries.get(i), contentX, listY + i * rowHeight, contentWidth);
            }
            if (maxOffset > 0) {
                ModernUi.drawTruncated(context, textRenderer,
                        (scrollOffset + 1) + "–" + end + " von " + entries.size() + " · Mausrad zum Scrollen",
                        contentX + 4, listY + visibleRows * rowHeight + 3, contentWidth - 8, ModernUi.MUTED);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawEntry(DrawContext context, int mouseX, int mouseY, CreditEntry entry, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 43);
        ModernUi.card(context, x, y, width, 43, hovered);
        int accent = statusColor(entry.getStatus());
        String otherPlayer = debts ? entry.getCreditor() : entry.getDebtor();
        context.drawItem(headFor(otherPlayer), x + 8, y + 13);
        String players = safe(entry.getDebtor()) + " -> " + safe(entry.getCreditor());
        ModernUi.drawTruncated(context, textRenderer, players, x + 30, y + 7, Math.max(40, width - 170), ModernUi.TEXT);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 30, y + 22,
                Math.max(40, width - 170), ModernUi.MUTED);
        String amount = FormatUtil.formatAmount(entry.getRemainingAmount());
        int amountWidth = textRenderer.getWidth(amount);
        context.drawText(textRenderer, Text.literal(amount), x + width - amountWidth - 12, y + 9,
                debts ? ModernUi.RED : ModernUi.GREEN, false);
        String status = statusLabel(entry.getStatus());
        int statusWidth = textRenderer.getWidth(status);
        context.drawText(textRenderer, Text.literal(status), x + width - statusWidth - 12, y + 24, accent, false);
        if (TimeUtil.isOverdue(entry.getDueDate())) {
            context.fill(x + 5, y + 5, x + 8, y + 8, ModernUi.RED);
        }
    }

    private ItemStack headFor(String playerName) {
        String key = safe(playerName).toLowerCase(Locale.ROOT);
        return playerHeads.computeIfAbsent(key, ignored -> {
            ItemStack head = new ItemStack(Items.PLAYER_HEAD);
            SkinHeadUtil.setzeSkin(head, playerName, () -> playerHeads.remove(key));
            SkinHeadUtil.versteckeProfilTooltip(head);
            return head;
        });
    }

    private List<CreditEntry> filteredEntries() {
        String player = currentPlayerName();
        List<CreditEntry> source = debts ? manager.getAllCreditsAsDebtor(player) : manager.getAllCreditsAsCreditor(player);
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(entry -> statusMatches(entry.getStatus()))
                .filter(entry -> query.isEmpty()
                        || contains(entry.getDealName(), query)
                        || contains(entry.getCreditor(), query)
                        || contains(entry.getDebtor(), query))
                .sorted(Comparator.comparingLong(CreditEntry::getCreatedAt).reversed())
                .toList();
    }

    private boolean statusMatches(String status) {
        return switch (statusIndex) {
            case 1 -> CreditManager.STATUS_OPEN.equals(status);
            case 2 -> CreditManager.STATUS_PARTIAL.equals(status);
            case 3 -> CreditManager.STATUS_PAID.equals(status);
            case 4 -> CreditManager.STATUS_CANCELLED.equals(status);
            default -> true;
        };
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0) {
            int toolbarY = contentY + 4;
            int filterWidth = Math.max(64, Math.min(92, contentWidth - 64));
            int newButtonX = contentX + filterWidth + 8;
            int newButtonWidth = Math.max(48, contentWidth - filterWidth - 8);
            if (ModernUi.contains(click.x(), click.y(), contentX, toolbarY + 39, filterWidth, 24)) {
                statusIndex = (statusIndex + 1) % FILTERS.length;
                scrollOffset = 0;
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), newButtonX, toolbarY + 39, newButtonWidth, 24)) {
                open(new ModernCreateCreditScreen(manager, debts, this));
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
                int index = (int) ((click.y() - listY) / 48);
                if (index >= 0 && index < renderedEntries.size()) {
                    open(new ModernCreditDetailScreen(manager, renderedEntries.get(index), debts, this));
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, listHeight) && verticalAmount != 0) {
            List<CreditEntry> entries = filteredEntries();
            int maxOffset = Math.max(0, entries.size() - Math.max(1, listHeight / 48));
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private int statusColor(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> ModernUi.GREEN;
            case CreditManager.STATUS_PARTIAL -> ModernUi.YELLOW;
            case CreditManager.STATUS_CANCELLED -> ModernUi.MUTED;
            default -> ModernUi.RED;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise";
            case CreditManager.STATUS_CANCELLED -> "Storniert";
            default -> "Offen";
        };
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }
}

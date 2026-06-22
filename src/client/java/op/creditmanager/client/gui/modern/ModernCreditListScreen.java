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
import op.creditmanager.client.search.FuzzySearch;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ModernCreditListScreen extends ModernBaseScreen {

    private final boolean debts;
    private TextFieldWidget searchField;
    private int statusIndex;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private int listY;
    private int listHeight;
    private List<CreditEntry> renderedEntries = List.of();
    private int renderedStart;
    private final Map<String, ItemStack> playerHeads = new ConcurrentHashMap<>();
    private String filteredCacheKey = "";
    private List<CreditEntry> filteredCache = List.of();

    private static final String[] FILTERS = {"Aktiv", "Offen", "Teilweise"};

    public ModernCreditListScreen(CreditManager manager, boolean debts, Screen parent) {
        super(manager, parent, debts ? "Schulden" : "Forderungen", debts ? "debts" : "claims");
        this.debts = debts;
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        searchField = ModernUi.configureGuiTextField(
                new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 34, Text.empty()));
        searchField.setMaxLength(64);
        ModernUi.setGuiPlaceholder(searchField, "Spieler oder Deal suchen...");
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
                FILTERS[statusIndex], ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, contentX, toolbarY + 39, filterWidth, 24));
        ModernUi.button(context, textRenderer, newButtonX, toolbarY + 39, newButtonWidth, 24, "+ Neu",
                debts ? ModernUi.theme().buttonDanger : ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, newButtonX, toolbarY + 39, newButtonWidth, 24));

        List<CreditEntry> entries = filteredEntries();
        listY = toolbarY + 70;
        listHeight = Math.max(48, contentHeight - 78);
        int rowHeight = 48;
        int visibleRows = Math.max(1, listHeight / rowHeight);
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight);
        scroll.tick(mouseX, mouseY);
        int pixelOffset = scroll.offset();
        renderedStart = Math.max(0, pixelOffset / rowHeight);
        int end = Math.min(entries.size(), renderedStart + visibleRows + 2);
        renderedEntries = entries.subList(renderedStart, end);

        if (renderedEntries.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(74, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, "Keine passenden Deals gefunden.", contentX + contentWidth / 2,
                    listY + 24, ModernUi.theme().muted);
            ModernUi.drawCentered(context, textRenderer, "Lege über '+ Neu' einen Deal an.", contentX + contentWidth / 2,
                    listY + 40, ModernUi.theme().muted);
        } else {
            context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
            for (int i = 0; i < renderedEntries.size(); i++) {
                drawEntry(context, mouseX, mouseY, renderedEntries.get(i), contentX,
                        listY + (renderedStart + i) * rowHeight - pixelOffset, contentWidth - (scroll.isScrollable() ? 8 : 0));
            }
            context.disableScissor();
            scroll.renderScrollbar(context, mouseX, mouseY);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawEntry(DrawContext context, int mouseX, int mouseY, CreditEntry entry, int x, int y, int width) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, y, width, 43);
        ModernUi.card(context, x, y, width, 43, hovered);
        int accent = statusColor(entry.getStatus());
        String otherPlayer = debts ? entry.getCreditor() : entry.getDebtor();
        context.drawItem(headFor(otherPlayer), x + 8, y + 13);
        String players = debts
                ? "Von dir an " + safe(entry.getCreditor())
                : "Von " + safe(entry.getDebtor()) + " an dich";
        ModernUi.drawTruncated(context, textRenderer, players, x + 30, y + 7, Math.max(40, width - 170), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 30, y + 22,
                Math.max(40, width - 170), ModernUi.theme().muted);
        String amount = FormatUtil.formatAmount(entry.getRemainingAmount());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, amount, x + width - 12, y + 9,
                debts ? ModernUi.theme().danger : ModernUi.theme().success);
        String status = statusLabel(entry.getStatus());
        ModernUi.drawGuiTextRightAligned(context, textRenderer, status, x + width - 12, y + 24, accent);
        if (TimeUtil.isOverdue(entry.getDueDate())) {
            context.fill(x + 5, y + 5, x + 8, y + 8, ModernUi.theme().danger);
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
        String query = searchField == null ? "" : searchField.getText().trim();
        String key = debts + "|" + player + '|' + statusIndex + '|' + query + '|' + manager.getRevision();
        if (key.equals(filteredCacheKey)) return filteredCache;
        List<CreditEntry> source = debts ? manager.getAllCreditsAsDebtor(player) : manager.getAllCreditsAsCreditor(player);
        filteredCache = source.stream()
                .filter(entry -> statusMatches(entry.getStatus()))
                .filter(entry -> query.isEmpty()
                        || score(entry, query) > 0)
                .sorted(Comparator.<CreditEntry>comparingInt(entry -> score(entry, query)).reversed()
                        .thenComparing(Comparator.comparingLong(CreditEntry::getCreatedAt).reversed()))
                .toList();
        filteredCacheKey = key;
        return filteredCache;
    }

    private boolean statusMatches(String status) {
        return switch (statusIndex) {
            case 1 -> CreditManager.STATUS_OPEN.equals(status);
            case 2 -> CreditManager.STATUS_PARTIAL.equals(status);
            default -> CreditManager.STATUS_OPEN.equals(status) || CreditManager.STATUS_PARTIAL.equals(status);
        };
    }

    private int score(CreditEntry entry, String query) {
        if (query == null || query.isBlank()) return 1;
        return Math.max(FuzzySearch.score(entry.getDealName(), query), Math.max(
                FuzzySearch.score(entry.getCreditor(), query), FuzzySearch.score(entry.getDebtor(), query)));
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
                scroll.scrollToStart();
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), newButtonX, toolbarY + 39, newButtonWidth, 24)) {
                open(new ModernCreateCreditScreen(manager, debts, this));
                return true;
            }
            if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
            if (ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
                int index = (int) ((click.y() - listY + scroll.offset()) / 48);
                if (index >= renderedStart && index < renderedStart + renderedEntries.size()) {
                    open(new ModernCreditDetailScreen(manager, renderedEntries.get(index - renderedStart), debts, this));
                    return true;
                }
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

    private int statusColor(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> ModernUi.theme().success;
            case CreditManager.STATUS_PARTIAL -> ModernUi.theme().warning;
            case CreditManager.STATUS_CLOSED -> ModernUi.theme().muted;
            case CreditManager.STATUS_CANCELLED -> ModernUi.theme().muted;
            default -> ModernUi.theme().danger;
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case CreditManager.STATUS_PAID -> "Bezahlt";
            case CreditManager.STATUS_PARTIAL -> "Teilweise";
            case CreditManager.STATUS_CLOSED -> "Abgeschlossen";
            case CreditManager.STATUS_CANCELLED -> "Storniert";
            default -> "Offen";
        };
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "Unbekannt" : value;
    }

    @Override
    protected void clearTransientState() {
        scroll.reset();
        if (searchField != null) searchField.setText("");
        filteredCacheKey = "";
        filteredCache = List.of();
        super.clearTransientState();
    }
}

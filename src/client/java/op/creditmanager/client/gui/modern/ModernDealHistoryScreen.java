package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.query.ModernQueryDebouncer;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ModernDealHistoryScreen extends ModernBaseScreen {
    private static final int PAGE_SIZE = DatabaseManager.PAGE_SIZE;
    private static final String[] SORT_LABELS = {"Neueste", "Älteste", "Betrag ↓", "Betrag ↑", "Spieler A-Z", "Status"};
    private static final DatabaseManager.DealHistorySort[] SORTS = DatabaseManager.DealHistorySort.values();
    private final ModernScrollArea scroll = new ModernScrollArea();
    private final ModernQueryDebouncer debouncer = new ModernQueryDebouncer(300L);
    private TextFieldWidget searchField;
    private DatabaseManager.QueryPage<CreditEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
    private List<CreditEntry> rendered = List.of();
    private int pageOffset, listY, listHeight, renderedStart, previousX, nextX, pageControlsY, pageButtonWidth;
    private int archiveToggleX, archiveToggleY, archiveToggleWidth, sortX, sortY, sortWidth;
    private boolean showArchived, forceSearch, disposed;
    private int sortIndex;
    private String rawSearchKey = "", requestedKey = "", appliedKey = "", queryError;
    private PendingQuery pending;
    private long requestSequence;

    public ModernDealHistoryScreen(CreditManager manager, Screen parent) { super(manager, parent, "Deal-History", "history"); }

    @Override protected void init() {
        super.init(); disposed = false; clearChildren();
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 28, Text.empty()));
        searchField.setMaxLength(128); ModernUi.setGuiPlaceholder(searchField, "Name, Spieler, Betrag, Status, Datum, ID oder Notiz..."); addDrawableChild(searchField);
        forceSearch = true;
    }

    @Override public void tick() {
        super.tick();
        if (disposed) return;
        String searchKey = currentPlayerName() + '|' + (searchField == null ? "" : searchField.getText().trim()) + '|' + manager.getRevision();
        if (!searchKey.equals(rawSearchKey)) {
            rawSearchKey = searchKey;
            pageOffset = 0;
            scroll.reset();
            debouncer.update(searchKey, System.currentTimeMillis());
            requestedKey = "";
        }
        if (pending != null && pending.future().isDone()) {
            applyFinished(pending);
            pending = null;
        }
        if (pending == null && (forceSearch || debouncer.ready(System.currentTimeMillis()))) {
            forceSearch = false;
            schedule(viewKey());
        }
    }

    private void schedule(String key) {
        long sequence = ++requestSequence;
        requestedKey = key;
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim();
        int offset = pageOffset;
        boolean archives = showArchived;
        DatabaseManager.DealHistorySort sort = SORTS[sortIndex];
        pending = new PendingQuery(sequence, key, CompletableFuture.supplyAsync(
                () -> DatabaseManager.getInstance().queryDealHistoryPage(player, query, archives, sort, PAGE_SIZE, offset), ModernQueryExecutor.get()));
        queryError = null;
    }

    private void applyFinished(PendingQuery result) {
        if (disposed || result.sequence() != requestSequence || !result.key().equals(viewKey())) return;
        try { page = result.future().join(); appliedKey = result.key(); scroll.reset(); }
        catch (RuntimeException error) { queryError = "Deal-History konnte nicht geladen werden."; CreditManagerClient.LOGGER.error("Deal-history background query failed", error); toastError(queryError); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        archiveToggleWidth = Math.max(105, Math.min(148, contentWidth / 2));
        sortWidth = Math.max(82, contentWidth - archiveToggleWidth - 8);
        archiveToggleX = contentX; archiveToggleY = contentY + 37;
        sortX = archiveToggleX + archiveToggleWidth + 8; sortY = archiveToggleY;
        ModernUi.button(context, textRenderer, archiveToggleX, archiveToggleY, archiveToggleWidth, 23,
                showArchived ? "Archivierte: an" : "Archivierte anzeigen", showArchived ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, archiveToggleX, archiveToggleY, archiveToggleWidth, 23));
        ModernUi.button(context, textRenderer, sortX, sortY, sortWidth, 23, "Sortierung: " + SORT_LABELS[sortIndex], ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, sortX, sortY, sortWidth, 23));
        String label = pending == null ? "History · Seite " + page.pageNumber() + "/" + page.pageCount() + " · " + page.totalCount() + " Ergebnis" + (page.totalCount() == 1 ? "" : "se") : "Deal-History wird geladen…";
        ModernUi.drawGuiText(context, textRenderer, label, contentX, contentY + 68, pending == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        listY = contentY + 83; listHeight = Math.max(45, contentHeight - 124);
        drawRows(context, mouseX, mouseY); drawPaging(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRows(DrawContext context, int mouseX, int mouseY) {
        List<CreditEntry> entries = page.entries(); int rowHeight = 46;
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight); scroll.tick(mouseX, mouseY);
        int offset = scroll.offset(); renderedStart = Math.max(0, offset / rowHeight); int end = Math.min(entries.size(), renderedStart + listHeight / rowHeight + 3); rendered = entries.subList(renderedStart, end);
        if (rendered.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(60, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, queryError != null ? queryError : pending == null ? "Keine History-Deals für diesen Filter gefunden." : "Deal-History wird geladen…", contentX + contentWidth / 2, listY + 24, queryError == null ? ModernUi.theme().muted : ModernUi.theme().danger);
            return;
        }
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int index = 0; index < rendered.size(); index++) drawEntry(context, mouseX, mouseY, rendered.get(index), contentX, listY + (renderedStart + index) * rowHeight - offset, contentWidth - (scroll.isScrollable() ? 8 : 0));
        context.disableScissor(); scroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void drawPaging(DrawContext context, int mouseX, int mouseY) {
        pageControlsY = listY + listHeight + 7; pageButtonWidth = Math.max(42, (contentWidth - 8) / 2); previousX = contentX; nextX = previousX + pageButtonWidth + 8;
        boolean previous = pageOffset > 0 && pending == null, next = page.hasNext() && pending == null;
        ModernUi.button(context, textRenderer, previousX, pageControlsY, pageButtonWidth, 23, "← Vorherige Seite", ModernUi.theme().buttonNeutral, previous && ModernUi.contains(mouseX, mouseY, previousX, pageControlsY, pageButtonWidth, 23));
        ModernUi.button(context, textRenderer, nextX, pageControlsY, pageButtonWidth, 23, "Nächste Seite →", next ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral, next && ModernUi.contains(mouseX, mouseY, nextX, pageControlsY, pageButtonWidth, 23));
    }

    private void drawEntry(DrawContext context, int mouseX, int mouseY, CreditEntry entry, int x, int y, int width) {
        ModernUi.card(context, x, y, width, 41, ModernUi.contains(mouseX, mouseY, x, y, width, 41));
        int color = entry.isArchived() ? ModernUi.theme().muted : CreditManager.STATUS_PAID.equals(entry.getStatus()) ? ModernUi.theme().success : ModernUi.theme().warning;
        ModernUi.drawTruncated(context, textRenderer, entry.getDebtor() + " → " + entry.getCreditor(), x + 9, y + 8, Math.max(40, width - 150), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 9, y + 23, Math.max(40, width - 150), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, FormatUtil.formatAmount(entry.getAmount()), x + width - 10, y + 8, color);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, entry.isArchived() ? "Archiviert" : statusLabel(entry.getStatus()), x + width - 10, y + 23, color);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), archiveToggleX, archiveToggleY, archiveToggleWidth, 23)) { showArchived = !showArchived; pageOffset = 0; forceImmediate(); return true; }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), sortX, sortY, sortWidth, 23)) { sortIndex = (sortIndex + 1) % SORTS.length; pageOffset = 0; forceImmediate(); return true; }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), previousX, pageControlsY, pageButtonWidth, 23) && pending == null && pageOffset > 0) { pageOffset = Math.max(0, pageOffset - PAGE_SIZE); forceImmediate(); return true; }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), nextX, pageControlsY, pageButtonWidth, 23) && pending == null && page.hasNext()) { pageOffset += PAGE_SIZE; forceImmediate(); return true; }
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 46);
            if (index >= renderedStart && index < renderedStart + rendered.size()) { CreditEntry entry = rendered.get(index - renderedStart); open(new ModernCreditDetailScreen(manager, entry, currentPlayerName().equalsIgnoreCase(entry.getDebtor()), this)); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    private void forceImmediate() { debouncer.commitImmediately(rawSearchKey); forceSearch = true; requestedKey = ""; scroll.reset(); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { if (scroll.contains(mouseX, mouseY)) { scroll.scroll(verticalAmount); return true; } return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount); }
    private String viewKey() { return rawSearchKey + '|' + showArchived + '|' + sortIndex + '|' + pageOffset; }
    private String statusLabel(String status) { return switch (status) { case CreditManager.STATUS_PAID -> "Bezahlt"; case CreditManager.STATUS_CLOSED -> "Abgeschlossen"; case CreditManager.STATUS_CANCELLED -> "Storniert"; default -> "Teilweise bezahlt"; }; }
    @Override protected void clearTransientState() { disposed = true; requestSequence++; scroll.reset(); page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE); rawSearchKey = ""; requestedKey = ""; appliedKey = ""; pending = null; queryError = null; pageOffset = 0; super.clearTransientState(); }
    private record PendingQuery(long sequence, String key, CompletableFuture<DatabaseManager.QueryPage<CreditEntry>> future) { }
}

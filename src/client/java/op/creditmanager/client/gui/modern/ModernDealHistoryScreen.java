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
import op.creditmanager.client.gui.modern.query.LatestQueryController;
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
    private final LatestQueryController<String, DatabaseManager.QueryPage<CreditEntry>> queries = new LatestQueryController<>();
    private TextFieldWidget searchField;
    private DatabaseManager.QueryPage<CreditEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
    private List<CreditEntry> rendered = List.of();
    private int pageOffset, listY, listHeight, renderedStart, previousX, nextX, pageControlsY, pageButtonWidth;
    private int archiveToggleX, archiveToggleY, archiveToggleWidth, sortX, sortY, sortWidth;
    private List<ModernLayout.Bounds> toolbarButtons = List.of();
    private List<ModernLayout.Bounds> pagingButtons = List.of();
    private List<ModernLayout.Bounds> errorButtons = List.of();
    private boolean showArchived, forceSearch;
    private int sortIndex;
    private String rawSearchKey = "", requestedKey = "", appliedKey = "", queryError;
    private LatestQueryController.Ticket<String, DatabaseManager.QueryPage<CreditEntry>> pending;
    private int errorCardY;

    public ModernDealHistoryScreen(CreditManager manager, Screen parent) { super(manager, parent, "Deal-History", "history"); }

    @Override protected void init() {
        super.init(); queries.reopen(); clearChildren();
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 28, Text.empty()));
        searchField.setMaxLength(128); ModernUi.setGuiPlaceholder(searchField, "Name, Spieler, Betrag, Status, Datum, ID oder Notiz..."); addDrawableChild(searchField);
        forceSearch = true;
    }

    @Override public void tick() {
        super.tick();
        if (!DatabaseManager.getInstance().isHealthy()) {
            queryError = "Die History kann erst nach der Datenbankprüfung geladen werden.";
            forceSearch = false;
            return;
        }
        String searchKey = currentPlayerName() + '|' + (searchField == null ? "" : searchField.getText().trim()) + '|' + manager.getRevision();
        if (!searchKey.equals(rawSearchKey)) {
            queries.invalidate();
            pending = null;
            rawSearchKey = searchKey;
            pageOffset = 0;
            scroll.reset();
            debouncer.update(searchKey, System.currentTimeMillis());
            requestedKey = "";
            queryError = null;
        }
        if (pending != null && pending.future().isDone()) {
            applyFinished(pending);
            pending = null;
        }
        if (pending == null && queryError == null && (forceSearch || debouncer.ready(System.currentTimeMillis()))) {
            forceSearch = false;
            schedule(viewKey());
        }
    }

    private void schedule(String key) {
        requestedKey = key;
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim();
        int offset = pageOffset;
        boolean archives = showArchived;
        DatabaseManager.DealHistorySort sort = SORTS[sortIndex];
        pending = queries.replace(key, CompletableFuture.supplyAsync(
                () -> DatabaseManager.getInstance().queryDealHistoryPage(player, query, archives, sort, PAGE_SIZE, offset), ModernQueryExecutor.get()));
    }

    private void applyFinished(LatestQueryController.Ticket<String, DatabaseManager.QueryPage<CreditEntry>> result) {
        if (!queries.isCurrent(result, viewKey())) return;
        try { page = result.future().join(); appliedKey = result.key(); scroll.reset(); }
        catch (RuntimeException error) { queryError = "Deal-History konnte nicht geladen werden."; CreditManagerClient.LOGGER.error("Deal-history background query failed", error); toastError(queryError); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernLayout.positionTextField(searchField, contentX + 4, contentY + 4, Math.max(1, contentWidth - 8), contentY, contentHeight, true);
        boolean compactToolbar = contentWidth < 220;
        toolbarButtons = ModernLayout.buttonRow(contentX, contentY + 37, contentWidth, 2, 92, 23, 8);
        ModernLayout.Bounds archiveBounds = toolbarButtons.getFirst();
        ModernLayout.Bounds sortBounds = toolbarButtons.get(1);
        archiveToggleX = archiveBounds.x(); archiveToggleY = archiveBounds.y(); archiveToggleWidth = archiveBounds.width();
        sortX = sortBounds.x(); sortY = sortBounds.y(); sortWidth = sortBounds.width();
        ModernUi.button(context, textRenderer, archiveBounds.x(), archiveBounds.y(), archiveBounds.width(), archiveBounds.height(),
                showArchived ? "Archivierte: an" : "Archivierte anzeigen", showArchived ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, archiveBounds.x(), archiveBounds.y(), archiveBounds.width(), archiveBounds.height()));
        ModernUi.button(context, textRenderer, sortBounds.x(), sortBounds.y(), sortBounds.width(), sortBounds.height(), "Sort: " + SORT_LABELS[sortIndex], ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, sortBounds.x(), sortBounds.y(), sortBounds.width(), sortBounds.height()));
        int toolbarHeight = compactToolbar ? ModernLayout.rowHeight(toolbarButtons, 0) : 23;
        String label = pending == null ? "History · Seite " + page.pageNumber() + "/" + page.pageCount() + " · " + page.totalCount() + " Ergebnis" + (page.totalCount() == 1 ? "" : "se") : "Deal-History wird geladen…";
        ModernUi.drawTruncated(context, textRenderer, label, contentX, contentY + 42 + toolbarHeight, contentWidth, pending == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        int baseListY = contentY + 56 + toolbarHeight;
        errorCardY = baseListY;
        int errorCardHeight = queryError == null ? 0 : errorCardHeight();
        listY = baseListY + errorCardHeight;
        int pagingHeight = ModernLayout.stack(contentWidth, 2, 88, 8) ? 54 : 23;
        listHeight = Math.max(45, contentY + contentHeight - listY - pagingHeight - 10);
        if (queryError != null) drawQueryErrorCard(context, mouseX, mouseY);
        drawRows(context, mouseX, mouseY); drawPaging(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRows(DrawContext context, int mouseX, int mouseY) {
        List<CreditEntry> entries = page.entries(); int rowHeight = 46;
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight); scroll.tick(mouseX, mouseY);
        int offset = scroll.offset(); renderedStart = Math.max(0, offset / rowHeight); int end = Math.min(entries.size(), renderedStart + listHeight / rowHeight + 3); rendered = entries.subList(renderedStart, end);
        if (rendered.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(60, listHeight), false);
            String message = queryError != null ? "Ergebnisanzeige wartet auf die Datenbankprüfung." : pending == null ? "Keine History-Deals für diesen Filter gefunden." : "Deal-History wird geladen…";
            ModernUi.drawCentered(context, textRenderer, message, contentX + contentWidth / 2, listY + 24, queryError == null ? ModernUi.theme().muted : ModernUi.theme().warning);
            return;
        }
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int index = 0; index < rendered.size(); index++) drawEntry(context, mouseX, mouseY, rendered.get(index), contentX, listY + (renderedStart + index) * rowHeight - offset, contentWidth - (scroll.isScrollable() ? 8 : 0));
        context.disableScissor(); scroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void drawPaging(DrawContext context, int mouseX, int mouseY) {
        pageControlsY = listY + listHeight + 7;
        pagingButtons = ModernLayout.buttonRow(contentX, pageControlsY, contentWidth, 2, 88, 23, 8);
        ModernLayout.Bounds previousBounds = pagingButtons.getFirst();
        ModernLayout.Bounds nextBounds = pagingButtons.get(1);
        previousX = previousBounds.x(); nextX = nextBounds.x(); pageButtonWidth = previousBounds.width();
        boolean previous = pageOffset > 0 && pending == null, next = page.hasNext() && pending == null;
        ModernUi.button(context, textRenderer, previousBounds.x(), previousBounds.y(), previousBounds.width(), previousBounds.height(), "← Vorherige Seite", ModernUi.theme().buttonNeutral,
                previous && ModernUi.contains(mouseX, mouseY, previousBounds.x(), previousBounds.y(), previousBounds.width(), previousBounds.height()));
        ModernUi.button(context, textRenderer, nextBounds.x(), nextBounds.y(), nextBounds.width(), nextBounds.height(), "Nächste Seite →", next ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral,
                next && ModernUi.contains(mouseX, mouseY, nextBounds.x(), nextBounds.y(), nextBounds.width(), nextBounds.height()));
    }

    private int errorCardHeight() { return ModernLayout.stack(Math.max(1, contentWidth - 12), 2, 74, 8) ? 76 : 48; }

    private void drawQueryErrorCard(DrawContext context, int mouseX, int mouseY) {
        int padding = contentWidth > 16 ? 6 : 0;
        int width = Math.max(1, contentWidth - padding * 2);
        ModernUi.card(context, contentX, errorCardY, contentWidth, errorCardHeight(), false);
        ModernUi.drawTruncated(context, textRenderer, "Datenbank-Schema/Reparatur erforderlich", contentX + padding, errorCardY + 5, width, ModernUi.theme().danger);
        errorButtons = ModernLayout.buttonRow(contentX + padding, errorCardY + 21, width, 2, 74, 23, 8);
        ModernLayout.Bounds recovery = errorButtons.getFirst();
        ModernLayout.Bounds retry = errorButtons.get(1);
        ModernUi.button(context, textRenderer, recovery.x(), recovery.y(), recovery.width(), recovery.height(), "Recovery öffnen", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, recovery.x(), recovery.y(), recovery.width(), recovery.height()));
        ModernUi.button(context, textRenderer, retry.x(), retry.y(), retry.width(), retry.height(), "Neu laden", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, retry.x(), retry.y(), retry.width(), retry.height()));
    }

    private void drawEntry(DrawContext context, int mouseX, int mouseY, CreditEntry entry, int x, int y, int width) {
        ModernUi.card(context, x, y, width, 41, ModernUi.contains(mouseX, mouseY, x, y, width, 41));
        int color = entry.isArchived() ? ModernUi.theme().muted : CreditManager.STATUS_PAID.equals(entry.getStatus()) ? ModernUi.theme().success : ModernUi.theme().warning;
        ModernUi.drawTruncated(context, textRenderer, entry.getDebtor() + " → " + entry.getCreditor(), x + 9, y + 8, Math.max(1, width - 150), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 9, y + 23, Math.max(1, width - 150), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, FormatUtil.formatAmountMinor(entry.getAmountMinor()), x + width - 10, y + 8, color);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, entry.isArchived() ? "Archiviert" : statusLabel(entry.getStatus()), x + width - 10, y + 23, color);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.getFirst().x(), errorButtons.getFirst().y(), errorButtons.getFirst().width(), errorButtons.getFirst().height())) { open(new ModernRecoveryScreen(manager)); return true; }
        if (click.button() == 0 && queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.get(1).x(), errorButtons.get(1).y(), errorButtons.get(1).width(), errorButtons.get(1).height())) { if (!manager.recheckAndRepairDatabase()) { queryError = "Datenbankprüfung konnte nicht abgeschlossen werden."; return true; } queryError = null; forceSearch = true; debouncer.commitImmediately(rawSearchKey); return true; }
        if (click.button() == 0 && !toolbarButtons.isEmpty() && ModernUi.contains(click.x(), click.y(), toolbarButtons.getFirst().x(), toolbarButtons.getFirst().y(), toolbarButtons.getFirst().width(), toolbarButtons.getFirst().height())) { showArchived = !showArchived; pageOffset = 0; forceImmediate(); return true; }
        if (click.button() == 0 && toolbarButtons.size() > 1 && ModernUi.contains(click.x(), click.y(), toolbarButtons.get(1).x(), toolbarButtons.get(1).y(), toolbarButtons.get(1).width(), toolbarButtons.get(1).height())) { sortIndex = (sortIndex + 1) % SORTS.length; pageOffset = 0; forceImmediate(); return true; }
        if (click.button() == 0 && !pagingButtons.isEmpty() && ModernUi.contains(click.x(), click.y(), pagingButtons.getFirst().x(), pagingButtons.getFirst().y(), pagingButtons.getFirst().width(), pagingButtons.getFirst().height()) && pending == null && pageOffset > 0) { pageOffset = Math.max(0, pageOffset - PAGE_SIZE); forceImmediate(); return true; }
        if (click.button() == 0 && pagingButtons.size() > 1 && ModernUi.contains(click.x(), click.y(), pagingButtons.get(1).x(), pagingButtons.get(1).y(), pagingButtons.get(1).width(), pagingButtons.get(1).height()) && pending == null && page.hasNext()) { pageOffset += PAGE_SIZE; forceImmediate(); return true; }
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 46);
            if (index >= renderedStart && index < renderedStart + rendered.size()) { CreditEntry entry = rendered.get(index - renderedStart); open(new ModernCreditDetailScreen(manager, entry, currentPlayerName().equalsIgnoreCase(entry.getDebtor()), this)); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    private void forceImmediate() { debouncer.commitImmediately(rawSearchKey); forceSearch = true; requestedKey = ""; queryError = null; scroll.reset(); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { if (scroll.contains(mouseX, mouseY)) { scroll.scroll(verticalAmount); return true; } return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount); }
    private String viewKey() { return rawSearchKey + '|' + showArchived + '|' + sortIndex + '|' + pageOffset; }
    private String statusLabel(String status) { return switch (status) { case CreditManager.STATUS_PAID -> "Bezahlt"; case CreditManager.STATUS_CLOSED -> "Abgeschlossen"; case CreditManager.STATUS_CANCELLED -> "Storniert"; default -> "Teilweise bezahlt"; }; }
    @Override protected void clearTransientState() { queries.close(); scroll.reset(); page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE); rawSearchKey = ""; requestedKey = ""; appliedKey = ""; pending = null; queryError = null; errorButtons = List.of(); pageOffset = 0; super.clearTransientState(); }
}

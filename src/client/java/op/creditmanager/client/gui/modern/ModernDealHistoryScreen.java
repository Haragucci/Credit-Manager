package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.FormatUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Async, database-paged view of final deals. No database work is performed by render(). */
public final class ModernDealHistoryScreen extends ModernBaseScreen {
    private static final int PAGE_SIZE = DatabaseManager.PAGE_SIZE;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private TextFieldWidget searchField;
    private DatabaseManager.QueryPage<CreditEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
    private List<CreditEntry> rendered = List.of();
    private int pageOffset, listY, listHeight, renderedStart, previousX, nextX, pageControlsY, pageButtonWidth;
    private String requestedFilterKey = "", appliedKey = "", queryError;
    private PendingQuery pending;
    private long requestSequence;
    private boolean disposed;

    public ModernDealHistoryScreen(CreditManager manager, Screen parent) { super(manager, parent, "Deal-History", "history"); }

    @Override protected void init() {
        super.init(); disposed = false; clearChildren();
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 12, Math.max(60, contentWidth - 16), 20, Text.empty()));
        searchField.setMaxLength(128); ModernUi.setGuiPlaceholder(searchField, "Name, Spieler, Betrag, Status, Datum, ID oder Notiz..."); addDrawableChild(searchField);
    }

    @Override public void tick() {
        super.tick();
        if (disposed) return;
        String filter = filterKey();
        if (!filter.equals(requestedFilterKey)) { requestedFilterKey = filter; pageOffset = 0; scroll.reset(); schedule(filter); }
        else if (pending != null && pending.future().isDone()) { applyFinished(pending); pending = null; }
        else if (pending == null && !pageKey(filter).equals(appliedKey)) schedule(filter);
    }

    private void schedule(String filter) {
        long sequence = ++requestSequence;
        String key = pageKey(filter);
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim();
        int offset = pageOffset;
        pending = new PendingQuery(sequence, key, CompletableFuture.supplyAsync(() -> DatabaseManager.getInstance().queryDealHistoryPage(player, query, PAGE_SIZE, offset)));
        queryError = null;
    }

    private void applyFinished(PendingQuery result) {
        if (disposed || result.sequence() != requestSequence || !result.key().equals(pageKey(filterKey()))) return;
        try { page = result.future().join(); appliedKey = result.key(); scroll.reset(); }
        catch (RuntimeException error) { queryError = "Deal-History konnte nicht geladen werden."; CreditManagerClient.LOGGER.error("Deal-history background query failed", error); toastError(queryError); }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernUi.card(context, contentX, contentY + 4, contentWidth, 34, ModernUi.contains(mouseX, mouseY, contentX, contentY + 4, contentWidth, 34));
        String label = pending == null ? "Abgeschlossen · Seite " + page.pageNumber() + "/" + page.pageCount() + " · " + page.totalCount() + " Ergebnis" + (page.totalCount() == 1 ? "" : "se") : "Deal-History wird geladen…";
        ModernUi.drawGuiText(context, textRenderer, label, contentX, contentY + 45, pending == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        listY = contentY + 60; listHeight = Math.max(45, contentHeight - 101);
        drawRows(context, mouseX, mouseY); drawPaging(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRows(DrawContext context, int mouseX, int mouseY) {
        List<CreditEntry> entries = page.entries(); int rowHeight = 46;
        scroll.setBounds(contentX, listY, contentWidth, listHeight, entries.size() * rowHeight); scroll.tick(mouseX, mouseY);
        int offset = scroll.offset(); renderedStart = Math.max(0, offset / rowHeight); int end = Math.min(entries.size(), renderedStart + listHeight / rowHeight + 3); rendered = entries.subList(renderedStart, end);
        if (rendered.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, Math.min(60, listHeight), false);
            ModernUi.drawCentered(context, textRenderer, queryError != null ? queryError : pending == null ? "Keine abgeschlossenen Deals gefunden." : "Deal-History wird geladen…", contentX + contentWidth / 2, listY + 24, queryError == null ? ModernUi.theme().muted : ModernUi.theme().danger);
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
        boolean paid = CreditManager.STATUS_PAID.equals(entry.getStatus());
        int color = paid ? ModernUi.theme().success : ModernUi.theme().muted;
        ModernUi.drawTruncated(context, textRenderer, entry.getDebtor() + " → " + entry.getCreditor(), x + 9, y + 8, Math.max(40, width - 150), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, entry.getDealName(), x + 9, y + 23, Math.max(40, width - 150), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, FormatUtil.formatAmount(entry.getAmount()), x + width - 10, y + 8, color);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, paid ? "Bezahlt" : entry.isArchived() ? "Archiviert" : "Storniert", x + width - 10, y + 23, color);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), previousX, pageControlsY, pageButtonWidth, 23) && pending == null && pageOffset > 0) { pageOffset = Math.max(0, pageOffset - PAGE_SIZE); schedule(filterKey()); return true; }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), nextX, pageControlsY, pageButtonWidth, 23) && pending == null && page.hasNext()) { pageOffset += PAGE_SIZE; schedule(filterKey()); return true; }
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 46);
            if (index >= renderedStart && index < renderedStart + rendered.size()) { CreditEntry entry = rendered.get(index - renderedStart); open(new ModernCreditDetailScreen(manager, entry, currentPlayerName().equalsIgnoreCase(entry.getDebtor()), this)); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { if (scroll.contains(mouseX, mouseY)) { scroll.scroll(verticalAmount); return true; } return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount); }
    private String filterKey() { return currentPlayerName() + '|' + (searchField == null ? "" : searchField.getText().trim()) + '|' + manager.getRevision(); }
    private String pageKey(String filter) { return filter + '|' + pageOffset; }
    @Override protected void clearTransientState() { disposed = true; requestSequence++; scroll.reset(); page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE); requestedFilterKey = ""; appliedKey = ""; pending = null; queryError = null; pageOffset = 0; super.clearTransientState(); }
    private record PendingQuery(long sequence, String key, CompletableFuture<DatabaseManager.QueryPage<CreditEntry>> future) { }
}

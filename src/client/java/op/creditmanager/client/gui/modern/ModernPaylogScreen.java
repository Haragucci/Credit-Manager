package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Database-paged paylog view. Query futures are never allowed to mutate this screen off-thread. */
public final class ModernPaylogScreen extends ModernBaseScreen {
    private static final int PAGE_SIZE = DatabaseManager.PAGE_SIZE;
    private static final String[] DIRECTION_FILTERS = {"Alle", "Eingehend", "Ausgehend"};

    private final ModernScrollArea scroll = new ModernScrollArea();
    private TextFieldWidget searchField;
    private int directionIndex;
    private int pageOffset;
    private int listY;
    private int listHeight;
    private int previousX, nextX, pageControlsY, pageButtonWidth;
    private int manualButtonX, manualButtonY, manualButtonWidth;
    private List<TransactionEntry> renderedEntries = List.of();
    private int renderedStart;
    private DatabaseManager.QueryPage<TransactionEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
    private String appliedKey = "";
    private String requestedFilterKey = "";
    private PendingQuery pending;
    private long requestSequence;
    private boolean disposed;
    private String queryError;

    public ModernPaylogScreen(CreditManager manager, Screen parent) { super(manager, parent, "Paylogs", "paylogs"); }

    @Override protected void init() {
        super.init();
        disposed = false;
        clearChildren();
        manualButtonWidth = 82;
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4,
                Math.max(60, contentWidth - manualButtonWidth - 22), 34, Text.empty()));
        searchField.setMaxLength(96);
        ModernUi.setGuiPlaceholder(searchField, "Spieler, Betrag, Datum oder Freitext...");
        addDrawableChild(searchField);
    }

    @Override public void tick() {
        super.tick();
        if (disposed) return;
        String filterKey = filterKey();
        if (!filterKey.equals(requestedFilterKey)) {
            requestedFilterKey = filterKey;
            pageOffset = 0;
            scroll.reset();
            schedule(filterKey);
        } else if (pending != null && pending.future().isDone()) {
            applyFinishedQuery(pending);
            pending = null;
        } else if (!pageKey(filterKey).equals(appliedKey) && pending == null) {
            schedule(filterKey);
        }
    }

    private void schedule(String filterKey) {
        String key = pageKey(filterKey);
        long sequence = ++requestSequence;
        String player = currentPlayerName();
        String query = searchField == null ? "" : searchField.getText().trim();
        int direction = directionIndex;
        int offset = pageOffset;
        pending = new PendingQuery(sequence, key, CompletableFuture.supplyAsync(() -> TransactionRepository.getInstance().queryPage(player, direction, query, PAGE_SIZE, offset)));
        queryError = null;
    }

    private void applyFinishedQuery(PendingQuery result) {
        if (disposed || result.sequence() != requestSequence || !result.key().equals(pageKey(filterKey()))) return;
        try {
            page = result.future().join();
            appliedKey = result.key();
            scroll.reset();
        } catch (RuntimeException error) {
            queryError = "Paylogs konnten nicht geladen werden.";
            CreditManagerClient.LOGGER.error("Paylog background query failed", error);
            toastError(queryError);
        }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        int toolbarY = contentY + 4;
        int filterWidth = Math.max(72, Math.min(104, contentWidth));
        ModernUi.card(context, contentX, toolbarY, contentWidth, 34, ModernUi.contains(mouseX, mouseY, contentX, toolbarY, contentWidth, 34));
        manualButtonX = contentX + contentWidth - manualButtonWidth - 4;
        manualButtonY = toolbarY + 5;
        ModernUi.button(context, textRenderer, manualButtonX, manualButtonY, manualButtonWidth, 24, "+ Paylog",
                ModernUi.theme().buttonPrimary, ModernUi.contains(mouseX, mouseY, manualButtonX, manualButtonY, manualButtonWidth, 24));
        ModernUi.button(context, textRenderer, contentX, toolbarY + 39, filterWidth, 24, DIRECTION_FILTERS[directionIndex], ModernUi.theme().buttonNeutral, ModernUi.contains(mouseX, mouseY, contentX, toolbarY + 39, filterWidth, 24));

        String state = pending == null ? "Seite " + page.pageNumber() + "/" + page.pageCount() + " · " + page.totalCount() + " Ergebnis" + (page.totalCount() == 1 ? "" : "se") : "Lade Seite " + (pageOffset / PAGE_SIZE + 1) + "…";
        ModernUi.drawGuiText(context, textRenderer, state, contentX + filterWidth + 8, toolbarY + 47, pending == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        listY = toolbarY + 70;
        listHeight = Math.max(45, contentHeight - 111);
        drawRows(context, mouseX, mouseY);
        drawPaging(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRows(DrawContext context, int mouseX, int mouseY) {
        List<TransactionEntry> entries = page.entries();
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
            String message = queryError != null ? queryError : pending == null ? "Keine Paylogs für diesen Filter gefunden." : "Paylogs werden geladen…";
            ModernUi.drawCentered(context, textRenderer, message, contentX + contentWidth / 2, listY + 25, queryError == null ? ModernUi.theme().muted : ModernUi.theme().danger);
            return;
        }
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int index = 0; index < renderedEntries.size(); index++) drawTransaction(context, mouseX, mouseY, renderedEntries.get(index), contentX, listY + (renderedStart + index) * rowHeight - pixelOffset, contentWidth - (scroll.isScrollable() ? 8 : 0));
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void drawPaging(DrawContext context, int mouseX, int mouseY) {
        pageControlsY = listY + listHeight + 7;
        pageButtonWidth = Math.max(42, (contentWidth - 8) / 2);
        previousX = contentX;
        nextX = previousX + pageButtonWidth + 8;
        boolean previous = pageOffset > 0 && pending == null;
        boolean next = page.hasNext() && pending == null;
        ModernUi.button(context, textRenderer, previousX, pageControlsY, pageButtonWidth, 23, "← Vorherige Seite", previous ? ModernUi.theme().buttonNeutral : ModernUi.theme().buttonNeutral, previous && ModernUi.contains(mouseX, mouseY, previousX, pageControlsY, pageButtonWidth, 23));
        ModernUi.button(context, textRenderer, nextX, pageControlsY, pageButtonWidth, 23, "Nächste Seite →", next ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral, next && ModernUi.contains(mouseX, mouseY, nextX, pageControlsY, pageButtonWidth, 23));
    }

    private void drawTransaction(DrawContext context, int mouseX, int mouseY, TransactionEntry entry, int x, int y, int width) {
        boolean outgoing = equals(entry.getFromPlayer(), currentPlayerName());
        int color = outgoing ? ModernUi.theme().danger : ModernUi.theme().success;
        ModernUi.card(context, x, y, width, 35, ModernUi.contains(mouseX, mouseY, x, y, width, 35));
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String otherPlayer = outgoing ? entry.getToPlayer() : entry.getFromPlayer();
        ModernUi.drawTruncated(context, textRenderer, (outgoing ? "An " : "Von ") + safe(otherPlayer), x + 19, y + 7, Math.max(40, width - 164), ModernUi.theme().text);
        String linkState = entry.isFullyLinked() ? "Verknüpft" : entry.getLinkedAmount() > 0
                ? "Rest: " + FormatUtil.formatAmount(entry.getRemainingAmount()) : "Klick: verknüpfen";
        ModernUi.drawTruncated(context, textRenderer, TimeUtil.formatDateTime(entry.getTimestamp()) + " · " + linkState,
                x + 19, y + 20, Math.max(40, width - 164), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, (outgoing ? "-" : "+") + FormatUtil.formatAmount(entry.getAmount()), x + width - 12, y + 13, color);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        int toolbarY = contentY + 4;
        int filterWidth = Math.max(72, Math.min(104, contentWidth));
        if (ModernUi.contains(click.x(), click.y(), manualButtonX, manualButtonY, manualButtonWidth, 24)) {
            open(new ModernCreatePaylogScreen(manager, this));
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), contentX, toolbarY + 39, filterWidth, 24)) {
            directionIndex = (directionIndex + 1) % DIRECTION_FILTERS.length;
            requestedFilterKey = "";
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), previousX, pageControlsY, pageButtonWidth, 23) && pending == null && pageOffset > 0) {
            pageOffset = Math.max(0, pageOffset - PAGE_SIZE); schedule(filterKey()); return true;
        }
        if (ModernUi.contains(click.x(), click.y(), nextX, pageControlsY, pageButtonWidth, 23) && pending == null && page.hasNext()) {
            pageOffset += PAGE_SIZE; schedule(filterKey()); return true;
        }
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 39);
            if (index >= renderedStart && index < renderedStart + renderedEntries.size()) {
                open(new ModernPaylogLinkScreen(manager, renderedEntries.get(index - renderedStart), this));
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (ModernUi.contains(mouseX, mouseY, contentX, listY, contentWidth, listHeight) && verticalAmount != 0) { scroll.scroll(verticalAmount); return true; }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String filterKey() { return currentPlayerName() + '|' + directionIndex + '|' + (searchField == null ? "" : searchField.getText().trim()) + '|' + TransactionRepository.getInstance().getRevision(); }
    private String pageKey(String filterKey) { return filterKey + '|' + pageOffset; }
    private boolean equals(String value, String expected) { return value != null && value.equalsIgnoreCase(expected); }
    private String safe(String value) { return value == null || value.isBlank() ? "Unbekannt" : value; }

    @Override protected void clearTransientState() {
        disposed = true;
        requestSequence++;
        scroll.reset();
        if (searchField != null) searchField.setText("");
        page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
        appliedKey = ""; requestedFilterKey = ""; pending = null; queryError = null; pageOffset = 0;
        super.clearTransientState();
    }

    private record PendingQuery(long sequence, String key, CompletableFuture<DatabaseManager.QueryPage<TransactionEntry>> future) { }
}

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
import op.creditmanager.client.gui.modern.query.ModernQueryDebouncer;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    private int filterButtonX, filterButtonY, filterButtonWidth;
    private List<ModernLayout.Bounds> toolbarButtons = List.of();
    private List<ModernLayout.Bounds> pagingButtons = List.of();
    private List<ModernLayout.Bounds> errorButtons = List.of();
    private List<TransactionEntry> renderedEntries = List.of();
    private int renderedStart;
    private DatabaseManager.QueryPage<TransactionEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, PAGE_SIZE);
    private String appliedKey = "";
    private String requestedFilterKey = "";
    private final ModernQueryDebouncer debouncer = new ModernQueryDebouncer(300L);
    private boolean forceQuery;
    private PendingQuery pending;
    private long requestSequence;
    private boolean disposed;
    private String queryError;
    private int errorCardY;

    public ModernPaylogScreen(CreditManager manager, Screen parent) { super(manager, parent, "Paylogs", "paylogs"); }

    @Override protected void init() {
        super.init();
        disposed = false;
        forceQuery = true;
        clearChildren();
        manualButtonWidth = 82;
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, 0, 0, 1, 24, Text.empty()));
        searchField.setMaxLength(96);
        ModernUi.setGuiPlaceholder(searchField, "Spieler, Betrag, Datum oder Freitext...");
        addDrawableChild(searchField);
    }

    @Override public void tick() {
        super.tick();
        if (disposed) return;
        if (!DatabaseManager.getInstance().isHealthy()) {
            queryError = "Paylogs können erst nach der Datenbankprüfung geladen werden.";
            forceQuery = false;
            return;
        }
        String filterKey = filterKey();
        if (!filterKey.equals(requestedFilterKey)) {
            requestedFilterKey = filterKey;
            pageOffset = 0;
            scroll.reset();
            queryError = null;
            debouncer.update(filterKey, System.currentTimeMillis());
        } else if (pending != null && pending.future().isDone()) {
            applyFinishedQuery(pending);
            pending = null;
        } else if (pending == null && queryError == null && (forceQuery || debouncer.ready(System.currentTimeMillis()) || !pageKey(filterKey).equals(appliedKey) && !filterKey.equals(requestedFilterKey))) {
            forceQuery = false;
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
        pending = new PendingQuery(sequence, key, CompletableFuture.supplyAsync(() -> TransactionRepository.getInstance().queryPage(player, direction, query, PAGE_SIZE, offset), ModernQueryExecutor.get()));
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
        boolean compactToolbar = contentWidth < 230;
        if (!compactToolbar) manualButtonWidth = Math.min(82, Math.max(64, contentWidth / 3));
        int searchWidth = compactToolbar ? Math.max(1, contentWidth - 8) : Math.max(1, contentWidth - manualButtonWidth - 12);
        ModernLayout.positionTextField(searchField, contentX + 4, toolbarY + 4, searchWidth, contentY, contentHeight, true);
        toolbarButtons = ModernLayout.buttonRow(contentX, toolbarY + 32, compactToolbar ? contentWidth : manualButtonWidth,
                compactToolbar ? 2 : 1, 72, 24, 8);
        ModernLayout.Bounds manual = toolbarButtons.getFirst();
        manualButtonX = compactToolbar ? manual.x() : contentX + contentWidth - manualButtonWidth;
        manualButtonY = compactToolbar ? manual.y() : toolbarY + 4;
        manualButtonWidth = compactToolbar ? manual.width() : manualButtonWidth;
        filterButtonX = compactToolbar ? toolbarButtons.get(1).x() : contentX;
        filterButtonY = compactToolbar ? toolbarButtons.get(1).y() : toolbarY + 32;
        filterButtonWidth = compactToolbar ? toolbarButtons.get(1).width() : Math.max(72, Math.min(104, contentWidth));
        int toolbarHeight = compactToolbar ? ModernLayout.rowHeight(toolbarButtons, 0) + 32 : 60;
        ModernUi.card(context, contentX, toolbarY, contentWidth, toolbarHeight, ModernUi.contains(mouseX, mouseY, contentX, toolbarY, contentWidth, toolbarHeight));
        ModernUi.button(context, textRenderer, manualButtonX, manualButtonY, manualButtonWidth, 24, "+ Paylog",
                ModernUi.theme().buttonPrimary, ModernUi.contains(mouseX, mouseY, manualButtonX, manualButtonY, manualButtonWidth, 24));
        ModernUi.button(context, textRenderer, filterButtonX, filterButtonY, filterButtonWidth, 24, DIRECTION_FILTERS[directionIndex], ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, filterButtonX, filterButtonY, filterButtonWidth, 24));

        String state = pending == null ? "Seite " + page.pageNumber() + "/" + page.pageCount() + " · " + page.totalCount() + " Ergebnis" + (page.totalCount() == 1 ? "" : "se") : "Lade Seite " + (pageOffset / PAGE_SIZE + 1) + "…";
        ModernUi.drawTruncated(context, textRenderer, state, contentX, toolbarY + toolbarHeight + 4, contentWidth, pending == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        int baseListY = toolbarY + toolbarHeight + 18;
        errorCardY = baseListY;
        int errorCardHeight = queryError == null ? 0 : errorCardHeight();
        listY = baseListY + errorCardHeight;
        int pagingHeight = ModernLayout.stack(contentWidth, 2, 88, 8) ? 54 : 23;
        listHeight = Math.max(45, contentY + contentHeight - listY - pagingHeight - 10);
        if (queryError != null) drawQueryErrorCard(context, mouseX, mouseY);
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
            String message = queryError != null ? "Ergebnisanzeige wartet auf die Datenbankprüfung." : pending == null ? "Keine Paylogs für diesen Filter gefunden." : "Paylogs werden geladen…";
            ModernUi.drawCentered(context, textRenderer, message, contentX + contentWidth / 2, listY + 25, queryError == null ? ModernUi.theme().muted : ModernUi.theme().warning);
            return;
        }
        context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
        for (int index = 0; index < renderedEntries.size(); index++) drawTransaction(context, mouseX, mouseY, renderedEntries.get(index), contentX, listY + (renderedStart + index) * rowHeight - pixelOffset, contentWidth - (scroll.isScrollable() ? 8 : 0));
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);
    }

    private void drawPaging(DrawContext context, int mouseX, int mouseY) {
        pageControlsY = listY + listHeight + 7;
        pagingButtons = ModernLayout.buttonRow(contentX, pageControlsY, contentWidth, 2, 88, 23, 8);
        ModernLayout.Bounds previousBounds = pagingButtons.getFirst();
        ModernLayout.Bounds nextBounds = pagingButtons.get(1);
        previousX = previousBounds.x();
        nextX = nextBounds.x();
        pageButtonWidth = previousBounds.width();
        boolean previous = pageOffset > 0 && pending == null;
        boolean next = page.hasNext() && pending == null;
        ModernUi.button(context, textRenderer, previousBounds.x(), previousBounds.y(), previousBounds.width(), previousBounds.height(), "← Vorherige Seite", ModernUi.theme().buttonNeutral,
                previous && ModernUi.contains(mouseX, mouseY, previousBounds.x(), previousBounds.y(), previousBounds.width(), previousBounds.height()));
        ModernUi.button(context, textRenderer, nextBounds.x(), nextBounds.y(), nextBounds.width(), nextBounds.height(), "Nächste Seite →", next ? ModernUi.theme().buttonPrimary : ModernUi.theme().buttonNeutral,
                next && ModernUi.contains(mouseX, mouseY, nextBounds.x(), nextBounds.y(), nextBounds.width(), nextBounds.height()));
    }

    private int errorCardHeight() { return ModernLayout.stack(Math.max(1, contentWidth - 12), 2, 74, 8) ? 76 : 48; }

    private void drawQueryErrorCard(DrawContext context, int mouseX, int mouseY) {
        int padding = contentWidth > 16 ? 6 : 0;
        int width = Math.max(1, contentWidth - padding * 2);
        int height = errorCardHeight();
        ModernUi.card(context, contentX, errorCardY, contentWidth, height, false);
        ModernUi.drawTruncated(context, textRenderer, "Datenbank-Schema/Reparatur erforderlich", contentX + padding, errorCardY + 5, width, ModernUi.theme().danger);
        errorButtons = ModernLayout.buttonRow(contentX + padding, errorCardY + 21, width, 2, 74, 23, 8);
        ModernLayout.Bounds recovery = errorButtons.getFirst();
        ModernLayout.Bounds retry = errorButtons.get(1);
        ModernUi.button(context, textRenderer, recovery.x(), recovery.y(), recovery.width(), recovery.height(), "Recovery öffnen", ModernUi.theme().buttonPrimary,
                ModernUi.contains(mouseX, mouseY, recovery.x(), recovery.y(), recovery.width(), recovery.height()));
        ModernUi.button(context, textRenderer, retry.x(), retry.y(), retry.width(), retry.height(), "Neu laden", ModernUi.theme().buttonNeutral,
                ModernUi.contains(mouseX, mouseY, retry.x(), retry.y(), retry.width(), retry.height()));
    }

    private void drawTransaction(DrawContext context, int mouseX, int mouseY, TransactionEntry entry, int x, int y, int width) {
        boolean outgoing = equals(entry.getFromPlayer(), currentPlayerName());
        int color = outgoing ? ModernUi.theme().danger : ModernUi.theme().success;
        ModernUi.card(context, x, y, width, 35, ModernUi.contains(mouseX, mouseY, x, y, width, 35));
        context.fill(x + 8, y + 7, x + 11, y + 28, color);
        String otherPlayer = outgoing ? entry.getToPlayer() : entry.getFromPlayer();
        ModernUi.drawTruncated(context, textRenderer, (outgoing ? "An " : "Von ") + safe(otherPlayer), x + 19, y + 7, Math.max(1, width - 164), ModernUi.theme().text);
        String linkState = entry.isFullyLinked() ? "Verknüpft" : entry.getLinkedAmount() > 0
                ? "Rest: " + FormatUtil.formatAmount(entry.getRemainingAmount()) : "Klick: verknüpfen";
        ModernUi.drawTruncated(context, textRenderer, TimeUtil.formatDateTime(entry.getTimestamp()) + " · " + linkState,
                x + 19, y + 20, Math.max(1, width - 164), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, (outgoing ? "-" : "+") + FormatUtil.formatAmount(entry.getAmount()), x + width - 12, y + 13, color);
    }

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        if (queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.getFirst().x(), errorButtons.getFirst().y(), errorButtons.getFirst().width(), errorButtons.getFirst().height())) {
            open(new ModernRecoveryScreen(manager));
            return true;
        }
        if (queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.get(1).x(), errorButtons.get(1).y(), errorButtons.get(1).width(), errorButtons.get(1).height())) {
            queryError = null;
            forceQuery = true;
            debouncer.commitImmediately(filterKey());
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), manualButtonX, manualButtonY, manualButtonWidth, 24)) {
            open(new ModernCreatePaylogScreen(manager, this));
            return true;
        }
        if (ModernUi.contains(click.x(), click.y(), filterButtonX, filterButtonY, filterButtonWidth, 24)) {
            directionIndex = (directionIndex + 1) % DIRECTION_FILTERS.length;
            requestedFilterKey = "";
            forceQuery = true;
            return true;
        }
        if (!pagingButtons.isEmpty() && ModernUi.contains(click.x(), click.y(), pagingButtons.getFirst().x(), pagingButtons.getFirst().y(), pagingButtons.getFirst().width(), pagingButtons.getFirst().height()) && pending == null && pageOffset > 0) {
            pageOffset = Math.max(0, pageOffset - PAGE_SIZE); forceQuery = false; schedule(filterKey()); return true;
        }
        if (pagingButtons.size() > 1 && ModernUi.contains(click.x(), click.y(), pagingButtons.get(1).x(), pagingButtons.get(1).y(), pagingButtons.get(1).width(), pagingButtons.get(1).height()) && pending == null && page.hasNext()) {
            pageOffset += PAGE_SIZE; forceQuery = false; schedule(filterKey()); return true;
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
        appliedKey = ""; requestedFilterKey = ""; pending = null; queryError = null; errorButtons = List.of(); pageOffset = 0; forceQuery = false;
        super.clearTransientState();
    }

    private record PendingQuery(long sequence, String key, CompletableFuture<DatabaseManager.QueryPage<TransactionEntry>> future) { }
}

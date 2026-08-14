package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.TransactionRepository;
import op.creditmanager.client.gui.CenteredTextFieldWidget;
import op.creditmanager.client.gui.modern.query.ModernQueryDebouncer;
import op.creditmanager.client.gui.modern.query.ModernQueryExecutor;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;
import op.creditmanager.client.model.CreditEntry;
import op.creditmanager.client.model.TransactionEntry;
import op.creditmanager.client.storage.db.DatabaseManager;
import op.creditmanager.client.util.FormatUtil;
import op.creditmanager.client.util.TimeUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ModernPaylogPaymentSelectionScreen extends ModernBaseScreen {
    private final CreditEntry credit;
    private final ModernPaymentScreen paymentScreen;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private final ModernQueryDebouncer debouncer = new ModernQueryDebouncer(300L);
    private TextFieldWidget searchField;
    private DatabaseManager.QueryPage<TransactionEntry> page = new DatabaseManager.QueryPage<>(List.of(), 0, 0, DatabaseManager.PAGE_SIZE);
    private List<TransactionEntry> rendered = List.of();
    private int listY, listHeight, renderedStart;
    private long sequence;
    private Pending pending;
    private String rawKey = "", queryError;
    private boolean force = true, disposed, loaded;
    private List<ModernLayout.Bounds> errorButtons = List.of();
    private int errorCardY;

    public ModernPaylogPaymentSelectionScreen(CreditManager manager, CreditEntry credit, ModernPaymentScreen parent) {
        super(manager, parent, "Paylog auswählen", "paylogs");
        this.credit = credit;
        this.paymentScreen = parent;
    }

    @Override protected void init() {
        super.init(); disposed = false; clearChildren();
        searchField = ModernUi.configureGuiTextField(new CenteredTextFieldWidget(textRenderer, contentX + 8, contentY + 4, Math.max(60, contentWidth - 16), 26, Text.empty()));
        searchField.setMaxLength(96); ModernUi.setGuiPlaceholder(searchField, "Paylog suchen..."); addDrawableChild(searchField);
    }

    @Override public void tick() {
        super.tick(); if (disposed) return;
        if (!DatabaseManager.getInstance().isHealthy()) {
            queryError = "Paylogs können erst nach der Datenbankprüfung geladen werden.";
            force = false;
            return;
        }
        String key = searchField == null ? "" : searchField.getText().trim();
        if (!key.equals(rawKey)) { if (pending != null) pending.future().cancel(true); pending = null; sequence++; rawKey = key; debouncer.update(key, System.currentTimeMillis()); loaded = false; queryError = null; }
        if (pending != null && pending.future().isDone()) { apply(pending); pending = null; }
        if (pending == null && queryError == null && (force || debouncer.ready(System.currentTimeMillis()))) { force = false; schedule(); }
    }

    private void schedule() {
        long id = ++sequence; String key = rawKey;
        pending = new Pending(id, key, CompletableFuture.supplyAsync(() -> TransactionRepository.getInstance().queryAvailableForDeal(credit.getDebtor(), credit.getCreditor(), key, DatabaseManager.PAGE_SIZE, 0), ModernQueryExecutor.get()));
    }

    private void apply(Pending result) {
        if (disposed || result.id() != sequence || !result.key().equals(rawKey)) return;
        try {
            page = result.future().join();
            loaded = true;
            queryError = null;
            scroll.reset();
        } catch (RuntimeException error) {
            queryError = "Paylogs konnten nicht geladen werden.";
            CreditManagerClient.LOGGER.error("Available-paylog background query failed", error);
            toastError(queryError);
        }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernLayout.positionTextField(searchField, contentX + 4, contentY + 4, Math.max(1, contentWidth - 8), contentY, contentHeight, true);
        ModernUi.drawTruncated(context, textRenderer, credit.getDebtor() + " → " + credit.getCreditor() + " · offen " + FormatUtil.formatAmountMinor(credit.getRemainingAmountMinor()), contentX, contentY + 37, contentWidth, ModernUi.theme().muted);
        int baseListY = contentY + 52;
        errorCardY = baseListY;
        int errorCardHeight = queryError == null ? 0 : errorCardHeight();
        listY = baseListY + errorCardHeight; listHeight = Math.max(48, contentHeight - (listY - contentY) - 4);
        if (queryError != null) drawQueryErrorCard(context, mouseX, mouseY);
        scroll.setBounds(contentX, listY, contentWidth, listHeight, page.entries().size() * 42); scroll.tick(mouseX, mouseY);
        int offset = scroll.offset(); renderedStart = Math.max(0, offset / 42); int end = Math.min(page.entries().size(), renderedStart + listHeight / 42 + 3); rendered = page.entries().subList(renderedStart, end);
        if (rendered.isEmpty()) {
            ModernUi.card(context, contentX, listY, contentWidth, 56, false);
            String message = queryError != null ? "Ergebnisanzeige wartet auf die Datenbankprüfung." : pending == null && loaded ? "Keine passenden verfügbaren Paylogs." : "Paylogs werden geladen…";
            ModernUi.drawCentered(context, textRenderer, message, contentX + contentWidth / 2, listY + 24,
                    queryError == null ? ModernUi.theme().muted : ModernUi.theme().warning);
        } else {
            context.enableScissor(contentX, listY, contentX + contentWidth, listY + listHeight);
            for (int index = 0; index < rendered.size(); index++) drawRow(context, mouseX, mouseY, rendered.get(index), contentX, listY + (renderedStart + index) * 42 - offset, contentWidth - (scroll.isScrollable() ? 8 : 0));
            context.disableScissor(); scroll.renderScrollbar(context, mouseX, mouseY);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawRow(DrawContext context, int mouseX, int mouseY, TransactionEntry paylog, int x, int y, int width) {
        ModernUi.card(context, x, y, width, 38, ModernUi.contains(mouseX, mouseY, x, y, width, 38));
        ModernUi.drawTruncated(context, textRenderer, paylog.getFromPlayer() + " → " + paylog.getToPlayer(), x + 10, y + 8, Math.max(40, width - 128), ModernUi.theme().text);
        ModernUi.drawTruncated(context, textRenderer, TimeUtil.formatDateTime(paylog.getTimestamp()) + " · Rest " + FormatUtil.formatAmountMinor(paylog.getRemainingAmountMinor()), x + 10, y + 22, Math.max(40, width - 128), ModernUi.theme().muted);
        ModernUi.drawGuiTextRightAligned(context, textRenderer, FormatUtil.formatAmountMinor(paylog.getAmountMinor()), x + width - 10, y + 13, ModernUi.theme().success);
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

    @Override public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (click.button() == 0 && queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.getFirst().x(), errorButtons.getFirst().y(), errorButtons.getFirst().width(), errorButtons.getFirst().height())) { open(new ModernRecoveryScreen(manager)); return true; }
        if (click.button() == 0 && queryError != null && errorButtons.size() == 2 && ModernUi.contains(click.x(), click.y(), errorButtons.get(1).x(), errorButtons.get(1).y(), errorButtons.get(1).width(), errorButtons.get(1).height())) { if (!manager.recheckAndRepairDatabase()) { queryError = "Datenbankprüfung konnte nicht abgeschlossen werden."; return true; } queryError = null; force = true; debouncer.commitImmediately(rawKey); return true; }
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, listY, contentWidth, listHeight)) {
            int index = (int) ((click.y() - listY + scroll.offset()) / 42);
            if (index >= renderedStart && index < renderedStart + rendered.size()) { paymentScreen.usePaylog(rendered.get(index - renderedStart)); navigateBack(); return true; }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { if (scroll.contains(mouseX, mouseY)) { scroll.scroll(verticalAmount); return true; } return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount); }
    @Override protected void clearTransientState() { disposed = true; sequence++; if (pending != null) pending.future().cancel(true); scroll.reset(); pending = null; loaded = false; queryError = null; errorButtons = List.of(); super.clearTransientState(); }
    private record Pending(long id, String key, CompletableFuture<DatabaseManager.QueryPage<TransactionEntry>> future) { }
}

package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import op.creditmanager.client.CreditManagerClient;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.core.CreditManagerMutationExecutor;
import op.creditmanager.client.core.service.MutationCommitResult;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import op.creditmanager.client.gui.modern.toast.ModernToastType;
import op.creditmanager.client.storage.DataHealth;

import java.util.List;

public abstract class ModernBaseScreen extends Screen {

    private static final Identifier LOGO_TEXTURE = Identifier.of("creditmanager", "textures/gui/logo.png");
    private static final int LOGO_TEXTURE_SIZE = 128;
    private static final int LOGO_DRAW_SIZE = 32;
    private static final String NAVIGATION_POSITION_KEY = "navigation-active-position";
    private static final List<NavigationEntry> NAVIGATION = List.of(
            new NavigationEntry("Übersicht", "overview"),
            new NavigationEntry("Forderungen", "claims"),
            new NavigationEntry("Schulden", "debts"),
            new NavigationEntry("Paylogs", "paylogs"),
            new NavigationEntry("History", "history"),
            new NavigationEntry("Info", "info"),
            new NavigationEntry("Einstellungen", "settings")
    );
    private static final IdentitySnapshotCache<Boolean> LOGO_AVAILABILITY = new IdentitySnapshotCache<>();

    protected final CreditManager manager;
    protected final Screen parent;
    private final String pageTitle;
    private final String activeNavigation;
    private final UiCompletionGeneration completionGeneration = new UiCompletionGeneration();

    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;
    protected int sidebarWidth;
    protected int contentX;
    protected int contentY;
    protected int contentWidth;
    protected int contentHeight;
    protected int closeButtonX;
    protected int closeButtonY;
    protected int backButtonX;
    protected int backButtonY;
    protected int backButtonWidth;
    protected boolean compactLayout;

    private record NavigationEntry(String label, String id, String animationKey) {
        private NavigationEntry(String label, String id) {
            this(label, id, "navigation:" + id);
        }
    }

    protected ModernBaseScreen(CreditManager manager, Screen parent, String pageTitle, String activeNavigation) {
        super(Text.literal(pageTitle));
        this.manager = manager;
        this.parent = parent;
        this.pageTitle = pageTitle;
        this.activeNavigation = activeNavigation;
    }

    @Override
    protected void init() {
        ModernLayout.ShellBounds layout = ModernLayout.shell(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        panelX = layout.panelX();
        panelY = layout.panelY();
        compactLayout = layout.compact();
        sidebarWidth = layout.sidebarWidth();
        contentX = layout.contentX();
        contentY = layout.contentY();
        contentWidth = layout.contentWidth();
        contentHeight = layout.contentHeight();
    }

    protected void renderShell(DrawContext context, int mouseX, int mouseY) {
        String dataWarning = DataHealth.consumeWarning();
        if (dataWarning != null) toastError(dataWarning);
        ModernThemePalette theme = ModernUi.theme();
        context.fill(0, 0, width, height, theme.overlay);
        ModernUi.panel(context, panelX, panelY, panelWidth, panelHeight, theme.panel);
        context.fill(panelX, panelY, panelX + sidebarWidth, panelY + panelHeight, theme.panelAlt);
        if (!compactLayout) {
            context.fill(panelX + sidebarWidth, panelY + 1, panelX + sidebarWidth + 1, panelY + panelHeight - 1, theme.border);
        }

        int brandX = panelX + (compactLayout ? 6 : 12);
        int brandY = panelY + 8;

        if (!compactLayout && logoAvailable()) {
            ModernUi.card(context, brandX - 2, brandY - 2, LOGO_DRAW_SIZE + 4, LOGO_DRAW_SIZE + 4, false);
            context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    LOGO_TEXTURE,
                    brandX,
                    brandY,
                    0.0F,
                    0.0F,
                    LOGO_DRAW_SIZE,
                    LOGO_DRAW_SIZE,
                    LOGO_TEXTURE_SIZE,
                    LOGO_TEXTURE_SIZE,
                    LOGO_TEXTURE_SIZE,
                    LOGO_TEXTURE_SIZE
            );
            context.fill(brandX, brandY, brandX + 3, brandY + 3, theme.card);
            context.fill(brandX + LOGO_DRAW_SIZE - 3, brandY, brandX + LOGO_DRAW_SIZE, brandY + 3, theme.card);
            context.fill(brandX, brandY + LOGO_DRAW_SIZE - 3, brandX + 3, brandY + LOGO_DRAW_SIZE, theme.card);
            context.fill(brandX + LOGO_DRAW_SIZE - 3, brandY + LOGO_DRAW_SIZE - 3,
                    brandX + LOGO_DRAW_SIZE, brandY + LOGO_DRAW_SIZE, theme.card);

            brandX += LOGO_DRAW_SIZE + 7;
        }

        ModernUi.drawTruncated(
                context,
                textRenderer,
                "CM",
                brandX,
                brandY + (LOGO_DRAW_SIZE - textRenderer.fontHeight) / 2,
                Math.max(1, (compactLayout ? contentWidth : sidebarWidth) - (brandX - panelX) - 8),
                theme.accent
        );
        int titleX = contentX;
        if (shouldShowBackButton()) {
            backButtonX = contentX;
            backButtonY = panelY + 9;
            backButtonWidth = Math.max(1, Math.min(52, contentWidth));
            ModernUi.button(context, textRenderer, backButtonX, backButtonY, backButtonWidth, 20, "Zurück", theme.buttonNeutral,
                    ModernUi.contains(mouseX, mouseY, backButtonX, backButtonY, backButtonWidth, 20));
            titleX += backButtonWidth + 8;
        } else {
            backButtonX = -1;
            backButtonY = -1;
            backButtonWidth = 0;
        }
        ModernUi.drawTruncated(context, textRenderer, pageTitle, titleX, panelY + 18,
                Math.max(20, contentWidth - (titleX - contentX) - 42), theme.text);
        context.fill(contentX, panelY + 36, Math.max(contentX + 1, panelX + panelWidth - Math.min(18, Math.max(1, panelWidth / 4))), panelY + 37, theme.border);
        closeButtonX = Math.max(panelX, panelX + panelWidth - 24);
        closeButtonY = panelY + 10;
        ModernUi.closeButton(context, textRenderer, closeButtonX, closeButtonY,
                ModernUi.contains(mouseX, mouseY, closeButtonX, closeButtonY, 18, 18));

        drawNavigation(context, mouseX, mouseY);
    }

    private void drawNavigation(DrawContext context, int mouseX, int mouseY) {
        if (compactLayout) return;
        ModernThemePalette theme = ModernUi.theme();
        int activeIndex = 0;
        for (int index = 0; index < NAVIGATION.size(); index++) {
            if (NAVIGATION.get(index).id().equals(activeNavigation)) {
                activeIndex = index;
                break;
            }
        }
        int activeY = ModernUi.animatedPosition(NAVIGATION_POSITION_KEY, panelY + 48 + activeIndex * 27);
        int y = panelY + 48;
        for (NavigationEntry entry : NAVIGATION) {
            boolean active = entry.id().equals(activeNavigation);
            boolean hovered = ModernUi.contains(mouseX, mouseY, panelX + 8, y, sidebarWidth - 16, 22);
            float emphasis = ModernUi.animationProgress(entry.animationKey(), hovered);
            if (emphasis > 0.01F) {
                int base = active ? theme.navActive : theme.panelAlt;
                context.fill(panelX + 8, y, panelX + sidebarWidth - 8, y + 22,
                        ColorUtil.mix(base, theme.navActive, emphasis));
                context.fill(panelX + 8, y, panelX + 10, y + 22,
                        ColorUtil.withAlpha(theme.accent, Math.max(1, Math.round(255.0F * emphasis))));
            }
            y += 27;
        }

        context.fill(panelX + 8, activeY, panelX + sidebarWidth - 8, activeY + 22, theme.navActive);
        context.fill(panelX + 8, activeY, panelX + 10, activeY + 22, theme.accent);

        y = panelY + 48;
        for (NavigationEntry entry : NAVIGATION) {
            boolean active = entry.id().equals(activeNavigation);
            ModernUi.drawTruncated(context, textRenderer, entry.label(), panelX + 16, y + 7,
                    sidebarWidth - 28, active ? theme.text : theme.muted);
            y += 27;
        }
    }

    protected boolean handleSidebarClick(Click click) {
        if (compactLayout) return false;
        if (click.button() != 0) {
            return false;
        }
        int y = panelY + 48;
        for (NavigationEntry entry : NAVIGATION) {
            if (ModernUi.contains(click.x(), click.y(), panelX + 8, y, sidebarWidth - 16, 22)) {
                openNavigation(entry.id());
                return true;
            }
            y += 27;
        }
        return false;
    }

    private void openNavigation(String id) {
        Screen next = switch (id) {
            case "claims" -> new ModernCreditListScreen(manager, false, null);
            case "debts" -> new ModernCreditListScreen(manager, true, null);
            case "paylogs" -> new ModernPaylogScreen(manager, null);
            case "history" -> new ModernDealHistoryScreen(manager, null);
            case "info" -> new ModernInfoScreen(manager, null);
            case "settings" -> new ModernSettingsScreen(manager, null);
            default -> new ModernMainScreen(manager);
        };
        open(next);
    }

    public static void invalidateResourceCaches() {
        LOGO_AVAILABILITY.invalidate();
        ModernUi.invalidateTextCaches();
    }

    static List<String> navigationIds() {
        return NAVIGATION.stream().map(NavigationEntry::id).toList();
    }

    private static boolean logoAvailable() {
        Object resourceManager = MinecraftClient.getInstance().getResourceManager();
        return LOGO_AVAILABILITY.get(resourceManager,
                () -> MinecraftClient.getInstance().getResourceManager().getResource(LOGO_TEXTURE).isPresent());
    }

    protected void open(Screen screen) {
        clearTransientState();
        MinecraftClient.getInstance().setScreen(screen);
    }

    protected void toast(String message, ModernToastType type) {
        ModernToastManager.getInstance().show(message, type);
    }

    protected void toastSuccess(String message) { toast(message, ModernToastType.SUCCESS); }
    protected void toastError(String message) {
        toast(message, ModernToastType.ERROR);
    }
    protected void toastWarning(String message) { toast(message, ModernToastType.WARNING); }
    protected void toastInfo(String message) { toast(message, ModernToastType.INFO); }

    protected boolean showMutationCommitNotice() {
        return showMutationCommitNotice(manager.consumeLastMutationCommit());
    }

    protected boolean showMutationCommitNotice(MutationCommitResult result) {
        if (result == null || result.status() == MutationCommitResult.Status.COMMITTED_SYNCED) return false;
        if (result.status() == MutationCommitResult.Status.COMMITTED_RELOAD_REQUIRED) {
            toastWarning(result.userMessage());
            return true;
        }
        if (result.status() == MutationCommitResult.Status.COMMITTED_DEGRADED) {
            toastError(result.userMessage());
            return true;
        }
        return false;
    }

    protected <T> boolean submitMutation(MutationSubmissionGuard guard,
                                         CreditManagerMutationExecutor.CheckedSupplier<T> operation,
                                         MutationCompletion<T> completion) {
        if (guard == null || operation == null || completion == null) return false;
        long submissionToken = guard.tryBeginToken();
        if (submissionToken < 0L) return false;
        long generation = completionGeneration.capture();
        CreditManagerMutationExecutor.getInstance().submit(manager, operation).whenComplete((result, failure) -> {
            try {
                MinecraftClient.getInstance().execute(() -> {
                    boolean ownsSubmission = guard.complete(submissionToken);
                    boolean current = ownsSubmission && completionGeneration.isCurrent(generation)
                            && MinecraftClient.getInstance().currentScreen == this;
                    try {
                        completion.complete(result, unwrapCompletionFailure(failure), current);
                    } catch (RuntimeException completionFailure) {
                        CreditManagerClient.LOGGER.error("CreditManager mutation completion failed", completionFailure);
                    }
                });
            } catch (RuntimeException publicationFailure) {
                CreditManagerClient.LOGGER.error("CreditManager mutation completion could not be published", publicationFailure);
            }
        });
        return true;
    }

    private Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    protected boolean isAnyInputFocused() {
        if (getFocused() instanceof TextFieldWidget field && field.isFocused()) {
            return true;
        }
        for (Element child : children()) {
            if (child instanceof TextFieldWidget field && field.isFocused()) {
                return true;
            }
        }
        return false;
    }

    protected boolean isTextInputFocused() {
        return isAnyInputFocused();
    }

    protected String currentPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? "" : client.player.getName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == 256) {
            closeCompletely();
            return true;
        }
        if (shouldCloseFromKey(input)) {
            closeCompletely();
            return true;
        }
        return super.keyPressed(input);
    }

    protected boolean shouldCloseFromKey(net.minecraft.client.input.KeyInput input) {
        return !isAnyInputFocused() && MinecraftClient.getInstance().options.inventoryKey.matchesKey(input);
    }

    protected void closeToParent() {
        navigateBack();
    }

    protected void navigateBack() {
        if (parent == null) {
            closeCompletely();
            return;
        }
        clearTransientState();
        MinecraftClient.getInstance().setScreen(parent);
    }

    protected void closeCompletely() {
        clearNavigationState();
        MinecraftClient.getInstance().setScreen(null);
    }

    protected boolean hasParentScreen() {
        return parent != null;
    }

    protected boolean shouldShowBackButton() {
        return hasParentScreen();
    }

    protected void clearTransientState() {
        clearInputFocus();
    }

    protected void clearInputFocus() {
        for (Element child : children()) {
            if (child instanceof TextFieldWidget field) {
                field.setFocused(false);
            }
        }
        setFocused(null);
    }

    protected void resetInputFocus() {
        clearInputFocus();
    }

    private void clearNavigationState() {
        Screen screen = this;
        while (screen instanceof ModernBaseScreen modernScreen) {
            modernScreen.clearTransientState();
            screen = modernScreen.parent;
        }
    }

    @Override
    public void close() {
        closeCompletely();
    }

    @Override
    public void removed() {
        completionGeneration.invalidate();
        clearTransientState();
        super.removed();
    }

    @FunctionalInterface
    protected interface MutationCompletion<T> {
        void complete(CreditManagerMutationExecutor.MutationOutcome<T> result, Throwable failure,
                      boolean screenCurrent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        ModernToastManager.getInstance().render(context, textRenderer, width, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), closeButtonX, closeButtonY, 18, 18)) {
            closeCompletely();
            return true;
        }
        if (click.button() == 0 && shouldShowBackButton() && ModernUi.contains(click.x(), click.y(), backButtonX, backButtonY, backButtonWidth, 20)) {
            navigateBack();
            return true;
        }
        if (ModernToastManager.getInstance().mouseClicked(click.x(), click.y(), click.button())) {
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

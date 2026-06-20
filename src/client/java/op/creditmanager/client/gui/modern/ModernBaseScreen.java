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
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.toast.ModernToastManager;
import op.creditmanager.client.gui.modern.toast.ModernToastType;

/** Common layout, navigation and input behaviour for the modern GUI screens. */
public abstract class ModernBaseScreen extends Screen {

    private static final Identifier LOGO_TEXTURE = Identifier.of("creditmanager", "textures/gui/logo.png");
    private static final int LOGO_TEXTURE_SIZE = 128;
    private static final int LOGO_DRAW_SIZE = 32;

    protected final CreditManager manager;
    protected final Screen parent;
    private final String pageTitle;
    private final String activeNavigation;

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

    protected ModernBaseScreen(CreditManager manager, Screen parent, String pageTitle, String activeNavigation) {
        super(Text.literal(pageTitle));
        this.manager = manager;
        this.parent = parent;
        this.pageTitle = pageTitle;
        this.activeNavigation = activeNavigation;
    }

    @Override
    protected void init() {
        int outerMargin = Math.max(8, Math.min(24, Math.min(width, height) / 16));
        panelWidth = Math.min(760, width - outerMargin * 2);
        panelHeight = Math.min(460, height - outerMargin * 2);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        sidebarWidth = Math.min(150, Math.max(78, panelWidth / 5));
        contentX = panelX + sidebarWidth + 18;
        contentY = panelY + 48;
        contentWidth = panelWidth - sidebarWidth - 36;
        contentHeight = panelHeight - 64;
    }

    protected void renderShell(DrawContext context, int mouseX, int mouseY) {
        ModernThemePalette theme = ModernUi.theme();
        context.fill(0, 0, width, height, theme.overlay);
        ModernUi.panel(context, panelX, panelY, panelWidth, panelHeight, theme.panel);
        context.fill(panelX, panelY, panelX + sidebarWidth, panelY + panelHeight, theme.panelAlt);
        context.fill(panelX + sidebarWidth, panelY + 1, panelX + sidebarWidth + 1, panelY + panelHeight - 1, theme.border);

        int brandX = panelX + 12;
        int brandY = panelY + 8;

        if (MinecraftClient.getInstance().getResourceManager().getResource(LOGO_TEXTURE).isPresent()) {
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
            // A four-corner mask keeps the legacy square source texture visually rounded without a second asset.
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
                Math.max(20, sidebarWidth - (brandX - panelX) - 8),
                theme.accent
        );
        int titleX = contentX;
        if (shouldShowBackButton()) {
            backButtonX = contentX;
            backButtonY = panelY + 9;
            ModernUi.button(context, textRenderer, backButtonX, backButtonY, 52, 20, "Zurück", theme.buttonNeutral,
                    ModernUi.contains(mouseX, mouseY, backButtonX, backButtonY, 52, 20));
            titleX += 60;
        } else {
            backButtonX = -1;
            backButtonY = -1;
        }
        ModernUi.drawTruncated(context, textRenderer, pageTitle, titleX, panelY + 18,
                Math.max(20, contentWidth - (titleX - contentX) - 42), theme.text);
        context.fill(contentX, panelY + 36, panelX + panelWidth - 18, panelY + 37, theme.border);
        closeButtonX = panelX + panelWidth - 28;
        closeButtonY = panelY + 10;
        ModernUi.closeButton(context, textRenderer, closeButtonX, closeButtonY,
                ModernUi.contains(mouseX, mouseY, closeButtonX, closeButtonY, 18, 18));

        drawNavigation(context, mouseX, mouseY);
    }

    private void drawNavigation(DrawContext context, int mouseX, int mouseY) {
        String[][] entries = {
                {"Übersicht", "overview"},
                {"Forderungen", "claims"},
                {"Schulden", "debts"},
                {"Paylogs", "paylogs"},
                {"Info", "info"},
                {"Einstellungen", "settings"}
        };
        int activeIndex = 0;
        for (int index = 0; index < entries.length; index++) {
            if (entries[index][1].equals(activeNavigation)) {
                activeIndex = index;
                break;
            }
        }
        int activeY = ModernUi.animatedPosition("navigation-active-position", panelY + 48 + activeIndex * 27);
        int y = panelY + 48;
        for (String[] entry : entries) {
            boolean active = entry[1].equals(activeNavigation);
            boolean hovered = ModernUi.contains(mouseX, mouseY, panelX + 8, y, sidebarWidth - 16, 22);
            // A hover deliberately resembles the selected tab and eases back out after leaving it.
            float emphasis = ModernUi.animationProgress("navigation:" + entry[1], hovered);
            if (emphasis > 0.01F) {
                int base = active ? ModernUi.theme().navActive : ModernUi.theme().panelAlt;
                context.fill(panelX + 8, y, panelX + sidebarWidth - 8, y + 22,
                        ColorUtil.mix(base, ModernUi.theme().navActive, emphasis));
                // Hover never receives a muted/grey marker: only the bright accent fades in and out.
                context.fill(panelX + 8, y, panelX + 10, y + 22,
                        ColorUtil.withAlpha(ModernUi.theme().accent, Math.max(1, Math.round(255.0F * emphasis))));
            }
            y += 27;
        }

        // Draw the moving active layer after every hover layer: its bright strip is never hidden by a hover.
        context.fill(panelX + 8, activeY, panelX + sidebarWidth - 8, activeY + 22, ModernUi.theme().navActive);
        context.fill(panelX + 8, activeY, panelX + 10, activeY + 22, ModernUi.theme().accent);

        y = panelY + 48;
        for (String[] entry : entries) {
            boolean active = entry[1].equals(activeNavigation);
            ModernUi.drawTruncated(context, textRenderer, entry[0], panelX + 16, y + 7,
                    sidebarWidth - 28, active ? ModernUi.theme().text : ModernUi.theme().muted);
            y += 27;
        }
    }

    protected boolean handleSidebarClick(Click click) {
        if (click.button() != 0) {
            return false;
        }
        int y = panelY + 48;
        String[] ids = {"overview", "claims", "debts", "paylogs", "info", "settings"};
        for (String id : ids) {
            if (ModernUi.contains(click.x(), click.y(), panelX + 8, y, sidebarWidth - 16, 22)) {
                openNavigation(id);
                return true;
            }
            y += 27;
        }
        return false;
    }

    private void openNavigation(String id) {
        Screen next = switch (id) {
            // Sidebar destinations are application roots, never children of the currently open sub-screen.
            // That prevents a second navigation layer and keeps root pages free of a back button.
            case "claims" -> new ModernCreditListScreen(manager, false, null);
            case "debts" -> new ModernCreditListScreen(manager, true, null);
            case "paylogs" -> new ModernPaylogScreen(manager, null);
            case "info" -> new ModernInfoScreen(manager, null);
            case "settings" -> new ModernSettingsScreen(manager, null);
            default -> new ModernMainScreen(manager);
        };
        open(next);
    }

    protected void open(Screen screen) {
        clearTransientState();
        MinecraftClient.getInstance().setScreen(screen);
    }

    protected void toast(String message, ModernToastType type) {
        ModernToastManager.getInstance().show(message, type);
    }

    protected void toastSuccess(String message) { toast(message, ModernToastType.SUCCESS); }
    protected void toastError(String message) { toast(message, ModernToastType.ERROR); }
    protected void toastWarning(String message) { toast(message, ModernToastType.WARNING); }
    protected void toastInfo(String message) { toast(message, ModernToastType.INFO); }

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

    /** Kept for existing screen-specific shortcuts; use isAnyInputFocused in new code. */
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
            // ESC always closes the full modern GUI, even while editing a field.
            closeCompletely();
            return true;
        }
        if (shouldCloseFromKey(input)) {
            // The user's inventory key closes the complete GUI only when it is not text input.
            closeCompletely();
            return true;
        }
        return super.keyPressed(input);
    }

    protected boolean shouldCloseFromKey(net.minecraft.client.input.KeyInput input) {
        return !isAnyInputFocused() && MinecraftClient.getInstance().options.inventoryKey.matchesKey(input);
    }

    /** Uses the parent only for in-app navigation; the red X always closes the complete GUI. */
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

    /** Only sub-screens receive the one shared header back control. */
    protected boolean shouldShowBackButton() {
        return hasParentScreen();
    }

    /** Override in screens that keep local selections, drag states, or temporary form modes. */
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

    /** Compatibility alias for existing integrations. */
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
        clearTransientState();
        super.removed();
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
        if (click.button() == 0 && shouldShowBackButton() && ModernUi.contains(click.x(), click.y(), backButtonX, backButtonY, 52, 20)) {
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

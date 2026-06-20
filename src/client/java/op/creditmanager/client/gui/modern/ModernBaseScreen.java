package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import op.creditmanager.client.core.CreditManager;

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
        context.fill(0, 0, width, height, ModernUi.OVERLAY);
        ModernUi.panel(context, panelX, panelY, panelWidth, panelHeight, ModernUi.PANEL);
        context.fill(panelX, panelY, panelX + sidebarWidth, panelY + panelHeight, ModernUi.PANEL_ALT);
        context.fill(panelX + sidebarWidth, panelY + 1, panelX + sidebarWidth + 1, panelY + panelHeight - 1, ModernUi.BORDER);

        int brandX = panelX + 12;
        int brandY = panelY + 8;

        if (MinecraftClient.getInstance().getResourceManager().getResource(LOGO_TEXTURE).isPresent()) {
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

            brandX += LOGO_DRAW_SIZE + 7;
        }

        ModernUi.drawTruncated(
                context,
                textRenderer,
                "CM",
                brandX,
                brandY + (LOGO_DRAW_SIZE - textRenderer.fontHeight) / 2,
                Math.max(20, sidebarWidth - (brandX - panelX) - 8),
                ModernUi.BLUE
        );
        ModernUi.drawTruncated(context, textRenderer, pageTitle, contentX, panelY + 18, contentWidth - 12, ModernUi.TEXT);
        context.fill(contentX, panelY + 36, panelX + panelWidth - 18, panelY + 37, ModernUi.BORDER);

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
        int y = panelY + 48;
        for (String[] entry : entries) {
            boolean active = entry[1].equals(activeNavigation);
            boolean hovered = ModernUi.contains(mouseX, mouseY, panelX + 8, y, sidebarWidth - 16, 22);
            if (active || hovered) {
                context.fill(panelX + 8, y, panelX + sidebarWidth - 8, y + 22,
                        active ? ModernUi.NAV_ACTIVE : ModernUi.NAV_HOVER);
                context.fill(panelX + 8, y, panelX + 10, y + 22, active ? ModernUi.BLUE : ModernUi.BORDER);
            }
            ModernUi.drawTruncated(context, textRenderer, entry[0], panelX + 16, y + 7,
                    sidebarWidth - 28, active ? ModernUi.TEXT : ModernUi.MUTED);
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
            case "claims" -> new ModernCreditListScreen(manager, false, this);
            case "debts" -> new ModernCreditListScreen(manager, true, this);
            case "paylogs" -> new ModernPaylogScreen(manager, this);
            case "info" -> new ModernInfoScreen(manager, this);
            case "settings" -> new ModernSettingsScreen(manager, this);
            default -> new ModernMainScreen(manager);
        };
        MinecraftClient.getInstance().setScreen(next);
    }

    protected void open(Screen screen) {
        MinecraftClient.getInstance().setScreen(screen);
    }

    protected boolean isTextInputFocused() {
        return getFocused() instanceof TextFieldWidget field && field.isFocused();
    }

    protected String currentPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? "" : client.player.getName().getString().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.inventoryKey.matchesKey(input)) {
            if (isTextInputFocused()) {
                return super.keyPressed(input);
            }
            closeToParent();
            return true;
        }
        if (input.getKeycode() == 256 && isTextInputFocused()) {
            setFocused(null);
            return true;
        }
        if (input.getKeycode() == 256) {
            closeToParent();
            return true;
        }
        return super.keyPressed(input);
    }

    protected void closeToParent() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

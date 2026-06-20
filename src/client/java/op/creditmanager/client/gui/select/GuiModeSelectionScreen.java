package op.creditmanager.client.gui.select;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.config.GuiMode;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.GuiRouter;
import op.creditmanager.client.gui.modern.ModernUi;

/** First-run choice shown until a GUI style has explicitly been selected. */
public class GuiModeSelectionScreen extends Screen {

    private final CreditManager manager;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int classicX;
    private int modernX;
    private int cardY;
    private int cardWidth;

    public GuiModeSelectionScreen(CreditManager manager) {
        super(Text.literal("CreditManager GUI auswählen"));
        this.manager = manager;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(300, Math.min(width - 24, 620));
        panelHeight = Math.min(220, Math.max(190, height - 24));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        cardWidth = (panelWidth - 54) / 2;
        classicX = panelX + 18;
        modernX = classicX + cardWidth + 18;
        cardY = panelY + 74;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, ModernUi.OVERLAY);
        ModernUi.panel(context, panelX, panelY, panelWidth, panelHeight, ModernUi.PANEL);
        ModernUi.drawCentered(context, textRenderer, "CreditManager GUI auswählen", width / 2, panelY + 20, ModernUi.TEXT);
        ModernUi.drawCentered(context, textRenderer, "Du kannst diese Auswahl später jederzeit in den Einstellungen ändern.",
                width / 2, panelY + 38, ModernUi.MUTED);

        drawOption(context, mouseX, mouseY, classicX, "Classic GUI", "Der vertraute Inventar- und Slot-Stil.", "Minecraft-nah & kompakt", GuiMode.CLASSIC);
        drawOption(context, mouseX, mouseY, modernX, "Modern GUI", "Panels, Listen, Suche und klare Navigation.", "Modern & übersichtlich", GuiMode.MODERN);
        super.render(context, mouseX, mouseY, delta);
    }

    private void drawOption(DrawContext context, int mouseX, int mouseY, int x, String title,
                            String lineOne, String lineTwo, GuiMode mode) {
        boolean hovered = ModernUi.contains(mouseX, mouseY, x, cardY, cardWidth, 112);
        ModernUi.card(context, x, cardY, cardWidth, 112, hovered);
        ModernUi.drawCentered(context, textRenderer, title, x + cardWidth / 2, cardY + 17,
                mode == GuiMode.CLASSIC ? ModernUi.YELLOW : ModernUi.BLUE);
        ModernUi.drawTruncated(context, textRenderer, lineOne, x + 12, cardY + 39, cardWidth - 24, ModernUi.MUTED);
        ModernUi.drawTruncated(context, textRenderer, lineTwo, x + 12, cardY + 53, cardWidth - 24, ModernUi.MUTED);
        ModernUi.button(context, textRenderer, x + 12, cardY + 76, cardWidth - 24, 24, "Auswählen",
                mode == GuiMode.CLASSIC ? 0xFF64522A : 0xFF1F5C88, hovered);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            if (ModernUi.contains(click.x(), click.y(), classicX, cardY, cardWidth, 112)) {
                GuiRouter.selectModeAndOpen(manager, GuiMode.CLASSIC);
                return true;
            }
            if (ModernUi.contains(click.x(), click.y(), modernX, cardY, cardWidth, 112)) {
                GuiRouter.selectModeAndOpen(manager, GuiMode.MODERN);
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.getKeycode() == 256) {
            MinecraftClient.getInstance().setScreen(null);
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

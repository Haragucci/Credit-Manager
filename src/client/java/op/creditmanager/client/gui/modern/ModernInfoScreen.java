package op.creditmanager.client.gui.modern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Readable, scrollable product information with safe Minecraft link confirmation. */
public class ModernInfoScreen extends ModernBaseScreen {
    private static final String GITHUB_URL = "https://github.com/Haragucci/Credit-Manager";
    private static final String DISCORD_URL = "https://discord.gg/C8x4qCJ5KA";
    private static final int BOTTOM_SCROLL_PADDING = 28;
    private final ModernScrollArea scroll = new ModernScrollArea();
    private int githubY;
    private int discordY;

    public ModernInfoScreen(CreditManager manager, Screen parent) {
        super(manager, parent, "Info", "info");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        List<String> lines = contentLines(Math.max(40, contentWidth - 34));
        int lineHeight = textRenderer.fontHeight + 4;
        int totalHeight = 65 + (lines.size() + 1) * lineHeight + textRenderer.fontHeight + BOTTOM_SCROLL_PADDING;
        int viewportY = contentY + 6;
        int viewportHeight = Math.max(36, contentY + contentHeight - viewportY - 4);
        scroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, totalHeight);
        scroll.tick(mouseX, mouseY);
        int y = viewportY - scroll.offset();
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        ModernUi.card(context, contentX, y, contentWidth - (scroll.isScrollable() ? 8 : 0), totalHeight, false);
        ModernUi.drawGuiText(context, textRenderer, "CreditManager", contentX + 14, y + 13, theme.text);
        ModernUi.drawGuiText(context, textRenderer, "Version " + modVersion(), contentX + 14, y + 29, theme.accent);
        int textY = y + 50;
        for (String line : lines) {
            ModernUi.drawGuiText(context, textRenderer, line, contentX + 14, textY, theme.muted);
            textY += lineHeight;
        }
        githubY = textY + 8;
        boolean githubHovered = ModernUi.contains(mouseX, mouseY, contentX + 14, githubY, contentWidth - 30, textRenderer.fontHeight + 2);
        ModernUi.drawGuiText(context, textRenderer, "GitHub: " + GITHUB_URL, contentX + 14, githubY,
                githubHovered ? theme.accent : theme.warning);
        context.fill(contentX + 14, githubY + textRenderer.fontHeight + 1,
                contentX + 14 + ModernUi.getGuiTextWidth(textRenderer, "GitHub: " + GITHUB_URL), githubY + textRenderer.fontHeight + 2,
                githubHovered ? theme.accent : theme.warning);
        discordY = githubY + lineHeight + 5;
        boolean discordHovered = ModernUi.contains(mouseX, mouseY, contentX + 14, discordY, contentWidth - 30, textRenderer.fontHeight + 2);
        ModernUi.drawGuiText(context, textRenderer, "Discord: " + DISCORD_URL, contentX + 14, discordY,
                discordHovered ? theme.accent : theme.warning);
        context.fill(contentX + 14, discordY + textRenderer.fontHeight + 1,
                contentX + 14 + ModernUi.getGuiTextWidth(textRenderer, "Discord: " + DISCORD_URL), discordY + textRenderer.fontHeight + 2,
                discordHovered ? theme.accent : theme.warning);
        context.disableScissor();
        scroll.renderScrollbar(context, mouseX, mouseY);
        super.render(context, mouseX, mouseY, delta);
    }

    private List<String> contentLines(int maxWidth) {
        String[] paragraphs = {
                "Verwalte Forderungen, Schulden, Zahlungen und erkannte Paylogs direkt im Client.",
                "Deine Daten liegen lokal in CreditManagerLogs. Die Auswahl zwischen Classic- und Modern-GUI verändert weder Deals noch gespeicherte JSON-Dateien.",
                "Die Modern-GUI bietet Themes, eine eigene Deal-Historie und Statistiken. Paylogs bleiben bewusst ein separates System."
        };
        List<String> result = new ArrayList<>();
        for (String paragraph : paragraphs) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (ModernUi.getGuiTextWidth(textRenderer, candidate) > maxWidth && !line.isEmpty()) {
                    result.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) result.add(line.toString());
            result.add("");
        }
        return result;
    }

    private String modVersion() {
        return FabricLoader.getInstance().getModContainer("creditmanager")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unbekannt");
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (scroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX + 14, githubY, contentWidth - 30, textRenderer.fontHeight + 2)) {
            ConfirmLinkScreen.open(this, URI.create(GITHUB_URL), true);
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX + 14, discordY, contentWidth - 30, textRenderer.fontHeight + 2)) {
            ConfirmLinkScreen.open(this, URI.create(DISCORD_URL), false);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (scroll.contains(mouseX, mouseY)) {
            scroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void clearTransientState() {
        scroll.reset();
        super.clearTransientState();
    }
}

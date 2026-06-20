package op.creditmanager.client.gui.modern;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import op.creditmanager.client.config.ClientConfigManager;
import op.creditmanager.client.core.CreditManager;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemeMode;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.gui.modern.widget.ModernScrollArea;

/** HSV picker plus hex input for the two persisted custom theme colours. */
public class ModernColorPickerScreen extends ModernBaseScreen {
    private final boolean accent;
    private float hue;
    private float saturation;
    private float brightness;
    private TextFieldWidget hexField;
    private final ModernScrollArea pickerScroll = new ModernScrollArea();
    private int pickerX;
    private int pickerY;
    private int applyY;
    private int viewportY;
    private int viewportHeight;

    public ModernColorPickerScreen(CreditManager manager, Screen parent, boolean accent) {
        super(manager, parent, accent ? "Akzent-Farbe" : "Main-Farbe", "settings");
        this.accent = accent;
        float[] hsv = ColorUtil.toHsv(accent ? ClientConfigManager.getCustomAccentColor() : ClientConfigManager.getCustomMainColor());
        hue = hsv[0];
        saturation = hsv[1];
        brightness = hsv[2];
    }

    @Override
    protected void init() {
        super.init();
        clearChildren();
        hexField = ModernUi.configureGuiTextField(new TextFieldWidget(textRenderer, 0, 0, 110, 18, Text.empty()));
        hexField.setMaxLength(7);
        hexField.setText(ColorUtil.toHex(color()));
        addDrawableChild(hexField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderShell(context, mouseX, mouseY);
        ModernThemePalette theme = ModernUi.theme();
        applyY = contentY + contentHeight - 22;
        viewportY = contentY + 6;
        viewportHeight = Math.max(32, applyY - viewportY - 8);
        pickerScroll.setBounds(contentX, viewportY, contentWidth, viewportHeight, 180);
        pickerScroll.tick(mouseX, mouseY);
        pickerX = contentX + 6;
        pickerY = viewportY - pickerScroll.offset();
        int pickerWidth = Math.min(160, Math.max(80, contentWidth - 38));
        int pickerHeight = 120;
        int hexY = pickerY + 154;
        boolean hexVisible = hexY + 18 > viewportY && hexY < viewportY + viewportHeight;
        hexField.setPosition(contentX + 6, hexY);
        hexField.setVisible(hexVisible);
        context.enableScissor(contentX, viewportY, contentX + contentWidth, viewportY + viewportHeight);
        for (int y = 0; y < pickerHeight; y += 4) {
            for (int x = 0; x < pickerWidth; x += 4) {
                float sat = x / (float) Math.max(1, pickerWidth - 1);
                float value = 1.0F - y / (float) Math.max(1, pickerHeight - 1);
                context.fill(pickerX + x, pickerY + y, pickerX + x + 4, pickerY + y + 4, ColorUtil.fromHsv(hue, sat, value));
            }
        }
        int hueX = pickerX + pickerWidth + 8;
        for (int y = 0; y < pickerHeight; y += 3) {
            context.fill(hueX, pickerY + y, hueX + 12, pickerY + y + 3, ColorUtil.fromHsv(y / (float) pickerHeight, 1, 1));
        }
        int markerX = pickerX + Math.round(saturation * (pickerWidth - 1));
        int markerY = pickerY + Math.round((1.0F - brightness) * (pickerHeight - 1));
        context.fill(markerX - 2, markerY - 2, markerX + 3, markerY + 3, ColorUtil.contrastText(color()));
        context.fill(hueX - 2, pickerY + Math.round(hue * pickerHeight) - 1, hueX + 14,
                pickerY + Math.round(hue * pickerHeight) + 2, theme.text);
        ModernUi.card(context, contentX, pickerY + 138, Math.min(180, contentWidth), 38, false);
        ModernUi.drawGuiText(context, textRenderer, "Hex-Farbe", contentX + 7, pickerY + 142, theme.muted);
        context.fill(contentX + 122, pickerY + 153, contentX + 171, pickerY + 169, color());
        context.disableScissor();
        pickerScroll.renderScrollbar(context, mouseX, mouseY);
        ModernUi.button(context, textRenderer, contentX, applyY, Math.min(118, contentWidth), 22, "Übernehmen", theme.buttonPrimary,
                ModernUi.contains(mouseX, mouseY, contentX, applyY, Math.min(118, contentWidth), 22));
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (handleSidebarClick(click)) return true;
        if (pickerScroll.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), pickerX, pickerY, Math.min(160, Math.max(80, contentWidth - 38)), 120)) {
            saturation = (float) Math.max(0, Math.min(1, (click.x() - pickerX) / Math.min(160, Math.max(80, contentWidth - 38))));
            brightness = (float) Math.max(0, Math.min(1, 1 - (click.y() - pickerY) / 120.0));
            hexField.setText(ColorUtil.toHex(color()));
            return true;
        }
        int hueX = pickerX + Math.min(160, Math.max(80, contentWidth - 38)) + 8;
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), hueX, pickerY, 12, 120)) {
            hue = (float) Math.max(0, Math.min(0.999, (click.y() - pickerY) / 120.0));
            hexField.setText(ColorUtil.toHex(color()));
            return true;
        }
        if (click.button() == 0 && ModernUi.contains(click.x(), click.y(), contentX, applyY, Math.min(118, contentWidth), 22)) {
            Integer parsed = ColorUtil.parseHex(hexField.getText());
            if (parsed == null) {
                toastError("Bitte eine Hex-Farbe wie #42D66B eingeben.");
                return true;
            }
            if (accent) ClientConfigManager.setCustomAccentColor(parsed); else ClientConfigManager.setCustomMainColor(parsed);
            ClientConfigManager.setModernThemeMode(ModernThemeMode.CUSTOM);
            toastSuccess("Farbe gespeichert.");
            closeToParent();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (pickerScroll.contains(mouseX, mouseY)) {
            pickerScroll.scroll(verticalAmount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    protected void clearTransientState() {
        pickerScroll.reset();
        super.clearTransientState();
    }

    private int color() {
        return ColorUtil.fromHsv(hue, saturation, brightness);
    }
}

package op.creditmanager.client.gui;

import net.minecraft.client.gui.Click;

/** Shared compact sizing rules for the legacy fixed-size panels. */
public final class GuiScaleUtil {

    private GuiScaleUtil() {
    }

    public static float compactPanelScale(int screenWidth, int screenHeight, int panelWidth, int panelHeight) {
        float widthScale = screenWidth * 0.86F / panelWidth;
        float heightScale = screenHeight * 0.82F / panelHeight;
        return Math.max(0.55F, Math.min(1.0F, Math.min(widthScale, heightScale)));
    }

    public static int centeredX(int screenWidth, float scale, int panelWidth) {
        return Math.round((screenWidth / scale - panelWidth) / 2.0F);
    }

    public static int centeredY(int screenHeight, float scale, int panelHeight) {
        return Math.round((screenHeight / scale - panelHeight) / 2.0F);
    }

    public static int toLayoutCoordinate(int coordinate, float scale) {
        return Math.round(coordinate / scale);
    }

    public static Click toLayoutClick(Click click, float scale) {
        return new Click(click.x() / scale, click.y() / scale, click.buttonInfo());
    }
}

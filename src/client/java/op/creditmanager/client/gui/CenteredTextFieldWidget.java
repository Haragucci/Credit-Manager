package op.creditmanager.client.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

public class CenteredTextFieldWidget extends TextFieldWidget {

    private final int textOffset;

    public CenteredTextFieldWidget(TextRenderer textRenderer, int x, int y, int width, int height, Text message) {
        super(textRenderer, x, y, width, height, message);
        this.textOffset = Math.max(0, (height - 8) / 2);
        setDrawsBackground(false);
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate(0.0F, textOffset);
        super.renderWidget(context, mouseX, mouseY, deltaTicks);
        context.getMatrices().popMatrix();
    }
}

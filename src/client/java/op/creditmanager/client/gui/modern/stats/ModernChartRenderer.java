package op.creditmanager.client.gui.modern.stats;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import op.creditmanager.client.gui.modern.ModernUi;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.util.FormatUtil;

/** Compact, dependency-free charts rendered with Minecraft GUI rectangles. */
public final class ModernChartRenderer {
    private ModernChartRenderer() {
    }

    public static void bars(DrawContext context, TextRenderer renderer, int x, int y, int width, int height,
                            double claims, double debts) {
        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, height, false);
        int plotX = x + 38;
        int plotY = y + 27;
        int plotWidth = Math.max(32, width - 52);
        int plotHeight = Math.max(38, height - 62);
        double scale = niceCeil(Math.max(claims, debts));
        double balance = claims - debts;
        ModernUi.drawGuiText(context, renderer, "Offene Forderungen und Schulden", x + 10, y + 7, theme.muted);
        ModernUi.drawGuiTextRightAligned(context, renderer, (balance >= 0 ? "+" : "") + FormatUtil.formatAmount(balance),
                x + width - 10, y + 7, balance >= 0 ? theme.success : theme.danger);
        if (claims == 0.0 && debts == 0.0) {
            ModernUi.drawCentered(context, renderer, "Keine offenen Einträge", x + width / 2, y + height / 2 - 4, theme.muted);
            return;
        }
        for (int line = 0; line <= 3; line++) {
            int lineY = plotY + Math.round(plotHeight * line / 3.0F);
            context.fill(plotX, lineY, plotX + plotWidth, lineY + 1, ColorUtil.withAlpha(theme.border, line == 3 ? 230 : 100));
            if (line < 3) {
                double value = scale * (3 - line) / 3.0;
                ModernUi.drawGuiTextRightAligned(context, renderer, FormatUtil.formatAmount(value), plotX - 5, lineY - 4, theme.muted);
            }
        }
        int barWidth = Math.max(18, Math.min(62, plotWidth / 4));
        int claimsX = plotX + plotWidth / 4 - barWidth / 2;
        int debtsX = plotX + plotWidth * 3 / 4 - barWidth / 2;
        drawBar(context, claimsX, plotY, plotHeight, barWidth, claims, scale, theme.success);
        drawBar(context, debtsX, plotY, plotHeight, barWidth, debts, scale, theme.danger);
        ModernUi.drawGuiTextCentered(context, renderer, FormatUtil.formatAmount(claims), claimsX + barWidth / 2,
                Math.max(plotY, plotY + plotHeight - (int) Math.round(plotHeight * claims / scale) - 12), theme.success);
        ModernUi.drawGuiTextCentered(context, renderer, FormatUtil.formatAmount(debts), debtsX + barWidth / 2,
                Math.max(plotY, plotY + plotHeight - (int) Math.round(plotHeight * debts / scale) - 12), theme.danger);
        int legendY = y + height - 17;
        context.fill(x + 10, legendY + 2, x + 15, legendY + 7, theme.success);
        ModernUi.drawGuiText(context, renderer, "Forderungen", x + 19, legendY, theme.muted);
        int secondLegendX = x + width / 2 + 2;
        context.fill(secondLegendX, legendY + 2, secondLegendX + 5, legendY + 7, theme.danger);
        ModernUi.drawGuiText(context, renderer, "Schulden", secondLegendX + 9, legendY, theme.muted);
    }

    private static void drawBar(DrawContext context, int x, int y, int height, int width, double value, double scale, int color) {
        int filledHeight = Math.max(value > 0.0 ? 2 : 0, (int) Math.round(height * value / scale));
        context.fill(x + 2, y + 2, x + width + 2, y + height + 2, ColorUtil.withAlpha(ModernUi.theme().shadow, 130));
        context.fill(x, y, x + width, y + height, ColorUtil.withAlpha(color, 45));
        context.fill(x, y + height - filledHeight, x + width, y + height, color);
        context.fill(x, y + height - filledHeight, x + width, y + height - filledHeight + 1, ColorUtil.lighten(color, 0.20F));
    }

    public static double niceCeil(double value) {
        if (value <= 0.0 || !Double.isFinite(value)) return 1.0;
        double exponent = Math.pow(10, Math.floor(Math.log10(value)));
        double normalized = value / exponent;
        double rounded = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return rounded * exponent;
    }

}

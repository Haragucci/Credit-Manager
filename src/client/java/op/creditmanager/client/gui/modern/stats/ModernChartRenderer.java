package op.creditmanager.client.gui.modern.stats;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import op.creditmanager.client.gui.modern.ModernUi;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;
import op.creditmanager.client.util.FormatUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class ModernChartRenderer {
    private static final int TEXT_HEIGHT = 8;
    private static final int MIN_PLOT_WIDTH = 32;
    private static final String FULL_TITLE = "Aktueller offener Stand";
    private static final String COMPACT_TITLE = "Offener Stand";

    private ModernChartRenderer() {
    }

    public static void bars(DrawContext context, TextRenderer renderer, int x, int y, int width, int height,
                            BigInteger claimsMinor, BigInteger debtsMinor) {
        if (width <= 0 || height <= 0) return;

        ModernThemePalette theme = ModernUi.theme();
        ModernUi.card(context, x, y, width, height, false);
        if (width < 28 || height < 36) return;

        BigInteger safeClaims = safeAmount(claimsMinor);
        BigInteger safeDebts = safeAmount(debtsMinor);
        int inset = clamp(width / 36, 6, 10);
        int innerLeft = x + inset;
        int innerRight = x + width - inset;
        int innerBottom = y + height - inset;
        int innerWidth = innerRight - innerLeft;
        if (innerWidth < 16 || innerBottom <= y + inset) return;

        BigInteger balance = safeClaims.subtract(safeDebts);
        String title = fitText(renderer, FULL_TITLE, COMPACT_TITLE, innerWidth);
        String summary = FormatUtil.formatChartAmountMinor(balance, balance.signum() > 0);
        int headerY = y + inset;
        boolean headerInline = ModernUi.getGuiTextWidth(renderer, title) + 8
                + ModernUi.getGuiTextWidth(renderer, summary) <= innerWidth;
        int headerBottom;
        if (headerInline) {
            ModernUi.drawGuiText(context, renderer, title, innerLeft, headerY, theme.muted);
            ModernUi.drawGuiTextRightAligned(context, renderer, summary, innerRight, headerY, summaryColor(balance, theme));
            headerBottom = headerY + TEXT_HEIGHT;
        } else {
            ModernUi.drawGuiText(context, renderer, title, innerLeft, headerY, theme.muted);
            int summaryY = headerY + TEXT_HEIGHT + 2;
            ModernUi.drawGuiTextRightAligned(context, renderer, summary, innerRight, summaryY, summaryColor(balance, theme));
            headerBottom = summaryY + TEXT_HEIGHT;
        }

        if (safeClaims.signum() == 0 && safeDebts.signum() == 0) {
            drawEmptyState(context, renderer, innerLeft, innerRight, headerBottom, innerBottom, theme);
            return;
        }

        BigInteger scale = safeClaims.max(safeDebts);
        int gridSteps = height >= 150 ? 4 : height >= 112 ? 3 : 2;
        List<String> axisLabels = axisLabels(scale, gridSteps);
        int widestAxisLabel = widestLabel(renderer, axisLabels);
        int plotX = innerLeft + widestAxisLabel + 6;
        int plotRight = innerRight;
        int plotWidth = plotRight - plotX;
        if (plotWidth < MIN_PLOT_WIDTH) {
            drawTooNarrowState(context, renderer, innerLeft, innerRight, headerBottom, innerBottom, theme);
            return;
        }

        String claimValue = FormatUtil.formatChartAmountMinor(safeClaims);
        String debtValue = FormatUtil.formatChartAmountMinor(safeDebts);
        int largestValueLabel = Math.max(ModernUi.getGuiTextWidth(renderer, claimValue),
                ModernUi.getGuiTextWidth(renderer, debtValue));
        boolean valueLegend = plotWidth < 2 * (largestValueLabel + 6);
        boolean horizontalLegend = !valueLegend && legendWidth(renderer, "Forderungen")
                + 14 + legendWidth(renderer, "Schulden") <= innerWidth;
        int legendHeight = valueLegend || !horizontalLegend ? TEXT_HEIGHT * 2 + 3 : TEXT_HEIGHT;
        int legendY = innerBottom - legendHeight;
        int categoryY = valueLegend ? -1 : legendY - TEXT_HEIGHT - 4;
        int baseline = valueLegend ? legendY - 5 : categoryY - 5;
        int barTop = headerBottom + 13;
        int plotHeight = baseline - barTop;
        if (plotHeight < 18) {
            drawTooShortState(context, renderer, innerLeft, innerRight, headerBottom, innerBottom, theme);
            return;
        }

        drawGrid(context, renderer, plotX, plotRight, barTop, baseline, gridSteps, axisLabels, headerBottom, theme);

        int barWidth = Math.min(48, Math.max(8, plotWidth / 5));
        int claimsCenter = plotX + plotWidth / 4;
        int debtsCenter = plotX + plotWidth * 3 / 4;
        int claimsX = clamp(claimsCenter - barWidth / 2, plotX, plotRight - barWidth);
        int debtsX = clamp(debtsCenter - barWidth / 2, plotX, plotRight - barWidth);
        int claimsFilledTop = drawBar(context, claimsX, barTop, baseline, barWidth, safeClaims, scale, theme.success, theme);
        int debtsFilledTop = drawBar(context, debtsX, barTop, baseline, barWidth, safeDebts, scale, theme.danger, theme);

        if (valueLegend) {
            drawValueLegend(context, renderer, innerLeft, innerWidth, legendY, claimValue, debtValue, theme);
        } else {
            int middle = plotX + plotWidth / 2;
            drawSmartBarLabel(context, renderer, claimValue, safeClaims, claimsX, barWidth, plotX, middle,
                    barTop, baseline, claimsFilledTop, theme.success, theme);
            drawSmartBarLabel(context, renderer, debtValue, safeDebts, debtsX, barWidth, middle, plotRight,
                    barTop, baseline, debtsFilledTop, theme.danger, theme);
            drawCategoryLabel(context, renderer, "Forderungen", "Ford.", claimsCenter, plotX, middle, categoryY, theme.muted);
            drawCategoryLabel(context, renderer, "Schulden", "Schuld.", debtsCenter, middle, plotRight, categoryY, theme.muted);
            drawLegend(context, renderer, innerLeft, innerWidth, legendY, horizontalLegend, theme);
        }
    }

    private static void drawGrid(DrawContext context, TextRenderer renderer, int plotX, int plotRight, int plotTop,
                                 int baseline, int steps, List<String> labels, int headerBottom,
                                 ModernThemePalette theme) {
        int plotHeight = baseline - plotTop;
        for (int line = 0; line <= steps; line++) {
            int lineY = plotTop + Math.round(plotHeight * line / (float) steps);
            int alpha = line == steps ? 225 : 95;
            context.fill(plotX, lineY, plotRight, lineY + 1, ColorUtil.withAlpha(theme.border, alpha));
            String label = labels.get(line);
            int labelY = clamp(lineY - TEXT_HEIGHT / 2, headerBottom + 2, baseline - TEXT_HEIGHT);
            ModernUi.drawGuiTextRightAligned(context, renderer, label, plotX - 5, labelY, theme.muted);
        }
    }

    private static int drawBar(DrawContext context, int x, int top, int baseline, int width, BigInteger value,
                               BigInteger scale, int color, ModernThemePalette theme) {
        int plotHeight = baseline - top;
        int filledHeight = value.signum() <= 0 ? 0 : Math.max(2, scaledBarHeight(value, scale, plotHeight));
        filledHeight = Math.min(plotHeight, filledHeight);
        int filledTop = baseline - filledHeight;

        context.fill(x + 2, top + 2, x + width + 2, baseline + 2, ColorUtil.withAlpha(theme.shadow, 100));
        context.fill(x, top, x + width, baseline, ColorUtil.withAlpha(color, 42));
        if (filledHeight > 0) {
            context.fill(x, filledTop, x + width, baseline, color);
            context.fill(x, filledTop, x + width, Math.min(baseline, filledTop + 1), ColorUtil.lighten(color, 0.20F));
        }
        return filledTop;
    }

    private static void drawSmartBarLabel(DrawContext context, TextRenderer renderer, String label, BigInteger value,
                                          int barX, int barWidth, int slotLeft, int slotRight, int barTop,
                                          int baseline, int filledTop, int color, ModernThemePalette theme) {
        int textWidth = ModernUi.getGuiTextWidth(renderer, label);
        int labelX = clamp(barX + barWidth / 2 - textWidth / 2, slotLeft + 1, slotRight - textWidth - 1);
        if (value.signum() <= 0) {
            ModernUi.drawGuiText(context, renderer, label, labelX, baseline - TEXT_HEIGHT - 2, theme.muted);
            return;
        }

        int filledHeight = baseline - filledTop;
        if (filledHeight >= TEXT_HEIGHT + 7 && textWidth <= barWidth - 4) {
            ModernUi.drawGuiText(context, renderer, label, labelX, filledTop + 3, ColorUtil.contrastText(color));
            return;
        }

        int labelY = Math.max(barTop - TEXT_HEIGHT - 2, filledTop - TEXT_HEIGHT - 2);
        labelY = Math.max(labelY, barTop - TEXT_HEIGHT - 2);
        context.fill(labelX - 2, labelY - 1, labelX + textWidth + 2, labelY + TEXT_HEIGHT + 1,
                ColorUtil.withAlpha(theme.card, 220));
        ModernUi.drawGuiText(context, renderer, label, labelX, labelY, color);
    }

    private static void drawCategoryLabel(DrawContext context, TextRenderer renderer, String full, String compact,
                                          int center, int slotLeft, int slotRight, int y, int color) {
        String label = fitText(renderer, full, compact, Math.max(1, slotRight - slotLeft - 2));
        int textWidth = ModernUi.getGuiTextWidth(renderer, label);
        int textX = clamp(center - textWidth / 2, slotLeft + 1, slotRight - textWidth - 1);
        ModernUi.drawGuiText(context, renderer, label, textX, y, color);
    }

    private static void drawLegend(DrawContext context, TextRenderer renderer, int x, int width, int y,
                                   boolean horizontal, ModernThemePalette theme) {
        String claims = "Forderungen";
        String debts = "Schulden";
        if (horizontal) {
            int claimsWidth = legendWidth(renderer, claims);
            int debtsWidth = legendWidth(renderer, debts);
            int start = x + (width - claimsWidth - 14 - debtsWidth) / 2;
            drawLegendItem(context, renderer, start, y, claims, theme.success, theme.muted);
            drawLegendItem(context, renderer, start + claimsWidth + 14, y, debts, theme.danger, theme.muted);
            return;
        }
        String first = fitText(renderer, claims, "Ford.", width - 10);
        String second = fitText(renderer, debts, "Schuld.", width - 10);
        drawLegendItem(context, renderer, x, y, first, theme.success, theme.muted);
        drawLegendItem(context, renderer, x, y + TEXT_HEIGHT + 3, second, theme.danger, theme.muted);
    }

    private static void drawValueLegend(DrawContext context, TextRenderer renderer, int x, int width, int y,
                                        String claims, String debts, ModernThemePalette theme) {
        String first = valueLegendText(renderer, "Forderungen", "Ford.", claims, width - 10);
        String second = valueLegendText(renderer, "Schulden", "Schuld.", debts, width - 10);
        drawLegendItem(context, renderer, x, y, first, theme.success, theme.muted);
        drawLegendItem(context, renderer, x, y + TEXT_HEIGHT + 3, second, theme.danger, theme.muted);
    }

    private static void drawLegendItem(DrawContext context, TextRenderer renderer, int x, int y, String label,
                                       int markerColor, int textColor) {
        context.fill(x, y + 2, x + 6, y + 8, markerColor);
        ModernUi.drawGuiText(context, renderer, label, x + 10, y, textColor);
    }

    private static void drawEmptyState(DrawContext context, TextRenderer renderer, int left, int right,
                                       int top, int bottom, ModernThemePalette theme) {
        String message = fitText(renderer, "Keine offenen Einträge", "Keine Einträge", right - left);
        int messageY = clamp((top + bottom - TEXT_HEIGHT) / 2, top + 5, bottom - TEXT_HEIGHT);
        ModernUi.drawGuiTextCentered(context, renderer, message, (left + right) / 2, messageY, theme.muted);
    }

    private static void drawTooNarrowState(DrawContext context, TextRenderer renderer, int left, int right,
                                           int top, int bottom, ModernThemePalette theme) {
        String message = fitText(renderer, "Diagramm ist zu schmal", "Zu schmal", right - left);
        int messageY = clamp((top + bottom - TEXT_HEIGHT) / 2, top + 4, bottom - TEXT_HEIGHT);
        ModernUi.drawGuiTextCentered(context, renderer, message, (left + right) / 2, messageY, theme.muted);
    }

    private static void drawTooShortState(DrawContext context, TextRenderer renderer, int left, int right,
                                          int top, int bottom, ModernThemePalette theme) {
        String message = fitText(renderer, "Diagramm benötigt mehr Höhe", "Mehr Höhe nötig", right - left);
        int messageY = clamp((top + bottom - TEXT_HEIGHT) / 2, top + 4, bottom - TEXT_HEIGHT);
        ModernUi.drawGuiTextCentered(context, renderer, message, (left + right) / 2, messageY, theme.muted);
    }

    private static List<String> axisLabels(BigInteger scale, int steps) {
        List<String> labels = new ArrayList<>(steps + 1);
        for (int line = 0; line <= steps; line++) {
            labels.add(FormatUtil.formatChartAmountMinor(scale.multiply(BigInteger.valueOf(steps - line))
                    .divide(BigInteger.valueOf(steps))));
        }
        return labels;
    }

    private static int widestLabel(TextRenderer renderer, List<String> labels) {
        int widest = 0;
        for (String label : labels) {
            widest = Math.max(widest, ModernUi.getGuiTextWidth(renderer, label));
        }
        return widest;
    }

    private static int legendWidth(TextRenderer renderer, String label) {
        return 10 + ModernUi.getGuiTextWidth(renderer, label);
    }

    private static String valueLegendText(TextRenderer renderer, String full, String compact, String value, int maxWidth) {
        String fullText = full + ": " + value;
        if (ModernUi.getGuiTextWidth(renderer, fullText) <= maxWidth) return fullText;
        String compactText = compact + ": " + value;
        if (ModernUi.getGuiTextWidth(renderer, compactText) <= maxWidth) return compactText;
        return fitText(renderer, value, value, maxWidth);
    }

    private static String fitText(TextRenderer renderer, String preferred, String compact, int maxWidth) {
        if (ModernUi.getGuiTextWidth(renderer, preferred) <= maxWidth) return preferred;
        if (ModernUi.getGuiTextWidth(renderer, compact) <= maxWidth) return compact;
        return ModernUi.trimGuiText(renderer, compact, Math.max(1, maxWidth));
    }

    private static int summaryColor(BigInteger balance, ModernThemePalette theme) {
        if (balance.signum() > 0) return theme.success;
        if (balance.signum() < 0) return theme.danger;
        return theme.muted;
    }

    private static BigInteger safeAmount(BigInteger value) {
        return value != null && value.signum() > 0 ? value : BigInteger.ZERO;
    }

    static int scaledBarHeight(BigInteger value, BigInteger scale, int plotHeight) {
        if (value == null || scale == null || value.signum() <= 0 || scale.signum() <= 0 || plotHeight <= 0) return 0;
        BigInteger height = value.min(scale).multiply(BigInteger.valueOf(plotHeight)).divide(scale);
        return height.min(BigInteger.valueOf(plotHeight)).intValue();
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public static double niceCeil(double value) {
        if (value <= 0.0D || !Double.isFinite(value)) return 1.0D;
        double exponent = Math.pow(10.0D, Math.floor(Math.log10(value)));
        double normalized = value / exponent;
        double rounded = normalized <= 1.0D ? 1.0D : normalized <= 2.0D ? 2.0D : normalized <= 5.0D ? 5.0D : 10.0D;
        double result = rounded * exponent;
        return Double.isFinite(result) ? result : value;
    }
}

package op.creditmanager.client.gui.modern.toast;

import net.minecraft.client.font.TextRenderer;
import op.creditmanager.client.gui.modern.ModernUi;

import java.util.List;
import java.util.function.ToIntFunction;

final class ModernToast {
    final String message;
    final ModernToastType type;
    final long createdAt = System.currentTimeMillis();
    boolean dismissed;
    private int wrappedWidth = Integer.MIN_VALUE;
    private long wrappedFontEpoch = Long.MIN_VALUE;
    private List<String> wrappedLines;

    ModernToast(String message, ModernToastType type) {
        this.message = message == null ? "" : message;
        this.type = type == null ? ModernToastType.INFO : type;
    }

    float visibility(long now) {
        long age = now - createdAt;
        if (dismissed) return 0.0F;
        if (age < 180) return age / 180.0F;
        if (age > 4_000) return Math.max(0.0F, 1.0F - (age - 4_000) / 260.0F);
        return 1.0F;
    }

    List<String> wrappedLines(TextRenderer renderer, int maxWidth, long fontEpoch) {
        if (wrappedLines != null && wrappedWidth == maxWidth && wrappedFontEpoch == fontEpoch) {
            return wrappedLines;
        }
        wrappedLines = ModernToastManager.wrap(message, maxWidth,
                value -> ModernUi.getGuiTextWidth(renderer, value));
        wrappedWidth = maxWidth;
        wrappedFontEpoch = fontEpoch;
        return wrappedLines;
    }

    List<String> wrappedLines(int maxWidth, long fontEpoch, ToIntFunction<String> widthMeasurer) {
        if (wrappedLines != null && wrappedWidth == maxWidth && wrappedFontEpoch == fontEpoch) {
            return wrappedLines;
        }
        wrappedLines = ModernToastManager.wrap(message, maxWidth, widthMeasurer);
        wrappedWidth = maxWidth;
        wrappedFontEpoch = fontEpoch;
        return wrappedLines;
    }
}

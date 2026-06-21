package op.creditmanager.client.gui.modern.toast;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import op.creditmanager.client.gui.modern.ModernUi;
import op.creditmanager.client.gui.modern.theme.ColorUtil;
import op.creditmanager.client.gui.modern.theme.ModernThemePalette;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ModernToastManager {
    private static final ModernToastManager INSTANCE = new ModernToastManager();
    private static final int MAX_TOASTS = 3;
    private final List<ModernToast> toasts = new ArrayList<>();
    private final List<ToastBounds> bounds = new ArrayList<>();

    private record ToastBounds(ModernToast toast, int x, int y, int width, int height) { }

    private ModernToastManager() { }
    public static ModernToastManager getInstance() { return INSTANCE; }

    public synchronized void show(String message, ModernToastType type) {
        if (message == null || message.isBlank()) return;
        long now = System.currentTimeMillis();
        toasts.removeIf(toast -> toast.dismissed || toast.visibility(now) <= 0.0F);
        toasts.addFirst(new ModernToast(message, type));
        while (toasts.size() > MAX_TOASTS) toasts.removeLast();
    }

    public void showSuccess(String message) { show(message, ModernToastType.SUCCESS); }
    public void showError(String message) { show(message, ModernToastType.ERROR); }
    public void showWarning(String message) { show(message, ModernToastType.WARNING); }
    public void showInfo(String message) { show(message, ModernToastType.INFO); }

    public synchronized void render(DrawContext context, TextRenderer renderer, int screenWidth, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        bounds.clear();
        Iterator<ModernToast> iterator = toasts.iterator();
        int y = 12;
        while (iterator.hasNext()) {
            ModernToast toast = iterator.next();
            float visible = toast.visibility(now);
            if (visible <= 0.0F) { iterator.remove(); continue; }
            int width = Math.min(Math.max(156, screenWidth - 24), 360);
            List<String> lines = wrap(renderer, toast.message, width - 22);
            int height = 12 + lines.size() * 10;
            int x = (screenWidth - width) / 2;
            int animatedY = y - Math.round((1.0F - visible) * 38.0F);
            ModernThemePalette theme = ModernUi.theme();
            int accent = switch (toast.type) {
                case SUCCESS -> theme.success;
                case ERROR -> theme.danger;
                case WARNING -> theme.warning;
                case INFO -> theme.accent;
            };
            boolean hovered = ModernUi.contains(mouseX, mouseY, x, animatedY, width, height);
            context.fill(x - 1, animatedY - 1, x + width + 1, animatedY + height + 1, ColorUtil.withAlpha(theme.shadow, 180));
            context.fill(x, animatedY, x + width, animatedY + height, theme.card);
            context.fill(x, animatedY, x + 3, animatedY + height, accent);
            for (int line = 0; line < lines.size(); line++) {
                ModernUi.drawGuiText(context, renderer, lines.get(line), x + 11, animatedY + 7 + line * 10, theme.text);
            }
            ModernUi.drawGuiText(context, renderer, "x", x + width - 14, animatedY + 7, hovered ? accent : theme.muted);
            bounds.add(new ToastBounds(toast, x, animatedY, width, height));
            y += height + 6;
        }
    }

    public synchronized boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        for (ToastBounds bound : bounds) {
            if (mouseX >= bound.x + bound.width - 24 && mouseX < bound.x + bound.width
                    && mouseY >= bound.y && mouseY < bound.y + bound.height) {
                bound.toast.dismissed = true;
                return true;
            }
        }
        return false;
    }

    private static List<String> wrap(TextRenderer renderer, String message, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : message.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && ModernUi.getGuiTextWidth(renderer, candidate) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (ModernUi.getGuiTextWidth(renderer, word) > maxWidth) {
                String remaining = word;
                while (!remaining.isEmpty()) {
                    int end = remaining.length();
                    while (end > 1 && ModernUi.getGuiTextWidth(renderer, remaining.substring(0, end)) > maxWidth) end--;
                    lines.add(remaining.substring(0, end));
                    remaining = remaining.substring(end);
                }
            } else { if (!line.isEmpty()) line.append(' '); line.append(word); }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.isEmpty() ? List.of("") : lines;
    }
}

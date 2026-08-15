package op.creditmanager.client.gui.modern;

final class ModernPaylogToolbarLayout {
    private static final int BUTTON_HEIGHT = 24;
    private static final int BUTTON_GAP = 8;

    private ModernPaylogToolbarLayout() { }

    static Layout calculate(int contentX, int toolbarY, int contentWidth, int contentHeight) {
        int width = Math.max(1, contentWidth);
        if (width >= 230) {
            int manualWidth = Math.min(82, Math.max(64, width / 3));
            ModernLayout.Bounds search = new ModernLayout.Bounds(contentX + 4, toolbarY + 4,
                    Math.max(1, width - manualWidth - 12), BUTTON_HEIGHT);
            ModernLayout.Bounds manual = new ModernLayout.Bounds(contentX + width - manualWidth - 4,
                    toolbarY + 4, manualWidth, BUTTON_HEIGHT);
            ModernLayout.Bounds importer = new ModernLayout.Bounds(manual.x(), toolbarY + 32,
                    manual.width(), BUTTON_HEIGHT);
            ModernLayout.Bounds filter = new ModernLayout.Bounds(contentX + 4, toolbarY + 32,
                    Math.max(1, Math.min(104, width - manualWidth - 16)), BUTTON_HEIGHT);
            return new Layout(search, manual, importer, filter, 60);
        }

        if (width > 1 && contentHeight < 219) {
            int gap = Math.min(BUTTON_GAP, Math.max(0, width - 2));
            int leftWidth = Math.max(1, (width - gap) / 2);
            int rightX = contentX + leftWidth + gap;
            int rightWidth = Math.max(1, contentX + width - rightX);
            ModernLayout.Bounds search = new ModernLayout.Bounds(contentX, toolbarY + 4,
                    leftWidth, BUTTON_HEIGHT);
            ModernLayout.Bounds manual = new ModernLayout.Bounds(rightX, toolbarY + 4,
                    rightWidth, BUTTON_HEIGHT);
            ModernLayout.Bounds importer = new ModernLayout.Bounds(rightX, toolbarY + 32,
                    rightWidth, BUTTON_HEIGHT);
            ModernLayout.Bounds filter = new ModernLayout.Bounds(contentX, toolbarY + 32,
                    leftWidth, BUTTON_HEIGHT);
            return new Layout(search, manual, importer, filter, 60);
        }

        int inset = Math.min(4, Math.max(0, (width - 1) / 2));
        ModernLayout.Bounds search = new ModernLayout.Bounds(contentX + inset, toolbarY + 4,
                Math.max(1, width - inset * 2), BUTTON_HEIGHT);
        var row = ModernLayout.buttonRow(contentX, toolbarY + 32, width, 2, 72,
                BUTTON_HEIGHT, BUTTON_GAP);
        ModernLayout.Bounds manual = row.getFirst();
        ModernLayout.Bounds filter = row.get(1);
        ModernLayout.Bounds importer = new ModernLayout.Bounds(manual.x(),
                manual.y() + BUTTON_HEIGHT + BUTTON_GAP, manual.width(), BUTTON_HEIGHT);
        if (manual.y() != filter.y()) filter = new ModernLayout.Bounds(filter.x(),
                importer.y() + BUTTON_HEIGHT + BUTTON_GAP, filter.width(), filter.height());
        int height = Math.max(importer.bottom(), filter.bottom()) - toolbarY + 4;
        return new Layout(search, manual, importer, filter, height);
    }

    record Layout(ModernLayout.Bounds search, ModernLayout.Bounds manual,
                  ModernLayout.Bounds importer, ModernLayout.Bounds filter, int height) { }
}

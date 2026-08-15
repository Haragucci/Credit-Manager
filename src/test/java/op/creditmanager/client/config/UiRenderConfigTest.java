package op.creditmanager.client.config;

import op.creditmanager.client.gui.modern.theme.ModernThemeMode;
import op.creditmanager.client.storage.db.StorageTestScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiRenderConfigTest {
    private static final List<String> MANAGER_FIELDS = List.of("config", "recoveryRequired", "renderedThemeMode",
            "renderedFontMode", "renderedMainColor", "renderedAccentColor", "renderEpoch", "fontEpoch",
            "renderConfig");

    @TempDir
    Path directory;

    private final Map<Field, Object> previous = new LinkedHashMap<>();
    private StorageTestScope storage;

    @AfterEach
    void restoreState() throws Exception {
        for (Map.Entry<Field, Object> entry : previous.entrySet()) entry.getKey().set(null, entry.getValue());
        if (storage != null) storage.close();
    }

    @Test
    void themeAndFontSettersPublishOneImmutableLockFreeSnapshot() throws Exception {
        for (String name : MANAGER_FIELDS) {
            Field field = ClientConfigManager.class.getDeclaredField(name);
            field.setAccessible(true);
            previous.put(field, field.get(null));
        }
        storage = new StorageTestScope();
        storage.configureExternal(directory);
        field("config").set(null, null);
        field("recoveryRequired").setBoolean(null, false);
        ClientConfigManager.reload();
        UiRenderConfig initial = ClientConfigManager.uiRenderConfig();

        assertTrue(ClientConfigManager.setCustomTheme(0xFF123456, 0xFF65ABCD, ModernThemeMode.CUSTOM));
        UiRenderConfig themed = ClientConfigManager.uiRenderConfig();

        assertNotEquals(initial.epoch(), themed.epoch());
        assertNotEquals(initial.theme().panel, themed.theme().panel);
        assertSame(themed, ClientConfigManager.uiRenderConfig());

        GuiFontMode replacement = themed.fontMode() == GuiFontMode.MOD ? GuiFontMode.MINECRAFT : GuiFontMode.MOD;
        assertTrue(ClientConfigManager.setGuiFontMode(replacement));
        UiRenderConfig fontChanged = ClientConfigManager.uiRenderConfig();

        assertEquals(replacement, fontChanged.fontMode());
        assertEquals(themed.fontEpoch() + 1L, fontChanged.fontEpoch());
        assertTrue(fontChanged.epoch() > themed.epoch());
    }

    private Field field(String name) {
        return previous.keySet().stream().filter(field -> field.getName().equals(name)).findFirst().orElseThrow();
    }
}

package op.creditmanager.client.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class ChatUtil {

    private static final String PREFIX = "§8§lC.M §7» §r";
    private static final String LINE = "§8§m *                                                  *§r";

    public static void msg(String message) {
        send(format(message));
    }

    public static void success(String message) {
        send(format("§a" + message));
    }

    public static void error(String message) {
        send(format("§c" + message));
    }

    public static void info(String message) {
        send(format("§e" + message));
    }

    public static void box(String title) {
        send(format(LINE));
        send(format("  §b§l" + title));
        send(format(LINE));
    }

    public static void boxEnd() {
        send(format(LINE));
    }

    public static void line(String label, String value) {
        send(format("  §7" + label + ": §f" + value));
    }

    public static void separator() {
        send(format(LINE));
    }

    public static void sendSuccess(String message) {
        success(message);
    }

    public static void sendError(String message) {
        error(message);
    }

    public static Text format(String message) {
        return Text.literal(message);
    }

    public static void send(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {

            MutableText finalText = Text.literal(PREFIX).append(text);

            client.player.sendMessage(finalText, false);
        }
    }

    public static void sendRaw(String raw) {
        send(format(raw));
    }

    public static void fehler(String message)            { error(message); }
    public static void erfolg(String message)            { success(message); }
    public static void nachricht(String message)         { msg(message); }
    public static void zeile(String label, String value) { line(label, value); }
}
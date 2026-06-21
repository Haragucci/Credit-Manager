package op.creditmanager.client.gui.modern;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import op.creditmanager.client.model.Payment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentItemResolver {
    private static final Pattern COUNT_PREFIX = Pattern.compile("^\\s*(\\d+)\\s*[x×]\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private PaymentItemResolver() {
    }

    public static List<ItemStack> resolve(Payment payment) {
        if (payment == null) return List.of(new ItemStack(Items.BARRIER));
        List<String> serialized = payment.getItemNbtEntries();
        List<String> descriptions = payment.getItems();
        int count = Math.max(serialized.size(), descriptions.size());
        if (count == 0) count = 1;

        List<ItemStack> stacks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String nbt = index < serialized.size() ? serialized.get(index) : index == 0 ? payment.getItemNbt() : null;
            String description = index < descriptions.size() ? descriptions.get(index) : "Unbekanntes Item";
            stacks.add(resolve(nbt, description));
        }
        return stacks;
    }

    private static ItemStack resolve(String serialized, String description) {
        ItemStack decoded = decodeSerialized(serialized);
        return decoded.isEmpty() ? fallback(description) : decoded;
    }

    private static ItemStack decodeSerialized(String serialized) {
        if (serialized == null || serialized.isBlank()) return ItemStack.EMPTY;
        try {
            NbtCompound nbt = StringNbtReader.readCompound(serialized);
            MinecraftClient client = MinecraftClient.getInstance();
            RegistryWrapper.WrapperLookup lookup = client.player != null ? client.player.getRegistryManager()
                    : client.world != null ? client.world.getRegistryManager() : null;
            if (lookup != null) {
                ItemStack full = ItemStack.CODEC.parse(RegistryOps.of(NbtOps.INSTANCE, lookup), nbt).result().orElse(ItemStack.EMPTY);
                if (!full.isEmpty()) return full;
            }
            String idValue = nbt.getString("id", "");
            if (!idValue.isBlank()) {
                Item item = Registries.ITEM.get(Identifier.of(idValue));
                if (item != Items.AIR) return new ItemStack(item, Math.max(1, nbt.getInt("count", 1)));
            }
        } catch (Exception ignored) {
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack fallback(String rawDescription) {
        String description = rawDescription == null ? "Unbekanntes Item" : rawDescription
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "").trim();
        int count = 1;
        Matcher matcher = COUNT_PREFIX.matcher(description);
        if (matcher.matches()) {
            count = Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
            description = matcher.group(2).trim();
        }
        try {
            Identifier id = Identifier.of(description.contains(":") ? description : "minecraft:" + description.replace(' ', '_'));
            Item item = Registries.ITEM.get(id);
            if (item != Items.AIR) return new ItemStack(item, count);
        } catch (RuntimeException ignored) {
        }
        for (Identifier id : Registries.ITEM.getIds()) {
            Item item = Registries.ITEM.get(id);
            if (item != Items.AIR && item.getName().getString().equalsIgnoreCase(description)) {
                return new ItemStack(item, count);
            }
        }
        ItemStack fallback = new ItemStack(Items.PAPER, count);
        fallback.set(DataComponentTypes.ITEM_NAME, Text.literal(description.isBlank() ? "Unbekanntes Item" : description));
        return fallback;
    }
}

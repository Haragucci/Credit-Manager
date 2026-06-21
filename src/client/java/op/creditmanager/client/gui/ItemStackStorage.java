package op.creditmanager.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;

public final class ItemStackStorage {

    private ItemStackStorage() {
    }

    public static String serialize(ItemStack stack, int requestedCount) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }

        ItemStack copy = stack.copy();
        copy.setCount(Math.max(1, Math.min(requestedCount, stack.getCount())));

        MinecraftClient client = MinecraftClient.getInstance();
        RegistryWrapper.WrapperLookup lookup = client.player != null
                ? client.player.getRegistryManager()
                : client.world != null ? client.world.getRegistryManager() : null;

        if (lookup != null) {
            NbtElement encoded = ItemStack.CODEC.encodeStart(RegistryOps.of(NbtOps.INSTANCE, lookup), copy)
                    .result()
                    .orElse(null);
            if (encoded != null) {
                return encoded.toString();
            }
        }

        Identifier id = Registries.ITEM.getId(copy.getItem());
        return "{id:\"" + id + "\",count:" + copy.getCount() + "}";
    }
}

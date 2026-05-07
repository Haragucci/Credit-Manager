package op.creditmanager.client.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class SkinHeadUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreditManager-Skins");

    private static final Map<String, GameProfile> SKIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> SKIN_LOADING = new ConcurrentHashMap<>();

    private SkinHeadUtil() {
    }

    public static void setzeSkin(ItemStack kopf, String spielerName, Runnable refreshGui) {
        if (kopf == null || spielerName == null || spielerName.isBlank()) return;

        String name = spielerName.trim();
        String key = name.toLowerCase(Locale.ROOT);

        GameProfile cachedProfile = SKIN_CACHE.get(key);

        if (cachedProfile != null) {
            kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(cachedProfile));
            return;
        }

        kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofDynamic(name));
        ladeSkinAsync(name, refreshGui);
    }

    public static void versteckeProfilTooltip(ItemStack kopf) {
        SequencedSet<ComponentType<?>> hidden = new LinkedHashSet<>();
        hidden.add(DataComponentTypes.PROFILE);

        kopf.set(
                DataComponentTypes.TOOLTIP_DISPLAY,
                new TooltipDisplayComponent(false, hidden)
        );
    }

    private static void ladeSkinAsync(String name, Runnable refreshGui) {
        String key = name.toLowerCase(Locale.ROOT);

        if (SKIN_CACHE.containsKey(key)) return;
        if (SKIN_LOADING.putIfAbsent(key, true) != null) return;

        CompletableFuture.runAsync(() -> {
            try {
                GameProfile profile = fetchMojangProfileMitSkin(name);

                if (profile != null) {
                    SKIN_CACHE.put(key, profile);

                    MinecraftClient client = MinecraftClient.getInstance();
                    client.execute(() -> {
                        if (refreshGui != null) {
                            refreshGui.run();
                        }
                    });

                    LOGGER.info("[CreditManager] Skin geladen für {}", name);
                } else {
                    LOGGER.warn("[CreditManager] Kein Skin-Profil gefunden für {}", name);
                }

            } catch (Exception e) {
                LOGGER.error("[CreditManager] Skin konnte nicht geladen werden für {}", name, e);
            } finally {
                SKIN_LOADING.remove(key);
            }
        });
    }

    private static GameProfile fetchMojangProfileMitSkin(String name) {
        try {
            String uuidUrl = "https://api.mojang.com/users/profiles/minecraft/" + name;

            JsonObject uuidJson;
            try (InputStreamReader reader = new InputStreamReader(
                    URI.create(uuidUrl).toURL().openStream(),
                    StandardCharsets.UTF_8
            )) {
                uuidJson = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (!uuidJson.has("id") || !uuidJson.has("name")) {
                return null;
            }

            String rawUuid = uuidJson.get("id").getAsString();
            String realName = uuidJson.get("name").getAsString();

            UUID uuid = UUID.fromString(
                    rawUuid.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                            "$1-$2-$3-$4-$5"
                    )
            );

            String profileUrl = "https://sessionserver.mojang.com/session/minecraft/profile/"
                    + rawUuid
                    + "?unsigned=false";

            JsonObject profileJson;
            try (InputStreamReader reader = new InputStreamReader(
                    URI.create(profileUrl).toURL().openStream(),
                    StandardCharsets.UTF_8
            )) {
                profileJson = JsonParser.parseReader(reader).getAsJsonObject();
            }

            GameProfile profile = new GameProfile(uuid, realName);

            if (profileJson.has("properties")) {
                for (var element : profileJson.getAsJsonArray("properties")) {
                    JsonObject property = element.getAsJsonObject();

                    String propertyName = property.get("name").getAsString();
                    String value = property.get("value").getAsString();
                    String signature = property.has("signature")
                            ? property.get("signature").getAsString()
                            : null;

                    if (signature != null) {
                        profile.properties().put(propertyName, new Property(propertyName, value, signature));
                    } else {
                        profile.properties().put(propertyName, new Property(propertyName, value));
                    }
                }
            }

            return profile;

        } catch (Exception e) {
            LOGGER.error("[CreditManager] Mojang-Profil-Abfrage fehlgeschlagen für {}", name, e);
            return null;
        }
    }
}
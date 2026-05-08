package op.creditmanager.client.gui;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.component.type.TooltipDisplayComponent;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;

public final class SkinHeadUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger("CreditManager-Skins");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Path CACHE_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("creditmanager")
            .resolve("skin-cache.json");

    private static final long NAME_FAILED_COOLDOWN_MS = 24L * 60L * 60L * 1000L;
    private static final long GLOBAL_RATE_LIMIT_COOLDOWN_MS = 15L * 60L * 1000L;
    private static final long REQUEST_PAUSE_MS = 7000L;

    private static final Pattern VALID_MINECRAFT_NAME =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final Pattern DEV_PLAYER_NAME =
            Pattern.compile("^Player\\d+$", Pattern.CASE_INSENSITIVE);

    // Runtime-Lookup: Spielername, lowercase Name, UUID usw. -> Skin
    private static final Map<String, CachedSkin> SKIN_ALIASES = new ConcurrentHashMap<>();

    // Persistenter Cache: UUID -> Skin, wird so auch gespeichert
    private static final Map<UUID, CachedSkin> SKIN_BY_UUID = new ConcurrentHashMap<>();

    private static final Map<String, Boolean> SKIN_LOADING = new ConcurrentHashMap<>();
    private static final Map<String, Long> SKIN_FAILED_UNTIL = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SKIN_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "CreditManager-SkinLoader");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile boolean cacheLoaded = false;
    private static volatile long globalRateLimitUntil = 0L;

    private SkinHeadUtil() {
    }

    public static void setzeSkin(ItemStack kopf, String spielerName, Runnable refreshGui) {
        if (kopf == null || spielerName == null || spielerName.isBlank()) return;

        ensureCacheLoaded();

        String name = spielerName.trim();
        String key = normalizeKey(name);

        CachedSkin cachedSkin = SKIN_ALIASES.get(key);

        if (cachedSkin != null) {
            GameProfile profile = cachedSkin.toGameProfile();

            if (profile != null) {
                kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(profile));
                return;
            }
        }

        kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(createFallbackProfile(name)));

        if (!darfSkinOnlineLaden(name)) {
            return;
        }

        ladeSkinAsync(name, refreshGui);
    }

    public static void versteckeProfilTooltip(ItemStack kopf) {
        if (kopf == null) return;

        SequencedSet<ComponentType<?>> hidden = new LinkedHashSet<>();
        hidden.add(DataComponentTypes.PROFILE);

        kopf.set(
                DataComponentTypes.TOOLTIP_DISPLAY,
                new TooltipDisplayComponent(false, hidden)
        );
    }

    private static boolean darfSkinOnlineLaden(String name) {
        if (name == null || name.isBlank()) return false;

        String clean = name.trim();

        if (!VALID_MINECRAFT_NAME.matcher(clean).matches()) {
            return false;
        }

        if (DEV_PLAYER_NAME.matcher(clean).matches()) {
            return false;
        }

        return true;
    }

    private static void ladeSkinAsync(String name, Runnable refreshGui) {
        String requestKey = normalizeKey(name);
        long now = System.currentTimeMillis();

        if (SKIN_ALIASES.containsKey(requestKey)) {
            return;
        }

        Long failedUntil = SKIN_FAILED_UNTIL.get(requestKey);
        if (failedUntil != null && failedUntil > now) {
            return;
        }

        if (globalRateLimitUntil > now) {
            return;
        }

        if (SKIN_LOADING.putIfAbsent(requestKey, true) != null) {
            return;
        }

        SKIN_EXECUTOR.execute(() -> {
            try {
                GameProfile profile = fetchMojangProfileMitSkin(name);

                if (profile == null) {
                    markNameFailed(requestKey);
                    LOGGER.debug("[CreditManager] Kein Skin-Profil gefunden für {}", name);
                    return;
                }

                CachedSkin cachedSkin = CachedSkin.fromGameProfile(profile);

                if (cachedSkin == null) {
                    markNameFailed(requestKey);
                    LOGGER.debug("[CreditManager] Skin-Profil hatte keine Textur für {}", name);
                    return;
                }

                putSkinInRuntimeAndCanonicalCache(requestKey, cachedSkin);

                SKIN_FAILED_UNTIL.remove(requestKey);
                SKIN_FAILED_UNTIL.remove(normalizeKey(cachedSkin.name()));
                SKIN_FAILED_UNTIL.remove(normalizeKey(cachedSkin.uuid().toString()));

                saveCache();

                net.minecraft.client.MinecraftClient client =
                        net.minecraft.client.MinecraftClient.getInstance();

                client.execute(() -> {
                    if (refreshGui != null) {
                        refreshGui.run();
                    }
                });

                LOGGER.info("[CreditManager] Skin gespeichert für {}", cachedSkin.name());

            } catch (RateLimitException e) {
                globalRateLimitUntil = System.currentTimeMillis() + GLOBAL_RATE_LIMIT_COOLDOWN_MS;
                LOGGER.warn("[CreditManager] Mojang Rate Limit. Skin-Requests pausiert für 15 Minuten.");

            } catch (ForbiddenException e) {
                markNameFailed(requestKey);
                LOGGER.debug("[CreditManager] Skin-Request abgelehnt für {}: {}", name, e.getMessage());

            } catch (Exception e) {
                markNameFailed(requestKey);
                LOGGER.debug("[CreditManager] Skin konnte nicht geladen werden für {}: {}", name, e.getMessage());

            } finally {
                SKIN_LOADING.remove(requestKey);

                try {
                    Thread.sleep(REQUEST_PAUSE_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    private static GameProfile fetchMojangProfileMitSkin(String name) {
        JsonObject uuidJson = requestJson(
                "https://api.mojang.com/users/profiles/minecraft/" + name
        );

        if (uuidJson == null || !uuidJson.has("id") || !uuidJson.has("name")) {
            return null;
        }

        String rawUuid = uuidJson.get("id").getAsString();
        String realName = uuidJson.get("name").getAsString();

        UUID uuid = uuidFromMojangId(rawUuid);

        JsonObject profileJson = requestJson(
                "https://sessionserver.mojang.com/session/minecraft/profile/"
                        + rawUuid
                        + "?unsigned=false"
        );

        if (profileJson == null || !profileJson.has("properties")) {
            return null;
        }

        Multimap<String, Property> properties = LinkedHashMultimap.create();

        for (JsonElement element : profileJson.getAsJsonArray("properties")) {
            if (!element.isJsonObject()) continue;

            JsonObject property = element.getAsJsonObject();

            if (!property.has("name") || !property.has("value")) continue;

            String propertyName = property.get("name").getAsString();
            String value = property.get("value").getAsString();
            String signature = property.has("signature") && !property.get("signature").isJsonNull()
                    ? property.get("signature").getAsString()
                    : null;

            if (signature != null && !signature.isBlank()) {
                properties.put(propertyName, new Property(propertyName, value, signature));
            } else {
                properties.put(propertyName, new Property(propertyName, value));
            }
        }

        return new GameProfile(uuid, realName, new PropertyMap(properties));
    }

    private static JsonObject requestJson(String url) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);

            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            );
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,de;q=0.8");
            connection.setRequestProperty("Connection", "close");

            int code = connection.getResponseCode();

            if (code == 204 || code == 404) {
                return null;
            }

            if (code == 429) {
                throw new RateLimitException();
            }

            if (code == 403) {
                throw new ForbiddenException("HTTP 403");
            }

            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code);
            }

            try (InputStreamReader reader = new InputStreamReader(
                    connection.getInputStream(),
                    StandardCharsets.UTF_8
            )) {
                JsonElement element = JsonParser.parseReader(reader);

                if (!element.isJsonObject()) {
                    return null;
                }

                return element.getAsJsonObject();
            }

        } catch (RateLimitException | ForbiddenException e) {
            throw e;

        } catch (Exception e) {
            throw new RuntimeException(e);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static synchronized void ensureCacheLoaded() {
        if (cacheLoaded) return;
        cacheLoaded = true;

        try {
            if (!Files.exists(CACHE_FILE)) return;

            try (Reader reader = Files.newBufferedReader(CACHE_FILE, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);

                if (!parsed.isJsonObject()) return;

                JsonObject root = parsed.getAsJsonObject();

                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;

                    CachedSkin loaded = CachedSkin.fromJson(entry.getValue().getAsJsonObject());

                    if (loaded == null || loaded.toGameProfile() == null) continue;

                    putSkinInRuntimeAndCanonicalCache(entry.getKey(), loaded);
                }
            }

            saveCache();

            LOGGER.info(
                    "[CreditManager] {} eindeutige Skin-Cache-Einträge geladen.",
                    SKIN_BY_UUID.size()
            );

        } catch (Exception e) {
            LOGGER.warn("[CreditManager] Skin-Cache konnte nicht geladen werden: {}", e.getMessage());
        }
    }

    private static synchronized void saveCache() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());

            JsonObject root = new JsonObject();

            for (CachedSkin cachedSkin : SKIN_BY_UUID.values()) {
                if (cachedSkin == null || cachedSkin.uuid() == null) continue;

                root.add(cachedSkin.uuid().toString(), cachedSkin.toJson());
            }

            try (Writer writer = Files.newBufferedWriter(CACHE_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }

        } catch (Exception e) {
            LOGGER.warn("[CreditManager] Skin-Cache konnte nicht gespeichert werden: {}", e.getMessage());
        }
    }

    private static void putSkinInRuntimeAndCanonicalCache(String requestedKeyOrName, CachedSkin cachedSkin) {
        if (cachedSkin == null || cachedSkin.uuid() == null) return;

        CachedSkin old = SKIN_BY_UUID.get(cachedSkin.uuid());

        if (old == null || cachedSkin.fetchedAt() >= old.fetchedAt()) {
            SKIN_BY_UUID.put(cachedSkin.uuid(), cachedSkin);
        } else {
            cachedSkin = old;
        }

        putAlias(requestedKeyOrName, cachedSkin);
        putAlias(cachedSkin.name(), cachedSkin);
        putAlias(cachedSkin.name().toLowerCase(Locale.ROOT), cachedSkin);
        putAlias(cachedSkin.uuid().toString(), cachedSkin);
        putAlias(cachedSkin.uuid().toString().replace("-", ""), cachedSkin);
    }

    private static void putAlias(String keyOrName, CachedSkin cachedSkin) {
        if (keyOrName == null || keyOrName.isBlank() || cachedSkin == null) return;
        SKIN_ALIASES.put(normalizeKey(keyOrName), cachedSkin);
    }

    private static void markNameFailed(String key) {
        SKIN_FAILED_UNTIL.put(key, System.currentTimeMillis() + NAME_FAILED_COOLDOWN_MS);
    }

    private static GameProfile createFallbackProfile(String name) {
        UUID offlineUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)
        );

        return new GameProfile(offlineUuid, name);
    }

    private static UUID uuidFromMojangId(String rawUuid) {
        return UUID.fromString(
                rawUuid.replaceFirst(
                        "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                        "$1-$2-$3-$4-$5"
                )
        );
    }

    private static String normalizeKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record CachedSkin(UUID uuid, String name, String value, String signature, long fetchedAt) {

        private static CachedSkin fromGameProfile(GameProfile profile) {
            if (profile == null || profile.id() == null || profile.name() == null) {
                return null;
            }

            Property texture = profile.properties()
                    .get("textures")
                    .stream()
                    .findFirst()
                    .orElse(null);

            if (texture == null || texture.value() == null || texture.value().isBlank()) {
                return null;
            }

            return new CachedSkin(
                    profile.id(),
                    profile.name(),
                    texture.value(),
                    texture.signature(),
                    System.currentTimeMillis()
            );
        }

        private GameProfile toGameProfile() {
            if (uuid == null || name == null || name.isBlank()) {
                return null;
            }

            if (value == null || value.isBlank()) {
                return null;
            }

            Multimap<String, Property> properties = LinkedHashMultimap.create();

            if (signature != null && !signature.isBlank()) {
                properties.put("textures", new Property("textures", value, signature));
            } else {
                properties.put("textures", new Property("textures", value));
            }

            return new GameProfile(uuid, name, new PropertyMap(properties));
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();

            json.addProperty("uuid", uuid.toString());
            json.addProperty("name", name);
            json.addProperty("value", value);

            if (signature != null && !signature.isBlank()) {
                json.addProperty("signature", signature);
            } else {
                json.add("signature", JsonNull.INSTANCE);
            }

            json.addProperty("fetchedAt", fetchedAt);

            return json;
        }

        private static CachedSkin fromJson(JsonObject json) {
            try {
                if (!json.has("uuid")
                        || !json.has("name")
                        || !json.has("value")
                        || !json.has("fetchedAt")) {
                    return null;
                }

                UUID uuid = UUID.fromString(json.get("uuid").getAsString());
                String name = json.get("name").getAsString();
                String value = json.get("value").getAsString();

                String signature = json.has("signature") && !json.get("signature").isJsonNull()
                        ? json.get("signature").getAsString()
                        : null;

                long fetchedAt = json.get("fetchedAt").getAsLong();

                return new CachedSkin(uuid, name, value, signature, fetchedAt);

            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static final class RateLimitException extends RuntimeException {
    }

    private static final class ForbiddenException extends RuntimeException {
        private ForbiddenException(String message) {
            super(message);
        }
    }
}
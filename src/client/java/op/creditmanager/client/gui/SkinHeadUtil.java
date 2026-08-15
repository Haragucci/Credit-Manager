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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final int MAX_CACHED_SKINS = 256;
    private static final long CACHE_SAVE_DEBOUNCE_MS = 750L;

    private static final Pattern VALID_MINECRAFT_NAME =
            Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private static final Pattern DEV_PLAYER_NAME =
            Pattern.compile("^Player\\d+$", Pattern.CASE_INSENSITIVE);

    private static final Map<String, CachedSkin> SKIN_ALIASES = new ConcurrentHashMap<>();

    private static final Map<UUID, CachedSkin> SKIN_BY_UUID = new ConcurrentHashMap<>();

    private static final Map<String, Boolean> SKIN_LOADING = new ConcurrentHashMap<>();
    private static final Map<String, Long> SKIN_FAILED_UNTIL = new ConcurrentHashMap<>();
    private static final Map<String, JsonElement> PRESERVED_INVALID_CACHE_ENTRIES = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SKIN_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "CreditManager-SkinLoader");
                thread.setDaemon(true);
                return thread;
            });

    private static final ScheduledExecutorService CACHE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "CreditManager-SkinCache");
                thread.setDaemon(true);
                return thread;
            });

    private static final Object CACHE_STATE_LOCK = new Object();
    private static final List<Runnable> CACHE_REFRESH_CALLBACKS = new ArrayList<>();
    private static final CoalescingSaveScheduler CACHE_SAVES = new CoalescingSaveScheduler(
            CACHE_EXECUTOR, CACHE_SAVE_DEBOUNCE_MS, SkinHeadUtil::saveCacheSnapshot);
    private static volatile CacheLoadState cacheLoadState = CacheLoadState.UNLOADED;
    private static volatile boolean shuttingDown;
    private static volatile boolean cacheRewriteAllowed = true;
    private static volatile long globalRateLimitUntil = 0L;

    private enum CacheLoadState {
        UNLOADED,
        LOADING,
        LOADED
    }

    private SkinHeadUtil() {
    }

    public static void setzeSkin(ItemStack kopf, String spielerName, Runnable refreshGui) {
        if (kopf == null || spielerName == null || spielerName.isBlank()) return;

        initializeAsync();

        String name = spielerName.trim();
        String key = normalizeKey(name);

        if (applyCachedSkin(kopf, SKIN_ALIASES.get(key))) return;

        kopf.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(createFallbackProfile(name)));

        if (!cacheReady(refreshGui)) return;

        if (applyCachedSkin(kopf, SKIN_ALIASES.get(key))) return;

        if (!darfSkinOnlineLaden(name)) {
            return;
        }

        ladeSkinAsync(name, refreshGui);
    }

    public static void initializeAsync() {
        synchronized (CACHE_STATE_LOCK) {
            if (cacheLoadState != CacheLoadState.UNLOADED || shuttingDown) return;
            cacheLoadState = CacheLoadState.LOADING;
            try {
                CACHE_EXECUTOR.execute(SkinHeadUtil::loadCache);
            } catch (RejectedExecutionException exception) {
                cacheLoadState = CacheLoadState.LOADED;
            }
        }
    }

    public static void shutdown() {
        shutdownAndAwait(Duration.ofSeconds(4L));
    }

    public static boolean shutdownAndAwait(Duration timeout) {
        long budgetNanos = timeout == null ? 0L : Math.max(0L, timeout.toNanos());
        long startedAt = System.nanoTime();
        shuttingDown = true;
        SKIN_EXECUTOR.shutdownNow();
        boolean networkStopped = false;
        try {
            networkStopped = SKIN_EXECUTOR.awaitTermination(remainingNanos(startedAt, budgetNanos),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        java.util.concurrent.Future<Boolean> flush = CACHE_SAVES.flushAsync();
        boolean flushed = flush == null;
        if (flush != null) {
            try {
                flushed = Boolean.TRUE.equals(flush.get(remainingNanos(startedAt, budgetNanos),
                        TimeUnit.NANOSECONDS));
            } catch (Exception exception) {
                LOGGER.warn("[CreditManager] Ausstehender Skin-Cache konnte beim Shutdown nicht vollständig geschrieben werden: {}",
                        exception.getMessage());
            }
        }
        CACHE_SAVES.close();
        CACHE_EXECUTOR.shutdown();
        boolean cacheStopped = false;
        try {
            cacheStopped = CACHE_EXECUTOR.awaitTermination(remainingNanos(startedAt, budgetNanos),
                    TimeUnit.NANOSECONDS);
            if (!cacheStopped) CACHE_EXECUTOR.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            CACHE_EXECUTOR.shutdownNow();
        }
        return networkStopped && flushed && cacheStopped;
    }

    private static long remainingNanos(long startedAt, long budgetNanos) {
        return Math.max(0L, budgetNanos - Math.max(0L, System.nanoTime() - startedAt));
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
        if (shuttingDown) return;
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

        try {
            SKIN_EXECUTOR.execute(() -> {
            try {
                GameProfile profile = fetchMojangProfileMitSkin(name);

                if (shuttingDown) return;

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

                requestCacheSave();

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
        } catch (RejectedExecutionException exception) {
            SKIN_LOADING.remove(requestKey);
        }
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

    private static void loadCache() {
        boolean shouldPersistCanonicalCache = false;
        try {
            if (Files.exists(CACHE_FILE)) {
                try (Reader reader = Files.newBufferedReader(CACHE_FILE, StandardCharsets.UTF_8)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (parsed.isJsonObject()) {
                        JsonObject root = parsed.getAsJsonObject();
                        boolean allEntriesValid = true;
                        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                            if (!entry.getValue().isJsonObject()) {
                                allEntriesValid = false;
                                PRESERVED_INVALID_CACHE_ENTRIES.put(entry.getKey(), entry.getValue().deepCopy());
                                continue;
                            }
                            CachedSkin loaded = CachedSkin.fromJson(entry.getValue().getAsJsonObject());
                            if (loaded == null || loaded.toGameProfile() == null) {
                                allEntriesValid = false;
                                PRESERVED_INVALID_CACHE_ENTRIES.put(entry.getKey(), entry.getValue().deepCopy());
                                continue;
                            }
                            putSkinInRuntimeAndCanonicalCache(entry.getKey(), loaded);
                        }
                        shouldPersistCanonicalCache = allEntriesValid;
                    } else {
                        cacheRewriteAllowed = false;
                    }
                }
            }
            LOGGER.info(
                    "[CreditManager] {} eindeutige Skin-Cache-Einträge geladen.",
                    SKIN_BY_UUID.size()
            );
        } catch (Exception e) {
            cacheRewriteAllowed = false;
            LOGGER.warn("[CreditManager] Skin-Cache konnte nicht geladen werden: {}", e.getMessage());
        } finally {
            completeCacheLoad();
        }
        if (shouldPersistCanonicalCache) requestCacheSave();
    }

    private static void requestCacheSave() {
        CACHE_SAVES.request();
    }

    private static boolean saveCacheSnapshot() {
        if (!cacheRewriteAllowed) return false;
        Path temporary = CACHE_FILE.resolveSibling(CACHE_FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            JsonObject root = new JsonObject();
            for (CachedSkin cachedSkin : SKIN_BY_UUID.values()) {
                if (cachedSkin == null || cachedSkin.uuid() == null) continue;
                root.add(cachedSkin.uuid().toString(), cachedSkin.toJson());
            }
            for (Map.Entry<String, JsonElement> entry : PRESERVED_INVALID_CACHE_ENTRIES.entrySet()) {
                root.add(entry.getKey(), entry.getValue().deepCopy());
            }
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, CACHE_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, CACHE_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("[CreditManager] Skin-Cache konnte nicht gespeichert werden: {}", e.getMessage());
            return false;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean applyCachedSkin(ItemStack head, CachedSkin cachedSkin) {
        if (cachedSkin == null) return false;
        GameProfile profile = cachedSkin.toGameProfile();
        if (profile == null) return false;
        head.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(profile));
        return true;
    }

    private static boolean cacheReady(Runnable refreshGui) {
        synchronized (CACHE_STATE_LOCK) {
            if (cacheLoadState == CacheLoadState.LOADED) return true;
            if (refreshGui != null) CACHE_REFRESH_CALLBACKS.add(refreshGui);
            return false;
        }
    }

    private static void completeCacheLoad() {
        List<Runnable> callbacks;
        synchronized (CACHE_STATE_LOCK) {
            cacheLoadState = CacheLoadState.LOADED;
            callbacks = List.copyOf(CACHE_REFRESH_CALLBACKS);
            CACHE_REFRESH_CALLBACKS.clear();
        }
        if (callbacks.isEmpty()) return;
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        client.execute(() -> {
            for (Runnable callback : callbacks) {
                try {
                    callback.run();
                } catch (RuntimeException exception) {
                    LOGGER.debug("[CreditManager] Skin-Cache-Refresh fehlgeschlagen: {}", exception.getMessage());
                }
            }
        });
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
        pruneCache();
    }

    private static void pruneCache() {
        while (SKIN_BY_UUID.size() > MAX_CACHED_SKINS) {
            UUID oldest = SKIN_BY_UUID.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().fetchedAt()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (oldest == null) break;
            SKIN_BY_UUID.remove(oldest);
        }
        SKIN_ALIASES.entrySet().removeIf(entry -> !SKIN_BY_UUID.containsKey(entry.getValue().uuid()));
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

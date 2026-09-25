package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Client only record of eaten foods, kept in the local config folder.
 * Progress is stored per server (or per singleplayer world), so joining a vanilla
 * server that does not have this mod installed still works and keeps its own list.
 *
 * <p>An entry has one of two origins: it was recorded live by this mod, or it was
 * backfilled from the vanilla "used" statistic, which covers everything eaten before the mod
 * was installed. Backfilled entries are kept apart so the screen can show them differently -
 * a statistic also counts uses that did not actually finish, so they are only a hint.</p>
 */
public final class ClientFoodLogData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(FoodLogMod.MODID);

    private static Set<ResourceLocation> eaten = new HashSet<>();
    private static Set<ResourceLocation> imported = new HashSet<>();
    private static String currentKey;
    private static int version;

    private ClientFoodLogData() {
    }

    /**
     * Called from the client tick loop; reloads the file when the joined world changed.
     */
    public static void refreshContext() {
        String key = resolveKey();
        if (key == null) {
            if (currentKey != null) {
                currentKey = null;
                eaten = new HashSet<>();
                imported = new HashSet<>();
                version++;
            }
            return;
        }
        if (!key.equals(currentKey)) {
            currentKey = key;
            loadFrom(fileFor(key));
            version++;
        }
    }

    /** The identifier of the world currently being played, or null when not in one. */
    public static String getContextKey() {
        return currentKey;
    }

    private static String resolveKey() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            return "server_" + sanitize(server.ip);
        }
        if (minecraft.hasSingleplayerServer() && minecraft.getSingleplayerServer() != null) {
            return "world_" + sanitize(minecraft.getSingleplayerServer().getWorldData().getLevelName());
        }
        return null;
    }

    private static String sanitize(String raw) {
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static Path fileFor(String key) {
        return DIRECTORY.resolve(key + ".json");
    }

    private static void loadFrom(Path file) {
        eaten = new HashSet<>();
        imported = new HashSet<>();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            eaten = readSet(object, "eaten");
            imported = readSet(object, "imported");
        } catch (Exception exception) {
            FoodLogMod.LOGGER.error("Could not read food log file {}", file, exception);
        }
    }

    private static Set<ResourceLocation> readSet(JsonObject root, String name) {
        Set<ResourceLocation> result = new HashSet<>();
        JsonArray array = root.getAsJsonArray(name);
        if (array == null) {
            return result;
        }
        for (JsonElement element : array) {
            ResourceLocation id = ResourceLocation.tryParse(element.getAsString());
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private static void writeFile(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.add("eaten", toArray(eaten));
            root.add("imported", toArray(imported));
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            FoodLogMod.LOGGER.error("Could not write food log file {}", file, exception);
        }
    }

    private static JsonArray toArray(Set<ResourceLocation> ids) {
        JsonArray array = new JsonArray();
        ids.stream().map(ResourceLocation::toString).sorted().forEach(array::add);
        return array;
    }

    private static void persist() {
        if (currentKey != null) {
            writeFile(fileFor(currentKey));
        }
    }

    public static boolean isEaten(ResourceLocation id) {
        return eaten.contains(id) || imported.contains(id);
    }

    /** True when this food is only known from the vanilla statistic, not recorded live. */
    public static boolean isImported(ResourceLocation id) {
        return !eaten.contains(id) && imported.contains(id);
    }

    public static int getVersion() {
        return version;
    }

    /**
     * @return true when this food had not been recorded before
     */
    public static boolean markEaten(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return false;
        }
        // Recording it live supersedes an earlier guess made from the vanilla statistic.
        if (!eaten.add(id) && !imported.remove(id)) {
            return false;
        }
        version++;
        persist();
        return true;
    }

    /**
     * Backfills foods found in the vanilla "used" statistic.
     *
     * @return how many entries were added
     */
    public static int importAll(Collection<ResourceLocation> ids) {
        int added = 0;
        for (ResourceLocation id : ids) {
            if (eaten.contains(id) || imported.contains(id)) {
                continue;
            }
            imported.add(id);
            added++;
        }
        if (added > 0) {
            version++;
            persist();
        }
        return added;
    }
}
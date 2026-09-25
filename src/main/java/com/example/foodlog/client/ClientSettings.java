package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Global preferences, kept next to the per server food logs.
 *
 * Unlike the food logs these are not split per world, because they describe how the player
 * wants the interface to behave rather than what has been eaten.
 */
public final class ClientSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve(FoodLogMod.MODID).resolve("settings.json");

    private static boolean loaded;
    private static boolean lighting;
    private static boolean autoEat;
    private static Integer takeX;
    private static Integer takeY;

    private ClientSettings() {
    }

    /** Whether uneaten food is eaten on its own once the hunger bar has room for it. */
    public static boolean isAutoEatEnabled() {
        load();
        return autoEat;
    }

    public static void setAutoEatEnabled(boolean value) {
        load();
        if (autoEat == value) {
            return;
        }
        autoEat = value;
        save();
    }

    /** Whether uneaten food inside containers is outlined. Off until turned on. */
    public static boolean isLightingEnabled() {
        load();
        return lighting;
    }

    public static void setLightingEnabled(boolean value) {
        load();
        if (lighting == value) {
            return;
        }
        lighting = value;
        save();
    }

    /** Where the player dragged the take button to, or null while it sits at its default. */
    public static Integer getTakeButtonX() {
        load();
        return takeX;
    }

    public static Integer getTakeButtonY() {
        load();
        return takeY;
    }

    public static void setTakeButtonPosition(int x, int y) {
        load();
        takeX = x;
        takeY = y;
        save();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            JsonElement value = object.get("lighting");
            if (value != null && value.isJsonPrimitive()) {
                lighting = value.getAsBoolean();
            }
            JsonElement eat = object.get("autoEat");
            if (eat != null && eat.isJsonPrimitive()) {
                autoEat = eat.getAsBoolean();
            }
            takeX = readInt(object, "takeX");
            takeY = readInt(object, "takeY");
        } catch (Exception exception) {
            FoodLogMod.LOGGER.error("Could not read food log settings {}", FILE, exception);
        }
    }

    private static Integer readInt(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : null;
    }

    private static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("lighting", lighting);
            root.addProperty("autoEat", autoEat);
            if (takeX != null && takeY != null) {
                root.addProperty("takeX", takeX);
                root.addProperty("takeY", takeY);
            }
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception exception) {
            FoodLogMod.LOGGER.error("Could not write food log settings {}", FILE, exception);
        }
    }
}

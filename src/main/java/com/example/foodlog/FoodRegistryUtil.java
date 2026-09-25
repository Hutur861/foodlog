package com.example.foodlog;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans the item registry for every edible item, so vanilla food and food added by
 * other mods are both covered without any hard coded list.
 */
public final class FoodRegistryUtil {

    private static List<ResourceLocation> cachedFoodIds;

    private FoodRegistryUtil() {
    }

    /**
     * Cached because the screen rebuilds its list on every keystroke.
     */
    public static List<ResourceLocation> getAllFoodIds() {
        if (cachedFoodIds == null) {
            cachedFoodIds = scanFoodIds();
        }
        return cachedFoodIds;
    }

    /**
     * Asks the stack instead of {@code Item#isEdible()}, because Forge lets a mod hand out the
     * food data per stack. Food declared that way looks inedible on the item and would be
     * missing from the list even though it can be eaten.
     */
    public static boolean isFood(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        try {
            return stack.getFoodProperties(null) != null;
        } catch (RuntimeException exception) {
            // Third party food data may not accept a missing entity.
            return stack.getItem().isEdible();
        }
    }

    public static boolean isFood(Item item) {
        return isFood(new ItemStack(item));
    }

    /** Drops the cached list, for example after a datapack reload changed the tags. */
    public static void clearCache() {
        cachedFoodIds = null;
    }

    private static List<ResourceLocation> scanFoodIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (!isFood(item)) {
                continue;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(ids);
    }
}
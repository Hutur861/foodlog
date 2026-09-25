package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.example.foodlog.FoodRegistryUtil;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IIngredientAliasRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes JEI's search box able to single out the food that has not been eaten yet.
 *
 * <p>JEI lets a plugin attach extra search words to an ingredient, which is the only hook that
 * reaches the search box, so every uneaten food gets {@value #UNEATEN_ALIAS} as an alias and
 * typing it lists exactly those. JEI matches search text as a substring, so the leading
 * {@code %} is only there to read like a command - it can be left out. It also means the alias
 * keeps working whatever JEI's search mode for creative tabs (which reserves {@code %}) is set
 * to, as long as that mode stays at its default of disabled.</p>
 *
 * <p>Aliases can only be handed over while JEI is starting up, which happens on joining a world
 * and again on every later join, so the list reflects the log as of the last join. Eating
 * something new adds it to the log right away but only drops it from this search after the
 * next join.</p>
 *
 * <p>JEI is optional: nothing here is touched unless JEI is installed and scanning for plugins,
 * and the rest of the mod has no dependency on it.</p>
 */
@JeiPlugin
public final class FoodLogJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = new ResourceLocation(FoodLogMod.MODID, "jei");
    private static final String UNEATEN_ALIAS = "%未吃";

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerIngredientAliases(IIngredientAliasRegistration registration) {
        // JEI starts before the first client tick, so the world may not be picked up yet.
        ClientFoodLogData.refreshContext();
        List<ItemStack> uneaten = new ArrayList<>();
        for (ResourceLocation id : FoodRegistryUtil.getAllFoodIds()) {
            if (ClientFoodLogData.isEaten(id)) {
                continue;
            }
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null) {
                uneaten.add(new ItemStack(item));
            }
        }
        if (!uneaten.isEmpty()) {
            registration.addAliases(VanillaTypes.ITEM_STACK, uneaten, UNEATEN_ALIAS);
        }
    }
}

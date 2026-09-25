package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.example.foodlog.FoodRegistryUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Outlines uneaten food inside an open container and dims everything else, switched with
 * {@code /foodlog lighting}.
 *
 * Drawing happens in {@link ContainerScreenEvent.Render.Foreground}. Forge fires it from
 * {@code AbstractContainerScreen#render}, right after the labels and while the pose is still
 * translated by the GUI origin, so {@code Slot#x}/{@code Slot#y} can be used unchanged. Items
 * are already on screen at that point, and tooltips come later, so the dimming does not hide
 * what a slot contains.
 *
 * Going through slots covers vanilla chests and every modded container built on a menu, and
 * because Refined Storage's grid screen also extends {@code AbstractContainerScreen} the
 * event reaches it too. Its items are not slots though, so they get their own pass in
 * {@link RefinedStorageCompat}.
 */
@Mod.EventBusSubscriber(modid = FoodLogMod.MODID, value = Dist.CLIENT)
public final class ContainerHighlighter {

    private static final int SLOT_SIZE = 16;
    private static final int COLOR_OUTLINE = 0xFFFFC400;
    /** Roughly 60% black: enough to push an item back without hiding what it is. */
    private static final int COLOR_DIM = 0x99000000;

    private ContainerHighlighter() {
    }

    @SubscribeEvent
    public static void onRenderForeground(ContainerScreenEvent.Render.Foreground event) {
        if (!ClientSettings.isLightingEnabled()) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (isUneatenFood(stack)) {
                outline(graphics, slot.x, slot.y);
            } else {
                dim(graphics, slot.x, slot.y);
            }
        }
        RefinedStorageCompat.decorateGrid(screen, graphics);
    }

    static boolean isUneatenFood(ItemStack stack) {
        if (stack.isEmpty() || !FoodRegistryUtil.isFood(stack)) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && !ClientFoodLogData.isEaten(id);
    }

    /** Draws a one pixel frame around a 16x16 slot, at the slot's own coordinates. */
    static void outline(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + SLOT_SIZE + 1, y, COLOR_OUTLINE);
        graphics.fill(x - 1, y + SLOT_SIZE, x + SLOT_SIZE + 1, y + SLOT_SIZE + 1, COLOR_OUTLINE);
        graphics.fill(x - 1, y, x, y + SLOT_SIZE, COLOR_OUTLINE);
        graphics.fill(x + SLOT_SIZE, y, x + SLOT_SIZE + 1, y + SLOT_SIZE, COLOR_OUTLINE);
    }

    /** Covers an occupied but irrelevant slot with a translucent black square. */
    static void dim(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, COLOR_DIM);
    }
}

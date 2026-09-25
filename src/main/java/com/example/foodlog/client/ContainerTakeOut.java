package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ContainerScreenEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Adds the "take one of each" button to every container screen.
 *
 * <p>The button is a normal widget, so the screen routes clicks to it, but it is painted from
 * {@link ContainerScreenEvent.Render.Foreground} instead of the usual widget pass: a widget is
 * drawn before the slot contents, and this button sits in a corner that is usually covered by the
 * player's own inventory slots, so it has to be on top. The event is fired while the pose is still
 * translated by the GUI origin, which is why the render call undoes that translation to work in
 * the button's own screen coordinates. The same pass also drives the button's drag, which the
 * screen does not forward.</p>
 *
 * <p>Taking items is done by sending ordinary slot clicks, exactly as if the player clicked
 * them, so it works on servers that do not have this mod installed. Refined Storage grids keep
 * their items outside the menu, so they are not covered by this.</p>
 */
@Mod.EventBusSubscriber(modid = FoodLogMod.MODID, value = Dist.CLIENT)
public final class ContainerTakeOut {

    private static final int MARGIN = 4;

    private ContainerTakeOut() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        // A grid screen keeps its items outside the menu, so it has no container slots to find.
        if (!hasContainerSlots(screen) && !RefinedStorageCompat.isGridScreen(screen)) {
            return;
        }
        Integer savedX = ClientSettings.getTakeButtonX();
        Integer savedY = ClientSettings.getTakeButtonY();
        int x = savedX != null ? savedX : screen.getGuiLeft() + MARGIN;
        int y = savedY != null ? savedY : screen.getGuiTop() + screen.getYSize() - TakeOutButton.HEIGHT - MARGIN;
        event.addListener(new TakeOutButton(x, y, () -> takeOneOfEach(screen)));
    }

    @SubscribeEvent
    public static void onRenderForeground(ContainerScreenEvent.Render.Foreground event) {
        AbstractContainerScreen<?> screen = event.getContainerScreen();
        TakeOutButton button = find(screen);
        if (button == null) {
            return;
        }
        GuiGraphics graphics = event.getGuiGraphics();
        button.followMouse(event.getMouseX(), event.getMouseY());
        graphics.pose().pushPose();
        graphics.pose().translate(-screen.getGuiLeft(), -screen.getGuiTop(), 0.0F);
        button.render(graphics, event.getMouseX(), event.getMouseY(), 0.0F);
        graphics.pose().popPose();
    }

    private static TakeOutButton find(AbstractContainerScreen<?> screen) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof TakeOutButton button) {
                return button;
            }
        }
        return null;
    }

    /** False for the player's own inventory, which has nothing to take out of. */
    private static boolean hasContainerSlots(AbstractContainerScreen<?> screen) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container != inventory) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves one item of every uneaten food from the container into the player's inventory.
     *
     * <p>Vanilla has no single click that takes exactly one item, so each food is a three click
     * sequence: pick the whole stack up, right click an empty inventory slot to drop one there,
     * then click the container slot again to put the rest back. That needs a free inventory slot
     * per food, and the loop stops when they run out.</p>
     *
     * <p>A Refined Storage grid is not made of menu slots and cannot be clicked that way at all,
     * so it goes through its own request instead. That one already means "one item into the
     * inventory", which needs no free slot to be picked in advance - the server simply declines
     * anything that will not fit.</p>
     */
    private static void takeOneOfEach(AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        LocalPlayer player = minecraft.player;
        if (gameMode == null || player == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        // Anything on the cursor would be dropped into the first slot that gets clicked.
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (RefinedStorageCompat.isGridScreen(screen)) {
            // Nothing can hold more distinct new items than the inventory has room for.
            RefinedStorageCompat.takeOneOfEach(screen, inventory.items.size());
            return;
        }
        for (Slot slot : menu.slots) {
            if (slot.container == inventory || !ContainerHighlighter.isUneatenFood(slot.getItem())) {
                continue;
            }
            int target = findEmptyInventorySlot(menu, inventory);
            if (target < 0) {
                return;
            }
            gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, player);
            gameMode.handleInventoryMouseClick(menu.containerId, target, 1, ClickType.PICKUP, player);
            gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.PICKUP, player);
        }
    }

    private static int findEmptyInventorySlot(AbstractContainerMenu menu, Inventory inventory) {
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getItem().isEmpty()) {
                return slot.index;
            }
        }
        return -1;
    }
}

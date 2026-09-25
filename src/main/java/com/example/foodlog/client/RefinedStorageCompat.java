package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Refined Storage support: outlining uneaten food in a grid, and taking it out.
 *
 * Refined Storage paints its grid items itself instead of using slots, so their positions have
 * to be derived from the grid layout and they cannot be clicked the way a container slot is.
 * Everything is read reflectively because Refined Storage is optional: when it is missing the
 * lookups fail once and this class then does nothing, which keeps the mod free of any compile or
 * runtime dependency on it.
 *
 * The layout below was read from the bytecode of Refined Storage 1.12.4's
 * {@code GridScreen#renderForeground}: the item area starts at (8, 19) inside the GUI, cells
 * are 18 pixels apart nine to a row, and the first visible entry is {@code offset * 9}. A
 * future version that changes this makes the highlight wrong rather than crashing, which is
 * why the geometry is kept in one place.
 */
final class RefinedStorageCompat {

    private static final String GRID_SCREEN = "com.refinedmods.refinedstorage.screen.grid.GridScreen";
    private static final String GRID_VIEW = "com.refinedmods.refinedstorage.screen.grid.view.IGridView";
    private static final String GRID_STACK = "com.refinedmods.refinedstorage.screen.grid.stack.IGridStack";
    private static final String GRID_ITEM_PULL = "com.refinedmods.refinedstorage.network.grid.GridItemPullMessage";
    private static final String NETWORK_HANDLER = "com.refinedmods.refinedstorage.network.NetworkHandler";
    private static final String RS = "com.refinedmods.refinedstorage.RS";

    private static final int COLUMNS = 9;
    private static final int CELL = 18;
    private static final int ORIGIN_X = 8;
    private static final int ORIGIN_Y = 19;

    /**
     * Refined Storage's {@code IItemGridHandler#EXTRACT_SHIFT}. It only decides where the item
     * goes - into the player's inventory rather than onto the cursor - and on its own still means
     * a whole stack, because the handler's default amount is a full stack size.
     */
    private static final int EXTRACT_SHIFT = 4;

    /** Refined Storage's {@code IItemGridHandler#EXTRACT_SINGLE}: reduce the amount to one. */
    private static final int EXTRACT_SINGLE = 2;

    /** One item, straight into the player's inventory - the gesture the take-out button performs. */
    private static final int TAKE_ONE = EXTRACT_SINGLE | EXTRACT_SHIFT;

    private static boolean resolved;
    private static Class<?> gridScreenClass;
    private static Method getView;
    private static Method getStacks;
    private static Method getIngredient;
    private static Method getVisibleRows;
    private static Method getCurrentOffset;
    private static Method getId;
    private static Constructor<?> gridItemPull;
    private static Field networkHandler;
    private static Method sendToServer;

    private RefinedStorageCompat() {
    }

    /** True for a grid screen, whose items live outside the menu and need their own handling. */
    static boolean isGridScreen(AbstractContainerScreen<?> screen) {
        return resolve() && gridScreenClass.isInstance(screen);
    }

    static void decorateGrid(AbstractContainerScreen<?> screen, GuiGraphics graphics) {
        if (!isGridScreen(screen)) {
            return;
        }
        try {
            Object view = getView.invoke(screen);
            if (view == null) {
                return;
            }
            List<?> stacks = (List<?>) getStacks.invoke(view);
            if (stacks == null || stacks.isEmpty()) {
                return;
            }
            int start = (Integer) getCurrentOffset.invoke(screen) * COLUMNS;
            int visible = (Integer) getVisibleRows.invoke(screen) * COLUMNS;
            for (int i = 0; i < visible; i++) {
                int index = start + i;
                if (index >= stacks.size()) {
                    return;
                }
                int x = ORIGIN_X + (i % COLUMNS) * CELL;
                int y = ORIGIN_Y + (i / COLUMNS) * CELL;
                // A fluid entry is not an ItemStack, so it is never uneaten food.
                if (getIngredient.invoke(stacks.get(index)) instanceof ItemStack stack
                        && ContainerHighlighter.isUneatenFood(stack)) {
                    ContainerHighlighter.outline(graphics, x, y);
                } else {
                    ContainerHighlighter.dim(graphics, x, y);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Refined Storage changed its internals; stop trying for this session.
            FoodLogMod.LOGGER.warn("Refined Storage grid highlighting is unavailable", exception);
            gridScreenClass = null;
        }
    }

    /**
     * Asks the server for one of every uneaten food the grid is showing.
     *
     * <p>One request per entry rather than one per food, because the grid is the only thing that
     * knows what it is displaying. The server refuses anything that would not fit in the
     * inventory, so over-asking is harmless, and {@code limit} just avoids a burst of pointless
     * requests when the storage holds far more food than the player could ever carry.</p>
     *
     * @return how many requests were sent
     */
    static int takeOneOfEach(AbstractContainerScreen<?> screen, int limit) {
        if (!isGridScreen(screen)) {
            return 0;
        }
        int sent = 0;
        try {
            Object view = getView.invoke(screen);
            if (view == null) {
                return 0;
            }
            List<?> stacks = (List<?>) getStacks.invoke(view);
            if (stacks == null) {
                return 0;
            }
            Object handler = networkHandler.get(null);
            for (Object entry : stacks) {
                if (sent >= limit) {
                    break;
                }
                if (entry == null) {
                    continue;
                }
                if (!(getIngredient.invoke(entry) instanceof ItemStack stack)
                        || !ContainerHighlighter.isUneatenFood(stack)) {
                    continue;
                }
                sendToServer.invoke(handler, gridItemPull.newInstance(getId.invoke(entry), TAKE_ONE));
                sent++;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            FoodLogMod.LOGGER.warn("Refined Storage grid take-out is unavailable", exception);
            gridScreenClass = null;
            return 0;
        }
        return sent;
    }

    private static boolean resolve() {
        if (resolved) {
            return gridScreenClass != null;
        }
        resolved = true;
        try {
            gridScreenClass = Class.forName(GRID_SCREEN);
            getView = gridScreenClass.getMethod("getView");
            getVisibleRows = gridScreenClass.getMethod("getVisibleRows");
            getCurrentOffset = gridScreenClass.getMethod("getCurrentOffset");
            Class<?> gridStackClass = Class.forName(GRID_STACK);
            getStacks = Class.forName(GRID_VIEW).getMethod("getStacks");
            getIngredient = gridStackClass.getMethod("getIngredient");
            getId = gridStackClass.getMethod("getId");
            gridItemPull = Class.forName(GRID_ITEM_PULL).getConstructor(UUID.class, int.class);
            networkHandler = Class.forName(RS).getField("NETWORK_HANDLER");
            sendToServer = Class.forName(NETWORK_HANDLER).getMethod("sendToServer", Object.class);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException exception) {
            gridScreenClass = null;
        }
        return gridScreenClass != null;
    }
}

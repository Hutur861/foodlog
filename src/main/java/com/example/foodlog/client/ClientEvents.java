package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.example.foodlog.FoodRegistryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RecipesUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Client only. Everything here relies on state the client already has, so a server that
 * does not have this mod installed still works.
 *
 * Note: LivingEntityUseItemEvent.Finish is not usable for this, because vanilla only calls
 * completeUsingItem on the server side. Instead we watch the locally synced "is using item"
 * flag and confirm the consumption by the item count dropping.
 */
@Mod.EventBusSubscriber(modid = FoodLogMod.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private static final int CONFIRM_TIMEOUT_TICKS = 40;
    /**
     * A use has to last this long before its item is worth watching. The client predicts a bite
     * on its own, so a use the server then refuses still shows up here for a tick or two; arming
     * on one of those would turn any later move of the item into a recorded meal. Real bites run
     * for the item's whole use duration, which is a good deal longer.
     */
    private static final int ARM_TICKS = 3;
    /** Round trip for the stat table; the reply is handled on the client thread. */
    private static final int SCAN_DELAY_TICKS = 5;
    /** A server that answers with an empty table gets asked again a few times. */
    private static final int MAX_IMPORT_ATTEMPTS = 3;
    /**
     * Extra packets during the first seconds of a session can upset other mods that run their
     * own login handshake (voice chat for instance), so the automatic request waits until the
     * session has settled. The import button bypasses this.
     */
    private static final int AUTO_REQUEST_DELAY_TICKS = 200;

    private static Item watchedItem;
    private static int watchedCount;
    private static int watchedTicks;
    /** How long the current use has lasted; see {@link #ARM_TICKS}. */
    private static int useTicks;

    private static String importedKey;
    private static int importAttempts;
    private static int nextScanTick;
    private static int lastTickCount;
    private static boolean notifyWhenEmpty;

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null) {
            reset();
            clearImportState();
            ClientFoodLogData.refreshContext();
            return;
        }
        // Dying or changing dimension resets the tick counter and makes the server resend
        // the whole table, so the import gets another chance.
        if (player.tickCount < lastTickCount) {
            importedKey = null;
            importAttempts = 0;
            nextScanTick = 0;
        }
        lastTickCount = player.tickCount;

        if (player.tickCount % 20 == 0) {
            ClientFoodLogData.refreshContext();
        }
        if (player.tickCount >= nextScanTick) {
            pollVanillaRecords(minecraft, player);
        }

        watchItemUse(player);

        while (KeyBindings.OPEN_FOOD_LOG.consumeClick()) {
            if (minecraft.screen == null) {
                ClientFoodLogData.refreshContext();
                minecraft.setScreen(new FoodLogScreen());
            }
        }
    }

    /**
     * Backfills what was eaten before this mod was installed, once per joined world.
     *
     * <p>The vanilla "used" statistic survives a mod being installed, but it is a push only
     * protocol: the client starts with an empty table and the server answers a
     * {@code REQUEST_STATS} command with everything it knows (this is what the vanilla
     * statistics screen does). So we ask first, then read the reply a few ticks later, which
     * also works on servers that do not have this mod installed.</p>
     */
    private static void pollVanillaRecords(Minecraft minecraft, LocalPlayer player) {
        String key = ClientFoodLogData.getContextKey();
        if (key == null || key.equals(importedKey)) {
            nextScanTick = 0;
            return;
        }
        if (nextScanTick == 0) {
            if (player.tickCount < AUTO_REQUEST_DELAY_TICKS) {
                return;
            }
            requestStats(minecraft);
            nextScanTick = player.tickCount + SCAN_DELAY_TICKS;
            return;
        }
        int added = importVanillaRecords(player);
        if (added > 0) {
            player.displayClientMessage(Component.translatable("foodlog.import.done", added), false);
            finishImport(key);
            return;
        }
        if (++importAttempts < MAX_IMPORT_ATTEMPTS) {
            requestStats(minecraft);
            nextScanTick = player.tickCount + SCAN_DELAY_TICKS;
            return;
        }
        if (notifyWhenEmpty) {
            player.displayClientMessage(Component.translatable("foodlog.import.none"), false);
        }
        finishImport(key);
    }

    private static void finishImport(String key) {
        importedKey = key;
        importAttempts = 0;
        nextScanTick = 0;
        notifyWhenEmpty = false;
    }

    private static void requestStats(Minecraft minecraft) {
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().send(
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
        }
    }

    /**
     * Asks the server for the full statistic table and rescans it, for the import button.
     */
    public static void requestImport() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) {
            return;
        }
        importedKey = null;
        importAttempts = 0;
        notifyWhenEmpty = true;
        requestStats(minecraft);
        nextScanTick = player.tickCount + SCAN_DELAY_TICKS;
    }

    /**
     * Looks up every known food in the client's copy of the vanilla statistic table.
     *
     * @return how many foods were added to the log
     */
    public static int importVanillaRecords(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        StatsCounter stats = player.getStats();
        List<ResourceLocation> found = new ArrayList<>();
        for (ResourceLocation id : FoodRegistryUtil.getAllFoodIds()) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && stats.getValue(Stats.ITEM_USED.get(item)) > 0) {
                found.add(id);
            }
        }
        int added = ClientFoodLogData.importAll(found);
        return added;
    }

    private static void clearImportState() {
        importedKey = null;
        importAttempts = 0;
        nextScanTick = 0;
        lastTickCount = 0;
        notifyWhenEmpty = false;
    }

    /**
     * Part of the classification reads conventional item tags, which a datapack reload can
     * change, so the caches are dropped whenever the server resends them.
     */
    @SubscribeEvent
    public static void onRecipesUpdated(RecipesUpdatedEvent event) {
        FoodRegistryUtil.clearCache();
        FoodCategories.clearCache();
    }

    private static void watchItemUse(LocalPlayer player) {
        ItemStack using = player.isUsingItem() ? player.getUseItem() : ItemStack.EMPTY;

        if (!using.isEmpty() && FoodRegistryUtil.isFood(using)) {
            useTicks++;
            // A use the server refuses is still predicted locally for a tick, so a use this
            // short says nothing; arming on it would let any later move of the item pass for a
            // meal. A real bite runs for the item's whole use duration, which is far longer.
            if (useTicks < ARM_TICKS) {
                return;
            }
            if (watchedItem != using.getItem()) {
                watchedItem = using.getItem();
                watchedCount = countInMenu(player, watchedItem);
                watchedTicks = 0;
            }
            return;
        }
        useTicks = 0;

        if (watchedItem == null) {
            return;
        }
        if (countInMenu(player, watchedItem) < watchedCount) {
            Item eaten = watchedItem;
            reset();
            if (ClientFoodLogData.markEaten(eaten)) {
                player.displayClientMessage(
                        Component.translatable("foodlog.new_food", new ItemStack(eaten).getHoverName()), true);
            }
            return;
        }
        if (++watchedTicks > CONFIRM_TIMEOUT_TICKS) {
            reset();
        }
    }

    /**
     * Total of this item across everything the open menu reaches. Counting the whole menu rather
     * than just the player's own slots matters because a stack can leave the inventory without
     * having been eaten, for instance when it is put into an open container.
     */
    private static int countInMenu(LocalPlayer player, Item item) {
        int total = 0;
        for (Slot slot : player.containerMenu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void reset() {
        watchedItem = null;
        watchedCount = 0;
        watchedTicks = 0;
        useTicks = 0;
    }
}
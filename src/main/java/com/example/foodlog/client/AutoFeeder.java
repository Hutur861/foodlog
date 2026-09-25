package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.example.foodlog.FoodRegistryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;

/**
 * Eats uneaten food by itself, the way a feeding upgrade would.
 *
 * <p>Vanilla cannot eat anything that is not in the hand, so a bite is exactly what a player
 * would do: swap the food into the selected hotbar slot with an ordinary number key exchange,
 * use it, then exchange the two slots back. Because an exchange only trades two slots, the
 * second one returns the leftover food to where it came from and puts whatever the player was
 * holding back in the hand, so the inventory looks untouched afterwards. The server only ever
 * sees normal clicks, so none of this needs the mod on the server.</p>
 *
 * <p>The use key is held down for the length of the bite, because vanilla drops a bite whose key
 * is not held; see {@link #holdUseKey}.</p>
 *
 * <p>Whether a food may be eaten right now is asked of the item itself, exactly the way vanilla
 * asks it ({@code Player#canEat(FoodProperties#canAlwaysEat)}). Ordinary vanilla food does need an
 * unfilled hunger bar, and that is what ends a plain run by itself, but mods hand out foods that
 * may always be eaten and a modpack is free to change the rule, so a full hunger bar is never
 * assumed to be the only reason to stop. There is deliberately no fallback that records a food
 * without eating it. The least filling food is taken first, which fits the most different foods
 * into one hunger bar, and the sources are whatever is in the open menu - the player's own
 * inventory in the world, plus a container's slots while one is open.</p>
 *
 * <p>The automatic half only runs with nothing open, so opening a container never starts a bite
 * on its own; the command is what asks for the container's food. Either way a bite goes through
 * the ordinary use path, so {@code ClientEvents} logs it like any other.</p>
 */
@Mod.EventBusSubscriber(modid = FoodLogMod.MODID, value = Dist.CLIENT)
public final class AutoFeeder {

    private static final int IDLE = 0;
    /** The food is on its way into the hand; the bite has not started yet. */
    private static final int SWAPPING = 1;
    /** The bite is running. The server confirms it by taking the item away. */
    private static final int EATING = 2;

    /** A bite lasts 32 ticks; the rest only covers the round trip for the result. */
    private static final int MAX_EAT_TICKS = 80;
    /** Lets the exchange land before it is judged to have failed. */
    private static final int SWAP_GRACE_TICKS = 5;
    /**
     * How many ticks in a row the game has to report the bite as over before that is believed.
     * The "using item" flag travels over the network, so a single reading can lag behind a bite
     * that is in fact still running.
     */
    private static final int USE_LOST_TICKS = 3;
    /** Also lets the food log finish recording the previous bite before picking the next one. */
    private static final int COOLDOWN_TICKS = 10;

    /** Foods that could not be eaten here, skipped until the world is left. */
    private static final Set<Item> UNREACHABLE = new HashSet<>();

    private static int phase = IDLE;
    private static int ticks;
    private static int cooldown;
    private static Item target;
    private static int containerId = -1;
    private static int slotIndex = -1;
    private static int hotbar = -1;
    private static boolean swapped;
    private static int startCount;
    /** True while the use key is held down for a bite; see {@link #holdUseKey}. */
    private static boolean holdingUse;
    /** Ticks in a row the game has reported the bite as over. */
    private static int useLostTicks;

    /** Set by {@link #eatNow}, which works with a screen open and ignores the setting. */
    private static boolean burst;
    private static int eaten;
    /** Bites this run started at all, and how many of them the game refused. */
    private static int attempts;
    private static int rejected;

    private AutoFeeder() {
    }

    public static boolean isEnabled() {
        return ClientSettings.isAutoEatEnabled();
    }

    /**
     * Turns the automatic half on or off. A bite that is already running is left to finish,
     * because stopping halfway would leave the food in the hand.
     */
    public static void setEnabled(boolean value) {
        ClientSettings.setAutoEatEnabled(value);
    }

    /** Eats every uneaten food the hunger bar has room for, once, wherever they are. */
    public static void eatNow() {
        burst = true;
        eaten = 0;
        attempts = 0;
        rejected = 0;
        cooldown = 0;
        // Asking again is a fresh attempt, so foods an earlier run gave up on get retried.
        UNREACHABLE.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            releaseKeyIfBiteOver(Minecraft.getInstance().player);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            reset();
            burst = false;
            eaten = 0;
            UNREACHABLE.clear();
            return;
        }
        if (phase != IDLE) {
            advance(player, minecraft);
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (!burst && !(isEnabled() && minecraft.screen == null)) {
            return;
        }
        // Never interrupt something the player is already holding down.
        if (player.isUsingItem() || player.isDeadOrDying()) {
            return;
        }
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        AbstractContainerMenu menu = player.containerMenu;
        // Anything on the cursor would be dropped into the first slot that gets clicked.
        if (gameMode == null || !menu.getCarried().isEmpty()) {
            return;
        }
        Slot slot = findCandidate(player, menu);
        if (slot == null) {
            finish(player, menu);
            return;
        }
        start(player, gameMode, menu, slot);
    }

    private static void start(LocalPlayer player, MultiPlayerGameMode gameMode,
            AbstractContainerMenu menu, Slot slot) {
        target = slot.getItem().getItem();
        containerId = menu.containerId;
        slotIndex = slot.index;
        hotbar = player.getInventory().selected;
        ticks = 0;
        attempts++;
        FoodLogMod.LOGGER.info("Auto eat: taking {} from slot {} (main hand {})",
                idOf(target), slotIndex, isMainHand(player, slot));
        if (isMainHand(player, slot)) {
            swapped = false;
            begin(player, gameMode);
            return;
        }
        swapped = true;
        gameMode.handleInventoryMouseClick(containerId, slotIndex, hotbar, ClickType.SWAP, player);
        phase = SWAPPING;
    }

    private static void advance(LocalPlayer player, Minecraft minecraft) {
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (gameMode == null) {
            reset();
            return;
        }
        ticks++;
        if (phase == SWAPPING) {
            if (player.containerMenu.containerId == containerId && player.getMainHandItem().is(target)) {
                begin(player, gameMode);
            } else if (ticks > SWAP_GRACE_TICKS) {
                FoodLogMod.LOGGER.info("Auto eat: {} never landed in the hand (slot {}, {} ticks)",
                        idOf(target), slotIndex, ticks);
                abandon(player, gameMode);
            }
            return;
        }
        // The server confirms the bite by shrinking the hand stack, or by emptying it. Waiting
        // for that rather than for a fixed time keeps the exchange back from racing the server.
        ItemStack hand = player.getMainHandItem();
        boolean consumed = hand.isEmpty() || hand.getCount() < startCount || !hand.is(target);
        if (!consumed) {
            useLostTicks = player.isUsingItem() ? 0 : useLostTicks + 1;
            if (useLostTicks < USE_LOST_TICKS && ticks <= MAX_EAT_TICKS) {
                return;
            }
        }
        restore(player, gameMode);
        if (consumed) {
            eaten++;
            FoodLogMod.LOGGER.info("Auto eat: ate {}", idOf(target));
        } else {
            rejected++;
            FoodLogMod.LOGGER.info("Auto eat: {} was not eaten after {} ticks (using item {})",
                    idOf(target), ticks, player.isUsingItem());
            UNREACHABLE.add(target);
        }
        reset();
        cooldown = COOLDOWN_TICKS;
    }

    private static void begin(LocalPlayer player, MultiPlayerGameMode gameMode) {
        startCount = player.getMainHandItem().getCount();
        useLostTicks = 0;
        // A bite only survives while the use key is held: Minecraft calls releaseUsingItem as soon
        // as the player is using an item while the key is up, so a bite started without it is
        // dropped on the very next tick without eating anything.
        holdUseKey(true);
        gameMode.useItem(player, InteractionHand.MAIN_HAND);
        // The use is started locally, so this is known right away: the game refused the bite.
        if (!player.isUsingItem()) {
            FoodLogMod.LOGGER.info("Auto eat: the game refused {} (hand holds {} x{}, using item {})",
                    idOf(target), idOf(player.getMainHandItem().getItem()),
                    startCount, player.isUsingItem());
            abandon(player, gameMode);
            return;
        }
        phase = EATING;
        ticks = 0;
    }

    private static void abandon(LocalPlayer player, MultiPlayerGameMode gameMode) {
        rejected++;
        restore(player, gameMode);
        UNREACHABLE.add(target);
        reset();
        cooldown = COOLDOWN_TICKS;
    }

    /** Puts the two exchanged slots back the way they were, if this bite exchanged any. */
    private static void restore(LocalPlayer player, MultiPlayerGameMode gameMode) {
        if (swapped && player.containerMenu.containerId == containerId) {
            gameMode.handleInventoryMouseClick(containerId, slotIndex, hotbar, ClickType.SWAP, player);
        }
    }

    /**
     * Lets the use key go as soon as the game has confirmed the bite, and does it in the tick's
     * start phase so the key is already up before the game reads it. Letting go any later leaves
     * one tick in which the key is held while the game believes nothing is being used, and that
     * tick uses whatever the hand holds next.
     */
    private static void releaseKeyIfBiteOver(LocalPlayer player) {
        if (phase != EATING || !holdingUse) {
            return;
        }
        ItemStack hand = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        if (hand.isEmpty() || hand.getCount() < startCount || !hand.is(target)) {
            releaseUseKey();
        }
    }

    /**
     * Holds the use key down for the whole bite, the way a player who wants to eat holds right
     * click. Whatever the key was doing before is not restored, because it has to be up the moment
     * the bite ends either way.
     */
    private static void holdUseKey(boolean down) {
        if (down == holdingUse) {
            return;
        }
        holdingUse = down;
        Minecraft.getInstance().options.keyUse.setDown(down);
    }

    private static void releaseUseKey() {
        holdUseKey(false);
    }

    private static boolean isMainHand(LocalPlayer player, Slot slot) {
        Inventory inventory = player.getInventory();
        return slot.container == inventory && slot.getContainerSlot() == inventory.selected;
    }

    /**
     * Picks the least filling uneaten food that may be eaten right now, so one hunger bar fits
     * the most different foods. Returns null when nothing is eatable at this moment, which is
     * not the same as having nothing left to try - {@link #hasUneatenFood} tells those apart.
     */
    private static Slot findCandidate(LocalPlayer player, AbstractContainerMenu menu) {
        Slot best = null;
        int lowest = Integer.MAX_VALUE;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || UNREACHABLE.contains(stack.getItem())
                    || !ContainerHighlighter.isUneatenFood(stack) || !slot.mayPickup(player)) {
                continue;
            }
            FoodProperties food = stack.getFoodProperties(player);
            // The item's own rule, not a guess about the hunger bar: foods flagged to be edible
            // at any time stay on the list, and a modpack that changes the rule is followed too.
            if (food == null || !player.canEat(food.canAlwaysEat())) {
                continue;
            }
            if (food.getNutrition() < lowest) {
                lowest = food.getNutrition();
                best = slot;
            }
        }
        return best;
    }

    /** Whether an uneaten food is there at all, whether or not it can be eaten right now. */
    private static boolean hasUneatenFood(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (ContainerHighlighter.isUneatenFood(slot.getItem())) {
                return true;
            }
        }
        return false;
    }

    /** How many occupied slots hold food at all, and how many of those are still uneaten. */
    private static String scanSummary(AbstractContainerMenu menu) {
        int food = 0;
        int uneaten = 0;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (FoodRegistryUtil.isFood(stack)) {
                food++;
            }
            if (ContainerHighlighter.isUneatenFood(stack)) {
                uneaten++;
            }
        }
        return uneaten + " uneaten of " + food + " food stacks in " + menu.slots.size() + " slots";
    }

    /**
     * Reports how the run ended. The empty handed cases are told apart on purpose: a run that
     * never saw any uneaten food is a different problem from one whose bites were all refused,
     * and the old "nothing to eat" wording hid that difference.
     */
    private static void finish(LocalPlayer player, AbstractContainerMenu menu) {
        if (burst) {
            Component message;
            if (eaten > 0) {
                message = Component.translatable("foodlog.eat.done", eaten);
            } else if (attempts > 0) {
                message = Component.translatable("foodlog.eat.blocked", rejected);
            } else if (hasUneatenFood(menu)) {
                message = Component.translatable("foodlog.eat.full");
            } else {
                message = Component.translatable("foodlog.eat.none");
            }
            player.displayClientMessage(message, false);
            FoodLogMod.LOGGER.info("Auto eat finished: eaten={} attempts={} rejected={}, {}",
                    eaten, attempts, rejected, scanSummary(menu));
        }
        burst = false;
        eaten = 0;
        attempts = 0;
        rejected = 0;
        cooldown = COOLDOWN_TICKS;
    }

    private static String idOf(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? String.valueOf(item) : id.toString();
    }

    private static void reset() {
        phase = IDLE;
        ticks = 0;
        target = null;
        containerId = -1;
        slotIndex = -1;
        hotbar = -1;
        swapped = false;
        startCount = 0;
        useLostTicks = 0;
        releaseUseKey();
    }
}

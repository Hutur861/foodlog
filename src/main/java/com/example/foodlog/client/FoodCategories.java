package com.example.foodlog.client;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Classifies a food item along one of several dimensions, so the screen can show tabs such
 * as "foods I still have not eaten, grouped by type".
 *
 * Type/cooked/nutrition/condition use fixed buckets, source is dynamic, and neither needs a
 * hard coded item list, so any modded food is covered.
 *
 * Food mods bring hundreds of items with names no keyword table can anticipate (the Farmer's
 * Delight family alone adds gyudon, ossobuco, sinigang, kwat_wheat, latiao, ...). The type is
 * therefore decided in three passes, cheapest first:
 *
 * <ol>
 *   <li>keywords that are unambiguous in the id, such as "soup" or "salmon";</li>
 *   <li>the conventional item tags food mods fill in, such as {@code farmersdelight:meals}
 *       or {@code forge:cooked_meat}, including the many spellings some mods use;</li>
 *   <li>the remaining keywords, then a last resort described on {@link #typeOf}.</li>
 * </ol>
 *
 * The result of all this is cached per item id, because the screen re-groups hundreds of foods
 * on every keystroke.
 */
public final class FoodCategories {

    public enum Dimension {
        TYPE("foodlog.dim.type"),
        COOKED("foodlog.dim.cooked"),
        SOURCE("foodlog.dim.source"),
        NUTRITION("foodlog.dim.nutrition"),
        CONDITION("foodlog.dim.condition");

        private final String translationKey;

        Dimension(String translationKey) {
            this.translationKey = translationKey;
        }

        public Component label() {
            return Component.translatable(this.translationKey);
        }
    }

    /** Declaration order is the order the tabs are shown in. */
    private enum Type {
        MEAT, FISH, VEGETABLE, FRUIT, STEW, DISH, DESSERT, DRINK, MONSTER, OTHER
    }

    private enum Cooked {
        COOKED, RAW, OTHER
    }

    private enum Nutrition {
        NONE, LOW, MEDIUM, HIGH
    }

    private enum Condition {
        EFFECT, PLACE, NORMAL
    }

    /** A tag and the bucket it implies. */
    private record TagRule(TagKey<Item> tag, Type type) {
    }

    private record Info(Type type, Cooked cooked, Nutrition nutrition, Condition condition,
                        ItemStack stack, String searchName) {
    }

    private static final Map<ResourceLocation, Info> CACHE = new HashMap<>();

    private static final String[] COOKED_MARKERS = {
            "cooked_", "_cooked", "roast", "baked", "grilled", "smoked", "fried", "toasted", "boiled", "steamed",
            "braised", "pickled", "candied", "dried", "salted", "brined", "fermented", "stewed", "poached"
    };
    /**
     * Unambiguous enough to match anywhere in the id.
     */
    private static final String[] DRINK_MARKERS = {
            "juice", "coffee", "offee", "drink", "bottle", "smoothie", "nectar", "cider", "lemonade", "milkshake",
            "espresso", "kombucha", "kvass", "vodka", "cocktail", "coktail", "affogato"
    };
    /** Short markers, matched as whole id parts: "tea" inside "steak" or "steamed" is not a drink. */
    private static final String[] DRINK_WORDS = {
            "tea", "milk", "cocoa", "soda", "wine", "beer", "mead", "sake", "latte", "shake", "ale"
    };
    private static final String[] STEW_MARKERS = {
            "stew", "soup", "broth", "chowder", "curry", "porridge", "ramen", "hotpot", "bisque", "gruel",
            "sinigang", "rubaboo"
    };
    private static final String[] FISH_MARKERS = {
            "fish", "cod", "salmon", "tropical", "shrimp", "prawn", "crab", "lobster", "clam", "oyster",
            "squid", "octopus", "tilapia", "herring", "sardine", "anchovy", "calamari", "mussel", "scallop",
            "caviar", "urchin"
    };
    /** "eel" inside "orange_peel" is not a fish. */
    private static final String[] FISH_WORDS = {"eel", "carp", "bass", "roe"};
    private static final String[] MONSTER_MARKERS = {
            "rotten", "spider_eye", "flesh", "brain", "pufferfish", "ghast", "creeper", "sculk", "zombie",
            "vex", "slime", "warden"
    };
    /**
     * Prepared dishes, checked before meat and dessert so that "chicken_sandwich" reads as a
     * dish while "honey_glazed_ham" still reads as meat rather than as a dessert.
     */
    private static final String[] DISH_MARKERS = {
            "sandwich", "burger", "wrap", "roll", "dumpling", "pasta", "noodle", "pizza", "taco", "burrito",
            "kebab", "skewer", "salad", "stir_fry", "fried_rice", "sushi", "omelet", "omelette", "stuffed_",
            "toast", "gratin", "casserole", "risotto", "pilaf", "cutlet", "jiaozi", "jiao_zi", "shao_mai",
            "wonton", "baozi", "gyudon", "oyakodon", "ossobuco", "tokayaki", "terrine", "wellington",
            "fricassee", "salmagundi", "chazuke", "poutine", "millefeuille", "chutney", "kimchi", "tofu",
            "doufu", "poke", "quesadilla", "lasagna", "quiche", "schnitzel", "pot_sticker", "latiao", "chips"
    };
    private static final String[] MEAT_MARKERS = {
            "beef", "pork", "chicken", "mutton", "rabbit", "meat", "steak", "bacon", "sausage", "venison",
            "wing", "drumstick", "jerky", "lamb", "duck", "turkey", "goat", "meatball", "patty", "mince",
            "filet", "wurst", "shank", "tartare", "sweetbread", "chorizo", "meatloaf"
    };
    /** "ham" inside "graham" is not meat. */
    private static final String[] MEAT_WORDS = {"ham"};
    private static final String[] DESSERT_MARKERS = {
            "cake", "cookie", "pie", "pudding", "candy", "chocolate", "ice_cream", "icecream", "icepop",
            "donut", "biscuit", "honey", "jam", "toffee", "waffle", "pancake", "custard", "brownie", "muffin",
            "pastry", "jelly", "tart", "sundae", "marshmallow", "mousse", "gelato", "sorbet", "granola",
            "parfait", "trifle", "macaron", "raisin", "yogurt"
    };
    private static final String[] VEGETABLE_MARKERS = {
            "carrot", "potato", "beetroot", "kelp", "cabbage", "lettuce", "tomato", "onion", "corn", "pea",
            "bean", "mushroom", "seaweed", "pumpkin", "cucumber", "spinach", "radish", "garlic", "ginger",
            "pepper", "broccoli", "cauliflower", "leek", "celery", "asparagus", "zucchini", "eggplant",
            "artichoke", "kale", "soybean", "turnip", "yam", "alfalfa"
    };
    private static final String[] FRUIT_MARKERS = {
            "apple", "berr", "melon", "banana", "orange", "grape", "pear", "peach", "lemon", "cherry", "mango",
            "fruit", "papaya", "coconut", "avocado", "pineapple", "plum", "kiwi", "apricot", "nectarine",
            "lychee", "starfruit", "gooseberry", "cranberry", "currant", "durian", "guava", "pitaya",
            "himekaido", "persimmon", "quince"
    };

    /**
     * Conventional tags, as declared by the food mods themselves. Several mods spell the same
     * tag differently, so all spellings are listed. A tag no datapack defined simply matches
     * nothing.
     */
    private static final List<TagRule> TAG_RULES = List.of(
            rule("farmersdelight:meals", Type.DISH),
            rule("dungeonsdelight:monster_foods", Type.DISH),
            rule("forge:pasta", Type.DISH),
            rule("farmersdelight:drinks", Type.DRINK),
            rule("forge:milk", Type.DRINK),
            rule("forge:cake", Type.DESSERT),
            rule("forge:jam_jars", Type.DESSERT),
            rule("dungeonsdelight:rock_candies", Type.DESSERT),
            rule("vintagedelight:sweet_jam", Type.DESSERT),
            rule("minecraft:is_meat", Type.MEAT),
            rule("forge:cooked_meat", Type.MEAT),
            rule("forge:cooked_meats", Type.MEAT),
            rule("forge:cookedmeat", Type.MEAT),
            rule("forge:cookedmeats", Type.MEAT),
            rule("forge:raw_meat", Type.MEAT),
            rule("forge:raw_meats", Type.MEAT),
            rule("forge:rawmeat", Type.MEAT),
            rule("forge:rawmeats", Type.MEAT),
            rule("forge:cooked_beef", Type.MEAT),
            rule("forge:raw_beef", Type.MEAT),
            rule("forge:cooked_pork", Type.MEAT),
            rule("forge:raw_pork", Type.MEAT),
            rule("forge:cooked_chicken", Type.MEAT),
            rule("forge:raw_chicken", Type.MEAT),
            rule("forge:cooked_mutton", Type.MEAT),
            rule("forge:raw_mutton", Type.MEAT),
            rule("forge:cooked_bacon", Type.MEAT),
            rule("forge:raw_bacon", Type.MEAT),
            rule("dungeonsdelight:fleshes", Type.MEAT),
            rule("forge:raw_fishes", Type.FISH),
            rule("forge:cooked_fishes", Type.FISH),
            rule("vintagedelight:raw_fish", Type.FISH),
            rule("forge:vegetables", Type.VEGETABLE),
            rule("forge:cucumber", Type.VEGETABLE),
            rule("forge:chilipepper", Type.VEGETABLE),
            rule("forge:fruits", Type.FRUIT),
            rule("forge:berries", Type.FRUIT)
    );

    private static final List<TagKey<Item>> COOKED_TAGS = List.of(
            tag("forge:cooked_meat"), tag("forge:cooked_meats"), tag("forge:cookedmeat"),
            tag("forge:cookedmeats"), tag("forge:cooked_fishes"), tag("forge:cooked_beef"),
            tag("forge:cooked_pork"), tag("forge:cooked_chicken"), tag("forge:cooked_mutton"),
            tag("forge:cooked_bacon"), tag("forge:cooked_eggs")
    );

    private FoodCategories() {
    }

    private static TagRule rule(String id, Type type) {
        return new TagRule(tag(id), type);
    }

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, new ResourceLocation(id));
    }

    public static String keyOf(ResourceLocation id, Dimension dimension) {
        Info info = infoOf(id);
        return switch (dimension) {
            case TYPE -> info.type().name();
            case COOKED -> info.cooked().name();
            case SOURCE -> id.getNamespace();
            case NUTRITION -> info.nutrition().name();
            case CONDITION -> info.condition().name();
        };
    }

    public static MutableComponent labelOf(String key, Dimension dimension) {
        if (dimension == Dimension.SOURCE) {
            return Component.literal(displayNameOfNamespace(key));
        }
        return Component.translatable("foodlog.cat." + dimension.name().toLowerCase(Locale.ROOT) + "."
                + key.toLowerCase(Locale.ROOT));
    }

    /**
     * Buckets ordered for display: vanilla first then alphabetical for sources, enum
     * declaration order otherwise.
     */
    public static List<String> sortedKeys(Dimension dimension, Collection<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        if (dimension == Dimension.SOURCE) {
            sorted.sort(Comparator
                    .comparing((String key) -> !"minecraft".equals(key))
                    .thenComparing(FoodCategories::displayNameOfNamespace, String.CASE_INSENSITIVE_ORDER));
        } else {
            sorted.sort(Comparator.comparingInt((String key) -> ordinalOf(dimension, key))
                    .thenComparing(Comparator.naturalOrder()));
        }
        return sorted;
    }

    private static int ordinalOf(Dimension dimension, String key) {
        try {
            return switch (dimension) {
                case TYPE -> Type.valueOf(key).ordinal();
                case COOKED -> Cooked.valueOf(key).ordinal();
                case NUTRITION -> Nutrition.valueOf(key).ordinal();
                case CONDITION -> Condition.valueOf(key).ordinal();
                case SOURCE -> 0;
            };
        } catch (IllegalArgumentException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static String displayNameOfNamespace(String namespace) {
        if ("minecraft".equals(namespace)) {
            return "原版";
        }
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /** Drops the cached classification, for example after a datapack reload changed the tags. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Info infoOf(ResourceLocation id) {
        Info cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        Info computed = computeInfo(id);
        CACHE.put(id, computed);
        return computed;
    }

    private static Info computeInfo(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        ItemStack stack = item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        String path = id.getPath().toLowerCase(Locale.ROOT);

        Type type = typeOf(id, path, stack);
        FoodProperties properties = stack.isEmpty() ? null : stack.getFoodProperties(null);

        return new Info(type, cookedOf(path, type, stack), nutritionOf(properties), conditionOf(item, properties),
                stack, searchNameOf(stack, id));
    }

    private static Type typeOf(ResourceLocation id, String path, ItemStack stack) {
        Type specific = typeByKeyword(path, true);
        if (specific != Type.OTHER) {
            return specific;
        }
        Type tagged = typeByTag(stack);
        if (tagged != Type.OTHER) {
            return tagged;
        }
        Type general = typeByKeyword(path, false);
        if (general != Type.OTHER) {
            return general;
        }
        // Nothing recognised it. Anything edible from a mod is a dish of some food addon whose
        // naming there is no way to guess, while unmatched vanilla items (bread, for one) are
        // honestly "other".
        return "minecraft".equals(id.getNamespace()) ? Type.OTHER : Type.DISH;
    }

    /**
     * @param specific only the buckets that cannot be confused with a tag, so that a tagged
     *                 dish name still wins over a lucky keyword hit
     */
    private static Type typeByKeyword(String path, boolean specific) {
        if (containsAny(path, DRINK_MARKERS) || containsWord(path, DRINK_WORDS)) {
            return Type.DRINK;
        }
        if (containsAny(path, STEW_MARKERS)) {
            return Type.STEW;
        }
        if (containsAny(path, FISH_MARKERS) || containsWord(path, FISH_WORDS)) {
            return Type.FISH;
        }
        if (containsAny(path, MONSTER_MARKERS)) {
            return Type.MONSTER;
        }
        if (specific) {
            return Type.OTHER;
        }
        if (containsAny(path, DISH_MARKERS)) {
            return Type.DISH;
        }
        if (containsAny(path, MEAT_MARKERS) || containsWord(path, MEAT_WORDS)) {
            return Type.MEAT;
        }
        // Fruit is matched after dessert so "apple_pie" reads as a dessert. It used to be the
        // other way round for "sweet_berries", which is why "sweet" is not a dessert marker.
        if (containsAny(path, DESSERT_MARKERS)) {
            return Type.DESSERT;
        }
        if (containsAny(path, FRUIT_MARKERS)) {
            return Type.FRUIT;
        }
        if (containsAny(path, VEGETABLE_MARKERS)) {
            return Type.VEGETABLE;
        }
        return Type.OTHER;
    }

    private static Type typeByTag(ItemStack stack) {
        if (stack.isEmpty()) {
            return Type.OTHER;
        }
        for (TagRule rule : TAG_RULES) {
            if (stack.is(rule.tag())) {
                return rule.type();
            }
        }
        return Type.OTHER;
    }

    private static Cooked cookedOf(String path, Type type, ItemStack stack) {
        if (containsAny(path, COOKED_MARKERS) || isTagged(stack, COOKED_TAGS)) {
            return Cooked.COOKED;
        }
        if (type == Type.STEW || type == Type.DESSERT || type == Type.DISH) {
            return Cooked.COOKED;
        }
        if (type == Type.MEAT || type == Type.FISH || type == Type.VEGETABLE || type == Type.FRUIT) {
            return Cooked.RAW;
        }
        return Cooked.OTHER;
    }

    private static boolean isTagged(ItemStack stack, List<TagKey<Item>> tags) {
        if (stack.isEmpty()) {
            return false;
        }
        for (TagKey<Item> tag : tags) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static Nutrition nutritionOf(FoodProperties properties) {
        if (properties == null) {
            return Nutrition.NONE;
        }
        int nutrition = properties.getNutrition();
        if (nutrition <= 0) {
            return Nutrition.NONE;
        }
        if (nutrition <= 3) {
            return Nutrition.LOW;
        }
        if (nutrition <= 7) {
            return Nutrition.MEDIUM;
        }
        return Nutrition.HIGH;
    }

    private static Condition conditionOf(Item item, FoodProperties properties) {
        if (properties != null && !properties.getEffects().isEmpty()) {
            return Condition.EFFECT;
        }
        if (item instanceof BlockItem) {
            return Condition.PLACE;
        }
        return Condition.NORMAL;
    }

    private static String searchNameOf(ItemStack stack, ResourceLocation id) {
        return nameOf(stack, id).toLowerCase(Locale.ROOT);
    }

    private static String nameOf(ItemStack stack, ResourceLocation id) {
        return stack.isEmpty()
                ? id.getPath()
                : ChatFormatting.stripFormatting(stack.getHoverName().getString());
    }

    private static boolean containsAny(String path, String[] markers) {
        for (String marker : markers) {
            if (path.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Matches whole {@code _} separated parts, for markers short enough to show up inside
     * unrelated words.
     */
    private static boolean containsWord(String path, String[] words) {
        for (String word : words) {
            int index = path.indexOf(word);
            while (index >= 0) {
                int end = index + word.length();
                boolean startOk = index == 0 || path.charAt(index - 1) == '_';
                boolean endOk = end == path.length() || path.charAt(end) == '_';
                if (startOk && endOk) {
                    return true;
                }
                index = path.indexOf(word, index + 1);
            }
        }
        return false;
    }

    /** Extra tooltip line describing the item, e.g. nutrition and effects. */
    public static Component describe(ResourceLocation id) {
        Info info = infoOf(id);
        ItemStack stack = info.stack();
        FoodProperties properties = stack.isEmpty() ? null : stack.getFoodProperties(null);
        if (properties == null) {
            return Component.empty();
        }
        Component text = Component.translatable("foodlog.info.nutrition", properties.getNutrition(),
                String.format(Locale.ROOT, "%.1f", properties.getSaturationModifier()));
        if (!properties.getEffects().isEmpty()) {
            text = text.copy().append(Component.translatable("foodlog.info.effect"));
        }
        return text;
    }

    /** Lower case display name or id path, prepared once for the search box. */
    public static String searchNameOf(ResourceLocation id) {
        return infoOf(id).searchName();
    }

    /** The display name, as shown in the list. */
    public static String nameOf(ResourceLocation id) {
        Info info = infoOf(id);
        return nameOf(info.stack(), id);
    }

    public static ItemStack stackOf(ResourceLocation id) {
        return infoOf(id).stack();
    }
}
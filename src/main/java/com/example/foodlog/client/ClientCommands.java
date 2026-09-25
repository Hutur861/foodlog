package com.example.foodlog.client;

import com.example.foodlog.FoodLogMod;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A client side command, so /foodlog also works on servers that do not have the mod.
 */
@Mod.EventBusSubscriber(modid = FoodLogMod.MODID, value = Dist.CLIENT)
public final class ClientCommands {

    private static final String VALUE = "value";

    private static final SuggestionProvider<CommandSourceStack> BOOLEAN_SUGGESTIONS =
            (context, builder) -> SharedSuggestionProvider.suggest(new String[]{"true", "false"}, builder);

    private ClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("foodlog")
                .executes(ClientCommands::openPage)
                .then(Commands.literal("page").executes(ClientCommands::openPage))
                .then(Commands.literal("lighting")
                        .executes(context -> setLighting(context, !ClientSettings.isLightingEnabled()))
                        .then(Commands.argument(VALUE, StringArgumentType.word())
                                .suggests(BOOLEAN_SUGGESTIONS)
                                .executes(context -> setLighting(context,
                                        parseBoolean(StringArgumentType.getString(context, VALUE))))))
                .then(Commands.literal("eat")
                        .executes(context -> setAutoEat(context, !AutoFeeder.isEnabled()))
                        .then(Commands.literal("now").executes(ClientCommands::eatNow))
                        .then(Commands.argument(VALUE, StringArgumentType.word())
                                .suggests(BOOLEAN_SUGGESTIONS)
                                .executes(context -> setAutoEat(context,
                                        parseBoolean(StringArgumentType.getString(context, VALUE)))))));
    }

    private static int openPage(CommandContext<CommandSourceStack> context) {
        ClientFoodLogData.refreshContext();
        Minecraft.getInstance().setScreen(new FoodLogScreen());
        return 1;
    }

    private static int eatNow(CommandContext<CommandSourceStack> context) {
        AutoFeeder.eatNow();
        return 1;
    }

    private static int setAutoEat(CommandContext<CommandSourceStack> context, boolean value) {
        AutoFeeder.setEnabled(value);
        context.getSource().sendSuccess(
                () -> Component.translatable(value ? "foodlog.eat.on" : "foodlog.eat.off"), false);
        return 1;
    }

    private static int setLighting(CommandContext<CommandSourceStack> context, boolean value) {
        ClientSettings.setLightingEnabled(value);
        context.getSource().sendSuccess(
                () -> Component.translatable(value ? "foodlog.lighting.on" : "foodlog.lighting.off"), false);
        return 1;
    }

    /**
     * Brigadier's own boolean reader only accepts lower case, so both spellings are taken here.
     */
    private static boolean parseBoolean(String raw) throws CommandSyntaxException {
        if (raw.equalsIgnoreCase("true")) {
            return true;
        }
        if (raw.equalsIgnoreCase("false")) {
            return false;
        }
        throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.readerExpectedBool().create();
    }
}

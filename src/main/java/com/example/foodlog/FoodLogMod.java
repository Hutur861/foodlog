package com.example.foodlog;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(FoodLogMod.MODID)
public class FoodLogMod {

    public static final String MODID = "foodlog";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FoodLogMod() {
        // Client only mod: all behaviour lives in the Dist.CLIENT subscribers and the
        // local config file, so the server never needs this mod installed.
    }
}
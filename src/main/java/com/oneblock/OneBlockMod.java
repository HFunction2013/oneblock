package com.oneblock;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * One Block - Forge 1.20.1
 *
 * Every chunk in the world is made of a single block type. The block is chosen
 * deterministically from the world seed (same seed => same chunk block, every
 * time). Terrain keeps its exact shape: air pockets, structures and houses are
 * preserved, and the bottom bedrock layer is left untouched.
 */
@Mod(OneBlockMod.MODID)
public class OneBlockMod {

    public static final String MODID = "oneblock";
    private static final Logger LOGGER = LogUtils.getLogger();

    public OneBlockMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        MinecraftForge.EVENT_BUS.register(new OneBlockEvents());
        MinecraftForge.EVENT_BUS.register(new RefreshZoneCommand());

        LOGGER.info("One Block mod initialized.");
    }
}

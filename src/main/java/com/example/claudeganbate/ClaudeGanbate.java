package com.example.claudeganbate;

import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.item.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/** Zombie Apocalypse: プレイヤーの進行度に応じてゾンビの強さ・出現量が変わる。設計は docs/DIFFICULTY.md。 */
@Mod(ClaudeGanbate.MODID)
public class ClaudeGanbate {
    public static final String MODID = "claudeganbate";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ClaudeGanbate() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, ZapocConfig.SPEC);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        modBus.addListener(ModItems::addToTabs);
        LOGGER.info("Zombie Apocalypse loaded");
    }
}

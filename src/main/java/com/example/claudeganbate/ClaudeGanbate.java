package com.example.claudeganbate;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ClaudeGanbate.MODID)
public class ClaudeGanbate {
    public static final String MODID = "claudeganbate";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ClaudeGanbate() {
        LOGGER.info("Claude Ganbate loaded!");
    }
}

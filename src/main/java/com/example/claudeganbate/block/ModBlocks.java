package com.example.claudeganbate.block;

import com.example.claudeganbate.ClaudeGanbate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    private ModBlocks() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ClaudeGanbate.MODID);

    /** 腐肉の祭壇: 腐肉からエリクサーを作る専用の台。 */
    public static final RegistryObject<Block> FLESH_ALTAR = BLOCKS.register("flesh_altar",
        () -> new AltarBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(3.0f, 6.0f)
            .sound(SoundType.BONE_BLOCK).lightLevel(s -> 4)));
}

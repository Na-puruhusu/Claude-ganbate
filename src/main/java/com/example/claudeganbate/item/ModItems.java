package com.example.claudeganbate.item;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.block.ModBlocks;
import com.example.claudeganbate.difficulty.ElixirModel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModItems {
    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ClaudeGanbate.MODID);

    /** 腐肉の祭壇(ブロックアイテム)。エリクサーはこの台で作る。 */
    public static final RegistryObject<Item> FLESH_ALTAR = ITEMS.register("flesh_altar",
        () -> new BlockItem(ModBlocks.FLESH_ALTAR.get(), new Item.Properties()));

    /** key = "<kind>_<level>" (例: health_3)。全16種類。 */
    public static final Map<String, RegistryObject<Item>> ELIXIRS = new LinkedHashMap<>();

    static {
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) {
            for (int lv = 1; lv <= k.maxLevel(); lv++) {
                final int level = lv;
                ELIXIRS.put(k.id + "_" + lv, ITEMS.register("elixir_" + k.id + "_" + lv,
                    () -> new ElixirItem(k, level, new Item.Properties().stacksTo(16))));
            }
        }
    }

    public static Item elixir(ElixirModel.Kind k, int level) { return ELIXIRS.get(k.id + "_" + level).get(); }

    public static void addToTabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) e.accept(FLESH_ALTAR);
        if (e.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) ELIXIRS.values().forEach(e::accept);
    }
}

package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.difficulty.Variant;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/** エリクサーの永続化(ログイン・死亡・帰還で効果を維持)と、腐肉の追加ドロップ。 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class ElixirEvents {
    private ElixirEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        ElixirState.apply(e.getEntity());
    }

    /** 死亡・エンド帰還でプレイヤーが作り直されるとき、永続データを引き継ぐ。ホードの状態もリセット(死んだ夜に再びホードが始まらないように)。 */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone e) {
        ElixirState.copy(e.getOriginal(), e.getEntity());
        ElixirState.apply(e.getEntity());
        HordeDirector.resetState(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        ElixirState.apply(e.getEntity());
        e.getEntity().setHealth(e.getEntity().getMaxHealth());
    }

    /** プレイヤーが倒したゾンビから腐肉を追加で落とす。脅威レベルが高く、強い個体ほど多い。 */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent e) {
        if (!ZapocConfig.ENABLED.get() || !ZapocConfig.EXTRA_FLESH_DROPS.get()) return;
        if (!(e.getEntity() instanceof Zombie z) || z instanceof ZombifiedPiglin || z.level().isClientSide()) return;
        if (!(e.getSource().getEntity() instanceof Player)) return;
        int n = extraFlesh(z, e.getLootingLevel(), new Random(z.getRandom().nextLong()));
        if (n > 0) e.getDrops().add(new ItemEntity(z.level(), z.getX(), z.getY(), z.getZ(), new ItemStack(Items.ROTTEN_FLESH, n)));
    }

    public static int extraFlesh(Zombie z, int looting, Random rng) {
        CompoundTag tag = z.getPersistentData();
        double lvl = tag.getBoolean(ZombieEmpowerer.TAG_DONE) ? tag.getDouble(ZombieEmpowerer.TAG_LEVEL) : 0.0;
        Variant v = Variant.byId(tag.getString(ZombieEmpowerer.TAG_VARIANT));
        return ElixirModel.fleshBonus(lvl, v, looting, rng);
    }
}

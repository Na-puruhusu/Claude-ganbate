package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.Variant;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 特殊ゾンビの挙動: 目印のパーティクル / アルファのオーラ / ブローターの毒ガス / ホードの収束。 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class ZombieSpecials {
    private ZombieSpecials() {}

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent e) {
        if (!(e.getEntity() instanceof Zombie z) || z.level().isClientSide() || z.tickCount % 20 != 0) return;
        if (!(z.level() instanceof ServerLevel level) || !z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_DONE)) return;

        Variant v = Variant.byId(z.getPersistentData().getString(ZombieEmpowerer.TAG_VARIANT));
        if (v != Variant.WALKER) {
            ParticleOptions p = switch (v) {
                case RUNNER -> ParticleTypes.CLOUD;
                case ARMORED -> ParticleTypes.SMOKE;
                case BRUTE -> ParticleTypes.CAMPFIRE_COSY_SMOKE;
                case BLOATER -> ParticleTypes.COMPOSTER;
                case ALPHA -> ParticleTypes.ANGRY_VILLAGER;
                default -> null;
            };
            if (p != null) level.sendParticles(p, z.getX(), z.getY() + z.getBbHeight() + 0.2, z.getZ(), 2, 0.25, 0.1, 0.25, 0.01);
        }

        // アルファのオーラ: 周囲10ブロックのゾンビに耐性I(被ダメージ-20%)。倒すのが最優先
        if (v == Variant.ALPHA && z.tickCount % 40 == 0) {
            for (Zombie o : level.getEntitiesOfClass(Zombie.class, z.getBoundingBox().inflate(10.0), o -> o != z && o.isAlive())) {
                o.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 0, true, true));
            }
        }

        // ホードのゾンビは、目標がいなければプレイヤーに向かって歩く(視線が通らなくても集まる)
        if (z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_HORDE) && z.getTarget() == null && z.tickCount % 40 == 0) {
            Player near = level.getNearestPlayer(z, 96.0);
            if (near != null && !near.isCreative() && !near.isSpectator()) z.getNavigation().moveTo(near, 1.0);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof Zombie z) || !(z.level() instanceof ServerLevel level)) return;
        if (!z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_DONE)) return;
        Variant v = Variant.byId(z.getPersistentData().getString(ZombieEmpowerer.TAG_VARIANT));

        if (v == Variant.BLOATER) {
            // 倒した位置に毒ガス(毒I 約4秒=約3ダメージ。致死はしないが回復を打ち消す)。通路で籠城して1体ずつ倒す戦法への対抗
            AreaEffectCloud cloud = new AreaEffectCloud(level, z.getX(), z.getY(), z.getZ());
            cloud.setRadius(2.5f);
            cloud.setDuration(80);
            cloud.setRadiusPerTick(-2.5f / 80f);
            cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 320, 0));   // 雲は効果時間の1/4で付与する
            level.addFreshEntity(cloud);
        }
        // 強敵の討伐報酬(経験値)。プレイヤーが倒したときのみ
        if (e.getSource().getEntity() instanceof Player) {
            int xp = switch (v) { case BRUTE -> 8; case ALPHA -> 30; case BLOATER -> 4; default -> 0; };
            if (xp > 0) ExperienceOrb.award(level, z.position(), xp);
        }
    }
}

package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.Tuning;
import com.example.claudeganbate.difficulty.Variant;
import com.example.claudeganbate.difficulty.ZombieProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * CI専用の自己診断。環境変数 ZAPOC_SELFTEST がある専用サーバーでのみ動き、FakePlayer を使って
 * 「強化処理・特殊ゾンビ・ホード出現・毒ガス・ドア破壊goal」が実際のゲーム内で期待どおり動くかを検証し、
 * 結果を "ZAPOC_SELFTEST_OK" / "ZAPOC_SELFTEST_FAIL" としてログに出してサーバーを止める。通常プレイでは何もしない。
 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class SelfTest {
    private SelfTest() {}

    private static boolean enabled = false;
    private static int phase = 0, wait = 0;
    private static ServerPlayer fp;
    private static ServerLevel level;
    private static final List<String> failures = new ArrayList<>();
    private static final List<Zombie> variantZombies = new ArrayList<>();
    private static int groupSpawned = 0;

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent e) {
        enabled = System.getenv("ZAPOC_SELFTEST") != null;
        if (enabled) ClaudeGanbate.LOGGER.info("ZAPOC_SELFTEST starting");
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (!enabled || e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = e.getServer();
        try {
            if (--wait > 0) return;
            switch (phase) {
                case 0 -> { setup(server); phase = 1; wait = 5; }
                case 1 -> { spawnVariants(); phase = 2; wait = 40; }      // 装備の属性反映は1tick後なので少し待つ
                case 2 -> { verifyVariants(); spawnHorde(); phase = 3; wait = 40; }
                case 3 -> { verifyHorde(); killBloater(); phase = 4; wait = 10; }
                case 4 -> { verifyCloud(); finish(server); phase = 5; }
                default -> { }
            }
        } catch (Throwable t) {
            ClaudeGanbate.LOGGER.error("selftest exception", t);
            failures.add("exception: " + t);
            finish(server);
            phase = 5;
        }
    }

    private static void check(boolean ok, String msg) {
        if (!ok) { failures.add(msg); ClaudeGanbate.LOGGER.error("SELFTEST FAIL: {}", msg); }
    }

    private static void setup(MinecraftServer server) {
        level = server.overworld();
        // 日光で燃えてHPが減らないよう夜に固定する(バニラ通りヘルメット無しのゾンビは昼に燃える)
        level.setDayTime(18000);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
        fp = FakePlayerFactory.getMinecraft(level);
        BlockPos sp = level.getSharedSpawnPos();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, sp.getX(), sp.getZ());
        fp.moveTo(sp.getX() + 0.5, y, sp.getZ() + 0.5);
        ZombieEmpowerer.TEST_PLAYER = fp;
        ThreatTracker.setOverride(fp.getUUID(), 8.0);

        // 実測の脅威計算(固定なし)も呼べること。新規ワールドのFakePlayerはステージ0のはず
        ThreatTracker.setOverride(fp.getUUID(), null);
        ThreatTracker.Snapshot s = ThreatTracker.get(fp);
        check(s.threat().stage() == 0, "新規プレイヤーのステージが0でない: " + s.threat().stage());
        check(s.threat().progress() >= 0 && s.threat().progress() < 2.0, "progress が範囲外: " + s.threat().progress());
        ThreatTracker.setOverride(fp.getUUID(), 8.0);
        check(ThreatTracker.get(fp).threat().statLevel() == 8.0, "override が効いていない");
        check(ZapocConfig.ENABLED.get(), "config が読めない/無効");
    }

    private static void spawnVariants() {
        BlockPos base = fp.blockPosition();
        int i = 0;
        for (Variant v : Variant.values()) {
            Zombie z = EntityType.ZOMBIE.create(level);
            z.moveTo(base.getX() + 3 + i * 2, base.getY(), base.getZ() + 3, 0, 0);
            z.getPersistentData().putString(ZombieEmpowerer.TAG_FORCE, v.id);
            level.addFreshEntity(z);        // onJoin が TEST_PLAYER の脅威(L=8)で強化する
            variantZombies.add(z);
            i++;
        }
    }

    private static void verifyVariants() {
        Tuning t = ZapocConfig.tuning();
        for (Zombie z : variantZombies) {
            String id = z.getPersistentData().getString(ZombieEmpowerer.TAG_VARIANT);
            Variant v = Variant.byId(id);
            check(z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_DONE), "強化済みタグが無い: " + id);
            ZombieProfile p = ZombieProfile.of(8.0, v, t);
            check(Math.abs(z.getMaxHealth() - p.maxHp()) < 0.05, String.format("%s の最大HP %.2f != 期待 %.2f", id, z.getMaxHealth(), p.maxHp()));
            check(Math.abs(z.getHealth() - z.getMaxHealth()) < 0.05, id + " のHPが満タンでない");
            check(Math.abs(z.getAttributeValue(Attributes.ATTACK_DAMAGE) - p.damage()) < 0.05,
                String.format("%s の攻撃 %.2f != 期待 %.2f", id, z.getAttributeValue(Attributes.ATTACK_DAMAGE), p.damage()));
            check(Math.abs(z.getAttributeValue(Attributes.ARMOR) - p.armor()) < 0.05,
                String.format("%s の防御 %.2f != 期待 %.2f(装備込み)", id, z.getAttributeValue(Attributes.ARMOR), p.armor()));
            double spd = z.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.23;
            check(Math.abs(spd - p.speedMult()) < 0.02, String.format("%s の速度倍率 %.3f != 期待 %.3f", id, spd, p.speedMult()));
            check(Math.abs(z.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) - p.kbResist()) < 0.01, id + " のKB耐性が不一致");
            if (v == Variant.ALPHA) check(z.hasEffect(MobEffects.GLOWING), "アルファが発光していない");
            // ドア破壊goal(L=8 >= 2.0)。ただし ZapocConfig.DOOR_BREAKING が true の場合
            if (ZapocConfig.DOOR_BREAKING.get())
                check(z.goalSelector.getAvailableGoals().stream().anyMatch(w -> w.getGoal() instanceof BreakDoorGoal), id + " にドア破壊goalが無い");
        }
        ClaudeGanbate.LOGGER.info("SELFTEST variants verified: {}", variantZombies.size());
    }

    private static void spawnHorde() {
        groupSpawned = HordeDirector.spawnGroup(level, fp, 8, 1, true, false, new Random(1));
        check(groupSpawned >= 3, "ホード出現が少なすぎる: " + groupSpawned);
        ClaudeGanbate.LOGGER.info("SELFTEST horde spawned {}", groupSpawned);
    }

    private static void verifyHorde() {
        AABB box = fp.getBoundingBox().inflate(80);
        List<Zombie> horde = level.getEntitiesOfClass(Zombie.class, box, z -> z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_HORDE));
        check(horde.size() >= groupSpawned - 1, "ホードのゾンビ数 " + horde.size() + " < 出現数 " + groupSpawned);
        boolean alpha = false;
        for (Zombie z : horde) {
            check(z.getPersistentData().getBoolean(ZombieEmpowerer.TAG_DONE), "ホードのゾンビが強化されていない");
            check(z.getAttributeValue(Attributes.FOLLOW_RANGE) >= 52.0 - 0.01, "ホードの索敵距離が足りない: " + z.getAttributeValue(Attributes.FOLLOW_RANGE));
            double d = Math.sqrt(z.distanceToSqr(fp));
            check(d >= 20 && d <= 50, "ホードの出現距離が範囲外: " + d);
            if ("alpha".equals(z.getPersistentData().getString(ZombieEmpowerer.TAG_VARIANT))) alpha = true;
        }
        check(alpha, "ホードにアルファがいない(L=8)");
        ClaudeGanbate.LOGGER.info("SELFTEST horde verified: {} zombies, alpha={}", horde.size(), alpha);
    }

    private static void killBloater() {
        for (Zombie z : variantZombies) {
            if ("bloater".equals(z.getPersistentData().getString(ZombieEmpowerer.TAG_VARIANT))) {
                z.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            }
        }
    }

    private static void verifyCloud() {
        List<AreaEffectCloud> clouds = level.getEntitiesOfClass(AreaEffectCloud.class, fp.getBoundingBox().inflate(40));
        check(!clouds.isEmpty(), "ブローターの毒ガスが発生していない");
        ClaudeGanbate.LOGGER.info("SELFTEST clouds: {}", clouds.size());
    }

    private static void finish(MinecraftServer server) {
        ZombieEmpowerer.TEST_PLAYER = null;
        if (failures.isEmpty()) ClaudeGanbate.LOGGER.info("ZAPOC_SELFTEST_OK");
        else ClaudeGanbate.LOGGER.error("ZAPOC_SELFTEST_FAIL: {}", failures);
        server.halt(false);
    }
}

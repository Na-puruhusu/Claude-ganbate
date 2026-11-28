package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.block.AltarBlock;
import com.example.claudeganbate.block.AltarMenu;
import com.example.claudeganbate.block.ModBlocks;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.DayCurve;
import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.difficulty.Tuning;
import com.example.claudeganbate.difficulty.Variant;
import com.example.claudeganbate.difficulty.ZombieProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.example.claudeganbate.item.ElixirItem;
import com.example.claudeganbate.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;

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
                case 4 -> { verifyCloud(); phase = 5; wait = 2; }
                case 5 -> { testDayBased(); testElixirs(server); testAltar(); testRecipes(server); testAssets(); finish(server); phase = 6; }
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
        check(s.threat().progress() >= 0 && s.threat().progress() < 0.5, "新規ワールド(0日目)の脅威レベルが範囲外: " + s.threat().progress());
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

    /** 難易度は世界の日数で決まる: 時刻を進めると脅威レベルがDayCurveどおりに上がる。 */
    private static void testDayBased() {
        long keep = level.getDayTime();
        ThreatTracker.setOverride(fp.getUUID(), null);
        for (int day : new int[]{0, 7, 25, 80, 150}) {
            level.setDayTime(day * 24000L + 18000);
            ThreatTracker.invalidate(fp.getUUID());
            double got = ThreatTracker.get(fp).threat().progress();
            double want = DayCurve.levelAt(day + 18000 / 24000.0, ZapocConfig.DAY_SPEED.get());
            check(Math.abs(got - want) < 0.01, String.format("%d日目の脅威レベル %.3f != 期待 %.3f", day, got, want));
        }
        level.setDayTime(keep);
        ThreatTracker.setOverride(fp.getUUID(), 8.0);
        ClaudeGanbate.LOGGER.info("SELFTEST day-based threat verified");
    }

    private static void resetElixirs() {
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) ElixirState.set(fp, k, 0);
        ElixirState.apply(fp);
    }

    private static void testElixirs(MinecraftServer server) {
        resetElixirs();
        double hp0 = fp.getMaxHealth(), spd0 = fp.getAttributeValue(Attributes.MOVEMENT_SPEED);
        ElixirModel.Kind HEALTH = ElixirModel.Kind.HEALTH;

        // 仕様どおりの段階数と効果量
        check(ElixirModel.FLESH_PER_ELIXIR == 256, "エリクサーは腐肉256個(4スタック)のはず");
        check(ElixirModel.Kind.DAMAGE.maxLevel() == 4 && ElixirModel.Kind.SPEED.maxLevel() == 4
            && ElixirModel.Kind.DURABILITY.maxLevel() == 3 && ElixirModel.Kind.HEALTH.maxLevel() == 5, "エリクサーの段階数が仕様と違う");

        // レベルを飛ばして飲めない(消費もしない)
        ItemStack skip = new ItemStack(ModItems.elixir(HEALTH, 2));
        ItemStack outSkip = skip.getItem().finishUsingItem(skip, level, fp);
        check(ElixirState.get(fp, HEALTH) == 0 && outSkip == skip && skip.getCount() == 1, "体力Lv1を飲まずにLv2を飲めてしまった");

        // 体力: Lv1〜5 を順に飲む → ハート +2/4/6/8/10(= HP +4/8/12/16/20)
        for (int lv = 1; lv <= 5; lv++) {
            ItemStack st = new ItemStack(ModItems.elixir(HEALTH, lv));
            ItemStack out = st.getItem().finishUsingItem(st, level, fp);
            check(ElixirState.get(fp, HEALTH) == lv, "体力エリクサーLv" + lv + "を飲んでもレベルが上がらない");
            check(out.isEmpty(), "飲んだエリクサーが消費されない Lv" + lv);
            check(Math.abs(fp.getMaxHealth() - (hp0 + 4.0 * lv)) < 0.01, "体力Lv" + lv + "の最大体力が不正: " + fp.getMaxHealth() + " (期待 " + (hp0 + 4.0 * lv) + ")");
        }
        // 同じレベルは飲めない(消費しない)
        ItemStack again = new ItemStack(ModItems.elixir(HEALTH, 3));
        ItemStack outAgain = again.getItem().finishUsingItem(again, level, fp);
        check(outAgain == again && again.getCount() == 1 && ElixirState.get(fp, HEALTH) == 5, "低いレベルのエリクサーを消費してしまった");

        // 全種最大: 体力+20 / スピード+100%
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) ElixirState.set(fp, k, k.maxLevel());
        ElixirState.apply(fp);
        check(Math.abs(fp.getMaxHealth() - (hp0 + 20.0)) < 0.01, "全種最大の最大体力が不正: " + fp.getMaxHealth());
        check(Math.abs(fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / spd0 - 2.0) < 0.01, "スピード最大で移動速度が2倍にならない: " + fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / spd0);
        ElixirState.apply(fp);   // 冪等(二重に積まれない)
        check(Math.abs(fp.getMaxHealth() - (hp0 + 20.0)) < 0.01 && Math.abs(fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / spd0 - 2.0) < 0.01, "apply を繰り返すと効果が重複する");
        // 段階ごとのスピード: 25/50/75/100%
        for (int lv = 1; lv <= 4; lv++) {
            ElixirState.set(fp, ElixirModel.Kind.SPEED, lv);
            ElixirState.apply(fp);
            check(Math.abs(fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / spd0 - (1.0 + 0.25 * lv)) < 0.01, "スピードLv" + lv + "の倍率が不正");
        }
        ElixirState.set(fp, ElixirModel.Kind.SPEED, 4);
        ElixirState.apply(fp);

        // ダメージ(与ダメージ +15/30/45/60%)と耐久(被ダメージ -20/40/60%)
        Zombie target = EntityType.ZOMBIE.create(level);
        double[] dmgBonus = {0.15, 0.30, 0.45, 0.60}, durCut = {0.20, 0.40, 0.60};
        for (int lv = 0; lv <= 4; lv++) {
            ElixirState.set(fp, ElixirModel.Kind.DAMAGE, lv);
            float got = ElixirEvents.modifyDamage(target, fp.damageSources().playerAttack(fp), 10f);
            double want = 10.0 * (1.0 + (lv == 0 ? 0 : dmgBonus[lv - 1]));
            check(Math.abs(got - want) < 0.01, "ダメージLv" + lv + "の与ダメージ " + got + " != " + want);
        }
        for (int lv = 0; lv <= 3; lv++) {
            ElixirState.set(fp, ElixirModel.Kind.DURABILITY, lv);
            float got = ElixirEvents.modifyDamage(fp, fp.damageSources().mobAttack(target), 10f);
            double want = 10.0 * (1.0 - (lv == 0 ? 0 : durCut[lv - 1]));
            check(Math.abs(got - want) < 0.01, "耐久Lv" + lv + "の被ダメージ " + got + " != " + want);
        }
        check(Math.abs(ElixirEvents.modifyDamage(fp, fp.damageSources().genericKill(), 10f) - 10f) < 0.01, "耐久が /kill などの無視できないダメージまで軽減している");
        check(Math.abs(ElixirEvents.modifyDamage(target, fp.damageSources().generic(), 10f) - 10f) < 0.01, "プレイヤー以外の攻撃者のダメージが変わっている");

        // 死亡・帰還を想定した引き継ぎ
        Player fp2 = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("zapoc_test2".getBytes(StandardCharsets.UTF_8)), "zapoc_test2"));
        ElixirState.copy(fp, fp2);
        ElixirState.apply(fp2);
        check(ElixirState.get(fp2, ElixirModel.Kind.DURABILITY) == 3 && Math.abs(fp2.getMaxHealth() - (hp0 + 20.0)) < 0.01, "死亡後の引き継ぎでエリクサーが消えた");

        // エリクサーを持つほどDDAで少し厳しくなる(標準より多く持つと補正が正)
        ThreatTracker.setOverride(fp.getUUID(), null);
        ThreatTracker.invalidate(fp.getUUID());
        double adj = ThreatTracker.get(fp).threat().adj();
        check(adj > 0.0, "エリクサー全部でもDDAの補正が上がらない: " + adj);
        ThreatTracker.setOverride(fp.getUUID(), 8.0);

        // 腐肉ドロップ
        Zombie alpha = EntityType.ZOMBIE.create(level);
        alpha.getPersistentData().putBoolean(ZombieEmpowerer.TAG_DONE, true);
        alpha.getPersistentData().putString(ZombieEmpowerer.TAG_VARIANT, "alpha");
        alpha.getPersistentData().putDouble(ZombieEmpowerer.TAG_LEVEL, 8.0);
        check(ElixirEvents.extraFlesh(alpha, 0, new Random(3)) >= 8, "アルファの追加腐肉ドロップが少ない");
        resetElixirs();
        ClaudeGanbate.LOGGER.info("SELFTEST elixirs verified");
    }

    private static void giveFlesh(int n) {
        for (int left = n; left > 0; left -= 64) fp.getInventory().add(new ItemStack(Items.ROTTEN_FLESH, Math.min(64, left)));
    }

    /** 祭壇でのクラフト: 腐肉256個、次のレベルだけ、重複所持の拒否、画面のクリック処理。 */
    private static void testAltar() {
        resetElixirs();
        fp.getInventory().clearContent();
        ElixirModel.Kind DMG = ElixirModel.Kind.DAMAGE;

        giveFlesh(255);
        check(AltarCrafting.craft(fp, DMG, 1) == AltarCrafting.Result.NOT_ENOUGH_FLESH && AltarCrafting.countFlesh(fp.getInventory()) == 255, "腐肉255個で作れてしまった/消費された");
        giveFlesh(1);
        check(AltarCrafting.craft(fp, DMG, 2) == AltarCrafting.Result.NEED_PREVIOUS && AltarCrafting.countFlesh(fp.getInventory()) == 256, "Lv1を飛ばしてLv2を作れてしまった");
        check(AltarCrafting.craft(fp, DMG, 5) == AltarCrafting.Result.INVALID && AltarCrafting.craft(fp, ElixirModel.Kind.DURABILITY, 4) == AltarCrafting.Result.INVALID, "存在しないレベルを作れてしまった");
        check(AltarCrafting.craft(fp, DMG, 1) == AltarCrafting.Result.OK, "腐肉256個(4スタック)で作れない");
        check(AltarCrafting.countFlesh(fp.getInventory()) == 0, "作成後に腐肉が残っている/足りない: " + AltarCrafting.countFlesh(fp.getInventory()));
        check(AltarCrafting.holds(fp.getInventory(), ModItems.elixir(DMG, 1)), "作ったエリクサーが渡されていない");
        giveFlesh(256);
        check(AltarCrafting.craft(fp, DMG, 1) == AltarCrafting.Result.ALREADY_HELD && AltarCrafting.countFlesh(fp.getInventory()) == 256, "同じエリクサーを持っているのに重複して作れてしまう");
        // 飲む → 習得済みになり、次のレベルが作れる
        ItemStack drink = new ItemStack(ModItems.elixir(DMG, 1));
        drink.getItem().finishUsingItem(drink, level, fp);
        check(AltarCrafting.craft(fp, DMG, 1) == AltarCrafting.Result.ALREADY_LEARNED, "習得済みのレベルを作れてしまう");

        // 画面(メニュー): 行=種類、列=レベル。表示専用で、クリックで作る
        AltarMenu menu = new AltarMenu(1, fp.getInventory(), ContainerLevelAccess.NULL);
        check(menu.slots.size() == AltarMenu.SIZE + 36, "祭壇画面のスロット数が不正: " + menu.slots.size());
        check(menu.getSlot(0).getItem().is(Items.IRON_SWORD), "ダメージ行の見出しが不正");
        check(menu.getSlot(1).getItem().is(ModItems.elixir(DMG, 1)), "習得済みのLv1が表示されない");
        check(menu.getSlot(2).getItem().is(ModItems.elixir(DMG, 2)), "次に作れるLv2が表示されない");
        check(menu.getSlot(3).getItem().is(Items.GRAY_STAINED_GLASS_PANE), "未解放のLv3がロック表示でない");
        check(menu.getSlot(4).getItem().is(ModItems.elixir(DMG, 4)) || menu.getSlot(4).getItem().is(Items.GRAY_STAINED_GLASS_PANE), "ダメージLv4の表示が不正");
        check(menu.getSlot(9 * 3 + 5).getItem().is(Items.GRAY_STAINED_GLASS_PANE), "体力Lv5(未解放)がロック表示でない");
        check(menu.getSlot(9 * 2 + 4).getItem().isEmpty(), "耐久は3段階のはず(Lv4のスロットは空)");
        check(menu.getSlot(AltarMenu.INFO_SLOT).getItem().is(Items.ROTTEN_FLESH), "腐肉の所持数の表示が無い");
        check(!menu.getSlot(2).mayPickup(fp) && !menu.getSlot(2).mayPlace(new ItemStack(Items.DIAMOND)), "表示スロットから取り出し/設置できてしまう");
        check(menu.quickMoveStack(fp, 0).isEmpty(), "シフトクリックで何かが動いた");
        // 腐肉256個(さっき渡した分)。ダメージLv2は既に持っている(作成済み)ので、体力Lv1(スロット28)をクリックして作る
        int before = AltarCrafting.countFlesh(fp.getInventory());
        menu.clicked(9 * 3 + 1, 0, ClickType.PICKUP, fp);
        check(AltarCrafting.holds(fp.getInventory(), ModItems.elixir(ElixirModel.Kind.HEALTH, 1)), "画面のクリックでエリクサーが作れない");
        check(AltarCrafting.countFlesh(fp.getInventory()) == before - 256, "画面で作ったときの腐肉消費が不正");
        check(menu.getCarried().isEmpty(), "画面のクリックでアイテムを持ててしまった");
        // 腐肉が足りないときは何も起きない
        int flesh = AltarCrafting.countFlesh(fp.getInventory());
        menu.clicked(9 + 1, 0, ClickType.PICKUP, fp);   // スピードLv1
        check(!AltarCrafting.holds(fp.getInventory(), ModItems.elixir(ElixirModel.Kind.SPEED, 1)) || flesh >= 256, "腐肉不足でも作れてしまった");

        // ブロック: 置いて右クリックすると画面が開く
        BlockPos pos = fp.blockPosition().offset(4, 0, 4);
        level.setBlockAndUpdate(pos, ModBlocks.FLESH_ALTAR.get().defaultBlockState());
        check(level.getBlockState(pos).getBlock() instanceof AltarBlock, "祭壇ブロックを置けない");
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false);
        var res = level.getBlockState(pos).use(level, fp, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        check(res.consumesAction() && fp.containerMenu instanceof AltarMenu, "祭壇を右クリックしても画面が開かない");
        fp.closeContainer();
        level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

        fp.getInventory().clearContent();
        resetElixirs();
        ClaudeGanbate.LOGGER.info("SELFTEST altar verified");
    }

    private static void testRecipes(MinecraftServer server) {
        var ra = server.registryAccess();
        var all = server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(r -> r.getId().getNamespace().equals(ClaudeGanbate.MODID)).toList();
        // エリクサーは祭壇で作る(クラフト台のレシピは祭壇だけ)
        check(all.size() == 1, "mod のレシピ数が不正(祭壇の1つだけのはず): " + all.size());
        CraftingRecipe r = all.stream().filter(x -> x.getResultItem(ra).is(ModItems.FLESH_ALTAR.get())).findFirst().orElse(null);
        if (r == null) { check(false, "腐肉の祭壇のレシピが無い"); return; }
        int flesh = 0, bone = 0, cauldron = 0, bricks = 0;
        for (Ingredient ing : r.getIngredients()) {
            if (ing.test(new ItemStack(Items.ROTTEN_FLESH))) flesh++;
            else if (ing.test(new ItemStack(Items.BONE))) bone++;
            else if (ing.test(new ItemStack(Items.CAULDRON))) cauldron++;
            else if (ing.test(new ItemStack(Items.STONE_BRICKS))) bricks++;
        }
        check(flesh == 3 && bone == 2 && cauldron == 1 && bricks == 3, "腐肉の祭壇のレシピ構成が不正: " + flesh + "," + bone + "," + cauldron + "," + bricks);
        ClaudeGanbate.LOGGER.info("SELFTEST recipes verified: {}", all.size());
    }

    /** アイテム・ブロックの登録・モデル・テクスチャ・言語ファイル(日英)が全部そろっているか。 */
    private static void testAssets() {
        String ns = ClaudeGanbate.MODID;
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) for (int lv = 1; lv <= k.maxLevel(); lv++) ids.add("elixir_" + k.id + "_" + lv);
        check(ids.size() == ElixirModel.TOTAL_ELIXIRS, "エリクサーの種類数が不正: " + ids.size());
        for (String id : ids) {
            check(ForgeRegistries.ITEMS.getValue(new ResourceLocation(ns, id)) != Items.AIR, "アイテム未登録: " + id);
            check(exists("/assets/" + ns + "/models/item/" + id + ".json"), "モデルが無い: " + id);
            check(exists("/assets/" + ns + "/textures/item/" + id + ".png"), "テクスチャが無い: " + id);
        }
        // 祭壇(ブロック+アイテム)
        check(ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ns, "flesh_altar")) instanceof AltarBlock, "祭壇ブロックが未登録");
        check(ForgeRegistries.ITEMS.getValue(new ResourceLocation(ns, "flesh_altar")) instanceof net.minecraft.world.item.BlockItem, "祭壇のブロックアイテムが未登録");
        for (String path : new String[]{"/assets/" + ns + "/blockstates/flesh_altar.json", "/assets/" + ns + "/models/block/flesh_altar.json",
            "/assets/" + ns + "/models/item/flesh_altar.json", "/assets/" + ns + "/textures/block/flesh_altar_side.png",
            "/assets/" + ns + "/textures/block/flesh_altar_top.png", "/assets/" + ns + "/textures/block/flesh_altar_bottom.png",
            "/data/" + ns + "/loot_tables/blocks/flesh_altar.json", "/data/" + ns + "/recipes/flesh_altar.json",
            "/data/minecraft/tags/blocks/mineable/pickaxe.json"})
            check(exists(path), "祭壇のファイルが無い: " + path);
        java.util.List<String> keys = new java.util.ArrayList<>(java.util.List.of("zapoc.dawn", "zapoc.levelup", "zapoc.horde.warning", "zapoc.horde.wave", "zapoc.horde.dawn", "zapoc.horde.cleared",
            "zapoc.horde.reward", "zapoc.sleep.blocked", "zapoc.elixir.drunk", "zapoc.elixir.already", "tooltip.claudeganbate.permanent", "tooltip.claudeganbate.level",
            "block." + ns + ".flesh_altar", "container." + ns + ".flesh_altar", "tooltip.claudeganbate.altar.1", "tooltip.claudeganbate.altar.2",
            "zapoc.altar.current", "zapoc.altar.locked", "zapoc.altar.locked.hint", "zapoc.altar.learned", "zapoc.altar.click", "zapoc.altar.cost",
            "zapoc.altar.flesh", "zapoc.altar.info.1", "zapoc.altar.info.2", "zapoc.altar.made", "zapoc.altar.noflesh", "zapoc.altar.held"));
        for (String lang : new String[]{"en_us", "ja_jp"}) {
            JsonObject json = readJson("/assets/" + ns + "/lang/" + lang + ".json");
            if (json == null) { check(false, "言語ファイルが読めない: " + lang); continue; }
            for (String id : ids) check(json.has("item." + ns + "." + id), lang + " に item." + id + " の名前が無い");
            check(json.has("item." + ns + ".flesh_altar") || json.has("block." + ns + ".flesh_altar"), lang + " に祭壇の名前が無い");
            for (ElixirModel.Kind k : ElixirModel.Kind.values()) {
                check(json.has("tooltip." + ns + "." + k.id), lang + " に tooltip." + k.id + " が無い");
                check(json.has("zapoc.elixir.kind." + k.id), lang + " に zapoc.elixir.kind." + k.id + " が無い");
            }
            for (String key : keys) check(json.has(key), lang + " に " + key + " が無い");
        }
        ClaudeGanbate.LOGGER.info("SELFTEST assets verified: {} elixirs + altar", ids.size());
    }

    private static boolean exists(String path) { return SelfTest.class.getResource(path) != null; }

    private static JsonObject readJson(String path) {
        try (InputStream in = SelfTest.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { return null; }
    }

    private static void finish(MinecraftServer server) {
        ZombieEmpowerer.TEST_PLAYER = null;
        if (failures.isEmpty()) ClaudeGanbate.LOGGER.info("ZAPOC_SELFTEST_OK");
        else ClaudeGanbate.LOGGER.error("ZAPOC_SELFTEST_FAIL: {}", failures);
        server.halt(false);
    }
}

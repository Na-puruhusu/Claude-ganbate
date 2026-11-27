package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
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
                case 5 -> { testDayBased(); testElixirs(server); testRecipes(server); testAssets(); finish(server); phase = 6; }
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

    private static void testElixirs(MinecraftServer server) {
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) ElixirState.set(fp, k, 0);
        ElixirState.apply(fp);
        double hp0 = fp.getMaxHealth();

        // 飲むとレベルが上がり、最大体力が増え、空き瓶が返る
        ItemStack st = new ItemStack(ModItems.elixir(ElixirModel.Kind.VITALITY, 2));
        ItemStack out = st.getItem().finishUsingItem(st, level, fp);
        check(ElixirState.get(fp, ElixirModel.Kind.VITALITY) == 2, "エリクサーを飲んでもレベルが上がらない");
        check(Math.abs(fp.getMaxHealth() - (hp0 + ElixirModel.maxHpBonus(2))) < 0.01, "活力Lv2の最大体力が不正: " + fp.getMaxHealth());
        check(out.is(Items.GLASS_BOTTLE), "飲んだ後に空き瓶が返らない");

        // 同じ/低いレベルは飲めない(消費しない)
        ItemStack st2 = new ItemStack(ModItems.elixir(ElixirModel.Kind.VITALITY, 1));
        ItemStack out2 = st2.getItem().finishUsingItem(st2, level, fp);
        check(out2 == st2 && st2.getCount() == 1 && ElixirState.get(fp, ElixirModel.Kind.VITALITY) == 2, "低いレベルのエリクサーを消費してしまった");

        // 全種Lv5: 属性に反映される
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) ElixirState.set(fp, k, 5);
        ElixirState.apply(fp);
        check(Math.abs(fp.getMaxHealth() - (20.0 + 10.0)) < 0.01, "全種Lv5の最大体力 != 30: " + fp.getMaxHealth());
        check(Math.abs(fp.getAttributeValue(Attributes.ATTACK_DAMAGE) - (1.0 + 4.0)) < 0.01, "凶暴Lv5の攻撃力が不正: " + fp.getAttributeValue(Attributes.ATTACK_DAMAGE));
        check(Math.abs(fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1 - 1.2) < 0.01, "俊足Lv5の速度倍率が不正: " + fp.getAttributeValue(Attributes.MOVEMENT_SPEED) / 0.1);
        check(Math.abs(fp.getAttributeValue(Attributes.ARMOR) - 5.0) < 0.01, "頑強Lv5の防御が不正: " + fp.getAttributeValue(Attributes.ARMOR));
        check(Math.abs(fp.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) - 0.3) < 0.01, "頑強Lv5のKB耐性が不正");
        ElixirState.apply(fp);   // 冪等(二重に積まれない)
        check(Math.abs(fp.getMaxHealth() - 30.0) < 0.01, "apply を繰り返すと効果が重複する");

        // 死亡・帰還を想定した引き継ぎ
        Player fp2 = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("zapoc_test2".getBytes(StandardCharsets.UTF_8)), "zapoc_test2"));
        ElixirState.copy(fp, fp2);
        ElixirState.apply(fp2);
        check(ElixirState.get(fp2, ElixirModel.Kind.RESILIENCE) == 5 && Math.abs(fp2.getMaxHealth() - 30.0) < 0.01, "死亡後の引き継ぎでエリクサーが消えた");

        // エリクサーを飲んだ分、脅威(DDA)が上がる(強くなるほど少し厳しくなる)
        ThreatTracker.setOverride(fp.getUUID(), null);
        ThreatTracker.invalidate(fp.getUUID());
        double adj = ThreatTracker.get(fp).threat().adj();
        check(adj > 0.0, "エリクサーで強くなってもDDAの補正が上がらない: " + adj);
        ThreatTracker.setOverride(fp.getUUID(), 8.0);

        // 腐肉ドロップ
        Zombie alpha = EntityType.ZOMBIE.create(level);
        alpha.getPersistentData().putBoolean(ZombieEmpowerer.TAG_DONE, true);
        alpha.getPersistentData().putString(ZombieEmpowerer.TAG_VARIANT, "alpha");
        alpha.getPersistentData().putDouble(ZombieEmpowerer.TAG_LEVEL, 8.0);
        check(ElixirEvents.extraFlesh(alpha, 0, new Random(3)) >= 6, "アルファの追加腐肉ドロップが少ない");
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) ElixirState.set(fp, k, 0);
        ElixirState.apply(fp);
        ClaudeGanbate.LOGGER.info("SELFTEST elixirs verified");
    }

    private static void testRecipes(MinecraftServer server) {
        var ra = server.registryAccess();
        var all = server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(r -> r.getId().getNamespace().equals(ClaudeGanbate.MODID)).toList();
        check(all.size() == 1 + ElixirModel.Kind.values().length * ElixirModel.MAX_LEVEL, "mod のレシピ数が不正: " + all.size());

        Item essence = ModItems.PUTRID_ESSENCE.get();
        CraftingRecipe er = all.stream().filter(r -> r.getResultItem(ra).is(essence)).findFirst().orElse(null);
        check(er != null && er.getIngredients().size() == ElixirModel.FLESH_PER_ESSENCE
            && er.getIngredients().stream().allMatch(i -> i.test(new ItemStack(Items.ROTTEN_FLESH))), "腐肉のエッセンスのレシピが不正");

        for (ElixirModel.Kind k : ElixirModel.Kind.values()) {
            Item catalyst = ForgeRegistries.ITEMS.getValue(new ResourceLocation(k.catalyst));
            for (int lv = 1; lv <= ElixirModel.MAX_LEVEL; lv++) {
                Item result = ModItems.elixir(k, lv);
                CraftingRecipe r = all.stream().filter(x -> x.getResultItem(ra).is(result)).findFirst().orElse(null);
                String name = k.id + " Lv" + lv;
                if (r == null) { check(false, name + " のレシピが無い"); continue; }
                Item base = lv == 1 ? Items.GLASS_BOTTLE : ModItems.elixir(k, lv - 1);
                int nEss = 0, nCat = 0, nBase = 0;
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.test(new ItemStack(essence))) nEss++;
                    else if (ing.test(new ItemStack(catalyst))) nCat++;
                    else if (ing.test(new ItemStack(base))) nBase++;
                }
                check(nEss == ElixirModel.essenceCost(lv), name + " のエッセンス数 " + nEss + " != " + ElixirModel.essenceCost(lv));
                check(nCat == ElixirModel.catalystCost(lv), name + " の触媒数 " + nCat + " != " + ElixirModel.catalystCost(lv));
                check(nBase == 1 && r.getIngredients().size() == nEss + nCat + nBase, name + " のレシピ構成が不正");
            }
        }
        ClaudeGanbate.LOGGER.info("SELFTEST recipes verified: {}", all.size());
    }

    /** アイテムの登録・モデル・テクスチャ・言語ファイル(日英)が全部そろっているか。 */
    private static void testAssets() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add("putrid_essence");
        for (ElixirModel.Kind k : ElixirModel.Kind.values()) for (int lv = 1; lv <= ElixirModel.MAX_LEVEL; lv++) ids.add("elixir_" + k.id + "_" + lv);
        for (String id : ids) {
            check(ForgeRegistries.ITEMS.getValue(new ResourceLocation(ClaudeGanbate.MODID, id)) != Items.AIR, "アイテム未登録: " + id);
            check(exists("/assets/" + ClaudeGanbate.MODID + "/models/item/" + id + ".json"), "モデルが無い: " + id);
            check(exists("/assets/" + ClaudeGanbate.MODID + "/textures/item/" + id + ".png"), "テクスチャが無い: " + id);
        }
        for (String lang : new String[]{"en_us", "ja_jp"}) {
            JsonObject json = readJson("/assets/" + ClaudeGanbate.MODID + "/lang/" + lang + ".json");
            if (json == null) { check(false, "言語ファイルが読めない: " + lang); continue; }
            for (String id : ids) check(json.has("item." + ClaudeGanbate.MODID + "." + id), lang + " に item." + id + " の名前が無い");
            for (ElixirModel.Kind k : ElixirModel.Kind.values()) {
                check(json.has("tooltip." + ClaudeGanbate.MODID + "." + k.id), lang + " に tooltip." + k.id + " が無い");
                check(json.has("zapoc.elixir.kind." + k.id), lang + " に zapoc.elixir.kind." + k.id + " が無い");
            }
            for (String key : new String[]{"zapoc.dawn", "zapoc.levelup", "zapoc.horde.warning", "zapoc.horde.wave", "zapoc.horde.dawn", "zapoc.horde.cleared",
                "zapoc.horde.reward", "zapoc.sleep.blocked", "zapoc.elixir.drunk", "zapoc.elixir.already", "tooltip.claudeganbate.permanent", "tooltip.claudeganbate.level"})
                check(json.has(key), lang + " に " + key + " が無い");
        }
        ClaudeGanbate.LOGGER.info("SELFTEST assets verified: {} items", ids.size());
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

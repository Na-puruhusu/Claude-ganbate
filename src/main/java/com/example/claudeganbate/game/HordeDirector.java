package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.SpawnPlanner;
import com.example.claudeganbate.difficulty.ThreatCalculator.Threat;
import com.example.claudeganbate.difficulty.Tuning;
import com.example.claudeganbate.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * 出現のディレクター(L4D風)。プレイヤーごとに「環境スポーン(夜の散発的な襲撃)」と「ホードナイト」を管理する。
 *
 * ペース配分(緊張と緩和):
 *  - ホード終了後は一定時間「緩和」(環境スポーン停止)
 *  - 体力が35%未満で直近に被弾していたら新規スポーンを見送る(ホードは遅延)
 *  - プレイヤー周囲の既存ゾンビ数が上限に達していれば追加しない
 * 数値の根拠は SpawnPlanner と tools/DifficultySim.java を参照。
 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class HordeDirector {
    private HordeDirector() {}

    private static final class State {
        long nextAmbient = 0;
        long relaxUntil = 0;
        int wavesLeft = 0, waveIdx = 0, wavesTotal = 0;
        long nextWave = 0;
        boolean hordeActive = false;
        long lastDawnDay = -1;
        int lastLevelInt = -1;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    public static final String TAG_LAST_HORDE = "zapoc_last_horde";   // 最後にホードを起こした日(+1)。再ログインでの重複防止
    private static final int NIGHT_START = 13000, NIGHT_END = 23000, HORDE_START_LIMIT = 21000;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !ZapocConfig.ENABLED.get()) return;
        int tick = e.getServer().getTickCount();
        if (tick % 20 != 0) return;
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            try { tickPlayer(p, tick); }
            catch (Exception ex) { ClaudeGanbate.LOGGER.error("director error", ex); }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        STATES.remove(e.getEntity().getUUID());
        ThreatTracker.forget(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onSleep(PlayerSleepInBedEvent e) {
        if (!ZapocConfig.ENABLED.get() || !ZapocConfig.HORDES_ENABLED.get() || !ZapocConfig.BLOCK_SLEEP_ON_HORDE_NIGHT.get()) return;
        Player p = e.getEntity();
        if (p.level().isClientSide() || p.level().dimension() != Level.OVERWORLD) return;
        long t = p.level().getDayTime();
        if (SpawnPlanner.isHordeNight(t / 24000, ZapocConfig.HORDE_INTERVAL_DAYS.get())) {
            e.setResult(Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
            p.displayClientMessage(Component.translatable("zapoc.sleep.blocked"), true);
        }
    }

    private static void tickPlayer(ServerPlayer p, int now) {
        ServerLevel level = p.serverLevel();
        if (ZapocConfig.OVERWORLD_DIRECTOR_ONLY.get() && level.dimension() != Level.OVERWORLD) return;
        if (p.isSpectator() || p.isCreative() || level.getDifficulty() == Difficulty.PEACEFUL || !p.isAlive()) return;

        State st = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        Tuning tuning = ZapocConfig.tuning();
        Threat th = ThreatTracker.get(p).threat();
        double sl = th.spawnLevel();
        Random rng = new Random();

        long dayTime = level.getDayTime();
        long dayIndex = dayTime / 24000;
        int tod = (int) (dayTime % 24000);
        boolean night = tod >= NIGHT_START && tod < NIGHT_END;
        boolean fragile = p.getHealth() < p.getMaxHealth() * 0.35f && p.tickCount - p.getLastHurtByMobTimestamp() < 300;

        int interval = ZapocConfig.HORDE_INTERVAL_DAYS.get();

        // ---- 夜明けの通知(日数・脅威レベル・次のホードナイト) ----
        if (ZapocConfig.DAWN_MESSAGE.get() && tod < 1500 && st.lastDawnDay != dayIndex) {
            st.lastDawnDay = dayIndex;
            int lvInt = (int) Math.floor(th.progress());
            long nextH = ZapocConfig.HORDES_ENABLED.get() ? SpawnPlanner.nextHordeNight(dayIndex, interval) : -1;
            p.displayClientMessage(Component.translatable("zapoc.dawn", dayIndex + 1, String.format("%.1f", th.statLevel()),
                nextH < 0 ? "-" : String.valueOf(nextH + 1)), false);
            if (st.lastLevelInt >= 0 && lvInt > st.lastLevelInt) p.displayClientMessage(Component.translatable("zapoc.levelup", lvInt), false);
            st.lastLevelInt = lvInt;
        }

        // ---- ホードナイトの開始 ----
        if (ZapocConfig.HORDES_ENABLED.get() && !st.hordeActive && night && tod < HORDE_START_LIMIT
            && SpawnPlanner.isHordeNight(dayIndex, interval)
            && p.getPersistentData().getLong(TAG_LAST_HORDE) != dayIndex + 1) {
            p.getPersistentData().putLong(TAG_LAST_HORDE, dayIndex + 1);
            st.hordeActive = true;
            st.wavesTotal = st.wavesLeft = SpawnPlanner.hordeWaves(sl);
            st.waveIdx = 0;
            st.nextWave = now + 20 * 20;   // 警告から20秒後に第1波
            p.displayClientMessage(Component.translatable("zapoc.horde.warning"), false);
            p.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.5f, 0.6f);
            if (ZapocConfig.DEBUG_LOG.get()) ClaudeGanbate.LOGGER.info("horde start for {} L={} waves={}", p.getName().getString(), sl, st.wavesTotal);
        }

        // ---- ホードのウェーブ ----
        if (st.hordeActive) {
            if (!night) {                                   // 夜明けで終了
                endHorde(p, st, now, false, sl);
            } else if (st.wavesLeft > 0 && now >= st.nextWave) {
                if (fragile) {
                    st.nextWave = now + 15 * 20;            // 瀕死なら次の波を遅らせる
                } else {
                    int room = Math.min(ZapocConfig.MAX_NEARBY_ZOMBIES.get(), SpawnPlanner.hordeCap(sl, tuning)) - countNearby(level, p);
                    int size = Math.min(room, SpawnPlanner.hordeWaveSize(sl, tuning, rng));
                    int alphas = tuning.variants() ? SpawnPlanner.alphasInWave(sl, st.waveIdx, st.wavesTotal) : 0;
                    if (size > 0 || alphas > 0) {
                        spawnGroup(level, p, Math.max(0, size), alphas, true, false, rng);
                        p.displayClientMessage(Component.translatable("zapoc.horde.wave", st.waveIdx + 1, st.wavesTotal), true);
                    }
                    st.waveIdx++;
                    st.wavesLeft--;
                    st.nextWave = now + (long) (SpawnPlanner.hordeWaveGapSec(sl) * 20);
                    if (st.wavesLeft == 0) st.relaxUntil = now + 90 * 20;   // 最終波の後も戦いは続く。緩和は少し先
                }
            } else if (st.wavesLeft == 0 && now >= st.relaxUntil - 60 * 20 && countNearby(level, p) == 0) {
                endHorde(p, st, now, true, sl);                // 全滅させたら早期終了
            }
            return;                                         // ホード中は環境スポーンしない
        }

        // ---- 環境スポーン ----
        if (now < st.relaxUntil || now < st.nextAmbient || tuning.spawnScale() <= 0) return;
        boolean day = !night;
        if (day && sl < SpawnPlanner.DAY_MIN_LEVEL) { st.nextAmbient = now + 20 * 30; return; }
        double sec = SpawnPlanner.ambientIntervalSec(sl, night, tuning) * (0.75 + 0.5 * rng.nextDouble());
        st.nextAmbient = now + (long) (sec * 20);
        if (fragile) return;
        int cap = Math.min(ZapocConfig.MAX_NEARBY_ZOMBIES.get(), SpawnPlanner.ambientCap(sl, tuning));
        int room = cap - countNearby(level, p);
        int n = Math.min(room, SpawnPlanner.ambientGroupSize(sl, tuning, rng));
        if (n > 0) spawnGroup(level, p, n, 0, false, true, rng);   // 暗い場所のみ(昼は洞窟や日陰)
    }

    private static void endHorde(ServerPlayer p, State st, int now, boolean cleared, double level) {
        st.hordeActive = false;
        st.wavesLeft = 0;
        st.relaxUntil = now + 120 * 20;
        p.displayClientMessage(Component.translatable(cleared ? "zapoc.horde.cleared" : "zapoc.horde.dawn"), false);
        if (ZapocConfig.HORDE_REWARD.get() && p.isAlive()) {
            // 生き延びた報酬: 腐肉のエッセンス(撃退なら2倍)。エリクサーの材料
            int n = Math.max(1, st.wavesTotal * (cleared ? 2 : 1) + (int) (level / 3.0));
            ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(ModItems.PUTRID_ESSENCE.get(), n));
            p.displayClientMessage(Component.translatable("zapoc.horde.reward", n), false);
        }
    }

    public static void resetState(UUID id) { STATES.remove(id); }

    /** デバッグ用: /zapoc horde で即座にホードを開始する。 */
    public static void forceHorde(ServerPlayer p) {
        State st = STATES.computeIfAbsent(p.getUUID(), k -> new State());
        double sl = ThreatTracker.get(p).threat().spawnLevel();
        st.hordeActive = true;
        st.wavesTotal = st.wavesLeft = SpawnPlanner.hordeWaves(sl);
        st.waveIdx = 0;
        st.nextWave = p.server.getTickCount() + 20 * 3;
        p.displayClientMessage(Component.translatable("zapoc.horde.warning"), false);
    }

    static int countNearby(ServerLevel level, ServerPlayer p) {
        return level.getEntitiesOfClass(Zombie.class, p.getBoundingBox().inflate(48.0), z -> z.isAlive() && !(z instanceof ZombifiedPiglin)).size();
    }

    /** 1グループ(通常ゾンビ count 体 + アルファ alphas 体)を出現させる。実際に出た数を返す。 */
    public static int spawnGroup(ServerLevel level, ServerPlayer p, int count, int alphas, boolean horde, boolean needDark, Random rng) {
        int spawned = 0;
        double minD = horde ? 26 : 24, maxD = horde ? 40 : 38;
        for (int i = 0; i < count + alphas; i++) {
            Zombie z = EntityType.ZOMBIE.create(level);
            if (z == null) continue;
            BlockPos pos = null;
            for (int attempt = 0; attempt < 14 && pos == null; attempt++) pos = findSpot(level, p, z, minD, maxD, needDark, rng);
            if (pos == null) { z.discard(); continue; }
            z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, rng.nextFloat() * 360f, 0f);
            if (i < alphas) z.getPersistentData().putString(ZombieEmpowerer.TAG_FORCE, "alpha");
            if (horde) z.getPersistentData().putBoolean(ZombieEmpowerer.TAG_HORDE, true);
            z.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null, null);
            if (horde) z.setTarget(p);
            if (level.addFreshEntity(z)) spawned++;
        }
        return spawned;
    }

    private static BlockPos findSpot(ServerLevel level, ServerPlayer p, Zombie z, double minD, double maxD, boolean needDark, Random rng) {
        double ang = rng.nextDouble() * Math.PI * 2, d = minD + rng.nextDouble() * (maxD - minD);
        int x = (int) Math.floor(p.getX() + Math.cos(ang) * d), zz = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
        if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), zz))) return null;
        int py = p.getBlockY();
        for (int k = 0; k <= 16; k++) {                                   // プレイヤーの高さ付近から上下に探す(洞窟対応)
            int dy = (k % 2 == 0) ? k / 2 : -(k + 1) / 2;
            BlockPos pos = new BlockPos(x, py + dy, zz);
            BlockPos below = pos.below();
            BlockState floor = level.getBlockState(below);
            if (!floor.isFaceSturdy(level, below, Direction.UP) || !level.getFluidState(pos).isEmpty()) continue;
            z.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (!level.noCollision(z)) continue;
            if (needDark && level.getMaxLocalRawBrightness(pos) > 7) continue;
            return pos;
        }
        return null;
    }
}

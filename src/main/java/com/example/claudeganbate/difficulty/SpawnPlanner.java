package com.example.claudeganbate.difficulty;

import java.util.Random;

/** 出現量の設計。脅威(spawnLevel)から「1グループの数・頻度・同時存在数・ホード規模」を決める。 */
public final class SpawnPlanner {
    private SpawnPlanner() {}

    // ---- 環境スポーン(通常の夜の散発的な襲撃)----
    static final double[] AMBIENT_INTERVAL_SEC = {90, 85, 78, 70, 62, 55, 48, 42, 36, 32, 28, 26};
    public static final double DAY_INTERVAL_MULT = 2.5;   // 昼の間隔は2.5倍
    public static final double DAY_MIN_LEVEL = 4.0;       // 昼の環境スポーンはステージ4以降のみ

    public static double ambientGroupMean(double level, Tuning t) { return (2.0 + 0.50 * level) * t.spawnScale(); }
    public static int ambientGroupSize(double level, Tuning t, Random r) { return stochasticRound(ambientGroupMean(level, t), r); }
    public static double ambientIntervalSec(double level, boolean night, Tuning t) {
        double s = Curve.at(AMBIENT_INTERVAL_SEC, level) / Math.max(0.1, t.spawnScale());
        return night ? s : s * DAY_INTERVAL_MULT;
    }
    /** 周囲(48ブロック)にこの数以上ゾンビがいるなら環境スポーンしない。 */
    public static int ambientCap(double level, Tuning t) { return (int) Math.round((6 + 1.8 * level) * t.spawnScale()); }

    // ---- ホードナイト(N日ごとの大襲撃)----
    public static boolean isHordeNight(long dayIndex, int intervalDays) {
        return intervalDays > 0 && dayIndex >= intervalDays - 1 && (dayIndex + 1) % intervalDays == 0;
    }
    /** dayIndex 以降(当日を含む)で最初のホードナイトの日(0始まり)。interval<=0 なら -1。 */
    public static long nextHordeNight(long dayIndex, int intervalDays) {
        if (intervalDays <= 0) return -1;
        long d = Math.max(0, dayIndex);
        while (!isHordeNight(d, intervalDays)) d++;
        return d;
    }
    public static int hordeWaves(double level) { return level >= 8.0 ? 4 : level >= 2.5 ? 3 : 2; }
    public static double hordeWaveMean(double level, Tuning t) { return (3.0 + 0.55 * level) * t.spawnScale(); }
    public static int hordeWaveSize(double level, Tuning t, Random r) { return stochasticRound(hordeWaveMean(level, t), r); }
    public static double hordeWaveGapSec(double level) { return Math.max(15.0, 28.0 - 1.2 * level); }
    public static int hordeCap(double level, Tuning t) { return (int) Math.round((12 + 3.0 * level) * t.spawnScale()); }
    /** 指揮官(ALPHA)の数。高ステージで最初のウェーブと中盤のウェーブに出る。 */
    public static int alphasInWave(double level, int waveIndex, int waves) {
        if (level < 6.0) return 0;
        if (waveIndex == 0) return level >= 9.0 ? 2 : 1;
        if (level >= 8.0 && waveIndex == waves / 2) return 1;
        return 0;
    }

    /** 期待値を保ったまま整数化(例: 3.4 → 70%で3, 30%で4)。 */
    static int stochasticRound(double mean, Random r) {
        int base = (int) Math.floor(mean);
        return base + (r.nextDouble() < (mean - base) ? 1 : 0);
    }
}

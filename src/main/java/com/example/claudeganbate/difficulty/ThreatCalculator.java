package com.example.claudeganbate.difficulty;

/**
 * 脅威レベル(Threat)の算出。ゾンビの強さ・出現量はすべてここで決まる1つの連続値から導く。
 *
 *   progress  = ステージ + 生存ボーナス + 世界日数ボーナス         (進行度ベース。基本はこれ)
 *   adj       = ln(装備Power / そのprogressの標準Power) * k        (DDA。装備が弱いと緩く、強いと厳しく)
 *   statLevel = progress + adj          … ゾンビの能力(HP/攻撃/速度/特殊)に使う
 *   spawnLevel= progress + 0.4 * adj    … 出現量に使う(装備での増減は能力より穏やかに)
 *
 * 生存ボーナスは「最後の死亡からの経過日数」で、死亡すると自然にリセットされる=特別な救済処理が不要。
 */
public final class ThreatCalculator {
    private ThreatCalculator() {}

    public static final double MAX_LEVEL = 11.0;
    public static final double SURVIVAL_PER_DAY = 0.12, SURVIVAL_CAP = 1.0;
    public static final double WORLD_PER_DAY = 0.02, WORLD_CAP = 0.5;
    public static final double DDA_K = 2.0, DDA_MIN = -2.0, DDA_MAX = 0.8;
    public static final double SPAWN_DDA_SHARE = 0.4;

    public record Inputs(int stage, double daysSinceDeath, double worldDay,
                         double maxHp, double armor, double toughness, double epf, double attack) {}

    public record Threat(int stage, double progress, double adj, double statLevel, double spawnLevel) {}

    public static double progress(int stage, double daysSinceDeath, double worldDay) {
        double surv = Curve.clamp(daysSinceDeath * SURVIVAL_PER_DAY, 0, SURVIVAL_CAP);
        double world = Curve.clamp(worldDay * WORLD_PER_DAY, 0, WORLD_CAP);
        return Curve.clamp(stage + surv + world, 0, MAX_LEVEL);
    }

    public static Threat compute(Inputs in, Tuning t) {
        double progress = progress(in.stage(), in.daysSinceDeath(), in.worldDay());
        double adj = 0;
        if (t.adaptive()) {
            double p = GearBench.power(in.maxHp(), in.armor(), in.toughness(), in.epf(), in.attack());
            double b = GearBench.benchPower(progress);
            adj = Curve.clamp(Math.log(p / b) * DDA_K, DDA_MIN, DDA_MAX);
        }
        double stat = Curve.clamp(progress + adj, 0, MAX_LEVEL);
        double spawn = Curve.clamp(progress + SPAWN_DDA_SHARE * adj, 0, MAX_LEVEL);
        return new Threat(in.stage(), progress, adj, stat, spawn);
    }
}

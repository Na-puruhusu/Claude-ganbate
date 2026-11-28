package com.example.claudeganbate.difficulty;

/**
 * 脅威レベル(Threat)の算出。ゾンビの強さ・出現量はすべてここで決まる1つの連続値から導く。
 *
 *   progress  = DayCurve(世界日数)                                (難易度は日数準拠。基本はこれ)
 *   adj       = ln(装備Power / そのprogressの標準Power) * k        (DDA。装備が弱いと緩く、強いと厳しく)
 *   statLevel = progress + adj          … ゾンビの能力(HP/攻撃/速度/特殊)に使う
 *   spawnLevel= progress + 0.4 * adj    … 出現量に使う(装備での増減は能力より穏やかに)
 *
 * 永続エリクサー(体力・耐久・ダメージ・スピード)の分も装備Powerに含まれる。標準プレイヤーは日数に応じた数の
 * エリクサーを持つ前提(GearBench.STANDARD_ELIXIRS)なので、作らない人はゾンビが弱く、作り込む人は少し厳しくなる。
 */
public final class ThreatCalculator {
    private ThreatCalculator() {}

    public static final double MAX_LEVEL = DayCurve.MAX_LEVEL;
    public static final double DDA_K = 2.0, DDA_MIN = -3.0, DDA_MAX = 0.8;
    public static final double SPAWN_DDA_SHARE = 0.4;

    /** maxHp は体力エリクサー込みの最大HP。attack は素の武器ダメージ(ダメージエリクサーは elixirs から掛ける)。 */
    public record Inputs(double worldDay, double maxHp, double armor, double toughness, double epf, double attack, ElixirModel.Levels elixirs) {}

    public record Threat(double day, double progress, double adj, double statLevel, double spawnLevel) {}

    public static Threat compute(Inputs in, Tuning t) {
        double progress = DayCurve.levelAt(in.worldDay(), t.daySpeed());
        double adj = 0;
        if (t.adaptive()) {
            double p = GearBench.power(in.maxHp(), in.armor(), in.toughness(), in.epf(), in.attack(), in.elixirs());
            double b = GearBench.benchPower(progress);
            adj = Curve.clamp(Math.log(p / b) * DDA_K, DDA_MIN, DDA_MAX);
        }
        double stat = Curve.clamp(progress + adj, 0, MAX_LEVEL);
        double spawn = Curve.clamp(progress + SPAWN_DDA_SHARE * adj, 0, MAX_LEVEL);
        return new Threat(in.worldDay(), progress, adj, stat, spawn);
    }
}

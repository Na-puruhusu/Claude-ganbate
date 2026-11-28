package com.example.claudeganbate.difficulty;

/**
 * 各ステージで「標準的なプレイヤー」が持つ装備・エリクサーのベンチマークと、強さ(Power)の計算。
 * 盾・金リンゴ・弓・地形利用は含めない(=近接のみ・素の状態)。実プレイは必ずこれより有利になるので、
 * モデル上でやや厳しめに調整しても実際には「ちょうど良い〜少し厳しい」に収まる。
 */
public final class GearBench {
    private GearBench() {}

    // index = 脅威レベル(0..10。その日数に到達した標準プレイヤー)。11要素目は端点
    //                                      S0    S1    S2    S3    S4    S5    S6    S7    S8    S9    S10   (end)
    public static final double[] ARMOR  = { 0,    4,    8,    12,   16,   18,   20,   20,   20,   20,   20,   20 };
    public static final double[] TOUGH  = { 0,    0,    0,    0,    2,    4,    6,    8,    8,    10,   12,   12 };
    public static final double[] EPF    = { 0,    0,    1,    3,    5,    7,    9,    11,   13,   15,   17,   17 };
    /** 対ゾンビの近接ダメージ(武器+エンチャ。ゾンビはアンデッドなのでスマイトも含む) */
    public static final double[] ATTACK = { 4,    5,    6,    7,    8,    9.5,  11,   12.5, 14,   15.5, 17,   17 };
    public static final double PLAYER_HP = 20.0;

    /** 標準プレイヤーがそのレベルまでに作り終えているエリクサーの数(ElixirModel.STANDARD_ORDER の先頭から)。 */
    public static final int[] STANDARD_ELIXIRS = { 0, 0, 0, 1, 2, 4, 6, 8, 10, 12, 14, 16 };

    private static final ElixirModel.Levels[] STD = new ElixirModel.Levels[STANDARD_ELIXIRS.length];
    static { for (int i = 0; i < STD.length; i++) STD[i] = ElixirModel.standardLevels(STANDARD_ELIXIRS[i]); }
    private static double[] column(java.util.function.ToDoubleFunction<ElixirModel.Levels> f) {
        double[] a = new double[STD.length];
        for (int i = 0; i < a.length; i++) a[i] = f.applyAsDouble(STD[i]);
        return a;
    }
    private static final double[] STD_HP    = column(ElixirModel.Levels::maxHpBonus);
    private static final double[] STD_DMG   = column(ElixirModel.Levels::damageMult);
    private static final double[] STD_TAKEN = column(ElixirModel.Levels::takenMult);
    private static final double[] STD_SPEED = column(ElixirModel.Levels::speedBonus);

    /** 整数ステージでの標準エリクサー。 */
    public static ElixirModel.Levels standardLevels(int stage) {
        return STD[Math.max(0, Math.min(STD.length - 1, stage))];
    }

    /** 1回あたり被ダメージ(armor/toughness/EPF)の軽減率。バニラ式。 */
    public static double reduction(double armor, double toughness, double epf, double rawHit) {
        double a = Math.min(20.0, Math.max(armor / 5.0, armor - 4.0 * rawHit / (toughness + 8.0))) / 25.0;
        double e = Math.min(20.0, Math.max(0.0, epf)) / 25.0;
        return 1.0 - (1.0 - a) * (1.0 - e);
    }

    /** 実効HP(基準となる1発6ダメージに対する)。 */
    public static double effectiveHp(double maxHp, double armor, double toughness, double epf) {
        return maxHp / (1.0 - reduction(armor, toughness, epf, 6.0));
    }

    /**
     * 総合力。攻防の幾何平均。maxHp には体力エリクサー込みの最大HPを渡す。
     * エリクサーのうち 耐久(被ダメージ軽減)・スピード(回避)は実効HPに、ダメージは攻撃に掛かる。
     */
    public static double power(double maxHp, double armor, double toughness, double epf, double attack, ElixirModel.Levels lv) {
        double eff = effectiveHp(maxHp, armor, toughness, epf) / lv.takenMult() / lv.evasion();
        return Math.sqrt(eff * Math.max(1.0, attack * lv.damageMult()));
    }

    public static double benchPower(double level) {
        double a = Curve.at(ARMOR, level), t = Curve.at(TOUGH, level), e = Curve.at(EPF, level), k = Curve.at(ATTACK, level);
        double hp = PLAYER_HP + Curve.at(STD_HP, level);
        double eff = effectiveHp(hp, a, t, e) / Curve.at(STD_TAKEN, level) * (1.0 + ElixirModel.SPEED_EVASION * Curve.at(STD_SPEED, level));
        return Math.sqrt(eff * Math.max(1.0, k * Curve.at(STD_DMG, level)));
    }
}

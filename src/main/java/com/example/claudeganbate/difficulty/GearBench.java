package com.example.claudeganbate.difficulty;

/**
 * 各ステージで「標準的なプレイヤー」が持つ装備のベンチマークと、装備の強さ(Power)の計算。
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

    /** 装備の総合力。攻防の幾何平均。 */
    public static double power(double maxHp, double armor, double toughness, double epf, double attack) {
        return Math.sqrt(effectiveHp(maxHp, armor, toughness, epf) * Math.max(1.0, attack));
    }

    public static double benchPower(double level) {
        double a = Curve.at(ARMOR, level), t = Curve.at(TOUGH, level), e = Curve.at(EPF, level), k = Curve.at(ATTACK, level);
        return power(PLAYER_HP, a, t, e, k);
    }
}

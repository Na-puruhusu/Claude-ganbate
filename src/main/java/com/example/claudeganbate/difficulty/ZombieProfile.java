package com.example.claudeganbate.difficulty;

/** 1体のゾンビに適用する最終能力。baseに対する倍率/加算で、Forge側が属性モディファイアとして付与する。 */
public record ZombieProfile(Variant variant, double hpMult, double damageMult, double speedMult,
                            double armorAdd, double followRange, double kbResist) {

    // index = 脅威レベル(0..11)
    static final double[] HP     = {1.00, 1.10, 1.25, 1.45, 1.70, 1.95, 2.20, 2.40, 2.60, 2.80, 3.00, 3.20};
    static final double[] DMG    = {1.00, 1.05, 1.15, 1.30, 1.50, 1.70, 1.90, 2.05, 2.20, 2.35, 2.50, 2.70};
    static final double[] ARMOR  = {0, 0, 1, 2, 3, 4, 5, 6, 8, 10, 12, 14};
    static final double[] SPEED  = {1.00, 1.00, 1.02, 1.04, 1.06, 1.08, 1.10, 1.12, 1.14, 1.16, 1.18, 1.20};
    static final double[] FOLLOW = {35, 38, 41, 44, 47, 50, 53, 56, 59, 62, 64, 64};
    static final double[] KB     = {0, 0, 0, 0, 0.03, 0.06, 0.09, 0.12, 0.15, 0.18, 0.20, 0.22};

    public static final double SPEED_CAP = 1.45;      // これ以上速いゾンビは理不尽(プレイヤーのスプリントを超える)
    public static final double BASE_HP = 20.0, BASE_DAMAGE = 3.0, BASE_ARMOR = 2.0;

    public static ZombieProfile of(double level, Variant v, Tuning t) {
        double hp = t.scaleDelta(Curve.at(HP, level)) * v.hp;
        double dmg = t.scaleDelta(Curve.at(DMG, level)) * v.dmg;
        double spd = Math.min(SPEED_CAP, t.scaleDelta(Curve.at(SPEED, level)) * v.speed);
        double armor = Curve.at(ARMOR, level) * t.statScale() + v.armor;
        double kb = Curve.clamp(Curve.at(KB, level) * t.statScale() + v.kb, 0, 0.9);
        return new ZombieProfile(v, Math.max(0.2, hp), Math.max(0.2, dmg), Math.max(0.5, spd),
            Math.min(20, armor), Curve.at(FOLLOW, level), kb);
    }

    public double maxHp()   { return BASE_HP * hpMult; }
    public double damage()  { return BASE_DAMAGE * damageMult; }
    public double armor()   { return BASE_ARMOR + armorAdd; }
}

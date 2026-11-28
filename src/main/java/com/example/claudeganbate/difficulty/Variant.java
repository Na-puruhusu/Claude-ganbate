package com.example.claudeganbate.difficulty;

import java.util.Random;

/**
 * ゾンビの種類。基礎能力(ZombieProfile)に対する倍率で表現し、見た目・挙動の差は Forge 側が付ける。
 * 出現割合は脅威レベルのカーブで決まる(WALKER は常に重み100)。ALPHA はホードの指揮官としてのみ出現。
 */
public enum Variant {
    // 防御(armor)は装備で実現する: ARMORED=革フルセット(7) / BRUTE=金のヘルメット(2) / ALPHA=鉄ヘルメット+鉄ブーツ(4)。
    //        id         hp    dmg   speed armor kb    weight(level 0..11)
    WALKER  ("walker",  1.00, 1.00, 1.00, 0,   0.0,  new double[]{100,100,100,100,100,100,100,100,100,100,100,100}),
    RUNNER  ("runner",  0.60, 0.80, 1.28, 0,   0.0,  new double[]{0,0,6,10,14,18,20,22,22,22,22,22}),
    ARMORED ("armored", 1.00, 1.00, 0.97, 7,   0.1,  new double[]{0,0,0,8,12,16,18,20,20,20,20,20}),
    BRUTE   ("brute",   2.20, 1.45, 0.86, 2,   0.45,  new double[]{0,0,0,0,4,7,9,11,13,14,15,15}),
    BLOATER ("bloater", 1.40, 0.60, 0.92, 0,   0.0,  new double[]{0,0,0,0,0,4,6,8,9,10,10,10}),
    ALPHA   ("alpha",   3.30, 1.30, 1.00, 4,   0.35,  new double[]{0,0,0,0,0,0,0,0,0,0,0,0});

    /** ALPHA のオーラ(耐性効果I=被ダメージ20%軽減)。Forge側と同じ値。 */
    public static final double AURA_DAMAGE_REDUCTION = 0.20;

    public final String id;
    public final double hp, dmg, speed, armor, kb;
    private final double[] weight;

    Variant(String id, double hp, double dmg, double speed, double armor, double kb, double[] weight) {
        this.id = id; this.hp = hp; this.dmg = dmg; this.speed = speed; this.armor = armor; this.kb = kb; this.weight = weight;
    }

    public double weightAt(double level) { return Curve.at(weight, level); }

    public static Variant byId(String id) {
        for (Variant v : values()) if (v.id.equals(id)) return v;
        return WALKER;
    }

    /** 通常スポーン用の抽選(ALPHAは含まない)。variants=false なら常に WALKER。 */
    public static Variant pick(double level, boolean variants, Random rng) {
        if (!variants) return WALKER;
        double total = 0;
        for (Variant v : values()) if (v != ALPHA) total += v.weightAt(level);
        double r = rng.nextDouble() * total;
        for (Variant v : values()) {
            if (v == ALPHA) continue;
            r -= v.weightAt(level);
            if (r < 0) return v;
        }
        return WALKER;
    }

    /** 日光で燃えない個体の確率。ステージ後半の「昼も安全ではない」圧力。 */
    private static final double[] SUN_HARDENED = {0,0,0,0,0,0.05,0.10,0.16,0.22,0.30,0.38,0.40};
    public static double sunHardenedChance(double level) { return Curve.at(SUN_HARDENED, level); }
}

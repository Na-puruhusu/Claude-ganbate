package com.example.claudeganbate.difficulty;

/**
 * 日数 → 脅威レベルのカーブ。難易度は「世界の経過日数」だけで決まる。
 *
 * 序盤は丁寧に(最初の1〜3日は穏やか)、中盤は約1週間で2レベル、終盤は伸びが緩やかになる。
 * 「その日数のときの標準的なプレイヤー」が持つ装備を GearBench が定義し、その差を DDA で補正する。
 */
public final class DayCurve {
    private DayCurve() {}

    /** index = 脅威レベル, 値 = そのレベルに到達する世界日数(daySpeed=1のとき)。L10は80日目、L11は100日目。 */
    public static final double[] DAY_AT_LEVEL = {0, 3, 7, 12, 18, 25, 33, 42, 52, 64, 80, 100};
    public static final double MAX_LEVEL = DAY_AT_LEVEL.length - 1;

    /** daySpeed: 2.0 なら時間が2倍速で進む(同じ日数で到達するレベルが上がる)。 */
    public static double levelAt(double worldDay, double daySpeed) {
        double d = Math.max(0.0, worldDay) * Math.max(0.01, daySpeed);
        if (d >= DAY_AT_LEVEL[DAY_AT_LEVEL.length - 1]) return MAX_LEVEL;
        for (int i = 0; i < DAY_AT_LEVEL.length - 1; i++) {
            if (d < DAY_AT_LEVEL[i + 1]) return i + (d - DAY_AT_LEVEL[i]) / (DAY_AT_LEVEL[i + 1] - DAY_AT_LEVEL[i]);
        }
        return MAX_LEVEL;
    }

    /** レベルに到達する世界日数(levelAt の逆関数)。 */
    public static double dayAt(double level, double daySpeed) {
        return Curve.at(DAY_AT_LEVEL, level) / Math.max(0.01, daySpeed);
    }
}

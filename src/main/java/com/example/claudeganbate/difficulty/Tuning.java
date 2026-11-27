package com.example.claudeganbate.difficulty;

/** サーバー設定から渡される調整値。モデル側は Minecraft/Forge に依存しない。 */
public record Tuning(double statScale, double spawnScale, boolean adaptive, boolean variants) {
    /** バニラのHardは敵ダメージ1.5倍。本modの強化と二重で効くため、Hardでは本mod分のダメージ倍率にこの係数を掛ける(1.5×0.8=実質1.2倍)。 */
    public static final double HARD_DAMAGE_FACTOR = 0.8;

    public static final Tuning DEFAULT = new Tuning(1.0, 1.0, true, true);

    /** 1.0 からの差分だけを statScale 倍する(0 でバニラ、2 で差分2倍)。 */
    public double scaleDelta(double mult) {
        return 1.0 + (mult - 1.0) * statScale;
    }
}

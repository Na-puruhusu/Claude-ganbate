package com.example.claudeganbate.difficulty;

/**
 * 永続エリクサー(ゾンビ肉から作る永続ポーション)の効果テーブル。レベル1〜5。
 * 一度飲むと死亡しても消えない。レベルが上がるほど効果が増える。
 * 数値は純Javaで持ち、シミュレータ(tools/DifficultySim)と Forge 側が同じ値を使う。
 */
public final class ElixirModel {
    private ElixirModel() {}

    public static final int MAX_LEVEL = 5;
    public static final int FLESH_PER_ESSENCE = 9;      // 腐肉9個 → 腐肉のエッセンス1個

    public enum Kind {
        VITALITY("vitality", "minecraft:glistering_melon_slice"),   // 活力: 最大体力
        FEROCITY("ferocity", "minecraft:blaze_powder"),             // 凶暴: 攻撃力(ネザー進出が必要)
        FLEETFOOT("fleetfoot", "minecraft:sugar"),                  // 俊足: 移動速度
        RESILIENCE("resilience", "minecraft:iron_ingot");           // 頑強: 防御 + ノックバック耐性

        public final String id;
        /** 作成時に必要な触媒アイテム(レシピと tools/gen_assets.py がここを読む) */
        public final String catalyst;
        Kind(String id, String catalyst) { this.id = id; this.catalyst = catalyst; }

        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    // ---- レベルごとの効果(レベル0=未摂取) ----
    public static double maxHpBonus(int lv)  { return 2.0 * clampLv(lv); }      // +1ハート/Lv  (Lv5で+10=計30HP)
    public static double attackBonus(int lv) { return 0.8 * clampLv(lv); }      // +0.8/Lv      (Lv5で+4.0)
    public static double speedBonus(int lv)  { return 0.04 * clampLv(lv); }     // +4%/Lv       (Lv5で+20%)
    public static double armorBonus(int lv)  { return 1.0 * clampLv(lv); }      // +1/Lv        (Lv5で+5)
    public static double kbResistBonus(int lv) { return 0.06 * clampLv(lv); }   // +6%/Lv       (Lv5で+30%)

    // ---- 作成コスト(レベルN = 前のレベルの薬 + エッセンス + 触媒) ----
    /** そのレベルを作るのに必要なエッセンス数 */
    public static int essenceCost(int lv)  { return new int[]{0, 2, 3, 4, 5, 6}[clampLv(lv)]; }
    /** そのレベルを作るのに必要な触媒の数(最高レベルのみ2個) */
    public static int catalystCost(int lv) { return lv >= MAX_LEVEL ? 2 : 1; }

    /** 1種類をLv5まで上げるのに必要な腐肉の総数 */
    public static int totalFleshToMax() {
        int e = 0;
        for (int l = 1; l <= MAX_LEVEL; l++) e += essenceCost(l);
        return e * FLESH_PER_ESSENCE;
    }

    // ---- ゾンビ肉の入手 ----
    /**
     * プレイヤーがゾンビを倒したときの腐肉の追加ドロップ(バニラの0〜2個に上乗せ)。
     * 脅威レベルが高いほど、強い個体ほど多い。期待値を保ったまま整数化する。
     */
    public static int fleshBonus(double level, Variant v, int looting, java.util.Random r) {
        double mean = 0.35 + 0.10 * level + 0.4 * Math.max(0, looting) + switch (v) {
            case BRUTE -> 2.0;
            case ALPHA -> 6.0;
            case BLOATER, ARMORED -> 1.0;
            default -> 0.0;
        };
        int base = (int) Math.floor(mean);
        return base + (r.nextDouble() < (mean - base) ? 1 : 0);
    }

    public static int clampLv(int lv) { return Math.max(0, Math.min(MAX_LEVEL, lv)); }
}

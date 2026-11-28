package com.example.claudeganbate.difficulty;

import java.util.Random;

/**
 * 永続エリクサー(ゾンビ肉から祭壇で作る永続ポーション)の効果テーブル。
 *
 *   ダメージ 4段階 +15/30/45/60%   … プレイヤーが与えるダメージ
 *   スピード 4段階 +25/50/75/100%  … 移動速度
 *   耐久     3段階 20/40/60%軽減   … プレイヤーが受けるダメージ
 *   体力     5段階 ハート+2/4/6/8/10(=最大HP +4/8/12/16/20)
 *
 * 1個作るのに腐肉256個(=4スタック)。一度飲むと死亡しても消えない。レベルは1つずつ順に上げる。
 * 数値は純Javaで持ち、シミュレータ(tools/DifficultySim)・Forge側・アセット生成(tools/gen_assets.py)が同じ値を使う。
 */
public final class ElixirModel {
    private ElixirModel() {}

    public static final int FLESH_PER_ELIXIR = 256;      // 腐肉4スタック → エリクサー1個
    /** スピードの回避効果: 移動が速いほど敵の攻撃が当たりにくい(当たる確率 = 1/(1 + EVASION × 速度ボーナス))。 */
    public static final double SPEED_EVASION = 0.35;

    public enum Kind {
        DAMAGE("damage", new double[]{0.15, 0.30, 0.45, 0.60}),
        SPEED("speed", new double[]{0.25, 0.50, 0.75, 1.00}),
        DURABILITY("durability", new double[]{0.20, 0.40, 0.60}),
        HEALTH("health", new double[]{2, 4, 6, 8, 10});

        public final String id;
        /** レベルごとの効果量(ダメージ/スピード/耐久は割合、体力はハートの数) */
        public final double[] values;
        Kind(String id, double[] values) { this.id = id; this.values = values; }

        public int maxLevel() { return values.length; }
        /** レベル lv の効果量。0以下は0、上限超過は最大。 */
        public double value(int lv) { return lv <= 0 ? 0.0 : values[Math.min(lv, values.length) - 1]; }
        public int clampLv(int lv) { return Math.max(0, Math.min(values.length, lv)); }

        public static Kind byId(String id) {
            for (Kind k : values()) if (k.id.equals(id)) return k;
            return null;
        }
    }

    /** プレイヤーが飲み終えているレベル(0=未摂取)。 */
    public record Levels(int damage, int speed, int durability, int health) {
        public static final Levels NONE = new Levels(0, 0, 0, 0);

        public Levels {
            damage = Kind.DAMAGE.clampLv(damage); speed = Kind.SPEED.clampLv(speed);
            durability = Kind.DURABILITY.clampLv(durability); health = Kind.HEALTH.clampLv(health);
        }

        public int of(Kind k) {
            return switch (k) { case DAMAGE -> damage; case SPEED -> speed; case DURABILITY -> durability; case HEALTH -> health; };
        }
        public Levels with(Kind k, int lv) {
            return switch (k) {
                case DAMAGE -> new Levels(lv, speed, durability, health);
                case SPEED -> new Levels(damage, lv, durability, health);
                case DURABILITY -> new Levels(damage, speed, lv, health);
                case HEALTH -> new Levels(damage, speed, durability, lv);
            };
        }
        public int total() { return damage + speed + durability + health; }

        /** 最大HPの増加分(HP。ハート1個=2HP)。 */
        public double maxHpBonus() { return 2.0 * Kind.HEALTH.value(health); }
        /** 与ダメージ倍率(1.0〜1.6) */
        public double damageMult() { return 1.0 + Kind.DAMAGE.value(damage); }
        /** 移動速度の増加割合(0〜1.0) */
        public double speedBonus() { return Kind.SPEED.value(speed); }
        /** 被ダメージ倍率(1.0〜0.4) */
        public double takenMult() { return 1.0 - Kind.DURABILITY.value(durability); }
        /** 敵の攻撃が当たる確率の倍率(1.0〜0.74)。スピードで下がる。 */
        public double evasion() { return 1.0 / (1.0 + SPEED_EVASION * speedBonus()); }
    }

    // ---- 標準的なプレイヤーのエリクサー進行(難易度調整の基準) ----
    /** 標準プレイヤーが作っていく順番(全16個)。体力→耐久→ダメージ→スピードの順に1段階ずつ。 */
    public static final Kind[] STANDARD_ORDER = {
        Kind.HEALTH, Kind.DURABILITY, Kind.DAMAGE, Kind.SPEED,
        Kind.HEALTH, Kind.DAMAGE, Kind.DURABILITY, Kind.SPEED,
        Kind.HEALTH, Kind.DAMAGE, Kind.DURABILITY, Kind.SPEED,
        Kind.HEALTH, Kind.DAMAGE, Kind.SPEED, Kind.HEALTH,
    };
    public static final int TOTAL_ELIXIRS = STANDARD_ORDER.length;
    /** 全種類・全レベルを揃えるのに必要な腐肉(16個 × 256 = 4096個 = 64スタック)。 */
    public static int totalFleshToMax() { return TOTAL_ELIXIRS * FLESH_PER_ELIXIR; }

    /** 標準の順番で count 個作ったときのレベル。 */
    public static Levels standardLevels(int count) {
        Levels l = Levels.NONE;
        for (int i = 0; i < Math.min(count, STANDARD_ORDER.length); i++) l = l.with(STANDARD_ORDER[i], l.of(STANDARD_ORDER[i]) + 1);
        return l;
    }

    // ---- ゾンビ肉の入手 ----
    /**
     * プレイヤーがゾンビを倒したときの腐肉の追加ドロップ(バニラの0〜2個に上乗せ)。
     * 脅威レベルが高いほど、強い個体ほど多い。期待値を保ったまま整数化する。
     */
    public static double fleshBonusMean(double level, Variant v, int looting) {
        return 0.60 + 0.15 * level + 0.5 * Math.max(0, looting) + switch (v) {
            case BRUTE -> 2.5;
            case ALPHA -> 8.0;
            case BLOATER, ARMORED -> 1.0;
            default -> 0.0;
        };
    }
    public static int fleshBonus(double level, Variant v, int looting, Random r) {
        double mean = fleshBonusMean(level, v, looting);
        int base = (int) Math.floor(mean);
        return base + (r.nextDouble() < (mean - base) ? 1 : 0);
    }

    /** ホードナイトを生き延びたときの報酬(腐肉の個数)。 */
    public static int hordeRewardFlesh(double level) { return (int) Math.round(32 + 16 * level); }
}

package com.example.claudeganbate.config;

import com.example.claudeganbate.difficulty.Tuning;
import net.minecraftforge.common.ForgeConfigSpec;

/** サーバー設定(world/serverconfig/claudeganbate-server.toml)。難易度の考え方は docs/DIFFICULTY.md。 */
public final class ZapocConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.DoubleValue DAY_SPEED;
    public static final ForgeConfigSpec.DoubleValue STAT_SCALE;
    public static final ForgeConfigSpec.DoubleValue SPAWN_SCALE;
    public static final ForgeConfigSpec.BooleanValue ADAPTIVE;
    public static final ForgeConfigSpec.BooleanValue VARIANTS;
    public static final ForgeConfigSpec.BooleanValue HORDES_ENABLED;
    public static final ForgeConfigSpec.IntValue HORDE_INTERVAL_DAYS;
    public static final ForgeConfigSpec.BooleanValue BLOCK_SLEEP_ON_HORDE_NIGHT;
    public static final ForgeConfigSpec.BooleanValue DOOR_BREAKING;
    public static final ForgeConfigSpec.IntValue MAX_NEARBY_ZOMBIES;
    public static final ForgeConfigSpec.BooleanValue OVERWORLD_DIRECTOR_ONLY;
    public static final ForgeConfigSpec.BooleanValue EXTRA_FLESH_DROPS;
    public static final ForgeConfigSpec.BooleanValue HORDE_REWARD;
    public static final ForgeConfigSpec.BooleanValue DAWN_MESSAGE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("general");
        ENABLED = b.comment("modの全機能のON/OFF").define("enabled", true);
        b.pop();

        b.push("difficulty");
        STAT_SCALE = b.comment("ゾンビ強化の倍率。0=バニラのまま / 1=標準 / 2=強化分(バニラとの差)が2倍")
            .defineInRange("statScale", 1.0, 0.0, 3.0);
        SPAWN_SCALE = b.comment("追加ゾンビの出現量の倍率。0=追加出現なし / 1=標準")
            .defineInRange("spawnScale", 1.0, 0.0, 3.0);
        ADAPTIVE = b.comment("装備に応じた難易度調整(DDA)。装備が弱いと緩く、強いと厳しくなる。falseで進行度のみで決まる")
            .define("adaptiveDifficulty", true);
        VARIANTS = b.comment("特殊ゾンビ(ランナー/アーマード/ブルート/ブローター/アルファ)を出す").define("variants", true);
        DOOR_BREAKING = b.comment("脅威レベル2以上のゾンビが木のドアを壊す(鉄のドアは壊せない)").define("doorBreaking", true);
        b.pop();

        b.push("horde");
        HORDES_ENABLED = b.comment("ホードナイト(定期的な大襲撃)").define("hordesEnabled", true);
        HORDE_INTERVAL_DAYS = b.comment("ホードナイトの周期(日)。7なら7日目・14日目の夜").defineInRange("hordeIntervalDays", 7, 2, 60);
        BLOCK_SLEEP_ON_HORDE_NIGHT = b.comment("ホードナイトはベッドで夜を飛ばせない").define("blockSleepOnHordeNight", true);
        b.pop();

        b.push("elixir");
        EXTRA_FLESH_DROPS = b.comment("プレイヤーが倒したゾンビの腐肉ドロップを増やす(脅威レベルと強さに応じて。エリクサーの材料)")
            .define("extraFleshDrops", true);
        HORDE_REWARD = b.comment("ホードナイトを生き延びると腐肉のエッセンスがもらえる").define("hordeReward", true);
        b.pop();

        b.push("performance");
        MAX_NEARBY_ZOMBIES = b.comment("プレイヤー周囲48ブロックのゾンビ数の絶対上限(これ以上は追加出現しない)")
            .defineInRange("maxNearbyZombies", 60, 10, 200);
        OVERWORLD_DIRECTOR_ONLY = b.comment("追加出現(ホード/環境)をオーバーワールドに限定する。ゾンビの強化は全ディメンションで有効")
            .define("overworldDirectorOnly", true);
        DAWN_MESSAGE = b.comment("夜明けに日数・脅威レベル・次のホードナイトを通知する").define("dawnMessage", true);
        DEBUG_LOG = b.comment("デバッグログ").define("debugLog", false);
        b.pop();
        SPEC = b.build();
    }

    private ZapocConfig() {}

    public static Tuning tuning() {
        return new Tuning(STAT_SCALE.get(), SPAWN_SCALE.get(), ADAPTIVE.get(), VARIANTS.get(), DAY_SPEED.get());
    }
}

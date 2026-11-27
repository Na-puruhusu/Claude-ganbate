package com.example.claudeganbate.difficulty;

import java.util.List;
import java.util.function.Predicate;

/**
 * ゲーム進行度(ステージ 0..10)の判定表。バニラの進捗(advancement)の達成状況だけから決まる。
 * 進捗はセーブに残り死亡でも消えないため、ステージは「単調増加」で追加の保存データが要らない。
 */
public final class Milestones {
    private Milestones() {}

    public static final int MAX_STAGE = 10;

    public record Milestone(int stage, String advancementId, String label) {}

    public static final List<Milestone> ALL = List.of(
        new Milestone(1, "minecraft:story/upgrade_tools", "石の道具"),
        new Milestone(2, "minecraft:story/smelt_iron", "鉄の精錬"),
        new Milestone(3, "minecraft:story/obtain_armor", "鉄の防具"),
        new Milestone(3, "minecraft:story/iron_tools", "鉄のツルハシ"),
        new Milestone(4, "minecraft:story/mine_diamond", "ダイヤモンド"),
        new Milestone(5, "minecraft:story/enter_the_nether", "ネザー突入"),
        new Milestone(6, "minecraft:nether/find_fortress", "ネザー要塞"),
        new Milestone(6, "minecraft:nether/obtain_blaze_rod", "ブレイズロッド"),
        new Milestone(7, "minecraft:story/follow_ender_eye", "要塞発見"),
        new Milestone(8, "minecraft:story/enter_the_end", "エンド突入"),
        new Milestone(9, "minecraft:end/kill_dragon", "ドラゴン討伐"),
        // 10はドラゴン討伐後にのみ数える(ウィザー先行などでの飛び級を防ぐ)
        new Milestone(10, "minecraft:nether/netherite_armor", "ネザライト装備"),
        new Milestone(10, "minecraft:nether/summon_wither", "ウィザー召喚"),
        new Milestone(10, "minecraft:nether/create_full_beacon", "フルビーコン")
    );

    /** 達成済み進捗の判定関数からステージを求める。 */
    public static int stageOf(Predicate<String> done) {
        int best = 0;
        boolean dragon = done.test("minecraft:end/kill_dragon");
        for (Milestone m : ALL) {
            if (m.stage() > best && done.test(m.advancementId())) {
                if (m.stage() == MAX_STAGE && !dragon) continue;
                best = m.stage();
            }
        }
        return best;
    }
}

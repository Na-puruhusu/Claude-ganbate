package com.example.claudeganbate.game;

import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.ThreatCalculator;
import com.example.claudeganbate.difficulty.ThreatCalculator.Inputs;
import com.example.claudeganbate.difficulty.ThreatCalculator.Threat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの脅威レベルを算出してキャッシュする。
 * 難易度は世界の経過日数で決まり(DayCurve)、プレイヤーの装備(エリクサー込み)の強さで少し補正される(DDA)。
 * 保存データは不要(日数はワールド時間、装備は現在の状態から毎回導く)。
 */
public final class ThreatTracker {
    private ThreatTracker() {}

    public record Snapshot(Threat threat, Inputs inputs, long tick, boolean overridden) {}

    private static final long TTL_TICKS = 100;
    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();
    private static final Map<UUID, Double> OVERRIDE = new HashMap<>();

    public static void setOverride(UUID id, Double level) {
        if (level == null) OVERRIDE.remove(id); else OVERRIDE.put(id, level);
        CACHE.remove(id);
    }

    public static void forget(UUID id) { CACHE.remove(id); OVERRIDE.remove(id); }

    public static void invalidate(UUID id) { CACHE.remove(id); }

    public static Snapshot get(ServerPlayer p) {
        long now = p.server.getTickCount();
        Snapshot s = CACHE.get(p.getUUID());
        if (s != null && now - s.tick() < TTL_TICKS) return s;
        s = compute(p, now);
        CACHE.put(p.getUUID(), s);
        return s;
    }

    /** 世界の経過日数(オーバーワールドの時刻。寝て夜を飛ばした分も日数に数える)。 */
    public static double worldDay(ServerPlayer p) {
        return p.server.overworld().getDayTime() / 24000.0;
    }

    private static Snapshot compute(ServerPlayer p, long now) {
        double day = worldDay(p);
        // 防御・最大体力は属性から読む(体力エリクサー・装備・エンチャントの効果を含む)
        double armor = p.getAttributeValue(Attributes.ARMOR);
        double tough = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int epf = EnchantmentHelper.getDamageProtection(p.getArmorSlots(), p.damageSources().generic());
        double attack = bestMeleeVsUndead(p);                       // 素の武器ダメージ。ダメージエリクサーは elixirs から掛ける
        Inputs in = new Inputs(day, p.getMaxHealth(), armor, tough, epf, attack, ElixirState.levels(p));

        Threat th = ThreatCalculator.compute(in, ZapocConfig.tuning());
        Double ov = OVERRIDE.get(p.getUUID());
        if (ov != null) th = new Threat(day, ov, 0, ov, ov);
        return new Snapshot(th, in, now, ov != null);
    }

    /** ホットバー上で最も強い近接武器の対アンデッドダメージ(素手=1)。持ち替えでDDAが揺れないようにする。 */
    static double bestMeleeVsUndead(ServerPlayer p) {
        double best = 1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            double d = 1.0;
            for (AttributeModifier m : st.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
                if (m.getOperation() == AttributeModifier.Operation.ADDITION) d += m.getAmount();
            }
            d += EnchantmentHelper.getDamageBonus(st, MobType.UNDEAD);
            best = Math.max(best, Math.min(d, 30.0));
        }
        return best;
    }
}

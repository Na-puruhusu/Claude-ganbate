package com.example.claudeganbate.game;

import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.Milestones;
import com.example.claudeganbate.difficulty.ThreatCalculator;
import com.example.claudeganbate.difficulty.ThreatCalculator.Inputs;
import com.example.claudeganbate.difficulty.ThreatCalculator.Threat;
import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの脅威レベルを算出してキャッシュする。ステージは進捗(advancement)から毎回導くので保存データは不要。
 * 生存日数は統計 TIME_SINCE_DEATH(死亡でリセットされる)を使うので、死亡すると自然に緩和される。
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

    public static Snapshot get(ServerPlayer p) {
        long now = p.server.getTickCount();
        Snapshot s = CACHE.get(p.getUUID());
        if (s != null && now - s.tick() < TTL_TICKS) return s;
        s = compute(p, now);
        CACHE.put(p.getUUID(), s);
        return s;
    }

    private static Snapshot compute(ServerPlayer p, long now) {
        MinecraftServer server = p.server;
        PlayerAdvancements pa = p.getAdvancements();
        int stage = Milestones.stageOf(id -> {
            Advancement a = server.getAdvancements().getAdvancement(new ResourceLocation(id));
            return a != null && pa.getOrStartProgress(a).isDone();
        });
        double daysSinceDeath = p.getStats().getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)) / 24000.0;
        double worldDay = server.overworld().getDayTime() / 24000.0;

        double armor = p.getArmorValue();
        double tough = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int epf = EnchantmentHelper.getDamageProtection(p.getArmorSlots(), p.damageSources().generic());
        Inputs in = new Inputs(stage, daysSinceDeath, worldDay, p.getMaxHealth(), armor, tough, epf, bestMeleeVsUndead(p));

        Double ov = OVERRIDE.get(p.getUUID());
        Threat th = ThreatCalculator.compute(in, ZapocConfig.tuning());
        if (ov != null) th = new Threat(stage, ov, 0, ov, ov);
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

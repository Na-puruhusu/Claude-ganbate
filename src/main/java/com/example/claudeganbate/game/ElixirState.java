package com.example.claudeganbate.game;

import com.example.claudeganbate.difficulty.ElixirModel.Kind;
import com.example.claudeganbate.difficulty.ElixirModel.Levels;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * プレイヤーの永続エリクサーレベルの保存と、効果の反映。
 * レベルはプレイヤーの永続NBT(zapoc_elixirs)に持ち、死亡・帰還時は PlayerEvent.Clone で引き継ぐ。
 *  - 体力 / スピード: 属性モディファイア(固定UUIDで毎回付け直す=冪等)
 *  - ダメージ / 耐久: ElixirEvents の LivingHurtEvent で与ダメージ・被ダメージに倍率を掛ける
 */
public final class ElixirState {
    private ElixirState() {}

    public static final String ROOT = "zapoc_elixirs";

    private static final UUID U_HP = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a01");
    private static final UUID U_SPD = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a03");
    // 旧バージョン(攻撃力・防御・KB耐性のエリクサー)のモディファイア。残っていたら取り除く。
    private static final UUID[] LEGACY = {
        UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a02"), UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a04"),
        UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a05")};

    public static int get(Player p, Kind k) {
        return k.clampLv(p.getPersistentData().getCompound(ROOT).getInt(k.id));
    }

    public static Levels levels(Player p) {
        return new Levels(get(p, Kind.DAMAGE), get(p, Kind.SPEED), get(p, Kind.DURABILITY), get(p, Kind.HEALTH));
    }

    public static void set(Player p, Kind k, int lv) {
        CompoundTag root = p.getPersistentData().getCompound(ROOT);
        root.putInt(k.id, k.clampLv(lv));
        p.getPersistentData().put(ROOT, root);
        if (p instanceof net.minecraft.server.level.ServerPlayer sp) ThreatTracker.invalidate(sp.getUUID());
    }

    /** 死亡・帰還で作り直されたプレイヤーへ、永続データ(エリクサー・ホード履歴)を引き継ぐ。 */
    public static void copy(Player from, Player to) {
        if (from.getPersistentData().contains(ROOT)) to.getPersistentData().put(ROOT, from.getPersistentData().getCompound(ROOT).copy());
        if (from.getPersistentData().contains(HordeDirector.TAG_LAST_HORDE)) {
            to.getPersistentData().putLong(HordeDirector.TAG_LAST_HORDE, from.getPersistentData().getLong(HordeDirector.TAG_LAST_HORDE));
        }
    }

    /** 保存されたレベルを属性に反映する(体力・スピード)。 */
    public static void apply(Player p) {
        Levels lv = levels(p);
        setModifier(p, Attributes.MAX_HEALTH, U_HP, "zapoc_elixir_health", lv.maxHpBonus(), AttributeModifier.Operation.ADDITION);
        setModifier(p, Attributes.MOVEMENT_SPEED, U_SPD, "zapoc_elixir_speed", lv.speedBonus(), AttributeModifier.Operation.MULTIPLY_BASE);
        for (UUID id : LEGACY) {
            for (Attribute a : new Attribute[]{Attributes.ATTACK_DAMAGE, Attributes.ARMOR, Attributes.KNOCKBACK_RESISTANCE}) {
                AttributeInstance inst = p.getAttribute(a);
                if (inst != null) inst.removeModifier(id);
            }
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void setModifier(Player p, Attribute attr, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (amount > 1e-9) inst.addPermanentModifier(new AttributeModifier(id, name, amount, op));
    }

    /** ElixirModel を直接触る側(コマンド等)用: 全レベルの合計。 */
    public static int total(Player p) { return levels(p).total(); }
}

package com.example.claudeganbate.game;

import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.difficulty.ElixirModel.Kind;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * プレイヤーの永続エリクサーレベルの保存と、属性への反映。
 * レベルはプレイヤーの永続NBT(zapoc_elixirs)に持ち、死亡・帰還時は PlayerEvent.Clone で引き継ぐ。
 * 属性モディファイアは固定UUIDで毎回付け直す(=冪等。数値をモデルから変えても次回ログインで反映される)。
 */
public final class ElixirState {
    private ElixirState() {}

    public static final String ROOT = "zapoc_elixirs";

    private static final UUID U_HP = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a01");
    private static final UUID U_ATK = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a02");
    private static final UUID U_SPD = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a03");
    private static final UUID U_ARM = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a04");
    private static final UUID U_KB = UUID.fromString("7a1c9e10-3b5d-4f60-8a21-6d0e5c4b3a05");

    public static int get(Player p, Kind k) {
        return ElixirModel.clampLv(p.getPersistentData().getCompound(ROOT).getInt(k.id));
    }

    public static void set(Player p, Kind k, int lv) {
        CompoundTag root = p.getPersistentData().getCompound(ROOT);
        root.putInt(k.id, ElixirModel.clampLv(lv));
        p.getPersistentData().put(ROOT, root);
        if (p instanceof net.minecraft.server.level.ServerPlayer sp) ThreatTracker.invalidate(sp.getUUID());
    }

    /** 死亡・帰還で作り直されたプレイヤーへ、永続データ(エリクサー・ホード履歴)を引き継ぐ。 */
    public static void copy(Player from, Player to) {
        for (String key : new String[]{ROOT}) {
            if (from.getPersistentData().contains(key)) to.getPersistentData().put(key, from.getPersistentData().getCompound(key).copy());
        }
        if (from.getPersistentData().contains(HordeDirector.TAG_LAST_HORDE)) {
            to.getPersistentData().putLong(HordeDirector.TAG_LAST_HORDE, from.getPersistentData().getLong(HordeDirector.TAG_LAST_HORDE));
        }
    }

    /** 保存されたレベルを属性に反映する。 */
    public static void apply(Player p) {
        int vit = get(p, Kind.VITALITY), fer = get(p, Kind.FEROCITY), flt = get(p, Kind.FLEETFOOT), res = get(p, Kind.RESILIENCE);
        set(p, Attributes.MAX_HEALTH, U_HP, "zapoc_elixir_vitality", ElixirModel.maxHpBonus(vit), AttributeModifier.Operation.ADDITION);
        set(p, Attributes.ATTACK_DAMAGE, U_ATK, "zapoc_elixir_ferocity", ElixirModel.attackBonus(fer), AttributeModifier.Operation.ADDITION);
        set(p, Attributes.MOVEMENT_SPEED, U_SPD, "zapoc_elixir_fleetfoot", ElixirModel.speedBonus(flt), AttributeModifier.Operation.MULTIPLY_BASE);
        set(p, Attributes.ARMOR, U_ARM, "zapoc_elixir_resilience_armor", ElixirModel.armorBonus(res), AttributeModifier.Operation.ADDITION);
        set(p, Attributes.KNOCKBACK_RESISTANCE, U_KB, "zapoc_elixir_resilience_kb", ElixirModel.kbResistBonus(res), AttributeModifier.Operation.ADDITION);
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static void set(Player p, Attribute attr, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        inst.removeModifier(id);
        if (amount > 1e-9) inst.addPermanentModifier(new AttributeModifier(id, name, amount, op));
    }
}

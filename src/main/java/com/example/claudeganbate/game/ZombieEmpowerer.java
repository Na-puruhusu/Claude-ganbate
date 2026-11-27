package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.Tuning;
import com.example.claudeganbate.difficulty.Variant;
import com.example.claudeganbate.difficulty.ZombieProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;
import java.util.UUID;

/** ゾンビがワールドに入った瞬間、最寄りプレイヤーの脅威レベルに応じて能力・種類・装備を決める。 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class ZombieEmpowerer {
    private ZombieEmpowerer() {}

    public static final String TAG_DONE = "zapoc_done";
    public static final String TAG_VARIANT = "zapoc_variant";
    public static final String TAG_FORCE = "zapoc_force";
    public static final String TAG_HORDE = "zapoc_horde";
    public static final String TAG_LEVEL = "zapoc_level";

    private static final UUID U_HP = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a01");
    private static final UUID U_DMG = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a02");
    private static final UUID U_SPD = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a03");
    private static final UUID U_ARM = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a04");
    private static final UUID U_FOL = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a05");
    private static final UUID U_KB = UUID.fromString("5b0d6f6a-1d63-4d9a-9b40-0a1c2e3f4a06");

    /** 脅威レベルがこれ以上なら木のドアを壊す(鉄のドアは壊せない) */
    public static final double DOOR_BREAK_LEVEL = 2.0;

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof Zombie z) || z instanceof ZombifiedPiglin) return;
        if (!ZapocConfig.ENABLED.get() || !(e.getLevel() instanceof ServerLevel level)) return;

        CompoundTag tag = z.getPersistentData();
        if (tag.getBoolean(TAG_DONE)) {            // チャンク再読込: 属性は保存済み。目標(goal)だけ付け直す
            attachBehavior(z, tag.getDouble(TAG_LEVEL));
            return;
        }
        Player near = level.getNearestPlayer(z, 128.0);
        if (!(near instanceof ServerPlayer sp)) return;   // 周囲にプレイヤーがいなければ後で(次回join時に)処理

        Tuning tuning = ZapocConfig.tuning();
        double lvl = ThreatTracker.get(sp).threat().statLevel();

        boolean eligible = (z.getType() == EntityType.ZOMBIE || z.getType() == EntityType.HUSK) && !z.isBaby();
        String forced = tag.getString(TAG_FORCE);
        Variant v;
        if (!forced.isEmpty()) v = Variant.byId(forced);
        else if (eligible) v = Variant.pick(lvl, tuning.variants(), new Random(z.getRandom().nextLong()));
        else v = Variant.WALKER;

        ZombieProfile p = ZombieProfile.of(lvl, v, tuning);
        double dmgMult = p.damageMult();
        if (level.getDifficulty() == Difficulty.HARD) dmgMult *= Tuning.HARD_DAMAGE_FACTOR;
        double spdMult = z.isBaby() ? 1.0 + (p.speedMult() - 1.0) * 0.5 : p.speedMult();   // 子ゾンビは既に速い
        double follow = tag.getBoolean(TAG_HORDE) ? Math.max(p.followRange(), 52.0) : p.followRange();

        mod(z, Attributes.MAX_HEALTH, U_HP, "zapoc_hp", p.hpMult() - 1.0, AttributeModifier.Operation.MULTIPLY_BASE);
        mod(z, Attributes.ATTACK_DAMAGE, U_DMG, "zapoc_dmg", dmgMult - 1.0, AttributeModifier.Operation.MULTIPLY_BASE);
        mod(z, Attributes.MOVEMENT_SPEED, U_SPD, "zapoc_spd", spdMult - 1.0, AttributeModifier.Operation.MULTIPLY_BASE);
        mod(z, Attributes.FOLLOW_RANGE, U_FOL, "zapoc_fol", follow - 35.0, AttributeModifier.Operation.ADDITION);
        mod(z, Attributes.KNOCKBACK_RESISTANCE, U_KB, "zapoc_kb", p.kbResist(), AttributeModifier.Operation.ADDITION);
        // 防御: 種類固有の分は装備で実現する(下のequip)。ここではレベル由来の分だけ
        mod(z, Attributes.ARMOR, U_ARM, "zapoc_arm", p.armorAdd() - v.armor, AttributeModifier.Operation.ADDITION);
        z.setHealth(z.getMaxHealth());

        equip(z, v);
        if (v == Variant.ALPHA) z.addEffect(new MobEffectInstance(MobEffects.GLOWING, Integer.MAX_VALUE, 0, false, false));
        if (eligible && z.getRandom().nextDouble() < Variant.sunHardenedChance(lvl)) {
            z.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        }

        tag.putString(TAG_VARIANT, v.id);
        tag.putDouble(TAG_LEVEL, lvl);
        tag.putBoolean(TAG_DONE, true);
        attachBehavior(z, lvl);
        if (ZapocConfig.DEBUG_LOG.get()) ClaudeGanbate.LOGGER.info("zombie {} -> {} L={} hp={}", z.getType(), v.id, String.format("%.2f", lvl), z.getMaxHealth());
    }

    /** goalは保存されないので、join のたびに付ける。 */
    private static void attachBehavior(Zombie z, double lvl) {
        if (ZapocConfig.DOOR_BREAKING.get() && lvl >= DOOR_BREAK_LEVEL && z.getNavigation() != null) {
            z.goalSelector.addGoal(1, new BreakDoorGoal(z, d -> true));
        }
    }

    private static void mod(Zombie z, Attribute attr, UUID id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = z.getAttribute(attr);
        if (inst == null) return;
        AttributeModifier old = inst.getModifier(id);
        if (old != null) inst.removeModifier(id);
        if (Math.abs(amount) < 1e-6) return;
        inst.addPermanentModifier(new AttributeModifier(id, name, amount, op));
    }

    /** 見た目の目印も兼ねる装備。ドロップは0(装備の水増しで難易度設計が崩れないように)。 */
    private static void equip(Zombie z, Variant v) {
        switch (v) {
            case ARMORED -> {
                int color = 0x2f2f33;
                set(z, EquipmentSlot.HEAD, dyed(Items.LEATHER_HELMET, color));
                set(z, EquipmentSlot.CHEST, dyed(Items.LEATHER_CHESTPLATE, color));
                set(z, EquipmentSlot.LEGS, dyed(Items.LEATHER_LEGGINGS, color));
                set(z, EquipmentSlot.FEET, dyed(Items.LEATHER_BOOTS, color));
            }
            case BRUTE -> set(z, EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            case ALPHA -> {
                set(z, EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                set(z, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            }
            case BLOATER -> set(z, EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            default -> { }
        }
    }

    private static ItemStack dyed(net.minecraft.world.item.Item item, int color) {
        ItemStack s = new ItemStack(item);
        s.getOrCreateTagElement("display").putInt("color", color);
        return s;
    }

    private static void set(Zombie z, EquipmentSlot slot, ItemStack s) {
        z.setItemSlot(slot, s);
        z.setDropChance(slot, 0.0f);
    }
}

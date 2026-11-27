package com.example.claudeganbate.item;

import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.game.ElixirState;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 永続エリクサー。飲むとその種類の永続レベルが上がり(死亡しても消えない)、レベルに応じて効果が増える。
 * すでに同じ/高いレベルなら飲めない(無駄打ち防止)。飲むと空き瓶が返る。
 */
public class ElixirItem extends Item {
    public final ElixirModel.Kind kind;
    public final int level;

    public ElixirItem(ElixirModel.Kind kind, int level, Properties props) {
        super(props);
        this.kind = kind;
        this.level = level;
    }

    @Override public UseAnim getUseAnimation(ItemStack s) { return UseAnim.DRINK; }
    @Override public int getUseDuration(ItemStack s) { return 32; }
    @Override public boolean isFoil(ItemStack s) { return level >= 4; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand h) {
        ItemStack st = p.getItemInHand(h);
        if (!l.isClientSide && ElixirState.get(p, kind) >= level) {
            p.displayClientMessage(Component.translatable("zapoc.elixir.already", Component.translatable(kindKey())), true);
            return InteractionResultHolder.fail(st);
        }
        p.startUsingItem(h);
        return InteractionResultHolder.consume(st);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack st, Level l, LivingEntity e) {
        if (!(e instanceof Player p)) return st;
        if (!l.isClientSide && p instanceof ServerPlayer sp) {
            if (ElixirState.get(sp, kind) >= level) return st;            // 念のため(使用中にレベルが上がった等)
            ElixirState.set(sp, kind, level);
            ElixirState.apply(sp);
            sp.setHealth(sp.getMaxHealth());
            l.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 1.0f, 0.8f);
            l.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.5f, 1.4f);
            CriteriaTriggers.CONSUME_ITEM.trigger(sp, st);
            sp.displayClientMessage(Component.translatable("zapoc.elixir.drunk", Component.translatable(kindKey()), level), false);
        } else if (l.isClientSide) {
            return st;
        }
        if (!p.getAbilities().instabuild) {
            st.shrink(1);
            ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
            if (st.isEmpty()) return bottle;
            if (!p.getInventory().add(bottle)) p.drop(bottle, false);
        }
        return st;
    }

    private String kindKey() { return "zapoc.elixir.kind." + kind.id; }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level l, List<Component> tip, TooltipFlag f) {
        tip.add(Component.translatable("tooltip.claudeganbate.permanent").withStyle(ChatFormatting.DARK_GREEN));
        String v1 = fmt(switch (kind) {
            case VITALITY -> ElixirModel.maxHpBonus(level) / 2.0;
            case FEROCITY -> ElixirModel.attackBonus(level);
            case FLEETFOOT -> ElixirModel.speedBonus(level) * 100.0;
            case RESILIENCE -> ElixirModel.armorBonus(level);
        });
        if (kind == ElixirModel.Kind.RESILIENCE) {
            tip.add(Component.translatable("tooltip.claudeganbate." + kind.id, v1, fmt(ElixirModel.kbResistBonus(level) * 100.0)).withStyle(ChatFormatting.GRAY));
        } else {
            tip.add(Component.translatable("tooltip.claudeganbate." + kind.id, v1).withStyle(ChatFormatting.GRAY));
        }
        tip.add(Component.translatable("tooltip.claudeganbate.level", level, ElixirModel.MAX_LEVEL).withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? Integer.toString((int) v) : String.format("%.1f", v);
    }
}

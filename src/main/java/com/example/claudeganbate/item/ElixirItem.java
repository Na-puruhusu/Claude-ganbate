package com.example.claudeganbate.item;

import com.example.claudeganbate.block.AltarMenu;
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
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 永続エリクサー(腐肉の祭壇で作る)。飲むとその種類の永続レベルが上がり(死亡しても消えない)、レベルに応じて効果が増える。
 * レベルは1つずつ順に上げる: 次のレベルのものしか飲めない(すでに同じ/高いレベルなら飲めない)。
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
    @Override public boolean isFoil(ItemStack s) { return level == kind.maxLevel(); }

    /** 飲めない理由のメッセージ(飲めるなら null)。 */
    @Nullable
    private Component refusal(Player p) {
        int cur = ElixirState.get(p, kind);
        if (cur >= level) return Component.translatable("zapoc.elixir.already", Component.translatable(kindKey()));
        if (cur < level - 1) return Component.translatable("zapoc.altar.locked.hint", level - 1);
        return null;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level l, Player p, InteractionHand h) {
        ItemStack st = p.getItemInHand(h);
        Component why = refusal(p);
        if (why != null) {
            if (!l.isClientSide) p.displayClientMessage(why, true);
            return InteractionResultHolder.fail(st);
        }
        p.startUsingItem(h);
        return InteractionResultHolder.consume(st);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack st, Level l, LivingEntity e) {
        if (!(e instanceof Player p)) return st;
        if (l.isClientSide) return st;
        if (p instanceof ServerPlayer sp) {
            if (refusal(sp) != null) return st;                  // 念のため(使用中にレベルが変わった等)
            ElixirState.set(sp, kind, level);
            ElixirState.apply(sp);
            sp.setHealth(sp.getMaxHealth());
            l.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 1.0f, 0.8f);
            l.playSound(null, sp.getX(), sp.getY(), sp.getZ(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.5f, 1.4f);
            CriteriaTriggers.CONSUME_ITEM.trigger(sp, st);
            sp.displayClientMessage(Component.translatable("zapoc.elixir.drunk", Component.translatable(kindKey()), level), false);
        }
        if (!p.getAbilities().instabuild) st.shrink(1);
        return st;
    }

    private String kindKey() { return "zapoc.elixir.kind." + kind.id; }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level l, List<Component> tip, TooltipFlag f) {
        tip.add(Component.translatable("tooltip.claudeganbate.permanent").withStyle(ChatFormatting.DARK_GREEN));
        tip.add(Component.translatable("tooltip.claudeganbate." + kind.id, AltarMenu.valueText(kind, level)).withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.claudeganbate.level", level, kind.maxLevel()).withStyle(ChatFormatting.DARK_GRAY));
    }
}

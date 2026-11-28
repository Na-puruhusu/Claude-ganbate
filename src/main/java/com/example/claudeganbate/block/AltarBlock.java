package com.example.claudeganbate.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.ChatFormatting;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 腐肉の祭壇: エリクサーを作る専用の台。右クリックで作成画面(AltarMenu)を開く。 */
public class AltarBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 14, 16);

    public AltarBlock(Properties props) { super(props); }

    @Override
    public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new AltarMenu(id, inv, ContainerLevelAccess.create(level, pos)),
                Component.translatable("container.claudeganbate.flesh_altar")));
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable BlockGetter g, List<Component> tip, TooltipFlag f) {
        tip.add(Component.translatable("tooltip.claudeganbate.altar.1").withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.claudeganbate.altar.2").withStyle(ChatFormatting.DARK_GREEN));
    }
}

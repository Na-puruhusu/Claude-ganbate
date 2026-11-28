package com.example.claudeganbate.block;

import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.difficulty.ElixirModel.Kind;
import com.example.claudeganbate.game.AltarCrafting;
import com.example.claudeganbate.game.ElixirState;
import com.example.claudeganbate.item.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * 腐肉の祭壇の画面。バニラの 9x5 チェスト画面を使う(クライアント側に追加コードは不要)。
 * 各行が1種類のエリクサー(ダメージ/スピード/耐久/体力)で、左端が種類、その右にレベル順のエリクサーが並ぶ。
 *   光っている = 習得済み / そのまま = 次に作れる(クリックで作成) / ガラス板 = まだ作れない
 * クリックの処理はサーバー側で行う(中のアイテムは取り出せない表示専用スロット)。
 */
public class AltarMenu extends AbstractContainerMenu {
    public static final int ROWS = 5, SIZE = ROWS * 9, INFO_SLOT = 4 * 9 + 4;

    private final ContainerLevelAccess access;
    private final Player player;
    private final SimpleContainer view = new SimpleContainer(SIZE);
    private long lastSig = Long.MIN_VALUE;

    public AltarMenu(int id, Inventory inv, ContainerLevelAccess access) {
        super(MenuType.GENERIC_9x5, id);
        this.access = access;
        this.player = inv.player;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < 9; c++) addSlot(new ViewSlot(view, c + r * 9, 8 + c * 18, 18 + r * 18));
        int yo = (ROWS - 4) * 18;                                     // バニラのチェスト画面と同じ配置
        for (int r = 0; r < 3; r++)
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 103 + r * 18 + yo));
        for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, 161 + yo));
        refresh();
    }

    // ---------------------------------------------------------------- 表示
    private void refresh() {
        for (int i = 0; i < SIZE; i++) view.setItem(i, ItemStack.EMPTY);
        int flesh = AltarCrafting.countFlesh(player.getInventory());
        Kind[] kinds = Kind.values();
        for (int r = 0; r < kinds.length; r++) {
            Kind k = kinds[r];
            int cur = ElixirState.get(player, k);
            view.setItem(r * 9, labelStack(k, cur));
            for (int lv = 1; lv <= k.maxLevel(); lv++) view.setItem(r * 9 + lv, levelStack(k, lv, cur, flesh));
        }
        view.setItem(INFO_SLOT, infoStack(flesh));
        lastSig = signature();
    }

    private long signature() {
        long s = AltarCrafting.countFlesh(player.getInventory());
        for (Kind k : Kind.values()) s = s * 11 + ElixirState.get(player, k);
        return s;
    }

    @Override
    public void broadcastChanges() {
        if (signature() != lastSig) refresh();
        super.broadcastChanges();
    }

    private static ItemStack labelStack(Kind k, int cur) {
        ItemStack st = new ItemStack(switch (k) {
            case DAMAGE -> Items.IRON_SWORD;
            case SPEED -> Items.SUGAR;
            case DURABILITY -> Items.SHIELD;
            case HEALTH -> Items.GLISTERING_MELON_SLICE;
        });
        st.setHoverName(plain(Component.translatable("zapoc.elixir.kind." + k.id), ChatFormatting.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(plain(Component.translatable("zapoc.altar.current", cur, k.maxLevel()), ChatFormatting.GRAY));
        if (cur > 0) lore.add(plain(Component.translatable("tooltip.claudeganbate." + k.id, valueText(k, cur)), ChatFormatting.GREEN));
        setLore(st, lore);
        st.hideTooltipPart(ItemStack.TooltipPart.MODIFIERS);
        st.hideTooltipPart(ItemStack.TooltipPart.ADDITIONAL);
        return st;
    }

    private static ItemStack levelStack(Kind k, int lv, int cur, int flesh) {
        if (lv > cur + 1) {
            ItemStack st = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
            st.setHoverName(plain(Component.translatable("zapoc.altar.locked", lv), ChatFormatting.DARK_GRAY));
            setLore(st, List.of(plain(Component.translatable("zapoc.altar.locked.hint", lv - 1), ChatFormatting.GRAY)));
            return st;
        }
        ItemStack st = new ItemStack(ModItems.elixir(k, lv));
        List<Component> lore = new ArrayList<>();
        if (lv <= cur) {
            st.enchant(Enchantments.UNBREAKING, 1);
            st.hideTooltipPart(ItemStack.TooltipPart.ENCHANTMENTS);
            lore.add(plain(Component.translatable("zapoc.altar.learned"), ChatFormatting.GREEN));
        } else {
            boolean enough = flesh >= ElixirModel.FLESH_PER_ELIXIR;
            lore.add(plain(Component.translatable("zapoc.altar.click"), ChatFormatting.YELLOW));
            lore.add(plain(Component.translatable("zapoc.altar.cost", flesh, ElixirModel.FLESH_PER_ELIXIR), enough ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        setLore(st, lore);
        return st;
    }

    private static ItemStack infoStack(int flesh) {
        ItemStack st = new ItemStack(Items.ROTTEN_FLESH, Math.max(1, Math.min(64, flesh)));
        st.setHoverName(plain(Component.translatable("zapoc.altar.flesh", flesh), ChatFormatting.GOLD));
        setLore(st, List.of(plain(Component.translatable("zapoc.altar.info.1", ElixirModel.FLESH_PER_ELIXIR), ChatFormatting.GRAY),
            plain(Component.translatable("zapoc.altar.info.2"), ChatFormatting.GRAY)));
        return st;
    }

    /** 数値の表示用(ダメージ/スピード/耐久は%、体力はハート数)。 */
    public static String valueText(Kind k, int lv) {
        double v = k.value(lv);
        return k == Kind.HEALTH ? Integer.toString((int) v) : Integer.toString((int) Math.round(v * 100));
    }

    private static MutableComponent plain(Component c, ChatFormatting color) {
        return c.copy().withStyle(color).withStyle(s -> s.withItalic(false));
    }

    private static void setLore(ItemStack st, List<Component> lines) {
        CompoundTag display = st.getOrCreateTagElement("display");
        ListTag list = new ListTag();
        for (Component c : lines) list.add(StringTag.valueOf(Component.Serializer.toJson(c)));
        display.put("Lore", list);
    }

    // ---------------------------------------------------------------- 操作
    @Override
    public void clicked(int slotId, int button, ClickType type, Player pl) {
        if (slotId >= 0 && slotId < SIZE) {                                       // 表示専用スロット: 取り出し・移動は不可
            if ((type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) && pl instanceof ServerPlayer sp) select(sp, slotId);
            return;
        }
        super.clicked(slotId, button, type, pl);
    }

    /** slotId のエリクサーを作る。 */
    public void select(ServerPlayer sp, int slotId) {
        int r = slotId / 9, c = slotId % 9;
        Kind[] kinds = Kind.values();
        if (r >= kinds.length || c < 1 || c > kinds[r].maxLevel()) return;
        Kind k = kinds[r];
        AltarCrafting.Result res = AltarCrafting.craft(sp, k, c);
        switch (res) {
            case OK -> {
                sp.level().playSound(null, sp.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.PLAYERS, 1.0f, 0.9f);
                sp.level().playSound(null, sp.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.4f, 1.5f);
                sp.displayClientMessage(Component.translatable("zapoc.altar.made", Component.translatable("zapoc.elixir.kind." + k.id), c), true);
            }
            case NOT_ENOUGH_FLESH -> sp.displayClientMessage(Component.translatable("zapoc.altar.noflesh",
                AltarCrafting.countFlesh(sp.getInventory()), ElixirModel.FLESH_PER_ELIXIR), true);
            case ALREADY_LEARNED -> sp.displayClientMessage(Component.translatable("zapoc.elixir.already", Component.translatable("zapoc.elixir.kind." + k.id)), true);
            case NEED_PREVIOUS -> sp.displayClientMessage(Component.translatable("zapoc.altar.locked.hint", c - 1), true);
            case ALREADY_HELD -> sp.displayClientMessage(Component.translatable("zapoc.altar.held"), true);
            default -> { }
        }
        refresh();
        broadcastChanges();
    }

    @Override public ItemStack quickMoveStack(Player pl, int index) { return ItemStack.EMPTY; }

    @Override
    public boolean stillValid(Player pl) { return stillValid(access, pl, ModBlocks.FLESH_ALTAR.get()); }

    /** 表示専用スロット(置けない・取れない)。 */
    private static final class ViewSlot extends Slot {
        ViewSlot(Container c, int i, int x, int y) { super(c, i, x, y); }
        @Override public boolean mayPlace(ItemStack s) { return false; }
        @Override public boolean mayPickup(Player p) { return false; }
    }
}

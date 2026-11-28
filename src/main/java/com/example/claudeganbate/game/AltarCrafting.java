package com.example.claudeganbate.game;

import com.example.claudeganbate.difficulty.ElixirModel;
import com.example.claudeganbate.difficulty.ElixirModel.Kind;
import com.example.claudeganbate.item.ModItems;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemHandlerHelper;

/** 腐肉の祭壇でのエリクサー作成。腐肉256個(4スタック)で1個。レベルは1つずつ順に(次のレベルだけ作れる)。 */
public final class AltarCrafting {
    private AltarCrafting() {}

    public enum Result { OK, ALREADY_LEARNED, NEED_PREVIOUS, ALREADY_HELD, NOT_ENOUGH_FLESH, INVALID }

    public static int countFlesh(Inventory inv) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(Items.ROTTEN_FLESH)) n += st.getCount();
        }
        return n;
    }

    public static boolean holds(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) return true;
        return false;
    }

    /** そのレベルを今作れるか(腐肉の数以外の条件)。 */
    public static Result check(ServerPlayer p, Kind k, int level) {
        if (k == null || level < 1 || level > k.maxLevel()) return Result.INVALID;
        int cur = ElixirState.get(p, k);
        if (level <= cur) return Result.ALREADY_LEARNED;
        if (level > cur + 1) return Result.NEED_PREVIOUS;
        if (holds(p.getInventory(), ModItems.elixir(k, level))) return Result.ALREADY_HELD;
        return Result.OK;
    }

    /** 作成を試みる。成功したら腐肉256個を消費(クリエイティブは無消費)してエリクサーを渡す。 */
    public static Result craft(ServerPlayer p, Kind k, int level) {
        Result r = check(p, k, level);
        if (r != Result.OK) return r;
        boolean free = p.getAbilities().instabuild;
        if (!free && countFlesh(p.getInventory()) < ElixirModel.FLESH_PER_ELIXIR) return Result.NOT_ENOUGH_FLESH;
        if (!free) removeFlesh(p.getInventory(), ElixirModel.FLESH_PER_ELIXIR);
        ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(ModItems.elixir(k, level)));
        return Result.OK;
    }

    private static void removeFlesh(Inventory inv, int amount) {
        int left = amount;
        for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.is(Items.ROTTEN_FLESH)) continue;
            int take = Math.min(left, st.getCount());
            st.shrink(take);
            left -= take;
        }
        inv.setChanged();
    }
}

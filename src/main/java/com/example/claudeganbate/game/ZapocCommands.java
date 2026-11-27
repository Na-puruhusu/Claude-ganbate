package com.example.claudeganbate.game;

import com.example.claudeganbate.ClaudeGanbate;
import com.example.claudeganbate.config.ZapocConfig;
import com.example.claudeganbate.difficulty.*;
import com.example.claudeganbate.difficulty.ThreatCalculator.Inputs;
import com.example.claudeganbate.difficulty.ThreatCalculator.Threat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/**
 * /zapoc info                 … 自分の進行度・脅威レベル・いまのゾンビ能力を表示(誰でも)
 * /zapoc override <0-11>      … 脅威レベルを固定(テスト用, OP)
 * /zapoc override clear       … 固定を解除
 * /zapoc horde                … 今すぐホードを開始(OP)
 * /zapoc spawn <variant> [n]  … 目の前にテスト用ゾンビを出す(OP)
 */
@Mod.EventBusSubscriber(modid = ClaudeGanbate.MODID)
public final class ZapocCommands {
    private ZapocCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(Commands.literal("zapoc")
            .then(Commands.literal("info").executes(c -> info(c.getSource(), c.getSource().getPlayerOrException())))
            .then(Commands.literal("override").requires(s -> s.hasPermission(2))
                .then(Commands.literal("clear").executes(c -> {
                    ThreatTracker.setOverride(c.getSource().getPlayerOrException().getUUID(), null);
                    return msg(c.getSource(), "脅威レベルの固定を解除しました");
                }))
                .then(Commands.argument("level", DoubleArgumentType.doubleArg(0, 11)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    double lv = DoubleArgumentType.getDouble(c, "level");
                    ThreatTracker.setOverride(p.getUUID(), lv);
                    return msg(c.getSource(), String.format("脅威レベルを %.2f に固定しました(/zapoc override clear で解除)", lv));
                })))
            .then(Commands.literal("horde").requires(s -> s.hasPermission(2)).executes(c -> {
                HordeDirector.forceHorde(c.getSource().getPlayerOrException());
                return msg(c.getSource(), "ホードを開始します(3秒後に第1波)");
            }))
            .then(Commands.literal("spawn").requires(s -> s.hasPermission(2))
                .then(Commands.argument("variant", StringArgumentType.word()).executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "variant"), 1))
                    .then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 20))
                        .executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "variant"),
                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "count")))))));
    }

    private static int msg(CommandSourceStack s, String text) {
        s.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    private static int spawn(CommandSourceStack s, String variant, int count) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = s.getPlayerOrException();
        Variant v = Variant.byId(variant);
        if (!v.id.equals(variant)) return msg(s, "variant は walker/runner/armored/brute/bloater/alpha のどれか");
        int n = 0;
        Random rng = new Random();
        for (int i = 0; i < count; i++) {
            net.minecraft.world.entity.monster.Zombie z = net.minecraft.world.entity.EntityType.ZOMBIE.create(p.serverLevel());
            if (z == null) continue;
            net.minecraft.world.phys.Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize().scale(6 + rng.nextInt(3));
            z.moveTo(p.getX() + look.x + rng.nextGaussian(), p.getY(), p.getZ() + look.z + rng.nextGaussian(), rng.nextFloat() * 360f, 0f);
            z.getPersistentData().putString(ZombieEmpowerer.TAG_FORCE, v.id);
            z.finalizeSpawn(p.serverLevel(), p.serverLevel().getCurrentDifficultyAt(z.blockPosition()),
                net.minecraft.world.entity.MobSpawnType.COMMAND, null, null);
            if (p.serverLevel().addFreshEntity(z)) n++;
        }
        return msg(s, n + "体の " + v.id + " を出現させました");
    }

    private static int info(CommandSourceStack s, ServerPlayer p) {
        ThreatTracker.Snapshot snap = ThreatTracker.get(p);
        Threat th = snap.threat();
        Inputs in = snap.inputs();
        Tuning t = ZapocConfig.tuning();
        StringBuilder sb = new StringBuilder();
        sb.append("§6== Zombie Apocalypse ==§r\n");
        sb.append(String.format("進行度ステージ: §e%d§r / %d", in.stage(), Milestones.MAX_STAGE));
        if (in.stage() < Milestones.MAX_STAGE) {
            for (Milestones.Milestone m : Milestones.ALL) {
                if (m.stage() == in.stage() + 1) { sb.append(" (次: ").append(m.label()).append(")"); break; }
            }
        }
        sb.append(String.format("%n生存%.1f日(死亡後) / 世界%.1f日目%n", in.daysSinceDeath(), in.worldDay()));
        sb.append(String.format("基礎レベル %.2f  装備補正 %+.2f%s%n", th.progress(), th.adj(), snap.overridden() ? " §c(固定中)§r" : ""));
        sb.append(String.format("§c能力レベル %.2f§r  §b出現レベル %.2f§r%n", th.statLevel(), th.spawnLevel()));
        sb.append(String.format("装備: 防御%.0f 強靭%.0f 防護EPF%.0f 対ゾンビ攻撃%.1f / 標準(同レベル) 防御%.0f 攻撃%.1f%n",
            in.armor(), in.toughness(), in.epf(), in.attack(),
            Curve.at(GearBench.ARMOR, th.progress()), Curve.at(GearBench.ATTACK, th.progress())));
        ZombieProfile w = ZombieProfile.of(th.statLevel(), Variant.WALKER, t);
        ZombieProfile b = ZombieProfile.of(th.statLevel(), Variant.BRUTE, t);
        sb.append(String.format("ゾンビ: HP%.0f 攻撃%.1f 速度x%.2f 防御%.1f | ブルート HP%.0f 攻撃%.1f%n", w.maxHp(), w.damage(), w.speedMult(), w.armor(), b.maxHp(), b.damage()));
        sb.append(String.format("環境グループ平均%.1f体 / 周期%.0f秒 / 上限%d  |  ホード %d波 x 平均%.1f体 / 上限%d%n",
            SpawnPlanner.ambientGroupMean(th.spawnLevel(), t), SpawnPlanner.ambientIntervalSec(th.spawnLevel(), true, t),
            SpawnPlanner.ambientCap(th.spawnLevel(), t), SpawnPlanner.hordeWaves(th.spawnLevel()),
            SpawnPlanner.hordeWaveMean(th.spawnLevel(), t), SpawnPlanner.hordeCap(th.spawnLevel(), t)));
        StringBuilder vs = new StringBuilder("種類の出現比: ");
        double total = 0;
        for (Variant v : Variant.values()) if (v != Variant.ALPHA) total += v.weightAt(th.statLevel());
        for (Variant v : Variant.values()) {
            if (v == Variant.ALPHA) continue;
            double wgt = v.weightAt(th.statLevel());
            if (wgt > 0) vs.append(String.format("%s %.0f%%  ", v.id, 100 * wgt / total));
        }
        sb.append(vs);
        long day = p.server.overworld().getDayTime() / 24000;
        int iv = ZapocConfig.HORDE_INTERVAL_DAYS.get();
        long next = day;
        while (!SpawnPlanner.isHordeNight(next, iv)) next++;
        sb.append(String.format("%n次のホードナイト: %d日目の夜", next + 1));
        String text = sb.toString();
        s.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }
}

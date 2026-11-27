import com.example.claudeganbate.difficulty.*;
import com.example.claudeganbate.difficulty.ThreatCalculator.Inputs;
import com.example.claudeganbate.difficulty.ThreatCalculator.Threat;

import java.util.*;

/**
 * 難易度の設計検証ツール(Minecraft不要)。 使い方: ./tools/run-sim.sh [--report]
 *
 * 戦闘モデル(バニラ準拠):
 *  - ゾンビの近接は1秒に1回。被ダメージは防具式(armor/toughness/EPF)で軽減
 *  - プレイヤーは被弾後0.5秒の無敵があり、複数体に囲まれても被ダメージの上限は約2回/秒
 *    → 「数を増やす」だけでは危険度が頭打ちになる。だから強さ・種類・持続戦で圧力をかける設計
 *  - プレイヤーは最もHPの低い個体を殴る(剣 1.6回/秒、命中率85%)。被弾した個体は怯む(KB耐性で短縮)
 *  - 自然回復・盾・金リンゴ・弓・地形は入れない(=実プレイはこれより有利)
 * 設計目標を満たさない場合は終了コード1で失敗する(CIで数値のズレを検知)。
 */
public class DifficultySim {
    /** MC側のアルファのオーラ(耐性効果)と同じ値をモデルから読む。 */
    static final class ZombieSpecialsModel { static final double AURA_REDUCTION = Variant.AURA_DAMAGE_REDUCTION; }

    static final double DT = 0.05;
    static final int RUNS = 1500;
    static final Tuning T = Tuning.DEFAULT;
    // プレイヤー操作の想定: 命中率90%、ジャンプクリティカル/スプリント攻撃を混ぜた平均ダメージ倍率1.2、
    // 被弾した個体はノックバックで0.8秒ほど攻撃できない(KB耐性で短縮)。剣での「怯み連打」が効くのはバニラ通り。
    static final double HIT_RATE = 0.90, CRIT_MEAN = 1.20, KNOCKBACK_STUN = 0.80;
    // ゾンビの近接が実際に当たる割合(位置取り・カイティング・ノックバックで外れる分を織り込む)。S0 の素手同然の序盤を基準に較正。
    static final double ZOMBIE_LAND = 0.75;
    // 自然回復(満腹時 4秒に1HP)。盾・金リンゴ・ポーション・弓は含めない。
    static final double REGEN_PER_SEC = 0.25;
    // プレイヤーのリーチ(3)がゾンビ(約2)より長く、接近中に先に殴れる。接敵直後の硬直。
    static final double FIRST_STRIKE_STUN = 0.90;
    // ブローター死亡時の毒ガス: 毒I(約1.25秒に1ダメージ) 約4秒 = 約3ダメージ。装備軽減を受けない。
    static final double BLOATER_CLOUD_DMG = 3.0;

    record Gear(double hp, double armor, double tough, double epf, double attack) {
        static Gear bench(double level) {
            return new Gear(GearBench.PLAYER_HP, Curve.at(GearBench.ARMOR, level), Curve.at(GearBench.TOUGH, level),
                Curve.at(GearBench.EPF, level), Curve.at(GearBench.ATTACK, level));
        }
    }
    /** 4種すべてをレベル k まで飲んだプレイヤー(最大体力・防御・攻撃が増える)。 */
    static Gear withElixirs(Gear g, int k) {
        return new Gear(g.hp + ElixirModel.maxHpBonus(k), g.armor + ElixirModel.armorBonus(k), g.tough, g.epf, g.attack + ElixirModel.attackBonus(k));
    }
    record Result(double lossFrac, boolean died, double duration) {}

    /** 戦い方。cap=同時に殴ってくる最大数 / land=ゾンビの攻撃が当たる割合 / regen=回復(HP/秒)。 */
    record Style(String name, int cap, double land, double regen) {}
    static final Style OPEN = new Style("開けた場所", 6, 0.75, 0.25);
    static final Style CHOKE2 = new Style("通路(2)", 2, 0.75, 0.25);
    static final Style CHOKE1 = new Style("通路(1)", 1, 0.75, 0.25);
    // 備え一式: 盾で正面の攻撃の約6割を受ける + 金リンゴ/再生ポーション/満腹ボーナスで回復が倍
    static final Style KIT_OPEN = new Style("備え+開", 6, 0.30, 0.50);
    static final Style KIT_CHOKE2 = new Style("備え+通路(2)", 2, 0.30, 0.50);
    static final Style KIT_CHOKE1 = new Style("備え+通路(1)", 1, 0.30, 0.50);

    static final class Z {
        ZombieProfile p; double hp, arrive, atkCd, stun; boolean alive = true;
        Z(ZombieProfile p, double arrive, Random r) { this.p = p; this.hp = p.maxHp(); this.arrive = arrive; this.atkCd = r.nextDouble();
            this.stun = FIRST_STRIKE_STUN * (1.0 - p.kbResist()); }
    }

    static double zombieReduction(double armor, double dmg) {
        return Math.min(20.0, Math.max(armor / 5.0, armor - 4.0 * dmg / 8.0)) / 25.0;
    }

    static Result fight(Gear g, List<Z> zs, Random r, double vanillaDifficulty) { return fight(g, zs, r, vanillaDifficulty, OPEN); }

    static Result fight(Gear g, List<Z> zs, Random r, double vanillaDifficulty, Style st) {
        int surroundCap = st.cap();
        double hp = g.hp, t = 0, lastHit = -9, atkTimer = 0;
        while (t < 240) {
            List<Z> engaged = new ArrayList<>();
            boolean anyAlive = false, alpha = false;
            for (Z z : zs) if (z.alive) { anyAlive = true; if (z.arrive <= t) engaged.add(z); if (z.p.variant() == Variant.ALPHA) alpha = true; }
            if (!anyAlive) break;
            // プレイヤー攻撃(最もHPの低い=最前列で殴っている個体を狙う)
            // 光る指揮官(ALPHA)が接敵していれば最優先で倒す(オーラ除去)。いなければ最もHPの低い個体。
            Z target = engaged.stream().filter(z -> z.p.variant() == Variant.ALPHA).findFirst()
                .orElseGet(() -> engaged.isEmpty() ? null : engaged.stream().min(Comparator.comparingDouble(z -> z.hp)).get());
            atkTimer -= DT;
            if (atkTimer <= 0 && target != null) {
                atkTimer = 0.625;
                if (r.nextDouble() < HIT_RATE) {
                    double atk = g.attack * CRIT_MEAN;
                    double d = atk * (1.0 - zombieReduction(target.p.armor(), atk));
                    if (alpha && target.p.variant() != Variant.ALPHA) d *= 1.0 - ZombieSpecialsModel.AURA_REDUCTION;  // アルファのオーラ
                    target.hp -= d;
                    target.stun = KNOCKBACK_STUN * (1.0 - target.p.kbResist());
                    if (target.hp <= 0) {
                        target.alive = false;
                        // ブローターは倒した位置(=通路の最前列)に毒ガスを撒く。毒は致死しない(1HPで止まる)が回復を打ち消す。
                        if (target.p.variant() == Variant.BLOATER && hp > 1.0) hp = Math.max(1.0, hp - BLOATER_CLOUD_DMG);
                    }
                }
            }
            // ゾンビ攻撃: 囲める数(surroundCap)に上限。最前列(=いま殴られている個体)が最優先で枠を取る
            List<Z> order = new ArrayList<>(engaged);
            if (target != null) { order.remove(target); order.add(0, target); }
            int slots = surroundCap;
            for (Z z : order) {
                if (!z.alive) continue;
                z.stun -= DT; z.atkCd -= DT;
                if (slots-- <= 0) continue;
                if (z.stun <= 0 && z.atkCd <= 0) {
                    z.atkCd = 1.0;
                    if (r.nextDouble() >= st.land()) continue;
                    double raw = z.p.damage() * vanillaDifficulty;
                    double applied = raw * (1.0 - GearBench.reduction(g.armor, g.tough, g.epf, raw));
                    if (t - lastHit >= 0.5) { hp -= applied; lastHit = t; }
                }
            }
            if (hp <= 0) return new Result(1.0, true, t);
            hp = Math.min(g.hp, hp + st.regen() * DT);
            t += DT;
        }
        return new Result(Math.min(1.0, (g.hp - hp) / g.hp), false, t);
    }

    record Scenario(Threat th, Random r) {}

    /** 脅威レベル stage に到達する日(=その日数の標準プレイヤー)に、装備 g のプレイヤーが受ける脅威。 */
    static Threat threat(int stage, Gear g) {
        double day = DayCurve.dayAt(stage, T.daySpeed());
        return ThreatCalculator.compute(new Inputs(day, g.hp, g.armor, g.tough, g.epf, g.attack), T);
    }

    static List<Z> group(Threat th, int n, double spread, Random r, boolean leaders, int alphas) {
        List<Z> zs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Variant v = Variant.pick(th.statLevel(), true, r);
            zs.add(new Z(ZombieProfile.of(th.statLevel(), v, T), 3 + r.nextDouble() * spread, r));
        }
        for (int i = 0; i < alphas; i++) zs.add(new Z(ZombieProfile.of(th.statLevel(), Variant.ALPHA, T), 3 + r.nextDouble() * spread, r));
        return zs;
    }

    /** 戦闘の集計値 */
    record Agg(double meanLoss, double pDeath) {}

    static Agg ambient(int stage, Gear g, double vd, long seed) {
        Random r = new Random(seed); double loss = 0; int dead = 0;
        for (int i = 0; i < RUNS; i++) {
            Threat th = threat(stage, g);
            Result res = fight(g, group(th, SpawnPlanner.ambientGroupSize(th.spawnLevel(), T, r), 6, r, false, 0), r, vd);
            loss += res.lossFrac(); if (res.died()) dead++;
        }
        return new Agg(loss / RUNS, dead / (double) RUNS);
    }

    static Agg wave(int stage, Gear g, double vd, long seed) {
        Random r = new Random(seed); double loss = 0; int dead = 0;
        for (int i = 0; i < RUNS; i++) {
            Threat th = threat(stage, g);
            int waves = SpawnPlanner.hordeWaves(th.spawnLevel());
            Result res = fight(g, group(th, SpawnPlanner.hordeWaveSize(th.spawnLevel(), T, r), 8, r, true, SpawnPlanner.alphasInWave(th.spawnLevel(), 0, waves)), r, vd);
            loss += res.lossFrac(); if (res.died()) dead++;
        }
        return new Agg(loss / RUNS, dead / (double) RUNS);
    }

    /** ホード全体: 全ウェーブがウェーブ間隔で到着し続ける。開けた場所で近接のみ。 */
    static Agg fullHorde(int stage, Gear g, double vd, long seed, Style st) {
        Random r = new Random(seed); double loss = 0; int dead = 0;
        for (int i = 0; i < RUNS / 3; i++) {
            Threat th = threat(stage, g);
            int waves = SpawnPlanner.hordeWaves(th.spawnLevel());
            List<Z> all = new ArrayList<>();
            for (int w = 0; w < waves; w++) {
                List<Z> grp = group(th, SpawnPlanner.hordeWaveSize(th.spawnLevel(), T, r), 8, r, true, SpawnPlanner.alphasInWave(th.spawnLevel(), w, waves));
                double off = w * SpawnPlanner.hordeWaveGapSec(th.spawnLevel());
                for (Z z : grp) z.arrive += off;
                all.addAll(grp);
            }
            Result res = fight(g, all, r, vd, st);
            loss += res.lossFrac(); if (res.died()) dead++;
        }
        int n = RUNS / 3;
        return new Agg(loss / n, dead / (double) n);
    }

    /** 1対1の撃破時間(秒)。標準装備で理論値に近い値を返す。 */
    static double ttk(Gear g, ZombieProfile p) {
        double atk = g.attack * CRIT_MEAN;
        double perHit = atk * (1.0 - zombieReduction(p.armor(), atk));
        return Math.ceil(p.maxHp() / perHit) * 0.625 / HIT_RATE;
    }

    // ------------------------------------------------------------------
    static int failures = 0;
    static void check(boolean ok, String msg) {
        if (!ok) { failures++; System.out.println("  FAIL: " + msg); }
    }

    public static void main(String[] args) {
        boolean report = args.length > 0 && args[0].equals("--report");
        System.out.println("=== 脅威レベル(on-curve: その日数の標準装備 / Normal) ===");
        System.out.println("Lv | progress adj   stat spawn | Walker(HP/Dmg/TTK)  Brute(HP/TTK)  Alpha(HP/TTK) | 環境グループ N 被害 死亡 | ウェーブ(開) 波数x人数 被害 死亡 | ホード全体[素の近接] 被害/死亡: 開 通路2 通路1 | ホード全体[備え一式(盾・回復)]: 開 通路2 通路1");
        Agg[] amb = new Agg[11], wav = new Agg[11], hOpen = new Agg[11], hC2 = new Agg[11], hC1 = new Agg[11];
        Agg[] kOpen = new Agg[11], kC2 = new Agg[11], kC1 = new Agg[11];
        for (int s = 0; s <= 10; s++) {
            Gear g = Gear.bench(s);
            Threat th = threat(s, g);
            ZombieProfile w = ZombieProfile.of(th.statLevel(), Variant.WALKER, T);
            ZombieProfile b = ZombieProfile.of(th.statLevel(), Variant.BRUTE, T);
            ZombieProfile a = ZombieProfile.of(th.statLevel(), Variant.ALPHA, T);
            amb[s] = ambient(s, g, 1.0, 100 + s); wav[s] = wave(s, g, 1.0, 200 + s);
            hOpen[s] = fullHorde(s, g, 1.0, 300 + s, OPEN); hC2[s] = fullHorde(s, g, 1.0, 600 + s, CHOKE2); hC1[s] = fullHorde(s, g, 1.0, 700 + s, CHOKE1);
            kOpen[s] = fullHorde(s, g, 1.0, 800 + s, KIT_OPEN); kC2[s] = fullHorde(s, g, 1.0, 900 + s, KIT_CHOKE2); kC1[s] = fullHorde(s, g, 1.0, 950 + s, KIT_CHOKE1);
            int waves = SpawnPlanner.hordeWaves(th.spawnLevel());
            System.out.printf("%2d | %5.2f %+5.2f %5.2f %5.2f | %3.0f/%4.1f/%3.1fs  %4.0f/%4.1fs  %4.0f/%4.1fs | %4.1f %4.2f %4.2f | %dx%4.1f %4.2f %4.2f | %4.2f/%4.2f %4.2f/%4.2f %4.2f/%4.2f | %4.2f/%4.2f %4.2f/%4.2f %4.2f/%4.2f%n",
                s, th.progress(), th.adj(), th.statLevel(), th.spawnLevel(),
                w.maxHp(), w.damage(), ttk(g, w), b.maxHp(), ttk(g, b), a.maxHp(), ttk(g, a),
                SpawnPlanner.ambientGroupMean(th.spawnLevel(), T), amb[s].meanLoss(), amb[s].pDeath(),
                waves, SpawnPlanner.hordeWaveMean(th.spawnLevel(), T), wav[s].meanLoss(), wav[s].pDeath(),
                hOpen[s].meanLoss(), hOpen[s].pDeath(), hC2[s].meanLoss(), hC2[s].pDeath(), hC1[s].meanLoss(), hC1[s].pDeath(),
                kOpen[s].meanLoss(), kOpen[s].pDeath(), kC2[s].meanLoss(), kC2[s].pDeath(), kC1[s].meanLoss(), kC1[s].pDeath());
        }

        System.out.println("\n=== 装備ズレ(DDA)の効き: 同じステージで装備だけ変える(環境グループ戦の 平均被害/死亡率) ===");
        System.out.println("St | gear-2.0        gear-1.0        on-curve        gear+1.0        gear+2.0");
        double[] offs = {-2, -1, 0, 1, 2};
        double[][] lossTab = new double[11][5];
        for (int s = 0; s <= 10; s++) {
            StringBuilder sb = new StringBuilder(String.format("%2d |", s));
            for (int i = 0; i < offs.length; i++) {
                Gear g = Gear.bench(Math.max(0, Math.min(10, s + offs[i])));
                Agg a = ambient(s, g, 1.0, 400 + s * 10 + i);
                lossTab[s][i] = a.meanLoss();
                sb.append(String.format(" %5.2f / %4.2f  ", a.meanLoss(), a.pDeath()));
            }
            System.out.println(sb);
        }

        System.out.println("\n=== 永続エリクサー(4種すべてLv k): 環境グループ戦の 被害率/死亡率 と DDAによるゾンビ強化(adj) ===");
        System.out.println("Lv | Lv0              Lv1              Lv3              Lv5   (被害/死亡 [adj])");
        double[][] elx = new double[11][4];
        int[] ks = {0, 1, 3, 5};
        for (int s = 0; s <= 10; s++) {
            StringBuilder sb = new StringBuilder(String.format("%2d |", s));
            for (int i = 0; i < ks.length; i++) {
                Gear g = withElixirs(Gear.bench(s), ks[i]);
                Agg a = ambient(s, g, 1.0, 1000 + s * 10 + i);
                Threat th = threat(s, g);
                elx[s][i] = a.meanLoss();
                sb.append(String.format(" %4.2f/%4.2f [%+.2f]  ", a.meanLoss(), a.pDeath(), th.adj()));
            }
            System.out.println(sb);
        }

        System.out.println("\n=== 腐肉の経済(1種をLv5まで = 腐肉" + ElixirModel.totalFleshToMax() + "個) ===");
        for (int s : new int[]{0, 2, 4, 6, 8, 10}) {
            double sum = 0; int n = 20000; Random r = new Random(7 + s);
            for (int i = 0; i < n; i++) sum += 1.0 + ElixirModel.fleshBonus(s, Variant.pick(s, true, r), 0, r);   // バニラ平均1 + 追加
            double perZ = sum / n;
            System.out.printf("L%2d: 1体あたり平均 腐肉%.2f個 → 1種Lv5まで 約%.0f体 / 4種で約%.0f体%n", s, perZ, ElixirModel.totalFleshToMax() / perZ, 4 * ElixirModel.totalFleshToMax() / perZ);
        }

        System.out.println("\n=== Hardモード(バニラ1.5倍 × 補正0.8)の on-curve 環境グループ ===");
        for (int s : new int[]{0, 3, 5, 8, 10}) {
            Agg am = ambient(s, Gear.bench(s), 1.5 * Tuning.HARD_DAMAGE_FACTOR, 500 + s);
            System.out.printf("S%2d 被害=%.2f 死亡=%.2f%n", s, am.meanLoss(), am.pDeath());
        }

        // ---------------- 設計目標(不変条件) ----------------
        System.out.println("\n=== 設計目標チェック ===");
        for (int s = 0; s <= 10; s++) {
            Gear g = Gear.bench(s);
            Threat th = threat(s, g);
            // 1) 環境グループ(夜の通常の襲撃): 被害率は緩やかに上昇する帯に収める。死亡は稀。
            double target = 0.22 + 0.04 * s;
            check(amb[s].meanLoss() >= target - 0.13 && amb[s].meanLoss() <= target + 0.13,
                String.format("S%d 環境グループの被害率 %.2f が目標 %.2f±0.13 の外", s, amb[s].meanLoss(), target));
            check(amb[s].pDeath() <= 0.03 + 0.018 * s, String.format("S%d 環境グループの死亡率 %.2f が高すぎる", s, amb[s].pDeath()));
            // 2) ウェーブは環境グループより重い
            check(wav[s].meanLoss() >= amb[s].meanLoss() - 0.02, String.format("S%d ウェーブが環境グループより軽い", s));
            // 3) ホード: 素の近接(盾・回復なし)では厳しい=備えの動機。備え一式+通路なら全ステージで凌げる。
            if (s >= 2) check(hOpen[s].pDeath() >= (s == 2 ? 0.40 : 0.50), String.format("S%d 備えなしでホードを凌げてしまう(死亡率 %.2f)", s, hOpen[s].pDeath()));
            check(kC1[s].pDeath() <= 0.10, String.format("S%d 備え一式+完璧な通路でも死亡率 %.2f > 0.10(理不尽)", s, kC1[s].pDeath()));
            check(kC2[s].pDeath() <= 0.30, String.format("S%d 備え一式+通路(2)の死亡率 %.2f > 0.30", s, kC2[s].pDeath()));
            if (s <= 2) check(hC1[s].pDeath() <= 0.15, String.format("S%d 序盤は素の近接+完璧な通路なら凌げるべき(死亡率 %.2f)", s, hC1[s].pDeath()));
            check(kC1[s].pDeath() <= kC2[s].pDeath() + 0.03 && kC2[s].pDeath() <= kOpen[s].pDeath() + 0.03 && hC1[s].pDeath() <= hC2[s].pDeath() + 0.03,
                String.format("S%d 備え/地形の効果が逆転している", s));
            check(kOpen[s].pDeath() <= hOpen[s].pDeath() + 0.02, String.format("S%d 備え一式が素の近接より不利", s));
            if (s >= 6) check(kC1[s].meanLoss() >= 0.06, String.format("S%d 備え+完璧な通路でほぼ無傷(%.2f): 終盤の圧力が不足", s, kC1[s].meanLoss()));
            // 4) TTK
            ZombieProfile w = ZombieProfile.of(th.statLevel(), Variant.WALKER, T);
            ZombieProfile b = ZombieProfile.of(th.statLevel(), Variant.BRUTE, T);
            ZombieProfile a = ZombieProfile.of(th.statLevel(), Variant.ALPHA, T);
            check(ttk(g, w) >= 1.5 && ttk(g, w) <= 6.0, String.format("S%d WALKERのTTK %.1fs が範囲外", s, ttk(g, w)));
            if (s >= 4) check(ttk(g, b) <= 12.0 && ttk(g, b) >= 4.0, String.format("S%d BRUTEのTTK %.1fs が範囲外", s, ttk(g, b)));
            if (s >= 6) check(ttk(g, a) <= 24.0 && ttk(g, a) >= 7.0, String.format("S%d ALPHAのTTK %.1fs が範囲外", s, ttk(g, a)));
        }
        // 5) 装備ズレ: 弱いほど被害は大きく、強いほど小さい。ただしDDAで極端にならない
        for (int s = 2; s <= 9; s++) {
            check(lossTab[s][0] >= lossTab[s][2] - 0.01, String.format("S%d 装備-2.0 の被害 %.2f が on-curve %.2f より軽い", s, lossTab[s][0], lossTab[s][2]));
            check(lossTab[s][4] <= lossTab[s][2] + 0.01, String.format("S%d 装備+2.0 の被害 %.2f が on-curve %.2f より重い", s, lossTab[s][4], lossTab[s][2]));
            check(lossTab[s][0] <= 0.90, String.format("S%d 装備-2.0 の被害率 %.2f(DDAが効かず理不尽)", s, lossTab[s][0]));
        }
        // 5b) エリクサー: 飲むほど楽になる(=報われる)が、DDAで厳しくなる分もあり、無双にはならない
        for (int s = 1; s <= 10; s++) {
            check(elx[s][3] <= elx[s][0] - 0.03, String.format("L%d エリクサーLv5の効果が薄い(%.2f vs なし %.2f)", s, elx[s][3], elx[s][0]));
            check(elx[s][1] <= elx[s][0] + 0.02 && elx[s][2] <= elx[s][1] + 0.02 && elx[s][3] <= elx[s][2] + 0.02, String.format("L%d エリクサーのレベル順に効果が単調でない", s));
            check(elx[s][3] >= 0.08, String.format("L%d エリクサーLv5で通常の夜がほぼ無傷(%.2f): 無双", s, elx[s][3]));
        }
        // 5c) エリクサーのレシピ枠(3x3クラフトに収まる)・コスト単調性・腐肉経済
        for (int l = 1; l <= ElixirModel.MAX_LEVEL; l++) {
            int ingredients = 1 + ElixirModel.essenceCost(l) + ElixirModel.catalystCost(l);   // 前の薬(Lv1は空き瓶) + エッセンス + 触媒
            check(ingredients <= 9, "エリクサーLv" + l + "のレシピが9スロットに収まらない: " + ingredients);
            if (l > 1) check(ElixirModel.essenceCost(l) >= ElixirModel.essenceCost(l - 1), "エッセンスのコストが単調でない Lv" + l);
        }
        check(ElixirModel.maxHpBonus(5) == 10 && ElixirModel.maxHpBonus(0) == 0 && ElixirModel.maxHpBonus(9) == 10, "エリクサー効果の上限/下限が不正");
        for (int s : new int[]{2, 6, 10}) {
            double sum = 0; int n = 20000; Random r = new Random(7 + s);
            for (int i = 0; i < n; i++) sum += 1.0 + ElixirModel.fleshBonus(s, Variant.pick(s, true, r), 0, r);
            double zNeeded = ElixirModel.totalFleshToMax() / (sum / n);
            check(zNeeded >= 40 && zNeeded <= 200, String.format("L%d: 1種Lv5までに必要なゾンビ数 %.0f が目安(40〜200体)の外", s, zNeeded));
        }
        // 6) モデルの単調性・上限
        for (double l = 0; l < 10.9; l += 0.1) {
            ZombieProfile p1 = ZombieProfile.of(l, Variant.WALKER, T), p2 = ZombieProfile.of(l + 0.1, Variant.WALKER, T);
            check(p2.hpMult() >= p1.hpMult() - 1e-9 && p2.damageMult() >= p1.damageMult() - 1e-9 && p2.speedMult() >= p1.speedMult() - 1e-9,
                "能力カーブが単調増加でない L=" + l);
            check(SpawnPlanner.ambientGroupMean(l + 0.1, T) >= SpawnPlanner.ambientGroupMean(l, T), "グループ数が単調でない L=" + l);
        }
        for (Variant v : Variant.values())
            for (double l = 0; l <= 11; l += 0.5) check(ZombieProfile.of(l, v, T).speedMult() <= ZombieProfile.SPEED_CAP + 1e-9, "速度上限超過 " + v);
        ZombieProfile vanilla = ZombieProfile.of(10, Variant.WALKER, new Tuning(0, 1, true, true, 1.0));
        check(Math.abs(vanilla.hpMult() - 1) < 1e-9 && Math.abs(vanilla.damageMult() - 1) < 1e-9, "statScale=0 がバニラ同等でない");
        // 7) 日数カーブ・周期
        for (double d = 0; d < 120; d += 0.5) {
            check(DayCurve.levelAt(d + 0.5, 1.0) >= DayCurve.levelAt(d, 1.0) - 1e-9, "日数カーブが単調でない d=" + d);
            check(Math.abs(DayCurve.dayAt(DayCurve.levelAt(d, 1.0), 1.0) - Math.min(d, 100)) < 1e-6, "dayAt が levelAt の逆関数でない d=" + d);
        }
        check(DayCurve.levelAt(0, 1.0) == 0 && DayCurve.levelAt(1000, 1.0) == DayCurve.MAX_LEVEL, "日数カーブの端点が不正");
        check(DayCurve.levelAt(10, 2.0) > DayCurve.levelAt(10, 1.0), "daySpeed が効いていない");
        check(DayCurve.levelAt(6, 1.0) <= 2.0, "最初のホードナイト(7日目)が重すぎる: L=" + DayCurve.levelAt(6, 1.0));
        check(!SpawnPlanner.isHordeNight(0, 7) && SpawnPlanner.isHordeNight(6, 7) && SpawnPlanner.isHordeNight(13, 7) && !SpawnPlanner.isHordeNight(7, 7), "ホードナイト周期が不正");

        System.out.println(failures == 0 ? "ALL OK" : failures + " 件の設計目標違反");
        if (!report && failures > 0) System.exit(1);
    }
}

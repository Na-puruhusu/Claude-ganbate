#!/usr/bin/env python3
"""
エリクサーのアセット(テクスチャ・モデル・レシピ・言語ファイル)を生成する。

数値(最大レベル・エッセンスのコスト・触媒・腐肉→エッセンスの個数)は
src/main/java/.../difficulty/ElixirModel.java から読み込む。読み込めない/食い違う場合は停止する。
生成物はコードと一緒にコミットする。  使い方:
    python3 tools/gen_assets.py          # 生成して上書き
    python3 tools/gen_assets.py --check  # 生成物が最新か検証(CI用。古ければ終了コード1)
"""
import json, os, random, re, sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODEL = os.path.join(ROOT, "src/main/java/com/example/claudeganbate/difficulty/ElixirModel.java")
RES = os.path.join(ROOT, "src/main/resources")
MODID = "claudeganbate"

# ---------------------------------------------------------------- Javaモデルから数値を読む
def die(msg):
    print("ERROR:", msg, file=sys.stderr); sys.exit(2)

src = open(MODEL, encoding="utf-8").read()
def grab(pattern, what):
    m = re.search(pattern, src)
    if not m: die(f"ElixirModel.java から {what} を読み取れない")
    return m

MAX_LEVEL = int(grab(r"MAX_LEVEL\s*=\s*(\d+)", "MAX_LEVEL").group(1))
FLESH_PER_ESSENCE = int(grab(r"FLESH_PER_ESSENCE\s*=\s*(\d+)", "FLESH_PER_ESSENCE").group(1))
costs = [int(x) for x in grab(r"essenceCost\(int lv\)\s*\{\s*return new int\[\]\{([^}]*)\}", "essenceCost").group(1).split(",")]
if len(costs) != MAX_LEVEL + 1: die(f"essenceCost の要素数 {len(costs)} != MAX_LEVEL+1")
cat_m = grab(r"catalystCost\(int lv\)\s*\{\s*return lv >= MAX_LEVEL \? (\d+) : (\d+);", "catalystCost")
CAT_MAX, CAT_NORMAL = int(cat_m.group(1)), int(cat_m.group(2))
KINDS = re.findall(r'([A-Z]+)\("(\w+)",\s*"([\w:]+)"\)', src)   # (ENUM, id, catalyst)
if len(KINDS) != 4: die(f"Kind を4種読み取れるはずが {len(KINDS)} 種")

def ess_cost(lv): return costs[lv]
def cat_cost(lv): return CAT_MAX if lv >= MAX_LEVEL else CAT_NORMAL

for lv in range(1, MAX_LEVEL + 1):
    n = 1 + ess_cost(lv) + cat_cost(lv)
    if n > 9: die(f"Lv{lv} のレシピが9スロットを超える: {n}")

# ---------------------------------------------------------------- テクスチャ
KIND_COLOR = {"vitality": (0xd9, 0x4a, 0x4a), "ferocity": (0xe8, 0x89, 0x2b), "fleetfoot": (0x4a, 0xc8, 0xe0), "resilience": (0x7d, 0x8f, 0xb0)}
OUTLINE = (0x2e, 0x2e, 0x3a, 255)
GLASS = (0xd7, 0xeb, 0xf5, 200)
CORK = (0x9c, 0x6b, 0x3a, 255)
GOLD = (0xff, 0xd8, 0x4a, 255)

BODY = {5: (5, 10), 6: (4, 11), 7: (3, 12), 8: (3, 12), 9: (3, 12), 10: (3, 12), 11: (3, 12), 12: (3, 12), 13: (4, 11), 14: (5, 10)}
NECK = {2: (6, 9), 3: (6, 9), 4: (6, 9)}

def bottle(kind, level):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    shape = set()
    for rows in (BODY, NECK):
        for y, (a, b) in rows.items():
            for x in range(a, b + 1): shape.add((x, y))
    r, g, b_ = KIND_COLOR[kind]
    liquid = (r, g, b_, 255)
    liquid_hi = (min(255, r + 40), min(255, g + 40), min(255, b_ + 40), 255)
    edge = (r, g, b_, 255) if level >= 4 else OUTLINE      # 高レベルは縁が薬の色に光る
    for (x, y) in shape:
        border = any((x + dx, y + dy) not in shape for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if border: px[x, y] = edge
        elif y >= 9: px[x, y] = liquid_hi if y == 9 else liquid
        else: px[x, y] = GLASS
    for y in (0, 1):
        for x in range(6, 10): px[x, y] = CORK if (x + y) % 2 == 0 else (0x7d, 0x52, 0x2b, 255)
    for y in (8, 9, 10): px[4, y] = (255, 255, 255, 220)      # ハイライト
    n = level
    x0 = 8 - n
    for i in range(n): px[x0 + 2 * i, 15] = GOLD              # レベルの数だけ金の点
    return img

def essence():
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    rnd = random.Random(1234)
    shape = {(x, y) for x in range(16) for y in range(16) if ((x - 7.5) / 6.2) ** 2 + ((y - 8.0) / 5.6) ** 2 <= 1.0}
    for (x, y) in shape:
        border = any((x + dx, y + dy) not in shape for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if border: px[x, y] = (0x2f, 0x3a, 0x12, 255)
        else:
            c = (0x7a, 0x8f, 0x2e, 255)
            t = rnd.random()
            if t < 0.18: c = (0x5b, 0x2a, 0x2a, 255)           # 腐った赤黒い斑点
            elif t < 0.40: c = (0x66, 0x7a, 0x25, 255)
            px[x, y] = c
    for (x, y) in ((4, 5), (5, 4), (5, 5), (6, 4)): 
        if (x, y) in shape: px[x, y] = (0xc4, 0xd8, 0x6a, 255)  # つや
    for (x, y) in ((8, 13), (8, 14)): px[x, y] = (0x7a, 0x8f, 0x2e, 255)   # しずく
    return img

# ---------------------------------------------------------------- 言語
ROMAN = ["", "I", "II", "III", "IV", "V"]
KIND_EN = {"vitality": "Vitality", "ferocity": "Ferocity", "fleetfoot": "Fleetfoot", "resilience": "Resilience"}
KIND_JA = {"vitality": "活力", "ferocity": "凶暴", "fleetfoot": "俊足", "resilience": "頑強"}

def lang(ja):
    d = {}
    if ja:
        d.update({
            "zapoc.dawn": "§6[ゾンビアポカリプス] %1$s日目の朝。脅威レベル %2$s。次のホードナイト: %3$s日目の夜",
            "zapoc.levelup": "§c[ゾンビアポカリプス] 死者たちが強くなった…(脅威Lv %1$s)",
            "zapoc.horde.warning": "§c[ゾンビアポカリプス] 今夜、ゾンビの大群が押し寄せる…拠点を固めて備えろ！",
            "zapoc.horde.wave": "§c第%1$s波 / %2$s",
            "zapoc.horde.dawn": "§e[ゾンビアポカリプス] 夜が明けた。大群は退いていく。",
            "zapoc.horde.cleared": "§a[ゾンビアポカリプス] 守り切った。大群を撃退した！",
            "zapoc.horde.reward": "§a生き延びた報酬: 腐肉のエッセンス ×%1$s",
            "zapoc.sleep.blocked": "眠れない。今夜は死者たちが騒がしい。",
            "zapoc.elixir.drunk": "§a%1$sのエリクサー(Lv%2$s)を飲んだ。効果は永続する。",
            "zapoc.elixir.already": "%1$sはすでに同じか上のレベルだ。",
            "tooltip.claudeganbate.permanent": "永続: 死んでも消えない",
            "tooltip.claudeganbate.level": "Lv %1$s / %2$s",
            "tooltip.claudeganbate.vitality": "最大体力 +%1$sハート",
            "tooltip.claudeganbate.ferocity": "攻撃力 +%1$s",
            "tooltip.claudeganbate.fleetfoot": "移動速度 +%1$s%%",
            "tooltip.claudeganbate.resilience": "防御 +%1$s / ノックバック耐性 +%2$s%%",
            "item.claudeganbate.putrid_essence": "腐肉のエッセンス",
        })
    else:
        d.update({
            "zapoc.dawn": "§6[Zombie Apocalypse] Day %1$s dawns. Threat level %2$s. Next horde night: day %3$s.",
            "zapoc.levelup": "§c[Zombie Apocalypse] The dead grow stronger... (Threat Lv %1$s)",
            "zapoc.horde.warning": "§c[Zombie Apocalypse] A horde is coming tonight... Fortify your base and get ready!",
            "zapoc.horde.wave": "§cWave %1$s / %2$s",
            "zapoc.horde.dawn": "§e[Zombie Apocalypse] Dawn breaks. The horde falls back.",
            "zapoc.horde.cleared": "§a[Zombie Apocalypse] You held the line. The horde is cleared.",
            "zapoc.horde.reward": "§aSurvival reward: %1$s Putrid Essence",
            "zapoc.sleep.blocked": "You can't sleep. The dead are restless tonight.",
            "zapoc.elixir.drunk": "§aYou drank the Elixir of %1$s (Lv %2$s). The effect is permanent.",
            "zapoc.elixir.already": "You already have %1$s at this level or higher.",
            "tooltip.claudeganbate.permanent": "Permanent: kept even after death",
            "tooltip.claudeganbate.level": "Level %1$s / %2$s",
            "tooltip.claudeganbate.vitality": "Max health +%1$s hearts",
            "tooltip.claudeganbate.ferocity": "Attack damage +%1$s",
            "tooltip.claudeganbate.fleetfoot": "Movement speed +%1$s%%",
            "tooltip.claudeganbate.resilience": "Armor +%1$s, knockback resistance +%2$s%%",
            "item.claudeganbate.putrid_essence": "Putrid Essence",
        })
    for _, kid, _ in KINDS:
        d[f"zapoc.elixir.kind.{kid}"] = KIND_JA[kid] if ja else KIND_EN[kid]
        for lv in range(1, MAX_LEVEL + 1):
            d[f"item.{MODID}.elixir_{kid}_{lv}"] = f"{KIND_JA[kid]}のエリクサー {ROMAN[lv]}" if ja else f"Elixir of {KIND_EN[kid]} {ROMAN[lv]}"
    return d

# ---------------------------------------------------------------- レシピ・モデル
def shapeless(ingredients, result):
    return {"type": "minecraft:crafting_shapeless", "category": "misc",
            "ingredients": [{"item": i} for i in ingredients], "result": {"item": f"{MODID}:{result}"}}

def build():
    """ {相対パス: bytes または PIL.Image} を返す """
    out = {}
    out["data/%s/recipes/putrid_essence.json" % MODID] = shapeless(["minecraft:rotten_flesh"] * FLESH_PER_ESSENCE, "putrid_essence")
    out["assets/%s/models/item/putrid_essence.json" % MODID] = {"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/putrid_essence"}}
    out["assets/%s/textures/item/putrid_essence.png" % MODID] = essence()
    for _, kid, catalyst in KINDS:
        for lv in range(1, MAX_LEVEL + 1):
            iid = f"elixir_{kid}_{lv}"
            base = "minecraft:glass_bottle" if lv == 1 else f"{MODID}:elixir_{kid}_{lv - 1}"
            ing = [base] + [f"{MODID}:putrid_essence"] * ess_cost(lv) + [catalyst] * cat_cost(lv)
            out[f"data/{MODID}/recipes/{iid}.json"] = shapeless(ing, iid)
            out[f"assets/{MODID}/models/item/{iid}.json"] = {"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{iid}"}}
            out[f"assets/{MODID}/textures/item/{iid}.png"] = bottle(kid, lv)
    out[f"assets/{MODID}/lang/en_us.json"] = lang(False)
    out[f"assets/{MODID}/lang/ja_jp.json"] = lang(True)
    return out

def render(obj):
    if isinstance(obj, Image.Image): return obj
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n")

def main():
    check = "--check" in sys.argv
    stale = []
    for rel, obj in build().items():
        path = os.path.join(RES, rel)
        data = render(obj)
        if isinstance(data, Image.Image):
            same = os.path.exists(path) and Image.open(path).convert("RGBA").tobytes() == data.tobytes()
            if not same:
                stale.append(rel)
                if not check: os.makedirs(os.path.dirname(path), exist_ok=True); data.save(path)
        else:
            same = os.path.exists(path) and open(path, encoding="utf-8").read() == data
            if not same:
                stale.append(rel)
                if not check: os.makedirs(os.path.dirname(path), exist_ok=True); open(path, "w", encoding="utf-8").write(data)
    if check:
        if stale:
            print("生成物が古い/欠けています(python3 tools/gen_assets.py を実行してコミット):"); [print(" ", s) for s in stale[:20]]
            sys.exit(1)
        print("assets up to date")
    else:
        print(f"generated: {len(stale)} files updated")

main()

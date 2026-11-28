#!/usr/bin/env python3
"""
エリクサーと腐肉の祭壇のアセット(テクスチャ・モデル・ブロックステート・ルートテーブル・レシピ・言語ファイル)を生成する。

数値(エリクサーの種類・段階数・効果量・腐肉の個数)は
src/main/java/.../difficulty/ElixirModel.java から読み込む。読み込めない/仕様とずれる場合は停止する。
生成物はコードと一緒にコミットする。  使い方:
    python3 tools/gen_assets.py          # 生成して上書き(古い生成物は削除)
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
m = re.search(r"FLESH_PER_ELIXIR\s*=\s*(\d+)", src)
if not m: die("ElixirModel.java から FLESH_PER_ELIXIR を読み取れない")
FLESH_PER_ELIXIR = int(m.group(1))
# 例: DAMAGE("damage", new double[]{0.15, 0.30, 0.45, 0.60}),
KINDS = []   # (id, [values])
for _enum, kid, vals in re.findall(r'([A-Z]+)\("(\w+)",\s*new double\[\]\{([^}]*)\}\)', src):
    KINDS.append((kid, [float(v) for v in vals.split(",")]))
if [k for k, _ in KINDS] != ["damage", "speed", "durability", "health"]: die(f"Kind の並びが想定と違う: {[k for k, _ in KINDS]}")
if FLESH_PER_ELIXIR != 256: die(f"エリクサーは腐肉4スタック(256個)のはず: {FLESH_PER_ELIXIR}")
if [len(v) for _, v in KINDS] != [4, 4, 3, 5]: die("エリクサーの段階数が仕様(4/4/3/5)と違う")
MAX_LV = max(len(v) for _, v in KINDS)

def pct(v): return str(int(round(v * 100)))
def shown(kid, v):
    """ツールチップに入れる数値(割合は%、体力はハートの数)"""
    return str(int(v)) if kid == "health" else pct(v)

# ---------------------------------------------------------------- テクスチャ(エリクサー)
KIND_COLOR = {"health": (0xd9, 0x4a, 0x4a), "damage": (0xe8, 0x89, 0x2b), "speed": (0x4a, 0xc8, 0xe0), "durability": (0x7d, 0x8f, 0xb0)}
OUTLINE = (0x2e, 0x2e, 0x3a, 255)
GLASS = (0xd7, 0xeb, 0xf5, 200)
CORK = (0x9c, 0x6b, 0x3a, 255)
GOLD = (0xff, 0xd8, 0x4a, 255)

BODY = {5: (5, 10), 6: (4, 11), 7: (3, 12), 8: (3, 12), 9: (3, 12), 10: (3, 12), 11: (3, 12), 12: (3, 12), 13: (4, 11), 14: (5, 10)}
NECK = {2: (6, 9), 3: (6, 9), 4: (6, 9)}

def bottle(kind, level, maxlv):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    shape = set()
    for rows in (BODY, NECK):
        for y, (a, b) in rows.items():
            for x in range(a, b + 1): shape.add((x, y))
    r, g, b_ = KIND_COLOR[kind]
    liquid = (r, g, b_, 255)
    liquid_hi = (min(255, r + 40), min(255, g + 40), min(255, b_ + 40), 255)
    edge = (r, g, b_, 255) if level >= maxlv else OUTLINE      # その種類の最高レベルは縁が薬の色に光る
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

# ---------------------------------------------------------------- テクスチャ(祭壇)
STONE = [(0x4b, 0x4b, 0x55), (0x55, 0x55, 0x60), (0x44, 0x44, 0x4e)]
MORTAR = (0x2c, 0x2c, 0x34)
FLESH = [(0x7a, 0x3a, 0x3a), (0x6a, 0x2e, 0x30), (0x8a, 0x48, 0x42)]
ROT = [(0x7a, 0x8f, 0x2e), (0x66, 0x7a, 0x25), (0x5b, 0x2a, 0x2a), (0x8c, 0xa3, 0x3a)]

def bricks(rnd):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 255))
    px = img.load()
    for y in range(16):
        for x in range(16):
            row = y // 4
            off = 0 if row % 2 == 0 else 4
            if y % 4 == 3 or (x + off) % 8 == 7: px[x, y] = MORTAR + (255,)
            else: px[x, y] = rnd.choice(STONE) + (255,)
    return img

def altar_side():
    rnd = random.Random(20240601)
    img = bricks(rnd)
    px = img.load()
    for x in range(16):                                   # 上端に腐肉が垂れた帯
        depth = 3 + (x * 7 % 5 == 0) + (x * 3 % 4 == 0)
        for y in range(depth):
            px[x, y] = rnd.choice(FLESH) + (255,)
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8), (7, 9), (8, 9), (6, 8), (9, 8)):   # 緑に光るルーン(十字)
        px[x, y] = (0x7a, 0xd0, 0x3a, 255) if (x, y) in ((7, 8), (8, 8)) else (0x4f, 0x8f, 0x28, 255)
    return img

def altar_bottom():
    return bricks(random.Random(777))

def altar_top():
    rnd = random.Random(31337)
    img = bricks(rnd)
    px = img.load()
    for y in range(3, 13):                                # 中央のくぼみ(腐った肉が溜まっている)
        for x in range(3, 13):
            edge = x in (3, 12) or y in (3, 12)
            px[x, y] = MORTAR + (255,) if edge else rnd.choice(ROT) + (255,)
    for (x, y) in ((5, 5), (9, 8), (6, 10), (10, 5)):     # 骨のかけら
        px[x, y] = (0xe8, 0xe4, 0xd0, 255)
        px[x + 1, y] = (0xc9, 0xc4, 0xac, 255)
    return img

# ---------------------------------------------------------------- 言語
ROMAN = ["", "I", "II", "III", "IV", "V"]
KIND_EN = {"damage": "Damage", "speed": "Speed", "durability": "Durability", "health": "Health"}
KIND_JA = {"damage": "ダメージ", "speed": "スピード", "durability": "耐久", "health": "体力"}

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
            "zapoc.horde.reward": "§a生き延びた報酬: 腐肉 ×%1$s",
            "zapoc.sleep.blocked": "眠れない。今夜は死者たちが騒がしい。",
            "zapoc.elixir.drunk": "§a%1$sのエリクサー(Lv%2$s)を飲んだ。効果は永続する。",
            "zapoc.elixir.already": "%1$sはすでに同じか上のレベルだ。",
            "tooltip.claudeganbate.permanent": "永続: 死んでも消えない",
            "tooltip.claudeganbate.level": "Lv %1$s / %2$s",
            "tooltip.claudeganbate.damage": "与えるダメージ +%1$s%%",
            "tooltip.claudeganbate.speed": "移動速度 +%1$s%%",
            "tooltip.claudeganbate.durability": "受けるダメージ -%1$s%%",
            "tooltip.claudeganbate.health": "最大体力 +%1$sハート",
            "block.claudeganbate.flesh_altar": "腐肉の祭壇",
            "container.claudeganbate.flesh_altar": "腐肉の祭壇",
            "tooltip.claudeganbate.altar.1": "右クリックでエリクサーの作成画面を開く",
            "tooltip.claudeganbate.altar.2": f"エリクサー1個 = 腐肉{FLESH_PER_ELIXIR}個(4スタック)",
            "zapoc.altar.current": "現在 Lv %1$s / %2$s",
            "zapoc.altar.locked": "Lv%1$s(まだ作れない)",
            "zapoc.altar.locked.hint": "先に Lv%1$s のエリクサーを作って飲む必要がある",
            "zapoc.altar.learned": "習得済み",
            "zapoc.altar.click": "クリックで作成",
            "zapoc.altar.cost": "腐肉 %1$s / %2$s",
            "zapoc.altar.flesh": "持っている腐肉: %1$s個",
            "zapoc.altar.info.1": f"エリクサー1個につき腐肉%1$s個(4スタック)を使う",
            "zapoc.altar.info.2": "インベントリの腐肉が使われる",
            "zapoc.altar.made": "§a%1$sのエリクサー Lv%2$s を作った",
            "zapoc.altar.noflesh": "§c腐肉が足りない(%1$s / %2$s)",
            "zapoc.altar.held": "そのエリクサーはすでに持っている",
        })
    else:
        d.update({
            "zapoc.dawn": "§6[Zombie Apocalypse] Day %1$s dawns. Threat level %2$s. Next horde night: day %3$s.",
            "zapoc.levelup": "§c[Zombie Apocalypse] The dead grow stronger... (Threat Lv %1$s)",
            "zapoc.horde.warning": "§c[Zombie Apocalypse] A horde is coming tonight... Fortify your base and get ready!",
            "zapoc.horde.wave": "§cWave %1$s / %2$s",
            "zapoc.horde.dawn": "§e[Zombie Apocalypse] Dawn breaks. The horde falls back.",
            "zapoc.horde.cleared": "§a[Zombie Apocalypse] You held the line. The horde is cleared.",
            "zapoc.horde.reward": "§aSurvival reward: %1$s rotten flesh",
            "zapoc.sleep.blocked": "You can't sleep. The dead are restless tonight.",
            "zapoc.elixir.drunk": "§aYou drank the Elixir of %1$s (Lv %2$s). The effect is permanent.",
            "zapoc.elixir.already": "You already have %1$s at this level or higher.",
            "tooltip.claudeganbate.permanent": "Permanent: kept even after death",
            "tooltip.claudeganbate.level": "Level %1$s / %2$s",
            "tooltip.claudeganbate.damage": "Damage dealt +%1$s%%",
            "tooltip.claudeganbate.speed": "Movement speed +%1$s%%",
            "tooltip.claudeganbate.durability": "Damage taken -%1$s%%",
            "tooltip.claudeganbate.health": "Max health +%1$s hearts",
            "block.claudeganbate.flesh_altar": "Flesh Altar",
            "container.claudeganbate.flesh_altar": "Flesh Altar",
            "tooltip.claudeganbate.altar.1": "Right-click to open the elixir crafting screen",
            "tooltip.claudeganbate.altar.2": f"1 elixir = {FLESH_PER_ELIXIR} rotten flesh (4 stacks)",
            "zapoc.altar.current": "Current: Lv %1$s / %2$s",
            "zapoc.altar.locked": "Lv %1$s (locked)",
            "zapoc.altar.locked.hint": "Craft and drink the Lv %1$s elixir first",
            "zapoc.altar.learned": "Learned",
            "zapoc.altar.click": "Click to craft",
            "zapoc.altar.cost": "Rotten flesh %1$s / %2$s",
            "zapoc.altar.flesh": "Rotten flesh you carry: %1$s",
            "zapoc.altar.info.1": "Each elixir uses %1$s rotten flesh (4 stacks)",
            "zapoc.altar.info.2": "Taken from your inventory",
            "zapoc.altar.made": "§aCrafted the Elixir of %1$s Lv %2$s",
            "zapoc.altar.noflesh": "§cNot enough rotten flesh (%1$s / %2$s)",
            "zapoc.altar.held": "You already carry that elixir",
        })
    for kid, vals in KINDS:
        d[f"zapoc.elixir.kind.{kid}"] = KIND_JA[kid] if ja else KIND_EN[kid]
        for lv in range(1, len(vals) + 1):
            d[f"item.{MODID}.elixir_{kid}_{lv}"] = f"{KIND_JA[kid]}のエリクサー {ROMAN[lv]}" if ja else f"Elixir of {KIND_EN[kid]} {ROMAN[lv]}"
    return d

# ---------------------------------------------------------------- モデル・レシピ・ルートテーブル
def altar_model():
    side = lambda: {"texture": "#side", "uv": [0, 0, 16, 12]}
    pile_side = lambda: {"texture": "#top", "uv": [3, 0, 13, 2]}
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": f"{MODID}:block/flesh_altar_side", "side": f"{MODID}:block/flesh_altar_side",
                     "top": f"{MODID}:block/flesh_altar_top", "bottom": f"{MODID}:block/flesh_altar_bottom"},
        "elements": [
            {"from": [0, 0, 0], "to": [16, 12, 16], "faces": {
                "down": {"texture": "#bottom", "cullface": "down"}, "up": {"texture": "#top"},
                "north": dict(side(), cullface="north"), "south": dict(side(), cullface="south"),
                "west": dict(side(), cullface="west"), "east": dict(side(), cullface="east")}},
            {"from": [3, 12, 3], "to": [13, 14, 13], "faces": {
                "up": {"texture": "#top", "uv": [3, 3, 13, 13]}, "north": pile_side(), "south": pile_side(),
                "west": pile_side(), "east": pile_side()}},
        ],
    }

def altar_recipe():
    return {"type": "minecraft:crafting_shaped", "category": "misc",
            "key": {"F": {"item": "minecraft:rotten_flesh"}, "B": {"item": "minecraft:bone"},
                    "C": {"item": "minecraft:cauldron"}, "S": {"item": "minecraft:stone_bricks"}},
            "pattern": ["FFF", "BCB", "SSS"], "result": {"item": f"{MODID}:flesh_altar"}}

def build():
    """ {相対パス: dict または PIL.Image} を返す """
    out = {}
    for kid, vals in KINDS:
        for lv in range(1, len(vals) + 1):
            iid = f"elixir_{kid}_{lv}"
            out[f"assets/{MODID}/models/item/{iid}.json"] = {"parent": "item/generated", "textures": {"layer0": f"{MODID}:item/{iid}"}}
            out[f"assets/{MODID}/textures/item/{iid}.png"] = bottle(kid, lv, len(vals))
    # 祭壇
    out[f"assets/{MODID}/blockstates/flesh_altar.json"] = {"variants": {"": {"model": f"{MODID}:block/flesh_altar"}}}
    out[f"assets/{MODID}/models/block/flesh_altar.json"] = altar_model()
    out[f"assets/{MODID}/models/item/flesh_altar.json"] = {"parent": f"{MODID}:block/flesh_altar"}
    out[f"assets/{MODID}/textures/block/flesh_altar_side.png"] = altar_side()
    out[f"assets/{MODID}/textures/block/flesh_altar_top.png"] = altar_top()
    out[f"assets/{MODID}/textures/block/flesh_altar_bottom.png"] = altar_bottom()
    out[f"data/{MODID}/loot_tables/blocks/flesh_altar.json"] = {
        "type": "minecraft:block",
        "pools": [{"rolls": 1.0, "bonus_rolls": 0.0, "entries": [{"type": "minecraft:item", "name": f"{MODID}:flesh_altar"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": f"{MODID}:blocks/flesh_altar"}
    out[f"data/{MODID}/recipes/flesh_altar.json"] = altar_recipe()
    out["data/minecraft/tags/blocks/mineable/pickaxe.json"] = {"replace": False, "values": [f"{MODID}:flesh_altar"]}
    out[f"assets/{MODID}/lang/en_us.json"] = lang(False)
    out[f"assets/{MODID}/lang/ja_jp.json"] = lang(True)
    return out

# 生成物だけが入っているディレクトリ(ここに build() に無いファイルがあれば古い生成物として扱う)
MANAGED_DIRS = [f"assets/{MODID}/models/item", f"assets/{MODID}/textures/item", f"assets/{MODID}/models/block",
                f"assets/{MODID}/textures/block", f"assets/{MODID}/blockstates", f"data/{MODID}/recipes", f"data/{MODID}/loot_tables"]

def render(obj):
    if isinstance(obj, Image.Image): return obj
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n")

def main():
    check = "--check" in sys.argv
    stale = []
    built = build()
    for rel, obj in built.items():
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
    # 古い生成物(旧エリクサー・腐肉のエッセンスなど)
    for d in MANAGED_DIRS:
        base = os.path.join(RES, d)
        for dp, _, files in os.walk(base):
            for f in files:
                rel = os.path.relpath(os.path.join(dp, f), RES).replace(os.sep, "/")
                if rel not in built:
                    stale.append("(削除) " + rel)
                    if not check: os.remove(os.path.join(dp, f))
    if check:
        if stale:
            print("生成物が古い/欠けています(python3 tools/gen_assets.py を実行してコミット):"); [print(" ", s) for s in stale[:20]]
            sys.exit(1)
        print("assets up to date")
    else:
        print(f"generated: {len(stale)} files updated")

main()

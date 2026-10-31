#!/usr/bin/env python3
"""从上游 BetterNether 的方块数据生成 CraftEngine 方块/物品配置。

## 真值来源（一律读文件，绝不按名字猜）

上游把每一项属性都 datagen 导出了，本脚本只读这些文件：

| 文件 | 提供 |
|---|---|
| `src/main/generated/block_shapes.txt` | 每个状态的轮廓与碰撞箱 |
| `src/main/generated/block_properties.json` | destroyTime / resistance / instrument / lightEmission / canOcclude / replaceable / waterlogged / pushReaction / friction / speed / jump / ignitedByLava / mapColor / sound / renderLayer |
| `src/main/generated/data/minecraft/tags/block/mineable/*.json` | **正确的挖掘工具** |
| `src/main/generated/data/minecraft/tags/block/needs_{iron,diamond}_tool.json` | 工具等级 → `required_break_power` |
| `src/main/generated/data/minecraft/tags/block/*.json` | leaves / logs / planks / replaceable 等语义 |
| `src/main/generated/block_registrations.json` | 可燃性（`spread/burn`） |

旧版用名字推断材质（`if "log" in name: 斧`），结果 `nether_cactus`（镐）被当成
植物、`cincinnasite_pillar`（镐）被漏掉、木头用斧挖反而更慢。现在这些判断全部
来自上游标签，任何一个数字都能指回某个文件。

## CE 能表达什么、不能表达什么

CraftEngine 自定义方块的**碰撞箱来自它借用的原版视觉状态**（`auto_state` 池），
不是自己声明的。`auto_state` 的取值是 **CE 写死的枚举**（`AutoStateGroup`），
不是自由组名：写错一个名字 CE 会报「无效的常量」，而这个方块**完全没有外观**
（2026-10-28 实测丢了 37 个植物）。合法值见本文件的 `AUTO_STATE_GROUPS`，
门禁 `plant_states.known_auto_state_group` 会拿产物逐条核对。池只有三档几何：

  * 满格立方体 —— `solid` / `note_block` / `mushroom_stem` …
  * 树叶（满格 + cutout 渲染）—— `leaves` / `non_tintable_leaves` …
  * 纤细/半高 —— `tripwire` / `lower_tripwire` / `sapling` / `kelp` / `cave_vine` …

于是本脚本按形状表的三档归类（`props.load_shape_tiers`）：

  * **满格立方体** → 可完整表达，含亮度/遮挡/可燃/推动反应/工具等级。
  * **无碰撞装饰** → 植物可表达（十字/悬挂）；告示牌与压力板不可（见下）。
  * **异形碰撞**（半砖/楼梯/墙/栅栏/门/活板门/按钮/梯子/窗格/家具）
        → **CE 无法表达**。借满格池会变成实心方块（物理错误），借纤细池会穿模。
        所以不输出，并在 `reports/block-inventory.json` 里逐条记录原因。

`required_break_power` 是 CE 的真实字段（`docs/blocks.md`「所需破坏力」，默认 0），
取值按原版工具破坏力：木/金=1、石/铜=2、铁=3、钻石=4、下界合金=5。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# 本文件既能被 extract_bn.py 当模块导入，也能直接当脚本执行（Gradle 的 Exec 任务就是这么调的）。
# 直接执行时 scripts/ 不在 sys.path 上，所以显式补一次。
_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from bnlib.namespace import NAMESPACE, UPSTREAM_PREFIX, rewrite_id  # noqa: E402
from bnlib import furniture  # noqa: E402  (家具化：清单 + YAML 生成，见 bnlib/furniture.py)
from bnlib import models as model_synth  # noqa: E402,F401  (保留给下游 hook 引用)
from bnlib import ore_drops  # noqa: E402
from bnlib import props as block_props  # noqa: E402
from bnlib import support  # noqa: E402
from bnlib import transparency  # noqa: E402
from bnlib import publication  # noqa: E402
from bnlib.publication import is_removed_block  # noqa: E402

MOD_NS = NAMESPACE + ":"

# 已知的矿物族：这些给 ore 模板与 basedrum 音（音效本身仍取上游 sound 字段）。
ORE_NAMES = {
    "cincinnasite_ore",
    "nether_ruby_ore",
    "nether_lapis_ore",
    "nether_redstone_ore",
}

# ---------------------------------------------------------------- 译名别名
#
# ## 为什么需要这张表
#
# 上游的**方块 id** 与**语言键**并非总是一致。`stalagnate_trunk` 是真实存在的方块
# （`block_shapes.txt` / `block_properties.json` / 结构 NBT 里都是它），但上游
# `en_us.json` 里**没有** `block.betternether.stalagnate_trunk` 这个键 —— 它的名字
# 写在 `block.betternether.stalagnate_stem` 下（`stalagnate_stem` 并不是一个方块 id）。
#
# 于是会出现两个后果，都必须在这里处理：
#   1. `item_name` 若写 `<lang:block.betternether.stalagnate_trunk>`，客户端查不到键，
#      玩家看到的是原始键名。
#   2. extractor 的 `build_langs` 在「上游 en/zh 都查不到译名」时会 `SystemExit`
#      拒绝生成（scripts/extract_bn.py:548-553），整个迁移被这一个方块卡死。
#
# 处理方式：**不伪造英文直译**（`Trunk`/`Stem` 都是猜），而是把语言键指向上游真实存在
# 的那一个。别名只影响语言键，方块 id 保持 `stalagnate_trunk` 不变 —— 结构 NBT 的
# palette 引用的是方块 id，改 id 会让结构里出现空洞。
LANG_ALIASES = {
    "stalagnate_trunk": "stalagnate_stem",
}

# 上游语言表里实际存在的键集合（由 `load_lang_table` 在 main 里填充）。
# 用它来**实测**候选键是否存在，而不是靠猜后缀。
_LANG_KEYS: set[str] = set()


def load_lang_table(upstream: Path) -> None:
    """载入上游 `en_us.json` 的键集合，供 {@link lang_name_of} 实测候选键。"""
    global _LANG_KEYS
    source = upstream / "src/main/resources/assets/betternether/lang/en_us.json"
    try:
        table = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        _LANG_KEYS = set()
        return
    _LANG_KEYS = {k for k in table if isinstance(k, str)}


def _has_lang(name: str) -> bool:
    return (f"block.betternether.{name}" in _LANG_KEYS
            or f"item.betternether.{name}" in _LANG_KEYS)


def lang_name_of(name: str) -> str:
    """这个方块该用哪个语言键后缀。

    优先级：
      1. 显式别名表（{@link LANG_ALIASES}）；
      2. 贴墙变体回退到它的立式同名键 —— 上游只给立式告示牌写了名字
         （`anchor_tree_sign` 有键、`anchor_tree_wall_sign` 没有），两者本来就是同一件物品；
      3. 原名。

    每一步都用上游语言表**实测**，表里没有就退回原名，绝不凭空造键。
    """
    if name in LANG_ALIASES:
        return LANG_ALIASES[name]
    if not _LANG_KEYS:
        return name
    candidates: list[str] = []
    if name.endswith("_hanging_wall_sign"):
        candidates.append(name[: -len("_hanging_wall_sign")] + "_hanging_sign")
    if name.endswith("_wall_sign"):
        candidates.append(name[: -len("_wall_sign")] + "_sign")
    for candidate in candidates:
        if _has_lang(candidate):
            return candidate
    return name


def lang_key_of(name: str) -> str:
    """CE `<lang:...>` 使用的完整键。"""
    return f"block.{NAMESPACE}.{lang_name_of(name)}"

# ---------------------------------------------------------------- 分类

# CE 分类（`configuration/categories/*.yml`），对齐**上游模组自己的创造模式分页**。
#
# 上游 `BECreativeTabs.java` 只显式建了一个自定义分页 `nature`：
#     createTab("nature").setPredicate(植物类 || 龙舌兰叶 || 黑苹果 || 岩浆花 || 鲁贝斯球果 || 柳枝火把)
# 其余（地形、装备）由 `createBlockOnlyTab` / `createItemOnlyTab` 按注册表整族生成。
#
# 本移植的方块落在 stones / woods / misc 三组；装备由 items.py 的 `EXTRA_CATEGORIES`
# 追加 tools / weapons / armor（见 `build_category_leaves`）。
# `nature` 必须覆盖上游 `NetherPlantBlocks` 的整族 —— 那份名单才是权威，不要靠名字后缀猜。
UPSTREAM_PLANT_BLOCKS = {
    # 逐字抄自上游 registry/block/NetherPlantBlocks.java 的成员名（小写化）。
    "eye_seed", "nether_grass", "swamp_grass", "soul_grass", "jungle_plant",
    "bone_grass", "pale_gloomgrass", "gloomgrass", "sepia_bone_grass",
    "black_bush", "ink_bush", "ink_bush_seed", "egg_plant", "magma_flower",
    "feather_fern", "moss_cover", "neon_equisetum", "agave", "barrel_cactus",
    "nether_cactus", "jungle_moss", "soul_lily", "eyeball", "eyeball_small",
}

# 上游 `NetherTerrainBlocks` 整族（地形/地表方块）。
UPSTREAM_TERRAIN_BLOCKS = {
    "farmland", "gloomsculk_geode", "gloomsculk_geode_crystal",
    "gloomsculk_lamp", "gloomsculk_crystal", "veined_sand",
}

# 上游 nature 分页额外收的几件「不是植物方块、但归自然」的东西。
UPSTREAM_NATURE_EXTRAS = {
    "black_apple", "black_apple_seed", "rubeus_cone", "willow_torch",
    "lumabus_seed", "golden_lumabus_seed", "wart_seed", "soul_lily_sapling",
}

# 分类键：与「更好的末地」保持一致的叶子分类（用户要求两插件分类一致）。
# `openable` 仍保留常量（`category_of` 的判定分支还在，属防御性保留），但它**已不在
# CATEGORY_ORDER 里**：门/活板门/闸门/压力板/按钮/拉杆按用户要求全部移除后，
# 这个分类永远不会再有成员。留一个永远跳过的分类只会制造噪声。
CATEGORY_STONES = "stones"
CATEGORY_WOODS = "woods"
CATEGORY_MISC = "misc"
CATEGORY_FOLIAGE = "foliage"
CATEGORY_FLORA = "flora"
CATEGORY_GROWTHS = "growths"
CATEGORY_LIGHTS = "lights"
CATEGORY_METALS = "metals"
CATEGORY_OPENABLE = "openable"
CATEGORY_FURNISHINGS = "furnishings"

# 叶子分类顺序（根分类 list 与 build_category_leaves 都按这个顺序，对齐 BetterEnd）。
CATEGORY_ORDER = (
    CATEGORY_STONES, CATEGORY_WOODS, CATEGORY_FOLIAGE,
    CATEGORY_FLORA, CATEGORY_GROWTHS, CATEGORY_LIGHTS, CATEGORY_METALS,
    CATEGORY_FURNISHINGS, CATEGORY_MISC,
)


def category_of(block_id: str, is_plant: bool) -> str:
    """CE 分类 id，**与「更好的末地」的叶子分类保持一致**：

        stones / woods / foliage / flora / growths / lights / metals /
        furnishings / misc

    （`openable` 不在其中：该族方块已全部移除，见 `CATEGORY_ORDER` 上方的说明。）

    判定按材质/用途关键词，顺序敏感（先命中先归类）。
    """
    name = block_id.split(":", 1)[1]

    def has(*keys: str) -> bool:
        return any(k in name for k in keys)

    # 可开关的异形族
    if has("_door", "_trapdoor", "_gate", "_plate"):
        return f"{NAMESPACE}:{CATEGORY_OPENABLE}"
    # 家具/容器/工作台
    if has("furniture", "bar_stool", "chair", "taburet", "_stool", "pedestal",
           "_pot", "_chest", "barrel", "bookshelf", "crafting_table", "_frame",
           "drawers"):
        return f"{NAMESPACE}:{CATEGORY_FURNISHINGS}"
    # 光照
    if has("lantern", "torch", "lamp", "crystal", "geode", "lucis", "glow"):
        return f"{NAMESPACE}:{CATEGORY_LIGHTS}"
    # 木头族
    if has("_log", "_bark", "_planks", "stripped", "wood", "_stem"):
        return f"{NAMESPACE}:{CATEGORY_WOODS}"
    # 叶子/树苗/藤蔓 -> foliage
    if has("_leaves", "_sapling", "_vine", "_branch", "_trunk", "_roots"):
        return f"{NAMESPACE}:{CATEGORY_FOLIAGE}"
    # 金属（矿物类金属块；矿石归 stones）
    if has("_ore", "obsidian", "basalt", "blackstone", "bone", "quartz",
           "glass", "stone", "brick", "tile", "sandstone"):
        return f"{NAMESPACE}:{CATEGORY_STONES}"
    if has("cincinnasite", "ruby", "flaming", "metal", "ingot"):
        return f"{NAMESPACE}:{CATEGORY_METALS}"
    # 菌类/苔藓 -> growths
    if has("mushroom", "mold", "moss", "fungus"):
        return f"{NAMESPACE}:{CATEGORY_GROWTHS}"
    # 其余植物/地表小装饰 -> flora
    if is_plant or has("grass", "fern", "flower", "bush", "agave", "reed",
                       "cactus", "equisetum", "lumabus", "wart", "cover", "eye",
                       "gourd", "algae", "seed", "spore", "soul_lily", "ink",
                       "egg_plant", "black_apple", "cone", "jungle_plant"):
        return f"{NAMESPACE}:{CATEGORY_FLORA}"
    # 地形（农场土/砂/石棉等）归 stones，其余杂项
    if has("farmland", "sand", "soil", "dirt", "grass_block"):
        return f"{NAMESPACE}:{CATEGORY_STONES}"
    return f"{NAMESPACE}:{CATEGORY_MISC}"


# 批次 = 文件分组（便于 review），与分类是两件事：一个批次里可以有多个分类的方块。
BATCHES: list[tuple[str, tuple[str, ...]]] = [
    # 矿物必须排在最前：`cincinnasite_ore` 同时含 `cincinnasite`（N4 的关键词），
    # 若 N4 先匹配，四个矿里的三个会散落进石头族。
    ("N5-ores", ("_ore",)),
    ("N1-wood-families", ("_log", "_bark", "_planks", "_stem", "wood", "stripped")),
    ("N2-leaves-saplings", ("_leaves", "_sapling")),
    ("N3-terrain", UPSTREAM_TERRAIN_BLOCKS),
    ("N4-stone-families",
     ("cincinnasite", "basalt", "blackstone", "obsidian", "glass", "bone",
      "flaming", "quartz", "gloomsculk", "molten")),
    ("N6-plants",
     ("grass", "fern", "bush", "flower", "mushroom", "mold", "moss", "agave",
      "reed", "cactus", "equisetum", "lumabus", "sakura", "wart", "cover", "eye",
      "gourd", "algae", "seed", "spore", "soul_lily", "nether_reed", "ink", "egg_plant",
      "black_apple", "cone")),
    ("N7-lanterns-light", ("lantern", "torch", "lamp", "crystal", "geode", "lucis")),
    ("N8-storage-misc", ("bookshelf", "barrel", "crafting_table", "chest", "frame", "pedestal")),
]

FALLBACK_BATCH = "N9-misc"

# 批次只作为**文件内**的中文注释横幅分组，不再每个批次一个 yml：
# 一个文件夹一个产物，CE 侧少一层扫描，人工 review 也不用跨文件跳。
BLOCKS_OUTPUT_NAME = "bn-blocks.yml"

# 语言文件也只有一个：分类译名（本模块写）与方块/物品译名（extract_bn 的 build_langs 写）
# 都并进这一个文件的不同段里。
LANGS_OUTPUT_NAME = "bn-lang.yml"

# 批次横幅里的中文名（仅用于注释，不影响任何 id/分类）。
BATCH_LABELS: dict[str, str] = {
    "N1-wood-families": "木族",
    "N2-leaves-saplings": "树叶与树苗",
    "N3-terrain": "地形方块",
    "N4-stone-families": "石族",
    "N5-ores": "矿物",
    "N6-plants": "植物",
    "N7-lanterns-light": "灯笼与光源",
    "N8-storage-misc": "储物与杂项",
    FALLBACK_BATCH: "杂项",
}


def batch_banner(batch: str, indent: str = "") -> str:
    """批次分组横幅（注释行）。横幅缩进到段内，这样它不会被当成顶层键。"""
    label = BATCH_LABELS.get(batch, batch)
    return f"{indent}# ================= {batch} {label} =================\n"


# 顶层段里的条目行：两空格缩进、以 `:` 结尾的键（`  betternether:xxx:`）。
# 键里本身含一个 `:`（命名空间分隔符），所以不能写 `[^:]*`，那会让所有 id 都匹配不上。
SECTION_ID_LINE = re.compile(r"^  ([^\s#]\S*):\s*$")


def section_ids(chunks: list[str]) -> list[str]:
    """从一个顶层段的**条目列表**里取出全部 id（只数两空格缩进、顶格的键）。

    元素是「一个条目的整段文本」（可能多行），所以必须先按行拆开再匹配 ——
    直接拿整段去 match 会因为中间的行不是空白而永远不命中（那就等于这条自检没跑）。
    """
    return [match.group(1)
            for chunk in chunks
            for line in chunk.splitlines()
            if (match := SECTION_ID_LINE.match(line))]


def assert_unique_sections(sections: dict[str, list[str]]) -> None:
    """合并后的每个顶层段里，同一个 id 只能出现一次。

    YAML 不允许重复顶层键：`items:`/`blocks:` 里出现两个同名 id 时，后一个会**静默覆盖**
    前一个。原先每个批次一个文件时不可能撞（各文件独立解析），合并成一个文件就必须自己守。
    """
    for name, lines in sections.items():
        seen: set[str] = set()
        duplicated: set[str] = set()
        for block_id in section_ids(lines):
            if block_id in seen:
                duplicated.add(block_id)
            seen.add(block_id)
        if duplicated:
            raise SystemExit(
                f"合并后的 `{name}:` 段里出现重复 id（后者会覆盖前者，条目会凭空消失）："
                f"{sorted(duplicated)}（共 {len(duplicated)} 个）")


def batch_of(block_id: str, is_plant: bool) -> tuple[str, str]:
    """返回 (批次文件名, CE 分类 id)。

    批次决定写进哪个 yml（按材质主题）；分类决定 CE 浏览器里的分页（按上游分页语义）。
    两者刻意分开：`nether_grass` 属于 N6-plants 批次，但分类是 nature。
    """
    name = block_id.split(":", 1)[1]
    category = category_of(block_id, is_plant)
    for batch, needles in BATCHES:
        if any(needle in name for needle in needles):
            return batch, category
    if is_plant:
        return "N6-plants", category
    return FALLBACK_BATCH, category


# ---------------------------------------------------------------- 分类清单
#
# 叶子分类的展示数据（裸名 / 英文名 / 中文名 / 图标）只有**一份清单**：写分类文件与写
# lang 文件都从它遍历。旧版两处各留一份白名单（分类文件里的 `list:` 与 lang 里的
# `labels` 字典），加一个分类要改两个地方，漏一处就是「分类存在、名字显示成裸键」。
#
# 装备分类（tools / weapons / armor）的成员是**物品**，数据归 items.py：它提供
# `EXTRA_CATEGORIES`，本模块只负责按同一套格式写出分类与 lang。用 `getattr` 兜底是
# 因为两个 owner 的进度独立 —— items.py 还没写这个常量时，blocks.py 必须能单独跑通。
CATEGORY_LABELS = {
    CATEGORY_STONES: ("BetterNether Stones", "BetterNether 石质方块"),
    CATEGORY_WOODS: ("BetterNether Woods", "BetterNether 木质方块"),
    CATEGORY_FOLIAGE: ("BetterNether Foliage", "BetterNether 枝叶"),
    CATEGORY_OPENABLE: ("BetterNether Openable", "BetterNether 可开关"),
    CATEGORY_FLORA: ("BetterNether Flora", "BetterNether 植物"),
    CATEGORY_GROWTHS: ("BetterNether Growths", "BetterNether 菌类"),
    CATEGORY_LIGHTS: ("BetterNether Lights", "BetterNether 光源"),
    CATEGORY_METALS: ("BetterNether Metals", "BetterNether 金属"),
    CATEGORY_FURNISHINGS: ("BetterNether Furnishings", "BetterNether 家具"),
    CATEGORY_MISC: ("BetterNether Misc", "BetterNether 杂项"),
}

# 装备分类在本包找不到图标时的回退（装备已并入 misc，这里保留确定存在的同族物品）。
EXTRA_CATEGORY_FALLBACK_ICONS = {
    "tools": "cincinnasite_pickaxe",
    "weapons": "cincinnasite_sword",
    "armor": "cincinnasite_chestplate",
}


def _entry_field(entry: object, *names: str) -> str:
    """按属性或键取第一个非空字符串（兼容 `ItemCategory` 对象与 dict 两种形态）。"""
    for name in names:
        value = entry.get(name) if isinstance(entry, dict) else getattr(entry, name, None)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def extra_categories() -> list[dict]:
    """items.py 提供的额外叶子分类，归一成 `{key, id, en, zh, icon_hint}`。

    形态容忍两种：`ItemCategory` 这类对象（按属性取）与 dict（按键取），两个 owner
    各自演进时不会因为形态差异静默丢掉分类。对端没有该常量时返回空列表。
    """
    try:
        from bnlib import items as item_catalog
    except ImportError:
        return []
    result: list[dict] = []
    for entry in getattr(item_catalog, "EXTRA_CATEGORIES", ()):
        name = _entry_field(entry, "name", "key", "id")
        if not name:
            continue
        # `id` 可能写成 `betternether:tools`；分类键与 lang 键只认裸名。
        key = name.split(":", 1)[-1]
        result.append({
            "key": key,
            "id": f"{NAMESPACE}:{key}",
            "en": _entry_field(entry, "en", "name_en", "en_us") or key.replace("_", " ").title(),
            "zh": _entry_field(entry, "zh", "name_zh", "zh_cn") or key,
            "icon_hint": _entry_field(entry, "icon_hint", "icon", "icon_id"),
        })
    return result


def extra_category_icon(entry: dict, published: set[str], fallback: str) -> str:
    """装备分类的图标：对端建议值 → 同族铌钙石对应件 → 已知存在的方块。

    图标必须是**真实存在**的物品：写一个拼错的 id，CE 只会把图标渲染成空气，配置本身
    不报错。所以逐个按注册清单校验，而不是「看起来合理就用」。
    """
    candidates = [entry["icon_hint"], EXTRA_CATEGORY_FALLBACK_ICONS.get(entry["key"], "")]
    for candidate in candidates:
        if not candidate:
            continue
        ident = candidate if ":" in candidate else f"{NAMESPACE}:{candidate}"
        if ident in published:
            return ident
    return fallback


def build_category_leaves(block_icons: dict[str, str], published: set[str],
                          extra: list[dict] | None = None) -> list[dict]:
    """叶子分类清单，顺序按 {@link CATEGORY_ORDER}（与更好的末地一致的 10 类）。

    `block_icons` 是 `{分类 id: 该分类第一个方块 id}`；`published` 用于给分类挑真实图标。
    `extra`（items.py 的 tools/weapons/armor）不再单列 —— 那两个插件分类集不带装备分页，
    装备物品已并入 misc，这里忽略 extra。
    每一项是 `{id, key, en, zh, icon}`，`priority` 由调用方按顺序编号（根固定 1）。

    2026-10-30 用户要求「分类表里每个条目都必须对应到已注册且存在的物品或方块」。
    所以在 `CATEGORY_ORDER` 里再筛一道：**没有任何成员分类整个不生成**
    （`block_icons` 只由已发布方块的 `category_of()` 结果填充，没成员的分类不在里面）。

    同类条目也校验**图标本身**：图标 id 必须出现在 `published`（本次发布的方块 ∪ 上游注册
    的物品）里，否则整个分类跳过、不生成，并在 stdout 记一行。

    坦白一句：`block_icons` 的值本来就是已发布方块的 id，所以这道校验在当前数据流下
    **基本不会命中**——它防的是「以后有人改成从别处取图标」这种情况，别把它当成强保护。

    为什么必须这样做：`openable` 一类的分类只在门/活板门/闸门/压力板存在时才有成员，
    这些方块按用户要求已经全部移除，于是分类会变成「声明了但一个物品都不指向它」的空壳。
    生成空分类会让 CE 浏览器里出现点不开的空页，也与「每条目都要对应实际物品」冲突。
    """
    fallback = next(iter(block_icons.values()), f"{NAMESPACE}:{NAMESPACE}")
    leaves: list[dict] = []
    skipped_icons: list[str] = []
    for key in CATEGORY_ORDER:
        category_id = f"{NAMESPACE}:{key}"
        if category_id not in block_icons:
            continue          # 无成员：不生成，也不进根分类的 list
        icon = block_icons[category_id]
        if icon not in published:
            skipped_icons.append(icon)   # 图标不在已发布 id 里：整个分类不生成
            continue
        en, zh = CATEGORY_LABELS[key]
        leaves.append({"id": category_id, "key": key, "en": en, "zh": zh,
                       "icon": icon})
    if skipped_icons:
        print(f"[分类] 图标未发布，跳过 {len(skipped_icons)} 个分类："
              + "、".join(skipped_icons))
    return leaves


def load_item_ids(upstream: Path) -> set[str]:
    """上游注册过的物品 id（`item_registrations.json`），用于校验分类图标。

    读不到时返回空集合：调用方会退回已知存在的方块图标，而不是让整批分类失败。
    """
    path = upstream / "src/main/generated/item_registrations.json"
    try:
        entries = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return set()
    if not isinstance(entries, list):
        return set()
    return {entry["id"] for entry in entries
            if isinstance(entry, dict) and isinstance(entry.get("id"), str)}


def load_registry_tags(upstream: Path, registry: str,
                       namespaces: tuple[str, ...] = (NAMESPACE,)) -> dict[str, tuple[str, ...]]:
    """上游 `data/betternether/tags/<registry>/**.json` -> `{成员 id: (标签 id, …)}`。

    `registry` 是 `item` 或 `block`。成员表就是上游 datagen 导出的那份真值：
    九个木族的 `*_logs`、`flaming_ruby/repair` 全在里面，不按名字后缀猜、也不在这里
    维护第二份名单。标签 id 由相对路径决定（`flaming_ruby/repair.json` ->
    `betternether:flaming_ruby/repair`）。

    ## 为什么 item 与 block 两份都要
    配方里的 `#betternether:<wood>_logs` 由 CE 的 `ItemManager.itemIdsByTag` 解析，
    读的是**物品**注册表的标签（来自物品 `settings.tags`）；方块 `settings.tags` 走
    `TagUtils`/`Registries.BLOCK`，只登记**方块**标签。上游对同名的两份都做了定义，
    所以两边都要声明：物品那份让配方能解析，方块那份保持上游语义。
    """
    collected: dict[str, list[str]] = {}
    # `namespaces` 含 `minecraft` 时同时读原版标签（planks/logs/wooden_slabs…）：
    # 原版箱子、工作台、木棍等配方按 `#minecraft:planks` 匹配，物品不挂这些标签就合成不了。
    for namespace in namespaces:
        root = upstream / "src/main/generated/data" / namespace / "tags" / registry
        for path in sorted(root.rglob("*.json")):
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            values = payload.get("values") if isinstance(payload, dict) else None
            if not isinstance(values, list):
                continue
            tag = f"{namespace}:{path.relative_to(root).with_suffix('').as_posix()}"
            for member in values:
                # `#其他标签` 是嵌套引用，不是成员 id（CE 的物品标签不做传递展开，不代填）。
                # 只收本插件的成员：原版标签里的原版物品不归这里管。
                if (isinstance(member, str) and member and not member.startswith("#")
                        and member.startswith(f"{NAMESPACE}:")):
                    collected.setdefault(member, []).append(tag)
    return {member: tuple(tags) for member, tags in collected.items()}


# ---------------------------------------------------------------- CE 能力边界

# 视觉状态池。
#
# `solid`  = 音符盒 + 蘑菇方块，满格立方体碰撞，SOLID 渲染。
# `non_tintable_leaves` = 云杉/白桦/杜鹃/樱花/苍白橡树叶，满格 + **cutout** 渲染且**不染色**。
#     非 SOLID 渲染（cutout/translucent）的满格方块必须借这个池：借 `solid`（音符盒）
#     会让带 alpha 的贴图按 SOLID 渲染，玻璃与树叶会变成实心色块。
#     不能借 `leaves`/`tintable_leaves`：那两个池含可染色树叶，会给自定义贴图套上
#     群系植被色（本包的贴图是预着色的）。
# 纤细池见 `PLANT_STATE_POOLS` / `VINE_STATE_POOLS`。
STATE_POOL_SOLID = "solid"
STATE_POOL_CUTOUT = "non_tintable_leaves"

# CE 的 `directional_attached_block` 要求方块声明 `facing`，取值就是原版六向。
_FACING_VALUES: tuple[str, ...] = ("north", "east", "south", "west", "up", "down")

# 上游资源的两棵树：datagen 产出（模型/blockstate 更全）与手写 resources。
# 只给「按模型 id 找文件」的模块级函数用；main 里另有针对自己目录的局部变量。
UPSTREAM_ASSET_TREES = (
    "src/main/generated/assets/betternether",
    "src/main/resources/assets/betternether",
)

# ---------------------------------------------------------------- auto_state 合法值
#
# `auto_state` **不是自由组名**，而是 CraftEngine 里写死的枚举
# （`net/momireanny/…/block/AutoStateGroup`，反编译 jar 逐个核对）。写错名字的后果
# 不是「退化成别的池」，而是加载时报「无效的常量」+ **该方块完全没有外观**
# （2026-10-28 实测：37 个植物全空，因为 sculk_vein / resin_clump / vine 三个组
# 根本不存在，`chorus_plant` 的真名是 `chorus`）。
#
# 因此这里把枚举的**全部合法取值**（小写别名）写死一份，生成器与门禁都以它为准：
#   `tools/verify_block_contracts.py` 的 `plant_states.known_auto_state_group`
#   会拿产物里出现的每一个 `auto_state` 值对本表断言。
AUTO_STATE_GROUPS = (
    "non_tintable_leaves", "waterlogged_non_tintable_leaves",
    "tintable_leaves", "waterlogged_tintable_leaves",
    "leaves", "waterlogged_leaves",
    "lower_tripwire", "higher_tripwire",
    "note_block", "brown_mushroom", "red_mushroom", "mushroom_stem",
    "tripwire", "sugar_cane", "cactus", "cave_vine", "weeping_vine",
    "twisting_vine", "kelp", "chorus", "pressure_plate", "sapling",
    "mushroom", "solid",
)

# ---------------------------------------------------------------- 植物真值表 ①
#
# ## 为什么植物只能借「无碰撞、且不遮挡」的组
#
# 旧实现让植物走 `transparent: true` + `entity_renderer`（每格一个 item_display 展示实体）。
# 用户反馈太卡：一片地狱草就是几百个实体。改成「真实方块状态 + 模型」后，碰撞箱不再由我们
# 声明，而是**跟着所借的原版状态走**（CE 的 `auto_state` 语义）。于是组的选择直接决定
# 植物是「可以穿过去的花草」还是「看不见的实心墙 / 透视洞」。
#
# 上面 24 个枚举里，**同时**满足 `collEmpty=true` 与 `canOcclude=false` 的只有 8 个组
# （服务端 jar 逐状态取证 `scripts/ce_patch/StateCollisionProbe.java` → `collstates.tsv`）：
#   chorus 63 余量、tripwire 9、weeping_vine 9、cave_vine 1、twisting_vine 1、
#   kelp 1、sugar_cane 1、sapling 1 —— 本包可用合计 **86** 格。
# `cactus` 虽有 11 格余量，但 16 个状态全部 `canOcclude=true`（借它＝挖掉邻面＝透视），
# `note_block`/`solid`/`mushroom`/`mushroom_stem` 满格碰撞 —— 都在禁止集里。
#
# ## 86 格装不下 168 个 appearance：靠「同方块内合并」而不是降级渲染
#
# 每个方块先保底 1 个 appearance，剩余额度补给「第 2、3 个」；额度用尽时把剩下的
# `variants` 键**改指向本方块已发布的那个 appearance**（共用模型），
# 见 {@link merge_plant_appearances} 与 `reports/plant-collision.json["appearance_merges"]`。
NO_COLLISION_POOLS = [
    "tripwire",          # 十字模型，最标准的花草载体（余量 9）
    "weeping_vine",      # 悬挂（15）
    "pressure_plate",    # 压力板：**碰撞箱为空**、不遮挡（27）—— 见下方说明
    "kelp",              # 竖向十字（0）
    "sugar_cane",        # 竖向（0）
    "sapling",           # 树苗（0）
    "cave_vine",         # 悬挂（1）
    "twisting_vine",     # 悬挂（1）
]

# `pressure_plate` 为什么算「无碰撞」：`StateCollisionProbe` 逐状态实测
# `light_weighted_pressure_plate` / `heavy_weighted_pressure_plate` 各 16 个状态，
# `collEmpty=true`、`canOcclude=false` —— 原版压力板只有在**有实体压着**时才产生碰撞，
# 空世界查询返回空形状，所以玩家走过去不会撞到东西。
#
# 代价是它带红石语义：踩在植物上会给周围红石信号。植物通常不长在红石电路旁边，
# 而另一个选择（借 `chorus`）是 10/16 宽的全高柱 —— 成片的地狱草走过去会撞墙，
# 那比偶发红石信号难忍得多。两害相权取这个。
#
# 非可爬植物**实际**可用的组：把三个可爬组从 `NO_COLLISION_POOLS` 里摘掉。
# 那三组只剩 11 格，而 11 个可爬方块刚好一人一格 —— 混用会让可爬方块爬不上去。
NOCLIP_POOLS = [pool for pool in NO_COLLISION_POOLS if pool not in
                ("cave_vine", "weeping_vine", "twisting_vine")]

# 有碰撞但**不遮挡**的组：`chorus` 的碰撞箱是
# `AABB[0.1875,0,0.1875] -> [0.8125,1,0.8125]`（10/16 宽、全高柱），
# 是上游「细柱/半格」植物最接近的几何近似，而且不像 `cactus` 那样剔除邻面。
# 枚举里它就是 CHORUS → `minecraft:chorus_plant`。
#
# **只给 `collision=partial` 的方块用**：那些上游本来就有碰撞（细柱、菌柄、锥体），
# 借它属于形状近似。`collision=empty` 的方块一律不许借 —— 那会把「能穿过去的花草」
# 变成隐形柱子（曾经 26 个地面植物就是这样变成柱子的）。
PARTIAL_COLLISION_POOLS = ["chorus"]

# 藤蔓组：悬挂植物的载体。
# 三个组同时是 `#minecraft:climbable` 的原版方块 —— 可爬方块也只能落在这里。
VINE_STATE_POOLS = ["cave_vine", "weeping_vine", "twisting_vine"]

# 可爬方块的候选组：枚举里真在 `data/minecraft/tags/block/climbable.json` 的只有
# `vine`（不是合法枚举组）/`cave_vine`/`weeping_vine`/`twisting_vine`，
# 而 ladder/scaffolding 没有对应组。所以可爬只能借这三个，合计 11 格 = 11 个可爬方块。
CLIMBABLE_POOLS = ["cave_vine", "weeping_vine", "twisting_vine"]

# 登记放行：**有碰撞**的组被无碰撞方块借用时，必须在这里写明原因，门禁才不判红。
#
# 现状（2026-10-28 返工）：CE 的 24 个枚举里，`collEmpty=true` 且 `canOcclude=false`
# 的组只剩 23 格（tripwire 9 / weeping 9 / kelp 1 / sugar_cane 1 / sapling 1 /
# cave 1 / twisting 1），而本包有 56 个植物方块。**每个方块至少要 1 个外观**，
# 23 < 56 —— 不借 `chorus`（有 10/16 细柱碰撞、但不遮挡）就必然有一批方块完全没有外观。
#
# 两个坏结果里选一个，这里选「有外观、但玩家会被 10/16 细柱挡住」：
#   * 不借 → 方块存在却看不见（2026-10-28 之前就是这样丢了 37 个方块的外观）；
#   * 借   → 看得见，代价是细柱碰撞。
# 逐块的「借了谁、丢了什么」仍写在 `reports/plant-collision.json`，不是静默通过。
COLLISION_POOL_WAIVERS: dict[str, str] = {
    "chorus": "**无碰撞池装不下的尾巴**：合法无碰撞组只有 tripwire(9)+pressure_plate(27)"
              "+cave_vine(1)+twisting_vine(1)，而可爬方块独占 cave/weeping/twisting 三组，"
              "非可爬的 collision=empty 方块还剩 46 个、只能拿到 36 格 —— 差 10 个。"
              "这 10 个只能借 chorus（10/16 全高柱、不遮挡）。按「贴地越矮越该无碰撞」"
              "排序，让最矮最密的那批优先拿无碰撞格，落在这里的是相对较高的单体。",
}

# 门禁用的**禁止集**：植物与装饰绝不能落到这些组。
#   * `solid`/`note_block`/`mushroom`/`mushroom_stem` —— 满格碰撞；
#   * `cactus` —— 16 个状态全部 `canOcclude=true`，借它等于挖掉邻面（透视）；
#   * 树叶族 —— 满格碰撞 + 遮挡剔除。
FORBIDDEN_PLANT_POOLS = (
    "solid", "note_block", "brown_mushroom", "red_mushroom", "mushroom",
    "mushroom_stem", "cactus", "non_tintable_leaves", "tintable_leaves", "leaves",
)

# 可爬藤蔓：**必须借一个本身就属于 `#minecraft:climbable` 的原版视觉状态**。
#
# ## 玩家的「爬」发生在客户端，判定的是客户端手里那个方块状态
#
# 判定点是 `LivingEntity#onClimbable()` → `getInBlockState().is(BlockTags.CLIMBABLE)`
# （本机 `mucore-26.3` 服务端字节码）。玩家能不能往上走由**客户端**先算出来：客户端跑同一段
# `LivingEntity#travel`，看的是它自己世界里的方块状态 —— 而 CE 给原版客户端下发的正是
# **视觉载体状态**（`cache/visual_block_states.json` 里全是 `note_block`/`sugar_cane`/…）。
#
# 所以载体就是客户端眼里的方块：
#   * 借 `sugar_cane`（不在 climbable 里）→ 客户端认为自己贴着一根甘蔗，**不会**产生攀爬移动；
#   * 借 `cave_vines` / `weeping_vines` / `twisting_vines`（都在 climbable 里）→ 客户端会爬。
#
# 运行期 tag 实测（`mucore-26.3` 服务端 jar 的 `data/minecraft/tags/block/climbable.json`）：
# ladder / vine / scaffolding / weeping_vines(+_plant) / twisting_vines(+_plant) /
# cave_vines(+_plant)，**没有** sugar_cane。
#
# ## `settings.tags` 仍然要写，但它不能代替载体
#
# `settings.tags` 挂在**自定义方块自己的** holder 上（CE `BlockSettings.tags()` →
# `HolderProxy$ReferenceProxy.setTags`），对它所有状态生效，服务端逻辑与装了 CE 客户端 mod
# 的玩家读得到；原版客户端读到的载体 tag 不会因此改变。因此它是**补充**，不是替代。
VINE_CLIMBABLE_TAG = "minecraft:climbable"

# 可爬方块：**每个外观都借一个本身就属于 `#minecraft:climbable` 的原版视觉状态**。
# 不再有「共享宿主」：改成真实状态后每个外观各占一个槽位，
# 所以这里只需保证「可爬方块的池族 = {@link CLIMBABLE_POOLS}」。
#
# 判据不变：客户端跑 `LivingEntity#travel` 时看的是它自己世界里的方块状态，
# 而 CE 下发给原版客户端的正是**视觉载体状态**（`cache/visual_block_states.json`）。
# 借 `sugar_cane` = 客户端认为贴着一根甘蔗 = 爬不上去；借三种藤蔓 = 会爬。
# `settings.tags: minecraft:climbable` 仍要写（服务端逻辑与装了 CE 客户端 mod 的玩家读得到），
# 但它挂在自定义方块自己的 holder 上，**替不了**载体。
VINE_CLIMBABLE_TAG = "minecraft:climbable"


# ---------------------------------------------------------------- 池容量表
#
# ## 这张表是「本包还剩多少空位」，不是 CE 池的总容量
#
# CE 的 AutoStateGroup 是**跨包共享**的：`plugins/CraftEngine/cache/visual_block_states.json`
# 记录谁占了哪个原版状态。把池的总容量当成本包额度，会算出一个看似充裕、实际一上线就撞
# 「视觉状态组 X 已达到 N 个槽位的最大容量」的数字。
#
# 每一项都是 `mappings 启用条目数 − cache 里已被别的包占用数`（2026-10-28 对账）：
#   chorus 63（无人占用）／tripwire 9（betterend 87 + farmersdelight 30 占 117/126）／
#   weeping_vine 9／cave_vine 1（betterend 占满 49/50）／twisting_vine 1／kelp 1／
#   sugar_cane 1／sapling 1。
# 合计 **86 格**，而 56 个方块有 168 个 appearance —— 装不下，这是硬约束，
# 由 {@link merge_plant_appearances} 在同一方块内部合并解决，不做任何渲染降级。
#
# **本表只记 `auto_state` 池。** 写死状态载体（{@link PINNED_CARRIERS}）认领的是原版状态
# 本身，既不从本表扣额度，也不该被本表约束 —— 把它加进来会让「池够不够」这个判断失真，
# 所以两者永远分开记账（`PoolAllocator.used` 与 `PinnedAllocator.used`）。
#
# 只列合法枚举组（见 {@link AUTO_STATE_GROUPS}）：不存在 `sculk_vein`/`resin_clump`/
# `vine`/`chorus_plant` 这些名字，写进来只会让人以为可以借。
POOL_BUDGET = {
    # 余量（不是总容量）。tripwire 三子池（tripwire/lower/higher）共用同一批状态，
    # 额度按合并后的一份额度算，见 `PoolAllocator._SHARED`。
    #
    # 2026-10-29 用测试端 live 配置重采：**原版状态池由 BetterNether 与 BetterEnd 共用**
    # （同一批 tripwire/sapling/kelp 状态）。BE 实测占用（含 higher_tripwire+lower_tripwire
    # 都算 tripwire 家族）：tripwire 家族 83、sapling 7、kelp 18、sugar_cane 7。
    # 所以 BN 只能用「总容量 − BE 占用」：
    #   sapling cap8 − BE7 = 1；tripwire cap126 − BE83 = 43（取 39 留余量）；
    #   kelp cap25 − BE18 = 7；sugar_cane cap15 − BE7 = 8；pressure_plate cap28（BE0）；chorus cap63。
    # 关键：sapling 必须压到 1，否则 BN8+BE7=15 超出 8 格的 sapling，CE 报「sapling 达到 8 最大容量」。
    # 被挤出的植物会顺延到 kelp/sugar_cane/pressure_plate/chorus（这些都有富余）。
    # 2026-10-29 回到实测最优预算（叠加 weeping_vine 兜底后尾部可装下）：
    #
    # 2026-10-30 与 `ce_pool_ledger._POOL_MEASUREMENTS` 对齐：账本那边按测试端 cache 重新
    # 校准过（kelp/sapling/sugar_cane/weeping_vine 实测余量都是 0，tripwire 是 39），
    # 这里却还留着校准前的旧值，两边漂移。超卖的那部分 CE 会拒绝分配，相关方块直接没有外观，
    # 所以门禁把「额度 > 账本余量」判红是对的 —— 额度必须 ≤ 真实余量。
    "chorus": 63,
    "tripwire": 39,
    "weeping_vine": 0,
    "twisting_vine": 20,
    "pressure_plate": 27,
    "cave_vine": 1,
    "kelp": 0,
    "sugar_cane": 0,
    "sapling": 0,
    # 满格透明方块（玻璃）。不染色树叶池本包实测分到 35 个（樱花/苍白橡树/盛开杜鹃）；
    # 可染色树叶池（橡树/深色橡树/丛林/金合欢/红树）在测试端没有任何包占用，
    # 先按 5 种 × 保守 10 个状态给额度。
    "non_tintable_leaves": 35,
    "tintable_leaves": 50,
}
CUTOUT_POOLS = ["non_tintable_leaves", "tintable_leaves"]


#: 需要**按几何段位保留多个外观**的方块 → 最少外观数。
#:
#: 为什么要单独一张表，而不是把 `shape` 统一并入 `floor`：`floor` 是全局的，
#: 一旦把段位数算进去，所有多段植物都会向可爬藤蔓池多要槽位，而该池在线上已用尽
#: （cave_vine/weeping_vine/twisting_vine 余量均为 0），必然 SystemExit。
#: 所以只给「走 pressure_plate 等有余量的池、且各段确实用不同模型」的方块单独开口子。
#:
#: `gloomwisp_vine` 依据上游 `blockstates/gloomwisp_vine.json`：
#:   shape=bottom          → gloomwisp_vine_t4
#:   shape=middle          → gloomwisp_vine_t3
#:   shape=top,spent=false → 加权随机 [gloomwisp_vine_head, _t0, _t1, _t2]
#:   shape=top,spent=true  → gloomwisp_vine_t5
#: 四段模型互不相同，合并成 1 个就等于顶端的「小鬼头」永远不出现
#: （用户 2026-10-31 报的「幽光藤头部显示不正确」）。
STRUCTURAL_APPEARANCE_FLOOR: dict[str, int] = {
    "gloomwisp_vine": 4,
}


def pool_budget() -> dict[str, int]:
    """每个池留给本包的槽位数（`POOL_BUDGET` 的副本，调用方可安全扣减）。"""
    return dict(POOL_BUDGET)


# ---------------------------------------------------------------- 植物真值表 ②
#
# `collision=empty` 的方块（真值来自 `upstream/.../generated/block_shapes.txt`，
# 由 `block_props.load_shape_tiers` 归到 `TIER_NONE`）。顺序无关，只是便于核对。
COLLISION_EMPTY_BLOCKS = frozenset({
    "agave", "anchor_tree_vine", "black_apple", "black_bush", "black_vine",
    "blooming_vine", "bone_grass", "egg_plant", "eye_vine", "eyeball",
    "eyeball_small", "feather_fern", "gloomgrass", "gloomsculk_crystal",
    "gloomsculk_vine", "gloomwisp_vine", "golden_lumabus_vine", "golden_vine",
    "gray_mold", "hook_mushroom", "ink_bush", "jungle_moss", "jungle_plant",
    "lumabus_vine", "magma_flower", "moss_cover", "nether_grass",
    "nether_reed_stem", "orange_mushroom", "pale_gloomgrass", "red_mold",
    "sepia_bone_grass", "soul_grass", "soul_lily_sapling", "soul_vein",
    "stalagnate_seed", "swamp_grass", "wall_moss", "wall_mushroom_brown",
    "wall_mushroom_red", "wart_seed", "whispering_gourd_vine",
})

# `collision=partial` 的方块：上游有碰撞箱但**不是满格**，CE 的池表达不了同样的几何。
#
# 每行三个字段：(池族, 上游碰撞箱摘要, 保真说明)。
#   * 池族 `PARTIAL_COLLISION_POOLS`：借 `chorus_plant`（AABB 10/16 宽全高柱、不遮挡），
#     形状最接近，容量 63 ≥ 本表需求 32，用尽再顺延无碰撞池并记录缺口。
#   * 池族 `NO_COLLISION_POOLS`：上游这半格碰撞**放弃**，按无碰撞落地（与改造前一致，
#     不会在有碰撞的位置凭空立起隐形墙）。这项降级逐条写进 `reports/plant-collision.json`。
#
# 排序依据是「上游碰撞箱有多接近 chorus_plant 的 10/16 × 全高细柱」：
# 全高柱排前面；半格高/整面宽/小碗状的排后面。
# `cactus` 曾经被用在这里，但实测 16 个状态全部 `canOcclude=true`（借它＝挖掉邻面＝透视），
# 已从所有载体池里删除，并进了 {@link FORBIDDEN_PLANT_POOLS}。
PARTIAL_COLLISION_FITS: dict[str, tuple[str, str, str]] = {
    # 仙人掌族：上游 age2/3 是 15/16 细柱，chorus 的 10/16 比它**更细**——
    # 方向写清楚：走位时会比以前更贴边穿过去，但不再是「看不见的整块」。
    "barrel_cactus": ("thin", "age0/1 empty；age2/3 0.0625..0.8125 细柱（15/16 宽）",
                      "chorus 10/16 比上游细 5/16"),
    "nether_cactus": ("thin", "0.0625..0.9375 × 0..1 / 0.25..0.75 × 0..0.5",
                      "chorus 10/16 比上游 15/16 细 5/16"),
    # 12/16 全高柱 —— 与 chorus 的 10/16 最接近的一档。
    "giant_mold": ("thin", "0.125..0.875 全高 / 0.3125..0.6875 全高",
                   "chorus 10/16 与 12/16 差 2/16"),
    # 6/16 的轴向细柱（三轴），chorus 只有竖直一种轴向：chorus 比它粗、且表达不了横放。
    "stalagnate_stem": ("thin", "axis 三态 6/16 细柱",
                        "chorus 无 axis：横放时碰撞箱仍竖直，且比上游粗 4/16"),
    "nether_mushroom_stem": ("thin", "axis 三态 6/16 细柱",
                             "chorus 无 axis：横放时碰撞箱仍竖直，且比上游粗 4/16"),
    # 8/16 宽的全高柱（willow_trunk 还有 0.75 高的顶段）。
    "willow_trunk": ("thin", "0.25..0.75 × 0..1 / 0..0.75",
                     "chorus 10/16 比上游粗 2/16"),
    "willow_branch": ("thin", "0.25..0.75 × 0..1", "chorus 10/16 比上游粗 2/16"),
    # 柱 + 枝头的混合：柱身能近似，枝头/菌盖做不到，如实写。
    "mushroom_fir_trunk": ("thin", "0.25..0.75 柱 + 半格枝头 / 满格菌盖",
                           "只近似柱身；枝头与菌盖无对应载体"),
    # 可爬 + partial：可爬优先（客户端认载体），代价是碰撞箱变空。
    "stalagnate_trunk": ("climb", "0.0625..0.9375 全高柱",
                         "上游在 climbable tag 里：必须借可爬池，碰撞箱随之变空"),
    # 水母蘑菇：上游 `renderLayer=TRANSLUCENT`，而 `chorus_plant` 是 SOLID 渲染 ——
    # 半透明贴图按 SOLID 渲染会变成实心色块。视觉错误比碰撞箱差异更容易被看见，
    # 所以它必须留在 noclip 组。
    "jellyfish_mushroom": ("noclip", "0.0625..0.9375 × 0..1（14/16 全高柱）",
                           "上游 TRANSLUCENT 渲染，chorus 是 SOLID：半透明会变实心色块"),
    # 上游是「整面宽的上半格」或小碗：chorus 比它高得多 → 放弃碰撞更诚实。
    # 整面宽但只有半格的菌盖：chorus 柱是 10/16 宽、**全高**，放进去会在菌盖所在的位置
    # 立起一根穿到方块顶的柱子 —— 那比「上半格没有碰撞」更容易被玩家看见，所以放弃碰撞。
    "brown_large_mushroom": ("noclip", "0.5..1 高的整面宽菌盖",
                             "chorus 是全高柱，表达不了「只有上半格」：放弃半格碰撞"),
    "red_large_mushroom": ("noclip", "0.5..1 高的整面宽菌盖",
                           "chorus 是全高柱，表达不了「只有上半格」：放弃半格碰撞"),
    # 锥体上下宽窄不同，chorus 是等宽柱；小碗只有 3/16 高，chorus 会一路穿到顶。
    "rubeus_cone": ("noclip", "0.1875..0.8125 × 0.1875..1 锥体",
                    "锥体是变宽度的，chorus 等宽柱套不上：放弃半格碰撞"),
    "stalagnate_bowl": ("noclip", "0.3125..0.6875 × 0..0.1875 小碗",
                        "碗口只有 3/16 高，chorus 柱会穿到方块顶：放弃 3/16 碰撞"),
}

# --- 容器：写死状态载体（`state:`），不再是 auto_state 池 -------------------------
#
# 用户 2026-10-29 明确要求：**箱子这类「可触发的非完整方块」也要用写死原版状态
# （pinned state）**，不许留在 `chorus` 池里。上一轮把这些方块签进 `chorus` 的方向
# 是反的：容器要的是「一整块挡住走位」的碰撞箱，而 `chorus` 的 10/16 柱恰好让箱体
# 四周 2.5/16 的边沿可以穿过去 —— 玩家能半个身子插进箱子里，比碰撞箱大 2/16 难看得多。
# 所以它们从 {@link PARTIAL_COLLISION_FITS} 里**整批移出**，改由本表点名载体，两条路
# 不并存（同一块方块只会走这里）。
#
# 载体选择（逐族实测见 {@link PINNED_CARRIERS}）：
#   * `chest` / `chest_of_drawers` → `note_block` 族：满格 1×1×1，正是容器要的
#     「整块碰撞」几何。空闲状态是**逐条算出来的**（见 `NOTE_BLOCK_OCCUPIED`），
#     不是「余 672」那种计数盲写；
#   * `nether_brewing_stand` → `trapdoor` 族：上游只有 1/8 高的底座，满格载体要多出
#     14/16 高度，而活板门下半（`half=bottom`）的碰撞箱是 `0..3/16 × 整面宽`，
#     与底座的差值只有 1/16（高度）与 1/16（每侧宽度）—— 几何上明显更诚实。
#
# 代价逐块写在下面（`note` 字段），报告 reports/plant-collision.json 会原样带出。
# 差值 >1/16 的三处必须能一眼看出，所以按「载体几何 / 上游碰撞箱 / 差值」三段写：
PINNED_CARRIER_TARGETS: dict[str, dict] = {}


def _container_target(name: str, carrier: str, box: str, note: str) -> None:
    """登记一个「容器走写死状态载体」的方块（唯一真值，生成器/报告都读这里）。"""
    PINNED_CARRIER_TARGETS[name] = {"carrier": carrier, "box": box, "note": note}


# 14 个 ChestBlock（ChestBlock.java 注册的木材族；本包实际发布 8 个，其余因
# 「世界生成与结构都不放置」不发布，但真值表照样登记，理由同 PARTIAL_COLLISION_FITS）。
# 上游碰撞箱 `0.0625..0.9375 × 0..0.875 × 0.0625..0.9375`（14/16 宽 × 14/16 高；
# 双箱的 left/right 半边会朝并箱方向延伸到 1）。
for _name in (
    "anchor_tree_chest", "crimson_chest", "gloomwood_chest", "gloomwood_dark_chest",
    "mushroom_fir_chest", "mushroom_fir_trimmed_chest", "nether_mushroom_chest",
    "nether_reed_chest", "nether_sakura_chest", "rubeus_chest", "stalagnate_chest",
    "warped_chest", "wart_chest", "willow_chest",
):
    _container_target(
        _name, "note_block",
        "0.0625..0.9375 × 0..0.875 × 0.0625..0.9375（14/16 宽 × 14/16 高）",
        "note_block 满格 1×1×1：四周宽 1/16、**顶部高 2/16**（双箱那半边沿最多 1/16）；"
        "2/16 超过「1/16 以内」的口径，取它是产品要求 —— 非满格载体都会让箱沿能穿过去",
    )
# 抽屉柜：面向 4 态，箱体**半格深、整格高**（facing=north 是 0..0.5，其余三次旋转）。
# CE 的载体里没有「半格深 + 整格高」的原版方块（活板门是「整面宽 × 3/16 高」，
# 门是「3/16 厚 × 整面宽 × 全高」），所以这里只能取满格。
_container_target(
    "chest_of_drawers", "note_block",
    "半格深 × 整格高（facing=north/south 是 0..0.5 / 0.5..1 深，east/west 换轴）",
    "note_block 满格 1×1×1：**深度多出 8/16**（整格 vs 半格）；"
    "原版没有任何「半格深 + 整格高」的载体，差值最大的一块，必须点名",
)
# 酿造台：底座 `0.0625..0.9375 × 0..0.125 × 0.0625..0.9375`（1/8 高）+ 中央细杆。
_container_target(
    "nether_brewing_stand", "trapdoor",
    "0.0625..0.9375 × 0..0.125 × 0.0625..0.9375（底座 1/8 高 × 14/16 宽）",
    "trapdoor 下半 `0..3/16 × 整面宽`：高度差 1/16、每侧宽度差 1/16，"
    "落在「1/16 以内」的口径里（中间那根细杆上游本来也没有碰撞）",
)


def plant_collision_class(tier: str) -> str:
    """形状三档 → 植物的碰撞类别（`empty` / `partial` / `unknown`）。"""
    if tier == block_props.TIER_NONE:
        return "empty"
    if tier == block_props.TIER_PARTIAL:
        return "partial"
    return "unknown"


def plant_slot_plan(name: str, tier: str, climbable: bool) -> tuple[tuple[str, ...], str, str]:
    """**单一真值表**：这个方块的 appearance 该从哪些组里取槽位。

    返回 `(组序列, 碰撞类别, 保真说明)`，生成器、报告与门禁都只从这里取，
    不允许别处再写一份组名单。**组名必须是 {@link AUTO_STATE_GROUPS} 里的合法值**，
    写错一个字的后果是方块完全没有外观（CE 加载时报「无效的常量」）。

    规则（按优先级，命中即停）：
      1. **可爬** → 只能借 `CLIMBABLE_POOLS`（三个可爬藤蔓组，合计 11 格 = 11 个可爬方块）；
      2. `collision=empty` → `NOCLIP_POOLS`（无碰撞、不遮挡，且不含可爬组的额度）；
      3. `collision=partial` → 见 {@link PARTIAL_COLLISION_FITS}：
         细柱类的先试 `chorus`（10/16 全高柱、不遮挡），用尽后顺延 `NOCLIP_POOLS`；
      4. 形状表里查不到（`tier` 为空/满格）→ 按无碰撞组处理，并把 `unknown` 写进报告，
         让「这个方块凭什么算植物」暴露出来，而不是默默借满格组。
    """
    collision = plant_collision_class(tier)
    target = PINNED_CARRIER_TARGETS.get(name)
    if target is not None and not climbable:
        # 容器：载体由 {@link PINNED_CARRIER_TARGETS} 点名（写死状态，不占池），
        # 池族返回空元组 —— 调用方看到空族就走 `PinnedAllocator.take_carrier`，
        # 而不是从任何 auto_state 池里取一个近似状态。
        return ((), collision,
                f"上游 {target['box']}；写死状态载体 {target['carrier']}：{target['note']}")
    if climbable:
        return (tuple(CLIMBABLE_POOLS), collision,
                "可爬：借可爬藤蔓组（原版 climbable），碰撞箱随载体")
    if collision == "empty":
        # 空碰撞植物先借无碰撞池，用尽顺延 chorus（10/16 柱、不遮挡）。
        return (tuple(NOCLIP_POOLS) + tuple(PARTIAL_COLLISION_POOLS), collision, "")
    if collision == "partial":
        family, box, note = PARTIAL_COLLISION_FITS.get(
            name, ("noclip", "形状表里没有这个方块的 partial 记录", ""))
        if family == "climb":
            # 表里标了 climb 的方块运行时一定会走到上面的 climbable 分支；
            # 这里保留同样的答案，好让「不传 climbable 的查询」（验收/测试）也拿到真值。
            return (tuple(CLIMBABLE_POOLS), collision, f"上游 {box}；{note}")
        if family == "thin":
            # 首选 chorus（10/16 全高柱、不遮挡），用尽后顺延无碰撞组。
            family_pools = tuple(PARTIAL_COLLISION_POOLS) + tuple(
                pool for pool in NOCLIP_POOLS if pool not in PARTIAL_COLLISION_POOLS)
            return (family_pools, collision, f"上游 {box}" + (f"；{note}" if note else ""))
        # 上游是「半格/整面宽菌盖」这类形状，CE 没有对应载体：按无碰撞落地。
        # 额度不够时同样顺延 chorus（有碰撞但更接近「有实体」），并记进报告。
        return (tuple(NOCLIP_POOLS) + tuple(PARTIAL_COLLISION_POOLS), collision,
                f"上游 {box}：CE 没有对应的部分碰撞载体，按无碰撞落地")
    # 形状表里查不到档位（tier 为空/未知）：按无碰撞落地，用尽后同样顺延 chorus，
    # 否则这批方块会卡在 NOCLIP_POOLS 上把额度吃干、报「缺口 N 个」。
    return (tuple(NOCLIP_POOLS) + tuple(PARTIAL_COLLISION_POOLS), collision,
            "形状表里查不到这个方块的碰撞档位，按无碰撞落地")


def merge_plant_appearances(spec: dict, keep: int) -> dict:
    """把 `spec` 的外观压缩到 `keep` 个，**被丢弃外观的 variants 键改指向保留的外观**。

    为什么必须合并而不是多占格：56 个方块有 168 个 appearance，而合法且无碰撞不遮挡的
    组只剩 86 格（见 {@link POOL_BUDGET}）。合并的语义是 vanilla 自己也有的
    「多个状态共用一个模型」，代价是丢掉部分内部状态的外观差异，但：

      * 每个方块**至少保住 1 个 appearance**（保底由分配器保证 `keep >= 1`）；
      * 每个 `variants` 键仍然指向某个 appearance —— 不会有「某个状态没外观」；
      * 被合并的气味逐条写进 `merged`，随 `reports/plant-collision.json` 落盘，
        门禁与需求方都能读到「哪个方块从几个合到几个」。

    「保留哪几个」= 文件顺序里的前 `keep` 个（第一个就是默认态/主变体）。
    「丢弃的指向谁」= 与它共享最多属性的保留外观（同 `shape` 不同朝向的自然并到一起）。
    """
    appearances = spec["appearances"]
    if keep <= 0 or len(appearances) <= keep:
        return spec

    # 每个外观的变体键（解析成 (属性, 取值) 字典，便于数「共享/差异了多少属性」）
    keys_of: dict[str, list[tuple[str, dict[str, str]]]] = {name: [] for name, _ in appearances}
    for key, app_name in spec["variants"]:
        values = {}
        for part in key.split(","):
            if "=" in part:
                prop, value = part.split("=", 1)
                values[prop.strip()] = value.strip()
        keys_of.setdefault(app_name, []).append((key, values))

    def difference(left: str, right: str) -> int:
        """两个外观的变体键差了几个属性（越大越不相似）。"""
        best = 0
        for _lkey, lvalues in keys_of.get(left, []):
            for _rkey, rvalues in keys_of.get(right, []):
                props = set(lvalues) | set(rvalues)
                best = max(best, sum(1 for prop in props
                                     if lvalues.get(prop) != rvalues.get(prop)))
        return best

    # 保留哪几个：先留默认态（文件第一个），再**优先按结构段位各留一个**（shape/top/bottom），
    # 最后才按「与已保留外观差异最大」补朝向副本。
    #
    # 为什么不只按差异最大挑：多段植物/柱体（gloomwisp_vine 的 top-spent=false 要用小鬼头模型、
    # lumabus_vine 的 top 要用 roots、middle 要用 vine、bottom 才是 bulb）在不同 shape 段位上
    # 用的是**完全不同的模型**。若只按“差异最大”挑，额度小时容易把不同段位挤掉、只剩底部段，
    # 表现为“整棵藤全长一个样、顶端没有头/果实”。这里先按结构签名去重保底，保证每个段位至少留一个。
    # `spent` 也进结构签名：对唯一使用它的 gloomwisp_vine，spent=false 与 true 是
    # **完全不同的模型**（false → 加权[head,t0,t1,t2]，true → t5），合并掉会让
    # 「已消耗」状态显示成发光的生长头。全包里只有这一个方块带 spent（实测），
    # 所以影响面就是它自己。
    _STRUCTURAL_PROPS = ("shape", "top", "bottom", "spent")

    def structural_signature(name: str) -> tuple:
        """外观对应的结构段位签名（只看 shape/top/bottom 这些决定几何的属性）。"""
        vals: dict[str, str] = {}
        for _key, values in keys_of.get(name, []):
            for prop in _STRUCTURAL_PROPS:
                if prop in values:
                    vals.setdefault(prop, values[prop])
        return tuple(sorted(vals.items()))

    order_of = {name: index for index, (name, _models) in enumerate(appearances)}
    chosen_names = [appearances[0][0]]
    seen_sigs = {structural_signature(chosen_names[0])}
    # 1) 先保证每个不同的结构段位签名各留一个（额度允许时）。
    while len(chosen_names) < keep:
        added = False
        for name, _models in appearances:
            if name in chosen_names:
                continue
            sig = structural_signature(name)
            if sig not in seen_sigs:
                chosen_names.append(name)
                seen_sigs.add(sig)
                added = True
                break
        if not added:
            break
    # 2) 剩余额度再按「与已保留外观差异最大」补朝向副本。
    while len(chosen_names) < keep:
        chosen_set = set(chosen_names)
        candidates = [(min(difference(name, picked) for picked in chosen_names),
                       -order_of[name], name)
                      for name, _models in appearances if name not in chosen_set]
        candidates.sort(reverse=True)
        chosen_names.append(candidates[0][2])
    kept = [(name, models) for name, models in appearances if name in set(chosen_names)]
    kept_names = chosen_names

    def pick_target(dropped: str) -> str:
        """挑一个「共享属性最多」的保留外观；并列时取先出现的（确定性）。

        结构段位优先：若被丢弃外观有同结构签名（shape/top/bottom 相同）的保留外观，
        **必须**改指向它——否则 `shape=top` 被重定向到 `shape=bottom` 的外观，
        顶端就会用底部段的模型（gloomwisp 没小鬼头、lumabus 顶端没 roots 的根因）。
        """
        dropped_sig = structural_signature(dropped)
        same_sig = [c for c in kept_names if structural_signature(c) == dropped_sig]
        pool_names = same_sig if same_sig else kept_names
        scores: list[tuple[int, int, str]] = []
        for order, candidate in enumerate(pool_names):
            best = 0
            for _key, values in keys_of.get(dropped, []):
                for _ckey, cvalues in keys_of.get(candidate, []):
                    shared = sum(1 for prop, value in values.items()
                                 if cvalues.get(prop) == value)
                    best = max(best, shared)
            scores.append((-best, order, candidate))
        scores.sort()
        return scores[0][2]

    # **不能**用 `appearances[keep:]` 切片：保留下来的外观是按「与已保留项差异最大」
    # 挑出来的（见上面的 chosen_names），不是前 keep 个。用切片的话，被选中的项如果
    # 落在切片范围内就会被写进 redirect，于是它自己的 variants 键被重定向到**别人**，
    # 指向一个已经被删掉的 appearance 名 —— 表现为「变体引用了不存在的外观」
    # （2026-10-31 门禁抓到的 gloomwisp_vine `shape=top,spent=true,vine_dir=east`）。
    merged_from = [name for name, _ in appearances if name not in set(chosen_names)]
    redirect = {name: pick_target(name) for name in merged_from}
    spec["variants"] = [(key, redirect.get(app, app)) for key, app in spec["variants"]]
    spec["appearances"] = kept
    spec["merged"] = {
        "from": len(appearances),
        "to": len(kept),
        "dropped": merged_from,
        "redirect": redirect,
    }
    return spec


class PoolAllocator:
    """按池族分配**真实视觉状态槽位**；整族都取不到时 fail-fast。

    ## 为什么不再有「透明共享状态」兜底

    `transparent: true` + `entity_renderer` 的代价是**每格一个展示实体**（用户实测太卡）。
    所以这里没有「拿到额度就真实状态、拿不到就展示实体」这种分叉：取不到槽位就是
    配置缺口，必须让构建失败并说出缺口数字 —— 静默降级回展示实体正是要根除的行为。

    `tripwire`/`lower_tripwire`/`higher_tripwire` 是同一批绊线状态，额度合并计算。
    """
    _SHARED = {"lower_tripwire": "tripwire", "higher_tripwire": "tripwire"}

    def __init__(self) -> None:
        self.left = pool_budget()
        self.cutout_overflow = 0
        # 每个池实际被取走的槽位数（写进 reports/plant-collision.json 的 pool_usage）。
        self.used: dict[str, int] = {}
        # 留给「结构性外观」（`axis` 三态）的预定额度，**按池族分开记账**：
        # 先到先得的方块不能把它吃光，否则放倒的原木/柱体会沿用竖着的模型。
        # 必须按族记：可爬族只有 11 格，拿非可爬族的预留去扣会直接把可爬方块挤成缺口。
        self.reserved: dict[tuple[str, ...], int] = {}

    def reserve(self, family: tuple[str, ...], amount: int) -> None:
        if amount > 0:
            self.reserved[family] = self.reserved.get(family, 0) + amount

    def release(self, family: tuple[str, ...], amount: int) -> None:
        if amount > 0:
            self.reserved[family] = max(0, self.reserved.get(family, 0) - amount)

    def free(self, family: tuple[str, ...]) -> int:
        """这一族还剩多少格（`tripwire` 三子池按合并后的那份额度算）。"""
        return sum(self.left.get(self._SHARED.get(pool, pool), 0) for pool in family)

    def quota(self, family: tuple[str, ...], wanted: int, pending: int,
              floor: int = 1, released: int = 0, pinned_free: int = 0) -> int:
        """这个方块能发布几个 appearance：**先保底 1 个**，富余的才补给第 2、3 个。

        为什么不是「能取多少取多少」：合法的无碰撞组只剩 86 格，却有 168 个 appearance。
        如果前面的方块把额度吃光，后面的方块会**一个外观都没有**（上一版就是这样丢了
        37 个方块的外观）。所以按「还剩多少方块没分到保底」预留：

            extras = free − pending      # pending 含当前方块
            配额   = 1 + max(0, extras)

        `free < pending` 说明连「每个方块 1 个」都保不住 → 直接 `SystemExit`，
        绝不静默丢方块（这时候要做的是扩容载具池，不是放宽判定）。

        `pinned_free` 是**写死状态载体**的余量（{@link PinnedAllocator.remaining}），
        只用来判断「保底够不够」，不参与 `extras`：写死状态认领的是真实原版状态，
        给第 2、3 个外观各钉一个只会平白多占原版状态，而「多一个外观」远不如
        「每个方块都有外观」重要。两者分账（见 {@link PinnedAllocator} 的说明）。
        """
        free = self.free(family) - self.reserved.get(family, 0) + released
        shortfall = pending - free
        if shortfall > 0 and pinned_free < shortfall:
            remaining = "、".join(
                f"{name}={self.left.get(self._SHARED.get(name, name), 0)}" for name in family)
            raise SystemExit(
                f"合法视觉状态组不够用：这 {pending} 个植物方块每个至少要 1 个外观，"
                f"但 [{', '.join(family)}] 只剩 {free} 格（缺口 {shortfall} 个），"
                f"写死状态载体也只剩 {max(0, pinned_free)} 个 —— 缺口仍是 "
                f"{shortfall - max(0, pinned_free)} 个。\n"
                f"  拒绝退回 entity_renderer（每格一个展示实体，用户已明确要求去掉），"
                f"也拒绝静默丢弃方块外观。\n"
                f"  各池余量：{remaining}；整体容量见 blocks.POOL_BUDGET"
                f"（这是本包余量，不是 CE 池总容量）；写死载体清单见 blocks.PINNED_CARRIERS。"
            )
        extras = max(0, free - pending)
        if not any(p in CLIMBABLE_POOLS for p in family):
            extras = 0
        # `floor` 是「结构性外观」的保底（目前只有 `axis` 三态：旋转的不只是朝向，
        # 模型的几何也跟着变，合并掉会让横放的原木/柱体显示成竖的）。
        # 保底不能吃掉别人那 1 格，所以再按 `free - (pending - 1)` 封顶。
        reserved_floor = max(1, min(floor, free - (pending - 1)))
        return max(1, min(wanted, max(1 + extras, reserved_floor)))

    def take_from(self, family: tuple[str, ...], block_id: str, why: str = "") -> str:
        """沿池族优先级取一个真实状态槽位；整族额度耗尽 → `SystemExit`。

        `family` 由 {@link plant_slot_plan} 给出，已经按「形状像不像」排好序。
        失败信息里带 id、族、每个池的剩余量，便于直接对上 mappings 补丁的缺口。
        """
        for candidate in family:
            key = self._SHARED.get(candidate, candidate)
            if self.left.get(key, 0) > 0:
                self.left[key] -= 1
                self.used[key] = self.used.get(key, 0) + 1
                return candidate
        remaining = "、".join(f"{name}={self.left.get(name, 0)}" for name in family)
        raise SystemExit(
            f"{block_id}: 需要 1 个真实视觉状态槽位，但池族 [{', '.join(family)}] "
            f"全部用尽（剩余 {remaining}）—— 缺口 1 个。\n"
            f"  拒绝退回 entity_renderer（那是每格一个展示实体，用户已明确要求去掉）。\n"
            f"  blocks.POOL_BUDGET 记的是**本包剩余空位**（mappings 启用条目 − 其他 CE 包"
            f"已占用），不是 CE 池的总容量；要扩额请先重跑"
            f" `scripts/ce_patch/mappings_plant_pools.py` 生成 mappings 补丁，"
            f"再把结果对齐 POOL_BUDGET{('；本次调用原因：' + why) if why else ''}"
        )

    def take_cutout(self) -> str:
        """满格透明方块（玻璃/树叶）的槽位：树叶池，取不到就如实记账。"""
        for candidate in CUTOUT_POOLS:
            if self.left.get(candidate, 0) > 0:
                self.left[candidate] -= 1
                self.used[candidate] = self.used.get(candidate, 0) + 1
                return candidate
        # 两个树叶池都满：仍写不染色树叶池，让 CE 日志如实报出溢出，而不是静默丢碰撞。
        self.cutout_overflow += 1
        return STATE_POOL_CUTOUT


# ---------------------------------------------------------------- 写死状态载体（pinned state）
#
# ## 为什么必须有这条路：`auto_state` 池是真的会用完的
#
# 2026-10-28 实测的失败现场（`scripts/extract_bn.py` 非零退出）：
#
#     合法视觉状态组不够用：这 23 个植物方块每个至少要 1 个外观，
#     但 [chorus, tripwire, pressure_plate, kelp, sugar_cane, sapling] 只剩 19 格（缺口 4 个）
#
# `tripwire`/`pressure_plate`/`kelp`/`sugar_cane`/`sapling` 已被别的包或本包历史吃干
# （`POOL_BUDGET` 里记 0 的那三个就是 CE 直接报「组 X 已达到 N 个槽位的最大容量」的池），
# 只剩 `chorus` 一个池在扛，而 `chorus` 要先被「结构性外观（axis 三态）」预留 4 格。
# 再往 mappings.yml 里扩池的路已经走不通（枚举只有 24 个组，能扩的都扩了），
# 所以必须换一种**不占 auto_state 额度**的载体。
#
# ## 机制：CE 的 `state:` 是「写死原版状态」，一个槽位都不从池里拿
#
# CE 对一个外观认两种宿主写法：
#
#     auto_state: note_block          # 从 AutoStateGroup 池里分配一个原版状态
#     state: minecraft:oak_fence_gate[powered=true]   # 直接认领这个原版状态
#
# 后者走 `AbstractBlockManager#assignFixedBlockState`：把它**直接写进**
# `blockStateOverrides[blockId][stateNBT]`，既不用 CE 分配，也不进
# `cache/visual_block_states.json` 的池账 —— 因此**不消耗任何 auto_state 额度**。
# 同一招在「更好的末地」的 `B3-openable.yml` 里已经跑通（门/活板门/栅栏门/压力板全部
# 用 `state:` 钉原版状态），本段是它在 BetterNether 这边的对应实现。
#
# ## 三个判定标准（缺一不可）
#
# 1. **不与别的 CE 包冲突**：载体状态必须在本包实测的空闲池里（任务给的实测口径：
#    门 12 种各 0/32、活板门 0/32、铜栏杆 0/32、栅栏门 10 种各 0/16、
#    避雷针系各 0/24、铜锁链系各 0/6、铜灯笼系各 0/4、note_block 628/1300）。
#    `state:` 是**独占认领**：两个包钉同一个状态，后加载的把那格的模型覆盖掉，
#    两边方块会显示成同一个模型 —— 比池耗尽更难查。
#
# 2. **不与原版世界冲突**：`state:` 写死的模型会覆盖**那个原版状态**的渲染
#    （`blockStateOverrides` 是按状态写死的，不走 mappings 重映射）。也就是说，
#    世界上任何一个真的处于该状态的原版方块，都会显示成我们的模型。
#    所以优先取**红石瞬态位**：`powered=true` 只在充能的那一瞬间存在
#    （避雷针被雷劈中的 1.5 秒），世界上不会**持久**存在这个状态，
#    覆盖率 = 「原版世界 0 处 + 每次雷击闪 1.5 秒」，这是这一招唯一干净的落点。
#    更好的末地的栅栏门就是这么挑的（`powered=true` 是「可被回收的那一半」）。
#
# 3. **几何合适**：CE 不给自定义方块声明碰撞箱，**碰撞箱就是载体状态的碰撞箱**。
#    这个事实必须逐块如实记录（`reports/plant-collision.json` 的
#    `pinned_states` / `fidelity`），不能因为「有外观了」就当成无代价。
#    另外两条硬约束来自既有的池判据（见 {@link FORBIDDEN_PLANT_POOLS}）：
#      * `canOcclude=true` 的载体等于挖掉邻面（透视洞），只能用 `false` 的；
#      * `lightEmission>0` 的载体会让借来的植物自己照亮地形，只能是 0。
#
# ## 逐族实测（2026-10-28，服务端 jar 逐状态取证，与 collstates.tsv 同一套探针）
#
#   lightning_rod[powered=true]        collEmpty=false canOcclude=false light=0
#       竖直（facing=up/down）：AABB[0.375,0,0.375] -> [0.625,1,0.625]
#       → 4/16 宽、**全高**、单块细柱。这是所有空闲载体里最接近「一丛草/一根细茎」的形状，
#         而且比现在兜底的 `chorus`（10/16 宽全高柱）细 6/16 —— 用来填无碰撞方块的缺口，
#         碰撞感严格更好，不是等价的将就。
#   copper_chain[axis=y]               collEmpty=false canOcclude=false light=0
#       竖直：AABB[0.40625,0,0.40625] -> [0.59375,1,0.59375]
#       → 3/16 宽全高柱，比避雷针还细 1/16；但**没有瞬态位可挑**
#         （chain 只有 axis/waterlogged），只能钉 `waterlogged=false` 的常驻状态，
#         因此排第二顺位，并把「玩家在世界里竖着放的铜锁链会显示成本包的植物」写进代价。
#   note_block                         满格 + canOcclude=true → **只给容器用**（见下）
#
# ## 明确排除的候选（都是「池空着也不能用」）
#
#   * 门 `*_door`：碰撞箱 `AABB[0,0,0]->[0.1875,1,1]`（3/16 厚、整面宽、全高的竖板）
#     —— 一丛草放进去等于一堵墙，比 chorus 差得多；
#   * 活板门 `*_trapdoor`：`0..3/16` 高、整面宽 —— 会变成一块踩得住的隐形地板；
#   * 铜栏杆 `*copper_bars`：`2/16` 厚的十字板，且**沿方块边缘**分布 —— 挡住走位又剔不透；
#   * 栅栏门 `*_fence_gate`：`open=true` 虽然 `collEmpty=true`，但 `canOcclude=true`
#     （实测），借它＝挖掉邻面＝透视；`open=false` 是 1.5 高的板；
#   * 铜灯笼 `*copper_lantern` / 灯笼：`lightEmission=15`，借来的植物会自己发光；
#   * 铁锁链 `minecraft:chain`：本体空闲，但**废弃矿井/地牢会自然生成**竖着的锁链，
#     钉 `axis=y` 等于把全世界矿井里的锁链都换成植物 —— 与「不与原版世界冲突」正面违反。
#     只取铜的 8 个氧化/打蜡变体（试炼密室之外基本不会自然出现）。
#
# ## 排除理由的例外：活板门给**容器**用（不给植物）
#
# 上面把活板门写进排除清单，判据是「植物借它 = 一块踩得住的隐形地板」。容器反过来：
# 酿造台的上游碰撞箱只有 `1/8` 高，活板门下半（`half=bottom`）的 `0..3/16 × 整面宽`
# 才是所有空闲载体里最接近它的一块，差值落在 1/16 以内。所以 `trapdoor` 族的
# `scope` 是 `container`（只由 {@link PINNED_CARRIER_TARGETS} 点名，不进植物队列）。
#
# ## note_block 的空闲状态清单（2026-10-29 逐条实测算出，不是计数盲写）
#
# 数据来源：Folia 测试端 `plugins/CraftEngine/cache/visual_block_states.json`
# （1120 条，CE 实际分配快照）。其中 `minecraft:note_block` 占 628 条，全部形如
# `minecraft:note_block[instrument=<乐器>,note=<0-24>,powered=<true|false>]`。
# 音符盒的状态空间 = 乐器 × 音符(25) × powered(2)，乐器取 cache 里**实证出现过的
# 名字**（19 个，避免拿一个本版本不存在的枚举值去钉状态 —— 那会让方块完全没外观）：
# 628 / (25×2) 说明这些状态横跨 19 个乐器位，本包只挑其中 4 个「必须把对应头颅
# 戴在音符盒上才会出现」的乐器位，并且只取 `powered=true`（红石充能的瞬态位）。
#
# 下面这张表是**占用**明细（从 cache 抄下来的音符号），空闲 = `0..24` 减去它。
# 只记本包要用的 4 个乐器位，避免把 628 条原样贴进源码；`note_block_free_states()`
# 由它算出逐条状态，`scripts/tests/test_state_budget.py` 在能读到 cache 时逐条对账。
NOTE_BLOCK_OCCUPIED: dict[str, frozenset[int]] = {
    # 头颅在音符盒上才会出现的乐器位（1.19+）。占用音符号来自上面的 cache 快照。
    "skeleton": frozenset({0, 1}),
    "wither_skeleton": frozenset({0, 5, 6, 7, 8, 10}),
    "creeper": frozenset({19, 20, 21, 22, 23, 24}),
    "zombie": frozenset({4, 14, 16, 17, 18, 21, 23, 24}),
}


def note_block_free_states() -> tuple[str, ...]:
    """note_block 的**空闲**状态（逐条，顺序即取用顺序：乐器分组 → 音符 0..24）。

    只在 `powered=true` 上取：那是红石充能的瞬态位，原版世界里不持久存在该状态，
    写死的模型只会出现在本包自己的方块上（与避雷针 `powered=true` 同一个判据）。
    """
    return tuple(
        f"minecraft:note_block[instrument={instrument},note={note},powered=true]"
        for instrument in ("skeleton", "wither_skeleton", "creeper", "zombie")
        for note in range(25)
        if note not in NOTE_BLOCK_OCCUPIED[instrument]
    )


# 活板门：只取橡木一种（8 种木材会白占 8 个状态，而容器只需要 1 个），
# 且取 `powered=true`（同上：瞬态位）+ `half=bottom,open=false`（`0..3/16` 高的下半板）。
TRAPDOOR_STATES = (
    "minecraft:oak_trapdoor[facing=north,half=bottom,open=false,powered=true,waterlogged=false]",
)
#
# ## 2026-10-29 对另外四个候选的核实结果（与上面那份 1120 条 cache 快照同一口径）
#
#   铜栏杆 `*copper_bars`     0 占用（cache 里一条都没有）
#     → 碰撞箱是 2/16 厚的十字板、沿方块边缘分布：容器要的是「一整块」，不用；
#   栅栏门 `*_fence_gate`     0 占用
#     → `open=false` 是 1.5 高的板、`open=true` 是 canOcclude=true 的空碰撞状态
#       （见上条排除理由）：容器要整格碰撞，不用；
#   门 `*_door`               0 占用（32 个状态全空）
#     → `AABB[0,0,0]->[0.1875,1,1]`，3/16 厚、整面宽、全高：任何容器都不是这个形状，
#       抽屉柜要的是「半格深 × 整格高」（门只有 3/16 深，差 5/16，比满格的 8/16 好但仍是
#       一块薄板），做整块容器会让玩家穿门而入，所以宁可取满格的 note_block；
#   活板门 `*_trapdoor`       0 占用（32 个状态全空）→ **已接进来**，只给酿造台（见上）。
#
# 结论：只有活板门这一族在容器这一档是「几何明显更好」的，其余三个 0 占用也仍不适合。

LIGHTNING_ROD_BLOCKS = (
    "lightning_rod",
    "exposed_lightning_rod", "weathered_lightning_rod", "oxidized_lightning_rod",
    "waxed_lightning_rod", "waxed_exposed_lightning_rod",
    "waxed_weathered_lightning_rod", "waxed_oxidized_lightning_rod",
)
COPPER_CHAIN_BLOCKS = (
    "copper_chain",
    "exposed_copper_chain", "weathered_copper_chain", "oxidized_copper_chain",
    "waxed_copper_chain", "waxed_exposed_copper_chain",
    "waxed_weathered_copper_chain", "waxed_oxidized_copper_chain",
)

#: 载体族：**按优先级排列**，前面的先取。每族自带实测几何与取舍说明，
#: 报告与门禁都从这里读，不允许别处再抄一份状态清单。
#:
#: `states` 的顺序就是取用顺序（确定性来源）：同样的方块序列重复运行必然拿到同样的状态，
#: 构建是幂等的。
#:
#: `scope` 决定这一族**能进哪条取用队列**：`plant` 的族按 `take()` 的优先级顺序给植物/
#: 装饰兜底；`container` 的族**不参与植物兜底**（满格碰撞 / 半格高对植物全是坏几何），
#: 只能由 {@link PINNED_CARRIER_TARGETS} 逐块点名取用。两条队列分开，是为了让
#: 「补一个满格载体」不会顺手把植物的碰撞箱也换成满格。
PINNED_CARRIERS: tuple[dict, ...] = (
    {
        "name": "lightning_rod",
        "scope": "disabled",
        "blocks": LIGHTNING_ROD_BLOCKS,
        # 8 个避雷针族 × facing=up/down，两者碰撞箱相同（都是 4/16 宽全高柱），
        # 取两个朝向是为了同一几何下多一倍余量。
        "states": tuple(
            f"minecraft:{rod}[facing={facing},powered=true,waterlogged=false]"
            for rod in LIGHTNING_ROD_BLOCKS
            for facing in ("up", "down")
        ),
        "geometry": "4/16 宽 × 全高细柱（AABB[0.375,0,0.375]->[0.625,1,0.625]）",
        "cost": "碰撞箱随载体：上游无碰撞的方块会变成**可以撞到的 4/16 细柱**"
                "（比 chorus 的 10/16 细 6/16，但仍不是空碰撞）",
        "why": "powered=true 是红石瞬态位：原版世界里不持久存在该状态，"
               "写死的模型只会出现在本包自己的方块上",
    },
    {
        "name": "copper_chain",
        "scope": "disabled",
        "blocks": COPPER_CHAIN_BLOCKS,
        "states": tuple(
            f"minecraft:{chain}[axis=y,waterlogged=false]" for chain in COPPER_CHAIN_BLOCKS
        ),
        "geometry": "3/16 宽 × 全高细柱（AABB[0.40625,0,0.40625]->[0.59375,1,0.59375]）",
        "cost": "碰撞箱随载体：变**可以撞到的 3/16 细柱**；且没有瞬态位可挑，"
                "玩家在世界里竖直放置的铜锁链会显示成本包的植物",
        "why": "铜锁链的 8 个氧化/打蜡变体在本包实测池里全空（各 0/6），"
               "且试炼密室之外几乎不会自然出现",
    },
    {
        "name": "note_block",
        "scope": "container",
        # 载体方块只写一个：`states` 已经把乐器位/音符/瞬态位全写死了。
        "blocks": ("note_block",),
        "states": note_block_free_states(),
        "geometry": "满格 1×1×1（AABB[0,0,0]->[1,1,1]），canOcclude=true",
        "cost": "碰撞箱随载体：非满格容器（抽屉柜半格深、酿造台 1/8 高）会变成**整格碰撞**；"
                "且这 4 个乐器位是「头颅戴在音符盒上」才出现的，"
                "世界上真摆成 `powered=true` 的音符盒会显示成本包的容器",
        "why": "空闲状态是逐条从 CE cache 算出来的（`NOTE_BLOCK_OCCUPIED` 求差，"
               "78 个可选、本包只用 13 个）；取 `powered=true` + 头颅乐器位，"
               "原版世界里几乎不会持久出现这些状态",
    },
    {
        "name": "trapdoor",
        "scope": "container",
        "blocks": ("oak_trapdoor",),
        "states": TRAPDOOR_STATES,
        "geometry": "3/16 高 × 整面宽（AABB[0,0,0]->[1,0.1875,1]），canOcclude=false",
        "cost": "碰撞箱随载体：只给酿造台这种「1/8 高底座」用（差值 1/16）；"
                "世界上被红石充能（`powered=true`）的橡木下半活板门会显示成本包的酿造台",
        "why": "活板门 32 个状态在 cache 里 0 占用；取下半 + `powered=true`，"
               "与上游 1/8 高底座的几何最接近的一档（比满格载体的 14/16 高度差好得多）",
    },
)


def pinned_carrier(name: str) -> dict:
    """按族名取 {@link PINNED_CARRIERS} 的条目；没有就 fail-fast。

    族名只写一遍（`PINNED_CARRIER_TARGETS` 里按名字引用），写错名字必须当场炸掉 ——
    静默返回 `None` 会让容器悄悄退回 auto_state 池，那正是这次要消灭的第二种写法。
    """
    for entry in PINNED_CARRIERS:
        if entry["name"] == name:
            return entry
    raise SystemExit(
        f"PINNED_CARRIERS 里没有载体族 {name!r}；合法族："
        + "、".join(entry["name"] for entry in PINNED_CARRIERS))


def _validate_carrier_targets() -> None:
    """`PINNED_CARRIER_TARGETS` 点名的族必须存在，且必须是 `container` 作用域。"""
    for block, target in PINNED_CARRIER_TARGETS.items():
        entry = pinned_carrier(target["carrier"])
        if entry["scope"] != "container":
            raise SystemExit(
                f"{block}: 点名的载体族 {target['carrier']} 是 {entry['scope']} 作用域，"
                f"只该给容器用的族才允许被 PINNED_CARRIER_TARGETS 引用")


_validate_carrier_targets()

# 写死状态 carrier 的「借了有碰撞的载体」放行登记。
#
# 门禁 `tools/verify_block_contracts.py` 的 `plant_states.collision_pool_mismatch` 要求：
# 上游 `collision=empty` 的方块，载体也必须无碰撞，**除非**在
# {@link COLLISION_POOL_WAIVERS} 里逐条签字（规则 `plant_states.collision_pool_waived`）。
# 键与门禁 `carrier_of()` 的归一化结果一致：显式原版状态会被归一成 `minecraft:<方块>`。
#
# 为什么必须签字而不是让门禁放行整个 `state:` 写法：那等于把「写死状态」变成一条
# 免检通道，以后有人钉一个满格方块进来也不会红。逐族签字后，多借一档就要多写一段理由。
def _register_pinned_waivers() -> None:
    """把 {@link PINNED_CARRIERS} 的代价逐族登记进放行表。

    两张表按 `scope` 分账，不能混：

      * `plant` 族（避雷针/铜锁链）借给**无碰撞方块**，代价是「无碰撞 → 细柱」，
        登记进 {@link COLLISION_POOL_WAIVERS}（门禁 `plant_states.collision_pool_waived`）；
      * `container` 族（note_block/活板门）借给**容器**，代价是「partial → 满格/半格」，
        而 note_block 还会命中「遮挡剔除」那条判据，登记进
        {@link OCCLUDING_CARRIER_WAIVERS}（门禁 `plant_states.occluding_carrier_waived`）。
        它是**用户明确要求的取舍**：写在容器 settings 里的 `can_occlude: false` 已经把
        遮挡关掉，所以「透视」这条不再成立，但判据本身不动 —— 逐块签字才放行。
    """
    for carrier in PINNED_CARRIERS:
        if carrier.get("scope") != "plant":
            continue
        reason = (
            f"**写死状态载体**（`state:`，不占 auto_state 额度）：{carrier['geometry']}。"
            f"无碰撞池已全部用尽（见 POOL_BUDGET 的三个 0 与 chorus 的罚单），"
            f"这一档是「有外观 + 最细的碰撞柱」——比继续借 chorus 的 10/16 柱更接近空碰撞，"
            f"但**不是**空碰撞；代价逐块写在 reports/plant-collision.json 的 fidelity。"
            f"另：{carrier['why']}"
        )
        for block in carrier["blocks"]:
            COLLISION_POOL_WAIVERS[f"minecraft:{block}"] = reason


_register_pinned_waivers()


def _register_occluding_waivers() -> None:
    """把「满格载体 + 容器」这一档逐块登记（门禁的**可见**签字，不是免检通道）。

    键是**方块 id 的短名**（`crimson_chest`），因为这一档的取舍是逐块记账的：
    门禁只在 `occluding_carrier` 判红时来查它，查不到照样红。多一个满格容器就要多签一行。
    """
    for name, target in PINNED_CARRIER_TARGETS.items():
        carrier = pinned_carrier(target["carrier"])
        if not any(block in OCCLUDING_CARRIER_BLOCKS for block in carrier["blocks"]):
            continue
        OCCLUDING_CARRIER_WAIVERS[name] = (
            f"**用户明确要求**：容器走写死状态载体（`state:`，不占 auto_state 额度），"
            f"载体 {target['carrier']} 是{carrier['geometry']}。"
            f"上游碰撞箱 {target['box']}，代价：{target['note']}。"
            f"遮挡风险已由方块 settings 的 `can_occlude: false` 消解"
            f"（CE 用 settings 覆盖状态判定，不沿用载体的 canOcclude）。"
        )


#: 会遮挡剔除的原版载体方块（{@link PINNED_CARRIERS} 里 `canOcclude=true` 的那些）。
OCCLUDING_CARRIER_BLOCKS = frozenset({"note_block"})

#: 「满格载体给容器用」的可见签字表：方块短名 → 理由。门禁逐块核对，
#: 缺席即判红（`plant_states.occluding_carrier`），不会因为这一档存在而整体放行。
OCCLUDING_CARRIER_WAIVERS: dict[str, str] = {}

_register_occluding_waivers()


# 关于 note_block：2026-10-29 起**已接进来**，但只给容器用（`scope: container`）。
#
# 上一轮记的「无法离线枚举哪些状态是空闲的」已经不成立：`NOTE_BLOCK_OCCUPIED` 是从
# 目标服 `cache/visual_block_states.json` 逐条求差得出的（628 条占用 → 本包挑的 4 个
# 头颅乐器位里 78 个空闲），不再有「照着全部状态盲写必然撞格」的问题。
# 仍然不给植物：满格 + canOcclude=true 对植物就是透视洞（见 FORBIDDEN_PLANT_POOLS），
# 这一条没有变，所以 `scope` 把它挡在植物队列之外。


class PinnedState:
    """一个**写死的原版状态**载体（CE 的 `state: minecraft:xxx[...]`）。

    单独一个类型而不是裸字符串：`pooled_states_yaml` / `state_body` 要靠它区分
    「该写 `auto_state:`」还是「该写 `state:`」—— 裸字符串会让两种写法混在一起，
    而写错其中一个的后果是整段配置变成另一个语义（`auto_state: minecraft:xxx` 会被
    CE 当成非法组名，方块**完全没有外观**）。
    """
    __slots__ = ("state", "carrier")

    def __init__(self, state: str, carrier: str) -> None:
        self.state = state
        self.carrier = carrier

    def __str__(self) -> str:
        return self.state

    def __repr__(self) -> str:
        return f"PinnedState({self.state!r})"


class PinnedAllocator:
    """写死状态载体的分配器：**与 {@link PoolAllocator} 分账**。

    两边各有各的余量，互不抵扣：`auto_state` 额度用掉一个少一个（跨包共享的稀缺资源），
    写死状态则是「认领一个原版状态」，不占池、但也不许重复认领。
    所以这里不是一个 `int` 额度，而是一条**有序的状态队列**，取走即出队。

    幂等：队列顺序是常量，取用顺序由方块处理顺序决定（`selected` 已排序），
    同样的输入重复运行得到同样的映射。
    """

    def __init__(self) -> None:
        self._queues = {carrier["name"]: list(carrier["states"]) for carrier in PINNED_CARRIERS}
        #: 能进植物兜底队列的族（`scope == "plant"`）：容器族（满格/半格）对植物是坏几何，
        #: 必须挡在这里，而不是靠调用方记得传 `allow_pinned=False`。
        self._plant_carriers = tuple(
            carrier["name"] for carrier in PINNED_CARRIERS if carrier.get("scope") == "plant")
        #: 每个载体族实际被取走的状态数（写进 `reports/plant-collision.json` 的 `pool_usage`）。
        self.used: dict[str, int] = {}
        #: 实际认领的状态（按取用顺序），逐条落进报告，便于人工核对「哪一格被谁占了」。
        self.used_states: list[str] = []

    def remaining(self) -> int:
        """还剩多少个写死状态可用（所有族合计）。"""
        return sum(len(queue) for queue in self._queues.values())

    def free_of(self, carrier: str) -> int:
        """某个载体族还剩多少格（报告与配额判断用）。"""
        return len(self._queues.get(carrier, ()))

    def take(self, block_id: str, why: str = "") -> PinnedState | None:
        """按 {@link PINNED_CARRIERS} 里 `scope=plant` 族的优先级取一个写死状态；
        全族用尽返回 `None`。

        **返回 `None` 而不是抛异常**：调用方还有别的次序要试（例如回落到 `chorus`），
        真正的 fail-fast 留在 `PoolAllocator.take_from`，免得这里提前截断信息。
        `why` 只用于把「为什么这个方块落到写死状态」留在报告里。
        """
        for name in self._plant_carriers:
            queue = self._queues.get(name, [])
            if not queue:
                continue
            state = queue.pop(0)
            self.used[name] = self.used.get(name, 0) + 1
            self.used_states.append(state)
            return PinnedState(state, name)
        return None

    def take_carrier(self, carrier: str, block_id: str) -> PinnedState:
        """从**指定载体族**取一个写死状态；该族用尽 → `SystemExit`。

        为什么这里 fail-fast 而不是回落到池：容器用 `PINNED_CARRIER_TARGETS` 点名载体，
        一旦取不到就退回 `chorus`，产物里就会同时出现两套写法（用户明确不许），
        而且碰撞箱会静默变成 10/16 柱。宁可让构建炸掉并把余量打出来。
        """
        queue = self._queues.get(carrier, [])
        if not queue:
            raise SystemExit(
                f"{block_id}: 写死状态载体族 {carrier!r} 已用尽（剩 0 格）。\n"
                f"  容器不会回落到 auto_state 池（用户要求容器统一写死状态），"
                f"扩额的办法是往 blocks.NOTE_BLOCK_OCCUPIED / TRAPDOOR_STATES 里补状态，"
                f"补之前先按 `cache/visual_block_states.json` 求差，确认那格真的空闲。")
        state = queue.pop(0)
        self.used[carrier] = self.used.get(carrier, 0) + 1
        self.used_states.append(state)
        return PinnedState(state, carrier)

    def geometry_of(self, carrier: str) -> str:
        """某个载体族的实测几何描述（写进报告，避免报告只给一个族名）。"""
        for entry in PINNED_CARRIERS:
            if entry["name"] == carrier:
                return entry["geometry"]
        return ""

    def cost_of(self, carrier: str) -> str:
        """某个载体族的代价（碰撞箱变化 / 与世界的冲突面），写进 `fidelity`。"""
        for entry in PINNED_CARRIERS:
            if entry["name"] == carrier:
                return entry["cost"]
        return ""


def plant_take_steps(family: tuple[str, ...], collision: str,
                     allow_pinned: bool = True) -> tuple[tuple[str, object], ...]:
    """一个外观取载体的**完整次序**：`("pool", 池名)` 与 `("pinned", None)` 的序列。

    为什么写死状态不是「池用完再说」那么靠后：

      * `collision=empty` 的方块要的是**空碰撞**，池里已经没有任何空碰撞组可用
        （见 {@link POOL_BUDGET} 的三个 0），真正的次序是「先试无碰撞池 →
        再试最细的写死柱 → 最后才 `chorus` 的 10/16 柱」。写死柱比 `chorus` 细，
        所以它必须排在 `chorus` **之前**，否则等于放弃更好的一档；
      * `collision=partial` 的细柱方块反过来：`chorus` 的 10/16 柱正是它们要的近似，
        写死柱（4/16/3/16）比上游还细，所以排在其后，只在整族用尽时兜底。

    `allow_pinned=False`（可爬方块）时整条写死路线被摘掉：
    客户端能不能爬，判定的是**载体状态是否在 `#minecraft:climbable` 标签里**
    （见 {@link CLIMBABLE_POOLS} 的说明），而避雷针/铜锁链都不在标签里 ——
    给可爬方块钉一个避雷针状态，等于让它爬不上去。
    """
    steps: list[tuple[str, object]] = []
    for pool in family:
        if allow_pinned and collision == "empty" and pool in PARTIAL_COLLISION_POOLS:
            # 罚单池（有碰撞的 chorus）之前先试写死细柱。
            steps.append(("pinned", None))
        steps.append(("pool", pool))
    if allow_pinned and not any(kind == "pinned" for kind, _ in steps):
        steps.append(("pinned", None))
    return tuple(steps)


def take_plant_carrier(allocator: PoolAllocator, pinned: PinnedAllocator,
                       family: tuple[str, ...], block_id: str, why: str = "",
                       collision: str = "empty",
                       allow_pinned: bool = True) -> tuple[str, object]:
    """按 {@link plant_take_steps} 的次序取一个载体。

    返回 `("auto_state", 池名)` 或 `("state", PinnedState)`，调用方据此写 `auto_state:` /
    `state:`。两条路都走不通时**回到 `PoolAllocator.take_from` 抛出既有的 fail-fast 文案**：
    缺口信息必须仍然指名池族与每个池的余量，而不是换成一句「载体也没了」。

    2026-10-29 关键修正：**均衡装载**。原来按家族顺序取「第一个有空位的池」→ 所有植物都先挤
    tripwire，把它撑爆而 sapling/kelp/pressure_plate 空着，导致怎么调/删块都溢出。现在在
    **不遮挡的池之间挑「剩余最多」的**，让块自然铺开、没有单一池被挤爆，需求(≈146)≤总容量(≈150)
    就能真正装下。chorus(有碰撞)仅在不遮挡池全满时才用；pinned 仍作最后兜底（容器 family 为空时直接走它）。
    """
    steps = plant_take_steps(family, collision, allow_pinned)
    pool_names = [str(step) for kind, step in steps if kind == "pool"]
    avail = [p for p in pool_names if allocator.free((p,)) > 0]
    if avail:
        noclip = [p for p in avail if p not in PARTIAL_COLLISION_POOLS]
        chosen = noclip if noclip else avail
        best = max(chosen, key=lambda p: allocator.free((p,)))
        return "auto_state", allocator.take_from((best,), block_id, why)
    if allow_pinned:
        pin = pinned.take(block_id, why)
        if pin is not None:
            return "state", pin
    return "auto_state", allocator.take_from(family, block_id, why)


def _build_plant_state_slots() -> dict[str, dict]:
    """把两张真值表合并成「方块 → 池族」的单一视图（见 {@link PLANT_STATE_SLOTS}）。"""
    slots: dict[str, dict] = {}
    for name in sorted(COLLISION_EMPTY_BLOCKS):
        family, collision, note = plant_slot_plan(name, block_props.TIER_NONE, False)
        slots[name] = {"collision": collision, "family": family, "fidelity": note}
    for name, (fits, box, note) in sorted(PARTIAL_COLLISION_FITS.items()):
        family, collision, detail = plant_slot_plan(
            name, block_props.TIER_PARTIAL, fits == "climb")
        slots[name] = {"collision": collision, "family": family,
                       "fidelity": detail, "fits": fits, "box": box, "note": note}
    return slots


# **单一真值表**：56 个「真实状态渲染」的植物各自从哪些池取槽位。
# 生成器按它分配、报告按它记录保真度、门禁按它断言「无碰撞方块只进无碰撞池」。
# 可爬方块运行时会被 `plant_slot_plan` 改写成 `CLIMBABLE_POOLS`（三者都在无碰撞池里）。
PLANT_STATE_SLOTS: dict[str, dict] = _build_plant_state_slots()


# 行为型排除：CE **没有**对应行为，写出来就是伪实现（右键无反应 / 无 GUI / 无持久化）。
# 这些类的方块形状可能是满格，但主功能是交互或方块实体，不是外观。
# 逐条列在报告里，不静默丢弃。
EXCLUDED_CLASS_REASONS = {
    # 目前为空：用户要求「全面向mod看齐、不留 waiver」，且结构 palette 里确实还有
    # 容器/熔炉/酿造台的引用（chest 族 22 处、furnace 族 7 处、brewing stand 1 处）。
    # 留空意味着这些方块会走 KIND_STATIC 近似：外观/硬度/工具/亮度按上游真值，
    # 交互（开箱 GUI、熔炼、酿造）不实现，逐 ID 在报告里写明缺的是哪一项交互。
    #
    # 若要恢复排除，把类名加回这里即可，classify() 会优先按本表返回 KIND_EXCLUDED。
}

# 告示牌：无碰撞，但主功能是文本编辑 GUI + 方块实体，CE 无此行为。
SIGN_CLASSES = {
    "StandingSignBlock",
    "WallSignBlock",
    "CeilingHangingSignBlock",
    "WallHangingSignBlock",
}

# 压力板：无碰撞，但它是红石元件（输出信号 + 实体检测），CE 无压力板行为。
PLATE_CLASSES = {"PressurePlateBlock"}

# 形状层级 → 排除原因（CE 无法表达碰撞箱）。
PARTIAL_SHAPE_REASON = (
    "异形碰撞箱（半砖/楼梯/墙/栅栏/门/活板门/按钮/梯子/窗格/家具）："
    "CE 方块的碰撞箱只能来自所借用的原版视觉状态，auto_state 池不提供这类几何"
)

# 上游注册为**植物**、但形状表里不是「全部状态无碰撞」的类。
#
# 仙人掌族（`BlockBarrelCactus` / `BlockNetherCactus`）带 `age`/`top` 属性：
# age=0/1 时 collision=empty，age=2/3 时长出实体段；水母蘑菇有 top/middle/bottom 三段。
# 按「全部状态无碰撞」的硬判据它们会掉进异形排除，但它们的主功能是植物装饰，
# 且 `NetherPlantBlocks.java` 把 `barrel_cactus`/`nether_cactus` 明确注册成植物。
# 仙人掌借 `cactus` 池 —— 该池的碰撞箱就是 15/16 宽的细柱，与上游仙人掌一致。
CACTUS_CLASSES = {"BlockBarrelCactus", "BlockNetherCactus"}
FORCED_PLANT_CLASSES = CACTUS_CLASSES | {
    "BlockJellyfishMushroom",
    "BlockWartSeed",
    "BlockGiantMold",
    "BlockMushroomFir",
    "BlockWillowTrunk",
    "BlockWillowBranch",
    "BlockStalagnate",
    # `BlockStem` 是竖直的细柱（stalagnate_stem / nether_mushroom_stem 等），
    # 带 axis 三态、碰撞箱是 6px 细柱，形状表里归为异形。
    # 结构 palette 明确引用 `stalagnate_stem`，所以必须收录。
    "BlockStem",
    "BlockBrownLargeMushroom",
    "BlockRedLargeMushroom",
    "BlockSoulLily",
    "BlockStalagnateBowl",
}

# ---------------------------------------------------------------- 纤细池瘦身
#
# 以同目录「更好的末地」为准：纤细池（tripwire/sapling/kelp/sugar_cane/藤蔓…）是所有
# CE 包共用的稀缺资源，BetterNether 不再持续占用。植物与异形装饰（KIND_PLANT /
# KIND_STATIC）只保留**世界生成或结构真正放置**的那些；其余不发布，逐 ID 记进 inventory。
#
# 判据是代码里的事实，不是主观挑选：
#   * Java 世界生成里写死的方块 id（common/ plugin/ 源码中的 "betternether:xxx" 字面量）
#   * 结构 NBT palette 里出现的方块 id
# 两者都扫不到的植物/装饰，世界里不会自然出现，只是创造物品栏里的条目。
WORLDGEN_SOURCE_DIRS = ("common/src/main/java", "plugin/src/main/java")
STRUCTURE_DIR = "plugin/src/main/resources/datapacks"

# 座椅三族（chair / taburet / bar_stool）用户已要求整类删除，名单以 publication 为准，
# 这里只做别名，避免两处各写一份后漂移。
SEAT_SUFFIXES = publication.SEAT_SUFFIXES
SEAT_NAMES = publication.SEAT_PREFIX_NAMES


#: 只被**配方**引用、世界生成与结构都不放置，但仍然必须发布的方块（裸名）。
#:
#: `referenced_block_ids()` 只扫世界生成与结构，配方引用不在其中。于是「只有配方要它」的
#: 方块会被判成「没人放置」而不发布，配方随之失效（`material_unplaced`）。
#: 用户 2026-10-30 裁决：配方引用到的物品必须真实存在，所以这几个显式放行：
#:
#:   * `glowstone_stalactite` —— `glowstone_stalactite_glowstone` 的原料，
#:     与已发布的 basalt/bone/blackstone/netherrack 四个钟乳石同类；
#:   * `ink_bush_seed` —— `black_dye_smelting` 的原料（种子类植物）。
#:
#: 只放这两个：其余「无人放置」的方块要么是**故意移除**的台阶/栅栏/箱子（由
#: `SHAPE_SUFFIXES` / `REMOVED_BLOCK_IDS` 拦在前面），要么根本没有配方依赖它。
RECIPE_ONLY_PUBLISHED: frozenset[str] = frozenset({
    "glowstone_stalactite",
    "ink_bush_seed",
})


def referenced_block_ids(project_root: Path, upstream: Path | None = None,
                         generated: Path | None = None) -> set[str]:
    """世界生成与结构实际会放置的 betternether 方块 id（不含命名空间）。

    ## 四类来源，缺一不可

      * **Java 世界生成源码** —— 树、地形特征这些仍在 Java 里复刻的方块字面量；
      * **结构 NBT** —— Nether City 片段的 palette；
      * **上游 worldgen JSON** —— 2026 年起植被改由 `bnlib.worldgen` 解释上游
        `placed_feature` / `feature` JSON，植物 id 大多只出现在那里。只算**解释得动**的
        特征（`worldgen.build().supported`），被跳过的特征里的 id 不进包 —— 否则包里会
        多出一批「永远不会有东西放置它」的方块；
      * **已生成的地形/树模板** —— `wover:template` 引用到的结构（`bone_01`、`jungle_bones_1`
        等）的 palette。这些 NBT 在上游 `resources/data/betternether/structure/` 下，
        **不在** `STRUCTURE_DIR`（那是本项目的 datapack），以前整类漏扫，
        结果 `bone_mushroom` 被判成「没人放置」而不发布，进服就报
        「CraftEngine 包缺少 1 个世界生成方块 id」。读产物而不是重扫上游 NBT：
        `worldgen_copy.write` 必须排在 `build_ce_pack` 之前，此刻模板已经落盘，
        而且只包含**被引用**的那些模板，不会把从不放置的结构里的方块也算进来。
    """
    import gzip
    import re

    pattern = re.compile(r'"betternether:([a-z0-9_]+)"')
    found: set[str] = set()
    for rel in WORLDGEN_SOURCE_DIRS:
        for path in (project_root / rel).rglob("*.java"):
            found.update(pattern.findall(path.read_text(encoding="utf-8", errors="replace")))
    nbt_pattern = re.compile(rb"betternether:([a-z0-9_]+)")
    for path in (project_root / STRUCTURE_DIR).rglob("*.nbt"):
        raw = path.read_bytes()
        try:
            raw = gzip.decompress(raw)
        except OSError:
            pass
        found.update(m.decode() for m in nbt_pattern.findall(raw))
    resolved_upstream = upstream if upstream is not None else project_root / "upstream/BetterNether"
    if (resolved_upstream / "src/main/generated/data/betternether/worldgen").is_dir():
        # 上游 JSON 的 `{"id": ..., "properties": ...}` 引用：这是「哪些方块会被放置」的
        # 权威来源之一，`wover:pillar`（尖锥）等特征只在这里出现。
        found.update(load_worldgen_block_states(resolved_upstream))
        from bnlib import worldgen as worldgen_rules

        found.update(worldgen_rules.placed_block_ids(resolved_upstream))
    else:
        # 上游不在就明确说出来：静默少扫一类来源会让一批植物被当成「没人放置」而不发布。
        print(f"[瘦身][警告] 找不到上游 worldgen：{resolved_upstream}，植被方块不会被计入引用。")
    # 地形/树模板的 palette（`wover:template` 引用的结构）。缺这一类就是上面注释里
    # `bone_mushroom` 那个事故：方块在世界里会被放置，却被当成「没人放置」而不发布。
    #
    # 注意 `generated` 传进来的是 **CE 包根**（`<out>/craftengine/betternether`，见
    # `extract_bn.py` 的 `--output`），而模板在 `<out>/worldgen-upstream/templates`。
    # 所以向上逐级找，不要假定相对层级 —— 曾经按 `generated/"worldgen-upstream"` 拼，
    # 结果目录永远不存在，`bone_mushroom` 依然被判成「没人放置」。
    if generated is not None:
        templates_root = None
        for base in (generated, *generated.parents):
            candidate = base / "worldgen-upstream" / "templates"
            if candidate.is_dir():
                templates_root = candidate
                break
        if templates_root is not None:
            for path in templates_root.rglob("*.json"):
                found.update(pattern.findall(
                    path.read_text(encoding="utf-8", errors="replace")))
        else:
            print(f"[瘦身][警告] 从 {generated} 向上找不到 worldgen-upstream/templates："
                  f"结构 palette 里的方块不会被计入引用。")
    return found


def is_seat(name: str) -> bool:
    return name in SEAT_NAMES or name.endswith(SEAT_SUFFIXES)


KIND_SOLID = "solid"
KIND_CUTOUT = "cutout"      # 满格但非 SOLID 渲染（玻璃、树叶）
KIND_PLANT = "plant"
KIND_SHAPE = "shape"        # 异形，但 CE 有官方行为可表达
KIND_STATIC = "static"      # 异形且 CE 无行为：按纤细装饰近似落地（保留亮度/音效/工具）
# 异形且**家具形态能精确表达碰撞箱**：不发布方块，改发布 `furniture_item`
# （清单与几何推导在 bnlib/furniture.py；为什么家具能做而方块做不到见那边的模块文档）。
KIND_FURNITURE = "furniture"
KIND_EXCLUDED = "excluded"

# ---------------------------------------------------------------- 异形：CE 官方行为
#
# ## 为什么这些族现在能做了
#
# CE 内置了与原版一致的异形方块行为（docs/blocks.md「方块行为」）：
#   slab_block / stairs_block / door_block / trapdoor_block /
#   fence_block / fence_gate_block / button_block / pressure_plate_block
# 每个行为要求方块声明一组属性（见下表），CE 据此提供**正确的碰撞箱与交互**。
#
# ## 模型怎么来
#
# 上游这些方块的模型 parent 就是**原版模型**（实测
# `cincinnasite_stairs.json` 的 parent 是 `minecraft:block/stairs`，
# `cincinnasite_slab_top.json` 是 `minecraft:block/slab_top`，
# `willow_door_bottom_left.json` 是 `minecraft:block/door_bottom_left`），
# 贴图则是本包的 `betternether:block/*`。所以用 CE 的 `generation`（parent + textures）
# 就能还原几何，**不需要释放 generated 目录里的模型文件** —— 那正是 extractor 只复制
# `src/main/resources` 时最容易断的一环。
#
# ## 变体为什么用「部分属性键」
#
# CE 的变体匹配是「约束的属性和值都相同就匹配」（docs/blocks.md「变体匹配规则」），
# 因此 `shape=straight` 一条就覆盖全部 facing/half/waterlogged 组合。
# 逐条枚举 80 个组合会把 YAML 撑到两万行，且没有任何额外信息。
SHAPE_BEHAVIOR_BY_CLASS = {
    "SlabBlock": "slab_block",
    "StairBlock": "stairs_block",
    "DoorBlock": "door_block",
    "BNWoodlikeDoor": "door_block",
    "TrapDoorBlock": "trapdoor_block",
    "FenceBlock": "fence_block",
    "FenceGateBlock": "fence_gate_block",
    "ButtonBlock": "button_block",
    "PressurePlateBlock": "pressure_plate_block",
    # ---- 以下三类 CE **没有**对应行为，用 fence_block 近似 ----
    #
    # `WallBlock`：墙的碰撞箱是 8px 厚、1.5 格高的立柱 + 横向连接，与栅栏同构。
    #     CE 的 fence_block 正好提供「四向连接 + 1.5 高碰撞」，是现有行为里最接近的。
    #     代价：渲染用单根 post 模型，横向连接臂不会画出来（CE 的 model 是单个模型，
    #     没有 multipart 能力），但方块可放置、可碰撞、可连接，不会在结构里留空洞。
    # `BNPane`：本包的窗格/铁栏杆（quartz_glass_framed_pane、cincinnasite_bars）。
    #     原版窗格同样是四向连接的薄板，理由同上。
    "WallBlock": "fence_block",
    "BNPane": "fence_block",
}

# 近似族：值是该方块「本该有的行为」的说明，写进报告供逐 ID 解释。
SHAPE_APPROXIMATIONS = {
    "WallBlock": "CE 无 wall_block 行为，用 fence_block 近似（四向连接 + 1.5 高碰撞，"
                 "横向连接臂不渲染）",
    "BNPane": "CE 无 pane_block/bars_block 行为，用 fence_block 近似（四向连接 + 薄板碰撞，"
              "连接臂不渲染）",
}

# 族 -> {外观名: (原版 parent 模型, 上游模型名后缀)}
SHAPE_APPEARANCES = {
    "slab_block": {
        "bottom": ("minecraft:block/slab", ""),
        "top": ("minecraft:block/slab_top", "_top"),
        "double": ("minecraft:block/cube_all", "_double"),
    },
    "stairs_block": {
        "straight": ("minecraft:block/stairs", ""),
        # 原版模型名是 inner_stairs / outer_stairs（不是 stairs_inner），写反了 CE 找不到父模型。
        "inner": ("minecraft:block/inner_stairs", "_inner"),
        "outer": ("minecraft:block/outer_stairs", "_outer"),
    },
    "trapdoor_block": {
        "bottom": ("minecraft:block/template_trapdoor_bottom", "_bottom"),
        "top": ("minecraft:block/template_trapdoor_top", "_top"),
        "open": ("minecraft:block/template_trapdoor_open", ""),
    },
    "door_block": {
        "bottom_left": ("minecraft:block/door_bottom_left", "_bottom_left"),
        "bottom_right": ("minecraft:block/door_bottom_right", "_bottom_right"),
        "top_left": ("minecraft:block/door_top_left", "_top_left"),
        "top_right": ("minecraft:block/door_top_right", "_top_right"),
    },
    "fence_block": {
        "post": ("minecraft:block/fence_post", ""),
    },
    "fence_gate_block": {
        "closed": ("minecraft:block/template_fence_gate", ""),
        "open": ("minecraft:block/template_fence_gate_open", "_open"),
    },
    "button_block": {
        "off": ("minecraft:block/button", ""),
        "on": ("minecraft:block/button_pressed", "_pressed"),
    },
    "pressure_plate_block": {
        "up": ("minecraft:block/pressure_plate_up", ""),
        "down": ("minecraft:block/pressure_plate_down", "_down"),
    },
}

# 族 -> 必须声明的属性（名字, CE 类型, 默认值）。顺序即 YAML 里的书写顺序。
SHAPE_PROPERTIES = {
    "slab_block": (("type", "slab_type", "bottom"),
                   ("waterlogged", "boolean", "false")),
    "stairs_block": (("facing", "horizontal_direction", "north"),
                     ("half", "single_block_half", "bottom"),
                     ("shape", "stairs_shape", "straight"),
                     ("waterlogged", "boolean", "false")),
    "trapdoor_block": (("facing", "horizontal_direction", "north"),
                       ("half", "single_block_half", "bottom"),
                       ("open", "boolean", "false"),
                       ("powered", "boolean", "false"),
                       ("waterlogged", "boolean", "false")),
    "door_block": (("facing", "horizontal_direction", "north"),
                   ("half", "double_block_half", "lower"),
                   ("hinge", "hinge", "left"),
                   ("open", "boolean", "false"),
                   ("powered", "boolean", "false")),
    "fence_block": (("north", "boolean", "false"),
                    ("east", "boolean", "false"),
                    ("south", "boolean", "false"),
                    ("west", "boolean", "false")),
    "fence_gate_block": (("facing", "horizontal_direction", "north"),
                         ("open", "boolean", "false"),
                         ("powered", "boolean", "false"),
                         ("in_wall", "boolean", "false")),
    "button_block": (("powered", "boolean", "false"),),
    "pressure_plate_block": (("powered", "boolean", "false"),),
}

# 族 -> 变体键到外观名的映射（部分属性键，见上面的说明）。
# `None` 表示「按属性全组合自动展开」，由 shape_states_yaml 处理。
SHAPE_VARIANTS = {
    "slab_block": (("type=bottom", "bottom"), ("type=top", "top"), ("type=double", "double")),
    "stairs_block": (("shape=straight", "straight"),
                     ("shape=inner_left", "inner"), ("shape=inner_right", "inner"),
                     ("shape=outer_left", "outer"), ("shape=outer_right", "outer")),
    "trapdoor_block": (("open=true", "open"),
                       ("open=false,half=bottom", "bottom"),
                       ("open=false,half=top", "top")),
    "door_block": (("half=lower,hinge=left", "bottom_left"),
                   ("half=lower,hinge=right", "bottom_right"),
                   ("half=upper,hinge=left", "top_left"),
                   ("half=upper,hinge=right", "top_right")),
    # 栅栏的 16 种连接组合必须逐条展开（CE 的变体是精确键匹配），全部指向同一个 post 外观。
    "fence_block": None,
    "fence_gate_block": (("open=false", "closed"), ("open=true", "open")),
    "button_block": (("powered=false", "off"), ("powered=true", "on")),
    "pressure_plate_block": (("powered=false", "up"), ("powered=true", "down")),
}

# ---------------------------------------------------------------- 异形族：CE 官方模板 + 写死可回收状态
#
# 2026-10-29 用户要求「门/活板门/闸门照 BetterEnd B3-openable.yml 的现成方案」，不是自造：
#   * door/trapdoor/fence_gate 用 CE 内置模板 `default:block_state/<kind>`，模板内部逐状态
#     钉 `state: <donor>[...,powered=true]` —— `powered=true` 是 mappings 里被回收的那半边
#     （映射到 powered=false 的正典状态），钉它不会污染世界里的原版门；碰撞与开关由 CE 的
#     door_block/trapdoor_block/fence_gate_block 行为提供。
#   * 一个 donor 被模板吃满（门 32 / 活板门 32 / 闸门 16 状态），所以每个 betternether 方块
#     独占一个 donor。donor 只取「mappings 有 powered=true→false 且 BetterEnd 未占」的材质。
#   * pressure_plate 不用模板（模板会落回 pressure_plate 池），钉
#     `heavy_weighted_pressure_plate[power=N]`，N 逐块连号。
#
# donor 清单（2026-10-29 逐条核对 mappings.yml + BetterEnd B3-openable.yml 已占 donor 得出）：
# donor 清单（2026-10-29 按 debug/block-state-mappings.json + BetterEnd 实际占用逐材质重核）：
#   * 门与活板门的占用不同，必须分开列（灯笼只占 copper 系「活板门」，不占 copper 系「门」）。
#   * 避开 BetterEnd 的 12 个门/活板门 replaced_type（crimson/spruce/birch/jungle/acacia/
#     dark_oak/mangrove/cherry/bamboo/pale_oak/copper/warped）。
#   * 活板门再避开灯笼/水生作物钉的 exposed/weathered/oxidized/waxed_copper/waxed_weathered 铜活板门。
SHAPE_TEMPLATE_DONORS = {
    "door_block": ["oak", "iron", "exposed_copper", "weathered_copper", "oxidized_copper",
                   "waxed_copper", "waxed_exposed_copper", "waxed_weathered_copper",
                   "waxed_oxidized_copper"],
    "trapdoor_block": ["oak", "iron", "waxed_exposed_copper", "waxed_oxidized_copper"],
    # 栅栏门只有木质，木闸门全被占，只剩 crimson / mangrove（FarmersDelight 未安装）。
    "fence_gate_block": ["crimson", "mangrove"],
}
_SHAPE_TEMPLATE_KIND = {
    "door_block": "door",
    "trapdoor_block": "trapdoor",
    "fence_gate_block": "fence_gate",
}
# 每个 donor 只能承载一个方块：按遍历顺序各领一个，用尽就返回 None（调用方剔除该方块）。
_DONOR_CURSOR: dict[str, int] = {b: 0 for b in SHAPE_TEMPLATE_DONORS}

# 压力板：heavy 压力板 power=1..14（mappings 可回收，BetterEnd 一块都没用 heavy）。
_PLATE_POWER_CURSOR = {"next": 1}


def shape_template_states(behavior: str, name: str) -> str:
    """为 door/trapdoor/fence_gate 生成 CE 官方模板的 `state:` 段。

    返回形如：
        state:
          template: default:block_state/trapdoor
          arguments:
            trapdoor_base_block: minecraft:oak_trapdoor
            trapdoor_model: betternether:block/<name>
            trapdoor_texture: betternether:block/<name>
    donor 用尽（或不是这三族）时返回空串，调用方据此把该方块降级/剔除。
    """
    kind = _SHAPE_TEMPLATE_KIND.get(behavior)
    if kind is None:
        return ""
    donors = SHAPE_TEMPLATE_DONORS[behavior]
    i = _DONOR_CURSOR[behavior]
    if i >= len(donors):
        return ""
    donor = donors[i]
    _DONOR_CURSOR[behavior] = i + 1
    # 从方块名反推木材前缀（去掉 _door / _trapdoor / _gate）。
    stem = name
    for suf in ("_door", "_trapdoor", "_gate"):
        if stem.endswith(suf):
            stem = stem[: -len(suf)]
            break
    if kind == "door":
        args = [
            f"door_base_block: minecraft:{donor}_door",
            f"door_model: {NAMESPACE}:block/{name}_ce",
            f"door_texture_top: {NAMESPACE}:block/{stem}_door_top",
            f"door_texture_bottom: {NAMESPACE}:block/{stem}_door_bottom",
        ]
    elif kind == "trapdoor":
        args = [
            f"trapdoor_base_block: minecraft:{donor}_trapdoor",
            f"trapdoor_model: {NAMESPACE}:block/{name}_ce",
            f"trapdoor_texture: {NAMESPACE}:block/{name}",
        ]
    else:  # fence_gate
        args = [
            f"fence_gate_base_block: minecraft:{donor}_fence_gate",
            f"fence_gate_model: {NAMESPACE}:block/{name}_ce",
            f"fence_gate_texture: {NAMESPACE}:block/{stem}_planks",
        ]
    body = "".join(f"        {a}\n" for a in args)
    return ("    state:\n"
            f"      template: default:block_state/{kind}\n"
            "      arguments:\n"
            f"{body}")


_TEMPLATE_PUBLISHED_BEHAVIORS = frozenset(
    set(_SHAPE_TEMPLATE_KIND) | {"pressure_plate_block"})


def _shape_is_template_published(behavior: str) -> bool:
    """该异形行为是否走 CE 模板/写死状态（门/活板门/闸门/压力板）→ 不受"被结构引用才发布"限制。"""
    return behavior in _TEMPLATE_PUBLISHED_BEHAVIORS


def pressure_plate_states(name: str) -> str:
    """木质/石质压力板：钉 `heavy_weighted_pressure_plate[power=N]`（N 逐块连号、可回收）。"""
    n = _PLATE_POWER_CURSOR["next"]
    if n > 14:
        return ""
    _PLATE_POWER_CURSOR["next"] = n + 1
    stem = name[:-5] if name.endswith("_plate") else name
    # 上游已有 `<name>.json`（parent: pressure_plate_up + planks 贴图），直接引用即可，
    # 不要再 generation —— 否则 CE 会去生成同名文件并报「模型文件已存在」。
    return (
        "    state:\n"
        "      properties:\n"
        "        powered:\n"
        "          type: boolean\n"
        "          default: false\n"
        "      appearances:\n"
        "        normal:\n"
        f"          state: minecraft:heavy_weighted_pressure_plate[power={n}]\n"
        "          model:\n"
        f"            path: {NAMESPACE}:block/{name}\n"
        "      variants:\n"
        "        powered=false:\n"
        "          appearance: normal\n"
        "        powered=true:\n"
        "          appearance: normal\n"
    )


# ---------------------------------------------------------------- 世界生成多状态
#
# ## 为什么必须有这一段
#
# 世界生成放置方块时是**带属性**放置的（`nether_reed_stem[top=true]`、
# `red_large_mushroom[shape=top]`、`wart_seed[facing=north]`、`wart_log[axis=x]`、
# `netherrack_stalactite[size=0]`……）。只写单状态 `state:` 的方块在 CE 里只有一个内部状态，
# 放置端 `CraftEngineLookup.lookup(id, properties)` 找不到这些属性，只能退回默认态 ——
# 表现就是「芦苇没有顶、巨菇塌成一格、藤蔓挂反、原木竖着、尖锥永远同粗细」。
#
# 所以这里把上游 **blockstate JSON**（真值来源，不按名字猜）编译成 CE 的
# `states.properties/appearances/variants`：
#
#   * 属性名、取值集合、默认值全部来自 blockstate 的变体键，一个都不臆造；
#   * **植物/装饰**（`KIND_PLANT`/`KIND_STATIC`）每个外观都用**同一个透明共享状态**
#     （`auto_state: {type: sugar_cane, id: transparent}` + `transparent: true`），
#     外观差异交给 `entity_renderer` 的 item_display —— 既不占纤细池，
#     也不借满格方块（借满格会让植物的十字模型剔除邻面 = 透视）。见
#     {@link transparent_states_yaml}；
#   * **满格方块**（原木/木板/树叶，`KIND_SOLID`/`KIND_CUTOUT`）每个外观各占真实视觉状态
#     （`auto_state: solid` / 树叶池），模型按上游 `x`/`y` 旋转，见 {@link pooled_states_yaml}；
#   * 透明的那些外观各自引用一个**上游已存在的模型**，对应一个只写 `path` 的隐藏 item
#     （items 段永不写 `generation`，见 {@link item_yaml}）。
#
# 只给**世界生成/结构真的携带属性**的方块声明属性，其余方块保持单状态，
# 免得凭空多出永不使用的内部状态。
# 注意：梯子整族（`LadderBlock`）已在 `publication.REMOVED_CLASSES` 里剔除，
# 不必在此登记属性——登了也永远读不到。
STATE_PROPS: dict[str, tuple[str, ...]] = {
    # ---- 透明共享状态（植物/装饰）
    # age：生长阶段决定模型（龙舌兰/仙人掌/灌木…），缺了所有植株同龄。
    "agave": ("age",),
    "barrel_cactus": ("age",),
    "black_apple": ("age",),
    # 骨菇：age 决定 floor/wall 的 pre（未长成）与成熟两套模型，缺了永远同一阶段；
    # facing 由支撑行为自动带出（`required_properties`），这里只补 age。
    "bone_mushroom": ("age",),
    "feather_fern": ("age",),
    "ink_bush": ("age",),
    "magma_flower": ("age",),
    "orange_mushroom": ("age",),
    # top：芦苇/仙人掌有没有顶段。
    "nether_cactus": ("top",),
    "nether_reed_stem": ("top",),
    "stalagnate_seed": ("top",),
    # bottom：藤蔓挂哪一头。
    "black_vine": ("bottom",),
    "blooming_vine": ("bottom",),
    "gloomsculk_vine": ("bottom",),
    "golden_vine": ("bottom",),
    # shape：多段植物（巨菇伞/树干/魂百合/锚树藤）的段位。
    "mushroom_fir_trunk": ("shape",),
    "brown_large_mushroom": ("shape",),
    "red_large_mushroom": ("shape",),
    "willow_trunk": ("shape",),
    "willow_branch": ("shape",),
    "soul_lily": ("shape",),
    "jellyfish_mushroom": ("shape", "visual"),
    "anchor_tree_vine": ("shape",),
    "cincinnasite_pillar": ("shape",),
    "smoker": ("shape",),
    # shape + spent + vine_dir：鬼火藤的段位、「已耗尽」外观与**朝向**。
    # 上游叫 `rotation`（east/north/south/west/random），CE 把 `rotation` 硬编码成 int，
    # 所以这里改名为 `vine_dir`、取值原样保留（见 PROPERTY_RENAMES）。
    "gloomwisp_vine": ("shape", "spent", "vine_dir"),
    # facing：贴附方向（发光水晶六向、疣种六向、壁挂装饰）。CE 的 `facing` 是
    # StringTag 语义的方向枚举，与放置端 putString 兼容。
    "gloomsculk_crystal": ("facing",),
    "wart_seed": ("facing",),
    "lucis_spore": ("facing",),
    "wall_moss": ("facing",),
    "jungle_moss": ("facing",),
    "wall_mushroom_brown": ("facing",),
    "wall_mushroom_red": ("facing",),
    "cincinnasite_lantern_small": ("facing",),
    # facing + shape：发光菇（中心/边/角 × 四向）。
    "lucis_mushroom": ("facing", "shape"),
    # size：石笋/钟乳石 8 档粗细（2px→16px），上游有 8 个独立模型。
    "basalt_stalactite": ("size",),
    "blackstone_stalactite": ("size",),
    "bone_stalactite": ("size",),
    "glowstone_stalactite": ("size",),
    "netherrack_stalactite": ("size",),
    # ---- 需要真实视觉状态的满格方块
    # axis：原木/树皮/木板/茎的朝向，缺了就永远竖着。
    "anchor_tree_bark": ("axis",),
    "anchor_tree_log": ("axis",),
    "cincinnasite_bricks_pillar": ("axis",),
    "gloomwood_bark": ("axis",),
    "gloomwood_dark_log": ("axis",),
    "gloomwood_dark_molten_log": ("axis",),
    "gloomwood_log": ("axis",),
    "gloomwood_transition_log": ("axis",),
    "mushroom_fir_stripped_log": ("axis",),
    "nether_mushroom_stem": ("axis",),
    "nether_reed_planks": ("axis",),
    "nether_sakura_bark": ("axis",),
    "nether_sakura_log": ("axis",),
    "stalagnate_stem": ("axis",),
    "stalagnate_stripped_log": ("axis",),
    "wart_bark": ("axis",),
    "wart_log": ("axis",),
    "willow_bark": ("axis",),
    "willow_log": ("axis",),
    # axis + shape：红宝石树的树干分「底/中/顶」三段，模型各不相同。
    "rubeus_bark": ("axis", "shape"),
    "rubeus_log": ("axis", "shape"),
    # facing + natural：柳树叶的六个朝向各用不同模型（侧/顶/底）。
    "willow_leaves": ("facing", "natural"),
    # blue：蘑菇冷杉脚下那层蓝色菌丝。上游 `MushroomFirFeature.BLUE_MYCELIUM`
    # 是 `nether_mycelium[blue=true]`（blockstate 的 `blue=true` 指向
    # `nether_mycelium_blue`），这是 surface_rules JSON 里看不到的那类属性，
    # 只能写在白名单里；缺了它菌丝永远退回普通外观。
    "nether_mycelium": ("blue",),
}


_HORIZONTAL_DIRECTIONS = ("north", "south", "east", "west")
_SIX_DIRECTIONS = _HORIZONTAL_DIRECTIONS + ("up", "down")


_YAML_AMBIGUOUS_SCALAR = re.compile(r"^(true|false|null|~|-?\d+(\.\d+)?)$")


def _yaml_scalar(value: str, ce_type: str) -> str:
    """字符串属性里形如 `false` / `0` 的取值必须加引号。

    不加引号时 YAML 会把它解析成布尔或数字，CE 读 `string` 属性就取不到 "false"。
    """
    if ce_type == "string" and _YAML_AMBIGUOUS_SCALAR.match(value):
        return f'"{value}"'
    return value


def _property_type(values: list[str]) -> tuple[str, str]:
    """按上游取值集合决定 CE 属性类型，返回 (type, 追加的 YAML 行)。

    ## 为什么 boolean / int 一律退化成 string

    CE 的每种属性类型只认特定 NBT 标签
    （javap `Property.unpack` 核对）：
      * `BooleanProperty.unpack` 只接受 **ByteTag**；
      * `IntegerProperty.unpack` 只接受 **NumericTag**；
      * `StringProperty` / `EnumProperty`（`direction` 等）只接受 **StringTag**。

    而放置端 `CraftEngineLookup.lookup(id, Map<String,String>)` 是
    `tag.putString(键, 值)` 建 CompoundTag 的（common/…/CraftEngineLookup.java）。
    所以声明成 `boolean`/`int` 的属性能被 `hasProperty` 认出来，但 `unpack` 会抛
    IllegalArgumentException，`getBlockState` 整体失败 → 又退回默认态，等于没声明。

    故这里只在「取值集合恰好是方向枚举」时用 `direction`/`horizontal_direction`
    （它们本来就是 StringTag 语义，还能顺带拿到 CE 的放置朝向行为），
    其余一律 `string` + 显式候选值。
    """
    unique = sorted(set(values))
    if set(unique) <= {"x", "y", "z"}:
        # `axis`：StringTag 语义的枚举，且是 CE 的硬编码名 —— 手动放置时会自动对齐轴向。
        return "axis", ""
    if set(unique) <= set(_HORIZONTAL_DIRECTIONS):
        return "horizontal_direction", ""
    if set(unique) <= set(_SIX_DIRECTIONS):
        return "direction", ""
    return "string", "          values:\n" + "".join(
        f"            - {_yaml_scalar(value, 'string')}\n" for value in unique)


def _normalize_degrees(value: int) -> int:
    """把角度收进 (-180, 180]，避免写出 `-270` 这种等价的怪值。"""
    return (value + 180) % 360 - 180


def load_variant_states(blockstates_dirs, name: str) -> dict | None:
    """读上游 blockstate 的 `variants`，按**文件顺序**返回

    `{"states": [(属性字典, [(模型名, x, y, uvlock), …])], "source": 文件名}`。

    模型名只取文件名部分（`betternether:block/agave_3` → `agave_3`），
    因为下游一律用 `betternether:block/<模型名>` 引用。

    **变体值是列表时整条保留**（vanilla 语义：同一状态列出多个模型 → 客户端按方块位置
    随机挑一个）。这一点很重要：
      * 满格方块（原木/树叶/尖锥）把它原样编译成 CE 的 `models:` 权重列表，
        上游的随机外观**不会丢**（`willow_trunk_t0/t1`、`wart_seed_02/03` 等）；
      * 透明共享状态（`entity_renderer.item`）只能绑**一个** item，
        列表里除第一个以外用不了，由 {@link compile_plant_states} 记进 `dropped`。
    `multipart` 形式的方块返回 None —— 它不是「一份属性→一个模型」的映射。
    """
    for directory in blockstates_dirs:
        path = directory / f"{name}.json"
        if not path.is_file():
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        variants = payload.get("variants")
        if not isinstance(variants, dict):
            continue
        states: list[tuple[dict, tuple[tuple[str, int, int, bool], ...]]] = []
        for key, value in variants.items():
            entries = value if isinstance(value, list) else [value]
            models: list[tuple[str, int, int, bool]] = []
            for entry in entries:
                if not isinstance(entry, dict) or not isinstance(entry.get("model"), str):
                    continue
                ref = entry["model"]
                stem = ref.split("/", 1)[1] if "/" in ref else ref
                models.append((stem, int(entry.get("x") or 0), int(entry.get("y") or 0),
                               bool(entry.get("uvlock"))))
            if not models:
                continue
            values: dict[str, str] = {}
            for part in (piece.strip() for piece in str(key).split(",")):
                if not part:
                    continue
                if "=" not in part:
                    values = {}
                    break
                prop, raw = part.split("=", 1)
                values[prop.strip()] = raw.strip()
            states.append((values, tuple(models)))
        return {"states": states, "source": path.name}
    return None


def compile_plant_states(name: str, raw, prop_names: tuple[str, ...],
                         model_exists) -> dict | None:
    """把上游变体编译成 CE `states:` 规格（属性 + 外观 + 变体 + 折叠说明）。

    `raw` 是 {@link load_variant_states} 的返回值（也接受裸 `[(属性, 模型列表)]`，方便测试）。
    `prop_names` 只列**世界生成真的会带**的属性：没被放置端写入的属性不声明，
    否则等于给方块凭空加内部状态。上游带了但这里没声明的属性（如鬼火藤的
    `rotation`/`offset`/`persistent`）会在 `dropped` 里逐条写明原因。

    外观一律用 CE 的 `models:` 权重列表表达，所以上游「同一状态列多个随机模型」
    **全部保留**（改造前走 item_display 时只能留第一个，那是展示实体路线的代价）。
    """
    states_in = raw["states"] if isinstance(raw, dict) else raw
    declared = [prop for prop in prop_names
                if any(prop in values for values, _models in states_in)]
    if not declared:
        return None

    # 1) 只保留被声明的属性。同一组取值只留第一次出现的模型列表：
    #    上游靠「未声明属性」区分的那几个状态在 CE 侧没有对应物。
    collapsed: dict[tuple, tuple[tuple[str, int, int, bool], ...]] = {}
    order: list[tuple] = []
    for values, models in states_in:
        key = tuple((prop, values[prop]) for prop in declared if prop in values)
        if key in collapsed:
            continue
        collapsed[key] = models
        order.append(key)

    dropped: list[str] = []
    ignored = sorted({prop for values, _models in states_in for prop in values} - set(declared))
    if ignored:
        dropped.append(
            "上游还带 " + "、".join(ignored) + "：世界生成不写这些属性（或 CE 对同名属性"
            "硬编码了类型），未声明，相关外观差异只能由已声明属性表达")
    if len(order) < len(states_in):
        dropped.append(
            f"上游 {len(states_in)} 个变体折叠成 {len(order)} 个状态：未声明属性在"
            "同一外观下无法区分，只保留文件里第一份模型")

    # 2) 属性定义：取值集合与默认值都取上游实际出现过的值；默认值优先用上游
    #    `registerDefaultState` 的真值（STATE_PROP_DEFAULTS），否则取文件里第一个变体的值
    #    （保证默认态一定是上游存在的组合，物品图标看到的就是它）。
    properties: list[tuple[str, str, str, str]] = []
    for prop in declared:
        seen: list[str] = [dict(key)[prop] for key in order if prop in dict(key)]
        ce_type, extra = _property_type(seen)
        if (name, prop) in STATE_PROP_DEFAULTS:
            default = STATE_PROP_DEFAULTS[(name, prop)]
        elif prop == "axis":
            # 原版 `RotatedPillarBlock` 的默认态是 `axis=y`（竖着）。上游 blockstate JSON
            # 只有变体、没有默认态；按「文件第一个变体」取会把 axis=x 当成默认，结构/世界生成
            # 放置不带 axis 的原木时就会变成横躺的。CE 的硬编码 axis 行为在玩家放置时用
            # 点击面覆盖默认值，所以 y 只影响「没给属性」的场景，与模组一致。
            default = "y"
        else:
            default = seen[0]
        if default not in seen:
            default = seen[0]
        properties.append((prop, ce_type, default, extra))

    # 3) 每个「模型列表 + 朝向」组合一个外观；同名外观加序号避免撞名。
    #    坏模型/缺失模型先从列表里剔掉；剔空了整个外观就不要了（宁可没有外观，
    #    也不写一个指向不存在文件的引用 —— 那在游戏里是紫黑格子）。
    dropped_models: set[str] = set()
    appearances: list[tuple[str, tuple[tuple[str, int, int, bool], ...]]] = []
    index_of: dict[tuple, str] = {}
    for key in order:
        usable = tuple(entry for entry in collapsed[key] if model_exists(entry[0]))
        dropped_models.update(entry[0] for entry in collapsed[key]
                              if not model_exists(entry[0]))
        if not usable:
            continue
        # 外观身份 = 整份模型列表：上游的随机备选（加权随机）要全部保留。
        signature = usable
        if signature in index_of:
            continue
        app_name = "_".join(f"{prop}_{value}" for prop, value in key) or "default"
        if app_name in index_of.values():
            app_name = f"{app_name}_{len(appearances)}"
        index_of[signature] = app_name
        appearances.append((app_name, signature))
    if dropped_models:
        dropped.append("上游缺模型或模型不可发布，这些外观引用被剔除："
                       + "、".join(sorted(dropped_models)))

    # 4) 变体键：能省掉的属性就省掉（CE 的变体键是「未列出的属性 = 匹配所有取值」），
    #    但省完必须仍然**只匹配本组**。逐个属性试着去掉，去掉后若会命中别人就留着。
    #    这样 `top=false`/`top=true` 两条就够，不用逐条列出全部内部状态；
    #    而 `shape=top,spent=false` 这种必须两个属性才唯一的组合也不会漏。
    groups: dict[str, list[tuple]] = {}
    for key in order:
        usable = tuple(entry for entry in collapsed[key] if model_exists(entry[0]))
        if not usable:
            continue
        groups.setdefault(index_of[usable], []).append(key)
    variants: list[tuple[str, str]] = []
    for app_name, members in groups.items():
        # 上游允许某个变体键省略属性（`willow_leaves` 的 `natural=false` 就不写 facing），
        # 所以只把「组内每个状态都带这个属性」的键放进基础键；缺失的属性不能写进变体键。
        present = [prop for prop in declared
                   if all(prop in dict(member) for member in members)]
        key = {prop: dict(members[0])[prop] for prop in present}
        others = [state for state in order if state not in members]
        # 没有别的状态时不需要压缩键：写全反而更易读，也不会把键压成空。
        for prop in (present if others else ()):
            trial = {name_: value for name_, value in key.items() if name_ != prop}
            if not trial:
                continue  # 至少保留一个属性，否则这条变体等于「匹配全部」
            if any(all(dict(state).get(name_) == value for name_, value in trial.items())
                   for state in others):
                continue
            key = trial
        if not key:
            dropped.append(f"外观 {app_name} 无法用变体键唯一定位（已回退默认外观）")
            continue
        variants.append((",".join(f"{prop}={key[prop]}" for prop in declared if prop in key),
                         app_name))
        # 同组里「取值不同但外观相同」的成员也要有自己的键。
        # 例：`gloomwisp_vine` 上游 `vine_dir=random` 与 `north` 用的是同一份模型，
        # 压缩后的键只写了 north，于是 `vine_dir=random` 放置时会**回退默认外观**
        # （连 shape 都跟着错）。这里给每个没被压缩键覆盖的成员补一条完整键。
        for member in members:
            values = dict(member)
            if all(values.get(prop) == value for prop, value in key.items()):
                continue
            explicit = ",".join(f"{prop}={values[prop]}" for prop in declared if prop in values)
            if all(explicit != existing for existing, _app in variants):
                variants.append((explicit, app_name))

    return {
        "properties": properties,
        "appearances": appearances,
        "variants": [(key, app) for key, app in variants
                     if any(app == appearance[0] for appearance in appearances)],
        "dropped": dropped,
    }


def _states_head(spec: dict) -> str:
    """`states: properties:` 段（所有策略共用）。"""
    prop_lines = ""
    for prop, ce_type, default, extra in spec["properties"]:
        prop_lines += (
            f"        {prop}:\n"
            f"          type: {ce_type}\n"
            f"          default: {_yaml_scalar(default, ce_type)}\n"
            f"{extra}"
        )
    return "    states:\n      properties:\n" + prop_lines


def _states_tail(spec: dict, app_lines: str) -> str:
    """`appearances:` + `variants:` 段，与 {@link _states_head} 拼成完整 `states:`。

    `variants` 在 CE 里是**可选**的（docs/blocks.md「变体匹配规则」规则 3：没匹配到任何
    变体的状态回退到第一个外观）。所以只有一条外观、或者所有状态都映射到同一个外观时，
    这里就不写 `variants`，只靠 properties + appearances 让属性存在且外观正确。
    """
    if not app_lines:
        return ""
    variant_lines = "".join(
        f"        {key}:\n          appearance: {app_name}\n"
        for key, app_name in spec["variants"]
    )
    tail = "      appearances:\n" + app_lines
    if variant_lines:
        tail += "      variants:\n" + variant_lines
    return tail


def _models_block(models, indent: str) -> str:
    """`models:`（权重列表）或单数 `model:` —— 两者的字段写法相同，只是嵌套位置不同。"""
    if len(models) == 1:
        model, x, y, uvlock = models[0]
        lines = f"{indent}model:\n{indent}  path: {NAMESPACE}:block/{model}\n"
        if x:
            lines += f"{indent}  x: {x}\n"
        if y:
            lines += f"{indent}  y: {y}\n"
        if uvlock and (x or y):
            lines += f"{indent}  uvlock: true\n"
        return lines
    lines = f"{indent}models:\n"
    for model, x, y, uvlock in models:
        lines += f"{indent}  - path: {NAMESPACE}:block/{model}\n{indent}    weight: 1\n"
        if x:
            lines += f"{indent}    x: {x}\n"
        if y:
            lines += f"{indent}    y: {y}\n"
        if uvlock and (x or y):
            lines += f"{indent}    uvlock: true\n"
    return lines


def _properties_block(properties: tuple[str, ...], indent: str,
                      facing_type: str = "6-direction") -> str:
    """CE 方块状态属性声明段（`properties:`）。

    目前只会出现 `facing` —— CE 的 `directional_attached_block` 要求方块声明它，
    不声明就报错（「该行为需要定义方块状态属性 facing」）并让整条行为失效。

    `facing` 的类型必须用 CE 的**内建类型名**（不是 `string` + 自列 values）：
      * `6-direction` —— 六向（含上下），对应上游 facing ∈ {north,east,south,west,up,down}；
      * `horizontal_direction` —— 仅水平四向，对应上游 facing ∈ {north,east,south,west}。
    取值集合由 CE 自己定；写错类型名 CE 不认，行为照样失效。
    更好的末地（手写、可用的参考实现）用的就是这两个名字。
    """
    lines = [f"{indent}properties:\n"]
    for prop in properties:
        lines.append(f"{indent}  {prop}:\n")
        lines.append(f"{indent}    type: {facing_type if prop == 'facing' else 'string'}\n")
    return "".join(lines)


def pooled_states_yaml(spec: dict, take_pool,
                       required_properties: tuple[str, ...] = ()) -> str:
    """多状态方块的 `states:` 段：**每个外观各占一个真实视觉状态**。

    植物（碰撞=empty/partial）与满格方块（原木/木板/树叶/尖锥）共用这一段，
    区别只在 `take_pool()` 返回哪个池：植物的池来自 {@link plant_slot_plan}
    （只可能是无碰撞池，或 `cactus`），满格的来自 `solid` / 树叶池。

    为什么要「每个外观一个状态」：CE 里**一个原版方块状态只能绑一个模型**，
    而植物有 162 个外观 —— 想用真实状态 + 模型渲染，就只能一个一个占槽位。
    槽位不够时由 {@link PoolAllocator.take_from} fail-fast，绝不退回展示实体。

    模型朝向直接沿用上游 blockstate 的 `x`/`y`：CE 的 `state.model.x/.y`
    与 vanilla 变体的旋转换算一致（官方 `block_states.yml` 模板里 stairs 的
    `x: 180, y: 90` 就是照抄 vanilla 变体值），不需要另行拧符号。

    **随机备选模型用 `models:` 权重列表原样保留**：CE 的方块外观支持
    `models: [{path, weight}]`（官方 `flame_cane.yml` 就是这种写法），
    vanilla 语义是「同一状态列多个模型 → 按方块位置随机挑一个」，
    所以上游 `willow_trunk_t0/t1`、`wart_seed_02/03`、`gloomwisp_vine` 的相位模型都不会丢。
    """
    # 单状态压缩：被压成 1 个外观、且没有原版属性时，用 CE 的**单状态形式 `state:`**
    # （而不是 `states:` + 空 properties），该块严格只占 1 个原版方块状态。
    if len(spec["appearances"]) == 1 and not spec["properties"]:
        _app_name, _models = spec["appearances"][0]
        _carrier = take_pool()
        _carrier_line = (f"state: {_carrier.state}" if isinstance(_carrier, PinnedState)
                         else f"auto_state: {_carrier}")
        if required_properties:
            # 支撑行为要求声明属性时（如 directional_attached_block 要 facing），CE 在
            # `properties` 非空时会**无条件**取 `appearance(s)` 段，缺了就抛
            # `missing_argument: appearances` —— 所以不能再写「扁平单状态」形式，
            # 必须套一层 appearances（单外观就叫 default，所有内部状态共用它）。
            # 结构与更好的末地（手写、可用的参考实现）一致：
            #   state: { properties: {...}, appearances: { default: { auto_state, models } } }
            return (
                "    state:\n"
                + _properties_block(required_properties, "      ")
                + "      appearances:\n"
                + "        default:\n"
                + f"          {_carrier_line}\n"
                + _models_block(_models, "          ")
            )
        return f"    state:\n      {_carrier_line}\n" + _models_block(_models, "      ")
    app_lines = ""
    for app_name, models in spec["appearances"]:
        # 载体两种：`auto_state:` 从池里分配（跨包共享），`state:` 写死（见 PinnedState）。
        # 写错一个字的后果完全不同：`auto_state: minecraft:xxx` 会被 CE 当成非法组名，
        # 该方块**完全没有外观**，所以这里按类型分派，不做字符串猜测。
        carrier = take_pool()
        if isinstance(carrier, PinnedState):
            app_lines += (
                f"        {app_name}:\n"
                f"          state: {carrier.state}\n"
            )
        else:
            app_lines += (
                f"        {app_name}:\n"
                f"          auto_state: {carrier}\n"
            )
        # 单模型写单数 `model:`（与其它方块一致，便于门禁校验）；
        # 多模型才写 `models:` 权重列表（上游随机备选 → 客户端按位置随机挑）。
        app_lines += _models_block(models, "          ")
    tail = _states_tail(spec, app_lines)
    return _states_head(spec) + tail if tail else ""


# 属性名被 CE 硬编码成别的类型、**不能按上游语义声明**的：
# 声明了反而更糟（CE 会对它 checkcast 成 IntegerProperty，字符串取值直接抛异常）。
UNSAFE_PROPERTY_NAMES = {
    "rotation": "CE 把属性名 rotation 硬编码成 int（HardcodedPropertyBlockBehavior 直接 "
                "checkcast IntegerProperty），上游取值是 east/north/south/west/random 字符串",
}

# 属性**改名**：CE 对某些属性名硬编码了类型，无法按上游语义声明，但语义要保留，
# 于是换一个名字继续用同一套取值。放置端必须按新名字传（已与 MegaLake 放置端协调）。
#   * `gloomwisp_vine` 上游 `rotation ∈ {east,north,south,west,random}`（字符串方向），
#     而 CE 的 `rotation` 是 int → 改名为 `vine_dir`，取值原样保留，朝向照旧能表达。
PROPERTY_RENAMES: dict[str, str] = {"rotation": "vine_dir"}

# 上游 `registerDefaultState` 里写死的默认值（blockstate JSON 只有变体，没有默认态）。
# 必须按上游真值设，否则「不带属性放置」的方块会拿到与模组不同的默认外观。
#   * `BlockGloomwispVine.java:307-311`：`.setValue(ROTATION, WispRotation.RANDOM)`
STATE_PROP_DEFAULTS: dict[tuple[str, str], str] = {
    ("gloomwisp_vine", "vine_dir"): "random",
}

def load_worldgen_block_states(upstream: Path) -> dict[str, dict[str, set[str]]]:
    """扫上游 worldgen JSON 里所有 `{"id": ..., "properties": {...}}` 形态的方块状态引用。

    这是**比代码里的白名单更权威的来源**：它同时给出「哪些方块会被放置」和
    「放置时带哪些属性」。`wover:pillar` 这类特征把方块状态写在 `state.id`/`state.properties`
    里（例如 `betternether:basalt_stalactite` + `size=0`），只按 `betternether:xxx` 字面量
    扫 Java 源码是抓不到的 —— 尖锥曾经因此整类被当成「没人放置」而不发布。

    返回 `{方块名(不含命名空间): {属性名: {取值...}}}`。
    """
    found: dict[str, dict[str, set[str]]] = {}
    worldgen = upstream / "src/main/generated/data/betternether/worldgen"
    if not worldgen.is_dir():
        return found

    def walk(node) -> None:
        if isinstance(node, dict):
            ref = node.get("id")
            if isinstance(ref, str) and ":" in ref:
                namespace, name = ref.split(":", 1)
                if namespace == NAMESPACE:
                    props = found.setdefault(name, {})
                    declared = node.get("properties")
                    if isinstance(declared, dict):
                        for key, value in declared.items():
                            props.setdefault(str(key), set()).add(str(value))
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    for path in worldgen.rglob("*.json"):
        try:
            walk(json.loads(path.read_text(encoding="utf-8")))
        except (json.JSONDecodeError, OSError):
            continue
    return found


# 上游有几个模型本身是坏的（引用原版不存在的贴图），`extract_bn.copy_assets` 因此
# **不会释放**它们（见那边的 `BROKEN_UPSTREAM_MODELS`）。这里必须同样排除：
# 否则会写出「外观指向一个永远不会落盘的模型」，资源包门禁直接报
# 「既没有模型文件…也没有 generation…游戏内会是紫黑格子」。
# 只读本文件无法导入 extract_bn（会形成循环导入），所以这两处名单要保持一致。
UNUSABLE_MODEL_STEMS = {"stairs_with_top", "wall_mushrooms_red_1", "wall_mushrooms_red_2"}

# 上游坏模型的**显式资源迁移修复**（不是丢方块）。
#
# `wall_mushrooms_red_1/_2` 的贴图引用写的是 `blocks/mushroom_red_new`（复数 `blocks/`
# 加一个原版不存在的名字），所以 extract_bn 的 BROKEN_UPSTREAM_MODELS 拒绝释放它们。
# 但红蘑菇方块的原版材质是现成的：`minecraft:block/red_mushroom_block` +
# `minecraft:block/mushroom_block_inside`（褐壁菇用的就是同结构的 brown 版）。
# 这里把引用改写成这对合法材质，并把修复后的模型在**构建期合成**到 `<stem>_ce`，
# 于是红色壁菇的四个朝向照旧可用 —— 属于「迁移期修资源引用」，不改世界生成形状。
MODEL_TEXTURE_REPAIRS: dict[str, dict[str, str]] = {
    "wall_mushrooms_red_1": {
        "blocks/mushroom_red_new": "minecraft:block/red_mushroom_block",
        "block/mushroom_red_new": "minecraft:block/red_mushroom_block",
    },
    "wall_mushrooms_red_2": {
        "blocks/mushroom_red_new": "minecraft:block/red_mushroom_block",
        "block/mushroom_red_new": "minecraft:block/red_mushroom_block",
    },
}


def build_model_repair(stem: str, read_model) -> dict | None:
    """按 {@link MODEL_TEXTURE_REPAIRS} 生成「修复后的模型」合成条目（不可修则 None）。

    `read_model(stem)` 返回上游模型 JSON（dict）或 None。
    返回的条目交给 assets.py 落成 `models/block/<stem>_ce.json`：
    有 `elements` 就搬本体，否则搬 `parent`；贴图槽按同族合法材质改写。
    """
    mapping = MODEL_TEXTURE_REPAIRS.get(stem)
    if mapping is None:
        return None
    payload = read_model(stem)
    if not isinstance(payload, dict):
        return None
    textures: dict[str, str] = {}
    for slot, value in (payload.get("textures") or {}).items():
        if not isinstance(value, str):
            continue
        textures[slot] = mapping.get(value, value if ":" in value else f"minecraft:{value}")
    entry: dict[str, object] = {"path": f"block/{stem}_ce", "textures": textures}
    elements = payload.get("elements")
    parent = payload.get("parent")
    if isinstance(elements, list):
        entry["elements"] = elements
    elif isinstance(parent, str):
        entry["parent"] = parent if ":" in parent else f"minecraft:{parent}"
    else:
        return None
    return entry


def model_is_usable(stem: str, resolver) -> bool:
    """模型是否能真的出现在资源包里：文件存在，且不在「上游坏模型」名单里。

    `resolver(stem)` 返回 `Path | None`（由调用方提供，因为模型目录是 main 里的局部状态）。
    """
    return stem not in UNUSABLE_MODEL_STEMS and resolver(stem) is not None


def model_file_of(upstream: Path, ref: str) -> Path | None:
    """模型 id（`betternether:block/x` / `betternether:item/x`）→ 上游文件。

    `models/block` 与 `models/item` 都要能解析：物品图标和门/告示牌用的是 item 模型，
    只查 `block/` 会把闭合的 item 模型误判成缺槽位。两棵资源树（datagen 的 generated
    与手写的 resources）都算。
    """
    tail = ref.split(":", 1)[1] if ":" in ref else ref
    for tree in UPSTREAM_ASSET_TREES:
        candidate = upstream / tree / "models" / f"{tail}.json"
        if candidate.is_file():
            return candidate
    return None


def item_model_ref(upstream: Path, name: str) -> str | None:
    """上游物品图标的模型 id：`items/<name>.json` 里的 `minecraft:model` 引用。

    墙/栅栏指向 `block/<name>_inventory`，门/告示牌/藤蔓指向 `item/<name>` ——
    取上游原值，比猜 `block/<name>` 准确。

    名字对不上时按 {@link LANG_ALIASES} 再看一眼：`stalagnate_trunk` 在上游和
    `stalagnate_stem` 是**同一个物品**（trunk 只加方块侧 trait，stem 的 `buildModel`
    明写 `externalModelDelegatedItem(block/stalagnate_stem_1)`），所以
    `items/stalagnate_stem.json` 就是它的图标来源。
    """
    gen_assets = upstream / UPSTREAM_ASSET_TREES[0]
    definition = gen_assets / "items" / f"{name}.json"
    if not definition.is_file():
        alias = LANG_ALIASES.get(name)
        if alias is None:
            return None
        definition = gen_assets / "items" / f"{alias}.json"
        if not definition.is_file():
            return None
    try:
        model = json.loads(definition.read_text(encoding="utf-8")).get("model") or {}
    except json.JSONDecodeError:
        return None
    ref = model.get("model") if model.get("type") == "minecraft:model" else None
    if not isinstance(ref, str) or ":" not in ref:
        return None
    return ref if model_file_of(upstream, ref) is not None else None


def model_slots_closed(ref: str, resolver) -> bool:
    """模型的贴图槽位是否闭合：每个 `#slot` 都能沿 parent 链解析到具体贴图。

    反例是上游的模板模型 `block/stalagnate_trunk`：`elements` 的六个面写的是
    `#side`/`#top`，而它自己的 `textures` 只声明了 `particle: "#side"` —— side/top
    由子模型（`stalagnate_stem_1`、`stalagnate_middle_*`）补。直接把它当物品图标，
    客户端拿到的是解析不到的槽位，渲染成缺失材质。

    `resolver(ref)` 返回模型文件路径（`Path | None`），由调用方提供 ——
    模型搜索目录是 main 里的局部状态。父级是原版模型（`minecraft:...`）时链会断在
    本地，这是正常的：原版父模型只提供几何，槽位名由本包的 `textures` 给。
    """
    merged: dict[str, str] = {}
    used: list[str] = []
    seen: set[str] = set()
    current: str | None = ref
    while current and current not in seen:
        seen.add(current)
        path = resolver(current)
        if path is None:
            break
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return False
        textures = payload.get("textures")
        if isinstance(textures, dict):
            for slot, value in textures.items():
                if isinstance(value, str) and value.strip().startswith("#"):
                    used.append(value.strip()[1:])
                merged.setdefault(slot, value)
        for element in payload.get("elements") or []:
            if not isinstance(element, dict):
                continue
            for face in (element.get("faces") or {}).values():
                texture = face.get("texture") if isinstance(face, dict) else None
                if isinstance(texture, str) and texture.startswith("#"):
                    used.append(texture[1:])
        parent = payload.get("parent")
        current = parent if isinstance(parent, str) else None
    resolved = transparency.resolve_slot_refs(merged)
    return all(slot in resolved for slot in used)


def classify(block_id: str, p: block_props.BlockProps | None,
             tier: str, leaves_tag: set[str]) -> tuple[str, str]:
    """决定这个方块在 CE 里怎么落地，返回 (kind, 原因/备注)。

    顺序是「先看 CE 有没有这个行为，再看形状能不能表达」：
      1. 方块实体/容器/工作站 → 排除（行为缺失，不是外观问题）
      2. 告示牌 / 压力板      → 排除（无碰撞但行为缺失）
      3. CE 有异形行为        → 形状族（stairs/slab/door/trapdoor）
      4. 上游注册为植物       → 植物
      5. 异形但 CE 无行为     → 排除（碰撞箱无法表达）
      6. 无碰撞装饰           → 植物
      7. 满格立方体           → SOLID 或 cutout
    """
    cls = p.cls if p else ""
    if cls in EXCLUDED_CLASS_REASONS:
        return KIND_EXCLUDED, EXCLUDED_CLASS_REASONS[cls]
    # CE 有官方行为的异形族优先：压力板/按钮/栅栏/栅栏门在这里必须排在
    # 旧的「压力板排除」之前，否则它们会被误判成不可实现。
    if cls in SHAPE_BEHAVIOR_BY_CLASS:
        return KIND_SHAPE, SHAPE_BEHAVIOR_BY_CLASS[cls]
    if cls in SIGN_CLASSES:
        # 用户要求「无 API 的牌子用可运行近似」：按静态装饰落地，保留外观与碰撞，
        # 只是不能输入文本。逐 ID 在报告里写明。
        return KIND_STATIC, "告示牌：CE 无文本编辑行为，按静态装饰近似（不可输入文本）"
    # 上游注册为植物、但形状不是「全部状态无碰撞」的类，按植物落地。
    if cls in FORCED_PLANT_CLASSES:
        return KIND_PLANT, "上游注册为植物（部分状态有碰撞），按植物挂到纤细视觉状态池"
    if tier == block_props.TIER_PARTIAL:
        # 家具族优先于「纤细装饰近似」：方块形态给不出它们的碰撞箱（见 furniture.py），
        # 而 `furniture_item` 的 hitbox 是实体，能逐族精确表达 —— 先问家具清单。
        family = furniture.family_of(block_id)
        if family is not None:
            return KIND_FURNITURE, (
                f"{family['name']} 族：家具 hitbox 表达碰撞箱 {family['truth']}"
                f"（方块形态只能借池近似）")
        # 剩余异形（灯笼/罐/基座/梯子/抽屉柜等）：CE 没有对应行为，
        # 但**不能留空洞**。按纤细装饰近似：保留真实贴图/亮度/音效/工具属性，
        # 碰撞借纤细池（不阻挡通行），逐 ID 在报告里说明近似方式。
        return KIND_STATIC, f"{cls or '未知类'}：CE 无对应行为，按纤细装饰近似"
    if tier == block_props.TIER_NONE:
        return KIND_PLANT, "无碰撞装饰，挂在植物视觉状态池上"
    # 满格：按渲染层决定借哪个满格池。
    if p is not None and p.render_layer != "SOLID":
        return KIND_CUTOUT, f"满格 + {p.render_layer} 渲染，借不染色的树叶池"
    return KIND_SOLID, "满格立方体"


# ---------------------------------------------------------------- 模型几何
#
# ## 为什么不能一律 cube_all
#
# 对真·立方体（木板、砖、矿石）cube_all 没问题，但对另外三类是明确的错误：
#
#   1. **地表覆盖方块**（`*_gloomsculk`）：上游是 `cube_bottom_top`，顶面用自己的 `_top`，
#      底面借下界岩/幽匿的贴图，只有侧面是 `_side`。压成 cube_all 等于把竖向渐变的侧面图
#      铺满顶面 —— 而它们是 gloomwood 群系 80% 的地表（BiomeSurface.java:273）。
#   2. **原木**：上游是 `cube_column`（侧面 `_log_side`，端面 `_log_top`）。压成 cube_all 后
#      原木两端也是年轮侧面图，站远看整片木头都是竖纹。
#   3. **石笋/钟乳石**：上游是 8 个 size 变体、真·尖刺几何（`stalactite_0..7`，2px~16px）。
#      压成 cube_all 就是一根 1x1x1 的实心方块柱，完全不是尖刺。
#
# 几何一律照抄上游，不自造：
#   * 有上游同名模型的方块走 `path:`，不生成（见 block_yaml）。
#   * 没有的，这里给出 CE `generation` 的 parent + textures，贴图槽位名与上游 parent 模型一致。

# 顶/底/侧三贴图的地表覆盖方块 -> 底面贴图。
# 出处：`NetherModels.terrainCover()` / `terrainCoverVariants()`（blocks/NetherModels.java:181-217）
TERRAIN_COVER_BOTTOM = {
    "bleached_gloomsculk": "minecraft:block/sculk",
    "molten_gloomsculk": "minecraft:block/sculk",
    "veined_gloomsculk": "minecraft:block/netherrack",
}

# 侧面/端面贴图名与方块名对不上、必须显式给出的柱状方块：(side, end)。
#   willow_log          —— WillowMaterial.java:51 `WeightedLog(true, {1,1}, willow_bark, willow_log_top)`；
#                          注释明写「willow 的原木侧面用 willow_bark，没有 willow_log_side」。
#   cincinnasite_pillar —— models/block/cincinnasite_pillar_middle.json：north/east/south/west 是
#                          `cincinnasite_pillar_middle`，up/down 是 `cincinnasite_plate_up`。
COLUMN_TEXTURES = {
    "willow_log": ("willow_bark", "willow_log_top"),
    "cincinnasite_pillar": ("cincinnasite_pillar_middle", "cincinnasite_plate_up"),
}

# 石笋/钟乳石的代表 size。
#
# 上游 blockstate 有 `size=0..7` 八个变体，几何是 `stalactite_0..7`（宽度 2px → 16px 逐级变粗）。
# CraftEngine 的 `auto_state` 表达不了 `size` 属性；而项目自身的世界生成
# （TerrainFeaturePopulator.spike）是逐格放置同一个方块、不写 size 属性，
# 所以把方块拆成 8 个状态也没有消费者。这里取**中间那档**当固定外观：
# size=3 是 8px 宽（半格），读起来是尖刺而不是墙，也不是细到看不见的 2px。
SPIKE_SIZE = 3

# 上游 `block_properties.json` 的 SoundType -> 原版音效事件前缀。
#
# 键集合必须覆盖上游导出的每一个 SoundType：没有条目就会掉进 `SOUND_FALLBACK`
# （netherrack = 石头家族），玩家听到的就是「花草木头都是挖石头的声音」。
SOUND_EVENTS = {
    "WOOD": "minecraft:block.wood",
    "NETHER_WOOD": "minecraft:block.nether_wood",
    "STEM": "minecraft:block.stem",
    "STONE": "minecraft:block.stone",
    "IRON": "minecraft:block.metal",
    "METAL": "minecraft:block.metal",
    "NETHER_ORE": "minecraft:block.nether_ore",
    "NETHER_BRICKS": "minecraft:block.nether_bricks",
    "NETHERRACK": "minecraft:block.netherrack",
    "BONE_BLOCK": "minecraft:block.bone_block",
    "BASALT": "minecraft:block.basalt",
    "SCULK": "minecraft:block.sculk",
    "SAND": "minecraft:block.sand",
    "GRASS": "minecraft:block.grass",
    "SHROOMLIGHT": "minecraft:block.shroomlight",
    "GLASS": "minecraft:block.glass",
    # 原版 `SoundType.AMETHYST_CLUSTER` 用的是簇自己的事件，不是紫水晶块的那组。
    "AMETHYST_CLUSTER": "minecraft:block.amethyst_cluster",
    "NETHERITE_BLOCK": "minecraft:block.netherite_block",
    "SLIME_BLOCK": "minecraft:block.slime_block",
    # 书架的五个事件都在（`block.chiseled_bookshelf.{break,step,place,hit,fall}`）。
    "CHISELED_BOOKSHELF": "minecraft:block.chiseled_bookshelf",
    "VINE": "minecraft:block.vine",
    "WOOL": "minecraft:block.wool",
    "FUNGUS": "minecraft:block.fungus",
    # `SHELF_MUSHROOM` 刻意不指向自己的族：原版没有 `block.shelf_mushroom.hit`
    # （只有 break/step/place/fall 加一个 bounce），按族名拼会拼出一个不存在的事件。
    "SHELF_MUSHROOM": "minecraft:block.wood",
}
# 上游有个条目写成了 `path:block.netherrack.break`（属性文件里的脏值），单独兜底。
SOUND_FALLBACK = "minecraft:block.netherrack"

# 五个事件**不属于同一个音效族**的 SoundType，逐事件给真值。
#
# 原版 `SoundType.CROP` 就是这种：破坏/踩中是 `block.crop.break`，放置是
# `item.crop.plant`（`SoundEvents.CROP_PLANTED`），踩踏与落地走草地音。
# 单靠 `<族>.<事件>` 拼出来的 `block.crop.step` / `block.crop.place` 在原版
# `sounds.json` 里根本不存在 —— 客户端只会静默变成没声音。
SOUND_EVENT_OVERRIDES: dict[str, dict[str, str]] = {
    "CROP": {
        "break": "minecraft:block.crop.break",
        "step": "minecraft:block.grass.step",
        "place": "minecraft:item.crop.plant",
        "hit": "minecraft:block.crop.break",
        "fall": "minecraft:block.grass.fall",
    },
}

# 「这是玻璃」的**结构判据**：上游类名，不是 id 后缀。
#
# 上游把这两种玻璃挂在 `GlassBlockTrait.glass()` 上（渲染层/透光/生成规则），但它们的
# BlockBehaviour 属性是整块抄来的：`BlockObsidianGlass` 抄 `Blocks.OBSIDIAN`，
# `BaseGlassBlock` 的 framed 变体抄 `CINCINNASITE_BLOCK`。于是 SoundType 跟着被抄成
# STONE / IRON —— 玻璃的**材质**由类和 trait 决定，不由那次属性拷贝决定。
GLASS_CLASSES = frozenset({"BaseGlassBlock", "BlockObsidianGlass"})
GLASS_SOUND_FAMILY = "GLASS"

# 可去皮的方块 -> 去皮后的方块。
#
# 上游用 `NetherWoodenMaterial.getStrippedLog()/getStrippedBark()` 建立配对
# （blocks/complex/NetherWoodenMaterial.java:215-221），命名规律固定：
#   <木>_log  -> <木>_stripped_log
#   <木>_bark -> <木>_stripped_bark
# CraftEngine 原生支持这个行为：`behavior: {type: strippable_block, stripped: <目标>}`，
# 默认工具就是 `#minecraft:axes`，默认音效 `minecraft:item.axe.strip`（官方 docs/blocks.md）。
STRIPPABLE_SUFFIXES = (
    ("_log", "_stripped_log"),
    ("_bark", "_stripped_bark"),
    ("_wood", "_stripped_wood"),
)


def strippable_target(name: str, available: set[str]) -> str | None:
    """返回这个方块去皮后的目标名；不是可去皮方块时返回 None。"""
    for plain, stripped in STRIPPABLE_SUFFIXES:
        if name.endswith(plain) and not name.endswith(stripped):
            target = name[: -len(plain)] + stripped
            if target in available:
                return target
    return None


# ---------------------------------------------------------------- 耕地（锄头）
#
# ## 上游怎么定义「可耕土壤」
#
# 上游**没有**任何 tillable 注册表：26.3 把锄地的 `useOn` 变成了数据驱动的
# `minecraft:block_transformer` 物品组件（见 items/NetherHoe.java:15-17 的注释），
# 而 BetterNether 自己没有往锄头上加任何条目 —— 也就是说**上游的土壤方块本来就不能耕**。
#
# 所以「哪些方块算土壤」的唯一权威来源是它 datagen 导出的标签文件：
#   src/main/generated/data/wover/tags/block/surfaces/soil.json
SOIL_TAG = "src/main/generated/data/wover/tags/block/surfaces/soil.json"

# 土壤被耕成什么。上游 NetherTerrainBlocks.java:187-199 的 FARMLAND 是一个
# 普通 Block（blocks/BlockFarmland.java:5 `extends Block`），没有 moisture 干湿状态。
TILL_TARGET = "farmland"

# 耕作音效：上游由原版锄头的 block_transformer 触发，声音就是原版 `item.hoe.till`。
TILL_SOUND = "minecraft:item.hoe.till"


def load_soil_blocks(upstream: Path) -> set[str]:
    """读上游导出的 `#c:soil` 标签，返回本移植命名空间下的方块 id 集合。

    文件缺失时返回空集并提示：宁可「这次不生成耕地」也不要靠猜 id 把一堆无关方块变成可耕。
    """
    source = upstream / SOIL_TAG
    if not source.is_file():
        print(f"[耕地] 找不到上游土壤标签 {SOIL_TAG}，本次不生成任何可耕地块。")
        return set()
    try:
        values = json.loads(source.read_text(encoding="utf-8")).get("values", [])
    except (json.JSONDecodeError, AttributeError):
        print(f"[耕地] 无法解析 {SOIL_TAG}，本次不生成任何可耕地块。")
        return set()
    # 只保留 `betternether:` 前缀的普通条目；`#tag` 形式的引用上游没用到，出现也要忽略。
    return {rewrite_id(v) for v in values if isinstance(v, str) and v.startswith(UPSTREAM_PREFIX)}


def till_events_yaml(indent: str = "    ") -> str:
    """可耕土壤的 `events:` 段：手持锄头右键 → 变成本包的耕地。

      * `match_item` 只做**字符串 id 比对**（core/.../condition/MatchItemCondition 的 test()
        拿到 ITEM_IN_HAND 后走 Item.id().asString() + MiscUtils.matchRegex），**不支持 `#标签`**，
        所以这里用 `regex: true` + `.*_hoe` 覆盖全部锄头。
      * `transform_block` 会把源状态的全部属性按名字搬到目标方块上。
      * `damage_item` 复刻锄地消耗 1 点耐久；`swing_hand` / `play_sound` 复刻原版手感；
        `cancel_event` 让 CE 接管这次交互，避免原版锄头逻辑再跑一遍。
    """
    return (
        f"{indent}events:\n"
        f"{indent}  - on: right_click\n"
        f"{indent}    conditions:\n"
        f"{indent}      - type: match_item\n"
        f"{indent}        id: '.*_hoe'\n"
        f"{indent}        regex: true\n"
        f"{indent}    functions:\n"
        f"{indent}      - type: transform_block\n"
        f"{indent}        block: {NAMESPACE}:{TILL_TARGET}\n"
        f"{indent}      - type: play_sound\n"
        f"{indent}        sound: {TILL_SOUND}\n"
        f"{indent}        source: block\n"
        f"{indent}        x: <arg:position.x>\n"
        f"{indent}        y: <arg:position.y>\n"
        f"{indent}        z: <arg:position.z>\n"
        f"{indent}      - type: swing_hand\n"
        f"{indent}        hand: main_hand\n"
        f"{indent}      - type: damage_item\n"
        f"{indent}        amount: 1\n"
        f"{indent}        slot: mainhand\n"
        f"{indent}      - type: cancel_event\n"
    )


def texture_ref(value: str) -> str:
    """把一张贴图名归一化成**带命名空间的完整引用**。

    ## 为什么必须有这一层

    上游模型里的贴图字段有三种写法：

      * `betternether:block/foo`  —— 本包贴图，已带命名空间
      * `minecraft:block/bar`     —— 显式原版贴图，已带命名空间
      * `block/baz`               —— 裸路径，按**原版**命名空间解析（MC 的默认规则）

    如果拿到值后一律套 `f"{NAMESPACE}:block/{value}"`，前两种就会被拼成
    `betternether:block/minecraft:block/bar` 这种双重命名空间 —— 资源不存在，
    CE 判定该方块没有外观。修复前正是这个 bug，影响 51 处。

    规则：
      * 已含命名空间（`xxx:block/...`）→ 原样返回，**绝不改写**
      * 裸 `block/xxx` → 补成 `minecraft:block/xxx`（上游裸路径全部是 vanilla 贴图）
      * 其它无 `block/` 前缀的裸名 → 按本包命名空间补全
    """
    text = value.strip()
    # 先剥掉历史遗留的重复命名空间：`minecraft:block/minecraft:block/x` -> `minecraft:block/x`。
    # 保证函数幂等 —— 调用链上被套两次前缀也不会继续累积。
    while "block/minecraft:block/" in text:
        text = text.replace("block/minecraft:block/", "block/")
    head, sep, rest = text.partition(":")
    if sep and rest.startswith("block/"):
        return text
    if text.startswith("block/"):
        return "minecraft:" + text
    return f"{NAMESPACE}:block/{text}"


def texture_is_vanilla(value: str) -> bool:
    """这个贴图引用是否指向原版（`minecraft:`）命名空间。"""
    return value.startswith("minecraft:")


def merged_model_textures(stem: str, model_path) -> dict[str, str]:
    """沿 `parent` 链把模型的 `textures` 合并成一张表（子模型覆盖父模型）。

    只读模型的 `textures` 段、不读 `elements`，是刻意的：CE 的视觉外观最终只由
    `textures` 里每个槽位解析到的图决定，`elements` 引用的是槽位名而不是贴图。
    合并后交给 `transparency.model_texture_refs` 做槽位链解析，才能得到「模型**实际
    用到**的贴图」—— 父模型里的 `particle`/`all` 槽位会被子模型的同名槽位覆盖，不会被
    误算成两套。

    `model_path(stem)` 由调用方提供（只在本文件 `main()` 里知道模型搜索目录）。
    parent 链可能在原版模型处断开（`block/cube_all` 不在上游），也可能成环，循环里
    用 `seen` 保底，绝不无限展开。
    """
    merged: dict[str, str] = {}
    seen: set[str] = set()
    current: str | None = stem
    while current and current not in seen:
        seen.add(current)
        path = model_path(current)
        if path is None:
            break
        payload = transparency.load_model(path)
        if payload is None:
            break
        textures = payload.get("textures")
        if isinstance(textures, dict):
            for slot, value in textures.items():
                # 先遇到的是子模型；同名槽位以子模型为准，父模型只补空缺。
                merged.setdefault(slot, value)
        parent = payload.get("parent")
        current = (parent.split("/", 1)[1]
                   if isinstance(parent, str) and "/" in parent else None)
    return merged


def scan_model_translucency(stem: str, model_path, assets_root: Path,
                            cache=None) -> transparency.ModelTextures:
    """扫描一个模型的**实际使用贴图**是否带 alpha（等价于 `transparency.scan_model_textures`，
    但先把 parent 链合并好）。

    `assets_root` 是上游资源包 `assets/` 目录。原版贴图（`minecraft:`）不在上游仓库里，
    会落进 `missing` —— 调用方必须用 {@link conservative_translucent} 区分「本包贴图
    真缺失」与「原版贴图只是不在本地」。
    """
    model = {"textures": merged_model_textures(stem, model_path)}
    return transparency.scan_model_textures(model, assets_root, cache)


def missing_local_textures(result: transparency.ModelTextures) -> tuple[str, ...]:
    """`missing` 里属于本包（非 `minecraft:`）的贴图。

    原版贴图只是没有随上游仓库提供，不能因此把一个用原版石头的实心方块判成不可验证；
    本包贴图缺失才是真的资源残缺，必须暴露出来而不是默默当不透明。
    """
    return tuple(ref for ref in result.missing if not texture_is_vanilla(ref))


def conservative_translucent(result: transparency.ModelTextures) -> bool:
    """这个满格方块是否**不能**按不透明（SOLID）渲染。

    两条依据缺一不可：
      * 模型实际用到的贴图里至少一张存在 alpha < 255（`has_translucency`）；
      * 本包贴图有缺失（`missing_local_textures`）时不得当作不透明 —— 看不到图就
        不能断言它不透明，否则一张列在缺失清单里的镂空贴图会被渲染成实心色块。

    原版贴图是否透明**没有**依据：它们不在上游资源里，这里不猜，交给上游 `renderLayer`
    已经给出的结论（`classify` 里 `render_layer != SOLID` 仍会先把它判成 cutout）。
    """
    return result.has_translucency or bool(missing_local_textures(result))


def sound_family(p: block_props.BlockProps | None) -> str:
    """这个方块该用哪个 SoundType 家族发声。

    上游 `sound` 字段是首选真值，唯一的例外是玻璃类：它们的 SoundType 是从
    「黑曜石 / 铌钙石金属块」整块属性拷贝过来的副产品（见 `GLASS_CLASSES`），
    所以按类名改回玻璃族 —— 判断「是不是玻璃」不靠 id 后缀。
    """
    if p is None:
        return ""
    if p.cls in GLASS_CLASSES:
        return GLASS_SOUND_FAMILY
    return p.sound


def sound_events(sound_type: str | None) -> list[tuple[str, str]]:
    """SoundType -> 五个音效事件（break/step/place/hit/fall）。"""
    # 事件不统一的族先查逐事件表（见 `SOUND_EVENT_OVERRIDES`），其余按 `<族>.<事件>` 拼。
    overrides = SOUND_EVENT_OVERRIDES.get(sound_type or "")
    if overrides:
        return [(event, overrides[event]) for event in ("break", "step", "place", "hit", "fall")]
    base = SOUND_EVENTS.get(sound_type or "", SOUND_FALLBACK)
    return [(event, f"{base}.{event}") for event in ("break", "step", "place", "hit", "fall")]


# ---------------------------------------------------------------- 硬度对齐
#
# 上游 `destroyTime` 直接搬运会搬出「空手挖太慢」，但**不能**无差别下调：
# 木材 2.0、黑曜石 50、藤蔓 0.2、仙人掌 0.4、紫水晶簇 1.5 都与原版同类一致。
# 只有数值明显来自「整块属性拷贝」时才按原版同类对齐。
#
# 判据是**上游怎么得到这个数的**，不是手感猜测：
#
#   * 玻璃类 —— `replacePropertiesWithCopy(Blocks.OBSIDIAN)`（obsidian_glass /
#     blue_obsidian_glass）和 `replacePropertiesWithCopy(CINCINNASITE_BLOCK)`
#     （quartz_glass_framed 全系列）把 destroyTime 一起抄成了 50 / 3。玻璃的材质由
#     `GlassBlockTrait` 决定，原版 glass 是 0.3。
#
# 刻意**不动**的取值（每条都能指回一个原版同类方块，或能指回上游的显式设计）：
#   木材/菌柄 2.0（原版原木 2.0）、黑曜石系 50（原版黑曜石 50）、
#   藤蔓 0.2（原版 vine 0.2）、仙人掌 0.4（原版 cactus 0.4）、
#   紫水晶簇 1.5（原版 amethyst_cluster 1.5）、矿石 3.0（原版下界石英矿 3.0）、
#   orange_mushroom 0.5（上游注释写明是拿 `destroyTime(0.5F)` 覆盖预设 `instabreak()`
#     的有意选择，不是拷贝事故）、巨型蘑菇 1.0/2.0（上游显式 `.strength(1)` /
#     `mushroomStem()` 预设；原版 mushroom_block 是 0.2，但那是设计取值，已由需求方
#     裁决保持不动）。
VANILLA_GLASS_HARDNESS = 0.3


def hardness_adjustment(p: block_props.BlockProps | None) -> tuple[float, str] | None:
    """上游 destroyTime 偏离「最接近的原版方块」时返回 `(新值, 依据)`；否则 None。"""
    if p is None:
        return None
    if p.cls in GLASS_CLASSES and p.hardness != VANILLA_GLASS_HARDNESS:
        return VANILLA_GLASS_HARDNESS, (
            f"minecraft:glass {VANILLA_GLASS_HARDNESS:g}"
            "（上游硬度来自整块属性拷贝，玻璃材质见 GlassBlockTrait）")
    return None


def aligned_hardness(p: block_props.BlockProps | None) -> float:
    """落进配置的硬度：上游值，或按原版同类对齐后的值。"""
    adjustment = hardness_adjustment(p)
    if adjustment is not None:
        return adjustment[0]
    return p.hardness if p is not None else 0.0


def requires_correct_tool(p: block_props.BlockProps | None) -> bool:
    """上游 `reqTool`，**玻璃类除外**。

    玻璃类的 `requiresCorrectToolForDrops` 是从黑曜石 / 铌钙石块整块拷贝来的
    （`replacePropertiesWithCopy`），与原版玻璃「任何工具都能挖」相反；硬度已按原版
    玻璃对齐到 0.3，再留着这道门槛等于硬度白降。
    """
    return bool(p is not None and p.requires_tool and p.cls not in GLASS_CLASSES)


def spike_generation(name: str, models_dir: Path) -> dict | None:
    """石笋/钟乳石的 `generation`：直接复用上游尖刺几何。

    上游 `<name>_<size>.json` 的形状是「parent = `betternether:block/stalactite_<size>`
    + 一组 textures 覆盖」，几何本体在共享的 `stalactite_<size>` 里（`copy_assets` 会把它一起
    释放进包）。所以这里不改几何，只把那个共享 parent 与它声明的贴图槽位搬过来 ——
    手写 `elements` 是不可接受的：上游注释写明这些盒子是 Paulevs 在 Cubik Studio 里设计的。
    """
    for size in (SPIKE_SIZE, 2, 4, 1, 5, 0, 6, 7):
        path = models_dir / f"{name}_{size}.json"
        if not path.is_file():
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        parent = payload.get("parent")
        textures = payload.get("textures")
        if not isinstance(parent, str) or not isinstance(textures, dict):
            continue
        # `copy_assets` 只给 parent 补 `minecraft:` 前缀，贴图里的裸 `block/xxx` 靠 MC 的默认
        # 命名空间解析。这里显式补上，免得门禁/下游要猜。
        return {
            "parent": parent if ":" in parent else f"minecraft:{parent}",
            "textures": {
                slot: value if ":" in value else f"minecraft:{value}"
                for slot, value in textures.items()
                if isinstance(value, str)
            },
            "size": size,
        }
    return None


def generation_texture_of(name: str, textures: list[str],
                          available: set[str]) -> str | None:
    """为几何推断选模型贴图；地表覆盖优先取本地方块侧面贴图。"""
    usable = [value for value in textures
              if value.startswith("minecraft:") or value in available]
    side = f"{name}_side"
    if name in TERRAIN_COVER_BOTTOM:
        if side in available:
            return side
        if f"betternether:block/{side}" in textures:
            return side
    return usable[0] if usable else None


def generation_of(name: str, texture: str, available: set[str],
                  models_dir: Path) -> dict:
    """返回这个方块该用的 CE `generation`（parent + textures）。

    规则按「上游几何长什么样」排序，命中即返回，最后的 cube_all 才是真·立方体：
      1. 石笋/钟乳石        -> 复用上游 `stalactite_<size>` 尖刺几何
      2. COLUMN_TEXTURES    -> 贴图名与方块名对不上的柱状方块
      3. 地表覆盖方块       -> cube_bottom_top，底面借别的方块贴图
      4. `<name>_bottom` 存在 -> cube_bottom_top（giant_lucis 就是这一类）
      5. `<name>_log` + `_top` -> cube_column（原木侧面/端面分开）
      6. 其余               -> cube_all
    """
    if name.endswith("_stalactite"):
        spike = spike_generation(name, models_dir)
        if spike is not None:
            return spike

    if name in COLUMN_TEXTURES:
        side, end = COLUMN_TEXTURES[name]
        return {
            "parent": "minecraft:block/cube_column",
            "textures": {"side": texture_ref(side), "end": texture_ref(end)},
        }

    # 只在「选中的贴图是侧面图」时才有端面可谈。树皮（`*_bark`）的六面就是同一张侧面图，
    # 它的 end 在上游是 null（WeightedBark(..., null)），必须留在 cube_all —— 少这一条判断
    # 会把 22 个树皮全部错判成柱状，端面变成不存在的 `_log_top`。
    if texture.endswith("_side"):
        end = texture[: -len("_side")] + "_top"
        if end in available:
            if name in TERRAIN_COVER_BOTTOM:
                return {
                    "parent": "minecraft:block/cube_bottom_top",
                    "textures": {
                        "top": texture_ref(end),
                        "side": texture_ref(texture),
                        "bottom": TERRAIN_COVER_BOTTOM[name],
                    },
                }
            bottom = f"{name}_bottom"
            if bottom in available:
                return {
                    "parent": "minecraft:block/cube_bottom_top",
                    "textures": {
                        "top": texture_ref(end),
                        "side": texture_ref(texture),
                        "bottom": texture_ref(bottom),
                    },
                }
            if name.endswith("_log"):
                return {
                    "parent": "minecraft:block/cube_column",
                    "textures": {
                        "side": texture_ref(texture),
                        "end": texture_ref(end),
                    },
                }

    return {
        "parent": "minecraft:block/cube_all",
        "textures": {"all": texture_ref(texture)},
    }


def write_text_file(path: Path, text: str) -> None:
    """写 UTF-8 文本，自动建父目录。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def item_yaml(block_id: str, category: str, model_path: str | None = None,
              tags: tuple[str, ...] = ()) -> str:
    """一段 block_item 定义。

    ## 物品标签（`settings.tags`）
    `tags` 来自上游 `data/betternether/tags/item/**`（见 `load_registry_tags`）。
    必须写在**物品**段：CE 解析配方里的 `#betternether:<wood>_logs` 走
    `ItemManager.itemIdsByTag`（物品注册表），拿不到就报 `invalid_tag` 并跳过整条配方 ——
    方块段 `settings.tags` 登记的是方块注册表，救不了这条配方。

    ## items 段**永远只写 `path`，绝不写 `generation`**

    `generation` 的语义是「让 CraftEngine 生成模型」。模型只需要存在**一次**，而且属于方块：
    要么由 blocks 段的 `state.model` 生成，要么在构建期就写成真实 JSON。
    items 只是复用那个模型作为物品图标。

    如果 items 也写 `generation`，CE 会对同一个 path 生成两次：blocks 先生成，items 再生成时
    文件已存在，于是每个这样的方块刷一条「无法生成模型，因为模型文件已存在」。

    透明共享状态（`transparent: true`）的方块**不例外**：它的方块段不写 model，模型改由
    blocks.py 把 `generation` 规格登记进 `reports/synthesized-models.json`，再由
    `assets.py` 在资源包阶段写成真实 JSON。这条约束因此没有出口 ——
    函数签名里根本没有 `generation` 参数。
    """
    name = block_id.split(":", 1)[1]
    # `model_path` 由调用方给出：上游有 `item/<name>.json` 时用它（门、墙、栅栏、告示牌、
    # 窗格、藤蔓的物品图标都只在 item/ 下，没有 block/<name>.json）。
    settings = ""
    if tags:
        settings = "    settings:\n      tags:\n" + "".join(f"        - {tag}\n" for tag in tags)
    return (
        f"  {block_id}:\n"
        f"    category: {category}\n"
        f"{settings}"
        f"    data:\n"
        f"      item_name: '<!i><white><lang:{lang_key_of(name)}></white>'\n"
        f"    model:\n"
        f"      path: {model_path or f'{NAMESPACE}:block/{name}'}\n"
        f"    behavior:\n"
        f"      type: block_item\n"
        f"      block: {block_id}\n"
    )


def state_body(block_id: str, generation: dict | None, has_model: bool,
               kind: str, pool: str | PinnedState | None,
               properties: tuple[str, ...] = (),
               variant_models: tuple[tuple[str, int, int, bool], ...] = ()) -> str:
    """`state:` 段：视觉状态池 + 模型来源。

    `pool` 由调用方按 {@link plant_slot_plan} / 满格池算好：**每个方块都借一个真实的
    原版方块状态**，碰撞箱随载体，模型写在自己的 `model.path` 上。
    这里不再有 `transparent: true` + `entity_renderer` 的分支 —— 那条路是每格一个
    展示实体，用户已明确要求去掉。

    `pool` 是 {@link PinnedState} 时写 `state: minecraft:xxx[...]`（写死原版状态，
    不消耗 auto_state 额度）；否则写 `auto_state: <池名>`。

    `properties` 是这个方块的支撑行为**要求**声明的 CE 方块状态属性（目前只会是
    `facing`）。CE 的 `directional_attached_block` 不声明 `facing` 就直接报
    「该行为需要定义方块状态属性 facing」并让整条行为失效，所以这里必须写出来。
    """
    name = block_id.split(":", 1)[1]
    pinned = pool if isinstance(pool, PinnedState) else None
    if kind in (KIND_PLANT, KIND_STATIC):
        # KIND_STATIC 是「CE 无行为」的异形装饰（灯笼/罐/基座/梯子/抽屉柜/告示牌…）。
        # 借纤细池：不阻挡通行、不会把结构通道封死，是现有池里最接近这些
        # 小体积装饰的碰撞近似。亮度/音效/工具属性仍然按上游真值写。
        if pinned is not None:
            head = f"      state: {pinned.state}\n"
        else:
            head = f"      auto_state: {pool}\n"
    elif kind == KIND_CUTOUT:
        head = f"      auto_state: {pool or STATE_POOL_CUTOUT}\n"
    else:
        head = f"      auto_state: {STATE_POOL_SOLID}\n"

    if properties:
        head += _properties_block(properties, "      ")

    # 上游**没有** `block/<方块名>.json`、只在 blockstate 里列了多个变体模型时
    # （`gloomgrass_1..6`、`pale_gloomgrass_1..6` 这类随机外观的草），必须把变体列表原样
    # 写出去。以前这里一律写 `model: path: block/<方块名>` —— 那个名字在上游根本不存在，
    # CE 就拿单张贴图兜底生成一个 `cube_all` 模型，于是十字植物被渲染成一个**立方体**
    # （用户截图里贴地的扁平色块）。变体模型其实都已经释放进资源包，直接引用即可。
    if len(variant_models) > 1:
        return head + _models_block(list(variant_models), "      ")

    if has_model:
        return head + f"      model:\n        path: {NAMESPACE}:block/{name}\n"
    if generation is None:
        raise SystemExit(f"{name}: 没有上游模型却没有 generation 规格，拒绝写出一个无外观的方块。")
    # 用户要求：**不使用 CE 运行期生成模型，直接用现有 JSON**。
    # 所以这里只写 `path`，绝不写 `generation:` —— 那份模型已在构建期由
    # `reports/synthesized-models.json` 交给 assets.py 落成真实 JSON 文件。
    return head + f"      model:\n        path: {NAMESPACE}:block/{name}\n"


def shape_states_yaml(behavior: str, textures_for, model_for=None, name: str = "") -> str:
    """生成异形族的 `states:` 段（属性 + 外观 + 变体）。

    `textures_for(suffix)` 由调用方提供：给定上游模型名后缀（`""` / `_top` / `_inner` …），
    返回该模型的贴图槽位字典 `{slot: "betternether:block/xxx"}`。取不到时返回空字典，
    对应外观会被跳过（宁可少一个朝向，也不写出一个贴图悬空的外观）。
    """
    props = SHAPE_PROPERTIES[behavior]
    appearances = SHAPE_APPEARANCES[behavior]
    variants = SHAPE_VARIANTS[behavior]

    prop_lines = "".join(
        f"        {name}:\n          type: {ce_type}\n          default: {default}\n"
        for name, ce_type, default in props
    )

    app_lines: list[str] = []
    built: set[str] = set()
    for app_name, (parent, suffix) in appearances.items():
        # 上游已有这块的模型（如 willow_stairs_inner.json）就直接引用，不再生成。
        existing = model_for(app_name, suffix) if model_for else None
        if existing:
            app_lines.append(
                f"        {app_name}:\n"
                f"          auto_state: {STATE_POOL_SOLID}\n"
                f"          model:\n"
                f"            path: {existing}\n"
            )
            built.add(app_name)
            continue
        textures = textures_for(suffix)
        if not textures:
            continue
        # cube_all（双层半砖）只认 `all`：把侧面贴图铺满六面。
        if parent.endswith("cube_all"):
            side = textures.get("side") or textures.get("all") or next(iter(textures.values()))
            textures = {"all": side}
        # 原版 `fence_post` 只声明了一个贴图槽 `texture`。上游墙/栅栏模型的槽位名
        # 是 `wall`/`side`/`pane`，直接沿用会让父模型的 `texture` 槽悬空 —— 方块能放，
        # 但渲染成紫黑格。这里按父模型的槽位规范重新映射。
        elif parent.endswith(("fence_post", "fence_side")):
            first = (textures.get("texture") or textures.get("wall") or textures.get("side")
                     or textures.get("pane") or next(iter(textures.values())))
            textures = {"texture": first}
        tex_lines = "".join(f"                {slot}: {value}\n"
                            for slot, value in textures.items())
        app_lines.append(
            f"        {app_name}:\n"
            f"          auto_state: {STATE_POOL_SOLID}\n"
            f"          model:\n"
            # 每个方块各自的路径：共用 `block/<behavior>_<app>` 会让 CE 报
            # 「多个配置试图用相同路径生成不同模型」。
            # 加 `_ce_` 段：不会与上游同名模型文件（如 willow_slab_top.json）撞车。
            f"            path: {NAMESPACE}:block/{name + '_ce' if name else behavior}_{app_name}\n"
            f"            generation:\n"
            f"              parent: {parent}\n"
            f"              textures:\n"
            f"{tex_lines}"
        )
        built.add(app_name)

    if not app_lines:
        return ""

    variant_lines = ""
    if variants is None:
        # fence_block：四个方向各有 true/false，CE 的变体是精确键匹配，
        # 所以必须把 16 种组合逐条列出来，全部指向同一个 post 外观。
        combos: list[str] = [""]
        for prop in ("north", "east", "south", "west"):
            combos = [f"{c},{prop}={v}" if c else f"{prop}={v}"
                      for c in combos for v in ("false", "true")]
        variant_lines = "".join(
            f"        {key.lstrip(',')}:\n          appearance: post\n"
            for key in combos
        )
    else:
        variant_lines = "".join(
            f"        {key}:\n          appearance: {app}\n"
            for key, app in variants if app in built
        )

    return (
        "    states:\n"
        "      properties:\n"
        f"{prop_lines}"
        "      appearances:\n"
        + "".join(app_lines)
        + "      variants:\n"
        + variant_lines
    )


def settings_yaml(block_id: str, p: block_props.BlockProps | None,
                  tools: frozenset[str], break_power: int,
                  flammable: tuple[int, int] | None,
                  extra_tags: list[str], kind: str) -> str:
    """`settings:` 段 —— 全部数值来自上游属性，缺属性时省略而不是猜。

    三个例外，都是上游「整块属性拷贝」的副产品，按原版同类改写而不是照抄：
      * `hardness` —— 见 `hardness_adjustment`（玻璃类对齐到原版 glass）；
      * 玻璃类的**硬门槛**（`require_correct_tools` / `required_break_power`）—— 上游从
        黑曜石 / 铌钙石块抄来的「必须钻石镐」；原版玻璃任何工具都能挖。
        `correct_tools`（挖得更快的工具族）与 `resistance`（防爆玻璃）都**保持上游值**：
        前者是效率提示不是门槛，后者是有意设计。
    """
    lines: list[str] = []
    if p is not None:
        lines.append(f"        hardness: {aligned_hardness(p):g}")
        lines.append(f"        resistance: {p.resistance:g}")
        lines.append(f"        instrument: {p.instrument}")
        lines.append(f"        map_color: {p.map_color}")
        lines.append(f"        push_reaction: {p.push_reaction}")
        lines.append(f"        can_occlude: {'true' if p.can_occlude else 'false'}")
        lines.append(f"        replaceable: {'true' if p.replaceable else 'false'}")
        # 熔岩能否点燃该方块（上游 `ignitedByLava`）。CE 的 `burnable` 就是这个语义。
        if p.ignited_by_lava:
            lines.append("        burnable: true")
        if p.luminance:
            lines.append(f"        luminance: {p.luminance}")
        # 不挡光的方块：光照衰减 0，且天空光可以穿过。
        if not p.can_occlude:
            lines.append("        block_light: 0")
            lines.append("        propagate_skylight: true")
        if p.is_suffocating is not None:
            lines.append(f"        is_suffocating: {'true' if p.is_suffocating else 'false'}")
        if p.is_view_blocking is not None:
            lines.append(f"        is_view_blocking: {'true' if p.is_view_blocking else 'false'}")
        if p.is_redstone_conductor is not None:
            lines.append(
                f"        is_redstone_conductor: {'true' if p.is_redstone_conductor else 'false'}")
        # 只有偏离 CE 默认值时才写，避免每个方块都刷三行噪声。
        if abs(p.friction - 0.6) > 1e-6:
            lines.append(f"        friction: {p.friction:g}")
        if abs(p.speed_factor - 1.0) > 1e-6:
            lines.append(f"        speed_factor: {p.speed_factor:g}")
        if abs(p.jump_factor - 1.0) > 1e-6:
            lines.append(f"        jump_factor: {p.jump_factor:g}")

    if flammable:
        spread, burn = flammable
        lines.append(f"        fire_spread_chance: {spread}")
        lines.append(f"        burn_chance: {burn}")

    # 工具标签决定效率；上游 reqTool 才决定错误工具是否允许掉落。
    # CE 即使关闭布尔要求，残留破坏力仍会拒绝空手，故两项必须一起映射。
    #
    # 玻璃类是唯一的例外：它们的 `reqTool` 也是从黑曜石 / 铌钙石块整块拷贝来的
    # （与原版玻璃「任何工具都能挖」相反），而硬度已经按原版玻璃对齐到 0.3 ——
    # 留着这道钻石镐门槛，玩家空手仍然挖不掉，等于硬度白降。所以玻璃类只保留
    # `minecraft:mineable/pickaxe` 作为「镐更快」的效率提示，不做硬门槛。
    requires_tool = requires_correct_tool(p)
    if p is not None:
        lines.append(f"        require_correct_tools: {'true' if requires_tool else 'false'}")
        lines.append(f"        required_break_power: {break_power if requires_tool else 0}")
    # `correct_tools` 与 `require_correct_tools` 是两个字段、两种语义：
    #   * `require_correct_tools` / `required_break_power` = 硬门槛（不对的镐挖不动）；
    #   * `correct_tools` = 「挖得**更快**的工具族」，纯效率提示。
    # 玻璃类只放开前者（上游 `reqTool` 是黑曜石/铌钙石拷贝的副产品），后者仍按上游
    # mineable 族给出 —— 清空会让镐子挖玻璃也不再加速。
    keep_correct_tools = requires_tool or (p is not None and p.cls in GLASS_CLASSES)
    if tools and (p is None or keep_correct_tools):
        if p is None:
            lines.append("        require_correct_tools: true")
            lines.append(f"        required_break_power: {break_power}")
        wanted: list[str] = []
        for family in sorted(tools):
            wanted.extend(block_props.TOOL_FAMILIES[family])
        lines.append("        correct_tools:")
        lines.extend(f"          - {tool}" for tool in wanted)

    tags = [f"minecraft:mineable/{family}" for family in sorted(tools)] + extra_tags
    if tags:
        lines.append("        tags:")
        lines.extend(f"          - {tag}" for tag in tags)

    return (
        "    settings:\n"
        "      template:\n"
        "        - default:settings/solid_1x1x1\n"
        "      merges:\n"
        + "".join(line + "\n" for line in lines)
    )


# ---------------------------------------------------------------- 功能方块（原生 CE 机制）
#
# ## 为什么是 `behavior` / `events`，而不是 Java 侧另开一个空 GUI
#
# CE 26.10-SNAPSHOT 自带三样真实机制，够覆盖上游「工作台 / 桶 / 箱」：
#
#   1. `behavior: {type: simple_storage_block}` —— CE 的方块实体容器。
#      `SimpleStorageBlockBehavior` 实现 `EntityBlock` + `WorldlyContainerHolder`，
#      由 `SimpleStorageBlockEntityController` 承载：内容存进方块实体自定义 NBT
#      （`saveCustomData` / `loadCustomData`），打开的是**原版容器界面**
#      （`onPlayerOpen`），打破时 `onRemove()` 对每个非空槽 `world.dropItemNaturally(...)`
#      再 `inventory.clear()` —— 掉落是 CE 自己做的，所以这里**一行存储/掉落逻辑都不写**。
#      参数取自 `SimpleStorageBlockBehavior$Factory` 字节码实测：`title`（默认
#      `<lang:container.chest>`，走 MiniMessage）、`rows`（默认 1）、`sounds.open` /
#      `sounds.close`、`has_signal` / `allow_input` / `allow_output`（默认都是 true）、
#      `data_key`（默认 `craftengine:simple_storage`）。方块若声明了 `open` 布尔属性，
#      行为会自动拿它做开合状态；本包的容器都是单状态、没有该属性，所以不开合动画。
#
#   2. `events: [{on: right_click, functions: [{type: open_window}]}]` ——
#      `gui_type` 走 `GuiType` 枚举（`ConfigValue.getAsEnum` 用 `toUpperCase(Locale.ROOT)`
#      + `Enum.valueOf`，所以小写 `crafting` 合法）。该枚举只有 ANVIL / CARTOGRAPHY /
#      ENCHANTMENT / GRINDSTONE / LOOM / SMITHING / CRAFTING 七种，`BukkitGuiManager`
#      逐项调用 `LegacyInventoryUtils.openAnvil` / `openWorkbench` / … 打开**原版**界面。
#      **没有 furnace**：因此 `BlockNetherFurnace` / `BlockCincinnasiteForge` 这次不给菜单
#      —— CE 表达不了熔炉，塞个存储箱只会变成假熔炉。
#
#   3. `type: test_flag, flag: interact` —— 走 CE 内置的 AntiGriefLib 判领地交互权限。
#      没装任何防破坏插件时 `AntiGriefLib#test` 的 providers 数组为空、直接返回 true，
#      也就是「默认放行、自动认领地插件」，不会把工作台在干净服上锁死。
FUNCTIONAL_GUI_OPEN_BY_CLASS = {
    "CraftingTableBlock": "crafting",
    # 铁砧的配置是备好的，但本包**没有发布** `BlockCincinnasiteAnvil`
    # （判定为「世界生成与结构都不放置它」，为省纤细池而不发布），
    # 所以游戏里目前没有铁砧菜单；这条要等铁砧真正发布才会生效。
    "BlockCincinnasiteAnvil": "anvil",
}

# 原版容器类 → 容器标题。行数按上游实测：桶/箱是 27 格（3 行），
# 抽屉柜（`BlockEntityChestOfDrawers`：`withSize(27)` + `ChestMenu.threeRows`）同为 3 行。
FUNCTIONAL_STORAGE_BY_CLASS = {
    "BarrelBlock": "<lang:container.barrel>",
    "ChestBlock": "<lang:container.chest>",
    # 抽屉柜上游标题就是方块自己的名字（`getName()` 取 `getDescriptionId()`）。
    "BlockChestOfDrawers": "<lang:block.betternether.chest_of_drawers>",
    # 酿造台：CE **没有**酿造行为（`GuiType` 枚举里没有 brewing，见上面 3 条机制的说明），
    # 但上游它是 3 个药水槽 + 1 个材料槽的方块实体容器。按 1 行（9 格）容器落地：
    # 能真的存放药水/材料、打破时 CE 掉落内容，**不能**酿造 —— 缺的那一项逐块写进报告。
    # 标题用方块自己的译名（`container.brewing` 是**原版**语言键，本包的 lang 门禁只认
    # 上游 en_us 与补充表里的键，写原版键会被门禁判成裸键并拒绝生成）。
    "BNBrewingStand": "<lang:block.betternether.nether_brewing_stand>",
}

#: 容器的行数（`simple_storage_block` 的 `rows`）。默认 3 行 = 27 格，与桶/箱/抽屉柜一致；
#: 酿造台只有 4 个槽位，给 1 行（9 格）已经比上游宽，但 CE 的行数不支持非整行。
FUNCTIONAL_STORAGE_ROWS = {"BNBrewingStand": 1}

# 箱/桶在 CE 里是**单个**方块实体容器：原版「相邻两个箱并成双箱」表达不了，
# 每个箱子各自是 27 格独立容器。这是机制边界，不是配置漏写。


def functional_behavior_entries(cls: str) -> list[list[str]]:
    """功能方块要追加的 `behavior:` 条目（每项是一行一条，不带缩进）。"""
    title = FUNCTIONAL_STORAGE_BY_CLASS.get(cls)
    if not title:
        return []
    return [[
        "type: simple_storage_block",
        f"title: '{title}'",
        f"rows: {FUNCTIONAL_STORAGE_ROWS.get(cls, 3)}",
        "has_signal: true",
        "allow_input: true",
        "allow_output: true",
    ]]


def functional_events_yaml(cls: str, indent: str = "    ") -> str:
    """「右键打开原版界面」的 `events:` 段（工作台 / 铁砧）；其余类返回空串。"""
    gui_type = FUNCTIONAL_GUI_OPEN_BY_CLASS.get(cls)
    if not gui_type:
        return ""
    return (
        f"{indent}events:\n"
        f"{indent}  - on: right_click\n"
        f"{indent}    conditions:\n"
        f"{indent}      - type: test_flag\n"
        f"{indent}        flag: interact\n"
        f"{indent}    functions:\n"
        f"{indent}      - type: open_window\n"
        f"{indent}        gui_type: {gui_type}\n"
        # 工作台/铁砧的右键必须被 CE 完全接管：否则手持方块/食物时原版还会继续放置或进食。
        f"{indent}      - type: cancel_event\n"
    )


def behavior_yaml(behaviors: list[list[str]]) -> str:
    """把若干行为渲染成一段 `behavior:`（单个）或 `behaviors:`（多个）YAML。

    同一个映射里出现两个 `behavior:` key 会让整段配置解析失败，所以多行为必须走
    CE 支持的列表写法（`BukkitBlockManager#createBlockBehavior` 用 `getAsList` 收，
    再包进 `CompositeBlockBehavior`）。
    """
    if not behaviors:
        return ""
    if len(behaviors) == 1:
        return "    behavior:\n" + "".join(f"      {line}\n" for line in behaviors[0])
    lines = "".join(
        f"      - {entry[0]}\n" + "".join(f"        {line}\n" for line in entry[1:])
        for entry in behaviors
    )
    return f"    behaviors:\n{lines}"


def block_yaml(block_id: str, generation: dict | None, has_model: bool,
               kind: str, pool: str | PinnedState | None,
               p: block_props.BlockProps | None,
               tools: frozenset[str], break_power: int,
               flammable: tuple[int, int] | None,
               extra_tags: list[str],
               stripped: str | None = None,
               tillable: bool = False,
               crop_support: bool = False,
               shape_behavior: str | None = None,
               shape_states: str | None = None,
               support_behaviors: list[list[str]] | None = None,
               required_properties: tuple[str, ...] = (),
               variant_models: tuple[tuple[str, int, int, bool], ...] = ()) -> str:
    """一段 block 定义。

    ## 音效
    用官方 `settings.sounds`（docs/blocks.md「音效」一节）写明 break/step/place/hit/fall 五个事件，
    取值来自上游 `block_properties.json` 的 SoundType（玻璃类见 `sound_family` 的类名判据）。

    ## 去皮
    CE 原生支持 `behavior: {type: strippable_block, stripped: <目标>}`（docs/blocks.md）。
    默认工具 `#minecraft:axes`、默认音效 `minecraft:item.axe.strip`，无需额外声明。

    ## 耕地
    `tillable` 为真时追加 `events: on: right_click`，把手持锄头右键变成耕地（见 {@link till_events_yaml}）。
    `crop_support` 为真时给方块挂 `#supports_crops` / `#grows_crops`，让原版作物能种在上面。

    ## 功能方块
    按上游类名挂 CE 原生机制（判定表见 {@link FUNCTIONAL_GUI_OPEN_BY_CLASS} 与
    {@link FUNCTIONAL_STORAGE_BY_CLASS}）：工作台/铁砧用 `events → open_window` 打开原版
    界面，桶/箱用 `behavior → simple_storage_block` 变成真实持久容器。

    ## 异形族与多状态植物
    `shape_behavior` 非空时写 CE 官方行为（stairs_block / slab_block / door_block /
    trapdoor_block），并用 `states` 取代单状态 `state:` —— 这些行为要求方块声明
    属性（facing/half/shape/type/…），没有 `states.properties` 行为无法工作。
    `states_yaml` 同时被多状态植物复用（见 `compile_plant_states`）：它们的属性来自
    上游 blockstate，外观一律是透明共享状态 + entity_renderer，没有方块行为。

    ## 可爬
    `climbable` 为真时，透明宿主要换成 `weeping_vine`（见 {@link CLIMB_CARRIER}）——
    玩家客户端读到的是甘蔗就爬不上去。`settings.tags` 里的 `minecraft:climbable`
    由调用方通过 `extra_tags` 追加，两件事都要有。

    ## 家具
    `KIND_FURNITURE` 在这里**早退并返回空串**：家具不是方块，CE 的家具定义只有
    `items:` 段（`behavior: furniture_item`），写进 `blocks:` 会让 CE 去建一个
    没有碰撞箱定义的方块。家具的 items 段由 `furniture.furniture_item_yaml` 生成，
    调用方（`main`）在分类为家具时直接写进同一个批次的 `items:` 列表，不再调用本函数。
    """
    if kind == KIND_FURNITURE:
        return ""
    sound_lines = "".join(
        f"        {event}: {value}\n" for event, value in sound_events(sound_family(p))
    )
    cls = p.cls if p else ""
    # 一个方块只能有一段 `behavior:`（要多个必须写成 `behaviors:` 列表）：
    # 重复 key 会让整段配置解析失败，所以这里统一收集后再渲染。
    behaviors: list[list[str]] = []
    if stripped:
        behaviors.append(["type: strippable_block", f"stripped: {NAMESPACE}:{stripped}"])
    if shape_behavior:
        behaviors.append([f"type: {shape_behavior}"])
    # 植物放置支撑（bnlib.support）：独立模块按上游源码证据生成 behavior 片段，
    # 与剥皮/异形族/功能方块并列；空列表就是「这个方块没有可证的支撑规则」，
    # 绝不在这里补一个看起来合理的支撑（那会变成伪实现）。
    if support_behaviors:
        behaviors.extend(support_behaviors)
    behaviors.extend(functional_behavior_entries(cls))
    behavior = behavior_yaml(behaviors)

    # `events:` 同理只能出现一次。耕地目标与功能方块在构造上不重叠；真要重叠了，
    # 这里显式失败，而不是悄悄丢掉一段右键行为。
    event_sections = [section for section in (
        till_events_yaml() if tillable else "",
        functional_events_yaml(cls),
    ) if section]
    if len(event_sections) > 1:
        raise ValueError(
            f"{block_id}: 同时需要两段 events 段（耕地与功能方块），请改成合并成一条 events")
    tillable_events = event_sections[0] if event_sections else ""

    settings = settings_yaml(block_id, p, tools, break_power, flammable,
                             extra_tags, kind)
    # `sounds` 并进 settings 段，缩进与 merges 的键同级。
    settings += f"      sounds:\n{sound_lines}"

    state_part = shape_states if shape_states else (
        f"    state:\n{state_body(block_id, generation, has_model, kind, pool, required_properties, variant_models)}"
    )

    return (
        f"  {block_id}:\n"
        f"{settings}"
        f"{ore_drops.loot_snippet(block_id.split(':', 1)[1])}"
        f"{behavior}"
        f"{tillable_events}"
        f"{state_part}"
    )


def verify_lang_coverage(upstream: Path, names: list[str]) -> dict[str, list[str]]:
    """校验每个方块都能在上游语言文件里解析到译名（经 {@link LANG_ALIASES}）。

    这是 extractor 硬失败的**前哨**：`build_langs`（scripts/extract_bn.py:548-553）在
    「`block.*` 与 `item.*` 都查不到」时会 `SystemExit`，一个方块就能卡住整次迁移。
    所以这里提前把缺口算出来并打印，让问题在 blocks.py 这一层就暴露，而不是等到
    集成阶段才发现清单与上游不一致。

    返回 `{"en_us": [...], "zh_cn": [...]}`，只含真正缺的项。
    英文缺口是**错误**（调用方应失败），中文缺口是上游事实（回退英文即可）。
    """
    lang_dir = upstream / "src/main/resources/assets/betternether/lang"
    result: dict[str, list[str]] = {}
    for code in ("en_us", "zh_cn"):
        source = lang_dir / f"{code}.json"
        if not source.is_file():
            result[code] = list(names)
            continue
        try:
            table = json.loads(source.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            result[code] = list(names)
            continue
        missing: list[str] = []
        for name in names:
            key = lang_name_of(name)
            if (f"block.betternether.{key}" not in table
                    and f"item.betternether.{key}" not in table):
                missing.append(name)
        result[code] = missing
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 CraftEngine 方块/物品配置")
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--output", required=True, help="CE 包根目录（含 pack.yml 的目录）")
    args = parser.parse_args()

    upstream = Path(args.upstream).resolve()
    ce_root = Path(args.output).resolve()
    blocks_dir = ce_root / "configuration/blocks"
    categories_dir = ce_root / "configuration/categories"
    # 矿物掉落规则由 ore_drops 从上游 loot_table JSON 解析；block_yaml 拿不到 upstream，
    # 所以在主循环前一次性注入。
    ore_drops.configure(upstream)

    # ---- 上游真值
    # 语言键解析必须先于任何 item_yaml：lang_name_of 要用上游语言表实测候选键。
    load_lang_table(upstream)
    props_by_id = block_props.load_props(upstream)
    tiers = block_props.load_shape_tiers(upstream)
    mineable = block_props.load_mineable_tools(upstream)
    break_power = block_props.load_break_power(upstream)
    flammable = block_props.load_flammable(upstream)
    leaves_tag = block_props.load_tag(upstream, "leaves")
    climbable_tag = block_props.load_tag(upstream, "climbable")
    logs_tag = block_props.load_tag(upstream, "logs")
    planks_tag = block_props.load_tag(upstream, "planks")
    replaceable_tag = block_props.load_tag(upstream, "replaceable")
    # 上游自定义标签的成员表（`data/betternether/tags/{item,block}/**`）。配方里的
    # `#betternether:<wood>_logs` / `#betternether:flaming_ruby/repair` 只有成员自己声明过
    # 才能被解析：物品那份写进 items 段（配方读物品注册表），方块那份写进 settings.tags。
    item_tags = load_registry_tags(upstream, "item", (NAMESPACE, "minecraft"))
    block_tags = load_registry_tags(upstream, "block")
    if not tiers:
        raise SystemExit("找不到方块形状表，拒绝生成一个没有形状依据的 CE 包。")
    if not props_by_id:
        print("[属性] 读不到 block_properties.json，所有方块将退回 CE 默认值。")

    # 世界生成与结构真正放置的方块；不在这里面的植物/装饰不发布（见「纤细池瘦身」）。
    referenced = referenced_block_ids(
        Path(__file__).resolve().parents[2],
        upstream=Path(args.upstream).resolve() if getattr(args, "upstream", None) else None,
        generated=Path(args.output).resolve() if getattr(args, "output", None) else None,
    )
    print(f"[瘦身] 世界生成/结构引用 {len(referenced)} 个方块 id")
    # 上游 worldgen JSON 里显式写了属性的方块状态（`state.id` + `state.properties`）：
    # 这是「放置时到底带哪些属性」的权威来源，用来自动补齐属性白名单。
    worldgen_refs = load_worldgen_block_states(upstream)
    print(f"[状态] 上游 worldgen JSON 显式带属性的方块 {len(worldgen_refs)} 个")

    # ---- 逐条 inventory：659 个方块每一个都要有结论
    inventory: list[dict[str, object]] = []
    selected: list[tuple[str, str, str]] = []   # (对外 id, kind, pool)
    excluded: list[dict[str, str]] = []

    for upstream_id, tier in tiers.items():
        p = props_by_id.get(upstream_id)
        block_id = rewrite_id(upstream_id)
        name = block_id.split(":", 1)[1]
        kind, reason = classify(upstream_id, p, tier, leaves_tag)
        if is_removed_block(block_id):
            kind, reason = KIND_EXCLUDED, "用户要求移除；结构与模板中的引用由发布规则替换为原版同形块"
        elif kind == KIND_FURNITURE:
            # 家具不走下面两条剔除规则，理由各不相同：
            #   * 座椅：`is_seat` 过去整类不发布（bclib 模型缺失），现在模型由
            #     `furniture.seat_model` 从 bclib 的几何 + 上游贴图合成，缺失理由消失；
            #   * 「世界生成与结构都不放置它」：这条是为了不长期占用 CE 的纤细视觉状态池，
            #     而家具**一个状态槽位都不占**（hitbox 是实体），前提不成立。
            pass
        elif is_seat(name):
            kind, reason = KIND_EXCLUDED, (
                "座椅模型的 parent 是 bclib:block/{chair,taburet,bar_stool}，"
                "bclib 模型不在上游仓库；与更好的末地一致不发布")
        elif (kind in (KIND_PLANT, KIND_STATIC)
              and name not in referenced
              and name not in RECIPE_ONLY_PUBLISHED):
            kind, reason = KIND_EXCLUDED, (
                "世界生成与结构都不放置它；为不持续占用 CE 纤细视觉状态池，不发布")
        elif kind == KIND_SHAPE and name not in referenced and not _shape_is_template_published(reason):
            # 异形族：普通异形（半砖/楼梯/栅栏/墙/窗格/按钮）每块要几十个服务端状态，
            # 全部发布会耗尽 CE serverside-blocks，只保留被世界生成/结构放置的。
            # 例外：门/活板门/闸门/压力板走 CE 官方模板 + 写死可回收状态（`_shape_is_template_published`），
            # 不占 auto_state 池，所以即使没被结构引用也要发布（用户要求照 BetterEnd 做全套门）。
            kind, reason = KIND_EXCLUDED, (
                "世界生成与结构都不放置它；异形方块占用大量服务端状态，不发布")
        entry: dict[str, object] = {
            "upstream_id": upstream_id,
            "id": block_id,
            "tier": tier,
            "class": p.cls if p else "",
            "kind": kind,
            "reason": reason,
        }
        inventory.append(entry)
        if kind == KIND_EXCLUDED:
            excluded.append({"id": block_id, "class": p.cls if p else "", "reason": reason})
        else:
            # 第四项是「落地细节」：形状族存 CE 行为名，其余存说明文字。
            selected.append((block_id, kind, name, reason))

    # ---- 贴图与模型
    #
    # 顺序是「拿上游真实数据」优先，绝不自造模型：
    #   1. 上游自带模型：直接读模型里 textures 引用的第一张贴图；
    #   2. 没有模型但有同名贴图：用同名贴图（CE 会据此生成 cube_all）；
    #   3. 按上游族约定找（侧面 > 端面 > 砖纹）；
    #   4. 都没有 -> 上游资源残缺，剔除并在 inventory 里记原因。
    textures_dir = upstream / "src/main/resources/assets/betternether/textures/block"
    models_dir = upstream / "src/main/resources/assets/betternether/models/block"
    blockstates_dir = upstream / "src/main/resources/assets/betternether/blockstates"
    # datagen 的产出在这里：472 个 blockstate、1304 个模型。手写的 `resources` 只有 187 个
    # blockstate，所以**只查 resources 会把 45 个方块误判成「无贴图」**（`nether_grass`、
    # `wall_moss`、`soul_lily`、`wart_seed` 这些植物全在里面）。
    # 贴图仍然只在 `resources` 里（generated 下没有 textures）。
    gen_assets = upstream / "src/main/generated/assets/betternether"
    gen_models_dir = gen_assets / "models/block"
    gen_blockstates_dir = gen_assets / "blockstates"
    available = {path.stem for path in textures_dir.glob("*.png")}
    # 贴图 alpha 扫描的根目录（命名空间的上层）与跨方块缓存：同一张图会被大量模型共用。
    assets_root = upstream / "src/main/resources/assets"
    translucency_cache: dict[Path, transparency.TextureAlpha] = {}

    def model_dirs() -> tuple[Path, ...]:
        """模型候选目录：generated 优先（更全），resources 兜底。"""
        return (gen_models_dir, models_dir)

    def model_path(stem: str) -> Path | None:
        for directory in model_dirs():
            candidate = directory / f"{stem}.json"
            if candidate.is_file():
                return candidate
        return None

    def upstream_model_file(ref: str) -> Path | None:
        """本文件内的模型文件解析（目录是 main 的局部状态，见模块级 `model_file_of`）。"""
        return model_file_of(upstream, ref)


    def model_stem_of(name: str) -> str | None:
        """找出这个方块真正使用的模型文件名。

        **不能假设模型名等于方块名**：上游大量方块复用别的模型名
        （`eyeball` → `block_eyeball`、`ink_bush` → `ink_bush_0`、
        `cincinnasite_stairs` → `cincinnasite_stairs_inner/outer`）。
        唯一权威来源是它的 blockstate —— 里面写着每个变体用哪个 model。
        没有 blockstate 的方块再按前缀兜底找一次，最后才假设同名。
        """
        for directory in (gen_blockstates_dir, blockstates_dir):
            blockstate = directory / f"{name}.json"
            if not blockstate.is_file():
                continue
            try:
                payload = json.loads(blockstate.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            refs: list[str] = []
            # variants 的值可能是 dict 也可能是 list（多方向变体）。
            for variant in (payload.get("variants") or {}).values():
                entries = variant if isinstance(variant, list) else [variant]
                for entry in entries:
                    if isinstance(entry, dict) and isinstance(entry.get("model"), str):
                        refs.append(entry["model"])
            # multipart 形式的方块（藤蔓、栅栏、墙）也带 model 引用。
            for part in payload.get("multipart") or []:
                apply = part.get("apply") if isinstance(part, dict) else None
                entries = apply if isinstance(apply, list) else [apply]
                for entry in entries:
                    if isinstance(entry, dict) and isinstance(entry.get("model"), str):
                        refs.append(entry["model"])
            for ref in refs:
                stem = ref.split("/", 1)[1] if "/" in ref else ref
                if model_path(stem) is not None:
                    return stem

        if model_path(name) is not None:
            return name

        # 前缀兜底：`ink_bush` -> `ink_bush_0`、`eyeball` -> `block_eyeball`。
        for directory in model_dirs():
            for pattern in (f"{name}_*.json", f"*_{name}.json", f"*{name}*.json"):
                for candidate in sorted(directory.glob(pattern)):
                    return candidate.stem
        return None

    def textures_of_model(stem: str) -> list[str]:
        """模型声明的贴图名（含 parent 链），按出现顺序。

        返回的字符串可能带 `minecraft:` 前缀 —— 上游有相当一批方块**直接复用原版贴图**
        （`wall_mushroom_brown` → `block/brown_mushroom_block`、
        `wart_seed` → `block/nether_wart_block`），把这类引用当成「资源残缺」会把
        明明有外观的方块丢掉。
        """
        found: list[str] = []
        seen: set[str] = set()
        current: str | None = stem
        while current and current not in seen:
            seen.add(current)
            path = model_path(current)
            if path is None:
                break
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                break
            for value in payload.get("textures", {}).values():
                if not isinstance(value, str):
                    continue
                if value.startswith(UPSTREAM_PREFIX + "block/"):
                    found.append(value.split("/", 1)[1])
                elif value.startswith("block/") or value.startswith("minecraft:block/"):
                    # 裸 `block/xxx` 或显式 minecraft 前缀都是原版贴图，直接引用。
                    found.append(value if value.startswith("minecraft:") else "minecraft:" + value)
            parent = payload.get("parent")
            current = (parent.split("/", 1)[1] if isinstance(parent, str) and "/" in parent
                       else None)
        return found

    def texture_of(name: str) -> str | None:
        """解析这个方块该用哪张贴图。

        优先级严格是「读上游真实数据」：
          1. 由 blockstate 定位真实模型 -> 用模型（含 parent 链）里第一张真实贴图；
             原版贴图（`block/xxx`）解析为 `minecraft:block/xxx`，同样可用；
          2. 有同名贴图 -> 用它；
          3. 按上游族约定找（侧面 > 端面 > 砖纹）；
          4. 都没有 -> 上游资源残缺，剔除。
        """
        stem = model_stem_of(name)
        if stem is not None:
            texture = generation_texture_of(name, textures_of_model(stem), available)
            if texture is not None:
                return texture

        if name in available:
            return name

        for suffix in ("_side", "_log_side", "_middle", "_top", "_log_top",
                       "_bricks", "_block", "_tile", "_1"):
            if name + suffix in available:
                return name + suffix

        # 树皮没有独立贴图，上游复用原木侧面（RubeusBark 的注释写明了这点）。
        if name.endswith("_bark"):
            base = name[: -len("_bark")]
            for suffix in ("_log_side", "_bark", "_log_top"):
                if base + suffix in available:
                    return base + suffix

        # 石笋/钟乳石的贴图只在「这个方块有没有外观」这一问上有意义：它真正的模型由
        # generation_of -> spike_generation 从上游 `<name>_<size>.json` 抄来。
        if name.endswith("_stalactite"):
            base = name[: -len("_stalactite")]
            for suffix in ("_bricks", "_block", "_tile"):
                if base + suffix in available:
                    return base + suffix

        return None

    def shape_textures(name: str, suffix: str) -> dict[str, str]:
        """异形族某个外观的贴图槽位（`{slot: "betternether:block/xxx"}`）。

        上游把楼梯/半砖/门的几何放在**原版 parent 模型**里，自己的文件只覆盖贴图，
        所以这里读的是 `<name><suffix>.json` 的 textures；取不到就退回基础模型。
        """
        base = model_stem_of(name) or name
        for stem in (((base + suffix) if suffix else base), base):
            path = model_path(stem)
            if path is None:
                continue
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                continue
            found: dict[str, str] = {}
            for slot, value in payload.get("textures", {}).items():
                if not isinstance(value, str):
                    continue
                # 只收真正能解析到的贴图：
                #   `betternether:block/x` → 必须 x 在本包 PNG 里
                #   `block/x` / `minecraft:block/x` → 原版贴图，直接可用
                # 统一走 texture_ref，避免拼出 `betternether:block/minecraft:block/x`。
                if value.startswith(UPSTREAM_PREFIX + "block/"):
                    if value.split("/", 1)[1] in available:
                        found[slot] = texture_ref(value)
                elif value.startswith("block/") or value.startswith("minecraft:block/"):
                    found[slot] = texture_ref(value)
            if found:
                return found
        return {}

    texture_by_block: dict[str, str] = {}
    dropped: list[str] = []
    for block_id, kind, name, _detail in selected:
        texture = texture_of(name)
        if texture is None:
            dropped.append(block_id)
            for entry in inventory:
                if entry["id"] == block_id:
                    entry["kind"] = KIND_EXCLUDED
                    entry["reason"] = "上游既无模型也无可用贴图（资源残缺）"
            continue
        texture_by_block[block_id] = texture
    if dropped:
        print(f"[形状] 剔除 {len(dropped)} 个上游既无模型、也无可用贴图的方块："
              + "、".join(b.split(":", 1)[1] for b in dropped[:10])
              + (" 等" if len(dropped) > 10 else ""))

    selected = [item for item in selected if item[0] in texture_by_block]

    # 植物放置支撑规则（独立模块，按上游 Java 源码的 canSurvive/SurvivesOnBlockTrait 取证）。
    # 只读、幂等；整棵树只解析一次，传入每个方块避免重复解析。
    support_rules = support.load_support_rules(upstream)
    support_ids = {name for name, rule in support_rules.items() if not rule.blocked}
    print(f"[支撑] 上游可证实的支撑方块 {len(support_ids)} 个；阻塞 {len(support_rules) - len(support_ids)} 个")

    # ---- 生成
    all_names = {block_id.split(":", 1)[1] for block_id, _kind, _name, _d in selected}
    # 本包真正发布的 id（带命名空间）：support 模块用它确认行为引用的目标真的存在。
    published_ids = {f"{NAMESPACE}:{name}" for name in all_names}
    soil_blocks = load_soil_blocks(upstream)
    tillable = {b for b in soil_blocks if b in {s[0] for s in selected}}
    if tillable:
        print(f"[耕地] 可耕土壤 {len(tillable)} 个 → {NAMESPACE}:{TILL_TARGET}："
              + "、".join(sorted(b.split(':', 1)[1] for b in tillable)))
    missing_soil = {b.split(":", 1)[1] for b in soil_blocks} - all_names
    if missing_soil:
        print(f"[耕地] 上游土壤标签里这 {len(missing_soil)} 个方块不在本包（不生成耕地）："
              + "、".join(sorted(missing_soil)))

    batches: dict[str, dict[str, list[str]]] = {}
    used_categories: set[str] = set()
    report: list[dict[str, object]] = []
    geometry_report: dict[str, list[str]] = {}
    # 植物/装饰的碰撞保真记录（写到 reports/plant-collision.json）。
    allocator = PoolAllocator()
    # 写死状态载体（`state:`）：与上面的池额度**分账**，一个槽位都不从 auto_state 里拿，
    # 只在池族给不出载体时补位（次序见 `plant_take_steps`）。
    pinned = PinnedAllocator()
    collision_report: list[dict] = []
    # 还要「保底 1 个 appearance」的植物/装饰方块数（含当前方块）——
    # 分配器用它预留额度，保证后面的方块不会因为前面吃光格子而整体丢外观。
    # 必须按**池族**分开计数：可爬方块只跟可爬方块抢那 11 格，非可爬的抢另外 75 格，
    # 混在一起数会让可爬方块一上来就看到「缺口 44」。
    pending_climb = 0
    pending_noclip = 0
    for _bid, _kind, _name, _detail in selected:
        if _kind not in (KIND_PLANT, KIND_STATIC):
            continue
        if _name in PINNED_CARRIER_TARGETS:
            continue
        if UPSTREAM_PREFIX + _name in climbable_tag:
            pending_climb += 1
        else:
            pending_noclip += 1
    # 结构性外观（axis 三态）的预定额度：先到先得会把 chorus 吃光，让后面的柱体只剩
    # 1 个外观 —— 而 axis=x/z 丢的是**几何**（放倒的柱体仍竖着显示）。所以先把这部分
    # 从「可自由分配的余量」里扣掉，等真的处理到那些方块时再释放。
    for _bid, _kind, _name, _detail in selected:
        if _kind not in (KIND_PLANT, KIND_STATIC):
            continue
        if _name in PINNED_CARRIER_TARGETS:
            continue
        _raw = load_variant_states((gen_blockstates_dir, blockstates_dir), _name)
        if not _raw:
            continue
        _axes = {part.split("=", 1)[1]
                 for _key, _models in _raw["states"]
                 for part in ",".join(f"{k}={v}" for k, v in _key.items()).split(",")
                 if part.startswith("axis=")}
        if len(_axes) > 1:
            _family = plant_slot_plan(
                _name, tiers.get(UPSTREAM_PREFIX + _name, ""),
                UPSTREAM_PREFIX + _name in climbable_tag)[0]
            allocator.reserve(_family, len(_axes) - 1)
    kind_counts: dict[str, int] = {}
    # CE 会按 `generation` 生成的模型名（相对 `block/`）。只靠它判断撞名：
    # 生成名永远取自方块自己的名字（异形族取带 `_ce_` 段的外观名），与上游文件名比较得出名单。
    generated_models: set[str] = set()
    # 透明方块需要的合成模型（CE 的 `generation` 规格 -> 构建期真实 JSON），
    # 由 assets.py 落到 `resourcepack/assets/<ns>/models/<path>.json`。
    # items 段因此不需要写 `generation`（那条约束没有例外）。
    synthesized_models: list[dict] = []
    # 多状态植物的落地清单（属性/外观/变体/折叠原因），写到 reports/plant-states.json，
    # 供放置端与验收脚本核对「哪个属性现在真的有了」。
    plant_state_reports: list[dict] = []
    # 满格方块的真实贴图 alpha 结论（写进 reports/translucency.json 供验收/回查）。
    translucency_report: list[dict] = []
    # 硬度对齐清单（写进 reports/hardness-adjustments.json）：改了哪些、原值→新值、依据。
    hardness_report: list[dict] = []

    # 家具清单（写进 reports/furniture.json）：逐块记录族、碰撞箱真值、hitbox 参数。
    furniture_report: list[dict] = []

    for block_id, kind, name, detail in selected:
        # 属性表用上游 id 索引，这里按上游前缀查回同一个方块。
        p = props_by_id.get(UPSTREAM_PREFIX + name)
        is_plant = kind == KIND_PLANT
        batch, category = batch_of(block_id, is_plant)
        used_categories.add(category)
        slot = batches.setdefault(batch, {"items": [], "blocks": []})

        # ---- 家具：只写 items 段（`behavior: furniture_item`），没有 blocks 段 --------
        #
        # 提前 return 而不是让后面那套「方块 + 载体状态」的流程跑一遍：家具没有
        # `state:`/`auto_state:`，借池、配额、碰撞保真这些概念对它都不成立。家具内部
        # 仍然各自生成模型（座椅合成、其余用上游同名模型），并逐块进报告。
        family = furniture.family_of(name) if kind == KIND_FURNITURE else None
        if family is not None:
            events = dict(sound_events(sound_family(p)))
            slot["items"].append(furniture.furniture_item_yaml(
                block_id, family,
                item_name=f"<!i><white><lang:{lang_key_of(name)}></white>",
                sounds={key: events[key] for key in ("break", "place", "hit")}))
            kind_counts[kind] = kind_counts.get(kind, 0) + 1
            furniture_report.append({
                "id": block_id,
                "family": family["name"],
                "truth": family["truth"],
                "hitboxes": family["hitboxes"],
                "rotation": family["rotation"],
                "model": furniture.model_path_of(name, family),
                "note": family["note"],
            })
            report.append({
                "id": block_id, "kind": kind, "category": category, "batch": batch,
                "ore": False, "tier": tiers.get(UPSTREAM_PREFIX + name, ""),
                "lang_key": lang_key_of(name), "class": p.cls if p else "",
                "instrument": p.instrument if p else "", "tools": sorted(
                    mineable.get(UPSTREAM_PREFIX + name, frozenset())),
                "break_power": break_power.get(UPSTREAM_PREFIX + name, 0),
                "luminance": p.luminance if p else None,
                "can_occlude": p.can_occlude if p else None,
                "replaceable": p.replaceable if p else None,
                "burnable": p.ignited_by_lava if p else None,
                "waterlogged": p.waterlogged if p else None,
            })
            continue

        # resources 与 generated 两处都算「已有模型」：已有就只引用、不生成，
        # 否则 CE 报「无法生成模型，因为模型文件已存在」。
        has_model = model_path(name) is not None
        generation = None if has_model else generation_of(
            name, texture_by_block[block_id], available, models_dir)

        # 物品图标：上游 `items/<name>.json` 指向的模型优先；没有就用 `block/<name>`。
        # 三条路都不写 `generation`（items 段永不生成模型）：
        #   1. 上游有 item 模型 -> 引用它（assets 会原样释放）；
        #   2. 方块段要生成 block/<name> -> 引用同一 path，模型由方块段生成；
        #   3. 透明方块且上面两条都不成立 -> 模型在构建期合成成真实 JSON（登记见下）。
        #
        # 引用的模型必须**槽位闭合**（见 {@link model_slots_closed}）：`block/<name>` 可能只是
        # 模板（`stalagnate_trunk` 就是 `#side`/`#top` 都要子模型补的那种），当图标会缺材质。
        # 不闭合时退到上游 blockstate 里真实的首个变体（模型名常与方块名不同，见 `model_stem_of`）。
        item_model = item_model_ref(upstream, name)
        if item_model is not None and not model_slots_closed(item_model, upstream_model_file):
            stem = model_stem_of(name)
            fallback = f"{NAMESPACE}:block/{stem}" if stem and model_path(stem) else None
            if fallback is not None and model_slots_closed(fallback, upstream_model_file):
                item_model = fallback

        # ---- 贴图 alpha 事实：交叉验证「这个满格方块能不能按 SOLID 渲染」
        #
        # 上游 `renderLayer` 是作者意图，这里再用真实 PNG 的 alpha 核一遍。判错两个方向
        # 都可见：漏判 -> 玻璃/树叶变成实心色块；误判 -> 不透明方块被迫走透明通道、
        # 白挤 cutout 状态额度。依据来自模型**实际用到**的贴图（parent 链已合并），
        # 不是贴图名字里的关键词。见 `bnlib.transparency`。
        translucency = None
        if kind in (KIND_SOLID, KIND_CUTOUT):
            stem = name if has_model else model_stem_of(name)
            if stem is not None:
                translucency = scan_model_translucency(
                    stem, model_path, assets_root, translucency_cache)
            else:
                # 没有模型文件：CE 会用单张贴图生成 cube_all，只查那一张。
                translucency = transparency.scan_model_textures(
                    {"textures": {"all": texture_ref(texture_by_block[block_id])}},
                    assets_root, translucency_cache)
            translucent = conservative_translucent(translucency)
            if kind == KIND_SOLID and translucent:
                # 纹理带 alpha 的方块绝不能借 full-solid 载体的实心渲染：
                # 迁到 cutout 状态（树叶载体：满格但不实心、保留碰撞），额度见 PoolAllocator。
                kind = KIND_CUTOUT
                for entry in inventory:
                    if entry["id"] == block_id:
                        entry["kind"] = KIND_CUTOUT
                        entry["reason"] = "真实贴图带 alpha，改按 cutout 渲染（不实心载体）"
            elif kind == KIND_CUTOUT and not translucent and not translucency.missing:
                # 反向：上游标了非 SOLID，但实际所有贴图都不透明且全部可验证 -> 回到 SOLID，
                # 把 cutout 额度留给真的透明的方块。有任何缺失就不降级（不可当不透明）。
                kind = KIND_SOLID
                for entry in inventory:
                    if entry["id"] == block_id:
                        entry["kind"] = KIND_SOLID
                        entry["reason"] = "真实贴图全部不透明，按 SOLID 渲染（不再占 cutout 额度）"
            translucency_report.append({
                "id": block_id,
                "kind": kind,
                "translucent": translucent,
                "refs": list(translucency.refs),
                "translucent_refs": list(translucency.translucent_refs),
                "missing": list(translucency.missing),
                "missing_local": list(missing_local_textures(translucency)),
            })

        # ---- 可爬（必须早于外观生成：载体的选择由它决定）
        #
        # 唯一真值是上游 `data/minecraft/tags/block/climbable.json` 的成员集合
        # （`props.load_tag` 直读该文件）。不能按类名推：`BlockGloomwispVine` 不在
        # 上游 climbable 里，而 `BlockSoulVein` / `neon_equisetum` / `stalagnate_trunk`
        # 在 —— 按类判定会同时产生假阳性和漏配。
        #
        # 命中后要做**两件**事，缺一不可：
        #   1. `settings.tags` 追加 `minecraft:climbable`（方块级 holder tag，服务端逻辑
        #      与装了 CE 客户端 mod 的玩家读得到）；
        #   2. 透明宿主要换成真实可爬的 `weeping_vine`（原版客户端读的是载体状态，
        #      甘蔗宿主爬不上去）。见 {@link CLIMB_CARRIER}。
        climbable = UPSTREAM_PREFIX + name in climbable_tag
        # 形状档位（真值来自上游 block_shapes.txt）：决定植物能借哪种碰撞箱。
        tier = tiers.get(UPSTREAM_PREFIX + name, "")
        # 池族 + 碰撞类别 + 保真说明，全部来自单一真值表 plant_slot_plan。
        slot_family, collision_class, fidelity = plant_slot_plan(
            name, tier, climbable)
        # 点名走写死状态载体的方块（容器）：载体从这里定，不再走任何 auto_state 池。
        # 为空表示「按池族分配」，行为与改造前一致。
        pinned_target = PINNED_CARRIER_TARGETS.get(name)

        # ---- 世界生成多状态（带属性放置的那些方块）
        #
        # 每个外观都要**自己占一个真实视觉状态**：CE 里一个原版状态只能绑一个模型。
        # 植物从 `slot_family` 里取（只可能是无碰撞池或 cactus），满格方块从
        # solid/树叶池取。取不到槽位就 fail-fast，不存在「退回展示实体」这条路。
        state_text = ""
        state_strategy = ""
        taken_pools: list[str] = []
        # 本块用掉的写死状态（`state:` 载体）与「逐外观的载体类型」。
        # `carriers` 与 `spec["appearances"]` 一一对应，报告按它落 `pool` / `state`，
        # 不能再用 `taken_pools[index]` 取：两条路混用后下标不再对齐。
        taken_pinned: list[PinnedState] = []
        carriers: list[tuple[str, object]] = []
        # 本块的外观数：合并前（上游编译出几个）与合并后（实际发布几个）。
        appearances_before = 0
        appearances_after = 0
        merged_info: dict | None = None

        # 坏模型 → 克隆名的映射（先只算名字，等编译完知道哪些外观真的用到再落盘，
        # 免得随机备选里没被采用的模型也生成一份死文件）。
        pending_repairs: dict[str, str] = {}

        def repair_stem(stem: str) -> str:
            """坏模型 → 同族合法材质修复后的克隆模型名（改不了就原样返回）。

            改不了（没有修复规则 / 模型文件不在这两处）就原样返回，交给下游的
            `model_usable` 剔除，绝不写一个指向不存在文件的引用。
            """
            if stem not in MODEL_TEXTURE_REPAIRS:
                return stem
            clone = f"{stem}_ce"
            pending_repairs[stem] = clone
            return clone

        def register_repairs(used_stems: set[str]) -> None:
            """把**真的被外观引用**的修复模型登记进构建期合成清单。"""
            for stem, clone in pending_repairs.items():
                if clone not in used_stems:
                    continue
                if any(entry["path"] == f"block/{clone}" for entry in synthesized_models):
                    continue
                source = model_path(stem)
                if source is None:
                    continue

                def read_model(_stem: str, _source=source) -> dict | None:
                    try:
                        return json.loads(_source.read_text(encoding="utf-8"))
                    except json.JSONDecodeError:
                        return None

                entry = build_model_repair(stem, read_model)
                if entry is not None:
                    synthesized_models.append(entry)

        def model_usable(stem: str) -> bool:
            """模型是否真的会出现在资源包里（含上面那种构建期修复出来的克隆）。"""
            if stem in pending_repairs.values():
                return True
            if any(entry["path"] == f"block/{stem}" for entry in synthesized_models):
                return True
            return model_is_usable(stem, model_path)

        # 放置方向不能只依赖世界生成引用：去皮原木等仅供玩家摆放的方块也需要轴向。
        # CE 对 axis/facing 内置点击面/玩家方向逻辑；其余属性不顺带扩张状态乘积。
        raw_states = load_variant_states((gen_blockstates_dir, blockstates_dir), name)
        direction_props = tuple(
            prop for prop in ("axis", "facing")
            if raw_states and any(prop in values for values, _models in raw_states["states"]))
        declared_props = tuple(dict.fromkeys(
            STATE_PROPS.get(name, ())
            + tuple(sorted(worldgen_refs.get(name, {})))
            + direction_props
        ))
        declared_props = tuple(prop for prop in declared_props
                               if prop not in UNSAFE_PROPERTY_NAMES)
        # 支撑行为可能额外要求声明属性（`directional_attached_block` 要 `facing`）。
        # 必须在 state 段生成**之前**算出来，否则「行为写了、属性没写」，CE 会报错。
        support_props = support.required_properties(
            name,
            rules=support_rules,
            available_ids=published_ids,
            available_properties=set(declared_props) | {"facing"},
        )
        if declared_props and kind in (KIND_PLANT, KIND_STATIC, KIND_SOLID, KIND_CUTOUT):
            state_strategy = "pooled" if kind in (KIND_SOLID, KIND_CUTOUT) else "slots"
            if raw_states:
                # 坏模型的贴图引用先按同族合法材质修复（结果登记为构建期合成模型）；
                # 顺带把 CE 硬编码冲突的属性名换成可用的别名（rotation → vine_dir）。
                raw_states = {
                    **raw_states,
                    "states": [
                        ({(PROPERTY_RENAMES.get(prop, prop)): value
                          for prop, value in values.items()},
                         tuple((repair_stem(stem), x, y, uv)
                               for stem, x, y, uv in models))
                        for values, models in raw_states["states"]
                    ],
                }
            spec = compile_plant_states(
                name, raw_states, declared_props, model_usable,
            ) if raw_states else None
            if spec:
                # 只给「外观真的用到」的修复模型落盘（随机备选里没被采用的不生成死文件）。
                register_repairs({stem for _app, models in spec["appearances"]
                                  for stem, *_ in models})
                if kind in (KIND_PLANT, KIND_STATIC):
                    # 合法的无碰撞组只剩 86 格，而本块有 len(appearances) 个外观：
                    # 先按「其它方块还要保底」算出配额，超出部分在同一方块内部合并
                    # （丢掉的 variants 键改指向保留的外观，不会有状态没外观）。
                    pending = pending_climb if climbable else pending_noclip
                    appearances_before = len(spec["appearances"])
                    if pinned_target is not None:
                        # 容器走写死状态：每个外观各认领一格，**不参与池配额、也不合并**
                        # （认领的是真实原版状态，不吃 auto_state 额度，没有「额度不够要合并」
                        # 这一说；合并掉反而会让抽屉柜少一个朝向的外观）。
                        appearances_after = appearances_before
                    else:
                        # `axis` 三态是**几何**差别（横放的原木/柱体模型要转 90°），
                        # 合并掉会让放倒的柱体还是竖着显示 —— 这种外观要保底保留。
                        #
                        # 注意：这里**不把 shape/top/bottom 段位数并入 floor**。并入会让多段植物
                        # 向可爬藤蔓池申请更多槽位，而该池在线上已用尽（cave_vine/weeping_vine/
                        # twisting_vine 余量均为 0），必然 SystemExit。段位优先保留由
                        # `merge_plant_appearances` 的“结构签名优先 + 同签名重定向”在**已被分配到的
                        # keep 个外观内**保证：额度够(keep≥2)时正确保留各段位；额度只够 1 个时，
                        # 仍保留底段并如实记录缺口（要根治需先扩可爬池，见 blocks.POOL_BUDGET 注释）。
                        axis_values = {part.split("=", 1)[1]
                                       for key, _app in spec["variants"]
                                       for part in key.split(",") if part.startswith("axis=")}
                        structural = STRUCTURAL_APPEARANCE_FLOOR.get(name, 1)
                        floor = max(1, len(axis_values), structural)
                        keep = allocator.quota(slot_family, appearances_before, pending,
                                               floor=floor, released=floor - 1,
                                               pinned_free=pinned.remaining())
                        if floor > 1:
                            allocator.release(slot_family, floor - 1)
                        spec = merge_plant_appearances(spec, keep)
                        # **不要**在这里清空 `properties` / `variants`。
                        #
                        # 合并函数已经把被丢弃外观的 `variants` 键**重定向**到保留的外观了
                        # （`merge_plant_appearances` 末尾：`spec["variants"] = [(key,
                        # redirect.get(app, app)) ...]`），也就是说「属性 → 外观」的映射
                        # 依然完整，只是多个取值共用同一个外观 —— 这**不额外占状态槽**。
                        #
                        # 旧代码在这里把两者清空，后果是：CE 配置里这些属性彻底不存在，
                        # 运行期 `CraftEngineLookup` 按带属性的状态取值时取不到，于是启动时刷
                        # 一屏「CraftEngine 方块 X 没有属性 Y」。那是**自己造出来的**缺口，
                        # 不是上游数据问题。用户 2026-10-31 要求「补配置，一点报错都不要」。
                        appearances_after = len(spec["appearances"])
                        merged_info = spec.get("merged")

                    def take_plant_pool(_family=slot_family, _id=block_id,
                                        _why=fidelity or "无碰撞载体",
                                        _collision=collision_class,
                                        _climb=climbable,
                                        _target=pinned_target) -> str | PinnedState:
                        """本块每个外观各取一次载体：池族优先，写死状态按 `plant_take_steps`
                        的次序插入（细节与理由都在那个函数里）。

                        写死状态**只用来保底本块的第一个外观**（`not carriers`）：
                        它认领的是真实原版状态，只该花在「每个方块至少 1 个外观」上，
                        不能拿去给第 2、3 个外观凑数 —— 那是额度富余时才做的事，
                        而富余按 `PoolAllocator.quota` 的口径只从 auto_state 池里算。

                        `_target` 非空（容器）时**整个外观都走点名载体**：容器统一用写死
                        状态，不允许第 2 个外观又落回 chorus（那会变成两套写法并存）。
                        """
                        if _target is not None:
                            pin = pinned.take_carrier(_target["carrier"], _id)
                            taken_pinned.append(pin)
                            carriers.append(("state", pin))
                            return pin
                        kind_, value = take_plant_carrier(
                            allocator, pinned, _family, _id, _why,
                            collision=_collision,
                            allow_pinned=not _climb and not carriers)
                        if kind_ == "state":
                            taken_pinned.append(value)
                        else:
                            taken_pools.append(value)
                        carriers.append((kind_, value))
                        return value

                    state_text = pooled_states_yaml(spec, take_plant_pool, support_props)
                    state_strategy = "slots"
                else:
                    # 满格方块（原木/木板/树叶）：碰撞箱与遮挡来自真实视觉状态，
                    # 每个外观各取一个槽位；树叶池受额度限制，取不到就如实记进报告。
                    state_strategy = "pooled"
                    state_text = pooled_states_yaml(
                        spec,
                        (lambda: STATE_POOL_SOLID) if kind == KIND_SOLID
                        else allocator.take_cutout,
                        support_props)
                if state_text:
                    plant_state_reports.append({
                        "id": block_id,
                        "strategy": state_strategy,
                        "kind": kind,
                        "collision": collision_class,
                        "fidelity": fidelity,
                        "properties": [
                            {"name": prop, "type": ce_type, "default": default}
                            for prop, ce_type, default, _extra in spec["properties"]
                        ],
                        "appearances": [
                            {"name": app_name,
                             "models": [f"{NAMESPACE}:block/{model}" for model, *_ in models],
                             # 载体二选一：池名（`auto_state`）或写死的原版状态（`state`）。
                             # 分开记而不是塞进同一个字段，是为了让「哪些外观不占池」
                             # 在报告里一眼可数（门禁的槽位账也只认 auto_state）。
                             "pool": (carriers[index][1] if index < len(carriers)
                                      and carriers[index][0] == "auto_state" else None),
                             "pinned_state": (carriers[index][1].state if index < len(carriers)
                                              and carriers[index][0] == "state" else None)}
                            for index, (app_name, models) in enumerate(spec["appearances"])
                        ],
                        "variants": [{"key": key, "appearance": app}
                                     for key, app in spec["variants"]],
                        "dropped": spec["dropped"],
                        # 合并记录：额度不够时本块从几个外观合到几个、哪些被并走了。
                        "appearances_merged": spec.get("merged"),
                    })

        pool = None
        # 走多状态定义时不在这里分配：每个外观已经各取过一个槽位。
        if not state_text:
            appearances_before = max(appearances_before, 1)
            appearances_after = 1
            if kind in (KIND_PLANT, KIND_STATIC):
                # 单状态植物/装饰：同样借一个真实状态，不写 entity_renderer。
                # 与多状态那条路走同一套次序（池族优先、写死状态按 `plant_take_steps` 插入），
                # 否则「多状态方块能兜底、单状态方块兜不住」会让缺口在别处重新冒出来。
                if pinned_target is not None:
                    # 容器（箱子/酿造台）：点名载体，完全不碰 auto_state 池。
                    value = pinned.take_carrier(pinned_target["carrier"], block_id)
                    pool = value
                    taken_pinned.append(value)
                    carriers.append(("state", value))
                else:
                    allocator.quota(slot_family, 1,
                                    pending_climb if climbable else pending_noclip,
                                    pinned_free=pinned.remaining())
                    kind_, value = take_plant_carrier(
                        allocator, pinned, slot_family, block_id, fidelity or "无碰撞载体",
                        collision=collision_class, allow_pinned=not climbable)
                    pool = value
                    if kind_ == "state":
                        taken_pinned.append(value)
                    else:
                        taken_pools.append(value)
                    carriers.append((kind_, value))
            elif kind == KIND_CUTOUT:
                # 满格玻璃/树叶：保留碰撞，借树叶池。
                # 不染色树叶池满了就退到可染色树叶池：自定义模型不带 tintindex 时不会被群系染色。
                pool = allocator.take_cutout()
                taken_pools.append(pool)

        if kind in (KIND_PLANT, KIND_STATIC) and pinned_target is None:
            # 容器（`pinned_target` 非空）没有计进 pending，这里也不能扣 —— 否则
            # pending 会被扣成负数，后面植物的配额算出来是负的。
            if climbable:
                pending_climb -= 1
            else:
                pending_noclip -= 1

        # 碰撞保真报告：每个走真实状态的植物/装饰一行，缺口逐条写清。
        if kind in (KIND_PLANT, KIND_STATIC) and (state_text or pool):
            # 用到写死状态时，把「碰撞箱因此变成了什么」补进 fidelity ——
            # 碰撞箱就是载体的碰撞箱，这一条不写等于把代价藏起来。
            if taken_pinned:
                _carrier_names = "、".join(sorted({pin.carrier for pin in taken_pinned}))
                _geometries = "；".join(sorted({f"{pin.carrier}: {pinned.geometry_of(pin.carrier)}"
                                                for pin in taken_pinned}))
                fidelity = ((fidelity + "；") if fidelity else "") + (
                    f"借写死状态 {_carrier_names}（{_geometries}）："
                    + "；".join(sorted({pinned.cost_of(pin.carrier) for pin in taken_pinned})))
            collision_report.append({
                "id": block_id,
                "collision": collision_class,
                "family": list(slot_family),
                "pools": taken_pools,
                # 写死状态（`state:`）载体：不占 auto_state 池，但要逐条列出「谁钉了哪一格」，
                # 因为这是独占认领的原版状态，别处再钉同一格会互相覆盖模型。
                "pinned_states": [pin.state for pin in taken_pinned],
                "climbable": climbable,
                # 额度不够时在同一方块内部合并外观：这几个数字就是「从几个合到几个」。
                "appearances_before": appearances_before,
                "appearances_after": appearances_after,
                "appearances_merged": merged_info,
                # `exact` 只表示**碰撞箱档位**没变（上游无碰撞 → 借的载体也无碰撞）；
                # 上游 partial 的方块一律 `approximate`，理由在 `fidelity` 里逐条写明。
                "match": "exact" if collision_class == "empty" else "approximate",
                "fidelity": fidelity,
            })

        tools = mineable.get(UPSTREAM_PREFIX + name, frozenset())
        extra_tags: list[str] = []
        if UPSTREAM_PREFIX + name in leaves_tag:
            extra_tags.append("minecraft:leaves")
        if UPSTREAM_PREFIX + name in logs_tag:
            extra_tags.append("minecraft:logs")
        if UPSTREAM_PREFIX + name in planks_tag:
            extra_tags.append("minecraft:planks")
        if UPSTREAM_PREFIX + name in replaceable_tag and "minecraft:replaceable" not in extra_tags:
            extra_tags.append("minecraft:replaceable")
        if name == TILL_TARGET:
            extra_tags.extend(["minecraft:supports_crops", "minecraft:grows_crops"])
        # 可爬的 tag 部分：真值与载体判定已在上面算过（见 `climbable`）。
        # `settings.tags` 是方块级，命中后对该方块所有 state 生效。
        if climbable and VINE_CLIMBABLE_TAG not in extra_tags:
            extra_tags.append(VINE_CLIMBABLE_TAG)
        # 上游自定义**方块**标签（`data/betternether/tags/block/**`，如 `<wood>_logs`）。
        # 与物品标签分开写：CE 把方块 settings.tags 登记进方块注册表，配方读的是物品注册表。
        for tag in block_tags.get(block_id, ()):
            if tag not in extra_tags:
                extra_tags.append(tag)

        # 植物放置支撑：只有上游源码能证实 canSurvive/支撑方向时才写 behavior。
        # 被阻塞/无规则时返回 []，绝不补一个「看起来合理」的支撑。
        support_entries = support.support_behaviors(
            name,
            rules=support_rules,
            available_ids=published_ids,
            available_properties=set(declared_props) | {"facing"},
        )

        # 异形族与多状态植物都用 `states:` 取代单状态 `state`。
        shape_behavior = None
        shape_states = state_text or None
        if kind == KIND_SHAPE:
            shape_behavior = detail
            # 门/活板门/闸门/压力板：走 CE 官方模板/写死状态（照 BetterEnd）。
            if detail in _SHAPE_TEMPLATE_KIND:
                shape_states = shape_template_states(detail, name)
            elif detail == "pressure_plate_block":
                shape_states = pressure_plate_states(name)
            else:
                def _shape_model(app_name: str, suffix: str, _name: str = name) -> str | None:
                    # 上游模型命名：主外观无后缀，其余是 `<name><suffix>`；
                    # 栅栏/墙的立柱叫 `<name>_post`。
                    stem = f"{_name}_post" if app_name == "post" else f"{_name}{suffix}"
                    return f"{NAMESPACE}:block/{stem}" if model_path(stem) else None

                shape_states = shape_states_yaml(
                    detail, lambda suffix: shape_textures(name, suffix),
                    model_for=_shape_model, name=name)
            if not shape_states:
                # 门/活板门/闸门/压力板 donor 用尽 → 直接剔除（不能降级成满格 solid，
                # 那会变成错误碰撞的实心方块，且门禁会判它遮挡透视）。
                if detail in _TEMPLATE_PUBLISHED_BEHAVIORS:
                    shape_behavior = None
                    kind = KIND_EXCLUDED
                    for entry in inventory:
                        if entry["id"] == block_id:
                            entry["kind"] = KIND_EXCLUDED
                            entry["reason"] = "模板 donor 已用尽，不发布（避免与 BetterEnd 抢状态）"
                    for exc in excluded:
                        if exc["id"] == block_id:
                            exc["reason"] = "模板 donor 已用尽，不发布"
                    continue
                # 普通异形：一个外观的贴图都取不到时绝不写出空 `states`
                #（CE 会解析失败），退回单状态满格并如实记录。
                shape_behavior = None
                kind = KIND_SOLID
                for entry in inventory:
                    if entry["id"] == block_id:
                        entry["kind"] = KIND_SOLID
                        entry["reason"] = "异形族但取不到任何外观贴图，降级为单状态满格"
                for exc in excluded:
                    if exc["id"] == block_id:
                        exc["reason"] = "异形族但取不到任何外观贴图，降级为单状态满格"

        # 物品图标：模型来源见上面 `item_model` 的取值（这里只补生成规格的判断）。
        #
        # 多状态方块（`state_text`）的 `states` 只引用上游已有模型，不会生成 `block/<name>`；
        # 物品图标若要用 `block/<name>`，就得在构建期把这套 generation 规格落成真实 JSON
        # （见 `reports/synthesized-models.json`，由 assets.py 写入资源包）——
        # items 段永远不写 generation（CE 会对同一 path 生成两次）。
        synthesize = state_text and not has_model and item_model is None
        if synthesize:
            synthesized_models.append({"path": f"block/{name}", **generation})
        # 单状态、但上游 blockstate 给**同一个状态**列了多个模型（随机外观的草/花：
        # `gloomgrass_1..6`、`pale_gloomgrass_1..6` …）。这类方块没有 `block/<方块名>.json`，
        # 以前会走下面的兜底生成分支产出一个 `cube_all`，把十字植物渲染成立方体。
        # 变体模型本来就已经释放进资源包，直接把列表交给 `state_body` 写 `state.models`。
        variant_models: tuple[tuple[str, int, int, bool], ...] = ()
        if not has_model and not state_text:
            loaded = load_variant_states((gen_blockstates_dir, blockstates_dir), name)
            if loaded and len(loaded["states"]) == 1 and len(loaded["states"][0][1]) > 1:
                variant_models = loaded["states"][0][1]
        # 用户要求「不使用 CE 生成模型，直接用现有 JSON」：单状态方块（非 state_text）
        # 若上游没有模型，也要在构建期把 generation 规格落成真实 JSON，
        # 配置里只写 `path`（见 state_body）。否则 CE 会在运行期生成模型。
        if generation is not None and not has_model and not state_text and not variant_models:
            synthesized_models.append({"path": f"block/{name}", **generation})
        slot["items"].append(item_yaml(block_id, category, model_path=item_model,
                                       tags=item_tags.get(block_id, ())))
        slot["blocks"].append(block_yaml(
            block_id, generation, has_model, kind, pool, p,
            tools, break_power.get(UPSTREAM_PREFIX + name, 0),
            flammable.get(UPSTREAM_PREFIX + name),
            extra_tags,
            stripped=strippable_target(name, all_names),
            tillable=block_id in tillable,
            crop_support=(name == TILL_TARGET),
            shape_behavior=shape_behavior,
            shape_states=shape_states,
            support_behaviors=support_entries,
            required_properties=support_props,
            variant_models=variant_models,
        ))
        if generation is not None and kind != KIND_SHAPE and not variant_models:
            geometry_report.setdefault(generation["parent"], []).append(name)
        # 配置里已不再写 `generation:`（见 state_body / synthesized_models），
        # 所以 CE 不会再生成任何模型，`generated_models` 保持为空：
        # 撞名名单（shadowed-models.txt）随之恒为空，上游模型照常全部释放。
        _ = generated_models
        kind_counts[kind] = kind_counts.get(kind, 0) + 1
        adjustment = hardness_adjustment(p)
        if adjustment is not None:
            hardness_report.append({
                "id": block_id,
                "class": p.cls if p else "",
                "upstream": p.hardness if p else None,
                "applied": adjustment[0],
                "reference": adjustment[1],
            })
        report.append({
            "id": block_id, "kind": kind, "category": category, "batch": batch,
            "ore": name in ORE_NAMES, "tier": tiers.get(UPSTREAM_PREFIX + name, ""),
            # 语言键：方块 id 与上游语言键不一致时（见 LANG_ALIASES）这里是别名后的键，
            # 下游 build_langs / 其它 owner 必须按这个键取译名，不能按 id 猜。
            "lang_key": lang_key_of(name),
            "class": p.cls if p else "",
            "instrument": p.instrument if p else "",
            "tools": sorted(tools),
            "break_power": break_power.get(UPSTREAM_PREFIX + name, 0),
            "luminance": p.luminance if p else None,
            "can_occlude": p.can_occlude if p else None,
            "replaceable": p.replaceable if p else None,
            "burnable": p.ignited_by_lava if p else None,
            "waterlogged": p.waterlogged if p else None,
        })

    blocks_dir.mkdir(parents=True, exist_ok=True)
    # 所有批次写进**同一个文件**：每个批次前加一条中文注释横幅，`items:`/`blocks:`
    # 各只保留一个顶层映射（YAML 不允许重复顶层键，拼接会让后面的覆盖前面的）。
    item_lines: list[str] = []
    block_lines: list[str] = []
    for batch in sorted(batches):
        slot = batches[batch]
        if slot["items"]:
            item_lines.append(batch_banner(batch, indent="  "))
            item_lines.extend(slot["items"])
        if slot["blocks"]:
            block_lines.append(batch_banner(batch, indent="  "))
            block_lines.extend(slot["blocks"])
    assert_unique_sections({"items": item_lines, "blocks": block_lines})

    body = (
        "# 由 scripts/bnlib/blocks.py 从上游 block_shapes.txt 生成，请勿手工编辑。\n"
        f"# 共 {len(section_ids(block_lines))} 个方块（物品 {len(section_ids(item_lines))} 个）；"
        "属性取自上游 block_properties.json 与 mineable/* 标签。\n"
        "# 文件内 `# ===== Nx 批次 中文名 =====` 是批次分组注释，不是段边界。\n"
        "items:\n"
        + "".join(item_lines)
        + "blocks:\n"
        + "".join(block_lines)
    )
    output_path = blocks_dir / BLOCKS_OUTPUT_NAME
    output_path.write_text(body, encoding="utf-8")
    written_files: list[str] = [BLOCKS_OUTPUT_NAME]
    # 清掉旧的「每批次一个 yml」产物：留着它们等于同一批 id 在包里被声明两遍。
    for stale in sorted(blocks_dir.glob("*.yml")):
        if stale.name != BLOCKS_OUTPUT_NAME:
            stale.unlink()

    # ---- 分类（CE 浏览器里的分组标签）
    categories_dir.mkdir(parents=True, exist_ok=True)
    block_icons: dict[str, str] = {}
    for entry in report:
        block_icons.setdefault(entry["category"], entry["id"])
    # 分类图标只在「真实存在」的 id 里挑：本次发布的方块（它们也是物品）加上游注册的物品。
    icon_candidates = published_ids | load_item_ids(upstream)
    leaves = build_category_leaves(block_icons, icon_candidates)
    leaf_block = []
    for priority, leaf in enumerate(leaves, start=2):
        leaf_id = leaf["id"]
        leaf_key = leaf["key"]
        leaf_block.append(
            f"  {leaf_id}:\n"
            # 根固定 1，叶子按清单顺序 2..N：CE 的 priority 越小越靠前。
            f"    priority: {priority}\n"
            f"    name: '<!i><white><l10n:category.{NAMESPACE}.{leaf_key}></white>'\n"
            f"    hidden: true\n"
            f"    icon: {leaf['icon']}\n"
            f"    list: []\n"
        )

    root = f"{NAMESPACE}:{NAMESPACE}"
    root_icon = block_icons.get(f"{NAMESPACE}:{CATEGORY_STONES}", report[0]["id"])
    refs = "".join(f"      - '#{leaf['id']}'\n" for leaf in leaves)
    write_text_file(categories_dir / f"{NAMESPACE}.yml", (
        "# 分类结构照参考工程（更好的末地）：一个可见根分类 + 若干 hidden 叶子分类。\n"
        "# 叶子分类 list 留空是故意的：成员由每个 item 的 category: 键推入\n"
        "# （CategoryParser 是 IdSectionConfigParser，重复声明同一 id 会静默覆盖）。\n"
        "categories:\n"
        f"  {root}:\n"
        "    priority: 1\n"
        f"    name: '<!i><white><l10n:category.{NAMESPACE}.name></white>'\n"
        "    lore:\n"
        f"      - '<!i><gray><l10n:category.{NAMESPACE}.lore>'\n"
        f"    icon: {root_icon}\n"
        "    list:\n"
        f"{refs}"
        + "".join(leaf_block)
    ))

    # lang 与分类文件遍历**同一份**清单：两处各留一份白名单，加一个分类就得改两个地方，
    # 漏一处就是「分类存在、名字显示成裸键 category.betternether.tools」。
    root_labels = [
        ("name", ("BetterNether", "更好的地狱")),
        ("lore", ("A server-side port of the BetterNether mod",
                  "BetterNether 模组的服务端移植")),
    ]
    label_entries = root_labels + [(leaf["key"], (leaf["en"], leaf["zh"])) for leaf in leaves]
    # 两个语言的分类译名写进**同一个**语言文件（各占一个 `translations#...` 段）。
    #
    # CE 的语言码取自**段的内层键**，不是文件名：
    # `TranslationManagerImpl$LangParser.parseSection(Pack, Path, ConfigSection)` 遍历段内
    # 键名，逐个调 `addClientTranslation(键名, 该语言下的全部键值)`，而 `addClientTranslation`
    # 是 `clientLangData.computeIfAbsent(语种).addTranslations(...)` —— 累加，不是覆盖。
    # 所以一个文件里放多个语种、多个段都成立（同一段 id 在本包内唯一即可）。
    # extract_bn.build_langs 之后会把自己那两段并进同一个文件。
    lang_lines = [
        "# 由 scripts/bnlib/blocks.py 生成，请勿手工编辑。",
        "# 分类名与分类 lore 的译名。语言码是每个段的内层键（en_us / zh_cn），与文件名无关；",
        "# 段 id（# 之前之外的部分）只要在本包内唯一即可。",
    ]
    for code, index in (("en_us", 0), ("zh_cn", 1)):
        lang_lines.append(f"translations#categories_{code}:")
        lang_lines.append(f"  {code}:")
        for key, values in label_entries:
            lang_lines.append(f'    category.{NAMESPACE}.{key}: "{values[index]}"')
    write_text_file(ce_root / f"configuration/langs/{LANGS_OUTPUT_NAME}",
                    "\n".join(lang_lines) + "\n")
    # 清掉旧的「每语种/每类别一个文件」产物：同一批译名不能在两处重复声明。
    for stale in sorted((ce_root / "configuration/langs").glob("*.yml")):
        if stale.name != LANGS_OUTPUT_NAME:
            stale.unlink()

    # ---- 报告
    # 清单不进 craftengine/ 目录：jar 内 craftengine/ 下的一切都会被 CE 当成包内容扫描。
    # 放到包外的 reports/ 下，只作为构建产物与后续脚本的输入。
    reports_dir = ce_root.parent.parent / "reports"
    manifest_path = reports_dir / "block-manifest.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)

    # 撞名子部件模型清单，供 extract_bn.py 决定「这些模型不释放」。
    #
    # 判定只能是**本次真的会让 CE 生成什么**（`generated_models`）：生成同名模型时若包内
    # 已有同名文件，CE 会报「无法生成模型，因为模型文件已存在」。
    #
    # ## 为什么不再按「贴图名」推算
    #
    # 旧设计让 CE 按 `state.texture` 的名字生成模型，所以那时「贴图名 = 上游某个模型文件名」
    # 就等于撞名，名单里有 `smoker_top`、`soul_vein_1`、`black_apple_seed` 等 8 项。
    # 现在的方块一律写自己的名字（`block/<方块名>`，见 `state_body`）或带 `_ce_` 段的外观名，
    # 那 8 个贴图名永远不会被生成 —— 继续按旧判据列名单的后果是**连带丢掉它们的子模型**：
    # `models/item/smoker.json` 的 parent 正是 `block/smoker_top`，两个一起被排除后
    # `betternether:smoker` 的物品模型和方块实体渲染器就都没有外观了。
    upstream_models = {p.stem for directory in model_dirs() for p in directory.glob("*.json")}
    shadowed = {name for name in generated_models if name in upstream_models}
    shadow_path = reports_dir / "shadowed-models.txt"
    shadow_path.write_bytes(("\n".join(sorted(shadowed)) + "\n").encode("utf-8") if shadowed else b"")
    if shadowed:
        print(f"[CE] {len(shadowed)} 个撞名子部件模型不释放（与 CE 生成的模型同名）："
              + "、".join(sorted(shadowed)))

    # 透明方块的合成模型清单，供 assets.py 在资源包阶段写成真实 JSON。
    #
    # 为什么不在这里直接写：`resourcepack/assets/<ns>/models` 是 assets.py 全权拥有的子树，
    # 它会清理「本次没产出」的残留文件 —— 在这里写会被它当成残留删掉（assets 在本步之后跑）。
    # 交清单让它写，既保证落盘，又保持「资源包只有一个写入者」。
    synthesized_path = reports_dir / "synthesized-models.json"
    synthesized_path.write_text(
        json.dumps({"models": synthesized_models}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if synthesized_models:
        print(f"[CE] {len(synthesized_models)} 个透明方块的模型在构建期合成（items 段只写 path）："
              + "、".join(entry["path"].split("/", 1)[1] for entry in synthesized_models))

    # 家具清单：逐块记录族、碰撞箱真值、hitbox 参数与误差说明。
    # 「非完整方块为什么走家具」的唯一出处，验收按它核对，而不是从产物里反推。
    furniture_path = reports_dir / "furniture.json"
    furniture_path.write_text(
        json.dumps({
            "families": furniture.geometry_report(),
            "blocks": furniture_report,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if furniture_report:
        by_family: dict[str, int] = {}
        for entry in furniture_report:
            by_family[entry["family"]] = by_family.get(entry["family"], 0) + 1
        print(f"[家具] {len(furniture_report)} 个方块转 furniture_item（"
              + "、".join(f"{k}={v}" for k, v in sorted(by_family.items()))
              + "），逐族碰撞箱与 hitbox 见 reports/furniture.json")

    # 满格方块的真实贴图 alpha 结论：记录每个方块用了哪些贴图、哪些带 alpha、哪些缺失。
    # 供验收脚本核对「透明判定依据」而不是只看 renderLayer。
    translucency_path = reports_dir / "translucency.json"
    translucency_path.write_text(
        json.dumps({"blocks": translucency_report}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if translucency_report:
        transparent_blocks = [e["id"] for e in translucency_report if e["translucent"]]
        print(f"[透明] {len(translucency_report)} 个满格方块用真实贴图 alpha 校验；"
              f"{len(transparent_blocks)} 个带透明 -> cutout 载体："
              + "、".join(b.split(":", 1)[1] for b in transparent_blocks))

    # 多状态植物清单：每个方块声明了哪些属性、每个外观用哪个模型/item、哪些上游状态被折叠。
    plant_states_path = reports_dir / "plant-states.json"
    plant_states_path.write_text(
        json.dumps({"blocks": plant_state_reports}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if plant_state_reports:
        print(f"[CE] {len(plant_state_reports)} 个植物/装饰用真实状态 + 模型渲染（无 entity_renderer）："
              + "、".join(entry["id"].split(":", 1)[1] for entry in plant_state_reports))

    # 碰撞保真清单：每个植物/装饰借了哪些池、上游碰撞箱是哪一档、哪里保不住。
    # 这是「14 个 partial 方块怎么处理」的唯一出处，验收与需求方都按它核对，
    # 而不是从产物里反推。
    by_pool: dict[str, int] = {}
    merges = [{"id": entry["id"],
               "from": entry["appearances_before"],
               "to": entry["appearances_after"],
               "dropped": entry["appearances_merged"]["dropped"],
               "redirect": entry["appearances_merged"]["redirect"]}
              for entry in collision_report
              if entry["appearances_merged"]]
    for entry in collision_report:
        for pool in entry["pools"]:
            by_pool[pool] = by_pool.get(pool, 0) + 1
    plant_collision_path = reports_dir / "plant-collision.json"
    plant_collision_path.write_text(
        json.dumps({
            "blocks": collision_report,
            "pool_usage": dict(sorted(by_pool.items())),
            # 写死状态（`state:`）的用量与逐条认领清单：**不进 pool_usage**，
            # 因为它们不从 auto_state 池里分配（分账见 blocks.PinnedAllocator）。
            "pinned_usage": dict(sorted(pinned.used.items())),
            "pinned_states": list(pinned.used_states),
            "pinned_capacity": {entry["name"]: len(entry["states"]) for entry in PINNED_CARRIERS},
            # 逐块的合并清单：额度不够时哪些方块从几个外观合到几个、被并走的键指向谁。
            "appearance_merges": merges,
            "appearance_totals": {
                "before": sum(entry["appearances_before"] for entry in collision_report),
                "after": sum(entry["appearances_after"] for entry in collision_report),
            },
            "pool_capacity": {pool: pool_budget().get(pool, 0) for pool in by_pool},
            "collision_counts": {
                "empty": sum(1 for e in collision_report if e["collision"] == "empty"),
                "partial": sum(1 for e in collision_report if e["collision"] == "partial"),
                "exact": sum(1 for e in collision_report if e["match"] == "exact"),
            },
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if collision_report:
        losses = [e for e in collision_report if e["match"] == "approximate"]
        print(f"[碰撞] {len(collision_report)} 个植物/装饰借真实状态；池占用："
              + "、".join(f"{p}={n}" for p, n in sorted(by_pool.items())))
        if pinned.used:
            print(f"[碰撞] 写死状态载体（不占 auto_state 额度）{len(pinned.used_states)} 个："
                  + "、".join(f"{k}={v}" for k, v in sorted(pinned.used.items()))
                  + "；逐条认领清单见 reports/plant-collision.json 的 pinned_states")
        print(f"[碰撞] 其中 {len(losses)} 个无法保真（上游 partial 或可爬优先），逐条见 "
              "reports/plant-collision.json")

    manifest_path.write_text(
        json.dumps({"blocks": report}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    # 逐条 inventory：659 个方块每个都在这里，含未收录者与原因。
    inventory_path = reports_dir / "block-inventory.json"
    inventory_path.write_text(
        json.dumps({
            "total": len(inventory),
            "included": len(report),
            "excluded": len(excluded),
            "kinds": kind_counts,
            "blocks": inventory,
            "excluded_blocks": excluded,
        }, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    # 硬度对齐清单：每条都是「上游值 → 落盘值 + 依据哪个原版方块」，供需求方逐条核对，
    # 而不是让下调悄悄发生在 260 个方块里。刻意没改的取值写在 `hardness_adjustment` 的注释里。
    hardness_path = reports_dir / "hardness-adjustments.json"
    hardness_path.write_text(
        json.dumps({"blocks": hardness_report}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if hardness_report:
        print(f"[硬度] {len(hardness_report)} 个方块按原版同类对齐：" + "、".join(
            f"{entry['id'].split(':', 1)[1]} {entry['upstream']:g}->{entry['applied']:g}"
            for entry in hardness_report))

    # 译名覆盖自检：在 blocks.py 这一层就把「清单与上游语言文件不一致」暴露出来，
    # 而不是等 extractor 的 build_langs 抛 SystemExit 才卡住整次迁移。
    lang_gaps = verify_lang_coverage(upstream, [e["id"].split(":", 1)[1] for e in report])
    if lang_gaps.get("en_us"):
        raise SystemExit(
            "这些方块的 id 在上游 en_us.json 里没有译名（需要补 LANG_ALIASES，"
            "不要伪造直译）：" + "、".join(lang_gaps["en_us"])
        )
    if lang_gaps.get("zh_cn"):
        preview = "、".join(lang_gaps["zh_cn"][:8])
        print(f"[译名] {len(lang_gaps['zh_cn'])} 个方块缺上游中文名，"
              f"由 extractor 回退英文（上游事实，不是本工程缺口）：{preview}"
              + (" 等" if len(lang_gaps["zh_cn"]) > 8 else ""))
    else:
        print(f"[译名] {len(report)} 个方块均有 en/zh 译名。")

    ores = [entry["id"] for entry in report if entry["ore"]]
    print(f"[CE] 方块配置单文件 {written_files[0]}（{len(section_ids(block_lines))} 个方块）："
          f"批次分组 " + "、".join(
              f"{batch}({len(batches[batch]['items'])})" for batch in sorted(batches)))
    print(f"[CE] 分类 {len(used_categories)} 个，矿物 {len(ores)} 种：{', '.join(ores)}")
    print(f"[CE] 收录 {len(report)} 个（"
          + "、".join(f"{k}={v}" for k, v in sorted(kind_counts.items()))
          + f"）；未收录 {len(excluded)} 个")
    total_generated = sum(len(v) for v in geometry_report.values())
    for parent in sorted(geometry_report):
        names = geometry_report[parent]
        print(f"[CE] 几何 {parent:36s} {len(names):2d} 个：{', '.join(sorted(names))}")
    print(f"[CE] CE 生成的模型 {total_generated} 个，其余用上游自带模型")
    print(f"[状态] 真实视觉状态槽位占用 {sum(allocator.used.values())} 个："
          + "、".join(f"{k}={v}" for k, v in sorted(allocator.used.items()))
          + f"；树叶池仍溢出 {allocator.cutout_overflow} 个")
    print(f"[状态] 剩余额度 {allocator.left}")
    print(f"[状态] 写死状态（state:）认领 {len(pinned.used_states)} 个原版状态，"
          f"余量 {pinned.remaining()} 个（不占 auto_state 额度）："
          + "、".join(f"{k}={v}" for k, v in sorted(pinned.used.items())))
    print(f"[CE] 清单：{manifest_path}")
    print(f"[CE] inventory：{inventory_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

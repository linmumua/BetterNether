#!/usr/bin/env python3
"""物品与配方：把上游 BetterNether 的 item registry / recipe JSON 翻成 CraftEngine 配置。

## 这个模块解决什么

上游 `generated/data/betternether/` 里有两类可以直接搬运的纯数据：

  * `item_registrations.json` —— 714 个物品 id（其中 659 个是方块物品，由 blocks.py 负责）；
  * `recipe/*.json`          —— 739 条配方（555 shaped / 91 stonecutting / 62 shapeless
                                / 18 smithing_transform / 10 smelting / 3 blasting）。

本模块只做这两件事，产出 `configuration/items/bn-items.yml`（`items:` + `recipes:` 两段）。
方块物品、方块掉落、语言文件、方块贴图都不在这里 —— 它们分别属于 blocks.py 与 extract_bn.py。

## 为什么配方要过滤，而不是全量搬运

CE 对「原料/产物 id 不存在」是硬报错（AbstractRecipeSerializer.parseIngredient 直接抛），
所以一条引用了本包没定义的 `betternether:<block>` 的配方，会把整个包加载带崩。
而 blocks.py 目前只生成 659 个方块中的一部分，因此：

  * 每条配方先解析出全部引用 id；
  * 引用只要落在 原版物品 / 本包已定义的方块或物品 / CE 能接受的 `#namespace:tag`
    之内就写出 —— tag **不展开**，因为 CE 的 ingredient 与 addition 都直接接受标签写法，
    挑一个成员展开等于静默改配方语义（见 docs/recipes.md「准备工作 / 标签」）；
  * 落不到的一律丢弃，按 `DropReason`（`reason_kind` + `missing_ids` + `product_status`）
    计入报告，绝不写出半条会炸包的配方。

这条规则同时保证：等 blocks.py 覆盖面扩大后，本模块**不需要改代码**，被丢掉的配方
会自动因为引用变合法而重新出现。判据是「ce_root 里真实存在的定义」，不是写死的名单。

## 标签的两层含义（别混）

  * **语法**：`#betternether:flaming_ruby/repair` 这样写出，CE 能解析；
  * **成员**：CE 侧某个 tag 有哪些成员，来自各物品自己的 `settings.tags`
    （docs/recipes.md 的 palm_planks 示例就是靠这个让自定义木板进入 `#minecraft:planks`）。
    本模块写的 tag 若没有任何物品声明成员，配方语法合法但**不可用** —— 所以
    `_load_tags` 读到的上游成员表只用来校验「成员里至少有一个已发布」，
    真正让标签可用的声明在 blocks.py / 本模块的 `settings.tags` 里。

## schema 依据（不是猜的）

CE jar `craft-engine-paper-plugin-26.10-SNAPSHOT.jar` 里：

  * `resources/default_assets/configuration/blocks/topaz_ore.yml` —— 官方矿物示例，
    同时演示了 `items:` 段的 `data.item_name` / `model` / `behavior` / `texture`，
    以及 `recipes:` 段的 smelting/blasting 写法（`ingredient` 单数 + `experience`）；
  * `CustomShapedRecipe$Serializer` 读取的键：`pattern` / `ingredients` / `result` / `group` / `category`
    （上游 JSON 的 `key` 在这里改名为 `ingredients`；写 `key` 会报 556 条「缺少 ingredient」）；
  * `CustomShapelessRecipe$Serializer`：`ingredients` / `result` / `group` / `category`；
  * `CustomStoneCuttingRecipe$Serializer`：`ingredient` / `result` / `group`；
  * `CustomSmithingTransformRecipe$Serializer`：`template` / `base` / `addition` / `result`
    / `merge_components` / `merge_enchantments` / `template_type`；
  * `CustomSmeltingRecipe$Serializer`：`result` / `group` / `category` / `ingredient` / `time` / `exp`。

所以本模块只写这些键，不发明字段。

## 物品定义为什么长这样

沿用本工程既有产物（`bn-items.yml` 里的矿物产物物品）与 CE 官方 topaz 示例的交集写法：
`material` + `category` + `data.item_name` + `texture`。

`material` 是底层原版物品，决定堆叠数与组件行为；客户端外观由 `texture` 覆盖。
工具/护甲按「原版同档位工具」选 carrier，**不编造耐久/挖掘等级**：上游的具体数值
写在模组 Java 里，静态 YAML 无法逐项验证，所以这里宁可用原版档位，也不假装复刻。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

from bnlib import ore_drops
from bnlib.blocks import assert_unique_sections, section_ids
from bnlib.namespace import NAMESPACE

# ---------------------------------------------------------------- 上游数据位置

REGISTRY_DIR = "src/main/generated"
RECIPE_DIR = "src/main/generated/data/betternether/recipe"
TAG_DIR = "src/main/generated/data/betternether/tags/item"
LOOT_DIR = "src/main/generated/data/betternether/loot_table"
ASSETS_DIR = "src/main/resources/assets/betternether"

# 本模块的产物文件名。读 ce_root 发现「已定义 id」时必须跳过它自己，否则第二次运行
# 会把自己上次写的物品当成「别人已定义」而全部跳过 —— 那就不是幂等而是自吞了。
OUTPUT_NAME = "bn-items.yml"

# ore_drops.py 负责的 4 个矿物产物物品。这些 id 永远由那边定义，本模块不重复写
# （CE 的 IdSectionConfigParser 对重复 id 是静默覆盖，重复定义会让归属变得不可追）。
RESERVED_BY_ORE_DROPS = frozenset({
    "cincinnasite",
    "cincinnasite_ingot",
    "lapis_pile",
    "nether_ruby",
})

# 上游注册表里存在、但静态数据包表达不了的物品前缀。
# 记录在这里是为了让报告能说清「为什么少了这些」，而不是静默消失。
UNSUPPORTED_PREFIXES: tuple[tuple[str, str], ...] = (
    ("spawn_egg_", "刷怪蛋需要一个真实 EntityType；Paper/Folia 插件不能注册新实体类型"),
    ("music_disc_", "唱片需要绑定 jukebox_song 音频资源，本包没有对应声音条目"),
)

# 显式材质载体表：id -> 底层原版物品。
#
# 只收录「语义明确、不会误导玩家」的映射。表里没有的 id 走 `_carrier()` 的后缀规则，
# 再落不到就计入未支持清单 —— 不用 `paper` 之类的东西糊弄过去。
MATERIALS: dict[str, str] = {
    "agave_leaf": "paper",
    "agave_medicine": "potion",
    "herbal_medicine": "potion",
    "hook_mushroom_cooked": "baked_potato",
    "glowstone_pile": "glowstone_dust",
    "stalagnate_bowl_apple": "mushroom_stew",
    "stalagnate_bowl_mushroom": "mushroom_stew",
    "stalagnate_bowl_wart": "mushroom_stew",
    "bowl_upgrade_smithing_template": "netherite_upgrade_smithing_template",
    "cincinnasite_diamond_upgrade_smithing_template": "netherite_upgrade_smithing_template",
    "flaming_ruby_upgrade_smithing_template": "netherite_upgrade_smithing_template",
}

# 工具/护甲：后缀 -> (原版同档位物品名后缀, 原版物品标签)
# `hammer`/`excavator` 是上游自造工具，按最接近的原版行为映射，不另造语义。
TOOL_KINDS: dict[str, tuple[str, str | None]] = {
    "pickaxe": ("pickaxe", "minecraft:pickaxes"),
    "axe": ("axe", "minecraft:axes"),
    "shovel": ("shovel", "minecraft:shovels"),
    "hoe": ("hoe", "minecraft:hoes"),
    "sword": ("sword", "minecraft:swords"),
    "hammer": ("pickaxe", "minecraft:pickaxes"),
    "excavator": ("shovel", "minecraft:shovels"),
    # 剪刀与三叉戟在原版没有档位变体，只能用唯一形态。
    "shears": ("__shears", None),
    "spear": ("__trident", None),
}

ARMOR_KINDS = ("helmet", "chestplate", "leggings", "boots")

# 装备「种类」-> 创造模式分页家族（分类判定的唯一依据）。
#
# 键集合必须覆盖 `TOOL_KINDS` 与 `ARMOR_KINDS`（`test_gear_family_covers_every_kind` 卡死），
# 值就是 `EXTRA_CATEGORIES` 里的三个裸名。分类因此由**种类**驱动，而不是靠 id 后缀再猜一次：
# `nether_ruby_sword` 与 `nether_ruby_pickaxe` 的区别来自它们命中的 `TOOL_KINDS` 键，
# 不来自字符串比较。
GEAR_FAMILY: dict[str, str] = {
    "pickaxe": "tools",
    "axe": "tools",
    "shovel": "tools",
    "hoe": "tools",
    "shears": "tools",
    "hammer": "tools",
    "excavator": "tools",
    "sword": "weapons",
    "spear": "weapons",
    "helmet": "armor",
    "chestplate": "armor",
    "leggings": "armor",
    "boots": "armor",
}

# 上游工具档位 -> 原版同档位前缀。顺序敏感：先长后短，`cincinnasite_diamond_*`
# 必须排在 `cincinnasite_*` 之前，否则会被短前缀先吃掉。
TIERS: tuple[tuple[str, str], ...] = (
    ("cincinnasite_diamond_", "diamond"),
    ("flaming_ruby_", "netherite"),
    ("nether_ruby_", "diamond"),
    ("cincinnasite_", "iron"),
)

# 船/筏：原版对应物的唯一形态。
BOAT_CARRIERS = {"_chest_boat": "oak_chest_boat", "_chest_raft": "oak_chest_boat",
                 "_boat": "oak_boat", "_raft": "oak_boat"}

# 分类归属：沿用既有 categories 里已声明的 3 个叶子分类（misc/stones/woods）。
# 只有「矿物/宝石类材料」进 stones，其余（船、食物、药水、锻造模板）进 misc。
# 装备不再落 misc：它们按种类进下面三个新叶子分类。
GEM_IDS = frozenset({
    "cincinnasite", "cincinnasite_ingot", "nether_ruby", "lapis_pile", "glowstone_pile",
})


# ---------------------------------------------------------------- 分类接口（blocks.py 复用）
#
# 分类文件 `configuration/categories/betternether.yml` 与 `langs/bn-lang.yml` 里的分类译名段的
# 所有权在 blocks.py（它写根分类 + stones/woods/misc 叶子）。本模块新增的三个叶子分类由 blocks.py
# 扫描这 4 个常量后**按同一套格式**写出，所以这里只提供数据，不自己写文件。
#
# 与 blocks.py 现有写法的对应关系（逐字对齐，改这里等于改 blocks.py 的产物）：
#
#     categories:
#       betternether:tools:
#         name: '<!i><white><l10n:category.betternether.tools></white>'
#         hidden: true
#         icon: betternether:<icon_hint>
#         list: []
#
#     translations#categories_zh_cn:
#       zh_cn:
#         category.betternether.tools: "<zh>"

CATEGORY_TOOLS = "tools"
CATEGORY_WEAPONS = "weapons"
CATEGORY_ARMOR = "armor"


@dataclass(frozen=True)
class ItemCategory:
    """一个额外叶子分类的全部展示数据。

    * `name`      —— 裸名，同时是 categories 里的 key 后缀与 lang 键后缀；
    * `en` / `zh` —— lang 里的英文 / 中文标签名；
    * `icon_hint` —— 建议的分类图标（**裸 id**，blocks.py 会自己补命名空间）。
                     与真实物品存在性强绑：`test_extra_category_icons_are_generated` 校验。
    """

    name: str
    en: str
    zh: str
    icon_hint: str

    @property
    def id(self) -> str:
        """完整分类 id，例如 `betternether:tools`。"""
        return f"{NAMESPACE}:{self.name}"

    @property
    def lang_key(self) -> str:
        """lang 文件里的完整键，例如 `category.betternether.tools`。"""
        return f"category.{NAMESPACE}.{self.name}"

    @property
    def label(self) -> tuple[str, str]:
        """`(en_us, zh_cn)` —— 顺序与 blocks.py 的 `labels[key][index]` 一致。"""
        return self.en, self.zh


#: 顺序即 categories 里的期望顺序（工具 -> 武器 -> 护甲）。
EXTRA_CATEGORIES: tuple[ItemCategory, ...] = (
    ItemCategory(CATEGORY_TOOLS, "BetterNether Tools", "更好的地狱：工具", "nether_ruby_pickaxe"),
    ItemCategory(CATEGORY_WEAPONS, "BetterNether Weapons", "更好的地狱：武器", "nether_ruby_sword"),
    ItemCategory(CATEGORY_ARMOR, "BetterNether Armor", "更好的地狱：护甲", "nether_ruby_chestplate"),
)


# 上游没有、但按用户需求补的配方。默认空：本模块默认只搬运上游真有的配方。
# 任何一条写在这里的配方，产物注释会标 `added_by_request`，不会被伪装成上游数据。
ADDED_BY_REQUEST: tuple[dict, ...] = ()


# ---------------------------------------------------------------- 基础读写


def _read_json(path: Path) -> dict | list:
    if not path.is_file():
        raise SystemExit(f"缺少上游数据文件：{path}")
    return json.loads(path.read_text(encoding="utf-8"))


def _bare(identifier: str) -> str:
    """`betternether:foo` -> `foo`；原版 id 原样返回（带冒号）。"""
    prefix = f"{NAMESPACE}:"
    return identifier[len(prefix):] if identifier.startswith(prefix) else identifier


def _ns(identifier: str) -> str:
    """补全本包命名空间；已带命名空间的原样返回。"""
    return identifier if ":" in identifier else f"{NAMESPACE}:{identifier}"


def _scan_ids(path: Path) -> dict[str, set[str]]:
    """扫描一份 CE 配置，返回 {段名: {完整 id}}。

    不用 yaml 解析：CE 配置里会有 `<lang:...>` 这类在 YAML 里合法、但会被某些
    解析器当成特殊标记的内容，而且本工程既有生成器（blocks.py）也是按行输出的。
    这里只需要「顶层段名 + 缩进 2 的 key」，按行扫比引依赖更稳。
    """
    found: dict[str, set[str]] = {}
    section: str | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()
        if indent == 0:
            section = stripped.split(":", 1)[0].strip() if stripped.endswith(":") else None
            continue
        if section is None or indent != 2 or not stripped.endswith(":"):
            continue
        found.setdefault(section, set()).add(stripped[:-1].strip())
    return found


def defined_ids(ce_root: Path, extra: Iterable[str] = ()) -> set[str]:
    """ce_root 里**真实存在**的全部 id（方块 + 物品），作为配方引用的唯一判据。

    `extra` 是集成器传进来的方块清单（manifest）。它在 blocks/*.yml 之外再提供一层
    真值：清单来自 blocks.py 自己的输出，不会凭空多出 CE 最终不存在的 id。
    """
    known: set[str] = set(extra)
    config = ce_root / "configuration"
    for sub in ("blocks", "items"):
        directory = config / sub
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.yml")):
            if path.name == OUTPUT_NAME:
                # 自己的上次产物：不参与判据，否则第二次运行会认为「全都已定义」。
                continue
            for section, ids in _scan_ids(path).items():
                if section in ("blocks", "items"):
                    known |= ids
    return known


def declared_tags(ce_root: Path, extra: Iterable[str] = ()) -> set[str]:
    """ce_root 里**有物品声明**的 tag（`settings.tags` 的并集）。

    这是「CE 侧某个 tag 到底有没有成员」的唯一判据，来自 CE 自己的实现：
    `ItemManager.itemIdsByTag` = `vanillaItemIdsByTag` + `customItemIdsByTag`，
    而 `customItemTags` 只在物品解析时由 `ItemSettings.tags()` 填入。
    换句话说：**没人声明的 tag，CE 眼里就是空标签**。
    """
    tags: set[str] = set(extra)
    config = ce_root / "configuration"
    for sub in ("blocks", "items"):
        directory = config / sub
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.yml")):
            if path.name == OUTPUT_NAME:
                # 自己的上次产物不参与判据，否则同一输入两次运行的报告会不一致。
                continue
            previous = ""
            for line in path.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if stripped.startswith("#") or not stripped:
                    continue
                if previous.endswith("tags:") and stripped.startswith("- "):
                    tags.add(stripped[2:].strip())
                previous = stripped
    return tags



# ---------------------------------------------------------------- 物品定义


@dataclass(frozen=True)
class ItemPlan:
    """一个待生成的物品，以及它为什么这样生成。"""

    item_id: str
    carrier: str
    category: str
    texture: str | None
    tags: tuple[str, ...] = ()
    model: str | None = None
    #: 装备资源 id（`betternether:nether_ruby` 之类）。只有盔甲四件套有值。
    #: 上游把「盔甲贴图」放在 `equipment/<材质>.json` 里，CE 侧必须成对写出
    #: `equipments:` 段并在物品上引用 `settings.equipment.asset_id`，否则穿上是透明的。
    equipment: str | None = None


def _tier_of(item_id: str) -> tuple[str, str] | None:
    for prefix, tier in TIERS:
        if item_id.startswith(prefix):
            return prefix, tier
    return None


def _strip_tier(item_id: str) -> tuple[str, str] | None:
    """装备 id -> (原版同档位前缀, 去掉档位前缀后的「种类片段」)；非装备返回 None。

    `cincinnasite_pickaxe_diamond` 这种「基础档 + 钻石强化」的后缀写法在这里归一化成钻石档。
    这段特判原先写在 `_carrier()` 内部，搬出来是为了让**载体判定与分类判定共用同一份解析结果**：
    两处各自实现一次，迟早会在某个新后缀上漂移（例如分类认为它是工具、载体却解析不出材料）。
    """
    tier = _tier_of(item_id)
    if tier is None:
        return None
    prefix, tier_name = tier
    rest = item_id[len(prefix):]
    if rest.endswith("_diamond"):
        rest = rest[: -len("_diamond")]
        tier_name = "diamond"
    return tier_name, rest


def _kind_of(item_id: str) -> str | None:
    """装备 id -> 种类（`pickaxe` / `sword` / `helmet` …）；不是装备返回 None。

    种类来自 `TOOL_KINDS` / `ARMOR_KINDS` 的显式枚举，不是对 id 做子串匹配 ——
    `cincinnasite_diamond_upgrade_smithing_template` 里含有 `pickaxe` 之类片段时也不会误判。
    """
    parts = _strip_tier(item_id)
    if parts is None:
        return None
    _, rest = parts
    return rest if rest in TOOL_KINDS or rest in ARMOR_KINDS else None


def _carrier(item_id: str) -> tuple[str, str | None] | None:
    """id -> (底层原版物品, 原版物品标签)；返回 None 表示本模块表达不了。"""
    if item_id in MATERIALS:
        return MATERIALS[item_id], None

    for suffix, carrier in BOAT_CARRIERS.items():
        if item_id.endswith(suffix):
            return carrier, None

    parts = _strip_tier(item_id)
    if parts is None:
        return None
    tier_name, rest = parts

    for kind, (carrier_kind, tag) in TOOL_KINDS.items():
        if rest == kind:
            if carrier_kind == "__shears":
                return "shears", None
            if carrier_kind == "__trident":
                return "trident", None
            return f"{tier_name}_{carrier_kind}", tag

    if rest in ARMOR_KINDS:
        return f"{tier_name}_{rest}", None

    return None


def _category(item_id: str) -> str:
    """物品归到哪个叶子分类（与「更好的末地」的 10 类一致，无 tools/weapons/armor 分页）。

    判定顺序：
      1. 装备（工具/武器/护甲）-> misc（更好的末地没有装备分页，统一归杂项）；
      2. 矿物与宝石类材料 -> stones；
      3. 其余（船、食物、药水、锻造模板）-> misc。
    """
    return f"{NAMESPACE}:stones" if item_id in GEM_IDS else f"{NAMESPACE}:misc"



def plan_items(upstream: Path, ce_root: Path,
               extra: Iterable[str] = ()) -> tuple[list[ItemPlan], dict[str, str]]:
    """算出要生成的物品，并把「没生成的原因」一并带出来。"""
    registry = _read_json(upstream / REGISTRY_DIR / "item_registrations.json")
    blocks = {
        _bare(entry["id"])
        for entry in _read_json(upstream / REGISTRY_DIR / "block_registrations.json")
    }
    all_items = [_bare(entry["id"]) for entry in registry]

    textures = upstream / ASSETS_DIR / "textures/item"
    already = defined_ids(ce_root, extra)

    plans: list[ItemPlan] = []
    skipped: dict[str, str] = {}

    for item_id in sorted(set(all_items)):
        full = f"{NAMESPACE}:{item_id}"
        if item_id in blocks:
            continue  # 方块物品归 blocks.py
        if item_id.startswith("debug/"):
            skipped[item_id] = "上游调试用物品，不属于可玩内容"
            continue
        # 用户 2026-10-29 要求「船也不要」：所有船/筏（含箱子船）全部不发布。
        if any(item_id.endswith(suf) for suf in ("_boat", "_raft", "_chest_boat", "_chest_raft")):
            skipped[item_id] = "用户要求移除船/筏"
            continue
        if item_id in RESERVED_BY_ORE_DROPS:
            skipped[item_id] = "由 ore_drops.py 定义（矿物产物），避免重复 id"
            continue
        if full in already:
            skipped[item_id] = "ce_root 已定义同名 id（其它模块负责）"
            continue
        for prefix, reason in UNSUPPORTED_PREFIXES:
            if item_id.startswith(prefix):
                skipped[item_id] = reason
                break
        else:
            resolved = _carrier(item_id)
            if resolved is None:
                skipped[item_id] = "没有可靠的原版载体映射，拒绝用占位物糊弄"
                continue
            carrier, tag = resolved
            has_texture = (textures / f"{item_id}.png").is_file()
            plans.append(ItemPlan(
                item_id=item_id,
                carrier=carrier,
                category=_category(item_id),
                # 缺贴图就不写 texture：让 CE 用 carrier 自带外观，
                # 而不是硬引用一张不存在的图（那是背包里的紫黑格子）。
                texture=f"{NAMESPACE}:item/{item_id}" if has_texture else None,
                tags=(tag,) if tag else (),
                model=(f"{NAMESPACE}:item/{item_id}"
                       if _item_model_exists(upstream, item_id) else None),
                # 盔甲四件套挂上装备资源：上游 `equipment/<材质>.json` 声明了
                # humanoid / humanoid_leggings 两层贴图，材质名就是 id 去掉 `_helmet` 等后缀。
                equipment=_equipment_asset(upstream, item_id),
            ))

    return plans, skipped


#: 盔甲四件套的后缀 → 装备资源的层。`humanoid` 覆盖头盔/胸甲/靴子，
#: `humanoid_leggings` 只给护腿（原版就是这么分的）。
_ARMOR_SUFFIXES = ("helmet", "chestplate", "leggings", "boots")


def _equipment_asset(upstream: Path, item_id: str) -> str | None:
    """这个物品要挂的装备资源 id；非盔甲或上游没有对应 equipment 文件时返回 None。

    判定只用**上游真实存在的文件**（`assets/<ns>/equipment/<材质>.json`），
    不按 id 前缀猜：上游的材质名（cincinnasite / flaming_ruby / nether_ruby）
    恰好等于 id 去掉后缀，但这一点必须由文件存在来确认，否则会写出一个
    指向不存在资源的 `asset_id` —— 那种情况 CE 不报错，穿上就是透明。
    """
    for suffix in _ARMOR_SUFFIXES:
        if not item_id.endswith("_" + suffix):
            continue
        material = item_id[: -len(suffix) - 1]
        for base in ("src/main/generated", "src/main/resources"):
            candidate = upstream / base / "assets" / NAMESPACE / "equipment" / f"{material}.json"
            if candidate.is_file():
                return f"{NAMESPACE}:{material}"
        return None
    return None


def _item_model_exists(upstream: Path, item_id: str) -> bool:
    """上游自带 `models/item/<id>.json`（assets 会原样释放）时，物品只引用不生成。"""
    return any((upstream / base / f"assets/{NAMESPACE}/models/item/{item_id}.json").is_file()
               for base in ("src/main/resources", "src/main/generated"))


def _item_block(plan: ItemPlan) -> str:
    lines = [f"  {NAMESPACE}:{plan.item_id}:"]
    if plan.carrier:
        lines.append(f"    material: {plan.carrier}")
    lines.append(f"    category: {plan.category}")
    settings: list[str] = []
    if plan.tags:
        settings.append("      tags:")
        settings.extend(f"        - {tag}" for tag in plan.tags)
    if plan.equipment:
        # CE 的 `settings.equipment.asset_id` 指向 `equipments:` 段里的资源 id。
        # 少了这一行，盔甲仍然能穿（原版 equippable 还在），但用的是原版材质贴图
        # —— 用户 2026-10-31 报的「nether 盔甲没有 3D 材质」就是这个。
        settings.append("      equipment:")
        settings.append(f"        asset_id: {plan.equipment}")
    if settings:
        lines.append("    settings:")
        lines.extend(settings)
    lines.append("    data:")
    lines.append(f"      item_name: '<!i><white><lang:item.{NAMESPACE}.{plan.item_id}></white>'")
    if plan.model:
        # 已有模型：只写 path，CE 不再生成，避免「模型文件已存在」。
        lines.append(f"    model: {plan.model}")
    elif plan.texture:
        lines.append(f"    texture: {plan.texture}")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 配方


# 上游 recipe type -> CE recipe type。
# CE 官方文档用 `shaped` / `shapeless`（不带 `crafting_` 前缀），
# 见 docs/recipes.md「有序合成配方」和「无序合成配方」的示例。
# 带前缀的写法会让 CE 当作未知类型，报 556 条「缺少 ingredient」。
RECIPE_TYPES = {
    "minecraft:crafting_shaped": "shaped",
    "minecraft:crafting_shapeless": "shapeless",
    "minecraft:stonecutting": "stonecutting",
    "minecraft:smithing_transform": "smithing_transform",
    "minecraft:smelting": "smelting",
    "minecraft:blasting": "blasting",
    "minecraft:smoking": "smoking",
    "minecraft:campfire_cooking": "campfire_cooking",
}


# ---------------------------------------------------------------- 原料/产物的受控替换
#
# 这两张表都是**近似**，每一条都要能说清「为什么可以近似」。表里没有的一律不做替换：
# 宁可丢一条配方并记录原因，也不要悄悄把玩家熟悉的配方改成本。

#: tag -> 本包可用的替代 ingredient。
TAG_SUBSTITUTIONS: dict[str, str] = {
    # 箱子船：`#c:chests` 的 14 个成员全是 BN 自己的箱子，而箱子（上游类 `ChestBlock`）
    # 在 publication.REMOVED_CLASSES 里 —— CE 没有 GUI，右键没有任何反应，所以一个都没发布。
    # 用 `minecraft:chest` 代替：**这是「任意箱子」语义的近似，不是等价**
    # （原版箱子船也只认原版箱子；玩家手里的 BN 箱子本来就不存在，无损表达无从谈起）。
    "#c:chests": "minecraft:chest",
}

#: id -> 已发布的同义 id。
ID_SUBSTITUTIONS: dict[str, str] = {
    # 荧光灯：上游配方要 2× `gloomsculk_geode_crystal` + 1× `gloomsculk_geode`。
    # 前者没发布（同族里 `gloomsculk_crystal` 才是已发布的那个），后者已发布。
    # 收敛到 `gloomsculk_crystal`：**近似**（少一个「水晶嵌在晶洞上」的工序），
    # 但代价方向正确 —— 玩家做出的是同族已发布的方块，而不是拿不到的材料。
    "betternether:gloomsculk_geode_crystal": "betternether:gloomsculk_crystal",
    # 2026-10-30：上游 `mushroom_fir_stem_to_log` 配方引用了 `mushroom_fir_stem`，
    # 但上游 Java **从未注册**这个方块（只有遗留 blockstate，渲染的是
    # `mushroom_fir_trunk_middle`）。属于上游自身的坏 id，按渲染实际指向收敛到
    # `mushroom_fir_trunk`（配方语义「4 树干 → 4 原木」也才成立）。
    "betternether:mushroom_fir_stem": "betternether:mushroom_fir_trunk",
}

#: 台阶原料的替代顺序：同材质的整块（先木板、再全块、再砖）。
#:
#: 上游有 25 条配方（11 桶 + 11 雕纹书架 + `bone_tile` + `cincinnasite_tile_large`
#: + `farmland`）把**台阶**当原料，而本包的台阶全部不发布（`publication.SHAPE_SUFFIXES`，
#: 异形块要吃一整个方块状态预算）。用户裁决：这些配方要恢复可合成，做法是把台阶换成
#: **同种材质的完整方块**。这是**有意的材料成本上升**（1 台阶 -> 1 整块），
#: 不是等价替换 —— 所以每条替换都会写进配方注释与 `reports/item-recipes.json`。
_SLAB_REPLACEMENT_SUFFIXES: tuple[str, ...] = ("_planks", "_block", "_bricks")

#: 显式替换的原因文案（进配方注释 + 报告）。台阶是动态原因，见 `_substitution_reason`。
SUBSTITUTION_REASONS: dict[str, str] = {
    "#c:chests": "成员（BN 箱子）全被移除，改用 minecraft:chest（「任意箱子」近似，非等价）",
    "betternether:gloomsculk_geode_crystal": "该原料未发布，收敛到同族已发布的 gloomsculk_crystal",
}



# ---------------------------------------------------------------- 丢弃原因（结构化）
#
# 只写一句「引用了未定义的 id」，484 条丢弃就无法归类，也就无法回答
# 「哪些是等 blocks.py 扩面就能回来的、哪些是需要用户裁决的」。所以拆成四要素：
#   reason_kind    —— 分类词表（下面的常量），可被统计
#   detail         —— 人读的一句话，保留可读性
#   missing_ids    —— 到底是什么引用没闭合（逐条可追）
#   product_status —— 产物本身的状态（published / removed / seat / unplaced / unknown）
#   blocked        —— 是否在等用户裁决（而不是等代码补覆盖）

DROP_PRODUCT_REMOVED = "product_removed"      # 产物已移除（台阶/楼梯/门/容器……）
DROP_PRODUCT_SEAT = "product_seat"            # 产物是 bclib 座椅，本包不发布
DROP_PRODUCT_UNPLACED = "product_unplaced"    # 产物是上游方块，但本包没放置它
DROP_MATERIAL_REMOVED = "material_removed"    # 原料已移除（同上，永远补不回来）
DROP_MATERIAL_UNPLACED = "material_unplaced"  # 原料是上游方块，本包没放置（等扩面）
DROP_UNPUBLISHED_TAG = "unpublished_tag"      # 原料 tag 上游没有，或成员全未发布
DROP_STEP_MATERIAL = "step_material"          # 原料是本包不发布的台阶/楼梯
DROP_TAG_MULTI_MEMBER = "tag_multi_member"    # 仅因 tag 多成员（词表保留：已改为直出 tag）
DROP_OTHER = "other"

DROP_KINDS: tuple[str, ...] = (
    DROP_PRODUCT_REMOVED, DROP_PRODUCT_SEAT, DROP_PRODUCT_UNPLACED,
    DROP_MATERIAL_REMOVED, DROP_MATERIAL_UNPLACED,
    DROP_UNPUBLISHED_TAG, DROP_STEP_MATERIAL, DROP_TAG_MULTI_MEMBER, DROP_OTHER,
)


@dataclass(frozen=True)
class DropReason:
    """一条配方没写出去的原因。"""

    kind: str
    detail: str
    missing_ids: tuple[str, ...] = ()
    product_status: str = "published"
    blocked: bool = False

    def as_dict(self) -> dict[str, object]:
        return {
            "reason_kind": self.kind,
            "detail": self.detail,
            "missing_ids": list(self.missing_ids),
            "product_status": self.product_status,
            "blocked": self.blocked,
        }


def _bare_name(identifier: str) -> str:
    return identifier.split(":", 1)[-1]


def _is_step(identifier: str) -> bool:
    """本包不发布的台阶/楼梯（`publication.SHAPE_SUFFIXES` 是唯一判据）。"""
    from bnlib.publication import SHAPE_SUFFIXES

    return _bare_name(identifier).endswith(SHAPE_SUFFIXES)


def _is_seat(identifier: str) -> bool:
    """bclib 座椅：blocks.py 的 `is_seat` 是权威名单，这里延迟导入而不是抄一份。"""
    from bnlib import blocks

    return blocks.is_seat(_bare_name(identifier))


def _is_removed(identifier: str) -> bool:
    """本包已确认移除的方块（台阶/楼梯/门/容器…）；原版 id 恒假。"""
    from bnlib.publication import is_removed_block

    return is_removed_block(identifier)


def _product_status(product: str | None, known: set[str]) -> str:
    if not product:
        return "unknown"
    if _is_published(product, known):
        return "published"
    if _is_seat(product):
        return "seat"
    if _is_removed(product):
        return "removed"
    return "unplaced"


def _drop_reason(data: dict, kind: str, missing: Sequence[str],
                 known: set[str]) -> DropReason:
    """把「引用没闭合」拆成可统计的类别。

    判定顺序：先看产物（产物都拿不到，补原料也没意义），再看原料。
    """
    result = data.get("result") or {}
    product = result.get("id") if isinstance(result, dict) else result
    status = _product_status(product, known)
    unique = tuple(sorted(set(missing)))

    if status == "removed":
        reason_kind = DROP_PRODUCT_REMOVED
        detail = f"产物 {product} 已被移除（台阶/楼梯/门/容器等本包不发布的方块）"
    elif status == "seat":
        reason_kind = DROP_PRODUCT_SEAT
        detail = f"产物 {product} 是 bclib 座椅，本包不发布该方块"
    elif status == "unplaced":
        reason_kind = DROP_PRODUCT_UNPLACED
        detail = f"产物 {product} 是上游注册方块，但本包没有放置它"
    elif unique and all(_is_step(ref) for ref in unique):
        # 台阶/楼梯的「换成木板」会改成本（6 木板 -> 4 台阶 vs 4 木板 -> 4 台阶），
        # 属于产品决策而不是数据缺口，所以标记 blocked 等用户裁决，不擅自替换。
        reason_kind = DROP_STEP_MATERIAL
        detail = ("原料是本包不发布的台阶/楼梯（可换成木板，但会改成本）："
                  + "、".join(unique))
    elif unique and all(ref.startswith("#") for ref in unique):
        reason_kind = DROP_UNPUBLISHED_TAG
        detail = "原料标签上游没有该 tag，或成员全部未发布：" + "、".join(unique)
    elif unique and all(_is_removed(ref) for ref in unique):
        reason_kind = DROP_MATERIAL_REMOVED
        detail = "原料是本包已移除的方块（永不发布）：" + "、".join(unique)
    elif unique and all(not _is_published(ref, known) for ref in unique):
        reason_kind = DROP_MATERIAL_UNPLACED
        detail = "原料是上游注册方块，但本包没有放置它：" + "、".join(unique)
    else:
        reason_kind = DROP_OTHER
        detail = "引用了本包尚未定义的 id：" + "、".join(unique)

    return DropReason(
        kind=reason_kind,
        detail=detail,
        missing_ids=unique,
        product_status=status,
        blocked=reason_kind == DROP_STEP_MATERIAL,
    )



@dataclass
class RecipeReport:
    emitted: int = 0
    added_by_request: int = 0
    emitted_ids: list[str] = field(default_factory=list)
    dropped: dict[str, DropReason] = field(default_factory=dict)
    by_type: dict[str, int] = field(default_factory=dict)
    #: 配方 id -> 该配方里做过的受控替换（原料 -> 替代原料 -> 原因）。
    substitutions: dict[str, list[dict[str, str]]] = field(default_factory=dict)
    #: tag 引用 -> 用到它的配方 id（用于「标签有没有成员」的生成期自检）。
    tags_used: dict[str, list[str]] = field(default_factory=dict)
    #: 真正被写出的配方的**产物**完整 id（判断「某物品有没有配方来源」的判据）。
    produced: set[str] = field(default_factory=set)

    def dropped_by_kind(self) -> dict[str, int]:
        """按 reason_kind 汇总丢弃条数（报告与测试共用，避免两处各统计一次）。"""
        counts: dict[str, int] = {}
        for reason in self.dropped.values():
            counts[reason.kind] = counts.get(reason.kind, 0) + 1
        return dict(sorted(counts.items()))

    def tags_without_members(self, declared: set[str]) -> dict[str, list[str]]:
        """写出去了、但 ce_root 里没有任何物品声明成员的标签。

        CE 的 `parseIngredient` 对空标签抛 `KnownResourceException(
        resource.recipe.ingredient.invalid_tag)`（由 `Config` 逐资源捕获后记日志、
        跳过该配方）—— 不会炸整个包，但这条配方不会加载。生成期把这件事说出来，
        比等玩家发现「木板做不出来」强。
        """
        gaps: dict[str, list[str]] = {}
        for tag, recipes in sorted(self.tags_used.items()):
            if tag.startswith("#minecraft:"):
                # 原版标签由游戏提供，成员表在服务端，生成期无从查也无需查。
                continue
            # `declared_tags()` 存的是 `settings.tags` 里的裸值（不带 `#`）。
            if tag[1:] not in declared:
                gaps[tag] = sorted(recipes)
        return gaps



def _load_tags(upstream: Path) -> dict[str, list[str]]:
    """读上游 item tag（`#betternether:x/y` 与 `#c:x/y` 的原料需要知道成员才能判断可表达）。

    上游同时生成了惯例标签 `data/c/tags/item/**`（Fabric/Forge 通用标签，例如
    `#c:ingots/iron` 只含 1 个成员）。漏掉 `c:` 命名空间会让一批本来可无损展开的
    配方被误判成不可表达。

    这里读到的成员表**只用于校验**（tag 是否存在、成员里有没有已发布的），不再作为
    「必须展开成单 id」的依据 —— CE 的 ingredient 直接接受 `#namespace:tag`。
    `minecraft` 命名空间的标签不在上游数据包里（由游戏自带），因此不在此读取。
    """
    tags: dict[str, list[str]] = {}
    for namespace in ("betternether", "c"):
        base = upstream / "src/main/generated/data" / namespace / "tags/item"
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.json")):
            rel = path.relative_to(base).with_suffix("").as_posix()
            values = _read_json(path).get("values") or []
            flat: list[str] = []
            for value in values:
                flat.append(value["id"] if isinstance(value, dict) else value)
            tags[f"#{namespace}:{rel}"] = flat
    return tags


def _is_published(identifier: str, known: set[str]) -> bool:
    """这个 id 在本包里「拿得到」吗：原版 id 恒真，本包 id 看 ce_root 是否真的定义。"""
    if identifier.startswith("minecraft:"):
        return True
    return identifier in known


def _full_block_for(reference: str, known: set[str]) -> str | None:
    """`betternether:stalagnate_slab` -> `betternether:stalagnate_planks`（同材质整块）。

    只认本包的 `_slab`：上游把台阶当原料的 25 条配方才有救，`_stairs` 不猜
    （楼梯的「同材质整块」在不同族里指代不一样，猜错就是一条静默改错的配方）。
    """
    if not reference.startswith(f"{NAMESPACE}:") or not reference.endswith("_slab"):
        return None
    base = reference[: -len("_slab")]
    for suffix in _SLAB_REPLACEMENT_SUFFIXES:
        candidate = f"{base}{suffix}"
        if candidate in known:
            return candidate
    return base if base in known else None


def _substitution_reason(source: str) -> str:
    explicit = SUBSTITUTION_REASONS.get(source)
    if explicit is not None:
        return explicit
    if source.endswith("_slab"):
        return ("上游用台阶，本包台阶不发布；改用同材质整块，材料成本上升"
                "（有意的成本变更，不是等价替换）")
    # 兜底：走的是 SUBSTITUTION_REASONS / PRODUCT_SUBSTITUTIONS 里登记过的受控替换，
    # 但没有更具体的理由文案。这里必须**明确写出这是近似**，不能只说"见替换表" ——
    # 报告是给玩家和后人看的，读到"受控替换"却不知道差异在哪，等于没有留痕。
    return "受控替换（近似，非等价）：替代关系与差异逐条登记在 items.py 的替换表里"


def _substitution_note(source: str) -> str:
    """配方注释里用的替换说明：**不得含任何 `namespace:path` 形式的 id**。

    注释会被门禁按文本扫描（`verify_block_contracts.py` 的 `removal.reference`：
    「产物里不得引用已移除的方块 id」，它读的是全文，不看 YAML 结构）。把被替换掉的
    裸 id 写进注释，等于在产物里留了一个已移除方块的引用 —— 门禁是对的，注释该改。
    可机读的明细在 `reports/item-recipes.json` 的 `substitutions` 段。
    """
    if source.startswith("#"):
        return "上游原料标签的成员全部未发布，改用可获得的替代原料"
    if source.endswith("_slab"):
        return "上游用台阶，本包台阶不发布，改用同材质整块"
    return "上游原料未发布，收敛到同族已发布的物品"



def _resolve(reference: str, known: set[str], tags: dict[str, list[str]],
             substitute: bool = True) -> str | None:
    """把一个 id（可能是 tag）解析成 CE 能接受的写法；解析不了返回 None。

    `substitute=False` 用于**产物**位置：产物绝不能被替换，否则「木板 -> 木板」这种
    自吞配方会被写出去（台阶产物正是 `_full_block_for` 的命中对象）。

    tag 的处理原则（见 docs/recipes.md「准备工作 / 标签」）：

      * CE 的 ingredient / addition 直接接受 `#namespace:tag`，所以多成员 tag **不必**
        也不应该展开 —— 展开会挑一个成员，静默改变配方语义；
      * 单成员 tag 仍展开成单 id（少一层「标签在 CE 侧有没有成员」的依赖），
        这是保留下来的可选路径；
      * 成员全部未发布的 tag 视为不可表达（写出去就是一条空标签配方）；
      * `#minecraft:*` 由游戏提供，上游数据包里没有它的成员表，无从校验也不需要校验。
    """
    if not isinstance(reference, str) or not reference:
        return None
    if reference.startswith("#"):
        return _resolve_tag(reference, known, tags)
    if substitute:
        reference = ID_SUBSTITUTIONS.get(reference, reference)
    if reference.startswith(f"{NAMESPACE}:"):
        if reference in known:
            return reference
        return _full_block_for(reference, known) if substitute else None
    if reference.startswith("minecraft:"):
        return reference
    return None  # 其它命名空间（模组内部引用）在本包里不存在



def _resolve_tag(reference: str, known: set[str],
                 tags: dict[str, list[str]]) -> str | None:
    substituted = TAG_SUBSTITUTIONS.get(reference)
    if substituted is not None:
        return substituted
    namespace = reference[1:].split(":", 1)[0]
    if namespace == "minecraft":
        return reference
    members = tags.get(reference)
    if not members:
        return None
    if len(members) == 1:
        return _resolve(members[0], known, tags)
    if not any(_is_published(member, known) for member in members):
        return None
    return reference



def _references(kind: str, data: dict) -> list[str]:
    """列出一条配方引用的全部 id（原料 + 产物），用于闭合判断。"""
def _ingredients(kind: str, data: dict) -> list[str]:
    """一条配方的全部**原料**引用（不含产物）。

    原料与产物必须分开：原料可以走替换表（台阶 -> 同材质整块），产物绝不能 ——
    否则「台阶产物」会被替换成整块，写出「木板 -> 木板」这种自吞配方。
    """
    refs: list[str] = []
    if kind in ("shaped", "crafting_shaped"):
        for value in (data.get("key") or {}).values():
            refs.extend(value if isinstance(value, list) else [value])
    elif kind in ("shapeless", "crafting_shapeless"):
        for value in data.get("ingredients") or []:
            refs.extend(value if isinstance(value, list) else [value])
    elif kind in ("stonecutting", "smelting", "blasting", "smoking", "campfire_cooking"):
        refs.append(data.get("ingredient"))
    elif kind == "smithing_transform":
        refs.extend([data.get("template"), data.get("base"), data.get("addition")])
    return [ref for ref in refs if ref]


def _result_of(data: dict) -> str | None:
    result = data.get("result") or {}
    return result.get("id") if isinstance(result, dict) else result


def _references(kind: str, data: dict) -> list[str]:
    """一条配方引用的全部 id（原料 + 产物），用于闭合自检与报告。"""
    refs = _ingredients(kind, data)
    result = _result_of(data)
    if result:
        refs.append(result)
    return refs


def _substitutions(kind: str, data: dict, known: set[str],
                   tags: dict[str, list[str]]) -> list[dict[str, str]]:
    """这条配方里实际发生了哪些受控替换（原原料 -> 替代原料 -> 原因）。

    只记录**真的写出去**的条目 —— 被丢弃的配方里发生过的替换没有意义，
    记进报告只会让「替换清单」变成噪声。
    """
    applied: list[dict[str, str]] = []
    for ref in _ingredients(kind, data):
        resolved = _resolve(ref, known, tags)
        if resolved is None or resolved == ref:
            continue
        if ref.startswith("#") and ref not in TAG_SUBSTITUTIONS:
            # 单成员 tag 展开成单 id 是无损的（成员唯一，语义不变），不算替换；
            # 把它记进替换清单会让「成本变了哪些配方」淹没在噪声里。
            continue
        applied.append({
            "from": ref,
            "to": resolved,
            "reason": _substitution_reason(ref),
            # 替换表里每一条都是**近似**：台阶换整块改了成本，箱子船换原版箱子改了语义。
            # 显式写出来，读者不必再从 reason 里猜。
            "equivalent": False,
        })
    return applied


def _unresolved(kind: str, data: dict, known: set[str],
                tags: dict[str, list[str]]) -> list[str]:
    """解析不出来的引用：原料按可替换口径判，产物按不可替换口径判（与写出口径一致）。"""
    missing = [ref for ref in _ingredients(kind, data)
               if _resolve(ref, known, tags) is None]
    result = _result_of(data)
    if result and _resolve(result, known, tags, substitute=False) is None:
        missing.append(result)
    return missing



def _yaml_value(reference: str) -> str:
    """把一个已解析的引用写进 YAML 标量。

    标签引用**必须带引号**：YAML 里行内 `#` 之后是注释，`'S': #c:chests` 会被读成空值，
    CE 随即报「缺少 ingredient」，而这个错误信息完全指不回真正的原因。
    """
    return f'"{reference}"' if reference.startswith("#") else reference


def _recipe_yaml(recipe_id: str, kind: str, data: dict, known: set[str],
                 tags: dict[str, list[str]], source: str) -> str | None:
    """生成一条 CE 配方；任何引用解析不了就返回 None（调用方记入丢弃清单）。"""
    refs = _ingredients(kind, data)
    resolved = [_resolve(ref, known, tags) for ref in refs]
    result_id = _resolve(_result_of(data), known, tags, substitute=False)
    if any(item is None for item in resolved) or result_id is None:
        return None

    result = data.get("result") or {}
    result_count = int(result.get("count", 1)) if isinstance(result, dict) else 1

    lines = [f"  # {source}"]
    applied = _substitutions(kind, data, known, tags)
    if applied:
        # 注释只写「发生了什么、后果是什么」，绝不写被替换掉的裸 id：
        # 门禁按全文扫描已移除方块 id，注释里的裸 id 同样算违规引用。
        # 逐条明细（原原料 -> 替代原料 -> 原因）在 reports/item-recipes.json 的 substitutions 段。
        notes = sorted({_substitution_note(entry["from"]) for entry in applied})
        lines.append(
            f"  # 原料替换 {len(applied)} 处：{'；'.join(notes)}"
            "（材料成本/语义与上游不同，非等价替换；明细见 reports/item-recipes.json）")
    lines.extend([f"  {NAMESPACE}:{recipe_id}:", f"    type: {kind}"])


    if data.get("group"):
        lines.append(f"    group: {data['group']}")

    if kind in ("shaped", "crafting_shaped"):
        lines.append("    pattern:")
        lines.extend(f"      - '{row}'" for row in data["pattern"])
        lines.append("    ingredients:")
        for symbol, value in data["key"].items():
            lines.append(f"      '{symbol}': {_yaml_value(_resolve(value, known, tags))}")
    elif kind in ("shapeless", "crafting_shapeless"):
        lines.append("    ingredients:")
        for value in data["ingredients"]:
            lines.append(f"      - {_yaml_value(_resolve(value, known, tags))}")
    elif kind == "smithing_transform":
        # 上游 JSON 叫 `template`，CE 的 CustomSmithingTransformRecipe.Serializer 读的是
        # `template_type`（docs/recipes.md「锻造升级配方」；本工程 BetterEnd B7-metals.yml 同写法）。
        # 写成 `template` 会被 CE 当模板参数解析，报「无效的模板」。
        lines.append(f"    template_type: {_yaml_value(_resolve(data['template'], known, tags))}")
        for key in ("base", "addition"):
            lines.append(f"    {key}: {_yaml_value(_resolve(data[key], known, tags))}")
    else:
        lines.append(f"    ingredient: {_yaml_value(_resolve(data['ingredient'], known, tags))}")


    if kind in ("smelting", "blasting", "smoking", "campfire_cooking"):
        lines.append("    category: misc")
        lines.append(f"    time: {int(data.get('cookingtime', 200))}")
        lines.append(f"    experience: {float(data.get('experience', 0.0))}")

    lines.append("    result:")
    lines.append(f"      id: {result_id}")
    lines.append(f"      count: {result_count}")
    return "\n".join(lines) + "\n"


def _added_recipe_yaml(spec: dict, known: set[str], tags: dict[str, list[str]]) -> str | None:
    """生成一条**上游没有**的配方，注释里明确标 `added_by_request`。

    存在的意义：用户要求的部分内容上游确实没写（例如「精准采集挖下来的矿方块能直接烧」），
    这类配方必须一眼可辨，不能混进上游数据里冒充。
    """
    kind = spec["type"]
    data = dict(spec)
    refs = _ingredients(kind, data)
    result_id = _resolve(_result_of(data), known, tags, substitute=False)
    if any(_resolve(ref, known, tags) is None for ref in refs) or result_id is None:
        return None
    block = _recipe_yaml(spec["id"], kind, data, known, tags, "added_by_request")
    return block


def plan_recipes(upstream: Path, ce_root: Path,
                 extra: Iterable[str] = ()) -> tuple[list[str], RecipeReport]:
    """把上游 recipe/*.json 翻成 CE 配方块，丢弃不可表达的并记录原因。"""
    known = defined_ids(ce_root, extra)
    tags = _load_tags(upstream)
    report = RecipeReport()
    blocks: list[str] = []

    base = upstream / RECIPE_DIR
    if not base.is_dir():
        raise SystemExit(f"找不到上游配方目录：{base}")

    for path in sorted(base.glob("*.json")):
        data = _read_json(path)
        upstream_type = data.get("type")
        kind = RECIPE_TYPES.get(upstream_type)
        source = f"{RECIPE_DIR}/{path.name}"
        if kind is None:
            report.dropped[path.stem] = DropReason(
                kind=DROP_OTHER,
                detail=f"配方类型 {upstream_type} 在 CE 没有对应序列化器",
                product_status="unknown",
            )
            continue

        block = _recipe_yaml(path.stem, kind, data, known, tags, source)
        if block is None:
            report.dropped[path.stem] = _drop_reason(
                data, kind, _unresolved(kind, data, known, tags), known)
            continue

        blocks.append(block)
        report.emitted += 1
        report.emitted_ids.append(path.stem)
        report.by_type[kind] = report.by_type.get(kind, 0) + 1
        product = _result_of(data)
        if product:
            report.produced.add(product)
        applied = _substitutions(kind, data, known, tags)
        if applied:
            report.substitutions[path.stem] = applied
        # 记下这条配方用到了哪些 tag，供「标签有没有成员」的生成期自检使用。
        for ref in _ingredients(kind, data):
            if not ref.startswith("#"):
                continue
            resolved = _resolve(ref, known, tags)
            if resolved is not None and resolved.startswith("#"):
                report.tags_used.setdefault(resolved, []).append(path.stem)

    # 上游没有、按用户需求补的配方。默认表为空；一旦有内容，注释里带 `added_by_request`，
    # 报告里也单列，绝不被读成「上游本来就有」。
    for spec in ADDED_BY_REQUEST:
        block = _added_recipe_yaml(spec, known, tags)
        if block is None:
            report.dropped[spec["id"]] = DropReason(
                kind=DROP_OTHER,
                detail="added_by_request 配方的引用同样没有闭合，未写出",
                product_status="unknown",
            )
            continue
        blocks.append(block)
        report.emitted += 1
        report.added_by_request += 1
        report.by_type[spec["type"]] = report.by_type.get(spec["type"], 0) + 1

    return blocks, report


# ---------------------------------------------------------------- 产物


def items_yaml(upstream: Path, ce_root: Path, extra: Iterable[str] = ()
               ) -> tuple[str, list[ItemPlan], dict[str, str], RecipeReport]:
    plans, skipped = plan_items(upstream, ce_root, extra)
    # 配方闭合判断必须把「本模块这次即将写出的物品」也算作已定义，
    # 否则引用这些物品的配方（例如钻石强化工具的 smithing）会被误判成未闭合而丢弃。
    # 判据仍然是确定性的：同一输入得到同一 plans 集合，重复运行结果一致。
    planned = {f"{NAMESPACE}:{plan.item_id}" for plan in plans}
    recipes, report = plan_recipes(upstream, ce_root, (*tuple(extra), *sorted(planned)))

    # 矿物产物物品与烧制配方由 ore_drops 提供。`configuration/items/` 只允许一个 yml，
    # 所以两边的条目必须并进**同一个** `items:` / `recipes:` 顶层映射
    # （YAML 不允许重复顶层键：第二段 `items:` 会把第一段整段覆盖掉）。
    ore_items, ore_recipes = ore_drops.items_sections(upstream)

    item_lines = _group_banner("普通物品（上游 item_registrations.json）")
    item_lines += "".join(_item_block(plan) for plan in plans)
    if ore_items:
        item_lines += _group_banner("矿物产物（ore_drops.py）")
        item_lines += "".join(ore_items)

    recipe_lines = _group_banner("普通配方（上游 recipe/*.json）")
    recipe_lines += "".join(recipes)
    if ore_recipes:
        recipe_lines += _group_banner("烧制配方（ore_drops.py：熔炉 200t、高炉 100t）")
        recipe_lines += "".join(ore_recipes)

    # 装备资源：盔甲贴图不在物品模型里，而在 `equipments:` 段声明的 humanoid 层。
    # 不写这段、或物品的 `settings.equipment.asset_id` 指不到这里，穿上就是透明
    # （用户 2026-10-31 报的「nether 盔甲没有材质」）。
    equipment_lines = _equipments_section(plans)

    item_rows = item_lines.splitlines(keepends=True)
    recipe_rows = recipe_lines.splitlines(keepends=True)
    equipment_rows = equipment_lines.splitlines(keepends=True)
    assert_unique_sections({"items": item_rows, "recipes": recipe_rows,
                            "equipments": equipment_rows})

    header = [
        "# 由 scripts/bnlib/items.py 生成，请勿手工编辑。",
        "# 物品来自上游 item_registrations.json（剔除方块物品与 ore_drops 负责的矿物产物），",
        "# 配方来自上游 recipe/*.json，只保留全部引用都能落到本包已定义 id 的那些。",
        f"# 共 {len(section_ids(item_rows))} 个物品、{len(section_ids(recipe_rows))} 条配方；"
        "矿物产物与烧制配方来自 ore_drops.py。",
        "# `# ===== 分组 =====` 只是注释分组，整份文件只有一对 `items:` / `recipes:` 顶层映射。",
        "items:",
    ]
    body = "\n".join(header) + "\n" + item_lines + "recipes:\n" + recipe_lines
    if equipment_lines:
        body += "equipments:\n" + equipment_lines
    return (body, plans, skipped, report)


def _equipments_section(plans: list[ItemPlan]) -> str:
    """盔甲贴图的装备资源段。

    只写**上游真实存在**的材质（由 `_equipment_asset` 核过文件），结构抄上游
    `equipment/<材质>.json`：humanoid 与 humanoid_leggings 两层都指向同一张图。
    `texture` 只写不带扩展名、不带 `textures/` 前缀的 id —— 资源包里对应的文件是
    `assets/betternether/textures/entity/equipment/<层>/<材质>.png`（assets.py 已释放）。
    """
    materials = sorted({plan.equipment for plan in plans if plan.equipment})
    if not materials:
        return ""
    lines = ["  # 盔甲装备资源（贴图来自上游 textures/entity/equipment/）"]
    for asset in materials:
        material = asset.split(":", 1)[1]
        lines.append(f"  {asset}:")
        lines.append("    type: component")
        # **命名空间必须是本包**：贴图在
        # `resourcepack/assets/betternether/textures/entity/equipment/<层>/<材质>.png`，
        # 写成 `minecraft:` 会去找原版目录，找不到就是「穿上什么都看不见」
        # （用户 2026-10-31 实测「盔甲还是看不到」）。CE 的这两个字段收完整资源路径，
        # 不含 `textures/` 前缀、不含扩展名。
        lines.append(f'    humanoid: "{NAMESPACE}:entity/equipment/humanoid/{material}"')
        lines.append("    humanoid_leggings: "
                     f'"{NAMESPACE}:entity/equipment/humanoid_leggings/{material}"')
    return "\n".join(lines) + "\n"


def _group_banner(title: str) -> str:
    """分组横幅（注释行）。缩进两格，避免被当成新的顶层键。"""
    return f"  # ================= {title} =================\n"


def write_items(ce_root: Path, content: str) -> Path:
    path = ce_root / "configuration/items" / OUTPUT_NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    # 清掉旧的「一个来源一个 yml」产物（历史上是 bn-ore-drops.yml）：
    # 留着它等于同一批 id 在包里被声明两遍，CE 侧后解析的静默覆盖前面的。
    for stale in sorted(path.parent.glob("*.yml")):
        if stale.name != OUTPUT_NAME:
            stale.unlink()
    return path


def copy_textures(upstream: Path, ce_root: Path, plans: list[ItemPlan]) -> int:
    """把生成物品用到的贴图拷进资源包，返回张数。

    物品外观是硬引用（`items.<id>.texture:`），少一张就是背包里的紫黑格子，
    而 verify_assets.py 只扫 blocks/，看不见 items/ 下的引用。
    """
    src = upstream / ASSETS_DIR / "textures/item"
    dest_root = ce_root / "resourcepack/assets" / NAMESPACE / "textures/item"
    copied = 0
    for plan in plans:
        if plan.texture is None:
            continue
        source = src / f"{plan.item_id}.png"
        if not source.is_file():
            raise SystemExit(f"缺物品贴图：{source}")
        dest_root.mkdir(parents=True, exist_ok=True)
        (dest_root / source.name).write_bytes(source.read_bytes())
        meta = source.with_name(source.name + ".mcmeta")
        if meta.is_file():
            (dest_root / f"{source.name}.mcmeta").write_bytes(meta.read_bytes())
        copied += 1
    return copied


def lang_entries(upstream: Path, plans: list[ItemPlan]) -> dict[str, str]:
    """物品名语言条目（键用本包命名空间）。缺名字直接失败，不让游戏里显示裸 id。"""
    lang_src = upstream / ASSETS_DIR / "lang"
    source = lang_src / "zh_cn.json"
    if not source.is_file():
        candidates = sorted(lang_src.glob("*.json"))
        if not candidates:
            raise SystemExit(f"找不到上游语言文件：{lang_src}")
        source = candidates[0]
    lang = _read_json(source)
    entries: dict[str, str] = {}
    missing: list[str] = []
    for plan in plans:
        value = lang.get(f"item.{NAMESPACE}.{plan.item_id}")
        if value is None:
            missing.append(plan.item_id)
            continue
        entries[f"item.{NAMESPACE}.{plan.item_id}"] = value
    if missing:
        raise SystemExit(f"上游语言文件缺这些物品名：{sorted(missing)[:8]}；拒绝让游戏里显示裸 id。")
    return entries


# ---------------------------------------------------------------- loot 闭合


def loot_item_references(upstream: Path) -> dict[str, set[str]]:
    """上游每份方块掉落表引用的 item id（用于闭合自检与差异清单）。"""
    result: dict[str, set[str]] = {}
    base = upstream / LOOT_DIR / "blocks"
    if not base.is_dir():
        return result

    def walk(node: object, sink: set[str]) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key == "name" and isinstance(value, str):
                    sink.add(value)
                else:
                    walk(value, sink)
        elif isinstance(node, list):
            for item in node:
                walk(item, sink)

    for path in sorted(base.glob("*.json")):
        sink: set[str] = set()
        walk(_read_json(path), sink)
        result[path.stem] = sink
    return result


def conditional_loot_tables(upstream: Path) -> list[str]:
    """上游那些「不只是掉自己」的掉落表名。

    这些需要精准采集/时运/方块状态等条件，CE 只能用模板或内联 `loot:` 表达；
    当前只有 ore_drops.py 覆盖了 4 个矿物，其余属于 blocks/掉落 owner 的范围。
    这里只负责把缺口列清楚，不越界改方块定义。
    """
    names: list[str] = []
    for name, refs in loot_item_references(upstream).items():
        payload = _read_json(upstream / LOOT_DIR / "blocks" / f"{name}.json")
        blob = json.dumps(payload)
        if any(marker in blob for marker in ("silk_touch", "fortune", "alternatives",
                                             "explosion_decay", "match_block_property")):
            names.append(name)
    return sorted(names)


def loot_gaps(upstream: Path, ce_root: Path, extra: Iterable[str] = ()) -> list[str]:
    """掉落表里引用了、但本包没有定义的 item id。"""
    known = defined_ids(ce_root, extra)
    gaps: set[str] = set()
    for refs in loot_item_references(upstream).values():
        for ref in refs:
            if ref.startswith(f"{NAMESPACE}:") and ref not in known:
                gaps.add(ref)
    return sorted(gaps)


def write_recipe_report(ce_root: Path, report: RecipeReport,
                        tag_gaps: dict[str, list[str]] | None = None,
                        extra_drops: dict[str, str] | None = None,
                        unobtainable: list[dict] | None = None) -> Path:
    """配方缺口报告，落到包外的 `reports/`。

    目录沿用 blocks.py 的既有约定（`ce_root.parent.parent / "reports"`，
    见 `block-manifest.json` / `block-inventory.json`）：`craftengine/` 下的一切都会被
    CE 当成包内容扫描，所以报告只放包外，不新建目录层级。
    """
    path = ce_root.parent.parent / "reports" / "item-recipes.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "emitted": report.emitted,
        "emitted_by_type": dict(sorted(report.by_type.items())),
        "dropped_total": len(report.dropped),
        "dropped_by_reason": report.dropped_by_kind(),
        "blocked": {name: reason.as_dict()
                    for name, reason in sorted(report.dropped.items()) if reason.blocked},
        "substitutions": {name: applied
                          for name, applied in sorted(report.substitutions.items())},
        "tags_without_members": tag_gaps or {},
        "tags_used": {tag: sorted(names) for tag, names in sorted(report.tags_used.items())},
        # 上游只靠掉落才给的物品 -> 这一刀补到了哪个方块（补不上时写明原因）。
        "extra_drops": dict(sorted((extra_drops or {}).items())),
        # 既没有配方产物、也没有掉落来源的物品：缺口必须可见，而不是玩家自己发现。
        "unobtainable_items": unobtainable or [],
        "dropped": {name: reason.as_dict()
                    for name, reason in sorted(report.dropped.items())},
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path




# ---------------------------------------------------------------- 上游掉落补写
#
# items.py 生成的物品里，有一部分上游**只从方块掉落表**里来（不是配方产物）。
# 典型是 `agave_leaf`：上游 `loot_table/blocks/agave.json` 在 `age=3` 时额外掉 2~5 个，
# 而 blocks.py 给绝大多数方块写死 `loot: {template: default:loot_table/self}`（只掉自己），
# 于是龙舌兰叶永久不可得，连带 2 条以它为原料的配方一起废掉。
#
# 补写规则（与 ore_drops.py 同一套思路，但**数据全部来自上游 JSON**，不硬编码）：
#   * 只补「本模块生成、且没有任何配方来源」的物品所在的方块；
#   * 掉落体按上游 pools 逐条翻译，遇到翻译不了的条件/修饰器直接报错，不做近似；
#   * 一旦方块的 loot 段已经不是「掉自己」（别人已经处理过），本步自动让路，不覆盖。

#: 上游 loot 条件里能无损翻成 CE `match_block_property` 的类型。其余一律报错。
_LOOT_CONDITION_TYPES = frozenset({"minecraft:match_block"})

#: `loot:` 直接嵌在 `loot:` 里是坏结构（本模块早期版本写坏过一次）。
#: 这类嵌套 CE 读不到内层，掉落会静默变成空 —— 见到就抹掉外层重复的那一行。
_DOUBLE_LOOT = re.compile(r"(\n[ ]*loot:\n)([ ]*loot:\n)")


def _heal_double_loot(text: str) -> str:
    return _DOUBLE_LOOT.sub(lambda match: match.group(1), text)


def _conditions_of(node: dict) -> list[dict]:
    """上游同一件事有两种写法：`condition`（单数）与 `conditions`（复数）。"""
    raw = node.get("conditions")
    if raw is None:
        raw = node.get("condition")
    if raw is None:
        return []
    return raw if isinstance(raw, list) else [raw]


def _loot_condition_lines(condition: dict, block_id: str, indent: str) -> list[str]:
    kind = condition.get("type")
    if kind not in _LOOT_CONDITION_TYPES:
        raise SystemExit(
            f"{block_id}: 掉落条件 {kind} 翻不成 CE 的 match_block_property；"
            "拒绝用近似条件补写掉落（那会改变玩家实际收益）。")
    targets = condition.get("blocks")
    if isinstance(targets, str) and targets != block_id:
        raise SystemExit(f"{block_id}: 掉落条件指向别的方块 {targets}")
    if isinstance(targets, list) and targets and block_id not in targets:
        raise SystemExit(f"{block_id}: 掉落条件不含自己（{targets}）")
    state = condition.get("state") or {}
    lines = [f"{indent}- type: match_block_property"]
    if state:
        lines.append(f"{indent}  properties:")
        for key, value in state.items():
            lines.append(f'{indent}    {key}: "{value}"')
    return lines


def _loot_function_lines(entry: dict, block_id: str, indent: str) -> list[str]:
    """上游 `modifier` / `functions` -> CE 的 `functions:`。

    数量一律写成 CE 的 `set_count`：`ore_drops.py` 已验证 `a~b` 会被
    `NumberProviders.fromString` 解析成 uniform(a,b)，所以区间直接照搬，不另造语法。
    """
    modifiers = entry.get("functions")
    if modifiers is None:
        single = entry.get("modifier")
        modifiers = [single] if single else []
    lines: list[str] = []
    for modifier in modifiers:
        kind = modifier.get("type")
        if kind != "minecraft:set_count":
            raise SystemExit(f"{block_id}: 掉落修饰器 {kind} 无法无损翻译，拒绝近似")
        count = modifier.get("count", 1)
        if isinstance(count, dict):
            if count.get("type") != "minecraft:uniform":
                raise SystemExit(f"{block_id}: set_count 的数量提供器 {count.get('type')} 不支持")
            value = f'"{int(count["min"])}~{int(count["max"])}"'
        else:
            value = str(int(count))
        lines.extend([f"{indent}- type: set_count", f"{indent}  add: false",
                      f"{indent}  count: {value}"])
    return lines


def _loot_entry_lines(entry: dict, block_id: str, indent: str) -> list[str]:
    kind = entry.get("type")
    if kind == "minecraft:empty":
        return [f"{indent}- type: empty"]
    if kind == "minecraft:alternatives":
        lines = [f"{indent}- type: alternatives", f"{indent}  children:"]
        for child in entry.get("children") or []:
            lines.extend(_loot_entry_lines(child, block_id, indent + "    "))
        return lines
    if kind != "minecraft:item":
        raise SystemExit(f"{block_id}: 掉落实体 {kind} 不能无损翻成 CE 条目，拒绝近似")
    name = entry.get("name")
    if not name:
        raise SystemExit(f"{block_id}: 掉落实体缺少 name")
    lines = [f"{indent}- type: item", f"{indent}  item: {name}"]
    conditions = _conditions_of(entry)
    if conditions:
        lines.append(f"{indent}  conditions:")
        for condition in conditions:
            lines.extend(_loot_condition_lines(condition, block_id, indent + "    "))
    functions = _loot_function_lines(entry, block_id, indent + "  ")
    if functions:
        lines.append(f"{indent}  functions:")
        lines.extend(functions)
    return lines


def block_loot_lines(upstream: Path, block_id: str, indent: str = "    ") -> list[str]:
    """按上游 `loot_table/blocks/<id>.json` 生成完整的 CE `loot:` 段（含外层 `loot:`）。"""
    path = upstream / LOOT_DIR / "blocks" / f"{_bare(block_id)}.json"
    data = _read_json(path)
    if data.get("type") != "minecraft:block":
        raise SystemExit(f"{path.name}: 掉落表类型 {data.get('type')} 不是方块掉落")
    lines = [f"{indent}loot:", f"{indent}  pools:"]
    for pool in data.get("pools") or []:
        lines.append(f"{indent}    - rolls: {int(pool.get('rolls', 1))}")
        conditions = _conditions_of(pool)
        if conditions:
            lines.append(f"{indent}      conditions:")
            for condition in conditions:
                lines.extend(_loot_condition_lines(condition, block_id, indent + "        "))
        lines.append(f"{indent}      entries:")
        for entry in pool.get("entries") or []:
            lines.extend(_loot_entry_lines(entry, block_id, indent + "        "))
    return lines


def loot_only_items(upstream: Path, planned: Iterable[str],
                    produced: Iterable[str]) -> dict[str, str]:
    """本模块生成、无配方来源、但上游方块掉落表里会掉的物品 -> 掉它的方块 id。

    「无配方来源」是这里的判据：有配方的物品玩家能做出来，掉落缺不缺不影响可玩性；
    没有配方的才必须靠掉落，否则就是永久不可得。
    """
    pending = {f"{NAMESPACE}:{item_id}" for item_id in planned} - set(produced)
    found: dict[str, str] = {}
    base = upstream / LOOT_DIR / "blocks"
    if not base.is_dir():
        return found
    # 掉落表只读一次：在循环里重读会让这一步变成 O(表数²)，几百张表就是几十秒。
    references = loot_item_references(upstream)
    for name, refs in sorted(references.items()):
        block_id = f"{NAMESPACE}:{name}"
        for ref in sorted(refs):
            if ref in pending and ref != block_id:
                found.setdefault(ref, block_id)
    return found


def _block_section(text: str, block_id: str) -> str:
    """从整份 YAML 文本的 **`blocks:` 段**里截出 `block_id` 那一段。

    批次文件里有 `items:` 和 `blocks:` 两个顶层段，同一个 id 会出现两次
    （物品定义 + 方块定义）。只按「两空格缩进的键」找第一处会命中 items 段，
    于是「这个方块自己有没有写某个掉落」的判断读到的是物品定义 —— 那里当然没有掉落，
    结果是每次运行都把已经补好的方块误报成「别人处理过、跳过」。

    所以先定位 `blocks:` 那一行，再在它之后按两空格缩进的方块键切段。
    """
    header = f"  {block_id}:"
    lines = text.splitlines(keepends=True)
    blocks_at = None
    for index, line in enumerate(lines):
        if line.rstrip("\n") == "blocks:":
            blocks_at = index
            break
    if blocks_at is None:
        return ""
    start = None
    for index in range(blocks_at + 1, len(lines)):
        if lines[index].rstrip("\n") == header:
            start = index
            break
    if start is None:
        return ""
    end = len(lines)
    for index in range(start + 1, len(lines)):
        line = lines[index]
        if line.startswith("  ") and not line.startswith("   ") and line.rstrip("\n").endswith(":"):
            end = index
            break
    return "".join(lines[start:end])


def patch_block_loot(upstream: Path, ce_root: Path,
                     mapping: dict[str, str]) -> dict[str, str]:
    """把「只靠掉落才拿得到」的物品补进对应方块的 `loot:` 段。返回 {物品: 补写结果}。

    判据与 `ore_drops.patch_generated_blocks` 一致：**只动仍然是「掉自己」的 loot 段**。
    别人（blocks.py 或后续模块）已经写出更完整掉落时，本步让路，不覆盖别人的结果。
    """
    result: dict[str, str] = {}
    if not mapping:
        return result
    blocks_dir = ce_root / "configuration/blocks"
    if not blocks_dir.is_dir():
        for item_id, block_id in sorted(mapping.items()):
            result[item_id] = f"未补写：生成目录里没有 blocks/（{block_id} 尚未生成）"
        return result

    by_block: dict[str, list[str]] = {}
    for item_id, block_id in sorted(mapping.items()):
        by_block.setdefault(block_id, []).append(item_id)

    for block_id, item_ids in sorted(by_block.items()):
        target = None
        for path in sorted(blocks_dir.glob("*.yml")):
            if f"  {block_id}:" in path.read_text(encoding="utf-8"):
                target = path
                break
        if target is None:
            for item_id in item_ids:
                result[item_id] = f"未补写：产物里找不到方块 {block_id}"
            continue

        lines = target.read_text(encoding="utf-8").splitlines(keepends=True)
        text = "".join(lines)
        healed = _heal_double_loot(text)
        if healed != text:
            target.write_text(healed, encoding="utf-8")
            lines = healed.splitlines(keepends=True)
            text = healed
        # 幂等：上一次已经把掉落写进去了（**这个方块自己的 loot 段**里已经出现该物品）就什么都不做。
        # 这条判据必须先判 —— 否则第二次运行会发现 loot 段「不再是掉自己」，
        # 把本模块自己上一轮的成果误报成「别人处理过、我跳过」。
        # 判据范围必须限定在目标方块内：曾经用「整个文件包含该物品」来判断，同一批次文件里
        # 只要有一个方块写了 agave，其余 75 个都会被误判成「已有来源」而跳过补写。
        target_block_text = _block_section(text, block_id)
        if all(item_id in target_block_text for item_id in item_ids):
            for item_id in item_ids:
                result[item_id] = f"已有来源：{block_id} 的 loot 段里已包含该物品"
            continue

        output: list[str] = []
        patched = False
        index = 0
        # 只替换**目标方块自己**的 loot 段。
        # 这里曾经把整个文件里每一处 `template: default:loot_table/self` 都换掉，
        # 结果同一个批次文件里的 76 个方块全被写成 agave 的掉落表（植物全掉 agave）。
        # 因此必须先定位到 `  <block_id>:` 这一行，再在它到下一个同级方块之间找模板行。
        in_target = False
        while index < len(lines):
            line = lines[index]
            stripped = line.strip()
            if line.startswith("  ") and not line.startswith("   ") and stripped.endswith(":"):
                in_target = stripped == f"{block_id}:"
            if in_target and stripped == "template: default:loot_table/self":
                indent = line[:len(line) - len(line.lstrip())]
                # 外层 `loot:` 行已经在方块里了，这里只补它下面的 body。
                output.extend(part + "\n"
                              for part in block_loot_lines(upstream, block_id, indent)[1:])
                patched = True
                index += 1
                continue
            output.append(line)
            index += 1

        if not patched:
            for item_id in item_ids:
                result[item_id] = (
                    f"未补写：{block_id} 的 loot 段已不是「掉自己」（可能已由别处处理）")
            continue

        target.write_text("".join(output), encoding="utf-8")
        for item_id in item_ids:
            result[item_id] = f"已补写：{block_id} 的 loot 段按上游掉落表重建"
    return result


def _upstream_sources(upstream: Path) -> tuple[set[str], set[str]]:
    """上游自己给的来源：(配方产物 id 集合, 掉落表引用 id 集合)。"""
    products: set[str] = set()
    base = upstream / RECIPE_DIR
    if base.is_dir():
        for path in sorted(base.glob("*.json")):
            product = _result_of(_read_json(path))
            if product:
                products.add(product)
    loot_names = {ref for refs in loot_item_references(upstream).values() for ref in refs}
    return products, loot_names


def unobtainable_items(upstream: Path, planned: Iterable[str],
                       produced: Iterable[str],
                       extra_drops: dict[str, str]) -> list[dict]:
    """本模块生成、但**既没有配方产物、也没有掉落来源**的物品。

    这类物品在游戏里拿不到，却不会报错 —— 不列出来就是「隐形缺口」。判据与
    `loot_only_items()` 同源：`produced` 是本模块真正写出的配方产物，`extra_drops`
    是本轮把上游掉落补进方块的记录（补写成功的要排除，否则会把已修好的报成缺口）。
    """
    upstream_products, upstream_loot = _upstream_sources(upstream)
    # 两个入参的口径不同（planned 是裸 id、produced 是完整 id），统一成完整 id 再比 ——
    # 直接相减会让「所有物品都不可获得」这种灾难性误报混进报告。
    pending = {f"{NAMESPACE}:{item_id}" for item_id in planned}
    made = {product if ":" in product else f"{NAMESPACE}:{product}" for product in produced}
    rows: list[dict] = []
    for full in sorted(pending - made):
        status = extra_drops.get(full, "")
        if status.startswith("已"):  # 已补写 / 已有来源，两种都算有来源
            continue
        if full in upstream_products or full in upstream_loot:
            note = "上游有配方/掉落来源，但本包没生成出来"
        else:
            note = ("上游也没有配方产物、掉落表里也没有它 —— 上游只能靠创造模式/指令获得，"
                    "本包同样没有来源")
        rows.append({
            "id": full,
            "upstream_recipe_product": full in upstream_products,
            "upstream_loot_table": full in upstream_loot,
            "extra_drop_status": status,
            "note": note,
        })
    return rows


# ---------------------------------------------------------------- 集成入口


def generate(upstream: Path, ce_root: Path,
             selected: Sequence[str] = ()) -> dict[str, object]:
    """生成 `configuration/items/bn-items.yml` 并拷贝贴图。幂等：输出只取决于输入。

    `selected` 是集成器传进来的方块清单（blocks.py 的 manifest）。本模块不按它过滤内容，
    而是把它当成「已定义 id」的一层额外真值 —— 配方闭合判断因此不受 blocks/*.yml 是否
    已经落盘影响，集成顺序怎么排都不会误丢配方。

    失败一律抛异常（`SystemExit` / `FileNotFoundError` 等），不返回半成品。
    """
    extra = (*selected, *ore_drops.item_ids())
    content, plans, skipped, report = items_yaml(upstream, ce_root, extra)
    path = write_items(ce_root, content)
    textures = copy_textures(upstream, ce_root, plans)
    tag_gaps = report.tags_without_members(declared_tags(ce_root, extra))

    # 上游只从方块掉落表给的物品（典型：龙舌兰叶）：按上游 loot JSON 补写方块 loot 段。
    # 必须在这一步做 —— 它是「补已生成的 blocks/*.yml」，blocks.py 已经跑完了。
    loot_only = loot_only_items(upstream, (plan.item_id for plan in plans), report.produced)
    extra_drops = patch_block_loot(upstream, ce_root, loot_only)
    unobtainable = unobtainable_items(upstream, (plan.item_id for plan in plans),
                                      report.produced, extra_drops)
    report_path = write_recipe_report(ce_root, report, tag_gaps, extra_drops, unobtainable)
    gaps = loot_gaps(upstream, ce_root, extra)
    conditional = conditional_loot_tables(upstream)

    unsupported = [
        f"{item_id}: {reason}" for item_id, reason in sorted(skipped.items())
    ]
    warnings: list[str] = []
    if report.dropped:
        by_kind = report.dropped_by_kind()
        blocked = sum(1 for reason in report.dropped.values() if reason.blocked)
        warnings.append(
            f"{len(report.dropped)} 条上游配方未生成（"
            + "、".join(f"{kind}={count}" for kind, count in by_kind.items())
            + f"）；其中 {blocked} 条在等用户裁决，详见 {report_path.name}")
    if tag_gaps:
        # 这些配方语法合法、但 CE 侧标签没有成员 -> 配方不加载（CE 记 invalid_tag 后跳过）。
        # 补成员是 blocks.py / items 的 `settings.tags` 的事，这里只负责让缺口可见。
        warnings.append(
            f"{len(tag_gaps)} 个原料标签在 ce_root 里没有任何物品声明成员，"
            "相关配方不会被 CE 加载："
            + "、".join(f"{tag}({len(recipes)} 条)" for tag, recipes in tag_gaps.items())
            + "；需要对应方块/物品补 settings.tags")
    if report.substitutions:
        warnings.append(
            f"{len(report.substitutions)} 条配方的原料经过受控替换"
            "（台阶不发布改用同材质整块、箱子船改用原版箱子等）：成本或语义与上游不同，"
            f"逐条见 {report_path.name} 的 substitutions 段")
    patched_drops = {item: status for item, status in extra_drops.items()
                     if status.startswith("已")}
    failed_drops = {item: status for item, status in extra_drops.items()
                    if not status.startswith("已")}
    if patched_drops:
        warnings.append(
            f"{len(patched_drops)} 个物品只靠方块掉落获得，已按上游掉落表补写方块 loot："
            + "、".join(sorted(patched_drops)))
    if failed_drops:
        warnings.append(
            "掉落补写未完成：" + "；".join(f"{item}（{status}）"
                                          for item, status in sorted(failed_drops.items())))
    if unobtainable:
        warnings.append(
            f"{len(unobtainable)} 个物品既无配方产物也无掉落来源（游戏里拿不到）："
            + "、".join(row["id"] for row in unobtainable)
            + f"；依据见 {report_path.name} 的 unobtainable_items 段")
    if gaps:
        warnings.append(f"{len(gaps)} 个掉落表引用的 id 尚未定义")
    if conditional:
        warnings.append(
            f"{len(conditional)} 份掉落表带精准采集/时运/方块状态条件，"
            "当前只有 ore_drops.py 覆盖了 4 个矿物")

    return {
        # 集成契约要求的固定键
        "files": 1 + (1 if textures else 0),
        "written": [str(path), str(report_path)],
        "warnings": warnings,
        "unsupported": unsupported,
        # 本模块的明细，供报告与测试使用
        "items": len(plans),
        "textures": textures,
        "recipes": report.emitted,
        "recipes_by_type": dict(sorted(report.by_type.items())),
        "recipes_dropped": len(report.dropped),
        "recipes_dropped_by_reason": report.dropped_by_kind(),
        "recipes_dropped_reasons": {name: reason.as_dict()
                                    for name, reason in sorted(report.dropped.items())},
        "recipe_substitutions": report.substitutions,
        "extra_drops": extra_drops,
        "unobtainable_items": unobtainable,
        "tags_without_members": tag_gaps,
        "recipe_report": str(report_path),
        "loot_gaps": gaps,
        "conditional_loot_tables": conditional,
    }



if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="生成 BetterNether 物品与配方")
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    summary = generate(args.upstream, args.output)
    print(json.dumps({k: v for k, v in summary.items() if k != "recipes_dropped_reasons"},
                     ensure_ascii=False, indent=2))

#!/usr/bin/env python3
"""上游 BetterNether 方块属性的**权威读取层**。

## 为什么必须单独一层

旧版 `blocks.py` 用**方块名字**推断材质、工具与硬度（`if "log" in name` 一类）。
那是错的，上游自己已经把每一项都导出成真值了：

  * `src/main/generated/block_properties.json`
        BlockBehaviour 的逐项属性：`destroyTime` / `resistance` / `instrument` /
        `lightEmission` / `canOcclude` / `replaceable` / `waterlogged` /
        `pushReaction` / `friction` / `speed` / `jump` / `ignitedByLava` /
        `mapColor` / `sound` / `renderLayer` / `isSuffocating` …
  * `src/main/generated/block_shapes.txt`
        每个状态的碰撞箱与轮廓（`shape=… collision=…`）
  * `src/main/generated/data/minecraft/tags/block/mineable/{pickaxe,axe,shovel,hoe}.json`
        **正确的挖掘工具**。这是唯一权威来源，名字里看不出来：
        `nether_cactus` 是镐、`willow_torch` 无工具、`cincinnasite_ore` 要铁镐。
  * `src/main/generated/data/minecraft/tags/block/needs_{iron,diamond}_tool.json`
        工具等级门槛 → CE 的 `required_break_power`
  * `src/main/generated/data/minecraft/tags/block/{leaves,logs,planks,replaceable,…}.json`
        语义标签，用于分类与行为判定
  * `src/main/generated/block_registrations.json`
        可燃性（`flammable`，形如 `"spread/burn"`）

本模块只做「读文件 + 解析成结构化值」，**不做任何取舍判断**；取舍在 `blocks.py`。
这样属性来源可以被单独审计：任何一个数字都能指回上游某一行。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

# 形状表里「满格立方体」的字面值。上游对 1x1x1 的导出就是这四组数字。
FULL_SHAPE = "[0,0,0|1,1,1]"

# 形状层级。只有前两档能被 CraftEngine 表达，见 blocks.py 的说明。
TIER_FULL = "full"        # 所有状态都是满格立方体碰撞
TIER_NONE = "none"        # 所有状态都没有碰撞（植物/告示牌/压力板…）
TIER_PARTIAL = "partial"  # 其余：半砖、楼梯、墙、栅栏、门、家具…

# `pushReaction` → CE `push_reaction`。
# CE 取值见 docs/blocks.md「活塞推动行为」：NORMAL/DESTROY/BLOCK/IGNORE/PUSH_ONLY。
PUSH_REACTION = {
    "PUSH_PULL": "NORMAL",    # 可推动可拉动
    "POPPED": "DESTROY",      # 被推动破坏，不可拉动
    "IMMOVEABLE": "BLOCK",    # 不可推动不可拉动
}

# 上游 `instrument` 是大写枚举名，CE 用小写值（docs/blocks.md「乐器」）。
INSTRUMENTS = {
    "HARP", "BASEDRUM", "SNARE", "HAT", "GUITAR", "FLUTE", "BELL", "CHIME",
    "XYLOPHONE", "IRON_XYLOPHONE", "COW_BELL", "DIDGERIDOO", "BIT", "BANJO",
    "PLING", "ZOMBIE", "SKELETON", "CREEPER", "DRAGON", "WITHER_SKELETON",
    "PIGLIN", "CUSTOM_HEAD", "BASS",
}

# 挖掘工具标签 → 该工具族的物品 id 列表。
# CE 的 `correct_tools` 收的是**具体物品 id**（docs/blocks.md 示例逐个列出六把镐），
# 不接受 `#标签` 写法，所以这里必须展开。原版工具族的成员是固定的。
TOOL_FAMILIES = {
    "pickaxe": (
        "minecraft:wooden_pickaxe", "minecraft:stone_pickaxe",
        "minecraft:copper_pickaxe", "minecraft:iron_pickaxe",
        "minecraft:golden_pickaxe", "minecraft:diamond_pickaxe",
        "minecraft:netherite_pickaxe",
    ),
    "axe": (
        "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe",
        "minecraft:iron_axe", "minecraft:golden_axe", "minecraft:diamond_axe",
        "minecraft:netherite_axe",
    ),
    "shovel": (
        "minecraft:wooden_shovel", "minecraft:stone_shovel",
        "minecraft:copper_shovel", "minecraft:iron_shovel",
        "minecraft:golden_shovel", "minecraft:diamond_shovel",
        "minecraft:netherite_shovel",
    ),
    "hoe": (
        "minecraft:wooden_hoe", "minecraft:stone_hoe", "minecraft:copper_hoe",
        "minecraft:iron_hoe", "minecraft:golden_hoe", "minecraft:diamond_hoe",
        "minecraft:netherite_hoe",
    ),
}

# 工具等级 → CE `required_break_power`。
# 依据 docs/blocks.md「所需破坏力」：原版工具破坏力写死为
# 木/金=1、石/铜=2、铁=3、钻石=4、下界合金=5。
# 上游 datagen 只导出了 needs_iron_tool / needs_diamond_tool 两个门槛标签
# （没有 needs_stone_tool），所以：
#   * 在 mineable/* 里但不在任何 needs_*_tool 里 → 木镐即可 → 1
#   * needs_iron_tool                          → 3
#   * needs_diamond_tool                       → 4
# 不在任何 mineable/* 里 → 0（无门槛，CE 默认值）。
BREAK_POWER_IRON = 3
BREAK_POWER_DIAMOND = 4
BREAK_POWER_ANY_TIER = 1


@dataclass(frozen=True)
class BlockProps:
    """一个方块的全部上游属性，逐项对应 `block_properties.json`。"""

    id: str
    cls: str
    hardness: float
    resistance: float
    instrument: str
    requires_tool: bool
    map_color: int
    sound: str
    friction: float
    speed_factor: float
    jump_factor: float
    ignited_by_lava: bool
    # 静态光照值；上游是动态光照（如 `0..15#2`）时为 None —— 那是状态/方块实体驱动的，
    # 单个 `luminance` 表达不了，交给调用方记录为未实现。
    luminance: int | None
    luminance_dynamic: bool
    replaceable: bool
    waterlogged: bool
    has_collision: bool
    can_occlude: bool
    render_layer: str
    push_reaction: str
    offset_type: str
    # `isSuffocating` / `isViewBlocking` / `isRedstoneConductor` 在上游导出为
    # `explicit/true` 或 `derived/false`。两者都是运行时真值，这里统一取布尔。
    is_suffocating: bool | None
    is_view_blocking: bool | None
    is_redstone_conductor: bool | None


def _float(value: object, default: float = 0.0) -> float:
    try:
        return float(str(value))
    except (TypeError, ValueError):
        return default


def _int(value: object, default: int = 0) -> int:
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return default


def _bool(value: object, default: bool = False) -> bool:
    text = str(value).strip().lower()
    if text == "true":
        return True
    if text == "false":
        return False
    return default


def _tri_state(value: object) -> bool | None:
    """解析 `explicit/true` / `derived/false` 这类三元导出。

    上游对这三个字段写成 `<来源>/<值>`，来源是 `explicit`（方块显式覆写）或
    `derived`（由其他属性推导）。两种来源的**值**都是运行时真值，所以只取最后一段。
    出现无法识别的形状时返回 None，让调用方省略该字段而不是猜一个值。
    """
    parts = str(value).split("/")
    if len(parts) >= 2 and parts[-1].strip().lower() in ("true", "false"):
        return parts[-1].strip().lower() == "true"
    return None


def _light(value: object) -> tuple[int | None, bool]:
    """解析 `lightEmission`，返回 (静态值或 None, 是否动态)。

    上游取值有两种形状：
      * `"15"`        —— 静态亮度
      * `"0..15#2"`   —— 随状态/方块实体变化（`#2` 是状态数），单个 luminance 表达不了
    """
    text = str(value).strip()
    if text.isdigit():
        return int(text), False
    return None, True


def _read_json(path: Path) -> object | None:
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None


def load_props(upstream: Path) -> dict[str, BlockProps]:
    """读 `block_properties.json`，返回 `id -> BlockProps`。

    读不到时返回空字典：调用方必须能感知「属性缺失」并退回保守值，
    而不是让一个坏文件把整批方块变成默认硬度。
    """
    entries = _read_json(upstream / "src/main/generated/block_properties.json")
    if not isinstance(entries, list):
        return {}

    result: dict[str, BlockProps] = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        luminance, dynamic = _light(entry.get("lightEmission", "0"))
        instrument = str(entry.get("instrument", "")).strip()
        result[entry["id"]] = BlockProps(
            id=entry["id"],
            cls=str(entry.get("class", "")),
            hardness=_float(entry.get("destroyTime"), 2.0),
            resistance=_float(entry.get("resistance"), 2.0),
            instrument=instrument.lower() if instrument in INSTRUMENTS else "harp",
            requires_tool=_bool(entry.get("reqTool")),
            map_color=_int(entry.get("mapColor"), 0),
            sound=str(entry.get("sound", "")).strip(),
            friction=_float(entry.get("friction"), 0.6),
            speed_factor=_float(entry.get("speed"), 1.0),
            jump_factor=_float(entry.get("jump"), 1.0),
            ignited_by_lava=_bool(entry.get("ignitedByLava")),
            luminance=luminance,
            luminance_dynamic=dynamic,
            replaceable=_bool(entry.get("replaceable")),
            waterlogged=_bool(entry.get("waterlogged")),
            has_collision=_bool(entry.get("hasCollision"), True),
            can_occlude=_bool(entry.get("canOcclude")),
            render_layer=str(entry.get("renderLayer", "SOLID")).strip(),
            push_reaction=PUSH_REACTION.get(
                str(entry.get("pushReaction", "PUSH_PULL")).strip(), "NORMAL"),
            offset_type=str(entry.get("offsetType", "NONE")).strip(),
            is_suffocating=_tri_state(entry.get("isSuffocating", "")),
            is_view_blocking=_tri_state(entry.get("isViewBlocking", "")),
            is_redstone_conductor=_tri_state(entry.get("isRedstoneConductor", "")),
        )
    return result


def load_shape_tiers(upstream: Path) -> dict[str, str]:
    """读形状表，把每个方块归到 full / none / partial 三档。

    判定用的是**全部状态**，不是第一个状态：
      * 所有状态的 shape 与 collision 都是满格 → `full`
      * 所有状态的 collision 都是 `empty`      → `none`
      * 其余                                   → `partial`

    只看第一条状态会把 `acacia_chair[top=true]`（无碰撞）和 `[top=false]`（有碰撞）
    混为一谈，那正是旧版按名字兜底剔除 22 张椅子的起因。
    """
    source = upstream / "src/main/generated/block_shapes.txt"
    if not source.is_file():
        return {}

    import re
    pattern = re.compile(
        r"([a-z0-9_.\-]+:[a-z0-9_]+)(\[[^\]]*\])?\s+shape=(\S+|empty)\s+collision=(\S+)"
    )
    shapes: dict[str, set[str]] = {}
    colls: dict[str, set[str]] = {}
    for line in source.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if not match:
            continue
        block_id, _props, shape, collision = match.groups()
        shapes.setdefault(block_id, set()).add(shape)
        colls.setdefault(block_id, set()).add(collision)

    tiers: dict[str, str] = {}
    for block_id in shapes:
        if shapes[block_id] == {FULL_SHAPE} and colls[block_id] == {FULL_SHAPE}:
            tiers[block_id] = TIER_FULL
        elif colls[block_id] == {"empty"}:
            tiers[block_id] = TIER_NONE
        else:
            tiers[block_id] = TIER_PARTIAL
    return tiers


def load_tag(upstream: Path, name: str, namespace: str = "minecraft") -> set[str]:
    """读一个方块标签，返回其中的方块 id 集合（原样保留命名空间）。"""
    path = (upstream / "src/main/generated/data" / namespace
            / "tags/block" / f"{name}.json")
    payload = _read_json(path)
    if not isinstance(payload, dict):
        return set()
    return {v for v in payload.get("values", []) if isinstance(v, str)}


def load_mineable_tools(upstream: Path) -> dict[str, frozenset[str]]:
    """`mineable/*` 标签 → 方块 id 到工具族集合的映射。

    这是挖掘工具的**唯一权威来源**。旧版按名字猜（`"log" in name → 斧`），
    于是 `nether_cactus`（镐）被当成植物、`cincinnasite_pillar`（镐）因为含
    `pillar` 被漏掉。标签文件没有这个问题。
    """
    tools: dict[str, set[str]] = {}
    for family in TOOL_FAMILIES:
        for block_id in load_tag(upstream, f"mineable/{family}"):
            tools.setdefault(block_id, set()).add(family)
    return {block_id: frozenset(value) for block_id, value in tools.items()}


def load_break_power(upstream: Path) -> dict[str, int]:
    """工具等级门槛 → `required_break_power`。

    只对「在 mineable/* 里」的方块给值：不在任何 mineable 里的方块本来就没有
    工具门槛，写 0（CE 默认）而不是 1，避免凭空要求一把木镐。
    """
    iron = load_tag(upstream, "needs_iron_tool")
    diamond = load_tag(upstream, "needs_diamond_tool")
    mineable = load_mineable_tools(upstream)

    power: dict[str, int] = {}
    for block_id in mineable:
        if block_id in diamond:
            power[block_id] = BREAK_POWER_DIAMOND
        elif block_id in iron:
            power[block_id] = BREAK_POWER_IRON
        else:
            power[block_id] = BREAK_POWER_ANY_TIER
    return power


def load_flammable(upstream: Path) -> dict[str, tuple[int, int]]:
    """`block_registrations.json` 的 `flammable` → (引燃几率, 烧毁几率)。

    上游把可燃性导出成 `"spread/burn"` 形式，与 CE 的
    `fire_spread_chance` / `burn_chance`（0-100）一一对应。
    """
    entries = _read_json(upstream / "src/main/generated/block_registrations.json")
    if not isinstance(entries, list):
        return {}

    result: dict[str, tuple[int, int]] = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("id"), str):
            continue
        raw = str(entry.get("flammable", "")).strip()
        left, _, right = raw.partition("/")
        try:
            spread, burn = int(left), int(right)
        except ValueError:
            continue
        if spread or burn:
            result[entry["id"]] = (spread, burn)
    return result

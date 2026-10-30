#!/usr/bin/env python3
"""上游「植物/装饰方块要不要支撑、支撑什么」的**权威读取层** + CE 行为渲染。

## 为什么单独一层

上游把「这株植物能立在什么上面」写在**注册处**，不是写在方块类里：

    public static final Block AGAVE = NetherBlocks.defineBlock("agave", BlockAgave::new)
            .addTrait(NetherSurvival.gravel())          # ← 支撑规则
            .buildAndRegister();

`canSurvive` 只负责**方向**（看 `pos.below()` / `pos.above()` / `state.getValue(FACING)`），
真正「哪些方块算数」在 `NetherSurvival` 的方法体里（tag / 方块列表）。

所以本模块只做两件事，且都不猜：

1. 从上游源码读**真值**：`registry/block/*.java` 里的 `.addTrait(...)`、
   `blocks/NetherSurvival.java` 的方法体、`blocks/*.java` 的 `canSurvive` 方向、
   `src/main/generated/data/**/tags/block/*.json` 的标签成员。
2. 把真值翻成 CE 官方行为（docs/blocks.md）：
   `bush_block`（下方白名单）/ `hanging_block`（上方白名单）/ `sturdy_base_block`
   （下方必须有坚固面）/ `directional_attached_block`（贴面 + `facing`）。

## 三类标签的可用性（决定了怎么落成 CE 配置）

  * `minecraft:*` 标签 —— **服务器上一定存在**（原版数据包提供），可以原样写进
    `bottom_block_tags`；成员由原版决定，本模块不需要、也不去枚举它。
  * `betternether:*` 标签 —— 本模组自己的标签，成员**完整**写在
    `src/main/generated/data/betternether/tags/block/<name>.json`。但 CE 资源包**不提供**
    方块标签（`plugin/src/main/resources/datapacks/` 里只有结构用的 biome 标签），
    所以这里**展开成显式方块 id**，而不是写一个运行期不存在的标签。
  * `wover:*` 公共标签（`CommonBlockTags.*`）—— 分**两层**：
    基表（wover 自己贡献的成员）+ 各模组的增量（`data/wover/tags/block/<路径>.json`）。
    两层现在都有可复核来源：基表来自**官方 WorldWeaver 仓库里已提交的生成数据**
    （`wover-tag-api/src/main/generated/data/wover/tags/block/**`，见 {@link WOVER_TAG_BASES}
    的来源注释），常量名→标签路径的对应关系见 {@link WOVER_TAG_PATHS}（逐条取自官方
    `CommonBlockTags.java`，不靠命名习惯猜）；增量来自上游树的
    `src/main/generated/data/wover/tags/block/**`。
    基表里的 `#wover:*` 引用按同一张表**递归展开到不动点**，`#minecraft:*` 原样写进 CE
    的标签字段 —— CE 包不提供方块标签，所以 `wover:` 标签一律展开成显式 id。
    表里没有的标签照旧阻塞 —— 只剩增量半边等于「植物在大多数原版地面上都不活」，
    那是改玩法，不是近似。

## 为什么不能「近似成任意坚固面」

`sturdy_base_block` / `bush_block` 都表达不了「白名单 ∪ 坚固面」这种 OR。给一株
「只能长在下界岩上」的植物挂 `sturdy_base_block`，它会活在下界砖、黑曜石、任何坚固
方块上。所以 OR 语义只在上游自己就是「坚固面 ∪ 树叶」时用 `sturdy_base_block`
（树叶那一半写进 notes），其余一律阻塞。

## 两层判据：`canSurvive` 与 `updateShape`

`canSurvive` 决定「能不能放在这里」，`updateShape` / `destroy` 决定「支撑没了以后怎么
收场」。两者不是一回事，而上游把 A 类（藤蔓、芦苇、疣种）的回收**只**写在 `updateShape`
里 —— 只读 `addTrait` + `canSurvive` 会整批漏掉它们。文件末尾的「updateShape 破坏判据层」
就是补这一层：读方向、读判据种类（白名单 / 自己∪白名单 / 任何坚固面 / 递归级联），
再决定「能不能等价落成 CE 行为」，并把「断开要不要掉落」落成产物里的
`reports/plant-breakdown.json`（CE 的支撑行为一律 setAir，掉落只能由 Java 侧补）。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field, replace
from pathlib import Path

# CE 包命名空间。上游方块 id 与 CE 方块 id 在本项目里一一对应（见 blocks.py）。
NAMESPACE = "betternether"

# `Blocks.<常量>` → 原版方块 id。只收 NetherSurvival 里真的用到的那些：
# 表里没有的常量一律当作「无法证实」并阻塞，绝不按名字拼 id。
JAVA_BLOCK_CONSTANTS = {
    "Blocks.SAND": "minecraft:sand",
    "Blocks.RED_SAND": "minecraft:red_sand",
    "Blocks.SOUL_SAND": "minecraft:soul_sand",
    "Blocks.SOUL_SOIL": "minecraft:soul_soil",
    "Blocks.GRAVEL": "minecraft:gravel",
    "Blocks.MAGMA_BLOCK": "minecraft:magma_block",
    "Blocks.BONE_BLOCK": "minecraft:bone_block",
    "Blocks.NETHERRACK": "minecraft:netherrack",
    # NetherStoneBlocks.BONE_BLOCK 是「以原版骨块注册在 betternether 命名空间下」的方块
    # （NetherStoneBlocks.java:127 `registerBlock("bone_block", Blocks.BONE_BLOCK, …)`）。
    "NetherStoneBlocks.BONE_BLOCK": "betternether:bone_block",
}

# 支撑方向。`mixed` = 同一个 canSurvive 里同时看两个方向（CE 单条行为表达不了）。
DIR_DOWN = "down"
DIR_UP = "up"
DIR_FACING = "facing"
DIR_MIXED = "mixed"
DIR_UNKNOWN = "unknown"
#: `updateShape` 破坏判据的方向：`both` = 上方与下方都要判（例如 `BlockNetherReed`）。
DIR_BOTH = "both"

#: 用户点名要求「只认某一种支撑方向」的方块 —— 覆盖从上游读出来的判据。
#:
#: `bone_mushroom`（骨菇）：上游 `canSurvive` 同时看上下/贴面（判据被解析成 `mixed`，
#: 于是整条规则不发布 = 完全没有放置限制，能飘在空中）。用户 2026-10-30 实测要求
#: 「骨菇孢子只能下方有方块才生成」，所以按 DOWN 覆盖 —— DOWN 会渲染成 `bush_block`，
#: 即「下方必须是白名单成员（黑名单模式下 = 任意方块）」。
FORCED_DIRECTIONS: dict[str, str] = {
    # **裸名**：`load_support_rules` 的键是 `_registered_traits` 给出的短名，
    # 带 `betternether:` 前缀会永远匹配不上（这一条和 `_STACKABLE_VINES` 同理）。
    "bone_mushroom": DIR_DOWN,
}

#: 允许**向下延伸**的悬挂藤蔓（成串、可爬的那一类）。
#:
#: 只收这三个：`eye_vine`（眼球藤）、`golden_vine`（金藤）、`lumabus_vine`（荧光藤）。
#: **注意是裸名**（不带 `betternether:` 前缀）：`blocks.py` 调 `support_behaviors(name, …)`
#: 时传的就是 `block_id.split(":", 1)[1]` 的结果，带前缀会永远匹配不上。
#: `hook_mushroom` / `rubeus_cone` 虽然是 `hanging_block`，但它们是**单体**蘑菇/锥体，
#: 串起来与上游观感不符，故意不列入——用户 2026-10-30 明确「仅 3 个藤蔓类」。
HANGING_GROWING_VINES: frozenset[str] = frozenset({
    "eye_vine",
    "golden_vine",
    "lumabus_vine",
})

#: 延伸藤蔓在 CE 侧的**构建期硬上限**。
#:
#: 为什么不是直接用 config 里的 `vine.max-length`：CE 的 `max_height` 是烘进 yml 的常量，
#: 改配置就得重新构建；所以这里给一个足够大的安全值，运行期真正生效的最大长度由
#: Java 侧的 `vine.max-length` 控制（超长不再生长/放置）。取 32 是「比任何合理配置都大、
#: 又不至于让误配置堆到世界高度」的折中。
HANGING_MAX_HEIGHT = 32

# 破坏判据的种类（`updateShape` / `destroy` 层，见文件末尾「updateShape 破坏判据层」）。
#: 判据 = 具体方块集合（含「同类」以外的显式方块 / 标签）。
CRITERION_WHITELIST = "whitelist"
#: 判据 = 自己 ∪ 具体方块集合（`eye_vine` 的「下方是自己或眼球」）。
CRITERION_SELF_OR_WHITELIST = "self_or_whitelist"
#: 判据 = 任何坚固面（`canSupportCenter` / `isDecorationSupport` / `SurvivesOnSolidTrait`），
#: 可能再 OR 上「自己」或白名单。
CRITERION_SOLID = "solid"
#: 判据 = 同类链，破坏时递归 `destroyBlock(..., true)`（石笋）。
CRITERION_RECURSIVE = "recursive_cascade"
CRITERION_UNKNOWN = "unknown"

# 断开后该不该掉落。上游 A 类（藤蔓/芦苇/疣种）是**静默消失不掉落**；B 类（石笋）真掉落。
#: 上游自己的 `destroyBlock(..., true)` 就带掉落。
DROPS_UPSTREAM = "upstream"
#: 上游静默消失，但用户明确要求「上方或下方连接没了也要掉落」→ 必须由 Java 侧补掉落。
DROPS_REQUIREMENT = "user_requirement"

# ---------------------------------------------------------------- wover 标签的基表
#
# wover 的地面标签是**两层**：wover 自己贡献的基表 + 各模组在注册时追加的增量
# （`data/wover/tags/block/**` 里能看到的那些 betternether 条目）。
#
# 两层现在都有可复核来源，不再靠推断：
#
#   1) 常量名 → 标签路径：官方 `wover-tag-api/src/main/java/de/ambertation/wover/tag/api/
#      predefined/CommonBlockTags.java`（仓库 quiqueck/WorldWeaver，分支 `release/26.3`，
#      提交 36ee9d8b697a777fbd9f51e7a18259c12598fe14 = 版本 26.301.2，与
#      `upstream/BetterNether/gradle.properties` 的 `wover_version` 一致）。
#      常量旁 Javadoc 就写着路径，逐条抄进 {@link WOVER_TAG_PATHS} —— 不按命名习惯推。
#
#   2) 基表成员：官方仓库**提交了** wover 自己的生成数据
#      `wover-tag-api/src/main/generated/data/wover/tags/block/<路径>.json`
#      （同一提交）。也就是说「wover 的 datagen 产物没提交」这个说法是错的，之前那次
#      调查只看了 BetterNether 的仓库。本表逐条抄自这些文件，`#` 前缀表示标签引用。
#      注意 `surfaces/nether/netherrack`、`surfaces/sculk_like`、`surfaces/soil_or_logs`
#      三个基表现在都有确切文件，之前把它们列为「基表不可证实」是证据缺失，不是真缺口。
#
#   3) 增量：本仓库上游树 `upstream/BetterNether/src/main/generated/data/wover/tags/block/**`
#      （`_read_tag_values` 读它）。BCLib 的 `release/26.3` 里没有
#      `data/wover/tags/block/**`（该分支文件树 528 项，未命中一条），所以本移植
#      参与的上游里只有 BetterNether 会往这些标签里加成员。
#
# `#wover:*` 引用按同一张表递归展开（`_resolve_wover_tag`），`#minecraft:*` 保留成标签
# —— 原版标签在服务器上一定存在。表里没有的标签继续阻塞。
WOVER_TAG_PATHS: dict[str, str] = {
    # 本模组 trait 直接用到的
    "NETHER_STONES": "surfaces/nether/stones",
    "SOUL_GROUND": "surfaces/soul_ground",
    "NETHER_MYCELIUM": "surfaces/nether/mycelium",
    "NETHERRACK": "surfaces/nether/netherrack",
    "SCULK_LIKE": "surfaces/sculk_like",
    "SOIL_OR_LOGS": "surfaces/soil_or_logs",
    # 递归展开时会被引用到的（soil_or_logs → soil/terrain → …）
    "SOIL": "surfaces/soil",
    "TERRAIN": "surfaces/terrain",
    "NETHER_TERRAIN": "surfaces/nether/terrain",
    "MYCELIUM": "surfaces/mycelium",
    "END_STONES": "surfaces/end/stones",
    "NETHER_ORES": "ores/nether",
}

WOVER_TAG_BASES: dict[str, tuple[str, ...]] = {
    # surfaces/nether/stones.json = ["#minecraft:base_stone_nether"]
    "wover:surfaces/nether/stones": ("#minecraft:base_stone_nether",),
    # surfaces/nether/netherrack.json（5 条，全是显式原版 id）
    "wover:surfaces/nether/netherrack": (
        "minecraft:crimson_nylium",
        "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:netherrack",
        "minecraft:warped_nylium",
    ),
    # surfaces/nether/mycelium.json = []（wover 侧本来就空，成员全在增量里）
    "wover:surfaces/nether/mycelium": (),
    # surfaces/mycelium.json = ["minecraft:mycelium", "#wover:surfaces/nether/mycelium"(可选)]
    "wover:surfaces/mycelium": ("minecraft:mycelium", "#wover:surfaces/nether/mycelium"),
    # surfaces/soul_ground.json = ["minecraft:soul_sand", "minecraft:soul_soil"]
    "wover:surfaces/soul_ground": ("minecraft:soul_sand", "minecraft:soul_soil"),
    # surfaces/sculk_like.json = ["minecraft:sculk"]
    "wover:surfaces/sculk_like": ("minecraft:sculk",),
    # surfaces/soil.json = ["#minecraft:dirt", "minecraft:end_stone", "minecraft:farmland"]
    "wover:surfaces/soil": ("#minecraft:dirt", "minecraft:end_stone", "minecraft:farmland"),
    # surfaces/terrain.json（13 条显式/标签成员 + 3 个可选子标签）
    "wover:surfaces/terrain": (
        "#minecraft:base_stone_overworld",
        "minecraft:bone_block",
        "minecraft:dirt",
        "#minecraft:dripstone_replaceable_blocks",
        "minecraft:farmland",
        "minecraft:glowstone",
        "minecraft:grass_block",
        "minecraft:gravel",
        "minecraft:magma_block",
        "#minecraft:nylium",
        "minecraft:red_sand",
        "minecraft:sand",
        "minecraft:sculk",
        "#wover:surfaces/end/stones",
        "#wover:surfaces/mycelium",
        "#wover:surfaces/nether/terrain",
    ),
    # surfaces/nether/terrain.json（7 条显式/标签成员 + 4 个可选子标签）
    "wover:surfaces/nether/terrain": (
        "minecraft:blackstone",
        "minecraft:bone_block",
        "minecraft:glowstone",
        "minecraft:gravel",
        "minecraft:magma_block",
        "#minecraft:nylium",
        "#wover:ores/nether",
        "minecraft:red_sand",
        "#wover:surfaces/nether/mycelium",
        "#wover:surfaces/nether/netherrack",
        "#wover:surfaces/soul_ground",
    ),
    # surfaces/end/stones.json = ["minecraft:end_stone"]
    "wover:surfaces/end/stones": ("minecraft:end_stone",),
    # ores/nether.json = ["minecraft:nether_gold_ore", "minecraft:nether_quartz_ore"]
    "wover:ores/nether": ("minecraft:nether_gold_ore", "minecraft:nether_quartz_ore"),
    # surfaces/soil_or_logs.json = ["#minecraft:logs", "#minecraft:planks",
    #                              "#wover:surfaces/soil"(可选), "#wover:surfaces/terrain"(可选)]
    "wover:surfaces/soil_or_logs": (
        "#minecraft:logs",
        "#minecraft:planks",
        "#wover:surfaces/soil",
        "#wover:surfaces/terrain",
    ),
}

#: 基表来源的一句话摘要（进 notes，方便被推翻）。
WOVER_BASE_SOURCE_NOTE = (
    "wover 基表逐条抄自官方 WorldWeaver 仓库 release/26.3（提交 36ee9d8b697a777fbd9f51e7a18259c12598fe14）"
    "已提交的生成数据 wover-tag-api/src/main/generated/data/wover/tags/block/<路径>.json；"
    "常量名→路径取自同提交的 CommonBlockTags.java"
)

# BCLib 基类的支撑语义（源码不在本仓库，语义来自官方仓库 release/26.3 的
# `src/main/java/org/betterx/bclib/blocks/*.java`，逐条按方法体抄写）。
#
#   官方 `BasePlantBlock.java:69`：
#       canSurvive = isTerrain(pos.below())                       // = SurvivesOnBlockTrait
#                    || SurvivesOnSolidTrait.survivesOn(..., Direction.UP)
#       → 方向 = down。
#   官方 `BaseWallPlantBlock.java:72`：
#       FACING 是**水平**方向、指向背离墙的一侧；支撑方块在 `pos.relative(FACING.getOpposite())`，
#       判定 = `BlocksHelper.isDecorationSupport(level, supportPos, state, FACING)`
#       → 方向 = facing（水平四向）。
#   官方 `AbstractVineBlock.java:68-78`（`BaseVineBlock extends` 它，`BaseVineBlock.Growing`
#   只加 randomTick）：
#       canSurvive = isSupport = up.is(this) || up.is(BlockTags.LEAVES)
#                                || canSupportCenter(world, pos.above(), Direction.DOWN)
#       → 方向 = up，语义是「同类 ∪ `#minecraft:leaves` ∪ 任何坚固面」。
#       CE 的 hanging_block 只能在白名单模式下列成员，拿不到「任何坚固面」那一支 ——
#       发布会让方块在坚固面下方破掉，所以这条留成 gap（继续阻塞）。
#   官方 `SurvivesOnSolidTrait` + `BlocksHelper`：
#       "坚固面" 的准确含义 = `(state.isSolid() && state.isFaceSturdy(level, pos, face))
#                              || (state.is(BlockTags.LEAVES) && isFullCube(...))`
#       → **坚固面 ∪ 整方块树叶**。CE 的 sturdy_base_block / directional_attached_block
#       只表达「坚固面」那一半，树叶分支表达不了，渲染时写进 notes。
#
# 值是 `(方向, 证据, 缺口)`：缺口非空 = 这条语义 CE 表达不了，对应方块继续列阻塞。
BCLIB_BASE_CLASSES: dict[str, tuple[str, str, str | None]] = {
    "BasePlantBlock": (DIR_DOWN,
                       "BCLib BasePlantBlock.canSurvive（官方 release/26.3）：pos.below() + Direction.UP",
                       None),
    "BaseWallPlantBlock": (DIR_FACING,
                           "BCLib BaseWallPlantBlock.canSurvive（官方 release/26.3）："
                           "FACING 水平、支撑在 FACING.getOpposite()",
                           None),
    # 藤类基类：BCLib AbstractVineBlock / BaseSimpleVineBlock 的 canSurvive 都是
    # 「上方是同类藤 或 树叶 或 坚固面」的 OR，本质是"靠上方方块支撑"= 悬挂(UP)。
    # 用 hanging_block 的黑名单模式（blacklist: true + 空列表 = 上方任意方块都算支撑）
    # 正好表达这条 OR（含树叶），且天然"只能挂方块下面、不能放方块上面"。
    "BaseSimpleVineBlock": (DIR_UP,
                            "BCLib AbstractVineBlock.canSurvive：up.is(this) || up.is(LEAVES) "
                            "|| canSupportCenter(pos.above(), DOWN)",
                            None),
    "BaseVineBlock": (DIR_UP,
                      "BCLib AbstractVineBlock.canSurvive：靠上方方块支撑（同上）",
                      None),
    "BaseVineBlock.Growing": (DIR_UP,
                              "BCLib AbstractVineBlock.canSurvive（官方 release/26.3）："
                              "isSupport = up.is(this) || up.is(BlockTags.LEAVES) "
                              "|| canSupportCenter(pos.above(), DOWN)",
                              None),
}

SOLID_SEMANTICS_NOTE = (
    "SurvivesOnSolidTrait 的官方语义是「坚固面 ∪ 整方块树叶」"
    "（BCLib BlocksHelper.isDecorationSupport）；CE 的支撑行为只表达「坚固面」那一半，"
    "整方块树叶上的放置表达不了")



@dataclass(frozen=True)
class SupportRule:
    """一个方块的上游支撑真值（未渲染成 YAML）。"""

    block_id: str
    trait: str                 # 上游 trait 表达式，如 "NetherSurvival.gravel()"
    direction: str             # DIR_*
    direction_evidence: str    # 方向证据（文件:canSurvive）
    blocks: tuple[str, ...] = ()   # 白名单：显式方块 id（含原版 id）
    tags: tuple[str, ...] = ()     # 白名单：可直接写进 CE 的标签 id（只含 minecraft:*）
    stackable_self: bool = False   # 上游允许「支撑物是自己」
    solid: bool = False            # 规则是「任何坚固面」（SurvivesOnSolidTrait）
    verified: bool = True          # 全部成员都可证实
    blocked: str | None = None     # 非空 = 不发布，原因写在这里
    notes: tuple[str, ...] = ()

    def whitelist(self) -> bool:
        return bool(self.blocks or self.tags)


@dataclass(frozen=True)
class SupportBehavior:
    """一个方块渲染出来的 CE 行为片段（可直接并进 blocks.py 的 `behaviors`）。"""

    block_id: str
    entries: list[list[str]] = field(default_factory=list)
    required_properties: tuple[str, ...] = ()
    notes: tuple[str, ...] = ()
    blocked: str | None = None


# ---------------------------------------------------------------- 源码定位


def _blocks_dir(upstream: Path) -> Path:
    return upstream / "src/main/java/org/betterx/betternether/blocks"


def _registry_dir(upstream: Path) -> Path:
    return upstream / "src/main/java/org/betterx/betternether/registry/block"


def _data_dir(upstream: Path) -> Path:
    return upstream / "src/main/generated/data"


def _balanced_body(text: str, start: int) -> str:
    """从 `start`（指向 `{` 之后）取出配平的函数体。"""
    depth = 1
    pos = start
    while depth and pos < len(text):
        char = text[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        pos += 1
    return text[start:pos - 1]


_CAN_SURVIVE = re.compile(r"public boolean canSurvive\(BlockState[^)]*\)\s*\{")


def _body_start(text: str, decl_end: int) -> int | None:
    """类声明之后第一个 `{` 的下标（跳过注解/泛型的 `{` 不可能出现得更早）。"""
    brace = text.find("{", decl_end)
    return brace if brace >= 0 else None


def _depth_map(body: str) -> list[int]:
    """`body` 每个字符所在的花括号深度（0 = 这个类自己的成员层级）。"""
    depths: list[int] = []
    depth = 0
    for char in body:
        if char == "}":
            depth -= 1
        depths.append(depth)
        if char == "{":
            depth += 1
    return depths


#: 上游 Java 源码的读取缓存。破坏判据层要逐类扫全部方块类，重复读盘会把门禁拖成分钟级；
#: 这些缓存只缓存**只读**结果，键里带目录，测试用不同临时目录时互不干扰。
_SOURCE_CACHE: dict[str, str] = {}
_CLASS_FILE_CACHE: dict[str, dict[str, tuple[Path, ...]]] = {}
_DECL_CACHE: dict[tuple[str, str, str], tuple[str | None, str]] = {}
_SUPER_CACHE: dict[tuple[str, str], str | None] = {}
_MATERIAL_CACHE: dict[str, dict[str, str]] = {}


def _block_source(path: Path) -> str:
    key = str(path)
    text = _SOURCE_CACHE.get(key)
    if text is None:
        text = path.read_text(encoding="utf-8")
        _SOURCE_CACHE[key] = text
    return text


def _classes_in(upstream: Path) -> dict[str, tuple[Path, ...]]:
    """`类名 → 声明它的文件`（含嵌套类，一次扫描建索引）。"""
    key = str(_blocks_dir(upstream))
    index = _CLASS_FILE_CACHE.get(key)
    if index is None:
        built: dict[str, list[Path]] = {}
        for path in sorted(_blocks_dir(upstream).glob("*.java")):
            for name in re.findall(r"\bclass\s+(\w+)", _block_source(path)):
                built.setdefault(name, []).append(path)
        index = {name: tuple(paths) for name, paths in built.items()}
        _CLASS_FILE_CACHE[key] = index
    return index


def _decl_body(upstream: Path, cls: str, pattern: "re.Pattern[str]",
               label: str) -> tuple[str | None, str]:
    """取这个类**自己**（深度 0 的成员层级）匹配 `pattern` 的方法体，`label` 只用于证据串。

    必须限定在类体内部、且只认**本类层级**的声明：
      * 不能从 `class X` 一路搜到文件末尾 —— 那样会拿到**同一个文件里后一个类**的方法
        （`BlockNetherGrass.java` 里 `BaseBlockNetherGrass` 之后还有别的类）；
      * 也不能认**嵌套类**里的声明（它属于嵌套类，不属于外层）。
    没有本类层级的声明时 body 为 None，由调用方沿 `extends` 链继续找。
    """
    simple = cls.split(".")[-1]
    cache_key = (str(_blocks_dir(upstream)), simple, pattern.pattern)
    cached = _DECL_CACHE.get(cache_key)
    if cached is not None:
        return cached
    decl = re.compile(r"\bclass\s+" + re.escape(simple) + r"\b")
    for path in _classes_in(upstream).get(simple, ()):
        text = _block_source(path)
        for declaration in decl.finditer(text):
            brace = _body_start(text, declaration.end())
            if brace is None:
                continue
            class_body = _balanced_body(text, brace + 1)
            depths = _depth_map(class_body)
            for match in pattern.finditer(class_body):
                if depths[match.start()] != 0:
                    continue  # 嵌套类里的声明不算本类的
                found = (_balanced_body(class_body, match.end()),
                         f"{path.name}:{simple}.{label}")
                _DECL_CACHE[cache_key] = found
                return found
    found = (None, f"{simple}: 本类层级没有 {label}")
    _DECL_CACHE[cache_key] = found
    return found


def _class_body(upstream: Path, cls: str) -> tuple[str | None, str]:
    """取这个类**自己**声明的 `canSurvive` 函数体（规则见 {@link _decl_body}）。"""
    return _decl_body(upstream, cls, _CAN_SURVIVE, "canSurvive")


def _super_class(upstream: Path, cls: str) -> str | None:
    """这个类的 `extends` 目标（只限单继承的类名，忽略接口列表）。"""
    simple = cls.split(".")[-1]
    cache_key = (str(_blocks_dir(upstream)), simple)
    if cache_key in _SUPER_CACHE:
        return _SUPER_CACHE[cache_key]
    result: str | None = None
    for path in _classes_in(upstream).get(simple, ()):
        text = _block_source(path)
        decl = re.search(r"\bclass\s+" + re.escape(simple) + r"\b(.*?)\{", text, re.S)
        if not decl:
            continue
        ext = re.search(r"\bextends\s+([\w.]+)", decl.group(1))
        result = ext.group(1) if ext else None
        break
    _SUPER_CACHE[cache_key] = result
    return result


def _direction_of(body: str) -> str:
    """从 canSurvive 函数体判定支撑方向。"""
    above = "pos.above()" in body or ".above();" in body
    below = "pos.below()" in body or ".below();" in body
    # 水平的四邻（`.north()` 等）说明支撑物可能在**任意侧面**，与单个 `facing` 不是一回事。
    sideways = any(token in body for token in
                   (".north()", ".south()", ".east()", ".west()"))
    facing = "FACING" in body or "pos.relative(" in body or "getValue(SHAPE)" in body
    if sideways or (facing and (above or below)):
        return DIR_MIXED
    if facing:
        return DIR_FACING
    if above and below:
        return DIR_MIXED
    if above:
        return DIR_UP
    if below:
        return DIR_DOWN
    return DIR_UNKNOWN


def _resolve_direction(upstream: Path, cls: str,
                       chain_limit: int = 6) -> tuple[str, str, str | None]:
    """沿 `extends` 链找 canSurvive，返回 `(方向, 证据, 阻塞原因)`。

    链走到仓库外的 BCLib 基类时，用 {@link BCLIB_BASE_CLASSES} 的官方语义兜底
    （表里可能还带一个 gap = 这条语义 CE 表达不了）；表里没有的基类才阻塞。
    `seen` 记**限定名**（`BaseVineBlock.Growing` 这种嵌套类必须带外类名才不含糊），
    查表时限定名和简单名都试。
    """
    seen: list[str] = []
    current: str | None = cls
    while current and len(seen) < chain_limit:
        simple = current.split(".")[-1]
        seen.append(current)
        body, evidence = _class_body(upstream, simple)
        if body is not None:
            direction = _direction_of(body)
            if direction == DIR_UNKNOWN:
                return direction, evidence, f"{evidence} 里读不出支撑方向"
            return direction, evidence, None
        current = _super_class(upstream, simple)
    for name in reversed(seen):
        known = (BCLIB_BASE_CLASSES.get(name)
                 or BCLIB_BASE_CLASSES.get(name.split(".")[-1]))
        if known:
            return known[0], known[1], known[2]
    # `extends` 链走到了没有记录的基类：不能猜方向。
    tail = " → ".join(seen)
    return DIR_UNKNOWN, tail, f"canSurvive 定义在未 vendored 的基类里（继承链 {tail}）"


def _chain_body(upstream: Path, cls: str, pattern: "re.Pattern[str]", label: str,
                chain_limit: int = 6) -> tuple[str | None, str, tuple[str, ...]]:
    """沿 `extends` 链取第一个声明了 `pattern` 的函数体，同时返回走过的链。

    链上一旦出现**限定名**（`BaseVineBlock.Growing`），说明走到了基类的嵌套类：
    它一定不在本仓库里，按名字查会误命中同名的仓库内类，所以直接停。
    """
    seen: list[str] = []
    current: str | None = cls
    while current and len(seen) < chain_limit:
        if "." in current:
            break
        simple = current.split(".")[-1]
        seen.append(simple)
        body, evidence = _decl_body(upstream, simple, pattern, label)
        if body is not None:
            return body, evidence, tuple(seen)
        current = _super_class(upstream, simple)
    return None, "", tuple(seen)


def _can_survive_body(upstream: Path, cls: str, chain_limit: int = 6) -> str | None:
    """沿 `extends` 链取第一个声明了 canSurvive 的函数体。"""
    body, _evidence, _chain = _chain_body(upstream, cls, _CAN_SURVIVE, "canSurvive",
                                          chain_limit)
    return body


def _stackable_self(upstream: Path, cls: str) -> bool:
    body = _can_survive_body(upstream, cls) or ""
    return "== this" in body


def _or_semantics(upstream: Path, cls: str) -> bool:
    """canSurvive 是不是「trait ∪ 坚固面」这种 OR（CE 单条行为只能表达一半）。"""
    body = _can_survive_body(upstream, cls) or ""
    return "SurvivesOnBlockTrait" in body and "SurvivesOnSolidTrait" in body


# ---------------------------------------------------------------- 注册表解析


_DEFINE_BLOCK = re.compile(r'defineBlock\("([\w]+)"')
_ADD_TRAIT = re.compile(
    r"NetherSurvival\.(\w+)\(\)|SurvivesOnSolidTrait\.(\w+)")


def _registered_traits(upstream: Path) -> dict[str, tuple[str, str]]:
    """`方块 id` → `(trait 表达式, 类名)`，只收带支撑 trait 的方块。

    以 `.buildAndRegister()` 为段尾；`registerBlock(...)` 风格的注册没有
    `.addTrait` 链，天然不会被匹配到。
    """
    result: dict[str, tuple[str, str]] = {}
    for path in sorted(_registry_dir(upstream).glob("*.java")):
        text = path.read_text(encoding="utf-8")
        for match in _DEFINE_BLOCK.finditer(text):
            block_id = match.group(1)
            end = text.find(".buildAndRegister()", match.end())
            segment = text[match.end():end if end > 0 else len(text)]
            trait = _ADD_TRAIT.search(segment)
            if not trait:
                continue
            expr = (f"NetherSurvival.{trait.group(1)}()" if trait.group(1)
                    else f"SurvivesOnSolidTrait.{trait.group(2)}")
            cls_match = (re.search(r"new\s+([\w.]+)\s*\(", segment)
                         or re.search(r"([\w.]+)::new", segment))
            result[block_id] = (expr, cls_match.group(1) if cls_match else "")
    return result


_METHOD = re.compile(r"public static List<BlockTrait<\?,\s*\?>>\s+(\w+)\(\)\s*\{")


def _nether_survival_methods(upstream: Path) -> dict[str, str]:
    """`NetherSurvival` 里每个返回 List<BlockTrait> 的方法 → 方法体。"""
    path = _blocks_dir(upstream) / "NetherSurvival.java"
    text = path.read_text(encoding="utf-8")
    methods: dict[str, str] = {}
    for match in _METHOD.finditer(text):
        methods[match.group(1)] = _balanced_body(text, match.end())
    return methods


# ---------------------------------------------------------------- 标签 / 方块解析


def _tag_file(upstream: Path, namespace: str, name: str) -> Path | None:
    candidate = _data_dir(upstream) / namespace / "tags/block" / f"{name}.json"
    return candidate if candidate.is_file() else None


def _snake(name: str) -> str:
    return name.lower()


def _read_tag_values(upstream: Path, namespace: str, name: str) -> tuple[tuple[str, ...], str | None]:
    """读一个生成标签文件的 `values`，规范化成「`#`=标签引用、其余=方块 id」。"""
    path = _tag_file(upstream, namespace, name)
    if path is None:
        return (), f"data/{namespace}/tags/block/{name}.json 不存在"
    values = json.loads(path.read_text(encoding="utf-8")).get("values", [])
    entries: list[str] = []
    for entry in values:
        if isinstance(entry, dict):  # {"id": …, "required": …}
            entry = entry.get("id", "")
        if not entry:
            continue
        if entry.startswith("#") or ":" in entry:
            entries.append(entry)
        else:
            entries.append(f"minecraft:{entry}")
    return tuple(entries), None


def _common_tag(upstream: Path, const: str
                ) -> tuple[str | None, tuple[str, ...], tuple[str, ...], str | None]:
    """`CommonBlockTags.X` → `(wover 标签 id, 显式方块 id, minecraft 标签, 阻塞原因)`。

    常量名→路径查 {@link WOVER_TAG_PATHS}（逐条抄自官方 CommonBlockTags.java），
    成员 = 基表（{@link WOVER_TAG_BASES}，官方生成数据）+ 本模组增量（上游树），
    基表里的 `#wover:*` 递归展开。任何一环缺证据都返回 error，调用方据此阻塞。
    """
    rel = WOVER_TAG_PATHS.get(const)
    if rel is None:
        return None, (), (), (
            f"CommonBlockTags.{const} 不在已核验的常量→路径表里；"
            f"官方 CommonBlockTags.java（release/26.3）里没有这个常量，或本模块还没抄进来")
    tag = f"wover:{rel}"
    resolved = _resolve_wover_tag(upstream, tag)
    return tag, resolved["blocks"], resolved["tags"], resolved["error"]


def _resolve_wover_tag(upstream: Path, tag: str,
                       stack: tuple[str, ...] = ()) -> dict:
    """把一个 wover 标签展开成 `{blocks, tags, error}`（递归到不动点）。

    `blocks` 是显式方块 id，`tags` 是**原版**标签 id（运行期一定存在）——
    `wover:` 标签在 CE 包里不存在，出现即等于没写。
    """
    if tag in stack:
        return {"blocks": (), "tags": (),
                "error": f"wover 标签成环：{' → '.join(stack + (tag,))}"}
    bases = WOVER_TAG_BASES.get(tag)
    if bases is None:
        return {"blocks": (), "tags": (),
                "error": f"{tag} 的官方基表不在 WOVER_TAG_BASES 里（没有可复核来源）"}
    increments, _missing = _read_tag_values(upstream, "wover", tag.split(":", 1)[1])
    blocks: list[str] = []
    tags: list[str] = []
    errors: list[str] = []
    for entry in bases + increments:
        if not entry.startswith("#"):
            blocks.append(entry)
            continue
        nested = entry[1:]
        if nested.startswith("minecraft:"):
            tags.append(nested)          # 原版标签：运行期一定存在，原样写进 CE
            continue
        if not nested.startswith("wover:"):
            errors.append(f"{tag} 引用了未核验命名空间的标签 #{nested}")
            continue
        sub = _resolve_wover_tag(upstream, nested, stack + (tag,))
        blocks.extend(sub["blocks"])
        tags.extend(sub["tags"])
        if sub["error"]:
            errors.append(sub["error"])
    return {"blocks": tuple(dict.fromkeys(blocks)),
            "tags": tuple(dict.fromkeys(tags)),
            "error": "；".join(errors) if errors else None}


def _own_tag_members(upstream: Path, name: str) -> tuple[tuple[str, ...], str | None]:
    """本模组标签的成员（生成文件是完整的）。"""
    path = _tag_file(upstream, NAMESPACE, name)
    if path is None:
        return (), f"data/betternether/tags/block/{name}.json 不存在"
    values = json.loads(path.read_text(encoding="utf-8")).get("values", [])
    members: list[str] = []
    for entry in values:
        if isinstance(entry, dict):  # {"id": …, "required": …}
            entry = entry.get("id", "")
        if not entry:
            continue
        if entry.startswith("#"):  # 标签嵌套：成员要到别的包里查
            return (), f"标签 {NAMESPACE}:{name} 嵌套了 {entry}，成员无法在此展开"
        members.append(entry if ":" in entry else f"minecraft:{entry}")
    return tuple(members), None


def _vanilla_tag(upstream: Path, const: str) -> tuple[str | None, str | None]:
    """`BlockTags.X` → `minecraft:x`。只要求本仓库生成过同名标签文件（存在性证据）。"""
    name = _snake(const)
    if not _tag_file(upstream, "minecraft", name):
        return None, f"data/minecraft/tags/block/{name}.json 不存在"
    return f"minecraft:{name}", None


def _tag_expr(upstream: Path, expr: str) -> dict:
    """把 `withTag(...)` 的实参解成 `{blocks, tags, error, external}`。"""
    expr = expr.strip()
    if expr.startswith("CommonBlockTags."):
        _tag, blocks, tags, error = _common_tag(upstream, expr.split(".", 1)[1])
        return {"blocks": blocks, "tags": tags, "error": error}
    if expr.startswith("BlockTags."):
        tag, error = _vanilla_tag(upstream, expr.split(".", 1)[1])
        return {"blocks": (), "tags": (tag,) if tag else (), "error": error}
    if expr.startswith("NetherTags."):
        members, error = _own_tag_members(upstream, _snake(expr.split(".", 1)[1]))
        return {"blocks": members, "tags": (), "error": error}
    return {"blocks": (), "tags": (), "error": f"未知标签表达式 {expr}"}


def _blocks_expr(expr: str) -> tuple[str | None, str | None]:
    key = expr.strip()
    if key in JAVA_BLOCK_CONSTANTS:
        return JAVA_BLOCK_CONSTANTS[key], None
    return None, f"Java 常量 {key} 没有可证实的方块 id（不在白名单表里）"


_WITH_TAG = re.compile(r"SurvivesOnBlockTrait\.withTag\(([\w.]+)\)")
_WITH_BLOCKS = re.compile(r"SurvivesOnBlockTrait\.withBlocks\(([^)]*)\)")


def _expand_trait(upstream: Path, expr: str,
                  methods: dict[str, str]) -> dict:
    """把 `NetherSurvival.X()` / `SurvivesOnSolidTrait.DEFAULT` 解成白名单。"""
    if expr.startswith("SurvivesOnSolidTrait."):
        # 语义见 SOLID_SEMANTICS_NOTE（官方 BCLib 源码），不是猜的。
        return {"blocks": (), "tags": (), "error": None, "solid": True,
                "from_common_tag": False}
    name = re.match(r"NetherSurvival\.(\w+)\(\)", expr)
    if not name:
        return {"blocks": (), "tags": (), "error": f"无法解析 trait {expr}"}
    body = methods.get(name.group(1))
    if body is None:
        return {"blocks": (), "tags": (), "error": f"NetherSurvival.{name.group(1)}() 方法体找不到"}
    blocks: list[str] = []
    tags: list[str] = []
    errors: list[str] = []
    for match in _WITH_TAG.finditer(body):
        parsed = _tag_expr(upstream, match.group(1))
        blocks.extend(parsed["blocks"])
        tags.extend(parsed["tags"])
        if parsed["error"]:
            errors.append(parsed["error"])
    for match in _WITH_BLOCKS.finditer(body):
        for raw in match.group(1).split(","):
            raw = raw.strip()
            if not raw:
                continue
            block_id, error = _blocks_expr(raw)
            if error:
                errors.append(error)
            else:
                blocks.append(block_id)
    if not blocks and not tags and not errors:
        errors.append(f"NetherSurvival.{name.group(1)}() 里没有解析出任何 tag/方块")
    return {"blocks": tuple(dict.fromkeys(blocks)), "tags": tuple(dict.fromkeys(tags)),
            "error": "；".join(errors) if errors else None, "solid": False,
            "from_common_tag": bool(tags) and "CommonBlockTags." in body}


# ---------------------------------------------------------------- 规则装配


def load_support_rules(upstream: Path) -> dict[str, SupportRule]:
    """读出所有带支撑 trait 的方块 → {@link SupportRule}。

    只读，不写任何文件；同一棵树多次调用结果完全一致（无随机、无缓存副作用）。
    """
    upstream = Path(upstream)
    methods = _nether_survival_methods(upstream)
    rules: dict[str, SupportRule] = {}
    for block_id, (expr, cls) in sorted(_registered_traits(upstream).items()):
        direction, evidence, dir_error = (
            _resolve_direction(upstream, cls) if cls else
            (DIR_UNKNOWN, "(无类名)", "注册处没有可解析的类名"))
        expanded = _expand_trait(upstream, expr, methods)
        notes: list[str] = []
        blocked = expanded["error"] or dir_error
        if direction == DIR_MIXED:
            blocked = blocked or (
                "canSurvive 同时看两个方向（见 " + evidence + "），CE 单条支撑行为表达不了")
        if direction == DIR_FACING and expanded["blocks"]:
            # CE 的 directional_attached_block 用 `facing: horizontal_direction`（只有水平四向），
            # 上游 facing 还包含 UP（`if (direction == Direction.DOWN) return false;`）。
            # 少一个朝上状态属于**无法表达**的差异，不能当成完整覆盖。
            notes.append("上游朝上也能存活（facing=up），CE 的 directional_attached_block "
                         "只有水平 facing，该状态不可表达")
        if expanded["solid"]:
            # `SurvivesOnSolidTrait` 的语义已经由官方源码证实（见 SOLID_SEMANTICS_NOTE），
            # 所以规则本身可发布；不可表达的只有树叶那一支。
            notes.append(SOLID_SEMANTICS_NOTE)
            if cls and _or_semantics(upstream, cls):
                # 例：gloomwisp_vine = `SurvivesOnBlockTrait || 坚固面 || 同类`。
                notes.append("上游 canSurvive 是 OR 语义（白名单 ∪ 坚固面 ∪ 同类），"
                             "这里只表达「坚固面 ∪ 同类自堆叠」")
        if expanded.get("from_common_tag"):
            # 成员来自两处，都必须写清楚，方便任何人复核/推翻。
            notes.append(
                "白名单 = wover 标签「基表 ∪ 本模组增量」，两层都按官方产物逐条抄写："
                + WOVER_BASE_SOURCE_NOTE
                + "；增量来自 upstream/BetterNether/src/main/generated/data/wover/tags/block/ 下"
                  "同名标签文件。基表里的 `#wover:*` 引用已递归展开，"
                  "所以原版成员有的写成 `minecraft:*` 标签、有的写成显式 id")
        verified = blocked is None
        rules[block_id] = SupportRule(
            block_id=block_id,
            trait=expr,
            direction=direction,
            direction_evidence=evidence,
            blocks=expanded["blocks"],
            tags=expanded["tags"],
            stackable_self=_stackable_self(upstream, cls) if cls else False,
            solid=expanded["solid"],
            verified=verified and blocked is None,
            blocked=blocked,
            notes=tuple(notes),
        )
    # 补充：类里手写 canSurvive、没走支撑 trait 的植物（藤/菌/藤蔓等）默认没有规则
    # = 无放置限制 = 能放到方块上面。按它们自己类的 canSurvive 方向补一条规则
    # （UP→悬挂 / DOWN→地面）。同一个函数返回，**生成器与门禁读同一份真值**。
    rules.update(_custom_plant_rules(upstream, rules))
    # 用户点名的方向覆盖（放在最后，两条来源 —— 支撑 trait 与类内 canSurvive —— 都盖得住）。
    # 只改方向、不动白名单：`inspect_support` 会把 DOWN 落到 `bush_block`，
    # 也就是「下方有方块才生成」。覆盖同时清掉 `blocked`，否则原来的「多向判据表达不了」
    # 会让这条规则直接不发布，方块反而变成完全没有放置限制。
    for block_id, direction in FORCED_DIRECTIONS.items():
        existing = rules.get(block_id)
        if existing is None:
            continue
        rules[block_id] = replace(
            existing, direction=direction, blocked=None, blocks=(), tags=(),
            notes=existing.notes + ("用户要求：只认下方支撑，覆盖上游的多向判据",))
    return rules


_PLANT_CLASS_HINTS = (
    "Vine", "Plant", "Mushroom", "Grass", "Seed", "Cone", "Bush", "Moss", "Flower",
    "Eyeball", "Equisetum", "Reed", "Cactus", "Lily", "Agave", "Fern", "Wart", "Lumabus",
    "Gourd", "Algae", "Spore", "EggPlant", "Ink", "BlackApple", "Mold", "Crop", "Sapling",
    "Stem",
)


def _custom_plant_rules(upstream: Path, rules: dict[str, SupportRule]) -> dict[str, SupportRule]:
    """从 `block_properties.json` 的 class 找出「植物类但没规则」的方块，按类方向补规则。"""
    path = upstream / "src/main/generated/block_properties.json"
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001 —— 读不到就当没有候选，不阻断主流程
        return {}
    entries = data.items() if isinstance(data, dict) else [
        ((b.get("id") or b.get("name")), b) for b in data if isinstance(b, dict)]
    candidates: dict[str, str] = {}
    for key, value in entries:
        if not isinstance(value, dict):
            continue
        cls = value.get("class", "") or ""
        if not any(hint in cls for hint in _PLANT_CLASS_HINTS):
            continue
        name = (key or "").split(":")[-1]
        if name and cls:
            candidates[name] = cls
    return augment_custom_plant_rules(upstream, rules, candidates)


def augment_custom_plant_rules(upstream: Path, rules: dict[str, SupportRule],
                               candidates: dict[str, str]) -> dict[str, SupportRule]:
    """给「没走支撑 trait、类里手写 canSurvive」的植物补一条方向规则。

    `candidates` = `{方块短名: 上游类名}`。只为自己类里能读出明确方向
    （`pos.above()`=UP 悬挂 / `pos.below()`=DOWN 地面）的植物补规则；其余跳过（不猜）。
    补出来的规则 `solid=False, whitelist 空` —— 由 {@link inspect_support} 落到
    「UP→hanging_block 黑名单（只能挂方块下面）/ DOWN→bush_block 黑名单（能放方块上面）」，
    这样既不会没放置限制（乱放），也不会永远存不活。
    """
    added: dict[str, SupportRule] = {}
    for block_id, cls in candidates.items():
        if block_id in rules or not cls:
            continue
        direction, evidence, err = _resolve_direction(upstream, cls)
        if err or direction not in (DIR_UP, DIR_DOWN):
            continue
        added[block_id] = SupportRule(
            block_id=block_id, trait="custom-canSurvive", direction=direction,
            direction_evidence=evidence, blocks=(), tags=(), stackable_self=False,
            solid=False, verified=True, blocked=None,
            notes=(f"类里手写 canSurvive（{evidence}）；按方向 {direction} 补放置行为",))
    return added


# ---------------------------------------------------------------- CE 行为渲染


def _lines(blocks: tuple[str, ...], tags: tuple[str, ...],
           block_key: str, tag_key: str) -> list[str]:
    out: list[str] = []
    if blocks:
        out.append(f"{block_key}:")
        out.extend(f"  - {item}" for item in blocks)
    if tags:
        out.append(f"{tag_key}:")
        out.extend(f"  - {item}" for item in tags)
    return out


def inspect_support(block_id: str, *, rules: dict[str, SupportRule] | None = None,
                    upstream: Path | None = None,
                    available_ids: set[str] | None = None,
                    available_properties: set[str] | None = None,
                    allow_unverified: bool = False) -> SupportBehavior:
    """把一条规则渲染成 CE 行为，并保留所有取舍说明。

    `available_ids`：本包真正发布的方块 id 集合。给了就过滤白名单 —— 引用一个没
    发布的 id 会让 CE 找不到方块（植物永远不会存活），所以这里显式剔除并记 note。
    `available_properties`：本包给该方块声明了的属性名；`facing` 不在里面就没法挂
    贴面行为。
    `allow_unverified`：`SurvivesOnSolidTrait`（BCLib 源码缺失）这类「按名字可推断、
    但无法逐字证实」的规则要不要发布。
    """
    if rules is None:
        if upstream is None:
            raise ValueError("inspect_support 需要 rules 或 upstream 之一")
        rules = load_support_rules(upstream)
    rule = rules.get(block_id)
    if rule is None:
        return SupportBehavior(block_id=block_id, notes=("上游没有支撑 trait（无支撑限制）",))
    if rule.blocked:
        return SupportBehavior(block_id=block_id, blocked=rule.blocked,
                               notes=rule.notes)
    if not rule.verified and not allow_unverified:
        return SupportBehavior(
            block_id=block_id,
            blocked="规则未逐字证实（" + "；".join(rule.notes) + "）；"
                    "需要 allow_unverified=True 才发布",
            notes=rule.notes)

    blocks, tags = list(rule.blocks), list(rule.tags)
    notes = list(rule.notes)
    if available_ids is not None:
        dropped = [item for item in blocks if item.startswith(NAMESPACE + ":")
                   and item not in available_ids]
        if dropped:
            blocks = [item for item in blocks if item not in dropped]
            notes.append("白名单里这些 id 本包没有发布，已剔除（它们在世界里不存在）："
                         + "、".join(dropped))
    properties: tuple[str, ...] = ()

    if rule.direction == DIR_FACING:
        properties = ("facing",)
        if available_properties is not None and "facing" not in available_properties:
            return SupportBehavior(
                block_id=block_id,
                blocked="需要 facing 属性才能挂 directional_attached_block，但该方块没有声明 facing",
                notes=tuple(notes))
        # 有白名单 → 白名单模式；上游是「任何坚固面」（SurvivesOnSolidTrait）→ 黑名单模式且列表留空。
        entry = ["type: directional_attached_block",
                 f"blacklist: {'false' if rule.whitelist() else 'true'}"]
        entry.extend(_lines(tuple(blocks), tuple(tags), "attached_blocks", "attached_block_tags"))
        return SupportBehavior(block_id=block_id, entries=[entry],
                               required_properties=properties, notes=tuple(notes))

    if not rule.whitelist() and not rule.solid:
        # 白名单空且不是「坚固面」规则 = CE 的 bush_block/hanging_block 永远不会放行。
        # 但如果方向明确是「悬挂(UP)」（上游 canSurvive 靠 pos.above() 支撑），
        # 用户要求「只能挂方块下面、不能放方块上面」——用 hanging_block 的
        # 黑名单模式（blacklist: true + 列表留空 = 上方任意方块都算支撑）落地：
        # 必须挂在某个方块之下，无法直接放到方块上面。这是对「任何坚固面 ∪ 树叶」
        # 这条 OR 判据最安全的近似（放弃「树叶也算」那一支，但方向正确、不再能乱放）。
        if rule.direction == DIR_UP:
            # 2026-10-30 用户要求「悬挂类植物要能向下延伸」。CE 的 hanging_block 只有
            # `stackable: true` 才允许「下方再挂一株同类」，否则整条永远只有顶端一格。
            # 但只有成串的**藤蔓**该延伸：hook_mushroom / rubeus_cone 是单体蘑菇/锥体，
            # 串起来会与上游观感不符，保持不堆叠。
            if block_id.split(":", 1)[-1] in HANGING_GROWING_VINES:
                entry = ["type: hanging_block", "blacklist: true",
                         "stackable: true", f"max_height: {HANGING_MAX_HEIGHT}", "delay: 0"]
                notes.append(
                    f"可向下延伸：stackable=true、max_height={HANGING_MAX_HEIGHT}（构建期硬上限）。"
                    "运行期真正生效的最大长度由 config.yml 的 vine.max-length 决定"
                    "（默认 9，取自上游 BaseVineBlock 的构造参数；bclib 源码不可得，"
                    "该数值是推断值），自然生长几率与扫描周期同样在 config.yml 配置")
                return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))
            entry = ["type: hanging_block", "blacklist: true",
                     "stackable: false", "delay: 0"]
            notes.append("上游判据是「任何坚固面 ∪ 树叶」的 OR；CE 只能给黑名单悬挂，"
                         "放弃树叶支，但保证只能挂方块下面、不能放方块上面")
            return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))
        if rule.direction == DIR_DOWN:
            # 地面类（苔藓/地被）：blacklist 空 = 下方任意方块都算支撑 → 能铺在方块上面。
            entry = ["type: bush_block", "blacklist: true",
                     f"stackable: {'true' if rule.stackable_self else 'false'}"]
            if rule.stackable_self:
                entry.append("max_height: 255")
            entry.append("delay: 0")
            notes.append("上游判据含「任何坚固面 ∪ 树叶」；CE 只能给黑名单底面，"
                         "保证能铺在方块上面（放弃树叶支）")
            return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))
        # 其余（方向不明或地面类）宁可阻塞，也不发一个永远破坏/可乱放的方块。
        if not rule.stackable_self:
            return SupportBehavior(
                block_id=block_id,
                blocked="白名单为空（缺成员证据），发布后植物永远不会存活",
                notes=tuple(notes))

    if rule.solid:
        # `SurvivesOnSolidTrait`：任何坚固面。CE 的对应物就是 sturdy_base_block
        # （默认 support_types: [full]），方向由 canSurvive 决定。
        if rule.direction not in (DIR_DOWN, DIR_UP):
            return SupportBehavior(block_id=block_id,
                                   blocked=f"坚固面规则的支撑方向是 {rule.direction}，"
                                           f"没有对应的 CE 行为", notes=tuple(notes))
        entry = ["type: sturdy_base_block",
                 f"direction: {rule.direction}",
                 "support_types:", "  - full",
                 f"stackable: {'true' if rule.stackable_self else 'false'}"]
        if rule.stackable_self:
            entry.append("max_height: 255")
            notes.append("上游允许无限自堆叠；CE 的 max_height 需要具体上界，取 255")
        entry.append("delay: 0")
        return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))

    if rule.direction == DIR_DOWN:
        entry = ["type: bush_block", "blacklist: false",
                 f"stackable: {'true' if rule.stackable_self else 'false'}"]
        if rule.stackable_self:
            # 上游对「同类自堆叠」没有上界（`down.getBlock() == this`），CE 的
            # `max_height` 必须是一个具体数字（0 不是无限）。取 255（世界高度上限），
            # 在 0..127 的下界里等价于无上界。
            entry.append("max_height: 255")
            notes.append("上游允许无限自堆叠；CE 的 max_height 需要具体上界，取 255")
        entry.append("delay: 0")
        entry.extend(_lines(tuple(blocks), tuple(tags), "bottom_blocks", "bottom_block_tags"))
        return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))

    if rule.direction == DIR_UP:
        entry = ["type: hanging_block", "blacklist: false",
                 "stackable: false", "delay: 0"]
        entry.extend(_lines(tuple(blocks), tuple(tags), "above_blocks", "above_block_tags"))
        return SupportBehavior(block_id=block_id, entries=[entry], notes=tuple(notes))

    return SupportBehavior(block_id=block_id,
                           blocked=f"支撑方向 {rule.direction} 没有对应的 CE 行为",
                           notes=tuple(notes))


def support_behaviors(block_id: str, *, rules: dict[str, SupportRule] | None = None,
                      upstream: Path | None = None,
                      available_ids: set[str] | None = None,
                      available_properties: set[str] | None = None,
                      allow_unverified: bool = False) -> list[list[str]]:
    """给生成器用的薄接口：返回可并进 `behaviors: list[list[str]]` 的片段。

    没有规则、被阻塞、或未获准发布时返回空列表（调用方再不会写出一段坏行为）；
    想知道理由用 {@link inspect_support} 或 {@link blocked_report}。
    """
    return inspect_support(
        block_id, rules=rules, upstream=upstream, available_ids=available_ids,
        available_properties=available_properties,
        allow_unverified=allow_unverified).entries


def required_properties(block_id: str, *, rules: dict[str, SupportRule] | None = None,
                        upstream: Path | None = None,
                        available_ids: set[str] | None = None,
                        available_properties: set[str] | None = None,
                        allow_unverified: bool = False) -> tuple[str, ...]:
    """这条支撑行为**必须**在 `state:` 段声明哪些方块状态属性。

    CE 的 `directional_attached_block` 明确要求方块声明 `facing`，不声明就直接报
    「该行为需要定义方块状态属性 facing」，整条行为失效。所以调用方必须在写 state 段
    之前把这里的名字并进 `properties`，否则会出现「行为写了、属性没写」的断链。
    """
    return inspect_support(
        block_id, rules=rules, upstream=upstream, available_ids=available_ids,
        available_properties=available_properties,
        allow_unverified=allow_unverified).required_properties


def blocked_report(rules: dict[str, SupportRule]) -> dict[str, str]:
    """所有不发布的方块 → 原因。逐条给出可复核的缺口，不写"未实现"了事。"""
    return {block_id: rule.blocked for block_id, rule in sorted(rules.items())
            if rule.blocked}


def tag_requirements(upstream: Path) -> dict[str, dict]:
    """trait 用到的 wover 标签 → 依据与缺口，逐条可复核。

    每个条目给出：标签 id、wover 侧基表条目（官方生成数据）、本模组增量文件里的条目、
    以及展开后的结果（显式 id / 原版标签）或缺口原因。这不是实现，只是把「这个标签
    到底等于哪些方块」变成一份可派工、可反驳的清单。
    """
    seen: dict[str, dict] = {}

    for _block_id, (expr, _cls) in sorted(_registered_traits(upstream).items()):
        name = re.match(r"NetherSurvival\.(\w+)\(\)", expr)
        if not name:
            continue
        body = _nether_survival_methods(upstream).get(name.group(1), "")
        for match in _WITH_TAG.finditer(body):
            arg = match.group(1)
            if not arg.startswith("CommonBlockTags."):
                continue
            const = arg.split(".", 1)[1]
            tag, blocks, tags, error = _common_tag(upstream, const)
            if tag is None or tag in seen:
                continue
            increments, missing = _read_tag_values(upstream, "wover", tag.split(":", 1)[1])
            seen[tag] = {
                "constant": f"CommonBlockTags.{const}",
                "file_members": list(increments),
                "base_tags": list(WOVER_TAG_BASES.get(tag, ())),
                "blocks": list(blocks),
                "tags": list(tags),
                "needs": error or (
                    "基表 = " + "、".join(WOVER_TAG_BASES[tag])
                    + "（" + WOVER_BASE_SOURCE_NOTE + "）；"
                      + (f"本模组增量：{missing}（即本模组没有往这个标签加成员）"
                         if missing and missing.endswith("不存在") else
                         "本模组增量已并入")),
            }
    return seen


def render_yaml(entries: list[list[str]]) -> str:
    """调试/测试用：把行为片段渲染成与 blocks.py `behavior_yaml` 相同的缩进形态。"""
    if not entries:
        return ""
    if len(entries) == 1:
        return "    behavior:\n" + "".join(f"      {line}\n" for line in entries[0])
    lines = "".join(
        f"      - {entry[0]}\n" + "".join(f"        {line}\n" for line in entry[1:])
        for entry in entries)
    return f"    behaviors:\n{lines}"


# ---------------------------------------------------------------- updateShape 破坏判据层
#
# 上游「连接断了怎么办」写在 `updateShape`（或 `destroy`），**不是** `canSurvive`：
#
#   BlockAnchorTreeVine.updateShape  → 上方既不是自己也不是装饰支撑面 → Blocks.AIR
#   BlockEyeVine.updateShape         → `direction == DOWN` 且下方既不是自己也不是眼球 → AIR
#   BlockNetherReed.updateShape      → 下方不是下界地面（且四周有岩浆）/ 不是同名段 → AIR
#   BlockWartSeed.updateShape        → `canSurvive` 失败 → AIR
#   BlockStalactite.destroy          → 上下相邻的同类比自己小 → destroyBlock(pos, true)
#
# `canSurvive` 决定「能不能放在这里」，`updateShape` 决定「支撑没了以后怎么收场」。
# CE 的 bush_block / hanging_block / sturdy_base_block 把「canSurvive 失败」映射成 setAir，
# 所以**只有**当上游的破坏判据恰好等于 canSurvive 判据时，已有支撑行为才等价覆盖了破坏语义。
# 这一层就是把这条差异显式化：逐个类读 updateShape / destroy，判出
#
#   * 方向 —— up / down / both / facing / unknown（`both` = 上下都要判，如芦苇）；
#   * 判据 —— 白名单 / 自己∪白名单 / 任何坚固面 / 递归级联 / 读不出；
#   * 结论 —— 能不能等价落成一条 CE 行为；不能就留 blocked 并写清差在哪一支。
#
# 表达不了的**绝不近似**：近似的结果要么是方块生成后立刻自毁（例如把「自己 ∪ 树叶 ∪
# 任何坚固面」写成 hanging_block 白名单，挂在树叶下的藤蔓会成批消失），要么是永远悬空。
#
# 判据方向的取值与 `DIR_*` 的区别：这里关注「上方/下方」，所以另有一个 `DIR_BOTH`；
# `facing` 仍然表示「按贴面方向判」。
_UPDATE_SHAPE = re.compile(r"\b(?:public|protected)\s+BlockState\s+updateShape\s*\(")
_DESTROY_METHOD = re.compile(r"\b(?:public|protected)\s+void\s+destroy\s*\(")

#: 判据里出现这些调用 = 「任何坚固面」那一支（BCLib / 原版的三种支撑判定）。
_SOLID_MARKERS = ("isDecorationSupport", "canSupportCenter(", "isFaceSturdy",
                  "SurvivesOnSolidTrait")
#: 支撑判定调用本身。它们的 `Direction.DOWN` 参数指的是**支撑面朝向**，不是判据看哪一边。
_SUPPORT_CALL = re.compile(
    r"(?:isDecorationSupport|canSupportCenter|isFaceSturdy|canSupportRigidBlock)"
    r"\([^()]*\)")
#: 「支撑物是自己 / 同类」。
_SELF_MARKERS = (".is(this)", "== this", "!= this")
#: 「具体方块集合」：类判定、trait 白名单、本模组的下界地面判定。
_SET_MARKERS = ("instanceof", "SurvivesOnBlockTrait", "isNetherGround(")

#: 上游判据里这两段 CE 表达不了（岩浆邻接、按「哪个邻居变了」判定）。
_LAVA_MARKER = "isLava("
_CHANGED_DIRECTION = re.compile(
    r"(?:direction|facing|neighborDirection)\s*==\s*Direction\.")

#: BCLib 基类里的破坏判据（源码不在本仓库，语义与 {@link BCLIB_BASE_CLASSES} 同源：
#: 官方 release/26.3 的 `AbstractVineBlock` 链）。值是 `(方向, 判据种类, 缺口)`。
BCLIB_BREAKDOWN_BASES: dict[str, tuple[str, str, str | None]] = {
    "BaseVineBlock": (
        DIR_UP, CRITERION_SOLID,
        "上游判据是 `up.is(this)` ∪ `up.is(BlockTags.LEAVES)` ∪ "
        "`canSupportCenter(pos.above(), DOWN)`；CE 的 hanging_block 只能列白名单，"
        "`sturdy_base_block` 只能表达「坚固面 ∪ 同类」，**整方块树叶**那一支两支都表达不了 —— "
        "而下界藤蔓的世界生成会把它们挂在树叶顶盖下，省掉这一支等于让生成出来的藤蔓立刻破掉"),
    "BaseVineBlock.Growing": (
        DIR_UP, CRITERION_SOLID,
        "同 BaseVineBlock（`Growing` 只额外加 randomTick）：判据含「任何坚固面 ∪ 整方块树叶」，"
        "CE 的支撑行为表达不了树叶那一支"),
    "BaseSimpleVineBlock": (
        DIR_UP, CRITERION_SOLID,
        "与 BaseVineBlock 同族的藤蔓基类（`AbstractVineBlock` 链，语义见同表 BaseVineBlock 条）；"
        "该基类本身不在本仓库，无法逐字复核它没有改写 canSurvive —— 但判据仍含"
        "「任何坚固面 ∪ 整方块树叶」，CE 表达不了"),
    "AbstractVineBlock": (
        DIR_UP, CRITERION_SOLID,
        "BCLib 藤蔓的最终基类：`up.is(this)` ∪ `up.is(BlockTags.LEAVES)` ∪ "
        "`canSupportCenter(pos.above(), DOWN)`，CE 表达不了任何坚固面/树叶这两支"),
}


@dataclass(frozen=True)
class BreakdownRule:
    """一个方块「连接断了怎么收场」的上游真值（未渲染成 YAML）。"""

    block_id: str
    cls: str                        # 上游方块类
    source: str                     # 判据出处：updateShape / destroy / 基类
    evidence: str                   # 文件:类.方法（或基类名）
    breakdown_direction: str        # DIR_UP / DIR_DOWN / DIR_BOTH / DIR_FACING / DIR_MIXED / DIR_UNKNOWN
    criterion: str                  # CRITERION_*
    drops_on_breakdown: bool        # 断开时该不该掉落
    drops_source: str               # DROPS_UPSTREAM / DROPS_REQUIREMENT
    whitelist_blocks: tuple[str, ...] = ()
    whitelist_tags: tuple[str, ...] = ()
    self_ok: bool = False           # 判据允许「支撑物是自己/同类」
    recursive: bool = False         # 递归级联破坏
    ce_behaviors: tuple[tuple[str, ...], ...] = ()   # 候选 CE 行为（行列表，未发布时仅作说明）
    covered_by_support_rule: bool = False           # 已有 canSurvive 支撑规则覆盖了破坏语义
    blocked: str | None = None      # 非空 = 不能等价落成 CE 行为，差在哪一支写在这里
    notes: tuple[str, ...] = ()

    @property
    def published(self) -> bool:
        return self.blocked is None and bool(self.ce_behaviors)


def _class_block_index(upstream: Path) -> dict[str, tuple[str, ...]]:
    """`方块类 → 方块 id`，来源是上游**生成数据** `block_properties.json` 的 `class` 字段。

    这一步不能按名字推：`betternether:wart_seed` 由 `WartMaterial` 的
    `AbstractSeed` 槽注册（源码里根本没有 `defineBlock("wart_seed", …)`），
    只有生成数据同时记着 id 与实现类。
    """
    path = upstream / "src/main/generated/block_properties.json"
    if not path.is_file():
        return {}
    index: dict[str, list[str]] = {}
    for record in json.loads(path.read_text(encoding="utf-8")):
        block_id = record.get("id")
        cls = record.get("class")
        if not block_id or not cls:
            continue
        index.setdefault(cls.split(".")[-1], []).append(block_id)
    return {cls: tuple(sorted(ids)) for cls, ids in index.items()}


def _subclass_ids(upstream: Path, base: str,
                  index: dict[str, tuple[str, ...]],
                  chain_limit: int = 6) -> tuple[str, ...]:
    """`BlockEyeBase` 这种基类 → 所有已注册子类的方块 id（沿 `extends` 走）。"""
    found: list[str] = []
    for cls in index:
        current, depth = cls, 0
        while current and depth < chain_limit:
            parent = _super_class(upstream, current)
            if parent is None:
                break
            simple = parent.split(".")[-1]
            if simple == base:
                found.extend(index[cls])
                break
            current, depth = simple, depth + 1
    return tuple(dict.fromkeys(found))


def _unvendored_base(upstream: Path, cls: str, chain_limit: int = 6) -> str:
    """`extends` 链上第一个不在本仓库的类名（BCLib 基类，可能带外类前缀）。"""
    current: str | None = cls
    steps = 0
    while current and steps < chain_limit:
        if "." in current:
            return current
        simple = current.split(".")[-1]
        if not _classes_in(upstream).get(simple):
            return current
        steps += 1
        parent = _super_class(upstream, simple)
        if parent is None:
            return current
        current = parent
    return current or cls


def _breakdown_direction_of(body: str) -> str:
    """从破坏判据的函数体判方向（上/下/贴面）。

    `Direction.DOWN` 在支撑判定调用里是**支撑面**参数（`isDecorationSupport(…, Direction.DOWN)`
    表示「上方那个方块的下表面」），不代表判据看下方 —— 所以先把这类调用剔掉再找方向标记。
    """
    stripped = _SUPPORT_CALL.sub("", body)
    up = "pos.above()" in body
    down = "pos.below()" in body
    changed = _CHANGED_DIRECTION.search(body)
    if changed:
        token = changed.group(0)
        if "DOWN" in token:
            down = True
        elif "UP" in token:
            up = True
    if "Direction.UP" in stripped:
        up = True
    if "Direction.DOWN" in stripped:
        down = True
    facing = ("getValue(FACING)" in body
              or "relative(direction.getOpposite())" in body
              or "relative(FACING" in body)
    if facing and (up or down):
        return DIR_MIXED
    if facing:
        return DIR_FACING
    if up and down:
        return DIR_BOTH
    if up:
        return DIR_UP
    if down:
        return DIR_DOWN
    return DIR_UNKNOWN


def _merge_direction(*directions: str) -> str:
    """合并「本类判据」与「基类判据」的方向：上下各一支 = both，其余混方向 = mixed。"""
    values = {direction for direction in directions if direction and direction != DIR_UNKNOWN}
    if not values:
        return DIR_UNKNOWN
    if len(values) == 1:
        return values.pop()
    if DIR_MIXED in values or DIR_FACING in values:
        return DIR_MIXED
    return DIR_BOTH          # 只有上下两支 = both


def _criterion_of(body: str) -> str:
    """把一段判据代码归到 {@link CRITERION_*}（只认代码里真的出现的调用）。"""
    if any(marker in body for marker in _SOLID_MARKERS):
        return CRITERION_SOLID
    self_ok = any(marker in body for marker in _SELF_MARKERS)
    block_set = any(marker in body for marker in _SET_MARKERS)
    if self_ok and block_set:
        return CRITERION_SELF_OR_WHITELIST
    if self_ok:
        return CRITERION_SELF_OR_WHITELIST
    if block_set:
        return CRITERION_WHITELIST
    return CRITERION_UNKNOWN


def _has_criterion_markers(body: str) -> bool:
    return any(marker in body for marker in
               _SOLID_MARKERS + _SELF_MARKERS + _SET_MARKERS)


def _material_registered_classes(upstream: Path,
                                 index: dict[str, tuple[str, ...]]) -> dict[str, str]:
    """`blocks/complex/*Material.java` 里出现的方块类 → 材料文件名。

    槽位注册（`AbstractSeed.create(BlockWartSeed::new, …)`、`WoodSlots.STEM`…）不走
    `registry/block/*.java` 的 `defineBlock("id", …)`，所以只扫注册处的 trait 索引会把
    它们整批漏掉（`wart_seed`、`stalagnate_*` 就是这样）。这里以**类名在材料源码里出现**
    为判据，id 仍由上游生成数据的 `class` 字段给出 —— 不从槽位名猜 id。
    """
    key = str(_blocks_dir(upstream))
    cached = _MATERIAL_CACHE.get(key)
    if cached is not None:
        return cached
    materials: dict[str, str] = {}
    directory = _blocks_dir(upstream) / "complex"
    if not directory.is_dir():
        _MATERIAL_CACHE[key] = materials
        return materials
    sources = {path.name: _block_source(path)
               for path in sorted(directory.glob("*Material.java"))}
    for cls in index:
        pattern = re.compile(r"\b" + re.escape(cls) + r"\b")
        for name, source in sources.items():
            if pattern.search(source):
                materials.setdefault(cls, name)
                break
    _MATERIAL_CACHE[key] = materials
    return materials


def _material_trait_whitelist(upstream: Path, cls: str,
                              material: str) -> dict:
    """材料槽位注册处那个类旁边的 `NetherSurvival.X()` → 白名单。

    `WartMaterial` 里 `BlockWartSeed::new` 的支撑 trait 写在同一个槽位定义里
    （`TraitLists.and(NetherSurvival.soulSand(), …)`）。窗口取「类引用之后 800 字符」，
    只为把**同一槽位**的 trait 读出来；读不到就返回空（宁可少写，不按名字补）。
    """
    path = _blocks_dir(upstream) / "complex" / material
    if not path.is_file():
        return {"blocks": (), "tags": (), "traits": (), "error": None}
    text = _block_source(path)
    occurrences = list(re.finditer(r"\b" + re.escape(cls) + r"\b", text))
    if not occurrences:
        return {"blocks": (), "tags": (), "traits": (), "error": None}
    # 取**最后**一次出现：文件开头是 import，槽位定义在下面。
    window = text[occurrences[-1].end():occurrences[-1].end() + 800]
    traits = re.findall(r"NetherSurvival\.(\w+)\(\)", window)
    if not traits:
        return {"blocks": (), "tags": (), "traits": (), "error": None}
    methods = _nether_survival_methods(upstream)
    blocks: list[str] = []
    tags: list[str] = []
    errors: list[str] = []
    for name in dict.fromkeys(traits):
        expanded = _expand_trait(upstream, f"NetherSurvival.{name}()", methods)
        blocks.extend(expanded["blocks"])
        tags.extend(expanded["tags"])
        if expanded["error"]:
            errors.append(expanded["error"])
    return {"blocks": tuple(dict.fromkeys(blocks)),
            "tags": tuple(dict.fromkeys(tags)),
            "traits": tuple(dict.fromkeys(traits)),
            "error": "；".join(errors) if errors else None}


def _can_survive_criterion(upstream: Path, cls: str) -> dict | None:
    """「放置判据」的方向与种类：本仓库有 canSurvive 就读它，链尾是已知基类就用表里的语义。"""
    body, evidence, _ = _chain_body(upstream, cls, _CAN_SURVIVE, "canSurvive")
    if body is not None:
        criterion = _criterion_of(body)
        if criterion == CRITERION_UNKNOWN:
            return None
        return {"direction": _breakdown_direction_of(body), "criterion": criterion,
                "body": body, "evidence": evidence, "gap": None,
                "notes": ()}
    base = _unvendored_base(upstream, cls)
    known = BCLIB_BREAKDOWN_BASES.get(base)
    if known is None:
        return None
    return {"direction": known[0], "criterion": known[1], "body": "",
            "evidence": f"{base}（未 vendored）", "gap": known[2],
            "notes": (f"放置判据在未 vendored 的 BCLib 基类 {base} 里",)}


def _analyse_breakdown(upstream: Path, cls: str,
                       index: dict[str, tuple[str, ...]],
                       depth: int = 0) -> dict | None:
    """读一个方块类的破坏判据；**没有**破坏判据时返回 None（挖中段上面照旧悬空）。

    判据可能由**多段 AND 起来**，每一段都要算进去，否则方向会少一半：
      * 本类 `updateShape` 自己的比对（`eye_vine` 的「下方是自己或眼球」）；
      * `updateShape` 里调用的 `canSurvive`（`wart_seed`、`whispering_gourd_vine`）；
      * `super.updateShape(...)` 的基类实现（`eye_vine` 的上方一支）；
      * `destroy()` 的递归级联（石笋）。

    `depth` 只用于 `super.updateShape(...)` 的内层递归，避免无限下降。
    """
    upd_body, upd_evidence, _ = _chain_body(upstream, cls, _UPDATE_SHAPE, "updateShape")
    destroy_body, destroy_evidence, _ = _chain_body(upstream, cls, _DESTROY_METHOD, "destroy")
    recursive = bool(destroy_body and "destroyBlock(" in destroy_body)

    if upd_body is not None and "Blocks.AIR" not in upd_body and not recursive:
        # `updateShape` 也用来刷外观（连接的砂岩、蘑菇块改形状…）。只有真的把方块换成
        # 空气的才是「拆除判据」，否则这一层会把一堆连体方块报成「该掉落」。
        return None

    if upd_body is None and not recursive:
        base = _unvendored_base(upstream, cls)
        known = BCLIB_BREAKDOWN_BASES.get(base)
        if known is None:
            return None
        direction, criterion, gap = known
        return {"source": "updateShape（BCLib 基类）",
                "evidence": f"{base}（未 vendored）", "direction": direction,
                "criterion": criterion, "body": "", "gap": gap, "self_ok": False,
                "blocks": (), "tags": (), "recursive": False,
                "notes": (f"判据在未 vendored 的 BCLib 基类 {base} 里",)}

    notes: list[str] = []
    gaps: list[str] = []
    pieces: list[dict] = []
    source = "updateShape" if upd_body is not None else "destroy"
    evidence = upd_evidence if upd_body is not None else destroy_evidence

    local = upd_body if (upd_body and _has_criterion_markers(upd_body)) else ""
    if local:
        pieces.append({"direction": _breakdown_direction_of(local),
                       "criterion": _criterion_of(local), "body": local,
                       "gap": None, "evidence": "", "notes": ()})

    if "super.updateShape(" in (upd_body or "") and depth < 2:
        parent = _super_class(upstream, cls)
        inherited = (_analyse_breakdown(upstream, parent, index, depth + 1)
                     if parent else None)
        if inherited:
            pieces.append(inherited)
            notes.append(f"另一半判据来自基类 {parent}（{inherited['source']}）")

    if "canSurvive(" in (upd_body or ""):
        can = _can_survive_criterion(upstream, cls)
        if can is None:
            gaps.append("updateShape 套用了 `canSurvive`，但那段判据读不出来 —— "
                        "缺一半判据就不能发布（会按另一半错误地回收方块）")
        else:
            pieces.append(can)
            notes.append("updateShape 里套用了 canSurvive（两段判据必须同时成立）")

    if not pieces:
        can = _can_survive_criterion(upstream, cls)
        if can is None:
            if not recursive:
                return None
            notes.append("破坏由 `destroy()` 递归 `destroyBlock(..., true)` 完成（会掉落），"
                         "不是 updateShape")
        else:
            pieces.append(can)
            if upd_body is not None:
                source = "updateShape → canSurvive"
                evidence = f"{upd_evidence} → {can['evidence']}"

    if recursive and not any(piece["criterion"] != CRITERION_UNKNOWN
                             for piece in pieces):
        # 石笋：`canSurvive` 只是 `canPlace(UP) || canPlace(DOWN)`，真正「同类链」的判据在
        # `destroy()` 里（`instanceof BlockStalactite` + SIZE 比较 + destroyBlock(...,true)）。
        pieces.append({"direction": _merge_direction(
                           _breakdown_direction_of(upd_body or destroy_body),
                           _breakdown_direction_of(destroy_body)),
                       "criterion": CRITERION_RECURSIVE, "body": destroy_body,
                       "gap": None, "evidence": "", "notes": ()})
        notes.append("判据由 `destroy()` 的同类链决定：上下相邻同类比自己小就连带破坏")

    criterion = CRITERION_UNKNOWN
    for piece in pieces:
        if piece["criterion"] == CRITERION_SOLID:
            criterion = CRITERION_SOLID
            break
        if criterion == CRITERION_UNKNOWN:
            criterion = piece["criterion"]
    if criterion == CRITERION_UNKNOWN:
        return None

    direction = _merge_direction(*[piece["direction"] for piece in pieces])
    body = "\n".join(piece["body"] for piece in pieces if piece["body"])
    self_ok = any(marker in body for marker in _SELF_MARKERS)
    for piece in pieces:
        if piece.get("gap"):
            gaps.append(piece["gap"])
        notes.extend(piece.get("notes") or ())
        if piece["criterion"] == CRITERION_UNKNOWN:
            notes.append(f"其中一段判据读不出种类（方向 {piece['direction']}）")

    blocks: list[str] = []
    tags: list[str] = []
    for name in re.findall(r"instanceof\s+([\w.]+)", body):
        simple = name.split(".")[-1]
        blocks.extend(index.get(simple, ()))
        blocks.extend(_subclass_ids(upstream, simple, index))
    if "SurvivesOnBlockTrait" in body:
        materials = _material_registered_classes(upstream, index)
        material = materials.get(cls)
        if material:
            resolved = _material_trait_whitelist(upstream, cls, material)
            blocks.extend(resolved["blocks"])
            tags.extend(resolved["tags"])
            if resolved["traits"]:
                notes.append("白名单来自材料槽位注册处的 "
                             + "、".join(f"NetherSurvival.{name}()"
                                        for name in resolved["traits"]))
            if resolved["error"]:
                notes.append(f"材料槽位白名单未完全展开：{resolved['error']}")
        else:
            notes.append("`SurvivesOnBlockTrait` 的白名单在材料槽位注册处，本层没展开")

    if _LAVA_MARKER in body:
        gaps.append("判据里还夹着「支撑方块四周必须有岩浆」（`BlocksHelper.isLava`）—— "
                    "CE 的支撑行为只认支撑物本身，看不到支撑物旁边的东西")
    changed = _CHANGED_DIRECTION.search(upd_body or "")
    if changed:
        gaps.append("上游只在**某一个方向**的邻居发生变化时才回收"
                    f"（`{changed.group(0).strip()} …`）；CE 的支撑行为在每次邻居变化都重算 "
                    "canSurvive，刚放下、下方本来就是空气的段会立刻被回收")

    return {"source": source, "evidence": evidence, "direction": direction,
            "criterion": criterion, "body": body, "gap": "；".join(gaps) or None,
            "self_ok": self_ok,
            "blocks": tuple(dict.fromkeys(blocks)),
            "tags": tuple(dict.fromkeys(tags)),
            "recursive": recursive, "notes": tuple(dict.fromkeys(notes))}


def _proposal(direction: str, criterion: str, self_ok: bool,
              blocks: tuple[str, ...],
              tags: tuple[str, ...]) -> tuple[tuple[str, ...], ...]:
    """把判据渲染成**候选** CE 行为（结构与 {@link inspect_support} 完全一致）。

    候选 ≠ 发布：能不能发布由 {@link _verdict} 判；判据里只要有一支 CE 表达不了就不发布，
    候选只留在报告里，等裁决后改一行即可启用。
    """
    if not blocks and not tags and not self_ok:
        return ()
    if criterion == CRITERION_SOLID:
        if direction not in (DIR_UP, DIR_DOWN):
            return ()
        lines = ["type: sturdy_base_block", f"direction: {direction}",
                 "support_types:", "  - full",
                 f"stackable: {'true' if self_ok else 'false'}"]
        if self_ok:
            lines.extend(["max_height: 255"])
        lines.append("delay: 0")
        return (tuple(lines),)
    if direction == DIR_UP:
        lines = ["type: hanging_block", "blacklist: false",
                 f"stackable: {'true' if self_ok else 'false'}", "delay: 0"]
        lines.extend(_lines(blocks, tags, "above_blocks", "above_block_tags"))
        return (tuple(lines),)
    if direction == DIR_DOWN:
        lines = ["type: bush_block", "blacklist: false",
                 f"stackable: {'true' if self_ok else 'false'}"]
        if self_ok:
            lines.extend(["max_height: 255"])
        lines.append("delay: 0")
        lines.extend(_lines(blocks, tags, "bottom_blocks", "bottom_block_tags"))
        return (tuple(lines),)
    return ()


def _verdict(analysis: dict, direction: str, criterion: str) -> str | None:
    """能不能等价落成一条 CE 行为；不能就返回「差在哪一支」。"""
    if analysis["recursive"]:
        return ("CE 没有「同类链递归破坏」这种行为：上游 `BlockStalactite.destroy()` 会递归 "
                "`destroyBlock(pos, true)`（整串破坏并掉落），YAML 层表达不了 —— "
                "级联与掉落只能落在 Java 侧")
    parts: list[str] = []
    if criterion == CRITERION_SOLID:
        leaves = ("`isDecorationSupport` 自带「坚固面 ∪ 整方块树叶」两支"
                  if "isDecorationSupport" in analysis["body"] else
                  "CE 的 `support_types: [full]` 就是原版 `isFaceSturdy(FULL)`，"
                  "整方块树叶那一支表达不了")
        parts.append("上游判据含「任何坚固面」：CE 的 hanging_block / bush_block 只能列白名单，"
                     "sturdy_base_block 只能表达「坚固面 ∪ 同类」，" + leaves)
    if direction == DIR_BOTH:
        parts.append("破坏判据同时看上方与下方，CE 的单条支撑行为只判一个方向")
    elif direction in (DIR_MIXED, DIR_FACING):
        parts.append(f"破坏判据按贴面方向判（{direction}），CE 只有水平 facing 的贴面行为，"
                     "朝上/朝下那两种朝向表达不了")
    elif direction == DIR_UNKNOWN:
        parts.append("读不出破坏判据的方向")
    if analysis.get("gap"):
        parts.append(analysis["gap"])
    return "；".join(dict.fromkeys(parts)) or None


def breakdown_candidates(upstream: Path) -> dict[str, str]:
    """`方块 id → 实现类`：上游**确实**会在连接断掉时回收自己的方块。

    这份名单只由上游源码 + 上游生成数据决定（不看规则表），所以门禁可以拿它和
    {@link load_breakdown_rules} 交叉核对：有候选却没记录 = 有人把记录删了。
    """
    upstream = Path(upstream)
    index = _class_block_index(upstream)
    if not index:
        return {}
    found: dict[str, str] = {}
    for cls, ids in index.items():
        if _analyse_breakdown(upstream, cls, index) is None:
            continue
        for block_id in ids:
            found[block_id] = cls
    return dict(sorted(found.items()))


def load_breakdown_rules(upstream: Path) -> dict[str, BreakdownRule]:
    """所有上游「连接断了会回收」的方块 → {@link BreakdownRule}（按不带命名空间的 id 索引）。

    只读；同一棵树多次调用结果完全一致（解析结果有缓存，无随机、无写盘）。
    """
    upstream = Path(upstream)
    index = _class_block_index(upstream)
    if not index:
        return {}
    support_rules = load_support_rules(upstream)
    had_material = bool(_material_registered_classes(upstream, index))
    rules: dict[str, BreakdownRule] = {}
    for block_id, cls in breakdown_candidates(upstream).items():
        analysis = _analyse_breakdown(upstream, cls, index)
        if analysis is None:
            continue
        name = block_id.split(":", 1)[-1]
        notes = list(analysis["notes"])
        if had_material:
            material = _material_registered_classes(upstream, index).get(cls)
            if material:
                notes.append(f"注册在材料槽位 {material}（不走 defineBlock，"
                             "所以不在注册处 trait 索引里）")
        support_rule = support_rules.get(name)
        covered = bool(support_rule and support_rule.verified)
        if covered:
            notes.append("已有可证实的支撑规则：CE 的支撑行为在邻居变化时重算 canSurvive，"
                         "破坏语义由它覆盖，本层不再重复声明")
        ce = _proposal(analysis["direction"], analysis["criterion"], analysis["self_ok"],
                       analysis["blocks"], analysis["tags"])
        blocked = None if covered else _verdict(
            analysis, analysis["direction"], analysis["criterion"])
        if blocked is None and not covered:
            whitelisted = any(line.startswith(("bottom_blocks:", "bottom_block_tags:",
                                               "above_blocks:", "above_block_tags:"))
                              for entry in ce for line in entry)
            if not ce or not whitelisted:
                blocked = ("白名单模式却没有任何可写的白名单成员，发布后这个方块永远不会放行 —— "
                           "缺成员证据（或判据的另一半没读出来）")
        rules[name] = BreakdownRule(
            block_id=block_id,
            cls=cls,
            source=analysis["source"],
            evidence=analysis["evidence"],
            breakdown_direction=analysis["direction"],
            criterion=analysis["criterion"],
            drops_on_breakdown=True,
            drops_source=(DROPS_UPSTREAM if analysis["recursive"] else DROPS_REQUIREMENT),
            whitelist_blocks=analysis["blocks"],
            whitelist_tags=analysis["tags"],
            self_ok=analysis["self_ok"],
            recursive=analysis["recursive"],
            ce_behaviors=ce,
            covered_by_support_rule=covered,
            blocked=blocked,
            notes=tuple(dict.fromkeys(notes)),
        )
    return rules


def breakdown_behaviors(block_id: str, *, rules: dict[str, BreakdownRule] | None = None,
                        upstream: Path | None = None) -> list[list[str]]:
    """可发布的破坏行为（当前**全部**为空：这批判据都差一支，见 {@link breakdown_report}）。

    接口先留着：一旦某条判据被确认可以近似（或上游改了），这里自然返回候选行为。
    """
    if rules is None:
        if upstream is None:
            raise ValueError("breakdown_behaviors 需要 rules 或 upstream 之一")
        rules = load_breakdown_rules(upstream)
    rule = rules.get(block_id.split(":", 1)[-1])
    if rule is None or rule.blocked:
        return []
    return [list(entry) for entry in rule.ce_behaviors]


def breakdown_report(rules: dict[str, BreakdownRule]) -> dict[str, dict]:
    """断开清单：哪些方块、断哪个方向、该掉落还是消失、为什么不能等价落成 CE 行为。

    这份东西是给 Java 侧消费的（CE 的支撑行为一律 setAir，掉落必须由插件侧补），
    也是给人复核的（每条都能指回上游文件里的一段判据）。
    """
    report: dict[str, dict] = {}
    for name, rule in sorted(rules.items()):
        report[rule.block_id] = {
            "class": rule.cls,
            "criteria_source": f"{rule.source} @ {rule.evidence}",
            "breakdown_direction": rule.breakdown_direction,
            "criterion": rule.criterion,
            "drops_on_breakdown": rule.drops_on_breakdown,
            "drops_source": rule.drops_source,
            "self_stacking": rule.self_ok,
            "recursive_cascade": rule.recursive,
            "whitelist": {"blocks": list(rule.whitelist_blocks),
                          "tags": list(rule.whitelist_tags)},
            "ce_behavior": {"published": rule.published,
                            "lines": [list(entry) for entry in rule.ce_behaviors]},
            "covered_by_support_rule": rule.covered_by_support_rule,
            "blocked": rule.blocked,
            "notes": list(rule.notes),
        }
    return report


def breakdown_manifest(upstream: Path) -> dict:
    """机器可读的断开清单（落成 `reports/plant-breakdown.json`）。"""
    upstream = Path(upstream)
    rules = load_breakdown_rules(upstream)
    report = breakdown_report(rules)
    drops = [block_id for block_id, entry in report.items() if entry["drops_on_breakdown"]]
    return {
        "source": "upstream/BetterNether 的 updateShape / destroy 判据（见 bnlib.support）",
        "convention": {
            "breakdown_direction": "up=依赖上方 / down=依赖下方 / both=上下都判 / "
                                   "facing=按贴面方向 / mixed=两种方向混用",
            "drops_source": {
                DROPS_UPSTREAM: "上游自己就掉落（destroyBlock(pos, true)）",
                DROPS_REQUIREMENT: "上游是静默消失（Blocks.AIR）；按用户要求改为掉落，"
                                   "CE 做不到，需 Java 侧补",
            },
        },
        "summary": {
            "total": len(report),
            "published_ce_behavior": sum(1 for entry in report.values()
                                         if entry["ce_behavior"]["published"]),
            "blocked": sum(1 for entry in report.values() if entry["blocked"]),
            "drops": len(drops),
        },
        "blocks": report,
    }


def generate(upstream: Path, ce_root: Path,
             selected: "tuple[str, ...] | list[str]" = ()) -> dict:
    """生成器钩子：把断开清单落到 `<out>/reports/plant-breakdown.json`。

    「谁该在断开时掉落」是 Java 侧的输入，不是资源包能表达的东西 —— 所以它必须是一份
    **产物里的文件**，而不是只活在 Python 里的表；`reports/**` 已被构建排除出 jar，
    不会被当成运行期配置。
    """
    upstream = Path(upstream)
    manifest = breakdown_manifest(upstream)
    # 只保留**本包真的发布**的方块。
    # 未发布的方块（4 种钟乳石、smoker、bone_mushroom 等）放进清单会造成双重错误：
    #   1. 运行期装载器拿这份清单去核对 CE 包，报「包缺少 N 个世界生成方块 id」；
    #   2. Java 的 `BlockBreakdownTable` 照抄清单，于是它也在守一批根本不存在的方块。
    # 上游判据本身仍然完整保留在 `load_breakdown_rules` 里（`material_registration_scan`
    # 与门禁用它做覆盖核对），这里过滤的只是「交给运行期和产物」的那一份。
    published = set(selected)
    if published:
        manifest["blocks"] = {
            block_id: entry for block_id, entry in manifest["blocks"].items()
            if block_id in published
        }
        drops = [block_id for block_id, entry in manifest["blocks"].items()
                 if entry["drops_on_breakdown"]]
        manifest["summary"] = {
            "total": len(manifest["blocks"]),
            "published_ce_behavior": sum(1 for entry in manifest["blocks"].values()
                                         if entry["ce_behavior"]["published"]),
            "blocked": sum(1 for entry in manifest["blocks"].values() if entry["blocked"]),
            "drops": len(drops),
        }
    reports = Path(ce_root).parents[1] / "reports"
    target = reports / "plant-breakdown.json"
    reports.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")
    blocked = [block_id for block_id, entry in manifest["blocks"].items() if entry["blocked"]]
    warnings = [
        f"{block_id}：断开方向 {entry['breakdown_direction']}，CE 表达不了 —— {entry['blocked']}"
        for block_id, entry in manifest["blocks"].items()
        if entry["blocked"]
    ]
    return {
        "files": 1,
        "written": ["reports/plant-breakdown.json"],
        "warnings": warnings,
        "unsupported": blocked,
        "blocks": len(manifest["blocks"]),
        "drops": manifest["summary"]["drops"],
    }

def material_registration_scan(upstream: Path) -> dict:
    """`blocks/complex/*Material.java` 的槽位注册扫描结果。

    槽位注册的方块（`wart_seed`、`stalagnate_*`）不会出现在 `registry/block/*.java` 的
    `defineBlock("id", …)` 里，所以「只扫注册处」的索引会把它们整批漏掉。这里给出
    `unindexed`：既不在注册处 trait 索引里、也不在破坏判据表里的类 —— 它们的支撑/破坏
    语义当前**没有任何来源**，必须显式报出来，而不是当成「上游没有规则」。
    """
    upstream = Path(upstream)
    index = _class_block_index(upstream)
    classes = _material_registered_classes(upstream, index)
    trait_classes = {cls.split(".")[-1]
                     for _block_id, (_expr, cls) in _registered_traits(upstream).items()
                     if cls}
    breakdown_classes = set(load_breakdown_rules(upstream).values())
    breakdown_classes = {rule.cls for rule in breakdown_classes}
    unindexed = sorted(cls for cls in classes
                       if cls not in trait_classes and cls not in breakdown_classes)
    return {
        "scanned": len(list((_blocks_dir(upstream) / "complex").glob("*Material.java"))),
        "classes": dict(sorted(classes.items())),
        "ids": {cls: list(index.get(cls, ())) for cls in sorted(classes)},
        "unindexed": unindexed,
    }

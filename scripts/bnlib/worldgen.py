#!/usr/bin/env python3
"""把上游 worldgen JSON 编译成插件运行期可直接解释的植被放置数据。

## 为什么需要这一步

上游的植被不是「每群系一张方块清单 + 每区块一个总预算」，而是 223 条
`placed_feature` 引用（83 个不同 id）挂在每个群系 `features[9]`（vegetal_decoration）上，
每条自带 `tries` / `x_spread` / `every_layer` / `rarity` 等修饰符。手写清单只能近似
「放什么」，永远表达不了「放多少、放多稀、挂在天花板还是长在地上」。

但这两类 JSON 不能原样进数据包：`wover:*` 是 wover 库的特征类型，原版注册表不认识，
数据包一加载就会在注册表解析阶段中止。所以它们进 jar 的 `worldgen-upstream/`，
由插件在启动期用 Gson 读进来、编译成自己的放置管线。

## 只复制**解释得动**的特征

分类只有一条规则：把 `placed_feature`（含 `random_patch` 的内联放置特征、`overlay` 的子特征）
连同 `feature` 一起递归看一遍，

  * 修饰符必须是 `SUPPORTED_PLACEMENT` 里的；
  * 方块谓词必须是 `SUPPORTED_PREDICATE` 里的，且 `matching_block_tag` 的 tag 必须能解析；
  * 配置特征类型必须是 `SUPPORTED_FEATURE` 里的。

任何一条不满足，整条 placed feature 就被跳过并记进 `manifest.json` 的 `skipped`
（原因逐条列出）。**不做近似**：树（`betternether:*_tree`）、`wart_cap`、`lucis`、
`simple_lily`、`multiface_growth`、`wover:stencil/noise_filter/extend/is_next_to` 这些
一律跳过 —— 它们要么由 TreePopulator / TerrainFeaturePopulator 用 Java 复刻，
要么语义无法从数据反推。

## 方块标签（`wover:surfaces/*`）

上游生成的 `data/wover/tags/block/surfaces/*.json` 只是**增量**文件（`replace` 未置位，
原版语义是并入），基表在 wover 库里，本仓库没有。所以这里按两档处理：

  * `wover:surfaces/nether/terrain`（以及 `wover:surfaces/terrain`）—— 交给 Java 侧的
    「任意非空气且非基岩的实心方块」近似（见 `WovernTags.SURFACE_APPROXIMATION`）。
    理由：这类 tag 的原意是「能站的地面」，用具体方块集合去近似只会在换了地形方块之后
    静默失效。
  * 其余（`sculk_like` / `soul_ground` / `mycelium`）—— 名窄且与上游注释一致（「SCULK_LIKE
    这个 tag 同时装着两种幽匿石和熔融变体」），所以取「上游增量文件里的 id ∪ 同名原版对方块」。
    基表逐字转录在 `WOVER_TAGS_BASE` 里（出处见其注释），展开时再并上 BetterNether 的增量与 `VANILLA_TAGS`。

## 树模板

`wover:template` 引用 `data/betternether/structure/trees/*.nbt`（29 个 gzip 结构文件）。
Java 侧不需要一个 NBT 读取器：这里在构建期把每个模板压成
`{"palette":[{"id","properties"}...],"blocks":[[x,y,z,state],...],"size":[x,y,z]}`，
运行期只读 JSON。实体（`entities`）不转换 —— 上游这些模板里的实体对服务端生成没有意义。
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

from bnlib import nbt
from bnlib.publication import filter_properties, is_removed_block, replacement_id

# ---------------------------------------------------------------------------
# 支持范围（分类的唯一真值来源；blocks.py 与测试都从这里读）
# ---------------------------------------------------------------------------

#: 能忠实实现的放置修饰符。
SUPPORTED_PLACEMENT: frozenset[str] = frozenset({
    "minecraft:height_range",
    "minecraft:count",
    "minecraft:count_on_every_layer",
    "minecraft:in_square",
    # Java 侧已实现（PlacementModifier.Heightmap，只支持 MOTION_BLOCKING；
    # 其它类型由装载器拒绝）。原版蘑菇补丁与春泉都用它。
    "minecraft:heightmap",
    "minecraft:biome",
    "minecraft:rarity_filter",
    "minecraft:block_predicate_filter",
    "minecraft:offset",
    "wover:every_layer",
    "wover:is",
    "wover:solid_in_dir",
    "wover:is_next_to",
    "wover:offset",
    "wover:noise_filter",  # 语义已从 wover 源码核实，见 PlacementModifier.NoiseFilter
    "wover:all",           # 16x16 网格 emit，见 PlacementModifier.EmitGrid
})

#: 能忠实实现的方块谓词。
SUPPORTED_PREDICATE: frozenset[str] = frozenset({
    "minecraft:matching_block_tag",
    "minecraft:matching_blocks",
    "minecraft:matching_fluids",
    "minecraft:solid",
    "minecraft:replaceable",
    "minecraft:all_of",
    "minecraft:any_of",
    "minecraft:not",
    "minecraft:would_survive",
    "minecraft:true",
})

#: 能忠实实现的配置特征类型。
SUPPORTED_FEATURE: frozenset[str] = frozenset({
    "wover:random_patch",
    "wover:pillar",
    "wover:place_block",
    "wover:template",
    "minecraft:simple_block",
    "minecraft:block_column",
    "minecraft:overlay",
    "minecraft:random_selector",
    # multiface_growth（sculk_vein 的上下面/侧面三态）：由地形侧 owner 实现，
    # 这里放开后其 placed_feature 才不会被分类层挡掉。
    "minecraft:multiface_growth",
    # twisting_vines：逐行移植自上游 TwistedVinesFeature（写原版 twisting_vines/_plant）
    "betternether:twisting_vines",
    # spring_feature：按 SpringFeature#place 反汇编实现（above/below 在 valid_blocks + 空气 + 邻居<4）
    "minecraft:spring_feature",
    # 算法型特征（JSON 里只有 {"type": ...}，无配置字段）。形状由 Java 侧
    # dev.mumu.betternether.worldgen.UpstreamVegetationFeatures 逐行移植上游 feature 类，
    # 经 Feature.Custom 适配进来。之所以不能靠既有类型拼：它们的随机数个数/顺序是形状的一部分。
    "betternether:wart_cap",
    "betternether:rubeus_bush",
    "betternether:sakura_bush",
    "betternether:wart_bush",
    "betternether:willow_bush",
    "betternether:lucis",
    "betternether:soul_lily",
    "betternether:jellyfish_mushroom",
    # 简化树（算法型，形状由 UpstreamSimpleTreeFeatures 逐行移植；JSON 里是**内联**配置特征，
    # 带 distance/natural 字段）。调度（count/every_layer/height_range）仍由上游 placed_feature 决定。
    "betternether:mushroom_fir",
    "betternether:wart_tree",
    "betternether:willow_tree",
    # old_willow_tree：由 UpstreamSimpleTreeFeatures 实现（WriteZone#fitRadius /
    # decorationBounds / 双层壳 crown / wallPlants 一套几何）。其 JSON 带 distance(13) 与
    # natural(false→airOnly)，按上游原样传；树冠写区裁剪由 WorldView.inRegion 承担，
    # 调度仍由上游 placed_feature 决定（不要在调度层再叠高度门槛）。
    "betternether:old_willow_tree",
    "betternether:big_brown_mushroom",
    # 复杂树（算法型，形状由 UpstreamComplexTreeFeatures 移植；带 distance/natural/
    # bleached_chance 字段）。注意它们的 placed_feature 还带 wover:noise_filter，
    # 只有该修饰符实现之后这些特征才会真正转为 supported。
    "betternether:gloomwood_tree",
    "betternether:rubeus_tree",
    "betternether:anchor_tree",
    "betternether:anchor_tree_branch",
    "betternether:anchor_tree_root",
    "betternether:sakura_tree",
})

#: wover 的地面 tag **官方基表**。
#:
#: 逐字转录自 WorldWeaver `release/26.3` 的
#: `wover-tag-api/src/main/generated/data/wover/tags/block/<path>.json`
#: （注意是 `src/main/**generated**/`，不是 `resources/` —— 我先前按 resources 探测到 404，
#: 据此得出「仓库里没有基表」是错的）。BetterNether 自己的
#: `data/wover/tags/block/...` 只是**增量**，构建期会把两边合并。
#:
#: `{"id": "#x", "required": false}` 形式的条目同样是 tag 引用，展开时一并递归。
WOVER_TAGS_BASE: dict[str, tuple[str, ...]] = {
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
    "wover:surfaces/sculk_like": ("minecraft:sculk",),
    "wover:surfaces/soul_ground": ("minecraft:soul_sand", "minecraft:soul_soil"),
    "wover:surfaces/mycelium": ("minecraft:mycelium", "#wover:surfaces/nether/mycelium"),
    "wover:surfaces/nether/netherrack": (
        "minecraft:crimson_nylium",
        "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:netherrack",
        "minecraft:warped_nylium",
    ),
    # `surfaces/nether/stones` = `#minecraft:base_stone_nether`（官方文件内容仅此一条）
    "wover:surfaces/nether/stones": ("#minecraft:base_stone_nether",),
    "wover:surfaces/nether/mycelium": (),
    "wover:ores/nether": ("minecraft:nether_gold_ore", "minecraft:nether_quartz_ore"),
    # 下界不会用到（`required: false`），留空并在展开时安全跳过。
    "wover:surfaces/end/stones": (),
}

#: 原版标签：官方固定内容，不是猜测。
VANILLA_TAGS: dict[str, tuple[str, ...]] = {
    "minecraft:nylium": ("minecraft:crimson_nylium", "minecraft:warped_nylium"),
    "minecraft:base_stone_overworld": (
        "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
        "minecraft:tuff", "minecraft:deepslate",
    ),
    "minecraft:base_stone_nether": (
        "minecraft:netherrack", "minecraft:basalt", "minecraft:blackstone",
    ),
    "minecraft:dripstone_replaceable_blocks": (
        "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
        "minecraft:tuff", "minecraft:deepslate", "minecraft:dripstone_block",
    ),
}

#: 历史遗留符号，**必须保持为空**。
#:
#: 它曾把 wover 地面 tag 标成「运行期按实心近似」。官方基表（WorldWeaver release/26.3 的
#: `wover-tag-api/src/main/generated/data/wover/tags/block/...`）取到之后，所有 surfaces/*
#: 都由 `_expand_tag` 递归展开成真实 id 表，这个集合再被填充就会把 tag 强制解析成空列表、
#: 退回近似路径。保留符号是为了让两处旧循环不做任何事 —— 那两处循环本身也应在下次清理时删掉。
APPROXIMATED_TAGS: frozenset[str] = frozenset()

#: 输出目录（jar 内路径）。**不能**放在 `datapack/` 下：原版会尝试加载这些
#: `wover:*` 特征并在注册表解析阶段中止。
OUTPUT_DIR = "worldgen-upstream"

#: 清单里 `owner` 字段的唯一取值：这条 placed feature 属于**地形类别**
#: （形状由 `UpstreamTerrainFeatures` 编译、文件由 `terrain_worldgen.write()` 写进同一个
#: `worldgen-upstream/` 根）。它**只是类别标注**，不是「另一个执行器」—— 运行期只有一个
#: 装饰计划，`terrain.json` 只用于导出与闭合核对。取值刻意只有一个。
TERRAIN_OWNER = "terrain"

#: 群系 `features` 数组里植被所在的步序号（vegetal_decoration，10 步中的最后一步）。
VEGETAL_DECORATION_STEP = 9

WORLDGEN = "src/main/generated/data/betternether/worldgen"
TREE_TEMPLATES = "src/main/resources/data/betternether/structure/trees"
WOVER_TAGS = "src/main/generated/data/wover/tags/block"
TEMPLATE_PREFIX = "trees/"

# NBT 标签号（只列模板转换真正用到的几个）。注意 TAG_INT_ARRAY 是 11，不是 3。
TAG_INT = 3
TAG_LIST = 9
TAG_INT_ARRAY = 11


def template_path(upstream: Path, location: str) -> Path:
    """`betternether:trees/x` -> `.../structure/trees/x.nbt`。"""
    name = location.split(":", 1)[1]
    if not name.startswith(TEMPLATE_PREFIX):
        raise SystemExit(f"模板位置 {location} 不以 {TEMPLATE_PREFIX} 开头，无法定位 NBT。")
    return upstream / TREE_TEMPLATES / f"{name[len(TEMPLATE_PREFIX):]}.nbt"


# ---------------------------------------------------------------------------
# JSON 读取与引用解析
# ---------------------------------------------------------------------------


class WorldgenData:
    """上游 worldgen 目录的只读视图，带 id 解析。"""

    def __init__(self, upstream: Path) -> None:
        self.upstream = upstream
        self.placed_dir = upstream / WORLDGEN / "placed_feature"
        self.feature_dir = upstream / WORLDGEN / "feature"
        self.biome_dir = upstream / WORLDGEN / "biome"
        if not self.placed_dir.is_dir():
            raise FileNotFoundError(f"找不到上游 placed_feature 目录：{self.placed_dir}")
        self._cache: dict[tuple[str, str], Any] = {}

    @staticmethod
    def source_path(kind: str, ident: str) -> Path:
        """id → 源文件路径（上游优先，其次原版副本）。"""
        name = ident.split(":", 1)[1]
        upstream_path = _UPSTREAM_ROOT / WORLDGEN / kind / f"{name}.json"
        if upstream_path.is_file():
            return upstream_path
        return VANILLA_DIR / kind / f"{name}.json"

    def _read(self, directory: Path, kind: str, ident: str) -> Any:
        name = ident.split(":", 1)[1] if ":" in ident else ident
        key = (kind, name)
        if key in self._cache:
            return self._cache[key]
        path = directory / f"{name}.json"
        if not path.is_file():
            # 第二来源：原版 JSON 的本地副本（见 VANILLA_DIR 的说明）。
            path = VANILLA_DIR / kind / f"{name}.json"
        value = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
        self._cache[key] = value
        return value

    def placed(self, ident: str) -> Any:
        return self._read(self.placed_dir, "placed_feature", ident)

    def configured(self, ident: str) -> Any:
        return self._read(self.feature_dir, "feature", ident)

    def biome_feature_refs(self) -> dict[str, dict[int, list[str]]]:
        """每个群系的 `features[step]` 原样（**含 `minecraft:` 条目**，保持数组顺序）。

        为什么不能只留 step 9、也不能过滤掉 `minecraft:`：原版种子里的 `featureIndex` 是
        **整条 `features[step]` 数组的下标**（`FeatureSorter.StepFeatureData#indexMapping` =
        列表下标）。少一条前面的 `minecraft:` 条目，后面每条的 index 都会前移，
        结果是「树形都对、每棵树都长在别处」，而且不报错。
        """
        out: dict[str, dict[int, list[str]]] = {}
        for path in sorted(self.biome_dir.glob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            steps = raw.get("features") or []
            out[path.stem] = {index: [str(ref) for ref in step]
                              for index, step in enumerate(steps)}
        return out


# ---------------------------------------------------------------------------
# 分类
# ---------------------------------------------------------------------------


@dataclass
class Report:
    """分类结果。`supported` 里的每一条都是「整条链都解释得动」。"""

    biome_refs: dict[str, list[str]] = field(default_factory=dict)
    #: 上游原样的 `{biome: {step: [id…]}}`（含 minecraft: 与未实现的条目，供全局调度对齐 index）
    biome_steps: dict[str, dict[int, list[str]]] = field(default_factory=dict)
    supported: list[str] = field(default_factory=list)
    skipped: dict[str, list[str]] = field(default_factory=dict)
    #: 因用户移除方块而**主动停发**的特征：`{id: 原因}`。
    #:
    #: 与 `skipped` 分开放的理由：`skipped` 的语义是「上游有、我们还没解释」的缺口，
    #: 契约上必须为空；这里的每一条都是用户拍板的结果，不是缺口。单独记一笔既不丢信息，
    #: 也能让门禁断言「停发集合恰好等于这几个 id」。
    excluded: dict[str, str] = field(default_factory=dict)
    placed_files: dict[str, str] = field(default_factory=dict)
    feature_files: dict[str, str] = field(default_factory=dict)
    templates: dict[str, str] = field(default_factory=dict)
    block_tags: dict[str, list[str]] = field(default_factory=dict)

    @property
    def skipped_reason_counts(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for reasons in self.skipped.values():
            for reason in reasons:
                counts[reason] = counts.get(reason, 0) + 1
        return dict(sorted(counts.items(), key=lambda item: (-item[1], item[0])))


def _predicate_problems(predicate: Any, tags: dict[str, list[str]]) -> list[str]:
    if not isinstance(predicate, dict):
        return ["predicate 不是对象"]
    kind = predicate.get("type")
    if kind not in SUPPORTED_PREDICATE:
        return [f"谓词 {kind}"]
    if kind == "minecraft:matching_block_tag":
        tag = predicate.get("tag")
        if tag != "minecraft:air" and tag not in tags:
            return [f"标签 {tag}"]
        return []
    if kind in ("minecraft:all_of", "minecraft:any_of"):
        problems: list[str] = []
        for sub in predicate.get("predicates") or []:
            problems += _predicate_problems(sub, tags)
        return problems
    if kind == "minecraft:not":
        return _predicate_problems(predicate.get("predicate"), tags)
    return []


def _modifier_problems(modifier: Any, tags: dict[str, list[str]]) -> list[str]:
    if not isinstance(modifier, dict):
        return ["修饰符不是对象"]
    kind = modifier.get("type")
    if kind not in SUPPORTED_PLACEMENT:
        return [f"修饰符 {kind}"]
    if kind == "minecraft:block_predicate_filter":
        return _predicate_problems(modifier.get("predicate"), tags)
    if kind in ("wover:is", "wover:is_next_to"):
        return _predicate_problems(modifier.get("predicate"), tags)
    return []


def _placed_problems(data: WorldgenData, placed: Any, tags: dict[str, list[str]],
                     depth: int = 0) -> list[str]:
    """一条（内联）placed feature 的全部问题；空列表=可解释。"""
    if depth > 12:
        return ["引用层级过深（疑似自引用）"]
    if not isinstance(placed, dict):
        return ["placed feature 不是对象"]
    problems: list[str] = []
    for modifier in placed.get("placement") or []:
        problems += _modifier_problems(modifier, tags)
    problems += _configured_problems(data, placed.get("feature"), tags, depth + 1)
    return problems


def _configured_problems(data: WorldgenData, configured: Any, tags: dict[str, list[str]],
                         depth: int) -> list[str]:
    if depth > 12:
        return ["引用层级过深（疑似自引用）"]
    if isinstance(configured, str):
        # 配置特征位置上的字符串引用，指向 `feature/` 而不是 `placed_feature/`：
        # 上游每条 placed_feature 的 `feature` 字段都写成与自己同名的 `betternether:x`，
        # 而 `feature/x.json` 才是真正的配置特征。
        resolved = data.configured(configured)
        if resolved is None:
            return [f"未解析的配置特征 {configured}"]
        return _configured_problems(data, resolved, tags, depth + 1)
    if not isinstance(configured, dict):
        return ["配置特征不是对象"]
    kind = configured.get("type")
    if kind not in SUPPORTED_FEATURE:
        return [f"特征类型 {kind}"]
    if kind == "wover:random_patch":
        inner = configured.get("feature")
        if isinstance(inner, str):
            # `wover:random_patch` 的 `feature` 是 Holder<PlacedFeature>：
            # 字符串指 placed_feature（`featureToPlace(NetherVegetationPlaced.X)`）。
            resolved = data.placed(inner)
            if resolved is None:
                return [f"未解析的 placed feature {inner}"]
            return _placed_problems(data, resolved, tags, depth)
        return _placed_problems(data, inner, tags, depth)
    if kind == "minecraft:overlay":
        # 上游 datagen 把它写成单个内联 placed feature（不是数组），两种都接受。
        subs = configured.get("features")
        subs = subs if isinstance(subs, list) else [subs]
        problems: list[str] = []
        for sub in subs:
            if isinstance(sub, str):
                resolved = data.placed(sub)
                if resolved is None:
                    problems.append(f"未解析的 placed feature {sub}")
                else:
                    problems += _placed_problems(data, resolved, tags, depth)
            else:
                problems += _placed_problems(data, sub, tags, depth)
        return problems
    if kind == "minecraft:random_selector":
        # `random_selector` 的 `features[].feature` 与 `default` 都是 **placed feature**
        # （原版 `WeightedPlacedFeature` / `PlacedFeature`），不是配置特征。
        subs: list[Any] = [entry.get("feature") for entry in configured.get("features") or []
                           if isinstance(entry, dict)]
        if "default" in configured:
            subs.append(configured["default"])
        problems: list[str] = []
        for sub in subs:
            if isinstance(sub, str):
                resolved = data.placed(sub)
                if resolved is None:
                    problems.append(f"未解析的 placed feature {sub}")
                else:
                    problems += _placed_problems(data, resolved, tags, depth)
            else:
                problems += _placed_problems(data, sub, tags, depth)
        return problems
    return []


# ---------------------------------------------------------------------------
# 方块标签
# ---------------------------------------------------------------------------


def _raw_values(upstream: Path, tag: str) -> list[str]:
    """一个 tag 的**原始**条目：wover 官方基表 + BetterNether 的增量文件。

    <p>增量文件不只在 `data/wover/tags/block/` 下 —— BetterNether 也会往
    `data/minecraft/tags/block/nylium.json` 这类**原版 tag** 里加东西（它把 4 种草加进 nylium）。
    所以这里对任意命名空间都读一次，否则 `minecraft:nylium` 会永远只有原版那 2 个菌岩。
    """
    values: list[str] = list(WOVER_TAGS_BASE.get(tag, ()))
    namespace, path = tag.split(":", 1)
    delta = (upstream / "src/main/generated/data" / namespace / "tags/block"
             / f"{path}.json")
    if delta.is_file():
        raw = json.loads(delta.read_text(encoding="utf-8"))
        for entry in raw.get("values") or []:
            if isinstance(entry, str):
                values.append(entry)
            elif isinstance(entry, dict) and isinstance(entry.get("id"), str):
                values.append(entry["id"])
    return values


def _expand_tag(upstream: Path, tag: str, seen: set[str] | None = None) -> list[str]:
    """把一个 tag 递归展开成具体方块 id（`#` 前缀条目继续展开，环引用安全跳过）。

    <p>原版 tag 的固定内容（`VANILLA_TAGS`）只是**基底**，仍要并上 BetterNether 的增量 ——
    否则 `minecraft:nylium` 会漏掉上游加进去的 4 种草。
    """
    seen = seen or set()
    if tag in seen:
        return []
    seen.add(tag)
    out: list[str] = list(VANILLA_TAGS.get(tag, ()))
    for entry in _raw_values(upstream, tag):
        if entry.startswith("#"):
            out += _expand_tag(upstream, entry[1:], seen)
        else:
            out.append(entry)
    return list(dict.fromkeys(out))


def resolve_block_tags(upstream: Path, used: Iterable[str]) -> dict[str, list[str]]:
    """本次真正用到的标签 → 具体方块 id 列表（去重、保持首次出现顺序）。"""
    out: dict[str, list[str]] = {}
    # `FindInDirection` 的缺省 surface_predicate 就是 wover:surfaces/terrain
    # （BlockPredicates.ONLY_GROUND = matchesTag(TagManager 的 surfaces/terrain)）。
    # 它不一定出现在任何谓词里，但运行期要用，所以总是解析出来。
    # 运行期还要用的 tag：FindInDirection 的缺省 surface（terrain），以及复杂树
    # `installGroundTags` 需要的 stones / mycelium / sculk_like / nylium。
    # 它们不一定出现在任何谓词里，但必须在 manifest 里解析出来。
    always = set(used) | {
        "wover:surfaces/terrain",
        "wover:surfaces/nether/stones",
        "wover:surfaces/nether/mycelium",
        "wover:surfaces/sculk_like",
        "minecraft:nylium",
    }
    for tag in sorted(always):
        if tag == "minecraft:air":
            out[tag] = []
            continue
        values = _expand_tag(upstream, tag)
        if not values and tag not in WOVER_TAGS_BASE and tag not in VANILLA_TAGS:
            raise SystemExit(
                f"标签 {tag} 既不在官方基表、也不在原版标签表、上游增量里也没有；"
                "分类阶段本应已跳过引用它的特征，这里不一致，拒绝生成。")
        out[tag] = list(dict.fromkeys(values))
    return out


def _used_tags(data: WorldgenData, placed: Any, into: set[str], depth: int = 0) -> None:
    """收集一条 placed feature 链里出现的所有 `matching_block_tag` 标签。

    <p>递归口径必须与 {@link _collect_references} 一致：**凡是会被复制的文件，其谓词里的
    标签都要收集**。`wover:sequence` 与 `wover:condition` 的分支同样是「会执行到的 placed
    feature」，只走 `random_patch` / `overlay` / `random_selector` 会让这些分支里的
    `matching_block_tag` 漏进 `blockTags` —— 运行期那个 tag 查不到，谓词恒 false，
    整条特征静默失效（地形侧尤其明显：`flooded_lava_pit_surface` 的两个分支就是这个形状）。
    """
    if depth > 12:
        return

    def walk_predicate(predicate: Any) -> None:
        if not isinstance(predicate, dict):
            return
        if predicate.get("type") == "minecraft:matching_block_tag":
            into.add(str(predicate.get("tag")))
        for sub in predicate.get("predicates") or []:
            walk_predicate(sub)
        if "predicate" in predicate and isinstance(predicate["predicate"], dict):
            walk_predicate(predicate["predicate"])

    def walk_placed_ref(node: Any, depth_inner: int) -> None:
        """`sequence` / `condition` 的子项：id 字符串先解析成 placed，内联对象直接下钻。"""
        if isinstance(node, str):
            walk_placed(data.placed(node), depth_inner)
        else:
            walk_placed(node, depth_inner)

    def walk_configured(configured: Any, depth_inner: int) -> None:
        if depth_inner > 12:
            return
        if isinstance(configured, str):
            walk_configured(data.configured(configured), depth_inner + 1)
            return
        if not isinstance(configured, dict):
            return
        kind = configured.get("type")
        if kind == "wover:random_patch":
            walk_placed_ref(configured.get("feature"), depth_inner)
        elif kind in ("minecraft:overlay", "wover:sequence"):
            subs = configured.get("features")
            subs = subs if isinstance(subs, list) else [subs]
            for sub in subs:
                walk_placed_ref(sub, depth_inner)
        elif kind == "minecraft:random_selector":
            subs2: list[Any] = [entry.get("feature")
                                for entry in configured.get("features") or []
                                if isinstance(entry, dict)]
            if "default" in configured:
                subs2.append(configured["default"])
            for sub in subs2:
                walk_placed_ref(sub, depth_inner)
        elif kind == "wover:condition":
            for key in ("filter_pass", "filter_fail"):
                if key in configured:
                    walk_placed_ref(configured[key], depth_inner)

    def walk_placed(node: Any, depth_inner: int) -> None:
        if depth_inner > 12 or not isinstance(node, dict):
            return
        for modifier in node.get("placement") or []:
            if isinstance(modifier, dict):
                walk_predicate(modifier.get("predicate"))
        walk_configured(node.get("feature"), depth_inner + 1)

    if isinstance(placed, str):
        walk_placed(data.placed(placed), depth)
    else:
        walk_placed(placed, depth)


# ---------------------------------------------------------------------------
# 树模板：NBT -> 紧凑 JSON
# ---------------------------------------------------------------------------


def _compound(node: Any) -> dict[str, tuple[int, Any]]:
    """TAG_Compound 的 payload（nbt.py 里是 `(10, {name: (tag, value)})`）。"""
    if isinstance(node, tuple) and len(node) == 2 and isinstance(node[1], dict):
        return node[1]
    return node if isinstance(node, dict) else {}


def _list_items(node: Any) -> list[tuple[int, Any]]:
    """TAG_List 的条目列表（nbt.py 里是 `(9, (elem_tag, [(tag, value), ...]))`）。"""
    if not isinstance(node, tuple) or len(node) != 2:
        return []
    payload = node[1]
    if isinstance(payload, tuple) and len(payload) == 2:
        return list(payload[1])
    return []


def _ints(node: Any) -> list[int] | None:
    """整数序列：TAG_Int_Array 直接是 list，TAG_List 是 `[(3, int), ...]`。"""
    if not isinstance(node, tuple) or len(node) != 2:
        return None
    tag, payload = node
    if tag == TAG_INT_ARRAY and isinstance(payload, list):
        return [int(v) for v in payload]
    if tag == TAG_LIST:
        items = _list_items(node)
        if all(isinstance(item, tuple) and item[0] == TAG_INT for item in items):
            return [int(item[1]) for item in items]
    return None


def convert_template(path: Path) -> dict[str, Any]:
    """把一个 gzip 结构 NBT 转成 `{palette, blocks, size}`。

    ## 为什么要转

    Java 侧只需要「哪个坐标放哪个状态」，不需要一个完整的 NBT 读取器。结构 NBT 里其余
    字段（`entities`、`DataVersion`、`author`）对服务端生成没有意义，转换时直接丢掉 ——
    尤其是 `entities`：上游模板里的实体是给建筑师看的标记，复刻它们会在世界里留下残留实体。

    ## 坐标约定

    `blocks` 的 `pos` 是**以角为原点**的模板内坐标（原版 `StructureTemplate` 语义），
    运行期按 `位置 + (0, offset_y, 0) + pos` 落地。不旋转、不镜像：上游 JSON 里没有任何
    旋转依据，凭空旋转只会让树朝向随机。
    """
    _, _, root = nbt.read_file(path)
    entries = _compound(root)
    dims = _ints(entries.get("size"))
    if not dims or len(dims) != 3:
        raise SystemExit(f"{path.name}: 缺少 size 整型数组，不是结构模板。")

    palette: list[dict[str, Any]] = []
    for entry in _list_items(entries.get("palette")):
        fields = _compound(entry)
        # 上游这些模板是**小写**结构格式（`id` / `properties`），原版 structure NBT 用
        # `Name` / `Properties`。两种都接受，不按文件名猜。
        name_node = fields.get("id") or fields.get("Name")
        if not isinstance(name_node, tuple):
            raise SystemExit(f"{path.name}: palette 条目既没有 id 也没有 Name。")
        original = str(name_node[1])
        item: dict[str, Any] = {"id": replacement_id(original)}
        props = fields.get("properties") or fields.get("Properties")
        if isinstance(props, tuple) and item["id"] != "minecraft:air":
            # 换成原版同形块后，只保留目标方块认得的属性：上游模板里混着目标没有的字段
            # （`cincinnasite_slab` 的 palette 就带了一个 `half`），带过去会让整条调色板失效。
            allowed = filter_properties(original, _compound(props).keys())
            kept = {str(key): str(value[1]) for key, value in _compound(props).items()
                    if key in allowed}
            if kept:
                item["properties"] = kept
        palette.append(item)

    blocks: list[list[int]] = []
    for entry in _list_items(entries.get("blocks")):
        fields = _compound(entry)
        coords = _ints(fields.get("pos"))
        state = fields.get("state")
        if coords is None or len(coords) != 3 or not isinstance(state, tuple):
            raise SystemExit(f"{path.name}: blocks 条目缺少 pos/state。")
        index = int(state[1])
        if not 0 <= index < len(palette):
            raise SystemExit(f"{path.name}: state {index} 超出 palette 长度 {len(palette)}。")
        blocks.append(coords + [index])

    if not palette or not blocks:
        raise SystemExit(f"{path.name}: 模板为空（palette {len(palette)}，blocks {len(blocks)}）。")
    return {"size": dims, "palette": palette, "blocks": blocks}


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


class _AnyTag(dict):
    """初筛期的标签占位表：对任何 tag 都报「存在」。

    第一轮只判断「修饰符/特征类型是否支持」，标签内容留到第二轮 —— 否则一个
    `matching_block_tag` 的 tag 解析失败会让整次生成直接抛异常，而正确行为是
    「跳过引用它的那一条」。标签名会在 `__contains__` 里被记下来，供第二轮解析。
    """

    def __init__(self, seen: set[str]) -> None:
        super().__init__()
        self._seen = seen

    def __contains__(self, key: object) -> bool:  # type: ignore[override]
        self._seen.add(str(key))
        return True


#: 原版 placed feature：JSON **在服务端 jar 里就有**（`data/minecraft/worldgen/placed_feature/`，
#: 26.3 共 282 条），所以「仓库里没有 JSON」不是理由。它们仍不解释，是因为各自需要尚未实现的
#: 修饰符 / 特征类型。补齐下面任何一项即可让对应条目转 supported（构建期需把这些 JSON 一并复制）。
#: 原版 placed/configured feature JSON 的本地副本（逐字取自 serverJar 的
#: `data/minecraft/worldgen/{placed_feature,feature}/`）。
VANILLA_DIR = Path(__file__).resolve().parent / "vanilla_worldgen"

#: 仓库内上游 mod 根，`WorldgenData.source_path` 用它定位 BetterNether 自己的 JSON。
_UPSTREAM_ROOT = Path(__file__).resolve().parents[2] / "upstream/BetterNether"

VANILLA_PENDING: dict[str, str] = {}   # 目前为空：spring_lava 与原版蘑菇都已实现

#: 特征里引用了用户移除的方块时的停发原因。见 `publication.REMOVED_CLASSES`：
#: 这些方块不再进 CE 包，数据包若继续指向它们就是「未注册方块」，jigsaw/特征都会静默丢件。
REMOVED_BLOCK_REASON = "方块已被用户移除（几何或功能不完整），特征不再发布"

#: 算法型特征类型：方块 id 写死在 Java 算法里，JSON 中**没有任何方块 id 可以摘**。
#: 它唯一放置的方块被用户移除后，只能整条停发 —— 否则运行期会去放一个未注册方块。
#: `betternether:soul_lily` 的 `SoulLilyFeature` 就是这种：JSON 只有 `{"type": ...}`。
#:
#: 2026-10-30 补齐：三棵树所需的方块已从发布集合移除，但它们的 id 写死在
#: `worldgen/UpstreamSimpleTreeFeatures.java` 里（JSON 无方块 id 可摘），所以只能在
#: 这里整条停发。不停发的后果不是「放不出方块」，而是运行期整棵树静默放弃并每次启动
#: 刷一条「方块无法解析」——
#:   * `willow_tree`     → `willow_trunk`(shape bottom/middle/top) + `willow_branch`
#:   * `old_willow_tree` → `willow_branch`(shape middle/end)
#:   * `wart_tree`       → `wart_seed`(facing ×6)
#: 三者在 `publication.REMOVED_BLOCK_IDS` 里。若将来重新发布这些方块，请同时把它们
#: 从这里移除，否则树仍然不会生成。
REMOVED_FEATURE_TYPES: frozenset[str] = frozenset({
    "betternether:soul_lily",
    "betternether:willow_tree",
    "betternether:old_willow_tree",
    "betternether:wart_tree",
})


#: 原版下界群系。**必须**与 23 个 BN 群系一起导出：`NetherBiomes.resolve()` 要求 28 个键全注册，
#: 而且只挂在原版群系上的条目（例如 `betternether:nether_ruby_large_ore` 只在
#: `minecraft:soul_sand_valley`）否则永远拿不到 featureIndex。
VANILLA_NETHER_BIOMES: tuple[str, ...] = (
    "minecraft:nether_wastes",
    "minecraft:soul_sand_valley",
    "minecraft:crimson_forest",
    "minecraft:warped_forest",
    "minecraft:basalt_deltas",
)

#: wover `biome_modifications` 的 datapack 目录（注意多一层 `wover/`：资源 id 是
#: `betternether:wover/worldgen/biome_modifications/<name>`）。
WOVER_MODIFICATIONS = "src/main/generated/data/betternether/wover/worldgen/biome_modifications"


def _predicate_holds(predicate: dict[str, object] | None, biome: str) -> bool:
    """判定 wover 的 biome modification 谓词在这个群系上是否成立。

    只实现这 5 个文件用到的类型（`and` / `or` / `not` / `is_biome` / `is_namespace` /
    `in_dimension`）；遇到别的类型**抛错**而不是猜。
    """
    if not isinstance(predicate, dict):
        return True
    # 上游 biome 文件的 key 是**裸名**（`bone_reef`、`gloomwood`），没有 `betternether:` 前缀。
    # 谓词里比较的是带命名空间的 id，所以先归一化，否则 `is_namespace betternether` 会判 false，
    # `default_ores` 的 `not(is_namespace betternether)` 反而把 23 个 BN 群系全命中。
    if ":" not in biome:
        biome = "betternether:" + biome
    kind = predicate.get("type")
    if kind == "wover:and":
        return all(_predicate_holds(p, biome) for p in predicate.get("predicates", []))
    if kind == "wover:or":
        return any(_predicate_holds(p, biome) for p in predicate.get("predicates", []))
    if kind == "wover:not":
        return not _predicate_holds(predicate.get("predicate"), biome)
    if kind == "wover:is_biome":
        return predicate.get("biome_key") == biome
    if kind == "wover:is_namespace":
        return biome.split(":", 1)[0] == predicate.get("namespace")
    if kind == "wover:in_dimension":
        return predicate.get("dimension_key") == "minecraft:the_nether"
    raise ValueError(f"未知的 biome modification 谓词类型：{kind}")


def vanilla_biome_steps(jar_biomes: pathlib.Path) -> dict[str, dict[int, list[str]]]:
    """5 个原版下界群系的 `features[step]`（数据来自本地 jar 抽出的 vendored JSON）。"""
    out: dict[str, dict[int, list[str]]] = {}
    for biome in VANILLA_NETHER_BIOMES:
        name = biome.split(":", 1)[1]
        raw = json.loads((jar_biomes / f"{name}.json").read_text(encoding="utf-8"))
        steps: dict[int, list[str]] = {}
        for step, refs in enumerate(raw.get("features") or []):
            if refs:
                steps[step] = [str(ref) for ref in refs]
        out[biome] = steps
    return out


def apply_wover_modifications(upstream: pathlib.Path,
                              biomes: dict[str, dict[int, list[str]]]) -> list[str]:
    """把 wover `biome_modifications` 注入到的群系 step 数组**末尾**。

    wover 的语义是「在原版/基础 features 之后追加」，所以追加在列表末尾；顺序即注入顺序
    （文件按名字排序遍历，保证可复现）。
    """
    directory = upstream / WOVER_MODIFICATIONS
    if not directory.is_dir():
        raise FileNotFoundError(f"缺少 wover biome_modifications 目录：{directory}")
    notes: list[str] = []
    for path in sorted(directory.glob("*.json")):
        document = json.loads(path.read_text(encoding="utf-8"))
        features = document.get("features")
        if not features:
            continue
        if not any(features):
            continue
        predicate = document.get("predicate")
        for biome in sorted(biomes):
            if not _predicate_holds(predicate, biome):
                continue
            steps = biomes.setdefault(biome, {})
            for step, refs in enumerate(features):
                if not refs:
                    continue
                bucket = steps.setdefault(step, [])
                for ref in refs:
                    bucket.append(str(ref))
                    notes.append(f"{path.stem} -> {biome} step {step}: {ref}")
    return notes


def build(upstream: Path) -> Report:
    """完整分析：分类 + 标签解析 + 树模板清单。"""
    data = WorldgenData(upstream)
    per_biome = data.biome_feature_refs()
    # 28 个群系 = 23 个 BN + 5 个原版下界；原版群系再叠加 wover 的 biome_modifications。
    vanilla = vanilla_biome_steps(Path(__file__).parent / "vanilla_worldgen" / "biome")
    per_biome.update(vanilla)
    modification_notes = apply_wover_modifications(upstream, per_biome)

    order: list[str] = []
    for steps in per_biome.values():
        for ref in steps.get(VEGETAL_DECORATION_STEP, []):
            # 原版条目一律 native（由原生 FeaturePlacer 执行），不参与「本移植能不能编译」的
            # 分类与 skipped 账本 —— 否则 5 个原版群系会把 minecraft:weeping_vines 这类
            # 条目记成「跳过」，读起来像我们漏做了。
            if ref.startswith("minecraft:"):
                continue
            if ref not in order:
                order.append(ref)

    # 第一轮：标签宽松放行，先把「类型层面支持」的候选筛出来。
    candidates: list[str] = []
    skipped: dict[str, list[str]] = {}
    for ref in order:
        placed = data.placed(ref)
        if placed is None:
            skipped[ref] = [VANILLA_PENDING.get(
                ref, "上游与 serverJar 都没有这条 placed_feature 的 JSON")]
            continue
        problems = sorted(set(_placed_problems(data, placed, _AnyTag(set()))))
        if problems:
            skipped[ref] = problems
        else:
            candidates.append(ref)

    # 只解析候选集真正用到的标签：被跳过的特征不该把无关标签拖进解析。
    used: set[str] = set()
    for ref in candidates:
        _used_tags(data, data.placed(ref), used)
    tags = resolve_block_tags(upstream, used)

    # 第二轮：拿真实标签集合复核。
    allowed_tags = dict(tags)
    for tag in APPROXIMATED_TAGS:
        allowed_tags.setdefault(tag, [])
    supported: list[str] = []
    excluded: dict[str, str] = {}
    for ref in candidates:
        # 原版条目一律交给 NMS 原生执行（FeaturePlacer）：本移植不再编译它们。
        # 这样做的前提是 `bnlib/biomes.py#strip_mod_features` 会在数据包里**保留**原版引用，
        # 所以「原生执行」是唯一不会双放的归属；算法实现仍留在 Java 侧做纯单元回归。
        if ref.startswith("minecraft:"):
            continue
        placed = data.placed(ref)
        problems = sorted(set(_placed_problems(data, placed, allowed_tags)))
        if problems:
            skipped[ref] = problems
            continue
        # 第三轮：用户移除的方块不再进包，这里把指向它们的特征一并停发。
        # 只摘得掉「加权候选」那一层；摘不掉（结构性写死引用/算法型方块 id）就整条不发，
        # 绝不交付悬空引用。这类停发**不进 skipped** —— 它不是「还没解释」的缺口。
        removed = removed_placement_reason(data, ref)
        if removed:
            excluded[ref] = removed
            continue
        supported.append(ref)

    # 逐群系只留下支持的那部分，保持上游顺序。
    biome_refs: dict[str, list[str]] = {}
    for biome, steps in per_biome.items():
        kept: list[str] = []
        # 只保留 step 9 且「能解释」的条目，用于 `supported`/复制文件；清单本身另见 `steps_all`。
        for ref in steps.get(VEGETAL_DECORATION_STEP, []):
            if ref in supported and ref not in kept:
                kept.append(ref)
        biome_refs[biome] = kept

    report = Report(
        biome_refs=biome_refs,
        biome_steps=per_biome,
        supported=supported,
        skipped=dict(sorted(skipped.items())),
        excluded=dict(sorted(excluded.items())),
        block_tags=tags,
    )

    # 收集要复制的文件与模板引用。
    #
    # 这里必须**递归**收集，不能按 id 同名去找文件：上游存在「名字对不上」的引用，
    #     placed_feature/vegetation_nether_jungle.json -> "betternether:vegetation_jungle"
    #     placed_feature/patch_lumabus_vine_sparse.json -> "betternether:patch_lumabus_vine_gloom"
    #     placed_feature/vegetation_nether_poor_grassland.json -> "betternether:vegetation_nether_poor_grasslands"
    # 只按同名复制会漏掉这三个文件，运行期解析失败 → 整条特征被跳过 → 该群系少一层植被。
    _collect_references(data, supported, report)
    report.templates = _template_references(data, supported)
    return report


def _collect_references(data: WorldgenData, supported: list[str], report: Report) -> None:
    """递归收集 supported 引用到的 placed / configured 特征文件（保持文件数最小）。

    ## 为什么要顺着 `feature` / `features` / `filter_*` 一路走到底

    一条 placed feature 的链常常嵌套两层以上，而**每一层都是独立文件**：漏掉哪一层，运行期
    就在那一层解析失败、整条链被跳过 —— 表现是「这个群系少了岩浆湖底/黑石钟乳」这种没有报错
    的空白。上游实际用到的嵌套形态（逐个实读 JSON 得到，不是枚举猜的）：

      * `wover:random_patch.feature`、`minecraft:overlay.features`、
        `minecraft:random_selector.features[].feature` + `default` —— 子项是 placed feature；
      * `wover:sequence.features` —— 一串**按序执行**的 placed feature：
        `betternether:flooded_lava_pit` 依次跑 `extend_basalt` / `flooded_lava_pit_surface` /
        `mark`，`patch_blackstone_stalactite_on_ceil` 与
        `patch_blackstone_stalagmite_on_ground` 则引用 `terracotta_clump`；
      * `wover:condition.filter_pass` / `filter_fail` —— 条件成立/不成立时各跑一条 placed
        feature：`flooded_lava_pit_surface` 的 `lava_pit` 与 `basalt_or_air`。

    只处理第一类时，后两类引用的 JSON 根本不会被复制，而清单仍把整条链登记为 supported ——
    数据看起来齐、世界里却少东西。
    """

    placed_names: dict[str, str] = {}
    configured_names: dict[str, str] = {}
    seen_placed: set[str] = set()

    def walk_placed(ident: str, depth: int) -> None:
        # ident 是**带命名空间**的完整 id（原版补丁是 minecraft:*）。此前这里硬拼
        # `betternether:`，会把 brown_mushroom_normal 写成 betternether:brown_mushroom_normal，
        # 于是运行期按 minecraft: 去查永远查不到。
        if depth > 12 or ident in seen_placed:
            return
        seen_placed.add(ident)
        namespace, name = ident.split(":", 1)
        node = data.placed(ident)
        if node is None:
            return
        placed_names[ident] = f"placed_feature/{name}.json"
        for modifier in node.get("placement") or []:
            pass  # 修饰符里不会引用特征
        walk_configured(node.get("feature"), depth + 1)

    def walk_placed_node(node: Any, depth: int) -> None:
        """一个**内联** placed feature 对象：它没有自己的文件，只有 `feature` 需要继续下钻。"""
        if depth > 12 or not isinstance(node, dict):
            return
        walk_configured(node.get("feature"), depth + 1)

    def walk_placed_ref(node: Any, depth: int) -> None:
        """`sequence` / `condition` 之类字段的子项：id 字符串按 placed 解析，内联对象直接下钻。

        优先按 placed 解释，因为 wover 这几个字段的类型就是 `Holder<PlacedFeature>`
        （`flooded_lava_pit.features` 里全是 `placed_feature/*.json` 的 id）；只有当同名
        placed 不存在、而配置特征存在时才退回配置特征 —— 少一层解释就等于漏一个文件。
        """
        if isinstance(node, str):
            if data.placed(node) is not None:
                walk_placed(node, depth)
            else:
                walk_configured(node, depth)
            return
        walk_placed_node(node, depth)

    def walk_configured(node: Any, depth: int) -> None:
        if depth > 12:
            return
        if isinstance(node, str):
            if WorldgenData.source_path("feature", node).is_file():
                _, node_name = node.split(":", 1)
                configured_names[node] = f"feature/{node_name}.json"
                walk_configured(data.configured(node), depth + 1)
            else:
                # 不是配置特征 —— 在 placed 位置上的字符串指的是 placed feature。
                walk_placed(node, depth)
            return
        if not isinstance(node, dict):
            return
        kind = node.get("type")
        if kind == "wover:random_patch":
            walk_placed_ref(node.get("feature"), depth)
        elif kind in ("minecraft:overlay", "wover:sequence"):
            # `overlay` 的子项是「同一位置上各放一次」，`sequence` 是「按序各跑一次」；
            # 两者的子项都是 placed feature，收集口径相同（文件层面不需要区分顺序）。
            subs = node.get("features")
            subs = subs if isinstance(subs, list) else [subs]
            for sub in subs:
                walk_placed_ref(sub, depth)
        elif kind == "minecraft:random_selector":
            subs2: list[Any] = [entry.get("feature") for entry in node.get("features") or []
                                if isinstance(entry, dict)]
            if "default" in node:
                subs2.append(node["default"])
            for sub in subs2:
                walk_placed_ref(sub, depth)
        elif kind == "wover:condition":
            # 条件成立 / 不成立各跑一条 placed feature；两个字段都可能缺（缺 = 什么都不跑）。
            for key in ("filter_pass", "filter_fail"):
                if key in node:
                    walk_placed_ref(node[key], depth)

    for ref in supported:
        walk_placed(ref, 0)

    report.placed_files = dict(sorted(placed_names.items()))
    report.feature_files = dict(sorted(configured_names.items()))


def _template_references(data: WorldgenData, supported: list[str]) -> dict[str, str]:
    """支持的 placed feature 里 `wover:template` 引用到的模板 → 目标文件名。"""
    found: dict[str, str] = {}

    def walk(node: Any, depth: int) -> None:
        if depth > 12 or not isinstance(node, dict):
            return
        if node.get("type") == "wover:template":
            items = (node.get("structures") or {}).get("items") or []
            for item in items:
                value = (item or {}).get("value") or {}
                location = value.get("location")
                if isinstance(location, str):
                    if not location.split(":", 1)[1].startswith(TEMPLATE_PREFIX):
                        raise SystemExit(
                            f"模板位置 {location} 不以 {TEMPLATE_PREFIX} 开头，无法定位 NBT。")
                    name = location.split(":", 1)[1][len(TEMPLATE_PREFIX):]
                    found.setdefault(location, f"templates/{TEMPLATE_PREFIX}{name}.json")
        if "feature" in node:
            walk(node["feature"], depth + 1)
        subs = node.get("features")
        if isinstance(subs, list):
            for sub in subs:
                walk(sub, depth + 1)
        elif isinstance(subs, dict):
            walk(subs, depth + 1)

    for ref in supported:
        placed = data.placed(ref)
        walk(placed.get("feature") if isinstance(placed, dict) else None, 0)
        if isinstance(placed, dict):
            walk(placed, 0)
    return found


#: 方块 id 只可能出自这些键：方块状态提供器（`id` / `data` / `state`）与谓词里的方块集合。
#: 用键名而不是「扫所有 betternether 字面量」，因为 JSON 里的 `feature` / `location`
#: 同样是 `betternether:*`，那是特征 id 与模板位置，不是方块。
_BLOCK_ID_KEYS = frozenset({"id", "data", "blocks"})


def _collect_block_ids(node: Any, found: set[str]) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key in _BLOCK_ID_KEYS:
                _collect_block_ids(value, found)
            elif isinstance(value, (dict, list)):
                _collect_block_ids(value, found)
    elif isinstance(node, list):
        for item in node:
            _collect_block_ids(item, found)
    elif isinstance(node, str):
        if node.startswith("betternether:") and "/" not in node:
            found.add(node.split(":", 1)[1])


def _mentions_removed(node: Any) -> bool:
    found: set[str] = set()
    _collect_block_ids(node, found)
    return any(is_removed_block(f"betternether:{name}") for name in found)


def strip_removed_placements(node: Any) -> tuple[Any, int, bool]:
    """删掉 weighted `entries` 里引用已移除方块的候选，返回 `(新节点, 删除条数, 是否删空)`。

    ## 为什么删候选而不是删整条特征

    `betternether:vegetation_magma_land` 在同一个 weighted 里并列了间歇泉（40）与岩浆花
    （30×4）。整条停发会让岩浆陆地上再长不出岩浆花；把间歇泉那一条从候选里摘掉，其余
    权重照旧生效（原版 weighted 按权重占比抽取，少一条只是概率重新分配）。

    `是否删空` 交给调用方：真的什么都不剩的特征应当整条停发，而不是留一条空特征。
    结构性引用（`to_place` 直接写死一个已移除方块、没有候选列表）这里**不猜着改**，
    由 `removed_placement_reason` 判定为整条停发。
    """
    dropped = 0
    emptied = False

    def walk(value: Any) -> Any:
        nonlocal dropped, emptied
        if isinstance(value, list):
            return [walk(item) for item in value]
        if not isinstance(value, dict):
            return value
        result: dict[str, Any] = {}
        for key, item in value.items():
            if key == "entries" and isinstance(item, list):
                kept = []
                for entry in item:
                    if _mentions_removed(entry):
                        dropped += 1
                        continue
                    kept.append(walk(entry))
                if not kept:
                    emptied = True
                result[key] = kept
            else:
                result[key] = walk(item)
        return result

    return walk(node), dropped, emptied


#: 上游 blockstate 里声明过的属性名（`方块 id` → 属性集合）；None 表示该方块没有 blockstate。
_BLOCKSTATE_PROPERTIES: dict[str, set[str] | None] = {}


def _declared_properties(upstream: Path, block_id: str) -> set[str] | None:
    """上游 blockstate 里这个方块**真实声明过**的属性名。

    没有 blockstate（原版方块、或上游用代码注册状态的方块）时返回 `None` —— 那时不做判断，
    保持原样，避免把原版方块的合法属性也削掉。
    """
    if block_id in _BLOCKSTATE_PROPERTIES:
        return _BLOCKSTATE_PROPERTIES[block_id]
    namespace, _, name = block_id.partition(":")
    if namespace != "betternether":
        _BLOCKSTATE_PROPERTIES[block_id] = None
        return None
    for tree in ("src/main/generated/assets", "src/main/resources/assets"):
        path = upstream / tree / namespace / "blockstates" / f"{name}.json"
        if not path.is_file():
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        found: set[str] = set()
        for key in (payload.get("variants") or {}):
            for part in str(key).split(","):
                part = part.strip()
                if "=" in part:
                    found.add(part.split("=", 1)[0])
        if found:
            _BLOCKSTATE_PROPERTIES[block_id] = found
            return found
    _BLOCKSTATE_PROPERTIES[block_id] = None
    return None


def strip_undeclarable_properties(node: Any, upstream: Path) -> tuple[Any, dict[str, list[str]]]:
    """删掉 feature 里**上游 blockstate 根本没声明**的方块属性，返回 `(新节点, 删除记录)`。

    ## 为什么必须删

    worldgen 放置时是按「带属性的方块状态」请求的，运行期由
    `CraftEngineLookup.lookup(id, properties)` 逐条应用。只要请求里出现 CE 没有声明过的
    属性名，就会刷一行「CraftEngine 方块 X 没有属性 Y」。

    而其中一类属性是**上游的纯运行时属性**，blockstate 里压根没有它：
    `gloomwisp_vine` 的 `offset`（上游按方块位置做 XZ 抖动，`BlockGloomwispVine#stateAwareOffset`）。
    这类属性在 CE 侧无从声明 —— 不是「补配置」能解决的，只能不请求它。

    ## 为什么按 blockstate 推断，而不是维护白名单

    白名单会随上游版本漂移，而且「哪些方块有哪个属性」本来就在 blockstate 里写着。
    用 blockstate 反推，等于用同一份真值约束两侧，不会漏也不会多。

    原版方块（没有 blockstate 可查）一律不动：它们的属性由 CraftEngine 自己保证。
    """
    dropped: dict[str, list[str]] = {}

    def walk(value: Any) -> Any:
        if isinstance(value, list):
            return [walk(item) for item in value]
        if not isinstance(value, dict):
            return value
        block_id = value.get("id")
        props = value.get("properties")
        if isinstance(block_id, str) and isinstance(props, dict):
            declared = _declared_properties(upstream, block_id)
            if declared is not None:
                keep = {k: v for k, v in props.items() if k in declared}
                removed = sorted(set(props) - set(keep))
                if removed:
                    dropped.setdefault(block_id, []).extend(removed)
                    value = dict(value)
                    if keep:
                        value["properties"] = keep
                    else:
                        value.pop("properties", None)
        return {k: walk(v) for k, v in value.items()}

    return walk(node), dropped


def _configured_graph_problem(data: "WorldgenData", node: Any,
                              seen: set[str], depth: int = 0) -> str | None:
    """沿配置特征引用图找「移除后不能再发布」的节点，返回停发原因或 None。

    为什么必须跟着引用走：`patch_neon_equisetum` 的 JSON 里只有
    `"feature": "betternether:neon_equisetum"` 这一条**特征引用**，真正的方块 id 在它指向
    的 `block_column` 里。只看一层就会漏掉，数据包照旧发出一个放置未注册方块的特征。
    """
    if depth > 12:
        return None
    if isinstance(node, str):
        if node in REMOVED_FEATURE_TYPES:
            return REMOVED_BLOCK_REASON
        if node.startswith("minecraft:") or node in seen:
            return None
        seen.add(node)
        return _configured_graph_problem(data, data.configured(node), seen, depth + 1)
    if isinstance(node, list):
        for item in node:
            problem = _configured_graph_problem(data, item, seen, depth + 1)
            if problem:
                return problem
        return None
    if not isinstance(node, dict):
        return None
    if node.get("type") in REMOVED_FEATURE_TYPES:
        return REMOVED_BLOCK_REASON
    sanitized, _dropped, emptied = strip_removed_placements(node)
    if emptied or _mentions_removed(sanitized):
        return REMOVED_BLOCK_REASON
    # 递归看**摘干净之后**的子树：被摘掉的加权候选不能反过来把整条特征判死。
    for value in sanitized.values():
        problem = _configured_graph_problem(data, value, seen, depth + 1)
        if problem:
            return problem
    return None


def removed_placement_reason(data: "WorldgenData", ref: str) -> str | None:
    """这条特征能否在「移除后」仍然发布；不能则给出写进 skipped 的原因。

    整条停发的情形（都已在 `_configured_graph_problem` 里判定）：

      * 摘掉可摘的加权候选后**仍然**引用已移除方块（`block_column` 的 `layers[].provider`、
        `to_place.state` 这类结构性写法）—— 不猜着改结构；
      * 加权候选被摘空，什么都不剩；
      * 用了 `REMOVED_FEATURE_TYPES` 里的算法型类型（方块 id 在 Java 里，摘不到）。
    """
    placed = data.placed(ref)
    if not isinstance(placed, dict):
        return None
    return _configured_graph_problem(data, placed.get("feature"), set())


def placed_block_ids(upstream: Path) -> set[str]:
    """支持的世界生成特征会放置/参照的 `betternether:` 方块 id（不含命名空间）。

    ## 为什么连「谓词里被检测的 id」也算

    `blocks.py` 用这个集合决定哪些植物/装饰方块进包。谓词里出现的 id（例如
    `matching_blocks betternether:bleached_gloomsculk`）如果没进包，那条谓词永远不成立，
    整条特征静默失效 —— 表现为「这个群系就是不长东西」。所以扫描口径是「支持的特征里
    所有方块状态提供器与方块谓词提到过的 id」。

    被跳过的特征**不**参与：它们的方块不该为了一个不放置的特征进包。
    """
    report = build(upstream)
    data = WorldgenData(upstream)
    found: set[str] = set()
    seen_features: set[str] = set()

    def scan_feature(feature_name: str) -> None:
        """读 `feature/<名>.json`，并把里面嵌套引用的 feature 一并展开。

        上游大量特征是「套娃」的：`feature/patch_eye_vine.json` 里只写
        `{"type": "wover:random_patch", "feature": "betternether:eye_vine"}`，
        真正的方块（`eye_vine` + `eyeball` + `eyeball_small`）在被引用的
        `feature/eye_vine.json` 里。只读一层就会漏掉 eyeball / eyeball_small ——
        它们因此被当成「没人放置」而不发布，运行期装载器报「包缺少方块 id」，
        整根眼球藤的结球段静默消失。
        """
        if feature_name in seen_features or len(seen_features) > 256:
            return
        seen_features.add(feature_name)
        path = data.feature_dir / f"{feature_name}.json"
        if not path.is_file():
            return
        payload = json.loads(path.read_text(encoding="utf-8"))
        _collect_block_ids(payload, found)
        # `feature` 键既可能是字符串（单引用），也可能是对象（带概率等包装）。
        nested = payload.get("feature")
        if isinstance(nested, str) and nested.startswith("betternether:"):
            scan_feature(nested.split(":", 1)[1])

    for ref in report.supported:
        name = ref.split(":", 1)[1]
        for directory in (data.placed_dir, data.feature_dir):
            path = directory / f"{name}.json"
            if not path.is_file():
                continue
            payload = json.loads(path.read_text(encoding="utf-8"))
            _collect_block_ids(payload, found)
            # placed_feature 指向的 feature 也要展开（同名时会被 scan_feature 去重）。
            target = payload.get("feature")
            if isinstance(target, str) and target.startswith("betternether:"):
                scan_feature(target.split(":", 1)[1])
    for location in report.templates:
        for item in convert_template(template_path(upstream, location))["palette"]:
            ident = item["id"]
            if ident.startswith("betternether:"):
                found.add(ident.split(":", 1)[1])
    return found


def _all_refs(biome_steps: dict[str, dict[int, list[str]]]) -> set[str]:
    """清单里出现过的所有 id（各群系各 step 的并集）。"""
    return {ref for steps in biome_steps.values() for refs in steps.values() for ref in refs}


def _terrain_owned_ids(data: WorldgenData) -> set[str]:
    """**地形类别**的 placed feature id 集合（清单给它们标 `owner`）。

    <h2>为什么必须共用一份真值</h2>
    地形导出的取值口径是「步白名单 + step 9 前缀白名单」（`TERRAIN_STEPS` /
    `STEP9_TERRAIN_PREFIXES`），文件由 `terrain_worldgen.write()` 写进**同一个**
    `worldgen-upstream/` 根。本清单若自己再抄一份步表，两份迟早对不上：一方加了一步、
    另一方没改，那些条目就会在本清单里既不是 supported 也没有 owner —— 于是被当成「缺口」。

    <p>**这些条目是可执行的**（同一条 `DecorationScheduler` 按 step 执行，形状由
    `UpstreamTerrainFeatures` 编译）：`owner` 只回答「哪一类」，不回答「谁来放」。

    <p>**延迟导入**是必需的：`terrain_worldgen` 在模块顶部就 `import worldgen`，
    反向的模块级 import 会构成循环。相对导入失败（例如把本文件当脚本直接跑，
    `__package__` 为空）时返回空集 —— 那种情形的降级是「不标 owner、按普通占位处理」，
    与引入 owner 之前的行为一致。
    """
    try:
        from . import terrain_worldgen  # noqa: PLC0415
    except ImportError:
        return set()
    steps = terrain_worldgen.biome_steps(data)
    owned = {ref for per_step in steps.values() for entries in per_step.values() for ref in entries}
    # 把「因为方块被移除而停发」的地形特征摘掉。
    # `owner` 在运行期等价于「可执行」——装载器不读 `owner`，它只看有没有 `skip`，
    # 于是标了 owner 的条目都会去编译。若某条特征的方块已按要求移除（4 种钟乳石、`smoker`），
    # 编译期解析方块失败 → 报「包缺少 N 个世界生成方块 id」，而那是**主动移除**、不是缺口。
    # 这类条目必须退回 `skip: true`（只占 featureIndex 下标，不编译）。
    return {ref for ref in owned if not removed_placement_reason(data, ref)}


def write(upstream: Path, out: Path) -> dict[str, object]:
    """把支持的特征 JSON、树模板与清单写到 `out/worldgen-upstream/`。"""
    report = build(upstream)
    root = out / OUTPUT_DIR
    data = WorldgenData(upstream)

    written: list[str] = []
    copies: list[tuple[str, Path]] = []
    for ident, relative in report.placed_files.items():
        copies.append((relative, WorldgenData.source_path("placed_feature", ident)))
    for ident, relative in report.feature_files.items():
        copies.append((relative, WorldgenData.source_path("feature", ident)))
    for relative, source in sorted(copies):
        if not source.is_file():
            raise SystemExit(f"{relative}: 分类通过但上游缺 {source}，拒绝写一份引用悬空的数据。")
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        # 落盘前摘掉引用「已移除方块」的加权候选：`build()` 只保证剩下的特征还能放东西，
        # 具体哪一条候选要摘是在这里做的，否则写出去的数据包会指向未注册方块。
        payload, _dropped, _emptied = strip_removed_placements(
            json.loads(source.read_text(encoding="utf-8")))
        # 再摘掉「上游 blockstate 根本没声明」的方块属性（例如 gloomwisp_vine 的
        # offset —— 那是上游的运行时抖动，CE 无从声明）。不删就会在启动时刷
        # 「CraftEngine 方块 X 没有属性 Y」。原版方块不受影响。
        payload, _undeclarable = strip_undeclarable_properties(payload, upstream)
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        written.append(str(target.relative_to(out)).replace("\\", "/"))

    for location, relative in sorted(report.templates.items()):
        source = template_path(upstream, location)
        if not source.is_file():
            raise SystemExit(f"模板 {location} 在上游找不到：{source}")
        payload = convert_template(source)
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
                          encoding="utf-8")
        written.append(str(target.relative_to(out)).replace("\\", "/"))

    # schema 2：`{biome: {step: [条目…]}}`，条目三种形态
    #   "id"                     → 本移植编译并放置
    #   {"id": ..., "native": true} → 原版条目，交给服务端注册表的 PlacedFeature
    #   {"id": ..., "owner": "terrain"} → **可执行**，只是额外标注它属于地形那一类
    #                                     （形状由 UpstreamTerrainFeatures 编译，装载器同一个）
    #   {"id": ..., "skip": true}   → 本移植侧没有任何执行者（矿 / 已主动停发），只占 index
    # 顺序是语义：原版种子里的 featureIndex 就是该数组下标，删条目会让后面全体前移。
    #
    # ## 为什么 terrain 条目**不能**带 skip
    #
    # 运行期只有**一个**装饰计划：`NetherTerrainGenerator` 里是单一
    # `DecorationScheduler.load(manifest)`，按 step 拆成 ALL0..3 / FEATURES4 等阶段执行；
    # 它**不 load `terrain.json`**（那份清单只用于导出与闭合核对，不是第二个 runtime 计划）。
    # 所以这里给 69 个地形槽位写 `skip: true`，等于让这 27 条地形特征在世界里彻底消失 ——
    # 而它们本来就该由这一个调度器执行。`owner` 只说明「这条属于地形类别」，不是「别人会放」。
    compiled_ids = set(report.supported)
    terrain_owned = _terrain_owned_ids(data)
    biomes_schema2: dict[str, dict[str, list[object]]] = {}
    for biome in sorted(report.biome_steps):
        steps = report.biome_steps[biome]
        def entry(ref: str) -> object:
            if ref in compiled_ids:
                return ref                                   # 本移植编译并放置
            if ref.startswith("minecraft:"):
                # 原版条目：保留 id/step/index，由调度器走原生 FeaturePlacer
                return {"id": ref, "native": True}
            if ref in terrain_owned:
                # 可执行：同一份清单会被同一个调度器按 step 执行，形状由
                # `UpstreamTerrainFeatures` 那一套编译（`WorldgenLoader#configuredObject` 的
                # 未知类型分支委托给它）。`owner` 是归属标注，**不是**执行者声明。
                # 全清单 191 处占位里 69 处属于这一类 —— 它们不是缺口，也不是别人的。
                return {"id": ref, "owner": TERRAIN_OWNER}
            # 上游 BN 里本移植侧没人执行的条目：矿归 OrePopulator、以及因用户移除方块停发的植物。
            return {"id": ref, "skip": True}

        biomes_schema2[biome] = {
            str(step): [entry(ref) for ref in steps[step]]
            for step in sorted(steps)
        }

    manifest = {
        "schema": 2,
        "biomes": biomes_schema2,
        "supported": report.supported,
        "skipped": report.skipped,
        # 用户移除方块导致的主动停发（`{id: 原因}`）。不在这两个集合里的上游特征才是真缺口。
        "excluded": report.excluded,
        # 地形类别的条目清单：`{id: "terrain"}`。**只标注类别**（形状由
        # UpstreamTerrainFeatures 编译），这些条目在 `biomes` 里是可执行的，
        # 由同一个调度器按 step 执行；不是「交给另一个 populator」。
        "owners": {ref: TERRAIN_OWNER for ref in sorted(terrain_owned)
                   if ref in _all_refs(report.biome_steps) and ref not in compiled_ids
                   and not ref.startswith("minecraft:")},
        "blockTags": report.block_tags,
        "placedFeatures": report.placed_files,
        "features": report.feature_files,
        "templates": report.templates,
    }
    manifest_path = root / "manifest.json"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
    written.append(f"{OUTPUT_DIR}/manifest.json")

    return {
        "files": len(written),
        "written": written,
        "supported": len(report.supported),
        "skipped": len(report.skipped),
        "skip_reasons": report.skipped_reason_counts,
        "excluded": len(report.excluded),
        "biomes": sum(len(refs) for refs in report.biome_refs.values()),
        "templates": len(report.templates),
    }


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="编译上游 worldgen 植被数据")
    parser.add_argument("--upstream", required=True, help="上游 BetterNether 源码根")
    parser.add_argument("--output", required=True, help="输出根目录（jar 资源根）")
    args = parser.parse_args(argv)

    result = write(Path(args.upstream), Path(args.output))
    print(f"[worldgen] 支持 {result['supported']} 条 placed feature，"
          f"跳过 {result['skipped']} 条，模板 {result['templates']} 个，"
          f"写出 {result['files']} 个文件。")
    for reason, count in result["skip_reasons"].items():
        print(f"[worldgen][跳过] {reason} × {count}")
    if result["excluded"]:
        print(f"[worldgen][停发] 因用户移除方块停发 {result['excluded']} 条特征")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
#: 输出目录（jar 内路径）。**不能**放在 `datapack/` 下：原版会尝试加载这些
#: `wover:*` 特征并在注册表解析阶段中止。
OUTPUT_DIR = "worldgen-upstream"

#: 群系 `features` 数组里植被所在的步序号（vegetal_decoration，10 步中的最后一步）。
VEGETAL_DECORATION_STEP = 9

WORLDGEN = "src/main/generated/data/betternether/worldgen"
TREE_TEMPLATES = "src/main/resources/data/betternether/structure/trees"
WOVER_TAGS = "src/main/generated/data/wover/tags/block"
TEMPLATE_PREFIX = "trees/"

# NBT 标签号（只列模板转换真正用到的几个）。注意 TAG_INT_ARRAY 是 11，不是 3。
TAG_INT = 3
TAG_LIST = 9
TAG_INT_ARRAY = 11


if __name__ == "__main__":
    raise SystemExit(main())

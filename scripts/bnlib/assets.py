#!/usr/bin/env python3
"""从上游 BetterNether 的 assets 抽取 CraftEngine 资源包需要的那部分资产。

## 为什么要有这个模块

`extract_bn.copy_assets()` 只释放了 `models/block` + `textures/block` + 4 张物品贴图。
上游真正的资产面比这宽得多，而且**有一半不在 `src/main/resources` 里** ——
BetterNether 用 Fabric datagen，物品模型、方块模型、blockstates、装备资源、
客户端物品定义全在 `src/main/generated/assets`：

    目录                          resources   generated
    models/block                        627        1304
    models/item                         207         302
    blockstates                         187         472
    items（1.21.4+ 客户端物品定义）       0         714
    equipment（1.21.2+ 装备资源）         0           3
    textures/**                        1376           0

所以「上游真值」是**两棵树的合并视图**，不是任意一棵。合并顺序照上游
`build.gradle` 的 `sourceSets.main.resources { srcDir 'src/main/generated' }`：
generated 先入、resources 后覆盖（上游还有一个 gradle 任务专门断言两棵树不重名，
所以实际上没有冲突，但顺序仍然照抄，避免将来上游改法时行为漂移）。

## 支持到什么程度

CE 的资源包就是一份普通资源包（`resourcepack/assets/<ns>/...`），因此
**凡是原版客户端能读的资源，CE 都能读**。按这条判据分三类：

**一、完整支持（原样复制 + 引用闭合）**

  * `models/item/**`、`models/block/**`（两棵树合并），父级补 `minecraft:` 前缀
  * `textures/block/**`、`textures/item/**`，含成对的 `*.png.mcmeta` 动画元数据
  * `textures/entity/equipment/**` + `equipment/*.json`（1.21.2+ 盔甲资源）
  * `textures/entity/chest/**`（上游 14 个 `minecraft:special` 箱子物品定义引用的
    渲染贴图；那 14 个 `*_chest` 方块本身按移除规则不发布，见下一节，所以这份贴图
    当前包内没有引用者）
  * `items/**`（1.21.4+ 客户端物品定义）—— **改放到 `items/upstream/`**，理由见下
  * `particles/*.json`、`atlases/blocks.json`、`textures/models/**`（旧版盔甲层）

**二、可以表达但与上游不完全相同（必须按「近似」对待）**

  * `items/*.json` 里 12 个 `minecraft:select` 按 `minecraft:trim_material` 选模型：
    原版纹饰要铁匠台打上去，CE 自定义物品没有原版纹饰，所以**只会命中 fallback**。
    文件照样释放（fallback 正确、未来若接纹饰也不用改），但别指望换纹饰换外观。
  * 4 个 `minecraft:select` 按 `minecraft:display_context` 选模型：原版语义，完整可用。
  * 14 个 `minecraft:special` 用 `minecraft:chest` 渲染器：**这 14 个定义不释放** ——
    对应的 `*_chest` 方块是 `ChestBlock`（CE 无 GUI），已按移除规则停发，物品不存在，
    外观也没有主人。判据走 `publication.is_removed_block`，不按 `_chest` 后缀猜
    （`*_chest_boat` 是船、`*_chestplate` 是盔甲，照常释放）。

**三、硬限制（做不了，不要假装做了）**

  * **emissive `_e`**：80 张 `*_e.png` + `shaders/material/*.frag` +
    `materials/*.json` + `materialmaps/block/*.json` + `optifine/dynamic_lights.properties`。
    这是 OptiFine/Continuity 的材质系统，原版客户端只把它当普通贴图。`_e.png`
    照样随包释放（否则连普通贴图都没有），但**不会发光**。
  * **weighted random model**：`blockstates/jungle_plant.json` 用 `"weight"` 做随机
    变体。CE 的 `state.auto_state` 按属性组合分配模型，没有「按权重随机」这一维，
    而且我们**不释放 blockstates**（见下），所以这一类只能退化成单一模型。
  * **模组客户端动画**：`textures/entity/**`（除 equipment/chest）、
    `textures/gui/**`、`textures/minimap/**`、`patterns/**` 是模组 Java 侧实体
    渲染器与界面用的，资源包表达不了「动画」本身 —— 只有贴图没有骨骼。
    不释放，并在报告里列出。

## 为什么不释放 blockstates

CE 给每个方块按 `state.auto_state` / `state.model.generation` **自己生成**
blockstates。再放一份上游的，等于用上游的属性名表去覆盖 CE 的分配：上游写
`facing=north,half=bottom`，CE 注册的是它自己那套状态，两边对不上就会
「方块状态缺少模型」或者干脆渲染错。上游 472 个 blockstates 里还有 57 个
`multipart`、1 个带 `"weight"`，CE 都表达不了。

所以本模块只**读** blockstates 做可达性分析（判断哪些模型真的会被用到），
一个都不写出去。

## 为什么不把 `items/*.json` 放在原位

CE 有两条物品外观路径：

  * `items.<id>.model: <ns>:item/xxx` —— CE 自己生成 `items/<id>.json`
  * `items.<id>.item_model: <ns>:<path>` —— CE 直接引用现成的 `items/<path>.json`

如果上游 `items/<id>.json` 和 CE 为同一个 id 生成的同名文件撞上，后写的赢，
上游那份（带 `select`/`special` 的完整定义）会被静默覆盖。放到 `items/upstream/`
下就没有任何碰撞可能，集成器要用时写 `item_model: <ns>:upstream/<id>` 即可。
报告里给出这个映射，不猜集成器要哪种。

## 上游坏模型：按数据判定，不写死名单

上游有 3 个模型本身是坏的，且没有任何方块引用（死资源）：

  * `stairs_with_top` —— `textures` 里 `"bottom": "#bottom"` 自引用，无同族贴图可换
  * `wall_mushrooms_red_1/2` —— 引用 `blocks/mushroom_red_new`（`blocks/` 复数），
    原版 1.13 起就没有这个目录了

本模块不硬编码这三个名字，而是按两条**可解释的规则**判定，再报告实际命中的文件：
自引用环修不好、或贴图引用指向结构性非法的原版路径。这样上游修好了会自动跟随，
上游新增同类坏文件也会被抓住。
"""

from __future__ import annotations

import json
import re
from collections import deque
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

from bnlib.namespace import NAMESPACE, is_foreign, rewrite_id
from bnlib.publication import is_removed_block

# 上游两棵资源树，按合并优先级排列：先读的先入，后读的覆盖前者。
# 顺序照抄上游 build.gradle 的 sourceSets 声明（generated 先、resources 后）。
ASSET_ROOTS = (
    "src/main/generated/assets",
    "src/main/resources/assets",
)

# 要复制的贴图子目录（相对 `textures/`）。
# 每一项都有明确用途，不是「能拷就拷」：
#   block            方块贴图（含 79 个动画贴图的 .png.mcmeta）
#   item             物品贴图（170 张，此前只拷了 4 张）
#   entity/equipment 1.21.2+ 盔甲层（equipment/*.json 按 <ns>:<id> 解析到这里）
#   entity/chest     上游 14 个 `minecraft:special` 箱子物品定义的渲染贴图
#                    （那 14 个方块已按移除规则停发，定义不释放，贴图暂无引用者）
#   models           旧版盔甲层（cincinnasite_layer_0.png），给未升级的客户端留后路
COPIED_TEXTURE_DIRS = (
    "textures/block",
    "textures/item",
    "textures/entity/equipment",
    "textures/entity/chest",
    "textures/models",
)

# 明确不复制、要在报告里交代原因的目录。
UNSUPPORTED_DIRS = {
    "shaders": "OptiFine/Continuity 材质着色器，原版客户端不读",
    "materials": "OptiFine 材质定义，原版客户端不读",
    "materialmaps": "OptiFine 材质映射，原版客户端不读",
    "optifine": "OptiFine 动态光源配置，原版客户端不读",
    "patterns": "模组客户端书架花纹渲染用，资源包表达不了",
    "sounds": "用户要求移除本包自定义声音，不复制音频或声音事件", 
    "lang": "由 extract_bn.build_langs() 负责，重复写会互相覆盖",
    "textures/gui": "模组界面贴图，CE 界面不读",
    "textures/minimap": "模组小地图贴图，CE 不读",
}

# 会被本模块「全权拥有」的输出子树：写完之后清理掉本次没产出的残留文件，
# 否则重复运行的结果会依赖上一次的输出（不幂等）。
OWNED_SUBTREES = (
    "models",
    "items",
    "equipment",
    "particles",
    "atlases",
    "textures/item",
    "textures/entity/equipment",
    "textures/entity/chest",
    "textures/models",
)

# `textures/block` 与 extract_bn.copy_assets() 共用，只写不清理：
# 清理会把对方写入的文件删掉。
SHARED_SUBTREES = ("textures/block",)

# 原版 1.13 起贴图目录是 `block` / `item`（单数）。模型里写成 `blocks/` `items/`
# 是 1.12 及更早的路径，解析到 `minecraft:` 下必然找不到 —— 这类引用是结构性坏的，
# 不需要原版资源清单就能判定。
LEGACY_PLURAL_DIRS = ("blocks", "items")

# 上游依赖的库模组命名空间 -> 原版。
#
# `wover`（WorldWeaver）是上游依赖的库模组，它的 `block/*` 是**原版同名模型的再声明**
# （`wover:block/composter` 用的就是原版 composter 的 bottom/side/top 槽位，
# 子模型 `anchor_tree_composter` 正好补上这三个槽位）。这些文件在上游仓库里不存在 ——
# 它们在 wover 的 jar 里 —— 所以不处理就有 35 个方块模型（堆肥桶、雕纹书架）缺父级。
#
# 这里改写成原版同名模型：**这是近似，不是等价**。wover 那份如果和原版有出入，
# 我们就丢了那个出入。每次改写都记进报告的 `repaired.library_parents`，可逐条核对。
LIBRARY_NAMESPACE_ALIASES = {"wover": "minecraft"}

# 物品定义里的模型节点可能出现在这些键上。`minecraft:special` 用 `base`，
# `select` 用 `fallback`，`composite` 用 `models`，`range_dispatch` 用 `fallback`。
_MODEL_REF_KEYS = ("model", "base", "fallback")

_ID_TOKEN = re.compile(r"\b([a-z0-9_.\-]+):([a-z0-9_./\-]+)")


def _split_id(ref: str) -> tuple[str, str]:
    """拆 `ns:path`；没有命名空间时按原版约定落到 `minecraft:`。"""
    if ":" in ref:
        namespace, path = ref.split(":", 1)
        return namespace, path
    return "minecraft", ref


def _normalize_ref(ref: str) -> str:
    """把引用收敛到 `ns:path`：补 `minecraft:`、把旧前缀换成当前命名空间。"""
    normalized = rewrite_id(ref)
    if ":" not in normalized:
        return f"minecraft:{normalized}"
    return normalized


def _model_id(namespace: str, relpath: str) -> str:
    """`models/block/agave.json` -> `betternether:block/agave`。"""
    return f"{namespace}:{relpath[len('models/'):-len('.json')]}"


def _texture_id(namespace: str, relpath: str) -> str:
    """`textures/block/agave.png` -> `betternether:block/agave`。"""
    return f"{namespace}:{relpath[len('textures/'):-len('.png')]}"


# --------------------------------------------------------------------------- #
# 合并视图
# --------------------------------------------------------------------------- #


def collect_files(upstream: Path) -> dict[str, dict[str, Path]]:
    """把上游两棵资源树合并成 `namespace -> relpath -> 绝对路径`。

    合并顺序是 `ASSET_ROOTS` 的顺序：generated 先入，resources 覆盖同名文件。
    上游有 gradle 任务断言两棵树不重名，所以正常情况下「覆盖」不会发生；
    这里保留覆盖语义只是为了让行为与上游 `processResources` 一致。
    """
    merged: dict[str, dict[str, Path]] = {}
    for relative_root in ASSET_ROOTS:
        root = upstream / relative_root
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            namespace, _, inner = relative.partition("/")
            if not inner:
                # `assets/<ns>/icon.png` 这类散文件不进任何资产索引。
                continue
            merged.setdefault(namespace, {})[inner] = path
    return merged


@dataclass
class Model:
    """一个模型文件的解析结果。"""

    model_id: str
    relpath: str
    path: Path
    parent: str | None
    textures: dict[str, str]
    payload: dict

    @property
    def namespace(self) -> str:
        return self.model_id.split(":", 1)[0]


@dataclass
class Analysis:
    """一次静态分析的完整结果。`generate()` 与门禁测试都只依赖它。"""

    files: dict[str, dict[str, Path]]
    shipped_namespaces: tuple[str, ...]
    models: dict[str, Model] = field(default_factory=dict)
    texture_paths: dict[str, set[str]] = field(default_factory=dict)
    blockstates: dict[str, Path] = field(default_factory=dict)
    item_definitions: dict[str, Path] = field(default_factory=dict)
    equipment: dict[str, Path] = field(default_factory=dict)
    particles: dict[str, Path] = field(default_factory=dict)
    atlases: dict[str, Path] = field(default_factory=dict)

    # 分析产物
    reachable_models: set[str] = field(default_factory=set)
    reachable_textures: set[str] = field(default_factory=set)
    missing_parents: list[tuple[str, str]] = field(default_factory=list)
    unresolved_textures: list[tuple[str, str]] = field(default_factory=list)
    unrepairable_cycles: list[str] = field(default_factory=list)
    repaired_cycles: list[str] = field(default_factory=list)
    aliased_parents: list[tuple[str, str, str]] = field(default_factory=list)
    weighted_variants: list[str] = field(default_factory=list)
    multipart_blockstates: list[str] = field(default_factory=list)
    item_definition_types: dict[str, int] = field(default_factory=dict)
    item_definition_properties: dict[str, int] = field(default_factory=dict)
    special_renderers: dict[str, int] = field(default_factory=dict)

    @property
    def namespace(self) -> str:
        return NAMESPACE

    def model_ids(self) -> set[str]:
        return set(self.models)


def _referenced_namespaces(files: dict[str, dict[str, Path]], namespaces: list[str]) -> list[str]:
    """找出被主命名空间资产引用、且上游确实有对应目录的旁支命名空间。

    上游 `wover` 有 11 个 `*_planks_particles` 模型被 35 个 betternether 方块模型
    当作 parent 引用。不把 `wover` 一起释放，那 35 个模型全部缺父级。
    这里按引用扫描发现，不写死命名空间名。
    """
    found: set[str] = set()
    for namespace in namespaces:
        for relpath, path in files.get(namespace, {}).items():
            if not relpath.endswith((".json", ".mcmeta")):
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for token_ns, _ in _ID_TOKEN.findall(text):
                if token_ns in files and token_ns not in ("minecraft", *namespaces):
                    found.add(token_ns)
    return sorted(found)


def analyze(upstream: Path) -> Analysis:
    """静态分析上游资产，不写任何文件。

    做四件事：
      1. 合并两棵资源树，建立模型 / 贴图 / blockstates / items / equipment 索引
      2. 修不掉的贴图自引用环与结构性非法引用挑出来（这些模型不释放）
      3. 从「真正的渲染入口」出发做引用闭包，算出可达模型与可达贴图
      4. 统计 CE 表达不了的部分（weighted variant、multipart、select/special）

    渲染入口（roots）取三类，都是 CE 会按名字硬引用模型的地方：
      * `models/item/**` —— CE 物品的 `model:` 直接指向这里
      * `items/*.json` 里 `model` 节点引用的模型 —— `item_model:` 指向这里
      * blockstates 里 variants/multipart 引用的模型 —— CE 生成外观时用到
    """
    files = collect_files(upstream)
    if NAMESPACE not in files:
        raise SystemExit(
            f"找不到上游资源目录：{upstream / ASSET_ROOTS[-1] / NAMESPACE}")

    shipped = [NAMESPACE, *_referenced_namespaces(files, [NAMESPACE])]
    analysis = Analysis(files=files, shipped_namespaces=tuple(shipped))

    # --- 索引 ------------------------------------------------------------- #
    for namespace in shipped:
        analysis.texture_paths[namespace] = {
            relpath[len("textures/"):-len(".png")]
            for relpath in files[namespace]
            if relpath.startswith("textures/") and relpath.endswith(".png")
        }
        for relpath, path in sorted(files[namespace].items()):
            if relpath.startswith("models/") and relpath.endswith(".json"):
                payload = _read_json(path)
                if not isinstance(payload, dict):
                    continue
                parent = payload.get("parent")
                textures = payload.get("textures")
                model_id = _model_id(namespace, relpath)
                analysis.models[model_id] = Model(
                    model_id=model_id,
                    relpath=relpath,
                    path=path,
                    parent=_normalize_ref(parent) if isinstance(parent, str) else None,
                    textures={k: v for k, v in textures.items() if isinstance(v, str)}
                    if isinstance(textures, dict) else {},
                    payload=payload,
                )
            elif relpath.startswith("blockstates/") and relpath.endswith(".json"):
                analysis.blockstates[f"{namespace}:{relpath}"] = path
            elif relpath.startswith("items/") and relpath.endswith(".json"):
                analysis.item_definitions[f"{namespace}:{relpath}"] = path
            elif relpath.startswith("equipment/") and relpath.endswith(".json"):
                analysis.equipment[f"{namespace}:{relpath}"] = path
            elif relpath.startswith("particles/") and relpath.endswith(".json"):
                analysis.particles[f"{namespace}:{relpath}"] = path
            elif relpath.startswith("atlases/") and relpath.endswith(".json"):
                analysis.atlases[f"{namespace}:{relpath}"] = path

    # --- 模型自检：父级 / 贴图 / 环 ---------------------------------------- #
    _alias_library_parents(analysis)

    for model in analysis.models.values():
        if model.parent and model.parent not in analysis.models:
            if _split_id(model.parent)[0] in shipped:
                analysis.missing_parents.append((model.model_id, model.parent))

        _repair_texture_cycles(model, analysis)

        for slot in model.textures:
            resolved, state = _resolve_slot(slot, model.textures)
            if state == "cycle":
                if model.model_id not in analysis.unrepairable_cycles:
                    analysis.unrepairable_cycles.append(model.model_id)
                continue
            if state != "direct" or resolved is None:
                # inherited：父模板靠子模型补槽位，原版语义，不是缺陷。
                continue
            namespace, path = _split_id(resolved)
            if namespace in shipped:
                if path not in analysis.texture_paths.get(namespace, set()):
                    analysis.unresolved_textures.append((model.model_id, slot))
            elif namespace == "minecraft" and path.split("/", 1)[0] in LEGACY_PLURAL_DIRS:
                analysis.unresolved_textures.append((model.model_id, slot))

    # --- 渲染入口与引用闭包 ------------------------------------------------ #
    # 门/活板门在本工程已全局删除（见 publication.REMOVED_CLASSES）。但上游 assets 里
    # 仍留着它们的 item 模型与 blockstates，若照单当根，会沿 parent 边把已经没有任何
    # 使用者的方块门/活板门模型也算成「可达」而写进资源包（实测残留 104 个门模型 +
    # 11 个活板门模型）。这里在选根阶段就剔除，保持「清理多余模型」的目标。
    roots: set[str] = {
        model_id for model_id, model in analysis.models.items()
        if model.relpath.startswith("models/item/")
        and not _removed_shape_model(model.relpath)
    }
    for path in analysis.item_definitions.values():
        if _removed_shape_model(path.name):
            continue
        roots.update(_item_definition_model_refs(_read_json(path)))
    for path in analysis.blockstates.values():
        if _removed_shape_model(path.name):
            continue
        models, weighted, multipart = _blockstate_model_refs(_read_json(path))
        roots.update(models)
        if weighted:
            analysis.weighted_variants.append(path.stem)
        if multipart:
            analysis.multipart_blockstates.append(path.stem)

    analysis.reachable_models, analysis.reachable_textures = _closure(analysis, roots)

    # --- CE 表达不了的物品定义形态 ----------------------------------------- #
    for path in analysis.item_definitions.values():
        payload = _read_json(path)
        node = payload.get("model") if isinstance(payload, dict) else None
        _classify_item_definition(node, analysis)

    return analysis


def _alias_library_parents(analysis: Analysis) -> None:
    """把指向库模组（上游仓库里没有、但按原版同名再声明）的父模型改写到原版。

    判据是「父模型 id 不在合并视图里」+「该命名空间有已知别名」，
    不是按名字硬编码 `composter`/`chiseled_bookshelf`。上游换库或换模型名时，
    只要别名表还在，行为就跟着数据走。
    """
    for model in analysis.models.values():
        parent = model.parent
        if not parent or parent in analysis.models:
            continue
        namespace, path = _split_id(parent)
        alias = LIBRARY_NAMESPACE_ALIASES.get(namespace)
        if not alias:
            continue
        aliased = f"{alias}:{path}"
        model.parent = aliased
        model.payload["parent"] = aliased
        analysis.aliased_parents.append((model.model_id, parent, aliased))


def _read_json(path: Path) -> dict:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return {}
    return payload if isinstance(payload, dict) else {}


def _resolve_slot(slot: str, declared: dict[str, str]) -> tuple[str | None, str]:
    """把一个贴图槽位解析到底，返回 `(最终直连引用, 状态)`。

    状态有三种，区别很重要：

      * `direct`    —— 解析到一个真实贴图引用（`ns:path`），要校验它存不存在
      * `inherited` —— 链尾指向一个**本文件没声明**的槽位。这是原版模型继承的
        正常写法：父模型写 `"particle": "#texture"`，由子模型提供 `texture`。
        不是缺陷，不能当成「缺贴图」报出来（`crop_block` 这类模板模型全靠它）
      * `cycle`     —— `#a -> #b -> #a`。Cubik 导出的半成品模型里很常见（上游靠
        Java 侧补真实贴图，磁盘 JSON 从来不完整）。CE 只做静态解析，遇到环就报
        「存在循环纹理引用」并放弃整个模型，所以必须先判出来。
    """
    seen: set[str] = set()
    current = slot
    while True:
        value = declared.get(current)
        if not isinstance(value, str):
            return None, "inherited"
        if not value.startswith("#"):
            return value, "direct"
        target = value[1:]
        if target == current or target in seen:
            return None, "cycle"
        seen.add(current)
        current = target


def _repair_texture_cycles(model: Model, analysis: Analysis) -> None:
    """把成环的槽位换成同族真实贴图；改不动就留给调用方报告。

    换的候选顺序与 `bnlib.models.break_texture_cycles()` 一致（同名、`_1`、`_side`、
    `_top`、同前缀、同族前缀），但**命名空间正确** —— 那边把 `betternether:block/`
    写死在字符串里，对 `wover` 的模型会拼错。
    """
    if not model.textures:
        return
    cyclic_slots = [slot for slot in model.textures
                    if _resolve_slot(slot, model.textures)[1] == "cycle"]
    if not cyclic_slots:
        return

    namespace = model.namespace
    available = analysis.texture_paths.get(namespace, set())
    stem = model.model_id.split(":", 1)[1].rsplit("/", 1)[-1]
    family = stem.split("_", 1)[0]
    candidates = [
        f"block/{stem}_1", f"block/{stem}", f"block/{stem}_side", f"block/{stem}_top",
        f"item/{stem}", f"item/{stem}_1",
        *sorted(path for path in available if path.startswith(f"block/{stem}_")),
        *sorted(path for path in available if path.startswith(f"block/{family}_")),
        *sorted(path for path in available if path.startswith(f"item/{stem}")),
    ]
    replacement = next((c for c in candidates if c in available), None)
    if replacement is None:
        return

    for slot in cyclic_slots:
        model.textures[slot] = f"{namespace}:{replacement}"
        model.payload.setdefault("textures", {})[slot] = f"{namespace}:{replacement}"
    analysis.repaired_cycles.append(model.model_id)


def _item_definition_model_refs(payload: dict) -> set[str]:
    """递归收集客户端物品定义里引用到的模型 id。

    1.21.4+ 的 `items/*.json` 是一棵模型节点树：`minecraft:model` 指模型，
    `minecraft:select` 有 cases/fallback，`minecraft:special` 有 base，
    `minecraft:composite` 有 models，`minecraft:range_dispatch` 有 fallback。
    统一按「键名 + 递归」收，不逐类型写分支 —— 上游加新类型时不会漏。
    """
    found: set[str] = set()

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key in _MODEL_REF_KEYS and isinstance(value, str):
                    found.add(_normalize_ref(value))
                elif key == "models" and isinstance(value, list):
                    for item in value:
                        if isinstance(item, str):
                            found.add(_normalize_ref(item))
                        else:
                            walk(item)
                else:
                    walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(payload)
    return found


def _blockstate_model_refs(payload: dict) -> tuple[set[str], bool, bool]:
    """收集 blockstate 引用的模型 id，并报告是否带权重 / multipart。

    只读不写：CE 自己生成 blockstates（见模块文档）。这里读它是为了知道
    「哪些模型真的会被方块用到」，否则可达性算不准。
    """
    models: set[str] = set()
    weighted = False
    multipart = "multipart" in payload

    variants = payload.get("variants")
    if isinstance(variants, dict):
        for value in variants.values():
            for entry in (value if isinstance(value, list) else [value]):
                if not isinstance(entry, dict):
                    continue
                if "weight" in entry:
                    weighted = True
                model = entry.get("model")
                if isinstance(model, str):
                    models.add(_normalize_ref(model))
    for case in payload.get("multipart") or []:
        if not isinstance(case, dict):
            continue
        apply = case.get("apply")
        for entry in (apply if isinstance(apply, list) else [apply]):
            if isinstance(entry, dict) and isinstance(entry.get("model"), str):
                models.add(_normalize_ref(entry["model"]))
    return models, weighted, multipart


_REMOVED_SHAPE_PAT = re.compile(
    r"(?:^|_)(door|trapdoor|pressure_plate|boat|raft)(?:_|\.|$)"
)


def _removed_shape_model(name: str) -> bool:
    """门/活板门在 publication 里已全局删除，其模型/blockstate 不应再被写进资源包。"""
    return bool(_REMOVED_SHAPE_PAT.search(name))


def _closure(analysis: Analysis, roots: set[str]) -> tuple[set[str], set[str]]:
    """从渲染入口沿 parent / texture 边做闭包，返回 (可达模型, 可达贴图)。"""
    reachable_models: set[str] = set()
    reachable_textures: set[str] = set()
    queue = deque(sorted(root for root in roots if root in analysis.models))

    while queue:
        model_id = queue.popleft()
        if model_id in reachable_models:
            continue
        model = analysis.models[model_id]
        reachable_models.add(model_id)

        if model.parent and model.parent in analysis.models:
            queue.append(model.parent)
        for slot in model.textures:
            resolved, state = _resolve_slot(slot, model.textures)
            if state != "direct" or resolved is None:
                continue
            reachable_textures.add(_normalize_ref(resolved))

    return reachable_models, reachable_textures


def _classify_item_definition(node: object, analysis: Analysis, depth: int = 0) -> None:
    """统计物品定义里各模型节点的数量与属性，供报告交代近似程度。

    `item_definition_types` 只统计**根节点**（一个文件一种顶层形态，714 个文件就是
    714 次），否则 `select` 里嵌的 11 个 case 会被算成 11 个独立定义，数字对不上
    「上游有 714 个物品定义」这个事实。`select` 属性与 `special` 渲染器则递归统计，
    因为它们才是判断「能不能表达」的依据。
    """
    if isinstance(node, dict):
        node_type = node.get("type")
        if isinstance(node_type, str):
            if depth == 0:
                analysis.item_definition_types[node_type] = (
                    analysis.item_definition_types.get(node_type, 0) + 1)
            if node_type == "minecraft:select":
                prop = node.get("property")
                if isinstance(prop, str):
                    analysis.item_definition_properties[prop] = (
                        analysis.item_definition_properties.get(prop, 0) + 1)
            if node_type == "minecraft:special":
                inner = node.get("model")
                renderer = inner.get("type") if isinstance(inner, dict) else None
                if isinstance(renderer, str):
                    analysis.special_renderers[renderer] = (
                        analysis.special_renderers.get(renderer, 0) + 1)
        for key in ("cases", "fallback", "base", "models"):
            value = node.get(key)
            if isinstance(value, list):
                for item in value:
                    _classify_item_definition(
                        item.get("model") if isinstance(item, dict) else item,
                        analysis, depth + 1)
            elif isinstance(value, dict):
                _classify_item_definition(value, analysis, depth + 1)


# --------------------------------------------------------------------------- #
# 写出
# --------------------------------------------------------------------------- #


def _assert_inside(path: Path, root: Path) -> None:
    """写盘前的硬门禁：任何输出都必须落在资源包根目录内。

    这条断言不是形式主义。资源包是**唯一**会被 CE 与客户端同时读取的目录，
    一旦有文件被写到 `craftengine/<ns>/configuration/` 或 `build/generated/`
    的其他位置，产出的就不再是一份自洽的资源包，而是一堆没人引用的散文件。
    """
    try:
        path.resolve().relative_to(root.resolve())
    except ValueError as error:
        raise SystemExit(
            f"拒绝写到资源包根目录之外：{path}（根目录 {root}）") from error


def _write_bytes(path: Path, data: bytes, root: Path) -> None:
    _assert_inside(path, root)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def _render_model(model: Model, rewritten: set[str]) -> bytes:
    """把模型渲染成写盘字节。

    只在**确实需要改动**时重新序列化，否则原样透传：
      * `parent` 没带命名空间 -> 补 `minecraft:`（CE 按 ResourceLocation 解析，
        解析不到就整个模型作废，回退成统一占位外观 —— 这是历史事故的根因）
      * `parent` 指向库模组、已别名到原版
      * 贴图自引用环被修过

    其余情况保留上游原始字节，diff 上游时能一眼看出哪些文件被动过。
    `rewritten` 由调用方传入「已知被改过的模型 id」集合 —— 别名改写发生在
    `analyze()` 里，`payload` 已经是改后的值，光比对文本看不出差异。
    """
    raw = model.path.read_text(encoding="utf-8")
    payload = dict(model.payload)

    parent_raw = payload.get("parent")
    parent_normalized = _normalize_ref(parent_raw) if isinstance(parent_raw, str) else None
    needs_rewrite = model.model_id in rewritten or (
        parent_normalized is not None and parent_normalized != parent_raw)

    if not needs_rewrite:
        return raw.encode("utf-8")
    if parent_normalized is not None:
        payload["parent"] = parent_normalized
    return (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _render_text_asset(path: Path) -> bytes:
    """JSON 资产原样透传；只在残留旧命名空间前缀时改写。"""
    text = path.read_text(encoding="utf-8")
    rewritten = text
    for known in ("betternether:", "mumu:"):
        if known != NAMESPACE + ":" and known in rewritten:
            rewritten = rewritten.replace(known, NAMESPACE + ":")
    if rewritten != text and is_foreign(rewritten):
        raise SystemExit(f"{path.name}: 改写后仍残留非当前命名空间，拒绝写出引用会断的资产。")
    return rewritten.encode("utf-8")


def _load_shadowed(ce_root: Path) -> set[str]:
    """读 blocks.py 算出的「CE 会自己生成、上游同名子部件模型要避让」名单。

    文件不存在就当作空集：本模块要能独立跑（集成器可能先跑资源再跑方块）。
    存在时**必须**遵守 —— 否则 CE 报「无法生成模型，因为模型文件已存在」，
    然后用了上游那个子部件模型，渲染未必对。
    """
    shadow_file = ce_root.parents[1] / "reports/shadowed-models.txt"
    if not shadow_file.is_file():
        return set()
    return {line.strip() for line in shadow_file.read_text(encoding="utf-8").splitlines()
            if line.strip()}


def _load_synthesized(ce_root: Path) -> list[dict]:
    """读 blocks.py 交来的「透明方块合成模型」清单。

    ## 为什么由本模块落盘，而不是 blocks.py 直接写

    `resourcepack/assets/<ns>/models` 是本模块**全权拥有**的子树（见 `OWNED_SUBTREES`），
    写完之后会清理本次没产出的残留文件。blocks.py 在本模块之前运行，它写进去的模型会被
    当成残留删掉。所以交清单过来，由唯一的写入者落盘。

    ## 这些模型从哪来

    透明共享状态（`transparent: true`）的方块不写方块模型，外观由 `entity_renderer`
    渲染物品模型；而 items 段**只允许写 `path`**，不许写 `generation`。所以那批
    「上游没有方块模型、也没有物品模型」的方块，需要把 CE 的 `generation` 规格
    （parent + textures）在构建期就落成真实 JSON。

    文件不存在就当作空清单：本模块要能独立跑（测试、或先跑资源再跑方块）。
    """
    path = ce_root.parents[1] / "reports/synthesized-models.json"
    if not path.is_file():
        return []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SystemExit(f"{path} 不是合法 JSON：{error}") from error
    models = payload.get("models") if isinstance(payload, dict) else None
    if not isinstance(models, list):
        raise SystemExit(f"{path} 缺少 models 数组，blocks.py 与本模块的契约已漂移。")
    return models


def _excluded_models(analysis: Analysis, shadowed: set[str]) -> dict[str, list[str]]:
    """算出不释放的模型，按原因分组。

    只有「放进去必然报错、且渲染不出来」的才排除：
      * 贴图自引用环修不掉（CE 会报循环纹理引用并放弃整个模型）
      * 贴图引用指向不存在的文件（模型缺纹理，CE 报缺贴图）
      * 撞 CE 自己生成的模型名（会互相覆盖）

    **缺父级不排除**。那是上游自己的缺陷（`nether_mushroom_fence` 的 item 模型
    指向不存在的 `block/nether_mushroom_fence_inventory`），上游同样坏。
    以 upstream 为真值就照原样释放，但要在报告里点名，不能假装它好着。
    """
    unrepairable = set(analysis.unrepairable_cycles)
    unresolved = {model_id for model_id, _ in analysis.unresolved_textures}
    shadow_hits = {
        model_id for model_id in analysis.models
        if model_id.split(":", 1)[1].rsplit("/", 1)[-1] in shadowed
    }
    # 父级指向未释放库模组（bclib 座椅）的模型：方块已不发布（blocks.SEAT_SUFFIXES），
    # 模型留着只会让 CE 逐个报「找不到父模型」。
    missing_lib = {
        model_id for model_id, model in analysis.models.items()
        if isinstance(model.parent, str) and model.parent.startswith("bclib:")
    }
    # 父级被 shadowed 名单拿掉的模型（如 item/smoker -> block/smoker_top）：
    # 父模型由 CE 另行生成或不存在，留着就报缺父级 + 未定义 #particle。
    shadow_ids = {f"{NAMESPACE}:block/{name}" for name in shadowed}
    orphan = {
        model_id for model_id, model in analysis.models.items()
        if isinstance(model.parent, str) and model.parent in shadow_ids
    }
    # 级联：父模型被排除的模型同样排除（item/chair_cincinnasite -> block/chair_cincinnasite）。
    dropped = set(missing_lib)
    while True:
        more = {
            model_id for model_id, model in analysis.models.items()
            if model_id not in dropped and isinstance(model.parent, str) and model.parent in dropped
        }
        if not more:
            break
        dropped |= more
    missing_lib = dropped
    taken = unrepairable | unresolved | shadow_hits
    return {
        "texture_cycle": sorted(unrepairable),
        "unresolved_texture": sorted(unresolved - unrepairable),
        "shadowed_by_ce": sorted(shadow_hits - unrepairable - unresolved),
        "missing_library_parent": sorted(missing_lib - taken),
        "parent_shadowed": sorted(orphan - taken - missing_lib),
    }


def removed_block_item_definitions(analysis: Analysis) -> set[str]:
    """算出「对应方块已经被移除」的客户端物品定义（文件名，含 `.json`）。

    判据只有一条：`publication.is_removed_block()` —— 方块不发布，它的物品定义就没人
    引用。14 个 `*_chest.json` 是 `minecraft:special` + `minecraft:chest` 渲染器，看着
    「能出图」，但那些 `*_chest` 方块本身是 `ChestBlock`（CE 无 GUI），根本不会注册，
    留着只是一份没有主人的外观。同理还有台阶/楼梯/门/栅栏/窗格/压力板等 200 多个。

    **只按方块 id 判**，不按文件名猜：`*_chest_boat`（船）与 `*_chestplate`（盔甲）
    名字里都带 `chest`，但都不是箱子方块，必须照常释放。上游新增方块时这条规则
    跟着 `publication.py` 走，不用回来改名单。
    """
    dropped: set[str] = set()
    for key in analysis.item_definitions:
        namespace, _, relpath = key.partition(":")
        stem = relpath[len("items/"):-len(".json")]
        # 对应方块被移除 → 物品定义没人引用，删。
        # 另外船/筏(*_boat/*_raft)：本工程按用户要求删船，其模型已不释放，物品定义也必须删，
        # 否则 CE 注册了船物品却找不到模型（reload 报 26 个「缺少模型文件」）。
        if is_removed_block(f"{namespace}:{stem}") or _removed_shape_model(stem):
            dropped.add(f"{stem}.json")
    return dropped


def generate(upstream: Path, ce_root: Path, selected: Sequence[str] = ()) -> dict:
    """把上游资产抽取进 CE 资源包，返回报告字典。

    契约（集成器依赖这四条，改动即破坏兼容）：

      * 入参是 `(upstream, ce_root)`，`ce_root` 指 `.../craftengine/<namespace>`
      * **幂等**：同样的上游输入重复运行，产物逐字节一致；本次没产出的旧文件会被清掉
      * **所有输出都在 `ce_root/resourcepack/` 内**，不碰 `configuration/`、
        不碰 `build/generated/` 的其他位置
      * 返回 dict 至少含 `files` / `warnings` / `unsupported`，另附完整分类计数

    `selected`（可选的 id 清单）在本模块**被忽略**：资源包资产按上游全量释放，
    不按注册 id 过滤。理由见模块文档 —— 资产是按「引用闭合」成组生效的，砍掉
    一个看似无关的模型会让别的模型的父级断掉，那种缺失在游戏里表现为紫黑格子，
    比多带几 MB 难查得多。保留这个参数只是为了与其它生成模块的调用形式统一。
    """
    del selected  # 见 docstring：资源包资产不按 id 过滤

    analysis = analyze(upstream)
    pack_root = ce_root / "resourcepack"
    pack_assets = pack_root / "assets"
    _assert_inside(pack_assets, pack_root)

    shadowed = _load_shadowed(ce_root)
    synthesized = _load_synthesized(ce_root)
    excluded = _excluded_models(analysis, shadowed)
    skip = {model_id for group in excluded.values() for model_id in group}
    dropped_definitions = removed_block_item_definitions(analysis)
    rewritten = set(analysis.repaired_cycles) | {
        model_id for model_id, _, _ in analysis.aliased_parents}

    written: dict[Path, None] = {}
    counts = {"models": 0, "textures": 0, "animations": 0, "equipment": 0,
              "item_definitions": 0, "particles": 0, "atlases": 0,
              "synthesized_models": 0}
    upstream_animations = 0

    for namespace in analysis.shipped_namespaces:
        dest = pack_assets / namespace
        files = analysis.files[namespace]
        is_main = namespace == NAMESPACE

        # --- 模型 --------------------------------------------------------- #
        for model_id, model in sorted(analysis.models.items()):
            if model.namespace != namespace or model_id in skip:
                continue
            # 只释放**被引用到的**模型（块状态/物品/物品定义闭包）。上游整份 models/block
            # 里含大量已删除/未发布族的模型（door/trapdoor/slab/stairs/fence/wall/pane/
            # button/plate），它们没有入口引用、纯属孤儿，不再复制，避免资源包臃肿。
            if model_id not in analysis.reachable_models:
                continue
            target = dest / model.relpath
            _write_bytes(target, _render_model(model, rewritten), pack_root)
            written[target] = None
            counts["models"] += 1

        # --- 透明方块的合成模型（blocks.py 的 generation 规格） ------------ #
        # 必须在上面那批之后写：同名时以逐字节相同的上游模型为准毫无意义，但顺序固定才能
        # 保证「同一份输入 → 同一份产物」。这些路径上游本就没有同名文件（blocks.py 只在
        # `has_model` 为假时才登记），不会互相覆盖。
        if is_main:
            for entry in synthesized:
                relpath = entry.get("path")
                textures = entry.get("textures")
                if not isinstance(relpath, str) or not isinstance(textures, dict):
                    raise SystemExit(
                        f"synthesized-models.json 的条目缺 path/textures：{entry!r}"
                    )
                payload: dict = {}
                parent = entry.get("parent")
                elements = entry.get("elements")
                # `parent` 与 `elements` **可以同时出现**：座椅模型（bnlib/furniture.py）
                # 需要 `parent: minecraft:block/block` 拿 display/gui 变换，同时内联 bclib
                # 的盒子几何（父模型在包外，不能只靠 parent）。原版模型格式本来就允许
                # 子模型带 `parent` + 自己的 `elements`（子模型 elements 覆盖父级）。
                if isinstance(parent, str):
                    payload["parent"] = parent
                if isinstance(elements, list):
                    payload["elements"] = elements
                if not isinstance(parent, str) and not isinstance(elements, list):
                    raise SystemExit(
                        f"synthesized-models.json 的条目既没有 parent 也没有 elements：{entry!r}"
                    )
                payload["textures"] = dict(textures)
                target = dest / "models" / f"{relpath}.json"
                _write_bytes(target, (json.dumps(payload, ensure_ascii=False, indent=2)
                                      + "\n").encode("utf-8"), pack_root)
                written[target] = None
                counts["synthesized_models"] += 1

        # --- 贴图 + 动画元数据 -------------------------------------------- #
        for subdir in COPIED_TEXTURE_DIRS:
            for relpath, path in sorted(files.items()):
                if not relpath.startswith(subdir + "/") or not relpath.endswith(".png"):
                    continue
                target = dest / relpath
                _write_bytes(target, path.read_bytes(), pack_root)
                written[target] = None
                counts["textures"] += 1

                # 动画贴图靠同名 `.png.mcmeta` 声明「这是一张竖排多帧图」。
                # 只拷 png 会让动画退化成一条被拉长的静态图，而且日志毫无痕迹。
                meta = path.with_name(path.name + ".mcmeta")
                if meta.is_file():
                    meta_target = target.with_name(target.name + ".mcmeta")
                    _write_bytes(meta_target, meta.read_bytes(), pack_root)
                    written[meta_target] = None
                    counts["animations"] += 1
            if is_main and subdir == "textures/block":
                upstream_animations = sum(
                    1 for relpath in files
                    if relpath.startswith("textures/block/") and relpath.endswith(".png.mcmeta"))

        if not is_main:
            continue

        # --- 装备资源（1.21.2+）------------------------------------------- #
        for key, path in sorted(analysis.equipment.items()):
            relpath = key.split(":", 1)[1]
            target = dest / relpath
            _write_bytes(target, _render_text_asset(path), pack_root)
            written[target] = None
            counts["equipment"] += 1

        # --- 客户端物品定义（1.21.4+）------------------------------------- #
        # 改放到 `items/upstream/`：CE 为 `items.<id>.model` 生成的同名文件不会
        # 和上游的完整定义（select/special）互相覆盖。集成器要用时写
        # `item_model: <ns>:upstream/<id>`。
        for key, path in sorted(analysis.item_definitions.items()):
            inner = key.split(":", 1)[1][len("items/"):]
            # 方块已被移除（台阶/门/栅栏/箱子……）：物品定义也不发。CE 不会注册那个
            # 物品，留着就是一份没有主人的外观 —— 14 个 `*_chest.json` 正是靠这条不释放的。
            if inner in dropped_definitions:
                continue
            # 定义引用的模型被排除了（座椅等），定义本身也不发：留着就是悬空引用。
            try:
                refs = _item_definition_model_refs(json.loads(path.read_text(encoding="utf-8")))
            except (json.JSONDecodeError, OSError):
                refs = []
            if any(ref in skip for ref in refs):
                continue
            target = dest / "items" / "upstream" / inner
            _write_bytes(target, _render_text_asset(path), pack_root)
            written[target] = None
            counts["item_definitions"] += 1

        # --- 粒子与图集 --------------------------------------------------- #
        for key, path in sorted(analysis.particles.items()):
            target = dest / key.split(":", 1)[1]
            _write_bytes(target, _render_text_asset(path), pack_root)
            written[target] = None
            counts["particles"] += 1
        for key, path in sorted(analysis.atlases.items()):
            target = dest / key.split(":", 1)[1]
            _write_bytes(target, _render_text_asset(path), pack_root)
            written[target] = None
            counts["atlases"] += 1

        # --- 清理本次没产出的残留 ----------------------------------------- #
        for subdir in (*OWNED_SUBTREES, *SHARED_SUBTREES):
            directory = dest / subdir
            if not directory.is_dir():
                continue
            owned = subdir in OWNED_SUBTREES
            for stale in sorted(directory.rglob("*")):
                if not stale.is_file() or stale in written:
                    continue
                # `textures/block` 与 extract_bn 共用：只补不删，避免删掉对方写的文件。
                if not owned:
                    continue
                _assert_inside(stale, pack_root)
                stale.unlink()

    # --- 门禁 -------------------------------------------------------------- #
    if counts["textures"] == 0:
        raise SystemExit("没有拷贝到任何贴图，CE 的方块会全部渲染成紫黑格子。")
    if counts["models"] == 0:
        raise SystemExit("没有拷贝到任何模型：CE 的 items.model 是硬引用，物品会没有外观。")
    if upstream_animations and counts["animations"] == 0:
        raise SystemExit(
            f"上游有 {upstream_animations} 个动画贴图元数据，却一个都没拷贝："
            "动画会退化成拉长的静态图。")

    return _report(analysis, counts, excluded, shadowed, upstream_animations, pack_root,
                   written, dropped_definitions)


def _report(
    analysis: Analysis,
    counts: dict[str, int],
    excluded: dict[str, list[str]],
    shadowed: set[str],
    upstream_animations: int,
    pack_root: Path,
    written: dict[Path, None],
    dropped_definitions: set[str] | None = None,
) -> dict:
    """组装报告。键名是给集成器与门禁测试用的稳定契约。"""
    files = analysis.files
    upstream_counts = {
        "models": sum(1 for ns in analysis.shipped_namespaces for r in files[ns]
                      if r.startswith("models/") and r.endswith(".json")),
        "textures": sum(1 for ns in analysis.shipped_namespaces for r in files[ns]
                        if r.startswith("textures/") and r.endswith(".png")),
        "animations": upstream_animations,
        "blockstates": len(analysis.blockstates),
        "item_definitions": len(analysis.item_definitions),
        "equipment": len(analysis.equipment),
        "particles": len(analysis.particles),
        "atlases": len(analysis.atlases),
    }

    reachable_item_models = sorted(
        model_id for model_id in analysis.reachable_models
        if analysis.models[model_id].relpath.startswith("models/item/"))

    written_paths = sorted(path.relative_to(pack_root).as_posix() for path in written)
    hard_limits = _hard_limits(analysis)

    return {
        "namespace": NAMESPACE,
        "dest": f"resourcepack/assets/{NAMESPACE}",
        "namespaces": list(analysis.shipped_namespaces),
        "upstream": upstream_counts,
        "copied": dict(counts),
        "files": len(written_paths),
        "written": written_paths,
        "reachable": {
            # 只算实际释放的模型：被排除的模型不能算作「可达」。
            "models": len(analysis.reachable_models
                          - {m for group in excluded.values() for m in group}),
            "textures": len(analysis.reachable_textures),
            "item_models": len(reachable_item_models),
            "unreachable_models": sorted(analysis.model_ids() - analysis.reachable_models),
        },
        "excluded": excluded,
        "upstream_defects": {
            "missing_parents": sorted(
                f"{model_id} -> {parent}" for model_id, parent in analysis.missing_parents),
        },
        "repaired": {
            "texture_cycles": sorted(analysis.repaired_cycles),
            "library_parents": [
                f"{model_id}: {original} -> {aliased}"
                for model_id, original, aliased in sorted(analysis.aliased_parents)
            ],
        },
        "blockstates": {
            "analyzed": len(analysis.blockstates),
            "copied": 0,
            "reason": "CE 按 state.auto_state 自行生成 blockstates，释放上游的会与 CE 的状态表冲突",
            "weighted_variants": sorted(analysis.weighted_variants),
            "multipart": len(analysis.multipart_blockstates),
        },
        "item_definitions": {
            "types": dict(sorted(analysis.item_definition_types.items())),
            "select_properties": dict(sorted(analysis.item_definition_properties.items())),
            "special_renderers": dict(sorted(analysis.special_renderers.items())),
            "relocated_to": "items/upstream/",
            "ce_reference": f"{NAMESPACE}:upstream/<id>",
            # 方块已被移除、因此定义也不发布的那批：留着只是没人引用的外观。
            "dropped_removed_blocks": sorted(dropped_definitions or ()),
        },
        "equipment_ids": sorted(
            key.split(":", 1)[1][len("equipment/"):-len(".json")]
            for key in analysis.equipment),
        "hard_limits": hard_limits,
        "warnings": _warnings(analysis, excluded, dropped_definitions),
        "unsupported": sorted(
            [limit["id"] for limit in hard_limits] + list(UNSUPPORTED_DIRS)),
        "shadowed_by_ce": sorted(shadowed),
    }


def _warnings(analysis: Analysis, excluded: dict[str, list[str]],
              dropped_definitions: set[str] | None = None) -> list[str]:
    """把「需要人看一眼」的事写成短句。不是错误，但绝不能静默。

    `generate()` 的成功返回只说明「写完了」，不说明「和模组一样」。差别全在这里。
    """
    messages: list[str] = []

    for reason, names in sorted(excluded.items()):
        if names:
            messages.append(f"未释放 {len(names)} 个模型（{reason}）：{', '.join(names)}")

    if dropped_definitions:
        messages.append(
            f"未释放 {len(dropped_definitions)} 个客户端物品定义：对应方块已按 "
            "publication.is_removed_block 移除，CE 不会注册那个物品，外观留着没有主人"
            "（明细见 item_definitions.dropped_removed_blocks）")

    for model_id, parent in analysis.missing_parents:
        messages.append(f"上游缺陷：{model_id} 的父模型 {parent} 不存在，按上游原样释放")

    if analysis.aliased_parents:
        messages.append(
            f"{len(analysis.aliased_parents)} 个模型的父级指向上游未提供的库模组模型，"
            "已改写为原版同名模型（近似，非等价），明细见 repaired.library_parents")

    if analysis.weighted_variants:
        messages.append(
            f"blockstate 权重随机变体无法表达：{', '.join(sorted(analysis.weighted_variants))}")

    if analysis.multipart_blockstates:
        messages.append(
            f"{len(analysis.multipart_blockstates)} 个 multipart blockstate 无法表达，"
            "外观由 CE 的状态分配决定")

    if analysis.unrepairable_cycles:
        messages.append(
            f"贴图自引用环修不掉：{', '.join(sorted(analysis.unrepairable_cycles))}")

    return messages


def _hard_limits(analysis: Analysis) -> list[dict[str, str]]:
    """列出「CE/原版资源包表达不了」的部分，连同实际计数与安全的近似做法。

    这些不是待办，是能力边界。写清楚是为了避免下游误以为「已经和模组一样」。
    """
    files = analysis.files
    namespace_files = files.get(NAMESPACE, {})

    def count(prefix: str, suffix: str) -> int:
        return sum(1 for relpath in namespace_files
                   if relpath.startswith(prefix) and relpath.endswith(suffix))

    emissive = count("textures/block/", "_e.png")
    entity = count("textures/entity/", ".png") - count("textures/entity/equipment/", ".png") \
        - count("textures/entity/chest/", ".png")
    gui = count("textures/gui/", ".png")
    minimap = count("textures/minimap/", ".png")

    limits = [
        {
            "id": "emissive_shader",
            "what": "自发光材质（OptiFine/Continuity 材质系统）",
            "evidence": (f"{emissive} 张 *_e.png、{count('shaders/', '.frag')} 个材质着色器、"
                         f"{count('materials/', '.json')} 个材质定义、"
                         f"{count('materialmaps/', '.json')} 个材质映射、"
                         f"{count('optifine/', '.properties')} 个动态光源配置"),
            "why": "原版客户端不读 shaders/materials/materialmaps/optifine，只把 *_e.png 当普通贴图",
            "approximation": "*_e.png 照常随包释放，保证有贴图；但不会发光，不要宣称等价",
        },
        {
            "id": "weighted_random_model",
            "what": "blockstate 按权重随机选模型",
            "evidence": f"{len(analysis.weighted_variants)} 个：{', '.join(analysis.weighted_variants) or '无'}",
            "why": "CE 的 state.auto_state 只按属性组合分配模型，没有权重随机这一维",
            "approximation": "退化为单一模型；具体用哪个由 CE 的状态分配决定，不由本模块决定",
        },
        {
            "id": "mod_client_animation",
            "what": "模组客户端实体/界面渲染与动画",
            "evidence": (f"textures/entity 非装备贴图 {entity} 张、textures/gui {gui} 张、"
                         f"textures/minimap {minimap} 张、patterns "
                         f"{count('patterns/', '.json')} 个"),
            "why": "动画在模组 Java 渲染器里，资源包只有贴图没有骨骼与关键帧",
            "approximation": "不释放；这些资产对 CE 无意义，释放只会让包变大",
        },
        {
            "id": "armor_trim_select",
            "what": "物品模型按盔甲纹饰材质切换",
            "evidence": f"{analysis.item_definition_properties.get('minecraft:trim_material', 0)} 个 select",
            "why": "原版纹饰要铁匠台打到原版盔甲上，CE 自定义物品拿不到纹饰组件",
            "approximation": "文件照常释放，实际只命中 fallback（未打纹饰的模型）",
        },
    ]
    if analysis.item_definition_types.get("minecraft:special"):
        limits.append({
            "id": "special_renderer",
            "what": "客户端硬编码特殊渲染器",
            "evidence": (f"{analysis.item_definition_types['minecraft:special']} 个，"
                         f"渲染器 {', '.join(sorted(analysis.special_renderers)) or '无'}"),
            "why": "渲染器由客户端硬编码，能出图但上下文与上游不同",
            "approximation": ("对应方块已被移除的定义不再释放（14 个 `*_chest.json` 即属此列，"
                              "见 item_definitions.dropped_removed_blocks）；其余 special "
                              "照常释放（含所需贴图），但不要宣称与上游方块物品完全一致"),
        })
    return limits


def summarize(report: dict) -> list[str]:
    """把报告压成几行给构建日志用。集成器可直接 print 每一行。"""
    upstream = report["upstream"]
    copied = report["copied"]
    reachable = report["reachable"]
    lines = [
        f"[CE/assets] 上游原件：模型 {upstream['models']}、贴图 {upstream['textures']}、"
        f"动画元数据 {upstream['animations']}、物品定义 {upstream['item_definitions']}、"
        f"装备 {upstream['equipment']}、blockstates {upstream['blockstates']}（只读分析）",
        f"[CE/assets] 已释放：模型 {copied['models']}、贴图 {copied['textures']}、"
        f"动画元数据 {copied['animations']}、装备 {copied['equipment']}、"
        f"物品定义 {copied['item_definitions']}、粒子 {copied['particles']}、图集 {copied['atlases']}",
        f"[CE/assets] 可达：模型 {reachable['models']}、贴图 {reachable['textures']}、"
        f"物品模型 {reachable['item_models']}",
    ]
    excluded = report["excluded"]
    for reason, names in excluded.items():
        if names:
            lines.append(f"[CE/assets] 未释放（{reason}）：{len(names)} 个 -> {', '.join(names[:6])}")
    dropped = report["item_definitions"].get("dropped_removed_blocks") or []
    if dropped:
        lines.append(f"[CE/assets] 未释放物品定义（方块已移除）：{len(dropped)} 个 -> "
                     f"{', '.join(dropped[:6])}")
    for defect in report["upstream_defects"]["missing_parents"]:
        lines.append(f"[CE/assets] 上游缺陷（照原样释放）：{defect}")
    for limit in report["hard_limits"]:
        lines.append(f"[CE/assets] 硬限制 {limit['id']}：{limit['what']}（{limit['evidence']}）")
    return lines

#!/usr/bin/env python3
"""生成方块发布契约门禁：校验 extract_bn 产物里的**语义**，而不是字符串出现过没有。

## 为什么要在生成产物上再查一遍

生成器 `scripts/bnlib/blocks.py` 已经把上游真值翻译成 CraftEngine 配置，但它自己
的测试只能覆盖被点名的那几个方块。真实事故是「没被点名的那个」：

  * 空手能挖的木头被写成 `require_correct_tools: true` —— 玩家空手挖半天没掉落；
  * 多状态方块只声明了部分 `variants` —— 某些朝向默默退回第一个外观；
  * 藤蔓漏了 `minecraft:climbable`，或者写了标签却仍借 `sugar_cane` 当视觉载体 ——
    前者缺服务端 holder 的 tag，后者**客户端**根本不会起攀（CE 给原版客户端下发的
    正是载体状态），两种都是「配置看着可爬、玩家实际贴不住」；
  * 贴图有全透明像素、却挂在 `auto_state: solid`（不透明宿主）上 —— 透明处画成黑块；
  * 已经移除的台阶/楼梯仍被 item / worldgen / 结构引用 —— 注册表解析阶段直接中止。

这些全部是**产物层面**的事实，必须读产物才能发现。本脚本读三样东西做交叉核对：

  1. `craftengine/betternether/configuration/blocks/*.yml` —— 发布出去的方块配置；
  2. `craftengine/betternether/resourcepack/assets/**` —— 模型与贴图（透明判定用）；
  3. 上游 `upstream/BetterNether` —— 通过 `bnlib.props` 读取的权威真值
     （`block_properties.json` / `mineable/*` / `climbable` 标签）。

## 判定原则

**不按名字猜。** 每条规则都必须能指回一个数据源或一条 CE 文档语义：

| 规则 | 真值来源 |
|---|---|
| 挖掘门槛 | 上游 `destroyTime` / `block_registrations` 的 `requiresTool` / `needs_*_tool` 标签 |
| 方向覆盖 | CE 文档「变体匹配规则」：未列出的属性=通配，未匹配=回退第一个外观 |
| 藤蔓可爬 | 上游 `data/minecraft/tags/block/climbable.json`（标签成员）+ 服务端 jar 同名标签（哪些载体真的可爬）|
| 删除引用 | `bnlib.publication.is_removed_block` |
| 透明实心 | 模型实际引用的 PNG 的 `min_alpha`（任一像素 alpha < 255 即算带 alpha）+ CE `auto_state` 组表 |
| 植物支撑 | `bnlib.support.load_support_rules` 读上游 `.addTrait(NetherSurvival.xxx())` + `canSurvive` 方向 + 标签成员 |
| 植物载体 | 上游 `src/main/generated/block_shapes.txt` 的逐状态 `collision=` + 本脚本里显式写死的池语义表 |
| 池槽位预算 | `bnlib.ce_pool_ledger` 的余量账本（cap/used/free）+ `bnlib.blocks.POOL_BUDGET` 的本期额度 + 产物里 `auto_state` 的实际占用 |

规则一旦缺少可核对的真值，脚本会把它明确标成 `UNIMPLEMENTED` 并以非零退出码结束，
而不是当成通过（宁可红，也不伪绿）。

植物支撑里「上游规则受阻（例如 `wover:*` 公共标签的成员在本仓库不可见）」的方块**不会**
走 `UNIMPLEMENTED`：它们按 `plant_support.blocked` 判红并打印受阻原因 —— 发布一个
「看着能长、其实永远长不出来」的植物，比构建失败严重得多。

植物/装饰改成「真实方块状态 + 模型」之后，碰撞箱与遮挡都由**借用的原版状态**决定，
于是多了两个最坏的结果：植物变成实心障碍、透过植物看到地下。`plant_states.*` 这组规则
只做一件事：把上游 `collision=` 真值与产物里的载体池用显式语义表对上，不按名字猜。

## 用法

    python tools/verify_block_contracts.py
    python tools/verify_block_contracts.py --generated plugin/build/generated/bn --verbose
    python tools/verify_block_contracts.py --allow-unimplemented   # 调试用：未实现规则降级为警告

退出码：0 全通过；1 有违规；2 有未实现规则（默认即失败）。
"""

from __future__ import annotations

import argparse
import itertools
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Mapping

import yaml

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "scripts") not in sys.path:
    sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import props, publication, transparency  # noqa: E402

DEFAULT_GENERATED = ROOT / "plugin/build/generated/bn"
DEFAULT_UPSTREAM = ROOT / "upstream/BetterNether"

#: seed / 世界生成会按属性取值的方块 id 里，这里只关心「方向类」属性的可取值。
#: 取值来源：CE 文档 docs/blocks.md「内部状态数量速查」与「属性类型」各节。
TYPE_VALUES: Mapping[str, tuple[str, ...]] = {
    "boolean": ("true", "false"),
    "axis": ("x", "y", "z"),
    "direction": ("north", "south", "east", "west", "up", "down"),
    "horizontal_direction": ("east", "south", "west", "north"),
    "single_block_half": ("top", "bottom"),
    "double_block_half": ("upper", "lower"),
    "hinge": ("left", "right"),
    "slab_type": ("top", "bottom", "double"),
    "stairs_shape": ("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
}

#: CE `auto_state` 组里有**不透明渲染**的那几个（docs/blocks.md「可用的 auto_state 组」：
#: 音符盒 + 蘑菇方块 = 完整方块）。树叶组 / 甘蔗 / 仙人掌 / 藤蔓 / 绊线都是 cutout，
#: 放在它们上面不会把 alpha 像素画成实心。
OPAQUE_HOSTS: frozenset[str] = frozenset({
    "solid", "note_block", "mushroom_stem",
    "red_mushroom_block", "brown_mushroom_block", "mushroom",
})

#: 「带 alpha」的判据：**任一像素 alpha < 255**。
#: 不设比例阈值：半透明玻璃只有 1 个半透明像素也算透明，而把半透明贴图交给不透明宿主
#: 会把它画成实心（混色丢失）。判据由 `bnlib.transparency.inspect_texture` 的
#: `min_alpha` 给出，与生成器判定透明度用的是同一份事实。

#: 方块 id 的扫描正则：worldgen JSON / 结构定义里都可能出现，命名空间写死本包。
BLOCK_ID_PATTERN = re.compile(r"betternether:[a-z0-9_]+")

#: 可爬判定只认这个原版方块标签（`LivingEntity#onClimbable()` 读的就是它）。
#: 与 `scripts/bnlib/blocks.VINE_CLIMBABLE_TAG` 同值；这里不 import blocks ——
#: 那是个 160KB 的生成器，门禁只需要这一个常量，不该被它的导入副作用绑架。
CLIMBABLE_TAG = "minecraft:climbable"

#: 真正可爬的 `auto_state` 组（组名 -> 该组分到的原版方块）。
#:
#: 真值：`mucore-26.3` 服务端 jar 的 `data/minecraft/tags/block/climbable.json` 只有
#: ladder / vine / scaffolding / weeping_vines(+`_plant`) / twisting_vines(+`_plant`) /
#: cave_vines(+`_plant`) —— **没有** sugar_cane。CE 文档「可用的 auto_state 组」里对应
#: 这三组的组名就是下面三个（jar 的 `AutoStateGroup` 同时接受单数/复数写法）。
#:
#: 为什么载体必须对：`onClimbable()` 在**客户端**也跑一遍，客户端读的是自己世界里那个
#: 方块状态 —— 而 CE 给原版客户端下发的正是视觉载体。载体是甘蔗，玩家就爬不上去。
CLIMB_HOSTS: Mapping[str, str] = {
    "vine": "minecraft:vine",
    "cave_vine": "minecraft:cave_vines",
    "weeping_vine": "minecraft:weeping_vines",
    "twisting_vine": "minecraft:twisting_vines",
}

#: 显式写原版状态（`auto_state: "minecraft:cave_vines[age=5,berries=false]"`）时，
#: 这些方块本身就在 climbable 标签里。
CLIMB_VANILLA_BLOCKS: frozenset[str] = frozenset({
    "minecraft:cave_vines", "minecraft:cave_vines_plant",
    "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
    "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
    "minecraft:vine", "minecraft:ladder", "minecraft:scaffolding",
})

#: 可爬方块共用的宿主 id（与 `scripts/bnlib/blocks.CLIMB_CARRIER_ID` 同值）。
#: 只有上游 climbable 成员才允许借这个 id：借了就等于把方块变成可爬。
CLIMB_SHARED_ID = "climbable"


#: 扫描删除引用时当成文本读的产物后缀；`.nbt` 是二进制，交给 tools/verify_structures.py。
TEXT_SUFFIXES = frozenset({".json", ".yml", ".yaml", ".mcmeta", ".txt", ".md"})

#: 运行时真正会被加载的产物子树。
#:   * `craftengine/**`  —— CE 配置 + 资源包；
#:   * `datapack/**`、`datapacks/**` —— 数据包（worldgen、结构）。
#: `reports/**` 是构建期报告（processResources 明确 exclude），`worldgen-upstream/**`
#: 是迁移输入镜像，两者都不参与运行期解析，扫进来只会把「没人读的文件」报成阻塞项。
RUNTIME_SUBTREES = ("craftengine", "datapack", "datapacks")

#: 单文件扫描上限：超过这个大小的文件不当文本读，避免把大 hash 清单读进内存。
MAX_SCAN_BYTES = 4 * 1024 * 1024

#: 「按原版玻璃对齐挖掘手感」的显式豁免表：方块 id → (期望硬度, 期望工具门槛, 原因)。
#:
#: 上游这些方块是用 `replacePropertiesWithCopy(Blocks.OBSIDIAN / CINCINNASITE_BLOCK)`
#: 整块拷贝出来的，于是把源方块的 destroyTime（50 / 3）和 `requiresTool` 一起继承，
#: 但运行时材质由 `GlassBlockTrait` 决定，外观就是玻璃。结果是「看起来是玻璃，
#: 空手挖十几秒还挖不掉」。用户要求空手能挖，产物按原版 `minecraft:glass`（0.3、无工具）对齐。
#:
#: 三条约束，缺一条这条豁免就不成立：
#:   1. 只列 id，禁止用后缀/前缀模式匹配 —— 新方块必须有人显式加进来；
#:   2. 数值写死（不是「取原版值」），产物一旦又变回 50 会立刻报红；
#:   3. 抗爆 `resistance` 不在豁免范围：obsidian_glass 仍是 1200（防爆玻璃是有意保留的特性）。
GLASS_MINING_ALIGNMENT: Mapping[str, tuple[float, int, str]] = {
    "betternether:obsidian_glass": (
        0.3, 0, "上游拷贝黑曜石属性，材质是玻璃；按原版玻璃 0.3、空手可挖（保留抗爆 1200）"),
    "betternether:blue_obsidian_glass": (
        0.3, 0, "上游拷贝黑曜石属性，材质是玻璃；按原版玻璃 0.3、空手可挖（保留抗爆 1200）"),
    **{
        f"betternether:quartz_glass{suffix}": (
            0.3, 0, "上游拷贝铌钙石块属性，材质是玻璃；按原版玻璃 0.3、空手可挖")
        for suffix in (
            "", "_black", "_blue", "_brown", "_cyan", "_gray", "_green", "_light_blue",
            "_light_gray", "_lime", "_magenta", "_orange", "_pink", "_purple", "_red",
            "_white", "_yellow",
        )
    },
    **{
        f"betternether:quartz_glass_framed{suffix}": (
            0.3, 0, "上游拷贝铌钙石块属性，材质是玻璃；按原版玻璃 0.3、空手可挖")
        for suffix in (
            "", "_black", "_blue", "_brown", "_cyan", "_gray", "_green", "_light_blue",
            "_light_gray", "_lime", "_magenta", "_orange", "_pink", "_purple", "_red",
            "_white", "_yellow",
        )
    },
}

# --------------------------------------------------------------- 植物/装饰载体契约
#
# 植物与异形装饰正在从「`transparent: true` + `entity_renderer`（展示实体）」改成
# 「真实方块状态 + 模型」。改完之后**碰撞箱与遮挡都由借用的原版状态决定**，CE 不再兜底：
#
#   * 借 `note_block` / `solid`（音符盒 + 蘑菇方块族）→ 完整方块 + SOLID 渲染：会遮挡
#     剔除相邻面，而十字/细长模型填不满方块 —— 玩家透过植物看到地下（**透视**）；
#   * 借树叶池（`*_leaves`）→ cutout 不遮挡，但**满格碰撞**：一株花把玩家挡住；
#   * 借 `cactus` → 原版仙人掌伤害贴着的实体；借 `pressure_plate` → 输出红石信号。
#
# 上游真值在 `upstream/BetterNether/src/main/generated/block_shapes.txt`：逐状态一行的
# `collision=`。本包收录的方块里 **42 个 empty、14 个 partial、0 个满格** —— 也就是说
# 没有任何一株植物该挡玩家。下面几张表把「载体池 → 碰撞/遮挡语义」写死成显式常量，
# 不按池名/方块名猜（`if 'plant' in name` 这类判定一律不用）。

#: 上游逐状态碰撞真值文件（相对 upstream 根目录）。
COLLISION_TRUTH_FILE = "src/main/generated/block_shapes.txt"

#: `block_shapes.txt` 里「整个方块」的字面写法。
FULL_COLLISION_BOX = "[0,0,0|1,1,1]"

#: 逐状态真值行的解析式：`betternether:xxx[props]  shape=… collision=…`。
COLLISION_TRUTH_PATTERN = re.compile(
    r"^(betternether:[a-z0-9_]+)(?:\[[^\]]*\])?\s+shape=\S+\s+collision=(\S+)\s*$")

#: **无碰撞**载体池白名单：组名 → 该组借用的原版方块（「无碰撞」的证明对象）。
#: 组名逐字取 CE 文档「可用的 auto_state 组」；绊线的三个组共用同一批绊线状态。
#:
#: 「无碰撞」的判据是 `scripts/ce_patch/collstates.tsv` 的逐状态取证（`collEmpty=true`
#: 且 `canOcclude=false`），不是「看着像植物」：tripwire 128 / sugar_cane 16 / kelp 26 /
#: 三种藤蔓各 26+ 个状态全为空碰撞。
#: `chorus` **不在**这里：它有碰撞箱（10/16 全高柱），见 {@link PARTIAL_COLLISION_POOLS}。
#:
#: ## 只许写 CE `AutoStateGroup` 枚举里存在的组名
#:
#: `auto_state` 是**固定枚举**（`net/momirealms/…/block/AutoStateGroup`），不是自由组名。
#: `vine`/`sculk_vein`/`resin_clump`/`dead_coral`/`chorus_plant` 这些名字**都不存在**，
#: 写进产物只会让 CE 报「无效的常量」+ 该方块完全没有外观（2026-10-28 实测 37 个植物全空）。
#: 合法值与别名见 {@link KNOWN_AUTO_STATE_GROUPS} 与 `plant_states.known_auto_state_group`。
COLLISION_FREE_POOLS: Mapping[str, str] = {
    "tripwire": "minecraft:tripwire",
    "lower_tripwire": "minecraft:tripwire",
    "higher_tripwire": "minecraft:tripwire",
    "sapling": "minecraft:oak_sapling",
    "sugar_cane": "minecraft:sugar_cane",
    "kelp": "minecraft:kelp",
    "weeping_vine": "minecraft:weeping_vines",
    "twisting_vine": "minecraft:twisting_vines",
    "cave_vine": "minecraft:cave_vines",
    # 压力板：`StateCollisionProbe` 实测两种压力板各 16 个状态
    # `collEmpty=true, canOcclude=false` —— 原版压力板只有**有实体压着**时才产生碰撞，
    # 空世界查询返回空形状。代价是踩上去会输出红石信号（见 DANGEROUS_POOLS 的说明）。
    "pressure_plate": "minecraft:light_weighted_pressure_plate",
}

#: 显式写原版状态（`state: "minecraft:xxx[...]"`）时算作**无碰撞**的方块。
#: 刻意逐个列：`ladder` / `scaffolding` 虽然可爬，但有碰撞箱，**不在这里**。
COLLISION_FREE_VANILLA_BLOCKS: frozenset[str] = frozenset({
    "minecraft:tripwire",
    "minecraft:sugar_cane",
    "minecraft:kelp", "minecraft:kelp_plant",
    "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
    "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
    "minecraft:cave_vines", "minecraft:cave_vines_plant",
    "minecraft:vine",
    "minecraft:sculk_vein", "minecraft:resin_clump",
    "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
    "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
    "minecraft:cherry_sapling", "minecraft:mangrove_propagule", "minecraft:pale_oak_sapling",
    "minecraft:nether_sprouts", "minecraft:crimson_roots", "minecraft:warped_roots",
    "minecraft:dead_brain_coral", "minecraft:dead_bubble_coral", "minecraft:dead_fire_coral",
    "minecraft:dead_horn_coral", "minecraft:dead_tube_coral",
    "minecraft:dead_brain_coral_fan", "minecraft:dead_bubble_coral_fan",
    "minecraft:dead_fire_coral_fan", "minecraft:dead_horn_coral_fan",
    "minecraft:dead_tube_coral_fan",
    # 压力板：实测 16 状态 collEmpty=true、canOcclude=false（只有实体压着才有碰撞）。
    # 木质/石质压力板钉 heavy_weighted_pressure_plate[power=N]，算无碰撞。
    "minecraft:light_weighted_pressure_plate",
    "minecraft:heavy_weighted_pressure_plate",
})

#: **满格碰撞但不遮挡**的载体池（树叶族：cutout 渲染 + 整格碰撞箱）。
#: 植物借它不会透视，但会变成实心障碍 —— 与透视同样严重，只是现象相反。
FULL_COLLISION_POOLS: Mapping[str, str] = {
    "non_tintable_leaves": "minecraft:oak_leaves",
    "tintable_leaves": "minecraft:oak_leaves",
    "leaves": "minecraft:oak_leaves",
}

#: 显式写原版状态时算作**满格碰撞**的方块（= 遮挡池那几族 + 树叶族）。
FULL_COLLISION_VANILLA_BLOCKS: frozenset[str] = frozenset({
    "minecraft:note_block", "minecraft:mushroom_stem",
    "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block",
    "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
    "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
    "minecraft:mangrove_leaves", "minecraft:cherry_leaves", "minecraft:azalea_leaves",
    "minecraft:flowering_azalea_leaves", "minecraft:pale_oak_leaves",
})

#: **会遮挡剔除**的载体池：完整方块 + SOLID 渲染，与透明实心规则同一组宿主。
#: 植物借它 → 相邻面被剔除而模型填不满方块 → 透视。与「贴图带 alpha」无关，所以
#: `transparency.solid_appearance` 抓不到它（那条规则只在贴图带 alpha 时才说话）。
#:
#: `cactus` 在这里有硬取证：`StateCollisionProbe` 跑出来 **16 个状态全部
#: `canOcclude=true`**（`scripts/ce_patch/collstates.tsv`）。所以它不能当植物载体，
#: 不论上游是不是仙人掌 —— 借它就是把邻面挖掉。
OCCLUDING_POOLS: frozenset[str] = OPAQUE_HOSTS | {"cactus"}

#: 显式写原版状态时的遮挡载体。
OCCLUDING_VANILLA_BLOCKS: frozenset[str] = frozenset({
    "minecraft:note_block", "minecraft:mushroom_stem",
    "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block",
    # 仙人掌原版状态同样 `canOcclude=true`（见上）。
    "minecraft:cactus",
})

#: **部分碰撞**载体池：池名 → 该组借用的原版方块。
#:
#: 取值依据（`StateCollisionProbe` 逐状态取证 + mappings 组定义）：`chorus` 组（chorus_plant）
#: 64 个状态
#: 全是 `collEmpty=false, canOcclude=false`，碰撞箱 `[0.1875,0,0.1875]→[0.8125,1,0.8125]`
#: （10/16 全高柱）—— 它是「细柱/半格」类植物最接近的几何近似，而且不会挖掉邻面。
PARTIAL_COLLISION_POOLS: Mapping[str, str] = {"chorus": "minecraft:chorus_plant"}

#: CE `AutoStateGroup` 的**全部**合法组名（小写别名），反编译
#: `net/momirealms/craftengine/core/block/AutoStateGroup.class` 逐个抄下来。
#:
#: `auto_state` 不是自由组名：写一个不存在的名字，CE 加载时报「无效的常量」，
#: 而这个方块的**所有外观都不渲染**（表现是「方块在但看不见」，构建侧没有任何报错）。
#: 2026-10-28 就是这样丢了 37 个植物方块的外观：产物里写了 `sculk_vein`/`resin_clump`/`vine`
#: 三个不存在的组，`chorus_plant` 也应该是 `chorus`。
KNOWN_AUTO_STATE_GROUPS: frozenset[str] = frozenset({
    "non_tintable_leaves", "waterlogged_non_tintable_leaves",
    "tintable_leaves", "waterlogged_tintable_leaves",
    "leaves", "waterlogged_leaves",
    "lower_tripwire", "higher_tripwire",
    "note_block", "brown_mushroom", "red_mushroom", "mushroom_stem",
    "tripwire", "sugar_cane", "cactus", "cave_vine", "weeping_vine",
    "twisting_vine", "kelp", "chorus", "pressure_plate", "sapling",
    "mushroom", "solid",
})

#: 显式写原版状态时的**部分碰撞**方块（与 {@link PARTIAL_COLLISION_POOLS} 同一份真值）。
PARTIAL_COLLISION_VANILLA_BLOCKS: frozenset[str] = frozenset(PARTIAL_COLLISION_POOLS.values())

#: 自带负面语义的载体池：借了就不是植物了。碰撞箱近似可以登记放行，**语义不能**静默通过。
#:
#: 现在只剩 `pressure_plate`：`cactus` 已经并进 {@link OCCLUDING_POOLS}（遮挡 = 直接禁止，
#: 不需要登记）；压力板不遮挡、但会输出红石信号，一旦被当作植物载体就必须有人签字。
#: 自带副作用（伤害/红石）的载体。**允许**给植物用，但必须在
#: `bnlib.blocks` 的真值表里登记理由 —— 不许静默通过。
#:
#: `pressure_plate` 已经从"禁止"降级为"可用但需登记"：它的碰撞箱实测为空、
#: 不遮挡，是 `collision=empty` 植物唯一还够用的载体（tripwire/vine 系已被别的包占满）。
#: 红石信号这个副作用是可接受的代价 —— 另一个选择是借 `chorus` 的 10/16 全高柱，
#: 那会让成片的地狱草变成隐形墙，明显更难忍。
DANGEROUS_POOLS: Mapping[str, str] = {}

#: 允许继续走 `entity_renderer` 的显式豁免表：方块 id → 原因。
#:
#: 三条约束，缺一条豁免就不成立：
#:   1. **逐 id 登记**，禁止前缀/后缀模式匹配 —— 新方块必须有人显式加进来；
#:   2. `原因` 必须非空，说明「为什么这块只能走展示实体」；
#:   3. 豁免只免除 `plant_states.no_entity_renderer`，不解除碰撞/遮挡要求。
ENTITY_RENDERER_EXEMPTIONS: Mapping[str, str] = {}

#: 池额度表里**刻意不设额度**的池（生成器无条件使用，没有额度常量可比）。
#: `solid`：满格方块（木头/石头…）全部借音符盒+蘑菇方块族，生成器不给它设额度。
UNBUDGETED_POOLS: frozenset[str] = frozenset({"solid"})

#: 与生成器 `PoolAllocator._SHARED` 同义：绊线的三个组共用同一批状态，额度合并计算。
SHARED_POOL_KEYS: Mapping[str, str] = {
    "lower_tripwire": "tripwire",
    "higher_tripwire": "tripwire",
}


RULE_DESCRIPTIONS: Mapping[str, str] = {
    "mining.hardness": "settings.merges.hardness 必须等于上游 destroyTime（玻璃对齐项见 GLASS_MINING_ALIGNMENT）",
    "mining.tool_gate": "require_correct_tools / required_break_power 必须与上游 requiresTool 一致（玻璃对齐项见 GLASS_MINING_ALIGNMENT）",
    "mining.correct_tools": "correct_tools 必须覆盖上游挖掘工具族，且不夹带别的工具族",
    "mining.mineable_tag": "settings.merges.tags 里的 mineable/* 必须与上游 mineable 标签完全一致",
    "mining.glass_alignment": "上游玻璃类方块必须统一按原版玻璃手感（硬度<1、空手可挖）并登记在豁免表里",
    "orientation.property_values": "每个声明的属性都必须能算出取值集合（否则无法验证覆盖）",
    "orientation.variant_property": "variants 键里的属性名必须是已声明的属性",
    "orientation.variant_appearance": "variants 引用的外观必须存在",
    "orientation.state_coverage": "每个内部状态组合都必须命中一个带 appearance 的变体",
    "vine.climbable_missing": "上游 climbable 标签里的方块必须写 minecraft:climbable",
    "vine.climbable_extra": "不在这标签里的方块不得声明 minecraft:climbable",
    "vine.climbable_host_missing":
        "可爬方块的视觉载体必须是真实可爬的原版方块（cave_vine/weeping_vine/twisting_vine）",
    "vine.climbable_host_misuse":
        "不可爬的方块不得借可爬共享 id=climbable（会被顺带变成可爬）",
    "removal.reference": "生成产物里不得引用已移除的方块 id",
    "transparency.solid_appearance": "带 alpha 像素的贴图不得挂在不透明宿主上",
    "transparency.missing_renderer": "transparent: true 必须配 entity_renderer，否则方块没有外观",
    "transparency.host_unknown": "外观必须有可判定的宿主（auto_state 或显式 state）",
    "transparency.unresolved_texture": "外观引用的模型/本包贴图必须存在，否则无法判定透明",
    "transparency.base_texture_unjudged": "只用到原版贴图的外观不越过包边界测量 alpha（盲区，仅计数）",
    "plant_support.contract": "可证实的支撑规则必须在产物里写出等价 CE 行为",
    "plant_support.blocked": "上游规则受阻（成员不可证实）必须显式失败，不得静默跳过",
    "plant_support.unexpected": "上游没有支撑 trait 时不得凭空声明支撑行为",
    "plant_support.dangling_block": "支撑白名单不得引用未发布的方块 id",
    "plant_support.empty_whitelist": "白名单模式（blacklist: false）不得一个支撑字段都不写",
    "plant_support.facing_missing": "facing 方向的支撑规则要求方块声明 facing 属性",
    "plant_support.malformed": "behavior/behaviors 的结构必须合法（映射列表、二者不许并存）",
    "plant_support.duplicate": "同一支撑行为类型不得声明两次",
    "plant_support.update_shape_unmapped":
        "上游 updateShape/destroy 有「连接断了就回收」的判据时，产物里要么有等价 CE 行为、"
        "要么必须在 support.load_breakdown_rules 里有显式记录",
    "plant_support.breakdown_contract":
        "已判定可发布的破坏判据必须在产物里写出等价的 CE 行为",
    "plant_support.material_registration_unscanned":
        "blocks/complex/*Material.java 里槽位注册的方块必须进 trait/破坏判据索引，"
        "否则它们的支撑与破坏语义会静默漏掉",
    "plant_states.no_entity_renderer":
        "植物/装饰（上游碰撞非满格）不得再用 entity_renderer 外挂展示实体"
        "（仅 ENTITY_RENDERER_EXEMPTIONS 里逐 id 登记并写明原因的方块除外）",
    "plant_states.collision_pool_mismatch":
        "上游 collision=empty 的方块，载体必须来自无碰撞池（COLLISION_FREE_POOLS）",
    "plant_states.collision_pool_waived":
        "无碰撞方块借了有碰撞的载体：必须由 bnlib.blocks.COLLISION_POOL_WAIVERS 逐组登记原因"
        "（正常情况下应为空 —— `collision=empty` 的方块一律落无碰撞池）",
    "plant_states.occluding_carrier":
        "植物不得借会遮挡剔除的载体（solid/note_block/蘑菇方块），否则透视",
    "plant_states.occluding_carrier_waived":
        "满格载体（会遮挡剔邻面）给容器用：必须逐块签字登记在 "
        "bnlib.blocks.OCCLUDING_CARRIER_WAIVERS（用户要求的取舍，但每个方块都要写明代价）",
    "plant_states.state_slot_budget":
        "账本必须自洽（cap-used==free），POOL_BUDGET 的额度不得超过账本余量，"
        "产物占用不得超过额度；缺账的池一律判红（来源：bnlib.ce_pool_ledger）",
    "plant_states.known_auto_state_group":
        "auto_state 必须是 CE AutoStateGroup 枚举里的合法组名（别名小写）；写错名字的方块"
        "在游戏里完全没有外观，而构建是全绿的 —— 这是 2026-10-28 那次返工的根因",
    "plant_states.partial_collision_unrecorded":
        "上游 partial 碰撞的方块放进形状差异明显的池时，bnlib.blocks.PARTIAL_COLLISION_FITS "
        "里必须有这块的条目且保真说明非空",
}


@dataclass(frozen=True)
class Finding:
    rule: str
    subject: str
    detail: str


@dataclass
class Report:
    findings: list[Finding] = field(default_factory=list)
    unimplemented: dict[str, str] = field(default_factory=dict)
    checked: dict[str, int] = field(default_factory=dict)
    notes: dict[str, list[str]] = field(default_factory=dict)

    def fail(self, rule: str, subject: str, detail: str) -> None:
        self.findings.append(Finding(rule, subject, detail))

    def skip(self, rule: str, reason: str) -> None:
        self.unimplemented.setdefault(rule, reason)

    def count(self, rule: str, amount: int = 1) -> None:
        self.checked[rule] = self.checked.get(rule, 0) + amount

    def note(self, rule: str, text: str) -> None:
        """记一条「已明确计数」的观察（不判红，但必须在报告里看得见）。"""
        self.notes.setdefault(rule, []).append(text)

    def failures(self, rule: str) -> list[Finding]:
        return [f for f in self.findings if f.rule == rule]


# --------------------------------------------------------------------------- 载入

@dataclass
class Pack:
    """待校验的生成产物。"""

    root: Path
    assets: Path
    blocks: dict[str, dict] = field(default_factory=dict)
    block_origin: dict[str, Path] = field(default_factory=dict)

    @classmethod
    def load(cls, root: Path) -> "Pack":
        pack = cls(root=root, assets=root / "craftengine/betternether/resourcepack/assets")
        blocks_dir = root / "craftengine/betternether/configuration/blocks"
        if not blocks_dir.is_dir():
            raise SystemExit(f"缺少方块配置目录：{blocks_dir}（先跑 extractBnResources）")
        for path in sorted(blocks_dir.glob("*.yml")):
            payload = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            for block_id, config in (payload.get("blocks") or {}).items():
                if not isinstance(config, dict):
                    continue
                pack.blocks[block_id] = config
                pack.block_origin[block_id] = path
        if not pack.blocks:
            raise SystemExit(f"{blocks_dir} 里没有任何 blocks: 条目，产物不完整")
        return pack

    def merges(self, block_id: str) -> dict:
        settings = self.blocks[block_id].get("settings") or {}
        return settings.get("merges") or {}


def appearances(config: Mapping[str, object]) -> list[tuple[str, dict]]:
    """方块的「外观」列表：单状态把 `state:` 当成一个叫 `state` 的外观。

    `state: {template: default:block_state/xxx, arguments: {...}}` 是 CE 内置模板写法
    （门/活板门/闸门；模板内部逐状态钉 `state: <donor>[...,powered=true]`）。本脚本
    无法展开 CE 模板，但可以确信它自带写死状态宿主，所以合成一个 `__template__` 外观
    让 `host_of` 返回「显式状态」，既不放行也不误报「没有宿主」。
    """
    out: list[tuple[str, dict]] = []
    state = config.get("state")
    if isinstance(state, dict):
        if "template" in state:
            out.append(("__template__", {"state": "__template__"}))
        elif isinstance(state.get("appearances"), dict):
            # `state:` 下再套 `appearances:`（单 state 段 + 多内部状态），
            # 如压力板钉 heavy_weighted_pressure_plate[power=N]。逐个外观取出。
            for name, appearance in state["appearances"].items():
                if isinstance(appearance, dict):
                    out.append((str(name), appearance))
        else:
            out.append(("state", state))
    states = config.get("states")
    if isinstance(states, dict):
        if "template" in states:
            out.append(("__template__", {"state": "__template__"}))
        for name, appearance in (states.get("appearances") or {}).items():
            if isinstance(appearance, dict):
                out.append((str(name), appearance))
    return out


def host_of(appearance: Mapping[str, object]) -> str | None:
    """外观用哪个原版状态渲染。

    返回 `"__explicit_state__"` 表示走 `state: "minecraft:xxx[...]"` 精确指定，
    这种写法的渲染面性质要解析原版状态才知道，本脚本不做猜测。
    """
    if "auto_state" in appearance:
        raw = appearance["auto_state"]
        if isinstance(raw, str):
            return raw
        if isinstance(raw, dict):
            host = raw.get("type")
            return str(host) if host is not None else None
    if "state" in appearance:
        return "__explicit_state__"
    return None


def model_specs(appearance: Mapping[str, object]) -> list[dict]:
    """外观声明的模型（`model:` 单个 / `models:` 加权列表）。"""
    out: list[dict] = []
    model = appearance.get("model")
    if isinstance(model, dict):
        out.append(model)
    elif isinstance(model, str):
        out.append({"path": model})
    for entry in appearance.get("models") or []:
        if isinstance(entry, dict):
            out.append(entry)
    return out


def model_path(spec: Mapping[str, object]) -> Path | None:
    path = spec.get("path")
    if not isinstance(path, str) or ":" not in path:
        return None
    namespace, _, tail = path.partition(":")
    return Path(namespace) / "models" / f"{tail}.json"


@dataclass(frozen=True)
class TextureScan:
    """一个模型**实际使用**的贴图，以及解析过程中的两种「测不到」。"""

    #: 去重后的贴图引用（`ns:block/x`，已解析 `#slot` 链、含 parent 继承）。
    refs: tuple[str, ...]
    #: 模型文件本身不存在（且没有 `generation`）—— 真实缺口，必定是坏的。
    model_missing: bool
    #: parent 链在原版模型处断开（`minecraft:block/*` 不在本包里）：继承下来的贴图测不到。
    stops_at_vanilla_parent: bool


def declared_textures(spec: Mapping[str, object]) -> dict[str, object]:
    """模型规格里**自己声明**的贴图：`generation.textures` / `textures`（映射或列表）。"""
    generated = spec.get("generation")
    if isinstance(generated, Mapping) and isinstance(generated.get("textures"), Mapping):
        return {str(slot): value for slot, value in generated["textures"].items()}
    declared = spec.get("textures")
    if isinstance(declared, Mapping):
        return {str(slot): value for slot, value in declared.items()}
    if isinstance(declared, list):
        # 列表写法没有槽位名：造一个不会与真实槽位/槽位引用（`#slot`）冲突的名字。
        return {f"_list{index}": value for index, value in enumerate(declared)}
    return {}


def pack_model_file(pack: Pack, model_ref: str) -> Path | None:
    """把模型引用解析成本包里的模型 JSON 路径；原版命名空间/裸 `block/x` 返回 None。"""
    namespace, sep, tail = str(model_ref).partition(":")
    if not sep:
        # 模型文件里的 parent/textures 不带命名空间时按原版解析（与 MC 规则一致）。
        return None
    if namespace == "minecraft":
        return None
    path = pack.assets / namespace / "models" / f"{tail}.json"
    return path if path.is_file() else None


def spec_texture_refs(spec: Mapping[str, object], pack: Pack) -> TextureScan:
    """沿 `parent` 链合并出模型**实际使用**的贴图，语义与生成器一致。

    生成器侧是 `blocks.merged_model_textures` + `transparency.model_texture_refs`：
      1. 子模型的 `textures` 覆盖父模型同名槽位（只补空缺），只读 `textures` 不读
         `elements`（`elements` 引用的是槽位名，不是贴图）；
      2. 合并后按 `#slot` 链解析到底（`transparency.resolve_slot_refs` 自带环保护）；
      3. parent 链可能成环、也可能断在原版模型处 —— 用 `seen` 保底，绝不无限展开。

    只看当前模型自己的 `textures` 会漏掉「父模型声明了 `all`、子模型只覆盖 `side`」
    这种继承，也会把只声明了 `#slot` 的模型误判成「无贴图」。
    """
    generated = isinstance(spec.get("generation"), Mapping)
    merged: dict[str, object] = dict(declared_textures(spec))
    seen: set[str] = set()
    model_missing = False
    stops_at_vanilla_parent = False

    current: str | None = spec.get("path") if isinstance(spec.get("path"), str) else None
    first = True
    while current and current not in seen:
        seen.add(current)
        if ":" in current and current.partition(":")[0] == "minecraft":
            stops_at_vanilla_parent = True
            break
        model_file = pack_model_file(pack, current)
        if model_file is None:
            if first and generated:
                # `generation` 声明的模型由 CE 在构建期合成，磁盘上本来就没有这个
                # JSON；贴图已在上面从 `generation.textures` 取到，不算缺口。
                parent = (spec.get("generation") or {}).get("parent")
                if isinstance(parent, str) and parent.partition(":")[0] == "minecraft":
                    stops_at_vanilla_parent = True
            elif first:
                model_missing = True  # 写了模型却没有文件，也没有 generation 兜底
            else:
                stops_at_vanilla_parent = True  # 断在父模型（原版）处
            break
        first = False
        payload = transparency.load_model(model_file)
        if payload is None:
            model_missing = model_missing or not merged
            break
        textures = payload.get("textures")
        if isinstance(textures, Mapping):
            for slot, value in textures.items():
                merged.setdefault(str(slot), value)
        parent = payload.get("parent")
        current = parent if isinstance(parent, str) else None

    resolved = transparency.resolve_slot_refs(merged)  # type: ignore[arg-type]
    refs: list[str] = []
    for value in resolved.values():
        ref = transparency.normalize_texture_ref(str(value))
        if ref not in refs:
            refs.append(ref)
    return TextureScan(refs=tuple(refs), model_missing=model_missing,
                       stops_at_vanilla_parent=stops_at_vanilla_parent)


def texture_alpha(path: Path, cache: dict[Path, "transparency.TextureAlpha"]):
    """一张 PNG 的 alpha 事实（带缓存）；判据见模块顶部的「带 alpha」说明。"""
    if path not in cache:
        cache[path] = transparency.inspect_texture(path)
    return cache[path]


# --------------------------------------------------------------------------- 规则

def check_mining(pack: Pack, upstream: Path, report: Report) -> None:
    """挖掘门槛：硬度、工具布尔、工具等级、工具族、mineable 标签五项都要与上游一致。

    唯一例外是 {@link GLASS_MINING_ALIGNMENT}：上游这些玻璃方块是「整块拷贝黑曜石/铌钙石块」
    的副产品，把硬度 50/3 与钻石镐门槛一起继承下来；实际材质是玻璃，空手挖要十几秒。
    用户要求「空手能挖」，所以产物按原版玻璃对齐。这是**有意的偏离**，必须登记在册、
    带原因、并锁定具体数值 —— 不能靠放宽规则或删断言让它变绿，否则以后任何硬度改动都会
    静默通过。未登记的方块仍按上游严格比对。
    """
    values = props.load_props(upstream)
    tools_by_block = props.load_mineable_tools(upstream)
    break_power = props.load_break_power(upstream)

    for block_id in sorted(pack.blocks):
        upstream_props = values.get(block_id)
        if upstream_props is None:
            continue
        report.count("mining.hardness")
        merges = pack.merges(block_id)
        exception = GLASS_MINING_ALIGNMENT.get(block_id)

        hardness = merges.get("hardness")
        expected_hardness = exception[0] if exception else upstream_props.hardness
        if hardness is None:
            report.fail("mining.hardness", block_id,
                        f"缺少 hardness，上游 destroyTime={expected_hardness}")
        elif abs(float(hardness) - float(expected_hardness)) > 1e-6:
            report.fail("mining.hardness", block_id,
                        f"hardness={hardness}，上游 destroyTime={expected_hardness}")

        report.count("mining.tool_gate")
        gate = merges.get("require_correct_tools") is True
        declared_power = int(merges.get("required_break_power") or 0)
        expected_power = 0 if exception else break_power.get(block_id, 0)
        requires_tool = False if exception else upstream_props.requires_tool
        if requires_tool:
            if not gate:
                report.fail("mining.tool_gate", block_id,
                            "上游 requiresTool=true，产物没写 require_correct_tools: true")
            if declared_power != expected_power:
                report.fail("mining.tool_gate", block_id,
                            f"required_break_power={declared_power}，上游等级门槛={expected_power}")
        else:
            if gate:
                reason = f"（已登记豁免：{exception[2]}）" if exception else "上游 requiresTool=false（空手可掉落）"
                report.fail("mining.tool_gate", block_id,
                            f"{reason}，产物却要求正确工具")
            if declared_power != 0:
                report.fail("mining.tool_gate", block_id,
                            f"上游 requiresTool=false，产物却写 required_break_power={declared_power}")

        report.count("mining.correct_tools")
        families = tools_by_block.get(block_id, frozenset())
        declared_tools = [str(t) for t in (merges.get("correct_tools") or [])]
        if upstream_props.requires_tool and not declared_tools:
            report.fail("mining.correct_tools", block_id, "上游需要工具，但没有 correct_tools")
        for tool in declared_tools:
            family = tool.rsplit("_", 1)[-1]
            if family not in families:
                report.fail("mining.correct_tools", block_id,
                            f"correct_tools 含 {tool}（{family}），上游 mineable 族为 "
                            f"{sorted(families) or '空'}")
        # 只有上游真的要求工具时，correct_tools 才承担「门槛」职责。
        # 反过来（`requiresTool=false`，例如原木：斧头只是更快）项目刻意不写
        # correct_tools —— 写了就等于空手挖不掉，所以这里不要求工具族覆盖。
        for family in sorted(families if upstream_props.requires_tool else ()):
            if not any(tool.endswith(f"_{family}") for tool in declared_tools):
                report.fail("mining.correct_tools", block_id, f"缺少 {family} 族工具")

        report.count("mining.mineable_tag")
        tags = [str(t) for t in (merges.get("tags") or [])]
        for family in sorted(families):
            expected_tag = f"minecraft:mineable/{family}"
            if expected_tag not in tags:
                report.fail("mining.mineable_tag", block_id, f"缺少 {expected_tag}")
        for tag in tags:
            if not tag.startswith("minecraft:mineable/"):
                continue
            family = tag.rsplit("/", 1)[-1]
            if family not in families:
                report.fail("mining.mineable_tag", block_id,
                            f"声明了 {tag}，但上游没有把该方块放进这个 mineable 标签")


def property_values(name: str, spec: Mapping[str, object]) -> tuple[str, ...] | None:
    """一个属性可能的取值；算不出来时返回 None（调用方按「无法验证」上报）。"""
    declared = spec.get("values")
    if isinstance(declared, list) and declared:
        return tuple(str(value) for value in declared)
    kind = spec.get("type")
    if isinstance(kind, str) and kind in TYPE_VALUES:
        return TYPE_VALUES[kind]
    if kind == "int":
        raw = spec.get("range")
        if isinstance(raw, str) and "~" in raw:
            low, _, high = raw.partition("~")
            try:
                return tuple(str(value) for value in range(int(low), int(high) + 1))
            except ValueError:
                return None
    return None


def parse_variant_key(key: object) -> dict[str, str]:
    """`"facing=east,open=true"` → `{"facing": "east", "open": "true"}`。"""
    out: dict[str, str] = {}
    for part in str(key).split(","):
        if "=" not in part:
            continue
        name, _, value = part.partition("=")
        out[name.strip()] = value.strip()
    return out


def check_orientation(pack: Pack, report: Report) -> None:
    """方向/形态覆盖：产物声明的每个内部状态都必须命中一个带外观的变体。

    CE 文档「变体匹配规则」：变体键里没列出的属性等于通配；一个状态若匹配不到任何
    带 `appearance` 的变体，就**回退到第一个外观**。回退不会报错，只会让某个朝向
    静默变成别的朝向的形状 —— 这正是最难发现的那类降级，所以这里要求显式覆盖。
    """
    for block_id in sorted(pack.blocks):
        config = pack.blocks[block_id]
        states = config.get("states")
        if not isinstance(states, dict):
            continue
        declared = states.get("properties") or {}
        appearances_declared = states.get("appearances") or {}
        variants = states.get("variants") or {}
        if not isinstance(declared, Mapping) or not declared:
            continue
        report.count("orientation.property_values")

        value_sets: dict[str, tuple[str, ...]] = {}
        for name, spec in declared.items():
            spec = spec if isinstance(spec, Mapping) else {}
            values = property_values(str(name), spec)
            if values is None:
                report.fail("orientation.property_values", block_id,
                            f"属性 {name}（type={spec.get('type')}）算不出取值集合")
                continue
            value_sets[str(name)] = values
        if len(value_sets) != len(declared):
            continue

        parsed: list[tuple[str, dict[str, str], str | None]] = []
        for key, entry in (variants or {}).items():
            entry = entry if isinstance(entry, Mapping) else {}
            conditions = parse_variant_key(key)
            report.count("orientation.variant_property")
            for name in conditions:
                if name not in value_sets:
                    report.fail("orientation.variant_property", block_id,
                                f"变体键 {key} 约束了未声明的属性 {name}")
            target = entry.get("appearance")
            target = str(target) if target is not None else None
            report.count("orientation.variant_appearance")
            if target is not None and target not in (appearances_declared or {}):
                report.fail("orientation.variant_appearance", block_id,
                            f"变体键 {key} 指向不存在的外观 {target}")
            parsed.append((str(key), conditions, target))

        total_states = 1
        for values in value_sets.values():
            total_states *= len(values)
        if total_states > 4096:
            report.skip("orientation.state_coverage",
                        f"{block_id} 有 {total_states} 个状态组合，超出逐个覆盖的验证预算")
            continue

        names = list(value_sets)
        uncovered: list[dict[str, str]] = []
        for combination in itertools.product(*(value_sets[name] for name in names)):
            state = dict(zip(names, combination))
            report.count("orientation.state_coverage")
            if not any(target is not None
                       and all(state.get(name) == value for name, value in conditions.items())
                       for _key, conditions, target in parsed):
                uncovered.append(state)
        for state in uncovered[:3]:
            description = ",".join(f"{name}={value}" for name, value in state.items())
            report.fail("orientation.state_coverage", block_id,
                        f"状态 {description} 匹配不到带 appearance 的变体"
                        f"（共 {len(uncovered)} 个未覆盖组合）")


def carrier_of(appearance: Mapping[str, object]) -> tuple[str | None, str | None]:
    """外观的**视觉载体**：返回 (载体名, 共享 id)。

    * `auto_state: cave_vine`                     -> ("cave_vine", None)
    * `auto_state: {type: cave_vine, id: xxx}`    -> ("cave_vine", "xxx")
    * `auto_state: "minecraft:cave_vines[age=5]"` -> ("minecraft:cave_vines", None)
    * `state: "minecraft:note_block[...]"`        -> ("minecraft:note_block", None)
    * 都没有（纯模型）                             -> (None, None)

    显式写着原版状态时只保留方块名（属性与攀爬无关）。
    """
    def normalize(raw: object) -> str | None:
        if not isinstance(raw, str):
            return None
        text = raw.strip()
        if not text:
            return None
        return text.split("[", 1)[0] if text.startswith("minecraft:") else text

    if "auto_state" in appearance:
        raw = appearance["auto_state"]
        if isinstance(raw, dict):
            return normalize(raw.get("type")), (
                str(raw["id"]) if raw.get("id") is not None else None)
        return normalize(raw), None
    if "state" in appearance:
        return normalize(appearance["state"]), None
    return None, None


def is_climb_host(carrier: str | None) -> bool:
    """这个载体是不是**真的**会触发攀爬的原版方块（组名或显式原版方块都认）。"""
    if not carrier:
        return False
    if carrier in CLIMB_HOSTS:
        return True
    return carrier in CLIMB_VANILLA_BLOCKS


def check_vines(pack: Pack, upstream: Path, report: Report) -> None:
    """藤蔓可爬：**tag 与视觉载体两件事都要对**，只查 tags 就是假绿。

    判定点只有一处：`LivingEntity#onClimbable()` → `getInBlockState().is(CLIMBABLE)`。
    这段逻辑**客户端也跑一遍**，而客户端手里的方块状态就是 CE 下发的视觉载体：

      * `settings.merges.tags` 的 `minecraft:climbable` 挂在自定义方块 holder 上
        （服务端逻辑 / CE 客户端 mod 读得到），**替不了载体**；
      * 只写 tag、载体仍是 `sugar_cane` 的方块，玩家实际爬不上去。

    所以两条都查：
      1. 上游 climbable 成员 ↔ `settings.merges.tags`（原有契约，码不变）；
      2. 可爬方块的**每个外观**都必须借真实可爬宿主（{@link CLIMB_HOSTS}）；
         反过来，不可爬的方块不得借共享 id `climbable` —— 那会把整片植物顺带变成可爬。
         其他合法宿主（例如共用真实薄池的植物）不在本条禁止范围内。
    """
    climbable = props.load_tag(upstream, "climbable")
    if not climbable:
        for rule in ("vine.climbable_missing", "vine.climbable_extra",
                     "vine.climbable_host_missing", "vine.climbable_host_misuse"):
            report.skip(rule, "上游没有可读的 climbable 标签文件，无法判定")
        return
    for block_id in sorted(pack.blocks):
        config = pack.blocks[block_id]
        for rule in ("vine.climbable_missing", "vine.climbable_extra",
                     "vine.climbable_host_missing", "vine.climbable_host_misuse"):
            report.count(rule)
        tags = [str(tag) for tag in (pack.merges(block_id).get("tags") or [])]
        declared = CLIMBABLE_TAG in tags
        expected = block_id in climbable
        if expected and not declared:
            report.fail("vine.climbable_missing", block_id,
                        f"上游 climbable 标签含该方块，但 settings.merges.tags 没有 "
                        f"{CLIMBABLE_TAG}")
        if declared and not expected:
            report.fail("vine.climbable_extra", block_id,
                        f"声明了 {CLIMBABLE_TAG}，但上游 climbable 标签里没有它")

        has_appearance = False
        for name, appearance in appearances(config):
            has_appearance = True
            carrier, shared_id = carrier_of(appearance)
            if declared or expected:
                # 可爬方块：载体必须是真可爬的，否则 tag 只是纸面配置。
                if carrier is None:
                    report.fail("vine.climbable_host_missing", block_id,
                                f"外观 {name} 没有可判定的视觉载体（auto_state/state），"
                                f"无法保证客户端读到可爬方块")
                elif not is_climb_host(carrier):
                    report.fail("vine.climbable_host_missing", block_id,
                                f"外观 {name} 的载体是 {carrier}（不在 climbable 标签里），"
                                f"玩家客户端读到它不会起攀；可爬宿主只有 "
                                f"{sorted(CLIMB_HOSTS)}")
            elif shared_id == CLIMB_SHARED_ID:
                report.fail("vine.climbable_host_misuse", block_id,
                            f"外观 {name} 借了可爬共享 id={CLIMB_SHARED_ID}，但该方块不在"
                            f"上游 climbable 标签里 —— 不可爬的植物会被顺带变成可爬")
        if (declared or expected) and not has_appearance:
            report.fail("vine.climbable_host_missing", block_id,
                        "可爬方块没有任何外观（state/states），无法证明客户端读到可爬宿主")


def check_removal_references(pack: Pack, report: Report) -> None:
    """删除引用：产物里任何一处仍指向已移除方块 id 的地方都要报出来。

    只看产物、不看 `src`：`src` 里保留上游原样（那是迁移的输入），产物才是要发布的东西。
    """
    removed_hits: dict[str, set[str]] = {}
    scan_roots = [pack.root / name for name in RUNTIME_SUBTREES
                  if (pack.root / name).is_dir()]
    for scan_root in scan_roots:
        for path in sorted(scan_root.rglob("*")):
            if not path.is_file() or path.suffix not in TEXT_SUFFIXES:
                continue
            report.count("removal.reference")
            try:
                if path.stat().st_size > MAX_SCAN_BYTES:
                    continue
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for match in BLOCK_ID_PATTERN.finditer(text):
                ident = match.group(0)
                if publication.is_removed_block(ident):
                    removed_hits.setdefault(ident, set()).add(
                        str(path.relative_to(pack.root)))
    for ident in sorted(removed_hits):
        places = sorted(removed_hits[ident])
        report.fail("removal.reference", ident,
                    "已移除但仍有引用：" + "、".join(places[:5])
                    + (f" 等 {len(places)} 处" if len(places) > 5 else ""))


def check_glass_mining_invariant(pack: Pack, upstream: Path, report: Report) -> None:
    """玻璃类必须**成批**落在同一个挖掘规格上，防止「新增玻璃忘记登记豁免」。

    {@link GLASS_MINING_ALIGNMENT} 是逐 id 的显式表，好处是精确，坏处是：以后上游新增一种
    玻璃（或有人复制了 quartz_glass_framed 的写法），只要忘了登记，就既不在豁免表里、
    又因为硬度=50/3 而「碰巧」通过 mining.hardness（因为它确实等于上游 destroyTime）——
    于是空手挖不动的问题会静默回来。

    这条规则换一个角度：**凡是上游类名里带 Glass 的已发布方块**，其产物必须落在同一个
    规格上（硬度 < 1 且不要求工具），并且必须能在这张豁免表里找到。两个条件缺一：
      * 未登记 → 报红（提示有人新增玻璃没加豁免）；
      * 登记了但数值不统一 → 报红（提示豁免表被改歪）。
    """
    values = props.load_props(upstream)
    violators = []
    unregistered = []
    for block_id in sorted(pack.blocks):
        upstream_props = values.get(block_id)
        if upstream_props is None:
            continue
        cls = getattr(upstream_props, "cls", "") or ""
        if "Glass" not in cls:
            continue
        report.count("mining.glass_alignment")
        merges = pack.merges(block_id)
        registered = block_id in GLASS_MINING_ALIGNMENT
        hardness = merges.get("hardness")
        gate = merges.get("require_correct_tools") is True
        if not registered:
            unregistered.append(block_id)
            continue
        # 登记过的必须真的按「原版玻璃手感」写，而不是只占了表里一行。
        if hardness is None or float(hardness) >= 1.0:
            violators.append(f"{block_id}：hardness={hardness}，玻璃应 < 1")
        if gate:
            violators.append(f"{block_id}：require_correct_tools=true，玻璃应空手可挖")
        declared_power = int(merges.get("required_break_power") or 0)
        if declared_power != 0:
            violators.append(f"{block_id}：required_break_power={declared_power}，玻璃应为 0")

    for item in violators:
        report.fail("mining.glass_alignment", item.split("：")[0], item.split("：", 1)[1])
    for block_id in unregistered:
        report.fail("mining.glass_alignment", block_id,
                    "上游这是玻璃类方块，但没有登记在 GLASS_MINING_ALIGNMENT："
                    "要么补登记（空手可挖），要么明确写出为什么它该保持上游硬度")


def check_transparency(pack: Pack, upstream: Path, report: Report) -> None:
    """透明实心外观：贴图带 alpha 的模型不得挂在不透明宿主上。

    判定链：
      * 宿主 `auto_state` 属于 {@link OPAQUE_HOSTS}（音符盒 / 蘑菇方块 = 完整方块，
        渲染类型是 SOLID，不做 alpha 测试、也不混色）；
      * 模型实际引用的贴图里**任一像素 alpha < 255**（半透明与全透明都算）。

    两条同时成立就是「透明贴图被画成实心」：全透明像素变成黑块、半透明像素丢失混色。
    上游 `block_properties.json` 的 `renderLayer` 只覆盖声明过 NetherRender trait 的
    方块（`NetherRender` 的注释：没给 trait 也没在模型里写 `render_type` 的一律
    SOLID），所以这里以贴图内容为准，而不是以那个字段为准。
    """
    values = props.load_props(upstream)
    cache: dict[Path, "transparency.TextureAlpha"] = {}

    for block_id in sorted(pack.blocks):
        config = pack.blocks[block_id]
        for name, appearance in appearances(config):
            report.count("transparency.solid_appearance")
            if appearance.get("transparent") is True:
                report.count("transparency.missing_renderer")
                if not appearance.get("entity_renderer"):
                    report.fail("transparency.missing_renderer", block_id,
                                f"外观 {name} 写了 transparent: true 但没有 entity_renderer，"
                                "方块会被清空模型且无替代渲染")
                continue

            host = host_of(appearance)
            report.count("transparency.host_unknown")
            if host is None:
                report.fail("transparency.host_unknown", block_id,
                            f"外观 {name} 既没有 auto_state 也没有 state，无法判定渲染宿主")
                continue
            if host == "__explicit_state__" or host not in OPAQUE_HOSTS:
                continue

            specs = model_specs(appearance)
            if not specs:
                continue
            report.count("transparency.unresolved_texture")
            translucent: tuple[int, str] | None = None  # (min_alpha, ref)：最小的那个最严重
            broken: list[str] = []      # 本包自己的贴图/模型缺失 —— 真实缺口
            vanilla: list[str] = []     # 复用原版贴图：原版包提供，本包内测不到 alpha
            inherited_unmeasured = False  # parent 链断在原版模型处，继承贴图测不到
            measured_local = False      # 至少真的量过一张本包贴图
            for spec in specs:
                scan = spec_texture_refs(spec, pack)
                if scan.model_missing:
                    broken.append(str(spec.get("path") or "（无 path 的模型）"))
                    continue
                if not scan.refs:
                    # 没有可解析的贴图槽位：能确认是断在原版 parent，就记为「测不到」，
                    # 否则是真实缺口（写了模型却解析不出任何贴图）。
                    if scan.stops_at_vanilla_parent:
                        inherited_unmeasured = True
                    else:
                        broken.append(str(spec.get("path") or "（无 refs 的模型）"))
                    continue
                if scan.stops_at_vanilla_parent:
                    inherited_unmeasured = True
                for ref in scan.refs:
                    namespace = ref.partition(":")[0]
                    texture = transparency.resolve_texture_ref(ref, pack.assets)
                    if texture is None:
                        continue
                    if not texture.is_file():
                        (vanilla if namespace == "minecraft" else broken).append(ref)
                        continue
                    measured_local = True
                    alpha = texture_alpha(texture, cache)
                    if not alpha.is_opaque and (translucent is None
                                                or alpha.min_alpha < translucent[0]):
                        translucent = (alpha.min_alpha, ref)
            if broken:
                report.fail("transparency.unresolved_texture", block_id,
                            f"外观 {name} 的模型/贴图在本包里不存在：{sorted(set(broken))[:3]}")
                continue
            if translucent is None and not measured_local and (vanilla or inherited_unmeasured):
                # 只用原版贴图 / parent 链断在原版模型处：宿主复用原版模型时渲染方式与
                # 原版一致，本脚本不越过包边界去读服务端的原版资源，明确记为「未测量」。
                report.count("transparency.base_texture_unjudged")
            if translucent is not None:
                min_alpha, ref = translucent
                upstream_layer = getattr(values.get(block_id), "render_layer", None)
                report.fail("transparency.solid_appearance", block_id,
                            f"外观 {name} 挂在宿主 {host}（不透明），但贴图 {ref} 含 alpha "
                            f"< 255 的像素（min_alpha={min_alpha}）"
                            f"（上游 render_layer={upstream_layer}）")


#: CE 里表达「这株植物靠什么支撑」的行为类型。列全是为了让「产物声明了支撑、
#: 但不在这个集合里」不会被静默当成无关行为放过。
#: CE 支撑行为里「允许的支撑方块」字段：白名单模式下至少要有一个非空，否则永远不放行。
WHITELIST_FIELDS: tuple[str, ...] = (
    "bottom_blocks", "bottom_block_tags",
    "above_blocks", "above_block_tags",
    "attached_blocks", "attached_block_tags",
)

#: 这些字段里装的是**方块 id**（标签字段装的是 `#ns:tag` 或标签名，不能按 id 校验）。
BLOCK_ID_FIELDS: frozenset[str] = frozenset({"bottom_blocks", "above_blocks", "attached_blocks"})

SUPPORT_BEHAVIOR_TYPES: frozenset[str] = frozenset({
    "bush_block",             # 下方白名单
    "hanging_block",          # 上方白名单（垂挂）
    "hangable_block",         # 可挂在地面或方块下方
    "sturdy_base_block",      # 下方必须是坚固面（无白名单）
    "directional_attached_block",   # 贴面 + facing
    "attached_stem_block",
    "face_attached_horizontal_directional_block",
})


def behavior_entries(config: Mapping[str, object]) -> tuple[list[Mapping[str, object]], list[str]]:
    """读方块的**全部**行为条目，返回 `(条目, 结构问题)`。

    CE 两种写法都要读：单条的 `behavior:` 和多条的 `behaviors:`（`blocks.behavior_yaml`
    在有多个行为时正是写 `behaviors:` 列表）。只读单数会漏掉「支撑行为和别的行为一起
    写」的方块 —— 那也是大多数植物被加上生长/多阶段行为之后的常态。

    结构问题（不合法的一律报出来，不静默跳过）：
      * `behavior` / `behaviors` 里的条目不是映射；
      * 两者同时出现（CE 只会用其中一份，等于重复声明）；
      * 同一支撑类型声明两次（后者不会生效，却让人以为生效了）。
    """
    entries: list[Mapping[str, object]] = []
    problems: list[str] = []
    singular = config.get("behavior")
    plural = config.get("behaviors")
    if singular is not None and plural is not None:
        problems.append("同时写了 behavior 与 behaviors（CE 只认其一，另一份静默失效）")
    for label, value in (("behavior", singular), ("behaviors", plural)):
        if value is None:
            continue
        items = value if isinstance(value, list) else [value]
        for index, item in enumerate(items):
            if not isinstance(item, Mapping):
                problems.append(f"{label}[{index}] 不是映射：{item!r}")
                continue
            entries.append(item)
    return entries, problems


def support_entries_of(config: Mapping[str, object]) -> list[Mapping[str, object]]:
    """产物里声明为「支撑/附着」的行为条目（`behavior:` / `behaviors:` 都读）。"""
    entries, _problems = behavior_entries(config)
    return [entry for entry in entries
            if str(entry.get("type")) in SUPPORT_BEHAVIOR_TYPES]


def parse_support_lines(lines: object) -> Mapping[str, object] | None:
    """`bnlib.support` 输出的行为是可读 YAML 片段（行列表），解析成映射再比较。

    行列表本身就是合法 YAML（序列项与父键同缩进），所以直接拼起来交给 safe_load；
    解析不出来时返回 None —— 那种情况必须报出来，不能当成「没有期望」。
    """
    try:
        payload = yaml.safe_load("\n".join(str(line) for line in lines))
    except yaml.YAMLError:
        return None
    return payload if isinstance(payload, Mapping) else None


def canonical_support(entry: Mapping[str, object]) -> tuple:
    """把一条支撑行为归一成可直接比较的形状（列表按集合比，顺序无关）。"""
    items: list[tuple[str, object]] = []
    for key, value in sorted(entry.items()):
        if isinstance(value, (list, tuple)):
            items.append((str(key), frozenset(str(item) for item in value)))
        else:
            items.append((str(key), value))
    return tuple(items)


_SUPPORT_RULES_CACHE: dict[Path, dict] = {}


def _support_rules(upstream_root: Path) -> dict:
    """读一次上游支撑规则就缓存：解析上游 Java 源码不便宜，门禁只跑一次也不需要重复。"""
    if upstream_root not in _SUPPORT_RULES_CACHE:
        from bnlib import support

        _SUPPORT_RULES_CACHE[upstream_root] = support.load_support_rules(upstream_root)
    return _SUPPORT_RULES_CACHE[upstream_root]


_BREAKDOWN_RULES_CACHE: dict[Path, dict] = {}
_BREAKDOWN_CANDIDATE_CACHE: dict[Path, dict[str, str]] = {}
_MATERIAL_SCAN_CACHE: dict[Path, dict] = {}


def _breakdown_rules(upstream_root: Path) -> dict:
    """`support.load_breakdown_rules`（updateShape/destroy 层）读一次就缓存。"""
    if upstream_root not in _BREAKDOWN_RULES_CACHE:
        from bnlib import support

        _BREAKDOWN_RULES_CACHE[upstream_root] = support.load_breakdown_rules(upstream_root)
    return _BREAKDOWN_RULES_CACHE[upstream_root]


def _breakdown_candidates(upstream_root: Path) -> dict[str, str]:
    """上游「确实会在连接断裂时回收自己」的方块 —— **独立于**规则表的一份真值。

    两边用的解析器相同，但一边是「上游有什么」，另一边是「我们记了什么」：
    只要记录被删掉，这里就查得出来。
    """
    if upstream_root not in _BREAKDOWN_CANDIDATE_CACHE:
        from bnlib import support

        _BREAKDOWN_CANDIDATE_CACHE[upstream_root] = support.breakdown_candidates(
            upstream_root)
    return _BREAKDOWN_CANDIDATE_CACHE[upstream_root]


def _material_scan(upstream_root: Path) -> dict:
    if upstream_root not in _MATERIAL_SCAN_CACHE:
        from bnlib import support

        _MATERIAL_SCAN_CACHE[upstream_root] = support.material_registration_scan(
            upstream_root)
    return _MATERIAL_SCAN_CACHE[upstream_root]


_BREAKDOWN_RULES: tuple[str, ...] = (
    "plant_support.update_shape_unmapped",
    "plant_support.breakdown_contract",
    "plant_support.material_registration_unscanned",
)


def check_plant_breakdown(pack: Pack, report: Report) -> None:
    """连接断裂层（`updateShape` / `destroy`）的门禁。

    支撑门禁（{@link check_plant_support}）只看 `.addTrait(...)` + `canSurvive`，天然看不到
    「邻居没了就 `Blocks.AIR`」这一整套逻辑 —— 藤蔓家族、芦苇、石笋柱全在这条盲区里。
    这里按**产物里发布的方块**逐条核对三件事：

      1. `update_shape_unmapped`：上游有判据却没记录 = 这条语义没人管（这次的形态）；
      2. `breakdown_contract`：判为可发布却没写进产物 = 玩家看到「该断的不断」；
      3. `material_registration_unscanned`：槽位注册的方块没进索引 = 连判据都读不到。

    `blocked` 记录**不算**通过：它是以「显式失败」的形式进报告（`· ` 打头的注记），
    既不会静默跳过，也不会被当成已实现。
    """
    try:
        sys.path.insert(0, str(ROOT / "scripts"))
        from bnlib import support  # noqa: F401
    except ImportError as exc:
        for rule in _BREAKDOWN_RULES:
            report.skip(rule, f"无法导入 bnlib.support：{exc}")
        return

    upstream_root = ROOT / "upstream/BetterNether"
    if not upstream_root.is_dir():
        for rule in _BREAKDOWN_RULES:
            report.skip(rule, f"缺少上游目录 {upstream_root}")
        return

    candidates = _breakdown_candidates(upstream_root)
    rules = _breakdown_rules(upstream_root)
    if not candidates:
        for rule in _BREAKDOWN_RULES:
            report.skip(rule, "上游读不出任何破坏判据（block_properties.json 或 updateShape 缺失）")
        return

    scan = _material_scan(upstream_root)
    report.count("plant_support.material_registration_unscanned", len(scan["unindexed"]))
    for cls in scan["unindexed"]:
        ids = scan["ids"].get(cls, [])
        report.note("plant_support.material_registration_unscanned",
                    f"{cls}（{scan['classes'][cls]}，{len(ids)} 个 id："
                    f"{'、'.join(ids[:3])}{'…' if len(ids) > 3 else ''}）"
                    "不在 trait／破坏判据索引里 —— 它的支撑与破坏语义目前没有来源")

    for block_id in sorted(pack.blocks):
        name = block_id.split(":", 1)[-1]
        report.count("plant_support.update_shape_unmapped")
        cls = candidates.get(block_id)
        if cls is None:
            continue
        rule = rules.get(name)
        if rule is None:
            report.fail(
                "plant_support.update_shape_unmapped", block_id,
                f"上游 {cls} 的 updateShape/destroy 会在连接断裂时回收方块，"
                "但产物里既没有等价 CE 行为，`support.load_breakdown_rules` 里也没有记录 ——"
                "这条语义目前没有任何人负责")
            continue
        if rule.blocked:
            report.note("plant_support.update_shape_unmapped",
                        f"{block_id}：断开方向 {rule.breakdown_direction}，"
                        f"CE 表达不了 —— {rule.blocked}")
            continue
        if rule.covered_by_support_rule:
            continue

        report.count("plant_support.breakdown_contract")
        parsed = [parse_support_lines(list(entry)) for entry in rule.ce_behaviors]
        if any(entry is None for entry in parsed):
            report.fail("plant_support.breakdown_contract", block_id,
                        f"bnlib.support 给出的破坏行为解析失败：{rule.ce_behaviors}")
            continue
        declared = [entry for entry in behavior_entries(pack.blocks[block_id])[0]
                    if str(entry.get("type")) in SUPPORT_BEHAVIOR_TYPES]
        expected_set = {canonical_support(entry) for entry in parsed}
        declared_set = {canonical_support(entry) for entry in declared}
        if expected_set != declared_set:
            report.fail("plant_support.breakdown_contract", block_id,
                        "破坏行为与上游真值不符：期望 "
                        f"{sorted(map(str, expected_set)) or '无声明'}，产物 "
                        f"{sorted(map(str, declared_set)) or '无声明'}")


def check_plant_support(pack: Pack, report: Report) -> None:
    """植物支撑：用 `bnlib.support` 的上游真值逐块核对产物里的 CE 支撑行为。

    真值来源（不是名字启发式）：`support.load_support_rules(upstream)` 读上游注册处的
    `.addTrait(NetherSurvival.xxx())`、`NetherSurvival` 方法体、`canSurvive` 方向，以及
    `data/**/tags/block/*.json` 的标签成员。

    分三种情况，全部都要红：
      1. 规则可证实 → 产物必须写出**等价**的支撑行为（类型 + 白名单/黑名单逐项相同）；
      2. 规则受阻（wover 公共标签成员在本仓库不可见、源码缺失）→ 显式失败并打印
         受阻原因，绝不因为「反正没法写」就跳过；
      3. 产物声明了支撑行为，但上游对该方块根本没有支撑 trait → 也是失败：凭空加
         限制会让植物永远长不出来。
    """
    try:
        sys.path.insert(0, str(ROOT / "scripts"))
        from bnlib import support
    except ImportError as exc:  # 环境/并发写文件导致模块不可导入
        report.skip("plant_support.contract", f"无法导入 bnlib.support：{exc}")
        return

    upstream_root = ROOT / "upstream/BetterNether"
    if not upstream_root.is_dir():
        report.skip("plant_support.contract", f"缺少上游目录 {upstream_root}")
        return

    rules = _support_rules(upstream_root)
    if not rules:
        report.skip("plant_support.contract",
                    "上游没有任何支撑 trait（load_support_rules 返回空），无法判定")
        return

    published = set(pack.blocks)
    for block_id in sorted(pack.blocks):
        config = pack.blocks[block_id]
        name = block_id.split(":", 1)[-1]
        all_entries, problems = behavior_entries(config)
        declared = [entry for entry in all_entries
                    if str(entry.get("type")) in SUPPORT_BEHAVIOR_TYPES]

        for problem in problems:
            report.count("plant_support.malformed")
            report.fail("plant_support.malformed", block_id, f"行为结构不合法：{problem}")

        seen_types: set[str] = set()
        for entry in declared:
            kind = str(entry.get("type"))
            report.count("plant_support.duplicate")
            if kind in seen_types:
                report.fail("plant_support.duplicate", block_id,
                            f"同一个方块声明了两条 {kind}（后一条不会生效）")
            seen_types.add(kind)

        # 独立核对 1：白名单里不许出现本包没发布的方块 id。
        # 引用一个不存在的 id 时 CE 找不到方块，植物永远不会存活；support.py 只在
        # 调用方传了 available_ids 时才剔除，所以门禁必须自己再查一遍。
        for entry in declared:
            for key in BLOCK_ID_FIELDS:
                value = entry.get(key)
                if not isinstance(value, (list, tuple)):
                    continue
                for item in value:
                    ident = str(item)
                    if ident.startswith("betternether:") and ident not in published:
                        report.count("plant_support.dangling_block")
                        report.fail("plant_support.dangling_block", block_id,
                                    f"{key} 引用了未发布的 {ident}（CE 找不到方块，"
                                    "植物永远不会存活）")

        # 独立核对 2：`blacklist: false`（白名单模式）却一个白名单字段都没有 =
        # 永远不放行；`blacklist: true`（黑名单模式）允许空列表，那是「任何坚固面」。
        for entry in declared:
            report.count("plant_support.empty_whitelist")
            if entry.get("blacklist") is not False:
                continue
            if not any(entry.get(field) for field in WHITELIST_FIELDS):
                report.fail("plant_support.empty_whitelist", block_id,
                            f"{entry.get('type')} 是白名单模式（blacklist: false），"
                            "却没有任何 bottom_blocks / bottom_block_tags / attached_blocks："
                            "这个方块永远不会被放行")

        rule = rules.get(name)
        if rule is None:
            report.count("plant_support.unexpected")
            if declared:
                report.fail("plant_support.unexpected", block_id,
                            "上游没有支撑 trait，产物却声明了 "
                            + "、".join(str(entry.get("type")) for entry in declared)
                            + "（凭空加限制会让方块永远长不出来）")
            continue

        report.count("plant_support.contract")
        states = config.get("states")
        if not isinstance(states, dict):
            # 这套 schema 允许两种写法：`states:`（按外形分组）与 `state:`（只有一个外形）。
            # 产物里 168 个方块用 `state:`、50 个用 `states:`。只读复数会让前者看起来
            # 「没声明任何属性」，facing 支撑规则于是把它们全判成红（实测 4 个贴面植物）。
            states = config.get("state")
        declared_props = (set((states or {}).get("properties") or {})
                          if isinstance(states, dict) else set())

        # 贴面支撑只能靠 `facing` 表达：规则方向是 facing 却没声明该属性时，
        # 不管 behavior 写没写都不会生效，所以这一条要单独判红。
        report.count("plant_support.facing_missing")
        if getattr(rule, "direction", None) == "facing" and "facing" not in declared_props:
            report.fail("plant_support.facing_missing", block_id,
                        "上游支撑方向是 facing（贴面），但方块没有声明 facing 属性："
                        "directional_attached_block 不会生效")
            continue

        expected = support.inspect_support(
            name, rules=rules, available_ids=published,
            available_properties=declared_props)

        if expected.blocked:
            report.count("plant_support.blocked")
            report.fail("plant_support.blocked", block_id,
                        f"上游规则受阻，产物无法闭合：{expected.blocked}")
            continue

        parsed_expected = [parse_support_lines(entry) for entry in expected.entries]
        if any(entry is None for entry in parsed_expected):
            report.fail("plant_support.contract", block_id,
                        f"bnlib.support 给出的支撑行为解析失败：{expected.entries}")
            continue
        expected_set = {canonical_support(entry) for entry in parsed_expected}
        declared_set = {canonical_support(entry) for entry in declared}
        if expected_set != declared_set:
            report.fail("plant_support.contract", block_id,
                        "支撑行为与上游真值不符：期望 "
                        f"{sorted(map(str, expected_set)) or '无声明'}，产物 "
                        f"{sorted(map(str, declared_set)) or '无声明'}")
            continue

        if expected.required_properties:
            missing = [prop for prop in expected.required_properties
                       if prop not in declared_props]
            if missing:
                report.fail("plant_support.contract", block_id,
                            f"支撑行为需要属性 {missing}，但方块没有声明")


# --------------------------------------------------------------------------- 植物载体

def upstream_collision_kinds(upstream: Path) -> dict[str, frozenset[str]]:
    """上游每个方块的**全部状态**碰撞箱集合（`block_shapes.txt`）。

    返回 `{方块 id: {"empty", "[0.25,0,0.25|0.75,1,0.75]", …}}`；文件缺失返回空字典
    （调用方据此把规则标成未实现，而不是当成通过）。
    """
    path = upstream / COLLISION_TRUTH_FILE
    if not path.is_file():
        return {}
    collected: dict[str, set[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = COLLISION_TRUTH_PATTERN.match(line.strip())
        if match is None:
            continue
        collected.setdefault(match.group(1), set()).add(match.group(2))
    return {block_id: frozenset(boxes) for block_id, boxes in collected.items()}


def collision_kind(boxes: frozenset[str] | None) -> str | None:
    """上游碰撞箱集合 → `empty` / `full` / `partial`；没有真值返回 None。

    * 全状态 `empty` → `empty`（玩家可以穿过去）；
    * 全状态都是整格箱子 → `full`；
    * 其余（含「一半状态 empty、一半不是」的多状态方块）→ `partial`。
      `partial` 刻意取宽：只要有一个状态不是整格，它就不可能靠满格载体如实复现。
    """
    if not boxes:
        return None
    if boxes == {"empty"}:
        return "empty"
    if boxes == {FULL_COLLISION_BOX}:
        return "full"
    return "partial"


def carrier_collision_kind(carrier: str | None) -> str | None:
    """载体给出的碰撞箱语义：`empty` / `full` / `partial`；判不出来返回 None。

    只看显式表（{@link COLLISION_FREE_POOLS} 等），不按名字猜。判不出来的载体由
    调用方按「无法证明合规」处理 —— 宁可红，也不默认它是无碰撞的。
    """
    if not carrier:
        return None
    if carrier in COLLISION_FREE_POOLS or carrier in COLLISION_FREE_VANILLA_BLOCKS:
        return "empty"
    if carrier in FULL_COLLISION_POOLS or carrier in FULL_COLLISION_VANILLA_BLOCKS:
        return "full"
    if carrier in PARTIAL_COLLISION_POOLS or carrier in PARTIAL_COLLISION_VANILLA_BLOCKS:
        return "partial"
    return None


def is_occluding_carrier(carrier: str | None) -> bool:
    """这个载体是不是「完整方块 + SOLID 渲染」（借来当植物 = 透视）。"""
    if not carrier:
        return False
    return carrier in OCCLUDING_POOLS or carrier in OCCLUDING_VANILLA_BLOCKS


def is_dangerous_carrier(carrier: str | None) -> bool:
    """这个载体是不是自带「伤害 / 红石」语义（借来当植物 = 不是植物了）。"""
    if not carrier:
        return False
    return carrier in DANGEROUS_POOLS


def partial_collision_fits() -> tuple[dict[str, tuple[str, str, str]] | None, str]:
    """读生成器登记的**碰撞箱近似表**，返回 `(表, 读不到的原因)`。

    真值只有一份：`scripts/bnlib/blocks.PARTIAL_COLLISION_FITS`（每方块一条
    `(形状档位, 上游碰撞箱摘要, 保真说明)`）。门禁不另存一份近似表 —— 两份真值必然漂移，
    而漂移的那一份恰好会在「谁负责」的问题上互相甩锅。

    读不到（模块不可导入 / 常量被改名）时返回 `(None, 原因)`，调用方把
    `plant_states.partial_collision_unrecorded` 标成未实现：不伪绿，也不自己编一张表。
    """
    try:
        from bnlib import blocks as generator  # noqa: PLC0415 —— 生成器很大，按需导入
    except Exception as error:  # noqa: BLE001 —— 语法错/缺依赖都算「取不到真值」
        return None, f"导入 bnlib.blocks 失败：{error.__class__.__name__}: {error}"
    fits = getattr(generator, "PARTIAL_COLLISION_FITS", None)
    if not isinstance(fits, Mapping):
        return None, "bnlib.blocks 里没有 PARTIAL_COLLISION_FITS 表，无法核对碰撞箱近似记录"
    table: dict[str, tuple[str, str, str]] = {}
    for name, record in fits.items():
        if isinstance(record, (tuple, list)) and len(record) == 3:
            table[str(name)] = (str(record[0]), str(record[1]), str(record[2]))
    # 走**写死状态载体**的方块（容器）不在 `PARTIAL_COLLISION_FITS` 里：它们的载体由
    # `PINNED_CARRIER_TARGETS` 点名，碰撞箱摘要与代价写在那一份真值里。这里并进来，
    # 让「这块的近似谁签的字」仍然只有一处可查（真值不复制，只做视图合并）。
    targets = getattr(generator, "PINNED_CARRIER_TARGETS", None)
    if isinstance(targets, Mapping):
        for name, target in targets.items():
            if not isinstance(target, Mapping):
                continue
            table[str(name)] = (f"pinned:{target.get('carrier', '')}",
                                str(target.get("box", "")), str(target.get("note", "")))
    if not table:
        return None, "PARTIAL_COLLISION_FITS 是空的，无法核对任何碰撞箱近似记录"
    return table, ""


def collision_pool_waivers() -> tuple[dict[str, str], str]:
    """读生成器登记的「有碰撞载体放行表」（`bnlib.blocks.COLLISION_POOL_WAIVERS`）。

    与 {@link partial_collision_fits} 同一原则：真值只有一份，门禁不另存一份放行名单，
    否则两份必然漂移。读不到就返回空表 + 原因，让规则按「无豁免」判红，不偷偷放行。
    """
    try:
        from bnlib import blocks as generator  # noqa: PLC0415
    except Exception as error:  # noqa: BLE001
        return {}, f"导入 bnlib.blocks 失败：{error.__class__.__name__}: {error}"
    table = getattr(generator, "COLLISION_POOL_WAIVERS", None)
    if not isinstance(table, Mapping):
        return {}, "bnlib.blocks 里没有 COLLISION_POOL_WAIVERS 表"
    return ({str(pool): str(reason) for pool, reason in table.items() if str(reason).strip()},
            "")


def occluding_carrier_waivers() -> tuple[dict[str, str], str]:
    """读「满格载体给容器用」的**可见签字表**（`bnlib.blocks.OCCLUDING_CARRIER_WAIVERS`）。

    与 {@link collision_pool_waivers} 同一原则：真值只有一份，门禁不另存一份放行名单。
    读不到就返回空表 + 原因 —— 规则照常判红，不因为这条路存在而整体放行。
    键是方块 id 的短名（`crimson_chest`），逐块签字。
    """
    try:
        from bnlib import blocks as generator  # noqa: PLC0415
    except Exception as error:  # noqa: BLE001
        return {}, f"导入 bnlib.blocks 失败：{error.__class__.__name__}: {error}"
    table = getattr(generator, "OCCLUDING_CARRIER_WAIVERS", None)
    if not isinstance(table, Mapping):
        return {}, "bnlib.blocks 里没有 OCCLUDING_CARRIER_WAIVERS 表"
    return ({str(name): str(reason) for name, reason in table.items() if str(reason).strip()},
            "")


def check_auto_state_groups(pack: Pack, report: Report) -> None:
    """产物里出现的每个 `auto_state` 值都必须是 CE 枚举里的合法组名。

    这是「组名写错 → 方块完全没有外观」的根因检查。以前的规则全都拿 `bnlib.blocks`
    自己写的表当参照，两边一起错就永远绿；这里改成对**CE 的真实枚举**验。

    合法名单见 {@link KNOWN_AUTO_STATE_GROUPS}；不在名单里就判红，并把合法值列进消息，
    免的下一个人又去猜「是不是少写了一个字母」。
    """
    legal = "、".join(sorted(KNOWN_AUTO_STATE_GROUPS))
    for block_id, config in sorted(pack.blocks.items()):
        for where, appearance in appearances(config):
            carrier = appearance.get("auto_state")
            if carrier is None:
                continue
            if isinstance(carrier, Mapping):
                # `auto_state: {type: …, id: …}` 是 CE 的另一种合法写法（显式组名 + 状态 id），
                # 组名在 `type` 上，同样必须是枚举值。
                value = str(carrier.get("type", "")).strip()
            else:
                value = str(carrier).strip()
            report.count("plant_states.known_auto_state_group")
            if value not in KNOWN_AUTO_STATE_GROUPS:
                report.fail("plant_states.known_auto_state_group", block_id,
                            f"{where}: auto_state={value!r} 不在 CE AutoStateGroup 枚举里 —— "
                            "该方块在游戏里会完全没有外观。合法值：" + legal)


def check_plant_states(pack: Pack, upstream: Path, report: Report) -> None:
    """植物/装饰的载体契约：不能借会遮挡的、不能借有碰撞的、不能再外挂展示实体。

    判定链：上游 `block_shapes.txt` 给出**逐状态碰撞箱**（本包收录的 56 个植物/装饰里
    42 个 `empty`、14 个 `partial`、0 个满格），产物给出**载体**，两边用显式语义表对照：

      * {@link COLLISION_FREE_POOLS}：无碰撞载体（绊线三组 / 树苗 / 甘蔗 / 海带 / 三种藤蔓）；
      * {@link OCCLUDING_POOLS}：完整方块 + SOLID 渲染 → 遮挡剔除（植物借它 = 透视）；
      * {@link FULL_COLLISION_POOLS}：树叶族 → 不遮挡但满格碰撞（植物借它 = 挡住玩家）。

    为什么必须在**产物**层再查一遍：载体是在 `blocks.py` 分配池的时候定下来的，一次
    「顺手换成 solid」不会让任何单元测试红，只会让线上植物变透视或变墙。这四条规则盯的
    正是「植物变成实心障碍」和「透视」两个最坏的结果。
    """
    kinds = upstream_collision_kinds(upstream)
    plant_rules = (
        "plant_states.no_entity_renderer",
        "plant_states.collision_pool_mismatch",
        "plant_states.occluding_carrier",
        "plant_states.occluding_carrier_waived",
        "plant_states.partial_collision_unrecorded",
    )
    if not kinds:
        for rule in plant_rules:
            report.skip(rule, f"缺少上游碰撞真值 {COLLISION_TRUTH_FILE}，无法判定载体碰撞语义")
        return
    fits, fits_problem = partial_collision_fits()
    if fits is None:
        report.skip("plant_states.partial_collision_unrecorded", fits_problem)
    exempt = {block_id: reason for block_id, reason in ENTITY_RENDERER_EXEMPTIONS.items()
              if reason and reason.strip()}
    waivers, waivers_problem = collision_pool_waivers()
    occ_waivers, occ_waivers_problem = occluding_carrier_waivers()
    waived_hits: dict[str, int] = {}
    occ_waived: dict[str, int] = {}

    for block_id in sorted(pack.blocks):
        upstream_kind = collision_kind(kinds.get(block_id))
        if upstream_kind is None or upstream_kind == "full":
            # 整格方块（木头/石头…）不是植物/装饰，本组规则不适用；上游没有的方块交给别的规则。
            continue
        views = appearances(pack.blocks[block_id])

        # 规则 1：不得再外挂展示实体。
        report.count("plant_states.no_entity_renderer")
        rendered = [name for name, view in views if view.get("entity_renderer")]
        if rendered and block_id not in exempt:
            report.fail("plant_states.no_entity_renderer", block_id,
                        f"外观 {rendered} 仍用 entity_renderer（展示实体渲染）；"
                        f"植物/装饰应改成真实方块状态 + 模型。若确需保留，必须逐 id 登记到 "
                        f"ENTITY_RENDERER_EXEMPTIONS 并写明原因（空原因不算登记）")

        approximations: list[str] = []
        for name, view in views:
            carrier, _shared_id = carrier_of(view)
            if carrier is None:
                # 没有载体 = 没有碰撞也没有遮挡（纯模型 / 透明共享状态），
                # 碰撞语义交给规则 1 与 transparency.*。
                continue
            kind = carrier_collision_kind(carrier)

            # 规则 2：上游无碰撞的方块，载体也必须无碰撞。
            if upstream_kind == "empty":
                if kind != "empty" and carrier in waivers:
                    # 登记放行：不是静默通过 —— 计数、聚合，并在结论里报出受影响的外观数。
                    report.count("plant_states.collision_pool_waived")
                    waived_hits[carrier] = waived_hits.get(carrier, 0) + 1
                    continue
                report.count("plant_states.collision_pool_mismatch")
                if kind != "empty":
                    reason = ("载体是完整方块（满格碰撞）" if kind == "full"
                              else "载体碰撞箱无法证实为空" if kind is None
                              else "载体自带部分碰撞箱")
                    report.fail("plant_states.collision_pool_mismatch", block_id,
                                f"外观 {name} 的载体是 {carrier}（{reason}），"
                                f"但上游该方块 collision=empty —— 植物会变成实心障碍。"
                                f"无碰撞载体只有 {sorted(COLLISION_FREE_POOLS)}")

            # 规则 3：不得借会遮挡剔除的载体（否则透视）。
            report.count("plant_states.occluding_carrier")
            if is_occluding_carrier(carrier):
                waiver_reason = occ_waivers.get(block_id.partition(":")[2])
                if waiver_reason:
                    # 逐块签字的**显式放行**：不是静默通过 —— 计数、聚合并写进结论。
                    report.count("plant_states.occluding_carrier_waived")
                    occ_waived[carrier] = occ_waived.get(carrier, 0) + 1
                else:
                    report.fail("plant_states.occluding_carrier", block_id,
                                f"外观 {name} 的载体是 {carrier}（完整方块 + SOLID 渲染，"
                                f"会遮挡剔除相邻面），植物模型填不满方块 → 玩家能透过植物看到"
                                f"地下（透视）。确需保留（例如容器要整块碰撞），必须逐块登记到 "
                                f"bnlib.blocks.OCCLUDING_CARRIER_WAIVERS 并写明原因")

            # 规则 5：partial 碰撞的方块放进形状差异明显的池 / 有危险语义的池，必须有记录。
            if upstream_kind == "partial":
                report.count("plant_states.partial_collision_unrecorded")
                if carrier in PARTIAL_COLLISION_POOLS or is_dangerous_carrier(carrier):
                    approximations.append(f"{name}→{carrier}")

        if upstream_kind != "partial" or fits is None:
            continue
        short_name = block_id.partition(":")[2]
        record = fits.get(short_name)
        fit_detail = (f"上游 {record[1]}；保真说明「{record[2]}」" if record
                      else "PARTIAL_COLLISION_FITS 里没有这块的条目")
        if not approximations:
            # 落在无碰撞池里的 partial 方块不需要签字（形状是「放弃碰撞」的有意选择），
            # 但事实必须看得见，不能静默。
            report.note("plant_states.partial_collision_unrecorded",
                        f"{block_id}：{fit_detail}")
            continue
        if record is not None and record[2].strip():
            continue
        report.fail("plant_states.partial_collision_unrecorded", block_id,
                    f"上游 collision 是 partial（{sorted(kinds[block_id])[:2]}），产物把外观放进了"
                    f"形状差异明显的载体 {approximations}，但 bnlib.blocks.PARTIAL_COLLISION_FITS 里"
                    f"{'没有这块的条目' if record is None else '保真说明是空的'}"
                    f"（现有记录：{fit_detail}）—— 碰撞箱近似必须有人签字，不能静默")
    if waivers_problem:
        report.skip("plant_states.collision_pool_waived", waivers_problem)
    for pool, hits in sorted(waived_hits.items()):
        report.note("plant_states.collision_pool_waived",
                    f"{hits} 个无碰撞外观借了有碰撞的 `{pool}`：{waivers[pool]}")
    if occ_waivers_problem:
        report.skip("plant_states.occluding_carrier_waived", occ_waivers_problem)
    for carrier, hits in sorted(occ_waived.items()):
        names = sorted(name for name, reason in occ_waivers.items()
                       if reason)
        report.note("plant_states.occluding_carrier_waived",
                    f"{hits} 个外观借了会遮挡的 `{carrier}`，已逐块签字放行"
                    f"（blocks.OCCLUDING_CARRIER_WAIVERS，共 {len(names)} 块）："
                    + "；".join(f"{name} —— {occ_waivers[name]}" for name in names))


def pool_budgets() -> dict[str, int]:
    """生成器声明的**本期额度**（`bnlib.blocks.POOL_BUDGET`），池名 → 允许占用的槽位数。

    语义是「本包还能用几个」，**不是 CE 池总容量**：CE 的 `AutoStateGroup` 跨包共享，
    真实可用数由 `scripts/bnlib/ce_pool_ledger.py` 的余量账本给出（`free = cap - used`）。
    把总容量当额度写进来，构建不会红，线上才会表现为「视觉状态组已满、方块没有外观」——
    所以这条常量必须与账本的 `free` 对得上（由 {@link check_state_slot_budget} 断言）。

    读不到（常量被改名/删除、或模块本身暂时不可导入）时返回空字典，调用方把规则标成
    未实现 —— 不拿一份过期表冒充真值，也不用一个 traceback 掩盖真正的原因。
    """
    try:
        from bnlib import blocks as generator  # noqa: PLC0415 —— 生成器很大，按需导入
    except Exception:  # noqa: BLE001 —— 语法错/缺依赖都算「取不到真值」，由调用方标未实现
        return {}
    budget = getattr(generator, "POOL_BUDGET", None)
    if not isinstance(budget, Mapping) or not budget:
        return {}
    return {str(pool): int(limit) for pool, limit in budget.items()}


def pool_ledger() -> dict[str, dict[str, object]]:
    """跨包共享池的余量账本（`bnlib.ce_pool_ledger`），池名 → cap/used/free/…。"""
    try:
        from bnlib import ce_pool_ledger  # noqa: PLC0415
    except Exception:  # noqa: BLE001
        return {}
    rows = getattr(ce_pool_ledger, "POOL_LEDGER", None)
    if not isinstance(rows, Mapping) or not rows:
        return {}
    return {str(pool): dict(entry) for pool, entry in rows.items()
            if isinstance(entry, Mapping)}


def state_slot_usage(pack: Pack) -> dict[str, int]:
    """产物里每个载体池**实际占用**的槽位数。

    计数口径与 CE 的分配语义一致：

      * `auto_state: tripwire`（不带 id）→ 每个外观各占一个槽位；
      * `auto_state: {type: sugar_cane, id: transparent}` → 同 id 的方块共用一个原版状态，
        整个包只占 1 个槽位（`transparent` / `climbable` 就是这种共享宿主）；
      * `state: "minecraft:xxx[...]"` → 用的是指定原版状态，不从池里分配，不占槽位。
    """
    shared: set[tuple[str, str]] = set()
    usage: dict[str, int] = {}
    for block_id in sorted(pack.blocks):
        for _name, view in appearances(pack.blocks[block_id]):
            carrier, shared_id = carrier_of(view)
            if carrier is None or carrier.startswith("minecraft:") or carrier == "__template__":
                continue
            if shared_id is not None:
                if (carrier, shared_id) in shared:
                    continue
                shared.add((carrier, shared_id))
            usage[carrier] = usage.get(carrier, 0) + 1
    return usage


def check_state_slot_budget(pack: Pack, report: Report) -> None:
    """池槽位预算：账本自洽、额度不超余量、产物不超额度 —— 三重对账。

    为什么三重而不是「占用 <= 额度」一条：

      1. **账本自身**必须自洽（`cap - used == free`，三者非负）。账本来自一次采集
         （`mappings.yml` 的启用条目数 + `cache/visual_block_states.json` 的占用量），
         抄错一个数字，后面两条断言就都在验错的东西；
      2. 生成器的 `POOL_BUDGET`（本期额度）不得超过账本的 `free`。**把 CE 池总容量当
         额度**是这次改造最容易犯的错：多包的池是共享的，额度写大了本地全绿、线上没外观；
      3. 产物里每个池的真实占用不得超过 `POOL_BUDGET`。生成器分了额度但产物绕过它硬写
         池名时，只有产物层能看见。

    任何一条缺真值（账本读不到 / `POOL_BUDGET` 读不到）都把规则标成未实现，而不是跳过。
    """
    budgets = pool_budgets()
    ledger = pool_ledger()
    if not budgets or not ledger:
        report.skip("plant_states.state_slot_budget",
                    "读不到 bnlib.blocks.POOL_BUDGET 或 bnlib.ce_pool_ledger.POOL_LEDGER，"
                    "没有可比对的额度与余量")
        return

    # 断言 1：申请了额度的池必须都有账本记录（漏账 = 没采集过 = 无法证明放得下）。
    # 额度为 0 的池不占任何槽位，不需要账；它若真的被用了，断言 3 会以「超额」抓住。
    for pool in sorted(budgets):
        if budgets[pool] <= 0:
            continue
        report.count("plant_states.state_slot_budget")
        if pool not in ledger:
            report.fail("plant_states.state_slot_budget", pool,
                        f"池 {pool} 在 bnlib.blocks.POOL_BUDGET 里申请了额度 {budgets[pool]}，"
                        f"但 bnlib.ce_pool_ledger 里没有它的余量记录 —— 无法证明这个额度放得下。"
                        f"请按 mappings.yml + cache/visual_block_states.json 重采后补进账本")

    # 断言 2：账本自洽 + 额度不超余量。
    for pool in sorted(ledger):
        entry = ledger[pool]
        report.count("plant_states.state_slot_budget")
        if entry.get("cap") is None or entry.get("free") is None:
            # `cap` 来自 CE **内置** mappings 的组（树叶两池），用户 mappings.yml 里没有
            # 这两个组名，取不到总槽位。这里不是放行：产物占用仍受断言 3（<= POOL_BUDGET）
            # 约束，只是「这一池还剩多少空位」不可判定，如实写进报告而不是编一个数字。
            report.note(
                "plant_states.state_slot_budget",
                f"池 {pool} 的 cap 无法采集（CE 内置 mappings 的组，用户 mappings.yml 里没有"
                f"该组名），跳过余量断言；额度 {budgets.get(pool, 0)} 仍受产物占用校验。"
                f"已占实测 {entry.get('used')}。{entry.get('note') or ''}".rstrip(),
            )
            continue
        try:
            cap = int(entry["cap"])  # type: ignore[arg-type]
            used = int(entry["used"])  # type: ignore[arg-type]
            free = int(entry["free"])  # type: ignore[arg-type]
        except (KeyError, TypeError, ValueError):
            report.fail("plant_states.state_slot_budget", pool,
                        f"账本条目 {pool} 缺少 cap/used/free 或不是整数：{dict(entry)}")
            continue
        if min(cap, used, free) < 0:
            report.fail("plant_states.state_slot_budget", pool,
                        f"账本条目 {pool} 出现负数（cap={cap} used={used} free={free}）")
        if cap - used != free:
            report.fail("plant_states.state_slot_budget", pool,
                        f"账本条目 {pool} 不自洽：cap - used = {cap - used}，但 free 写的是 {free}"
                        f"（cap={cap} used={used}）—— 账本抄错会让额度断言失效")
        if pool in budgets and budgets[pool] > free:
            report.fail("plant_states.state_slot_budget", pool,
                        f"池 {pool} 的额度 {budgets[pool]} 超过了真实余量 {free}"
                        f"（cap={cap} used={used}）—— 别的包已经在用这些状态，"
                        f"超额申请时 CE 会拒绝分配，相关方块没有外观")

    # 断言 3：产物里的真实占用不超额度。
    usage = state_slot_usage(pack)
    grouped: dict[str, int] = {}
    for pool, count in usage.items():
        bucket = SHARED_POOL_KEYS.get(pool, pool)
        grouped[bucket] = grouped.get(bucket, 0) + count

    for pool in sorted(grouped):
        count = grouped[pool]
        report.count("plant_states.state_slot_budget")
        if pool in UNBUDGETED_POOLS:
            continue
        budget = budgets.get(pool)
        if budget is None:
            report.fail("plant_states.state_slot_budget", pool,
                        f"池 {pool} 占用了 {count} 个槽位，但 bnlib.blocks.POOL_BUDGET 里没有它 —— "
                        f"无法证明不超限。新池必须先拿到额度并记进账本")
        elif count > budget:
            report.fail("plant_states.state_slot_budget", pool,
                        f"池 {pool} 占用了 {count} 个槽位，本期额度只有 {budget}（超额 "
                        f"{count - budget}）—— CE 会拒绝分配状态，相关方块没有外观")


# --------------------------------------------------------------------------- 输出

def render(report: Report, max_examples: int) -> None:
    print("方块发布契约校验（生成产物）")
    print("=" * 72)
    for rule, description in RULE_DESCRIPTIONS.items():
        failures = report.failures(rule)
        checked = report.checked.get(rule, 0)
        notes = report.notes.get(rule, [])
        if rule in report.unimplemented:
            print(f"[UNIMPLEMENTED] {rule}  {RULE_DESCRIPTIONS[rule]}")
            print(f"                原因：{report.unimplemented[rule]}")
            for finding in failures[:max_examples]:
                print(f"    仍发现：{finding.subject} —— {finding.detail}")
            continue
        if not failures:
            print(f"[OK]   {rule}  已检查 {checked} 项  {description}")
            for note in notes[:max_examples]:
                print(f"    · {note}")
            if len(notes) > max_examples:
                print(f"    …… 另有 {len(notes) - max_examples} 条（--verbose 查看全部）")
            continue
        print(f"[FAIL] {rule}  {len(failures)} 项违规／已检查 {checked} 项  {description}")
        for finding in failures[:max_examples]:
            print(f"    - {finding.subject}：{finding.detail}")
        if len(failures) > max_examples:
            print(f"    …… 另有 {len(failures) - max_examples} 项（--verbose 查看全部）")
    print("-" * 72)
    total = len(report.findings)
    print(f"违规合计：{total} 项；未实现规则：{len(report.unimplemented)} 条")


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验生成方块配置的发布契约")
    parser.add_argument("--generated", type=Path, default=DEFAULT_GENERATED,
                        help=f"extract_bn 的产物根目录（默认 {DEFAULT_GENERATED}）")
    parser.add_argument("--upstream", type=Path, default=DEFAULT_UPSTREAM,
                        help=f"只读上游 BetterNether 目录（默认 {DEFAULT_UPSTREAM}）")
    parser.add_argument("--allow-unimplemented", action="store_true",
                        help="把未实现规则降级为警告（调试用；默认按失败处理）")
    parser.add_argument("--verbose", action="store_true", help="打印全部违规，不只前若干条")
    parser.add_argument("--max-examples", type=int, default=20,
                        help="每条规则最多打印多少条违规（默认 20）")
    args = parser.parse_args(list(argv) if argv is not None else None)

    generated = args.generated if args.generated.is_absolute() else ROOT / args.generated
    upstream = args.upstream if args.upstream.is_absolute() else ROOT / args.upstream
    if not upstream.is_dir():
        raise SystemExit(f"缺少上游目录：{upstream}")

    pack = Pack.load(generated)
    report = Report()

    check_mining(pack, upstream, report)
    check_glass_mining_invariant(pack, upstream, report)
    check_orientation(pack, report)
    check_vines(pack, upstream, report)
    check_removal_references(pack, report)
    check_transparency(pack, upstream, report)
    check_plant_support(pack, report)
    check_plant_breakdown(pack, report)
    check_plant_states(pack, upstream, report)
    check_auto_state_groups(pack, report)
    check_state_slot_budget(pack, report)

    render(report, 10_000 if args.verbose else args.max_examples)
    if report.findings:
        return 1
    if report.unimplemented and not args.allow_unimplemented:
        print("存在未实现规则：默认按失败处理（确需放行请显式 --allow-unimplemented）")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

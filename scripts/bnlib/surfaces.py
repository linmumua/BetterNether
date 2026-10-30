#!/usr/bin/env python3
"""上游 BetterNether 各群系**表面材质**（`surface()` 规则）的提取表 —— 本文件是「上游真值」。

## 这张表从哪来

逐个读了 `upstream/BetterNether/src/main/java/org/betterx/betternether/world/biomes/*.java`
（23 个群系类）的 `surface(BiomeSurfaceRuleBuilder builder)` 方法，把子类额外声明的
`floor` / `belowFloor` / `ceil` / `aboveCeil` / `chancedFloor` / `rule` 全部展开。

每条记录的第 4 个字段 `chance` 说明占比是怎么来的，取值只有四种：

  * `declared`  —— 上游用 `floor(x)` / `ceil(x)` 无条件声明，占比就是 100%，无推断。
  * `measured`  —— 上游注释里写了实测占比（唯一一处：Gloomwood 的 1/19/80）。
  * `provider`  —— 占比能把 `NumericProvider` 的源码算出来（ grasslands 的 nextInt(3)、
                   forest_edge 的 nextInt(4)>0、flooded_deltas 的 nextInt(3) 取 2 项表）。
  * `inferred`  —— 由 `roughNoise(Noise, t)` 的阈值推得（推理依据见下）。
  * `unknown`   —— 阈值烧在 wover 库里，本仓库没有该库源码，只能按 50% 近似。**每条都标了。**

## 为什么 `roughNoise(Noise, t)` 可以读成「占比 = t」

wover 的源码不在本仓库（`de.ambertation.wover.surface.api.Conditions` 只是 import），
所以这条是**推理**，不是抄来的，依据有两条：

  1. `UpsideDownForestCleared.java:60-73` 的上游注释：地面苔阈值 0.221，而父类
     `UpsideDownForest` 是 0.021，注释明说「苔明显更多」。0.221 → 明显多于 0.021，
     这种单调关系与「阈值即面积占比」完全一致（2.1% → 22.1%）。
  2. `NetherSwampland.java:68-84` 的两个阈值 0.14 / 0.19 恰好落在「草 14%、灵魂沙 19%」
     这种量级上，而不是 0.14 在 [0,1] 噪声上「高于阈值」会得到的 ~43%。

因此本文件把 `roughNoise(n, t)` 记为占比 t，并在 `note` 里注明这是推理。
若日后拿到 wover 源码而结论不同，只需改 `floor` 里的数字 —— 结构不用动。

## 占比语义（Java 侧必须一致）

`floor` 里每项是**绝对占比**，同一群系的所有项之和 ≤ 1.0（本表内都有校验）。
**剩下的那部分（1 - Σ）不是任何列出的材质，而是「不动」，即落在生成器的默认填充
（下界岩）上**。这不是偷懒：上游这些序列的兜底项本来就是 NETHERRACK
（`WartForest.java:66-99` 的 switch 列表末项、`WartForestEdge.java:43-64` 的兜底），
所以「余量 = 下界岩」在那些群系是**精确**的，不是近似。

唯一的例外是 `nether_grasslands`：它的三层是 1/3 + 1/3 + 1/3，Σ = 1.0，**没有余量**。
若消费方只读 `top` / `accent` 而忽略第三个材质，那个群系会有 1/3 的列落回下界岩。
这一点在 Java 侧 `Profile` 的 `accent2` 字段注释里也重复了一遍。

## 与 Java 的关系

`common/src/main/java/dev/mumu/betternether/biome/BiomeSurface.java` 的 `PROFILES` 表是本文件
`project()` 的输出。Java 只有 3 个地面槽 + 2 个天花板槽，装不下 4 项以上的混合，
所以 `project()` 会把超出的材质**丢掉并在打印时喊出来**（`DROPPED` 一栏），
而不是安静地少一种方块。

直接 `python scripts/bnlib/surfaces.py` 会打印「上游真值 + Java 投影 + 丢掉了什么」，
并在找到 `plugin/build/generated/bn/reports/block-manifest.json` 时核对每个 id 是否存在。
"""

from __future__ import annotations

import json
import pathlib
import sys

# 上游 Java 常量 → 本移植产出 id。
# 左边是源码里出现的引用，右边是 CE 包里的方块 id（NAMESPACE 恒为 betternether，见 namespace.py）。
CONSTANT_TO_ID = {
    # registry/block/NetherTerrainBlocks.java
    "NetherTerrainBlocks.MUSHROOM_GRASS": "betternether:mushroom_grass",
    "NetherTerrainBlocks.SEPIA_MUSHROOM_GRASS": "betternether:sepia_mushroom_grass",
    "NetherTerrainBlocks.JUNGLE_GRASS": "betternether:jungle_grass",
    "NetherTerrainBlocks.NETHER_MYCELIUM": "betternether:nether_mycelium",
    "NetherTerrainBlocks.NETHERRACK_MOSS": "betternether:netherrack_moss",
    "NetherTerrainBlocks.SWAMPLAND_GRASS": "betternether:swampland_grass",
    "NetherTerrainBlocks.CEILING_MUSHROOMS": "betternether:ceiling_mushrooms",
    "NetherTerrainBlocks.BLEACHED_GLOOMSCULK": "betternether:bleached_gloomsculk",
    "NetherTerrainBlocks.MOLTEN_GLOOMSCULK": "betternether:molten_gloomsculk",
    "NetherTerrainBlocks.VEINED_GLOOMSCULK": "betternether:veined_gloomsculk",
    # registry/block/NetherStoneBlocks.java
    "NetherStoneBlocks.SOUL_SANDSTONE": "betternether:soul_sandstone",
    # 原版（net.minecraft.world.level.block.Blocks）
    "Blocks.NETHERRACK": "minecraft:netherrack",
    "Blocks.SOUL_SOIL": "minecraft:soul_soil",
    "Blocks.SOUL_SAND": "minecraft:soul_sand",
    "Blocks.GRAVEL": "minecraft:gravel",
    "Blocks.BLACKSTONE": "minecraft:blackstone",
    "Blocks.DEEPSLATE": "minecraft:deepslate",
    "Blocks.MAGMA_BLOCK": "minecraft:magma_block",
    "Blocks.RED_SAND": "minecraft:red_sand",
    "Blocks.CRIMSON_NYLIUM": "minecraft:crimson_nylium",
    "Blocks.WARPED_NYLIUM": "minecraft:warped_nylium",
    "Blocks.NETHER_WART_BLOCK": "minecraft:nether_wart_block",
    "Blocks.SCULK": "minecraft:sculk",
}

NETHERRACK = "minecraft:netherrack"

# 群系 key 与上游类名、NetherBiomes 注册 key 一致（下划线小写）。
#
# 字段：
#   floor           [(id, 占比), ...]  地面**最上层**的材质，绝对占比，Σ ≤ 1.0
#   below           (id, 层数) 或 None  上游 belowFloor(id, 层数)；层数从「地表下一格」算起
#   ceiling         [(id, 占比), ...] 或 None  天花板**最下一层**的材质
#   ceiling_depth   天花板材质向上压进顶板的层数；0 = 没有天花板规则
#   chance          占比来源：declared / measured / provider / inferred / unknown
#   source          上游文件与行号
#   note            为什么是这些数，以及哪些东西装不进 Profile
SURFACES: dict[str, dict[str, object]] = {
    "bone_reef": {
        "floor": [("betternether:mushroom_grass", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "BoneReef.java:40-43",
        "note": "surface(): 只有一句 floor(MUSHROOM_GRASS)，没有 belowFloor、没有 ceil。",
    },
    "sulfuric_bone_reef": {
        "floor": [("betternether:sepia_mushroom_grass", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "SulfuricBoneReef.java:41-44",
        "note": "surface(): floor(SEPIA_MUSHROOM_GRASS)。",
    },
    "nether_jungle": {
        "floor": [("betternether:jungle_grass", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "NetherJungle.java:66-69",
        "note": "surface(): floor(JUNGLE_GRASS)。",
    },
    "nether_mushroom_forest": {
        "floor": [("betternether:nether_mycelium", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "NetherMushroomForest.java:48-51",
        "note": "surface(): floor(NETHER_MYCELIUM)。",
    },
    "nether_mushroom_forest_edge": {
        # provider: nextInt(4)>0 ? 0 : (nextBoolean() ? 1 : 2)
        # 0 = 75%，1 = 3/16 = 12.5%，2 = 3/16 = 12.5%；列表第 3 项正是 NETHERRACK。
        "floor": [
            ("betternether:nether_mycelium", 0.75),
            ("betternether:netherrack_moss", 0.125),
            ("minecraft:netherrack", 0.125),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "provider",
        "source": "NetherMushroomForestEdge.java:38-54",
        "note": (
            "surface() 一句 rule(ON_FLOOR -> SwitchRuleSource("
            "NetherMushroomForestEdgeNumericProvider, [NETHER_MYCELIUM, NETHERRACK_MOSS, NETHERRACK]))。"
            "provider（providers/NetherMushroomForestEdgeNumericProvider.java:30-40）是 "
            "nextInt(4)>0 ? 0 : (nextBoolean() ? 1 : 2)，即 75% / 12.5% / 12.5% —— 精确，不是推断。"
            "列表的第三项就是原版下界岩，所以这里 Σ = 1.0，余量为 0。"
        ),
    },
    "old_fungiwoods": {
        "floor": [("betternether:nether_mycelium", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "OldFungiwoods.java:48-51",
        "note": "surface(): floor(NETHER_MYCELIUM)。",
    },
    "old_warped_woods": {
        "floor": [("minecraft:warped_nylium", 1.0)],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "declared",
        "source": "OldWarpedWoods.java:50-53",
        "note": "surface(): floor(Blocks.WARPED_NYLIUM)。",
    },
    "crimson_glowing_woods": {
        "floor": [
            ("minecraft:crimson_nylium", 0.5),
            ("minecraft:nether_wart_block", 0.5),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "CrimsonGlowingWoods.java:60-67",
        "note": (
            "surface(): chancedFloor(NETHER_WART_BLOCK, CRIMSON_NYLIUM, "
            "Conditions.FORREST_FLOOR_SURFACE_NOISE_B)。"
            "**占比未知**：FORREST_FLOOR_SURFACE_NOISE_B 是 wover 里烧好的阈值，本仓库无该库源码，"
            "按 50% 近似（本表不确定性最大的一条：下界疣块的覆盖面完全由这个阈值决定）。"
        ),
    },
    "crimson_pinewood": {
        "floor": [
            ("minecraft:crimson_nylium", 0.5),
            ("minecraft:nether_wart_block", 0.5),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "CrimsonPinewood.java:54-61",
        "note": (
            "surface() 与 CrimsonGlowingWoods 逐字相同："
            "chancedFloor(NETHER_WART_BLOCK, CRIMSON_NYLIUM, FORREST_FLOOR_SURFACE_NOISE_B)。占比同样未知，按 50%。"
        ),
    },
    "flooded_deltas": {
        # provider: 顶层 nextInt(3) -> 0/1/2。列表只有 [DEEPSLATE, BLACKSTONE] 两项，
        # 无论越界是 clamp(2->1) 还是 2%2=0，结果都是 2/3 深板岩、1/3 黑石。
        "floor": [
            ("minecraft:deepslate", 0.667),
            ("minecraft:blackstone", 0.333),
        ],
        "below": None,
        "ceiling": [("minecraft:deepslate", 1.0)],
        "ceiling_depth": 1,
        "chance": "provider",
        "source": "FloodedDeltas.java:50-64",
        "note": (
            "surface(): ceil(DEEPSLATE) + rule(FLOOR_PRIORITY, SwitchRuleSource("
            "NetherGrasslandsNumericProvider, [DEEPSLATE, BLACKSTONE]))。"
            "该 provider 顶层返回 nextInt(3)（providers/NetherGrasslandsNumericProvider.java:36-44），"
            "取值 0/1/2 映到两项表上，clamp 与取模都给 2/3 : 1/3，故占比可算。"
        ),
    },
    "gloomwood": {
        # 上游注释实测（Gloomwood.java:143-146）：1% molten / 19% sculk / 80% bleached
        "floor": [
            ("betternether:bleached_gloomsculk", 0.80),
            ("minecraft:sculk", 0.19),
            ("betternether:molten_gloomsculk", 0.01),
        ],
        # belowFloor(VEINED_GLOOMSCULK, 1)：正好一层，上游注释说明是「过渡层」，不能叠第二层
        "below": ("betternether:veined_gloomsculk", 1),
        # 天花板 = 幽匿为底、下界岩作小岛；CEILING_DEEP 让约 1/3 的列压到 5 层
        "ceiling": [
            ("minecraft:sculk", 0.75),
            ("minecraft:netherrack", 0.25),
        ],
        "ceiling_depth": 5,
        "chance": "measured",
        "source": "Gloomwood.java:141-200",
        "note": (
            "地面 chancedFloor(MOLTEN, sequence(if FLOOR_SCULK -> SCULK, BLEACHED), FLOOR_MOLTEN)："
            "上游注释写明 1500x1500 实测 1% 熔融 / 19% 幽匿 / 80% 漂白 —— **全表唯一有实测数字的群系**。"
            "belowFloor(VEINED_GLOOMSCULK, 1)：一层过渡层，上游明说不能叠第二层（会让下界岩底面与"
            "上一层的幽匿顶面接缝重现）。"
            "天花板：rule(if stoneDepthCheck(1,false,CEILING) -> sequence(if CEILING_NETHERRACK -> "
            "NETHERRACK, SCULK)) 是 1 层皮层，下界岩为小岛；再 rule(if CEILING_DEEP -> if "
            "stoneDepthCheck(5,false,CEILING) -> SCULK) 让约 1/3 的列压到 5 层，另 aboveCeil(SCULK, 3) "
            "补 3 层，故皮层常态 4 层、深处 5 层 —— ceiling_depth 取 5（最大值）。"
            "天花板的 25% 是**未知**：上游只说 CEILING_NETHERRACK 是 'a clear minority'，无数字。"
        ),
    },
    "gravel_desert": {
        "floor": [("minecraft:gravel", 1.0)],
        # belowFloor(GRAVEL, 4, NETHER_VOLUME_NOISE)：全表最深的亚表层
        "below": ("minecraft:gravel", 4),
        "ceiling": [("minecraft:netherrack", 1.0)],
        "ceiling_depth": 1,
        "chance": "declared",
        "source": "GravelDesert.java:44-52",
        "note": (
            "surface(): ceil(NETHERRACK) + floor(GRAVEL) + "
            "rule(UNDER_FLOOR -> GRAVEL, FLOOR_PRIORITY+1) + belowFloor(GRAVEL, 4, NETHER_VOLUME_NOISE)。"
            "UNDER_FLOOR 是 stoneDepthCheck(0,true,FLOOR)，覆盖地表下若干格；belowFloor 再补到 4 层，"
            "合起来就是 4 层砂砾。天花板显式声明了下界岩 —— 与默认填充同值，属空写（投影时按余量处理）。"
        ),
    },
    "magma_land": {
        "floor": [
            ("minecraft:magma_block", 0.5),
            ("minecraft:red_sand", 0.25),
            ("minecraft:netherrack", 0.25),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "MagmaLand.java:41-60",
        "note": (
            "surface(): chancedFloor(MAGMA_BLOCK, sequence(if NETHER_VOLUME_NOISE -> sequence(if hole -> "
            "NETHERRACK, RED_SAND), NETHERRACK), NETHER_SURFACE_NOISE)。"
            "**两处未知**：(1) chancedFloor 的条件 NETHER_SURFACE_NOISE 阈值未知，按 50% 给熔岩块；"
            "(2) 底料里 NETHER_VOLUME_NOISE 未知，按 50% 给红沙 / 50% 下界岩。"
            "两者相乘即 50% 熔岩块 / 25% 红沙 / 25% 下界岩。"
            "按名义值算熔岩块是本群系地面占优的材质，这与群系名一致（旧实现靠 50% accent 得到同样的观感）。"
            "空洞里的下界岩（hole()）是 3 层以下的细节，装不进 Profile。"
        ),
    },
    "nether_grasslands": {
        # provider 顶层 nextInt(3)：0=SOUL_SOIL、1=SOUL_SAND、2=MOSS；列表第 4 项 NETHERRACK 取不到
        "floor": [
            ("minecraft:soul_soil", 0.334),
            ("minecraft:soul_sand", 0.333),
            ("betternether:netherrack_moss", 0.333),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "provider",
        "source": "NetherGrasslands.java:69-88",
        "note": (
            "surface() 一句 rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> SwitchRuleSource("
            "NetherGrasslandsNumericProvider, [SOUL_SOIL, SOUL_SAND, MOSS, NETHERRACK]), "
            "SwitchRuleSource(同一 provider, [SOUL_SOIL, SOUL_SAND, NETHERRACK])))。"
            "provider 顶层返回 nextInt(3)（providers/NetherGrasslandsNumericProvider.java:36-44），"
            "三个取值等概率，故顶层是**精确的 1/3 三等份**，列表第 4 项 NETHERRACK 永远取不到。"
            "**这一条的三项之和恰好 1.0，没有余量**：只读 top/accent 的实现会让 1/3 的列落回下界岩，"
            "比上游少一种材质 —— 消费方必须读第三个材质（Java 侧 accent2）。"
            "ON_FLOOR 之外的分支（第 2-3 层）按 provider 的 depth 逻辑在灵魂土/下界岩之间切换，"
            "那是亚表层，地下不可见，未建模（below 留空 = 不动）。"
        ),
    },
    "poor_nether_grasslands": {
        "floor": [
            ("betternether:netherrack_moss", 0.334),
            ("minecraft:soul_soil", 0.333),
            ("minecraft:netherrack", 0.333),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "PoorNetherGrasslands.java:46-70",
        "note": (
            "surface(): rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> SwitchRuleSource("
            "NetherNoiseCondition, [MOSS, SOUL_SOIL, NETHERRACK]), soilStoneDist))，"
            "soilStoneDist = if NETHER_VOLUME_NOISE -> SOUL_SOIL else NETHERRACK。"
            "**占比未知**：NetherNoiseCondition 在 wover 里（本仓库无源码），这里假定三项等概率 1/3 —— "
            "该 provider 要给 3 项和 6 项的列表选下标（SoulPlain 用 6 项），必然不是等分，"
            "所以这是近似而非推导。首项下界岩苔是本群系唯一的地面标记。"
        ),
    },
    "nether_swampland": {
        # 顺序即优先级：roughNoise(NETHERRACK,0.14) -> 沼泽草；再 roughNoise(NETHER_WART,0.19) -> 灵魂沙；
        # 兜底灵魂土。两个条件用**不同噪声场**，近似独立，故灵魂沙 = 0.19 * (1-0.14)。
        "floor": [
            ("minecraft:soul_soil", 0.697),
            ("minecraft:soul_sand", 0.163),
            ("betternether:swampland_grass", 0.14),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "inferred",
        "source": "NetherSwampland.java:66-85",
        "note": (
            "surface(): rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> if roughNoise(NETHERRACK, 0.14) -> "
            "SWAMPLAND_GRASS, if roughNoise(NETHER_WART, 0.19) -> SOUL_SAND, SOUL_SOIL))。"
            "占比按「roughNoise 阈值≈面积占比」推：草 14%，灵魂沙 0.19×(1-0.14)=16.3%（两个条件用不同"
            "噪声场，近似独立），余下灵魂土 69.7%。推理依据见文件头。"
            "注意第二个条件没有 ON_FLOOR 门控，所以灵魂沙也会出现在亚表层。"
        ),
    },
    "nether_swampland_terraces": {
        "floor": [
            ("minecraft:soul_soil", 0.697),
            ("minecraft:soul_sand", 0.163),
            ("betternether:swampland_grass", 0.14),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "inferred",
        "source": "NetherSwamplandTerraces.java:1-20（无 surface 重写，继承 NetherSwampland.java:66-85）",
        "note": (
            "本类**没有**重写 surface()，只加了 LAVA_TERRACE 特征和气候，"
            "所以数值与 nether_swampland 完全相同（推理来源也一样）。"
        ),
    },
    "old_swampland": {
        # roughNoise(NETHERRACK,0.19) -> 沼泽草；NETHER_SURFACE_NOISE_LARGE -> 灵魂沙（未知，按 50%）；
        # 兜底灵魂土。
        "floor": [
            ("minecraft:soul_soil", 0.405),
            ("minecraft:soul_sand", 0.405),
            ("betternether:swampland_grass", 0.19),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "inferred",
        "source": "OldSwampland.java:65-84",
        "note": (
            "surface(): rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> if roughNoise(NETHERRACK, 0.19) -> "
            "SWAMPLAND_GRASS, if NETHER_SURFACE_NOISE_LARGE -> SOUL_SAND, SOUL_SOIL))。"
            "结构与 nether_swampland 相同，但草的阈值是 0.19（推得 19%），"
            "第二档用的是大尺度具名噪声 NETHER_SURFACE_NOISE_LARGE，**占比未知**，按剩余 81% 的一半给，"
            "即 40.5% 灵魂沙 / 40.5% 灵魂土。"
        ),
    },
    "soul_plain": {
        # 地面 soilSandDist = if NETHER_VOLUME_NOISE -> SOUL_SOIL else SOUL_SAND（未知，按 50%）
        "floor": [
            ("minecraft:soul_sand", 0.5),
            ("minecraft:soul_soil", 0.5),
        ],
        # ceil(SOUL_SANDSTONE)：皮层是 100% 灵魂砂岩
        "ceiling": [("betternether:soul_sandstone", 1.0)],
        "ceiling_depth": 1,
        "below": None,
        "chance": "unknown",
        "source": "SoulPlain.java:57-92",
        "note": (
            "surface(): rule(if ON_FLOOR -> soilSandDist, CEILING_PRIORITY+1)，"
            "soilSandDist = if NETHER_VOLUME_NOISE -> SOUL_SOIL else SOUL_SAND（**NETHER_VOLUME_NOISE 阈值"
            "未知，按 50/50**）；ceil(SOUL_SANDSTONE)；"
            "rule(if UNDER_CEILING -> soilStoneDist, CEILING_PRIORITY-1)，"
            "soilStoneDist = if NETHER_VOLUME_NOISE -> SOUL_SOIL else SOUL_SANDSTONE；"
            "rule(Switch(NetherNoiseCondition, [SOUL_SOIL, SOUL_SAND, SOUL_SANDSTONE, LAVA, LAVA, "
            "SOUL_SAND]), CEILING_PRIORITY-2)。"
            "后两条是**顶板第 2 格往上的层**，不是皮层本身，所以不写进 ceiling：皮层是纯灵魂砂岩，"
            "把灵魂土混进皮层会让从下方看到的顶板颜色变掉（那是可见的地表）。"
            "最后那条 6 项列表（含两个岩浆块，用于挖岩浆穴）在地下更深处，未建模。"
            "另注：上游刻意把地面规则挂在 CEILING_PRIORITY+1 上。"
        ),
    },
    "upside_down_forest": {
        # 地面：roughNoise(NETHERRACK, 0.021) -> 苔，兜底蘑菇草；2.1% 是推理
        "floor": [
            ("betternether:mushroom_grass", 0.979),
            ("betternether:netherrack_moss", 0.021),
        ],
        # 天花板：ON_CEILING -> sequence(if FORREST_FLOOR_SURFACE_NOISE_A -> CEILING_MUSHROOMS, NETHERRACK)
        "ceiling": [
            ("minecraft:netherrack", 0.5),
            ("betternether:ceiling_mushrooms", 0.5),
        ],
        "ceiling_depth": 1,
        "below": None,
        "chance": "inferred",
        "source": "UpsideDownForest.java:73-96",
        "note": (
            "surface(): rule(CEILING_PRIORITY, if ON_CEILING -> sequence(if "
            "FORREST_FLOOR_SURFACE_NOISE_A -> CEILING_MUSHROOMS, NETHERRACK)) + "
            "rule(FLOOR_PRIORITY, if ON_FLOOR -> sequence(if roughNoise(NETHERRACK, 0.021) -> "
            "NETHERRACK_MOSS, MUSHROOM_GRASS))。"
            "地面苔 2.1%（roughNoise 阈值推理）；天花板菌盖的占比用 FORREST_FLOOR_SURFACE_NOISE_A，"
            "**未知，按 50%**。天花板的兜底是下界岩 —— 所以这个群系从下方看是「下界岩顶板 + 一半菌盖」，"
            "旧实现把整个顶板都铺成菌盖，是过头了。"
        ),
    },
    "upside_down_forest_cleared": {
        "floor": [
            ("betternether:mushroom_grass", 0.779),
            ("betternether:netherrack_moss", 0.221),
        ],
        "ceiling": [
            ("minecraft:netherrack", 0.5),
            ("betternether:ceiling_mushrooms", 0.5),
        ],
        "ceiling_depth": 1,
        "below": None,
        "chance": "inferred",
        "source": "UpsideDownForestCleared.java:59-88",
        "note": (
            "surface() 与 UpsideDownForest 同一套材质，只换噪声：地面苔阈值 0.221（父类 0.021，"
            "苔明显更多 → 22.1%），天花板改用 UpsideDownForest.NOISE_CEIL_LAYER（**占比未知**，按 50%）。"
            "天花板规则挂的优先级是 FLOOR_PRIORITY-1（父类是 CEILING_PRIORITY），材质不变。"
        ),
    },
    "wart_forest": {
        # 顶层 switch: [SOUL_SOIL, SOUL_SAND, MOSS, SOUL_SAND, NETHERRACK] —— soul_sand 出现两次即加权
        "floor": [
            ("minecraft:soul_sand", 0.4),
            ("minecraft:soul_soil", 0.2),
            ("betternether:netherrack_moss", 0.2),
            ("minecraft:netherrack", 0.2),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "WartForest.java:64-111",
        "note": (
            "surface(): rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> SwitchRuleSource("
            "NetherNoiseCondition, [SOUL_SOIL, SOUL_SAND, MOSS, SOUL_SAND, NETHERRACK]), "
            "if stoneDepthCheck(4, true, 1, FLOOR) -> SwitchRuleSource([soul_sandstone UP=true, "
            "soul_sandstone UP=false]), SwitchRuleSource([SOUL_SOIL, SOUL_SAND, NETHERRACK])))。"
            "顶层 5 项里 soul_sand 出现两次（模组用重复项加权），等概率下即 20/40/20/20 —— "
            "**NetherNoiseCondition 的实际分布未知，这里假定等分**。"
            "第 4-5 层的灵魂砂岩由 stoneDepthCheck(4,true,1,FLOOR) 指定，位置在地下 4-5 格；"
            "Profile 的 under 只能从地表下一格开始铺，写了会把砂岩顶到 1-2 格（看得见的地方），"
            "所以宁可留空 —— 这 2 层是唯一装不下的层级。"
        ),
    },
    "wart_forest_edge": {
        "floor": [
            ("minecraft:soul_soil", 0.25),
            ("minecraft:soul_sand", 0.25),
            ("betternether:netherrack_moss", 0.25),
            ("minecraft:netherrack", 0.25),
        ],
        "below": None,
        "ceiling": None,
        "ceiling_depth": 0,
        "chance": "unknown",
        "source": "WartForestEdge.java:41-66",
        "note": (
            "surface(): rule(FLOOR_PRIORITY, sequence(if ON_FLOOR -> SwitchRuleSource("
            "NetherNoiseCondition, [SOUL_SOIL, SOUL_SAND, MOSS, NETHERRACK]), "
            "if NETHER_VOLUME_NOISE -> SOUL_SAND, NETHERRACK))。"
            "边缘群系比 WartForest 少一个权重项（4 项、无重复），假定等分即各 25%；"
            "**NetherNoiseCondition 分布未知**。亚表层：体积噪声处灵魂沙、否则下界岩，均在默认填充附近，未建模。"
        ),
    },
}


def project(key: str) -> dict[str, object]:
    """把一页上游真值投影成 `BiomeSurface.Profile` 的 3 个地面槽 + 2 个天花板槽。

    投影规则（与 Java 侧字段一一对应）：

      * **地面**：按占比从大到小取前 3 个材质 → top / accent / accent2，且**剔除下界岩** ——
        它是生成器的默认填充，留在原地就等于画成下界岩，所以算「余量」而不是少一种材质
        （见文件头的占比语义）。
      * **天花板**：按占比从大到小取前 2 个 → ceiling / ceilingAccent，**下界岩照留**。
        地面与天花板在此不对称，原因是天花板槽里的 `ceiling` 承担的是「剩下那些列画什么」，
        而上游天花板序列的兜底项往往正是下界岩（UpsideDownForest 的 `sequence(..., NETHERRACK)`）：
        把下界岩剔掉会让 ceiling 变成占比 1.0 的菌盖，整个顶板铺满菌盖 —— 正是旧实现的过头之处。
      * 被丢掉的材质放进 `dropped`，由 `main()` 打印出来。
    """
    entry = SURFACES[key]

    dropped: list[str] = []

    def ranked(materials: list[tuple[str, float]], slots: int, keep_netherrack: bool) -> list[tuple[str, float]]:
        ordered = sorted(materials, key=lambda m: -m[1])
        if not keep_netherrack:
            ordered = [m for m in ordered if m[0] != NETHERRACK]
        for material in ordered[slots:]:
            dropped.append(f"{material[0]} {material[1]:.3f}")
        return ordered[:slots]

    floor = ranked(entry["floor"], 3, keep_netherrack=False)
    ceiling = ranked(entry["ceiling"] or [], 2, keep_netherrack=True)

    below = entry["below"]
    return {
        "top": floor[0][0] if floor else NETHERRACK,
        "topChance": round(floor[0][1], 3) if floor else 1.0,
        "accent": floor[1][0] if len(floor) > 1 else None,
        "accentChance": round(floor[1][1], 3) if len(floor) > 1 else 0.0,
        "accent2": floor[2][0] if len(floor) > 2 else None,
        "accent2Chance": round(floor[2][1], 3) if len(floor) > 2 else 0.0,
        "under": below[0] if below else NETHERRACK,
        "underDepth": below[1] if below else 0,
        "ceiling": ceiling[0][0] if ceiling else None,
        "ceilingDepth": entry["ceiling_depth"] if ceiling else 0,
        "ceilingAccent": ceiling[1][0] if len(ceiling) > 1 else None,
        "ceilingAccentChance": round(ceiling[1][1], 3) if len(ceiling) > 1 else 0.0,
        "dropped": dropped,
    }


def check_shares() -> list[str]:
    """Σ(占比) 必须 ≤ 1，且每个 id 必须带命名空间。返回错误列表。"""
    problems: list[str] = []
    for key, entry in SURFACES.items():
        for field in ("floor", "ceiling"):
            materials = entry[field] or []
            total = sum(share for _, share in materials)
            if total > 1.0 + 1e-9:
                problems.append(f"{key}.{field} 占比之和 {total:.3f} > 1")
            for material_id, share in materials:
                if ":" not in material_id:
                    problems.append(f"{key}.{field} 的 id 缺少命名空间：{material_id}")
                if not 0.0 < share <= 1.0:
                    problems.append(f"{key}.{field} 的 {material_id} 占比越界：{share}")
    return problems


def find_manifest() -> pathlib.Path | None:
    """从当前目录向上找资源包流水线产出的方块清单。"""
    here = pathlib.Path.cwd()
    for candidate_root in (here, *here.parents):
        candidate = candidate_root / "plugin/build/generated/bn/reports/block-manifest.json"
        if candidate.is_file():
            return candidate
    return None


def check_manifest() -> list[str]:
    """每个 betternether: id 都必须在方块清单里，否则运行时静默变成空气。"""
    manifest = find_manifest()
    if manifest is None:
        return []
    declared = {block["id"] for block in json.loads(manifest.read_text(encoding="utf-8"))["blocks"]}
    problems: list[str] = []
    for key, entry in SURFACES.items():
        ids = [material_id for material_id, _ in (entry["floor"] or [])]
        ids += [material_id for material_id, _ in (entry["ceiling"] or [])]
        if entry["below"]:
            ids.append(entry["below"][0])
        for material_id in ids:
            if material_id.startswith("betternether:") and material_id not in declared:
                problems.append(f"{key} 引用了清单里不存在的方块：{material_id}")
    return problems


def main() -> int:
    """打印上游真值 + Java 投影 + 丢掉了什么，并做自检。"""
    print(f"{'群系':30} {'地面（占比 / 来源）':52} {'亚表层':26} 天花板")
    for key, entry in SURFACES.items():
        projected = project(key)
        floor = "  ".join(f"{i.split(':')[1]} {s:.3f}" for i, s in sorted(entry["floor"], key=lambda m: -m[1]))
        below = entry["below"]
        below_text = f"{below[0].split(':')[1]} x{below[1]}" if below else "-"
        ceiling_materials = entry["ceiling"] or []
        ceiling = "  ".join(f"{i.split(':')[1]} {s:.3f}" for i, s in ceiling_materials) or "-"
        print(f"{key:30} {floor:52} {below_text:26} {ceiling}")
        print(f"{'':30} 来源 {entry['source']}  占比来源 {entry['chance']}")
        if projected["dropped"]:
            print(f"{'':30} !! 装不进 Java 槽位：{'；'.join(projected['dropped'])}")
        if entry["chance"] in ("inferred", "unknown"):
            print(f"{'':30} !! 占比含推断/未知，不是抄来的数字")

    problems = check_shares() + check_manifest()
    print(f"\n共 {len(SURFACES)} 个群系；常量映射 {len(CONSTANT_TO_ID)} 条。")
    if problems:
        print("\n自检失败：")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("自检通过：占比之和与方块清单均无问题。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

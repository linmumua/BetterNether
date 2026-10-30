# betternether-structures 数据包迁移说明

本包把上游 BetterNether 的结构迁成原版 `minecraft:jigsaw` 结构。

> 文件名沿用历史的 `README-NetherCity.md`，但内容已覆盖全包 10 个结构。
> `plugin/build.gradle.kts` 的 `exclude("datapacks/betternether-structures/README-*.md")`
> 会把本文件排除在 jar 之外 —— 这是给维护者看的，不是数据包内容。

## 命名空间

本移植对外发布 **`betternether:`**（`scripts/bnlib/namespace.py` 的 `NAMESPACE`）。
上游也是 `betternether:`，所以 31 个新迁 NBT **按原字节 `cp` 复制**，没有任何改写：
NBT 里出现的命名空间只有 `betternether:` 与 `minecraft:`（已逐个片段统计）。

## 全包文件清单

```
data/betternether/structure/<结构名>/*.nbt     67 个片段 = nether_city 36 + 新迁 31
data/betternether/worldgen/template_pool/<结构名>/start.json    9 个新池 + city 3 个
data/betternether/worldgen/structure/<结构名>.json              9 个新结构 + city（作者定义）
data/betternether/worldgen/structure_set/<结构名>.json          8 个新集合 + city（放置规格，发布时搬到 mumu）
data/betternether/worldgen/processor_list/
    strip_structure_blocks.json                 剥 structure_block + jigsaw
    strip_structure_blocks_keep_terrain.json    上面两个 + air
data/betternether/tags/worldgen/biome/has_structure/
    nether_city.json  nether_decorations.json  ghast_hive.json
    jungle_temples.json  pyramid.json
```

`src` 里**没有** `data/mumu/**`：公开结构 id 由构建期从上面这份源布局生成
（`scripts/bnlib/public_structures.py`，在 `processResources` 的 `doLast` 里执行），
提交一份复制件只会多一份会漂移的 JSON。发布后的结构包是这样：

```
data/betternether/worldgen/structure/<结构名>.json      作者定义（读旧存档的 starts，不再放置）
data/mumu/worldgen/structure/<结构名>.json              公开定义，id 即 /locate structure 用的那个
data/mumu/worldgen/structure_set/<结构名>.json          唯一一份自然放置，structures[] 指向 mumu:
```

`/locate structure mumu:<建筑名>` 之所以需要第二份定义，是因为注册表 id 就是文件路径 ——
不存在命令别名这回事。方块/物品/群系/template_pool/处理器/NBT 调色板全部仍是
`betternether:`，只有「结构自己的 id」多发一份 `mumu:`。旧命名空间**不许**再留放置集：
两套 salt 会让同一座建筑各生成一次。`jungle_temples` 也不单独开集合（见下表）。

`nether_city` 的 NBT 同时存在于 `src` 与 `build/generated`（构建期由
`scripts/extract_bn.py` 生成）；`src` 那一份是为了让 `tools/verify_structures.py`
能在源码树上跑通引用的模板都存在，两份内容相同。

## 逐结构数值

`y` = `start_height.absolute`；`spacing/separation/salt` 抄上游 `structure_set/<名字>.json`。
发布时这些集合整体搬到 `mumu:<名字>`，成员 id 也从 `betternether:<名字>` 换成
`mumu:<名字>`，数值一个不改；旧命名空间只留结构定义、不再留集合。

| 结构 | 片段（权重） | 群系 tag | y | start_pool | structure_set |
|---|---|---|---|---|---|
| `altars` | altar_01..08（全 1） | `has_structure/nether_decorations` | 40 | `betternether:altars/start` | 40 / 16 / 1526029009 |
| `gardens` | garden_01、02（1、1） | `has_structure/nether_decorations` | 40 | `betternether:gardens/start` | 50 / 16 / 1545304170 |
| `ghast_hive` | ghast_hive（1） | `has_structure/ghast_hive` | 100 | `betternether:ghast_hive/start` | 80 / 32 / 532488614 |
| `jungle_temples` | ruined_temple、jungle_temple_altar、jungle_temple_2（1、1、1） | `has_structure/jungle_temples` | 40 | `betternether:jungle_temples/start` | **无独立集合**，挂在 `pyramid` 里 weight 1（与上游一致） |
| `pillars` | pillar_01..06（全 1） | `has_structure/nether_decorations` | 40 | `betternether:pillars/start` | 20 / 8 / 1923978943 |
| `portals` | portal_01、02（1、1） | `has_structure/nether_decorations` | 40 | `betternether:portals/start` | 40 / 15 / 1746424769 |
| `pyramid` | pyramid_1..4（10、10、6、3） | `has_structure/pyramid` | 31 | `betternether:pyramid/start` | 32 / 8 / 1460687864（成员 pyramid + jungle_temples） |
| `respawn_points` | respawn_point_01..04（10、10、6、3） | `has_structure/nether_decorations` | 40 | `betternether:respawn_points/start` | 32 / 24 / 2077824658 |
| `spawn_altar_ladder` | spawn_altar_ladder（1） | `has_structure/nether_decorations` | 40 | `betternether:spawn_altar_ladder/start` | 40 / 20 / 1339555171 |

所有结构：`type: minecraft:jigsaw`、`step: surface_structures`（与上游一致）、
`size: 1`、`terrain_adaptation: none`、`use_expansion_hack: false`、不写
`project_start_to_heightmap`。`max_distance_from_center`：小片段 48，`jungle_temples` /
`spawn_altar_ladder` 64，`pyramid` 96。

### 为什么没有 6 个独立群系 tag

上游本来就只发 5 个 tag 文件（`nether_city`、`nether_decorations`、`ghast_hive`、
`jungle_temples`、`pyramid`）。`altars`/`gardens`/`pillars`/`portals`/`respawn_points`/
`spawn_altar_ladder` 共用 `#betternether:has_structure/nether_decorations`。
这里照抄上游，**没有**为它们各造一个内容重复的 tag。

### 悬空群系 id：0 条

逐条比对过：上游 6 个 tag 的每个 `betternether:*` 条目都落在本移植注册的 23 个群系内，
`nether_city` 里的 `minecraft:nether_wastes` 属 `minecraft:*`。所以**没有删任何 id**
（不是"删了但没记"）。

## 无法忠实迁移的部分（逐条列出）

### 1. `mega_lava_lake` 没迁

上游这个结构**没有任何 NBT 片段**，是纯程序化生成：`MegaLavaLakeStructure` 自己走噪声列找
"空中→实心"的过渡当湖面，再用 `LavaLakePiece` 挖碗、灌岩浆、铺岸。数据包表达不了
"先探地形再挖"，所以本包不含它的 structure / structure_set / tag。
上游的 24 / 8 / 353194681 因此也没有落地。

### 2. 放置方式从"跟地形"变成"固定 y"

这是最实质的偏差。上游 5 种放置策略都依赖运行期扫描：

| 上游 placement | 行为 | 本包 |
|---|---|---|
| `nether_surface` | 从天花板下扫，找 air-over-solid 的顶面 | 固定 y=40 |
| `nether_surface_flat_2` / `_4` | 同上 + 要求 ±2 / ±4 格内平坦 | 固定 y=40 |
| `nether_ceil` | 贴天花板 | 固定 y=100 |
| `lava` | 贴岩浆面（下界岩浆海 y=31） | 固定 y=31 |

原版 jigsaw 没有"向下找地板"的字段，而 `project_start_to_heightmap` 在下界取
`WORLD_SURFACE_WG` 会返回基岩天花板（≈127），把结构放进虚空，所以只能用绝对值。
后果：这些装饰在地形起伏处可能**轻微悬空或埋入**，不像上游那样逐格贴合。
y 值取在下界可用带（岩浆海 31、天花板 127）之间，且贴着各自语义：
地面装饰 40、天花板吊挂的 `ghast_hive` 100（片段高 24，顶部到 123）、
岩浆里的 `pyramid` 31。

### 3. `keep_air` 用 processor 近似

上游 `RandomNbtStructure` 的 `keep_air` 决定模板里的空气要不要写进世界：

- `keep_air: false`（altars、ghast_hive、pillars、respawn_points、spawn_altar_ladder）
  → 本包挂 `strip_structure_blocks_keep_terrain`，用 `block_ignore` 连 `minecraft:air`
  一起忽略，让原地形保留；
- `keep_air: true`（gardens、jungles_temples、portals、pyramid）
  → 挂 `strip_structure_blocks`，空气照常放置。

### 4. 小数权重取整

原版权重是整数，上游 `pyramid` / `respawn_points` 的 1.0 : 1.0 : 0.6 : 0.3
按 ×10 写成 10 : 10 : 6 : 3，比例不变、绝对值放大（不影响相对概率）。

### 5. `offset_y` 无法表达

上游每个片段带自己的 `offset_y`（altar 各 -2..-4、pillar -1、portal -3/-4、
spawn_altar_ladder -5 等）。jigsaw 的 start_height 是结构级的，池里没法给单个元素
加 Y 偏移，所以这些偏移被丢弃 —— 这也是第 2 条之外的第二层 Y 误差。

### 6. 单片段不是损失（与 `nether_city` 不同）

`nether_city` 是 `CityGenerator` 边贴边判碰撞的街区生成器，只能退化成一个中心广场。
但上面这 9 个结构上游就是 `RandomNbtStructure`：**随机抽 1 个 NBT 片段放下就结束**，
没有递归装配。所以本包"一个池、放一片"与上游的片段数是一致的，不是退化。

顺带核实：这 31 个新迁片段里 `minecraft:structure_block` 与 `minecraft:jigsaw`
**各 0 处**（160 处 structure_block 只存在于 city 的 36 个片段里）。所以新结构的
processor 对结构方块是空转，真正起作用的是空气规则。

## 验收

```
python tools/verify_structures.py plugin/src/main/resources/datapacks/betternether-structures
```

在**源码树**上跑只会核对模板引用、NBT 可解析、群系 tag 闭合、`start_pool` 引用存在、
注册表文件形状；公开结构 id 的布局（两份定义、一份放置、`mumu:` 引用）要等构建期生成后
才存在，脚本会打印一行「这是发布前的源布局」提示。完整的发布布局由
`:plugin:verifyStructures` 在最终 jar 上核对（`separation >= spacing`、
每个公开 id 恰好被放置一次、旧命名空间无放置集、调色板方块已注册）。

**进服仍需人工确认**（静态证明不了）：`/locate structure mumu:altars` 等能找到并走到
真实结构；结构里看不到结构方块；不整体埋进岩层。

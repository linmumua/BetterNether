"""用户明确移除的发布内容；筛选与结构替换共用规则，避免留下未注册方块引用。

## 移除范围（用户拍板，逐条可指回上游）

  * **本包的台阶 / 楼梯**：CE 每张异形块要吃掉一整个原版方块状态的预算，用户明确不做。
  * **碰撞或功能不完整的上游方块类**：CE 没有对应行为，只能近似，玩家拿到了「看着像、
    用不了」的方块。按**上游 `block_properties.json` 的 `class` 判定**，不按名字猜 ——
    这正是 `cincinnasite_plate`（class `Metal`、满格无关的薄板）与
    `cincinnasite_brick_plate`（class `Block`、真正的满格方块）不能凭 `_plate` 后缀一起删的原因。
  * **逐 ID 移除的自定义方块**：上游用自定义类注册、但主功能是几何/交互体（间歇泉、烟熏炉、
    钟乳石……），逐个列出来，宁可少删也不猜。注意 `glowstone_stalactite` 与
    `rubeus_cone` / `lucis_mushroom` **不在**清单内：它们分别是发光钟乳石与植物，用户没有要求移除。

## 为什么结构里要替换而不是直接删

jigsaw 装配时调色板里找不到定义的方块会被**静默跳过**：不抛异常、不刷日志，玩家看到的是
一座缺件的建筑。所以结构 NBT / 树模板里的这些 id 必须换成**原版同形块**，保留几何
（台阶高度、楼梯朝向、门洞、窗格）与放置位置；只有确实没有同形对应的（间歇泉、钟乳石）
才落到 `minecraft:air`。

替换目标的属性必须过滤：上游属性名不一定被原版同形块接受（`cincinnasite_slab` 的 palette
里就混着一个 `half`），把不认识的属性带过去会让整条调色板条目解析失败。

## 类判定为什么读上游文件

`class` 是上游导出的事实，写死在本地就成了第二份会漂移的清单。这里按上游
`src/main/generated/block_properties.json` 解析，**读不到就报错退出**：一份缺了类信息的
方块表会让「移除」静默失效，那比构建失败更糟。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

# 用户要求移除的上游方块类（`block_properties.json` 的 `class` 字段）。
# 2026-10-29：门/活板门/栅栏门/压力板 已解禁（移出本表），改用 CE 官方模板
# `default:block_state/door|trapdoor|fence_gate` + 压力板钉 light/heavy 压力板 power 序号，
# 只钉 powered=true / power=N 可回收半边，照 BetterEnd B3-openable.yml，零污染。
# 见 blocks.SHAPE_TEMPLATE_DONORS / pressure_plate 分支。
REMOVED_CLASSES: frozenset[str] = frozenset({
    # 梯子：CE 无攀爬行为，走上去会掉下来 —— 移除。
    "LadderBlock",
    # 熔炉族：CE 无烧炼，也不点亮 —— 移除。
    "BlockNetherFurnace",
    "BlockCincinnasiteForge",
    # 栅栏/墙/窗格/按钮：mappings 无可回收章节，CE 也无 wall/pane/button 行为 —— 移除。
    "FenceBlock",
    "WallBlock",
    "BNPane",
    "ButtonBlock",
    # 压力板：用户 2026-10-29 要求「两个插件的压力板都不要」—— 移除。
    "PressurePlateBlock",
    # 门 / 活板门：用户 2026-10-29 要求「所有门和活板门都删掉，不保留」——
    # 且现有插件组合下 donor 全被 betterend/kaleidoscope 占用，无法不冲突地发布 —— 移除。
    "DoorBlock",
    "BNWoodlikeDoor",
    "TrapDoorBlock",
    # 容器（可打开、CE 只有方块实体容器）：用户要求箱子不要 —— 移除。
    "ChestBlock",
    "BlockChestOfDrawers",
    "BarrelBlock",
    "BlockSmoker",
})

# ---------------------------------------------------------------------------
# 2026-10-29 解禁：容器类（原 `ChestBlock` / `BlockChestOfDrawers` / `BNBrewingStand`）
# ---------------------------------------------------------------------------
# ## 为什么可以解禁
#
# 这三类的**唯一**阻塞理由是「CE 无 GUI，右键没有任何反应」，而那条理由已经不成立：
# `blocks.functional_behavior_entries` 按类名给 `ChestBlock` / `BlockChestOfDrawers`
# 挂 CE 原生的 `behavior: {type: simple_storage_block, rows: 3}` —— 方块实体容器，
# 打开的是原版容器界面，打破时由 CE 自己掉落内容（字节码实测见那边的注释）。
#
# ## 为什么容器**不**做家具
#
# 家具是实体，没有方块实体容器；`simple_storage_block` 只能挂在方块上。家具化等于把
# 「能存 27 格」的功能换成「摆着好看」，是功能倒退。这也正是「可触发的非完整方块」
# 与「纯外观异形方块」的分界线：前者必须留在方块形态。
#
# ## 为什么容器**不**钉写死状态（`state: minecraft:chest[...]`）
#
#   * `state:` 是**独占认领**一个原版状态的模型（`blockStateOverrides` 按状态写死，
#     不走 mappings），钉 chest 的状态等于让**世界上每一个原版箱子**都显示成本包的
#     木箱模型 —— 与 `blocks.py` pinned-state 一节判据第 2 条（不与原版世界冲突）正面
#     冲突，而箱子是生存里最不缺的方块。
#   * `PinnedAllocator` 的 24 个载体格已被植物用掉 23 个，只剩 1 格，
#     装不下 17 个容器（15 个 chest + 抽屉柜 + 酿造台）。
#
# 因此它们按既有 `KIND_STATIC` 落地：模型/硬度/音效是上游真值、容器行为是 CE 真行为、
# 碰撞箱借纤细载体（`chorus` 10/16 宽全高柱）。「方块形态给不出 15/16 箱体碰撞」是 CE
# 的固有限制，逐块记进 `reports/plant-collision.json` 的 fidelity。

# 逐 ID 移除：用户点名的方块（上游各自用自定义类注册，类级判定覆盖不到）。
# `glowstone_stalactite` 不在此列 —— 上游有 5 种钟乳石，用户只点名 4 种。
#
# **2026-10-29 家具化解禁**（从本表移出，改由 `bnlib/furniture.py` 以 `furniture_item`
# 发布，碰撞箱按上游 `block_shapes.txt` 逐族精确表达）：
#   `cincinnasite_fire_bowl`（+ 同族 5 个火盆）／`cincinnasite_pot`／`cincinnasite_pedestal`
# 留在表里的家具候选与理由（都不是「CE 表达不了碰撞箱」，而是别的原因）：
#   * `cincinnasite_lantern_small`：墙/顶挂件，6 个 facing 各一套几何，且 hitbox position
#     的 z 在 CE 里的符号未经实测；家具的 `wall` 锚点没验证前不发布（宁缺不错）。
#   * `geyser`：碰撞是 7/8 见方 × 1/4 高，shulker 给不出「方形底 + 更矮的高度」，
#     单箱最好的近似是 0.25 见方（只剩 1/3 宽度），且它本体是 `BlockGeyser` 的弹射功能。
#   * `smoker`：`shape ∈ {top,middle,bottom}` 三态 + `BlockSmoker` 的烟熏功能。
#   * `pig_statue_respawner`：`BlockStatueRespawner` 是功能性方块实体（复活猪），
#     家具化会丢功能；碰撞 7/8 见方 × 1 高本身是可表达的。
#   * `potted_plant`：碰撞是满格立方体（`getCollisionShape` 返回 `Shapes.block()`），
#     且有 30 个 `plant` 变体 —— 一条家具定义表达不了，按方块发布又会占满格状态。
REMOVED_BLOCK_IDS: frozenset[str] = frozenset({
    # 2026-10-30 用户要求「酿造台也删了凳子也删了」：
    #   * 酿造台 —— CE 没有 brewing 行为（GuiType 里没有这一项），发布出来只能是个
    #     打不开的空壳方块，用户选择直接不发布。座椅（chair/taburet/bar_stool 共 69 个）
    #     在 bnlib/furniture.py 里整族删除，不走这张表。
    "betternether:nether_brewing_stand",
    # 熔炉类：CE 无烧炼行为，`REMOVED_CLASSES` 已按上游类排除（`BlockNetherFurnace` /
    # `BlockCincinnasiteForge`）。但结构 NBT 的**方块实体数据**里用的是短名
    # `betternether:forge` / `betternether:furnace`，`_block_class` 查不到，这里显式列出，
    # 好让 structures.py 把它们从 `blocks[].nbt` 里抹掉，装配时不再去创建不存在的方块实体。
    "betternether:forge",
    "betternether:furnace",
    "betternether:nether_furnace",
    "betternether:golden_lumabus_vine",
    "betternether:gloomsculk_vine",
    "betternether:black_vine",
    "betternether:anchor_tree_vine",
"betternether:cincinnasite_lantern_small",
    "betternether:pig_statue_respawner",
    "betternether:potted_plant",
    "betternether:geyser",
    "betternether:smoker",
    # 两张压力板用的是上游自定义类（`Metal` / `Wood`），类级判定抓不到，但证据齐全：
    # `hasCollision=false`（薄板）、状态里带 `powered`、模型 parent 是
    # `minecraft:block/pressure_plate_up` —— 与 11 个 `*_plate` 完全同构。
    # 同族的 `cincinnasite_brick_plate` **不能**删：它是满格方块（`hasCollision=true`），
    # 正是用户提醒的「不能凭 `_plate` 后缀一刀切」。
    "betternether:cincinnasite_plate",
    "betternether:bone_plate",
    # 用户 2026-10 点名移除的两种植物：CE 单条支撑行为表达不了它们的放置规则
    # （`neon_equisetum` 是「同类 ∪ 树叶 ∪ 任何坚固面」的 OR，`soul_lily` 要按水平朝向
    # 看四邻），发布出来就是「能拿到、放下去就掉」的方块。
    # **只删这两个 ID**：`soul_lily_sapling` 是独立注册的方块，用户没有要求删，也没有
    # 任何 worldgen / 结构 / 配方引用它 —— 它的去留由 blocks.py 的「无引用不发布」规则决定。
    "betternether:neon_equisetum",
    "betternether:soul_lily",
    # 用户 2026-10-27 批复「灵芝一起删」：灵芝、巨灵芝、灵芝孢子三个全部移除。
    # 这三个是同一套素材（孢子长成灵芝、灵芝长成巨灵芝），只删其中一个会留下
    # 「能拿到但没用途 / 有配方却缺原料」的孤儿条目，所以按用户的口径整组删除。
    # 连带影响由现有门禁覆盖：配方与掉落表引用它们会进 `product_removed` 统计，
    # `removal.reference` 会挡住任何残留引用，语言文件按已发布集合裁剪。
    "betternether:lucis_mushroom",
    "betternether:giant_lucis",
    "betternether:lucis_spore",
    # 2026-10-30：CE 视觉状态组槽位硬上限（tripwire 126 / pressure_plate 28 /
    # weeping_vines 50）容不下全部内容，每次重载稳定溢出 37 个块。这些块本就因
    # 状态分配失败而没注册成功（日志里「包缺少 29 个世界生成方块 id」「方块无法解析」
    # 列的就是它们），从发布中移除不造成新的功能损失，只让溢出警告归零。
    # tripwire 组溢出 25 个：
    "betternether:willow_branch",
    # pressure_plate 组溢出 11 个：
    "betternether:stalagnate_seed",
    "betternether:wart_seed",
    "betternether:willow_trunk",
    # weeping_vines 组溢出 2 个：
    "betternether:stalagnate_trunk",
    "betternether:whispering_gourd_vine",
    # 2026-10-30（第二轮）：共享 tripwire 池由 BN 与 BE 共用，BE 实际占用远高于
    # POOL_BUDGET 注释里按「块数 83」估的值，BN 只剩约 3 个 tripwire 位置，
    # 二次重载又溢出这 8 个。
    # 2026-10-30：用户要求「可开关类方块全都删掉」——栅栏门与压力板一并移除。
    # 此前 `cincinnasite_brick_plate` 因是满格方块被保留，现按用户口径一并删除。
    "betternether:anchor_tree_gate",
    "betternether:gloomwood_dark_gate",
    "betternether:cincinnasite_brick_plate",
    # 同上：另外两个木系栅栏门。
    "betternether:gloomwood_gate",
    "betternether:mushroom_fir_gate",
})

# 本包所有台阶/楼梯都不发布（异形块每张要吃一整个原版方块状态预算）。
SHAPE_SUFFIXES: tuple[str, ...] = ("_slab", "_stairs")

# 用户 2026-10-30 要求「可开关类方块全都删掉」：门 / 活板门 / 栅栏门 / 压力板 / 按钮 / 拉杆
# 这类带 open|powered 状态的方块一律按**名字后缀**排除，不再逐个往 REMOVED_BLOCK_IDS 里补
# —— 它们是按树系批量生成的，逐条列举总会漏（nether_mushroom_gate、nether_reed_gate …）。
OPENABLE_SUFFIXES: tuple[str, ...] = ("_gate", "_door", "_trapdoor", "_plate", "_button", "_lever")

# 用户 2026-10-30 要求「那个书也删掉」：书架（bookshelf / chiseled_bookshelf）整类不发布。
# 同样是按树系批量生成（22 个木种 × 2），逐条列举必漏，故按后缀排除。
BOOK_SUFFIXES: tuple[str, ...] = ("_bookshelf", "_chiseled_bookshelf")

# 用户 2026-10-30 要求「凳子也删了」：座椅三族（chair / taburet / bar_stool，共 69 个）
# 已从 bnlib/furniture.py 的 FURNITURE_FAMILIES 移除。这里补一条名字判定，
# 因为结构 NBT 的调色板仍引用它们，必须能识别出来换成 air，否则装配时静默缺块。
# 注意顺序：`_bar_stool` 要在 `_chair`/`_taburet` 之外单列；另有 3 个后缀在前的名字
# （chair_cincinnasite 等）由 SEAT_PREFIX_NAMES 兜住。
SEAT_SUFFIXES: tuple[str, ...] = ("_chair", "_taburet", "_bar_stool")
SEAT_PREFIX_NAMES: frozenset[str] = frozenset(
    {"chair_cincinnasite", "taburet_cincinnasite", "bar_stool_cincinnasite"})

# ---------------------------------------------------------------------------
# 替换目标：族 -> (原版 id, 允许保留的属性名)
# ---------------------------------------------------------------------------
# 属性白名单取**原版同形块自己的属性集**：原版同形块的 Block 类与上游一致时属性名相同，
# 少数字段（例如 slab 上没有 `half`）必须丢掉，否则整条调色板条目失效。
_PROPS_STAIRS = frozenset({"facing", "half", "shape", "waterlogged"})
_PROPS_SLAB = frozenset({"type", "waterlogged"})
_PROPS_FENCE = frozenset({"north", "east", "south", "west", "waterlogged"})
_PROPS_FENCE_GATE = frozenset({"facing", "in_wall", "open", "powered"})
_PROPS_WALL = frozenset({"up", "north", "east", "south", "west", "waterlogged"})
_PROPS_PANE = frozenset({"north", "east", "south", "west", "waterlogged"})
_PROPS_DOOR = frozenset({"facing", "half", "hinge", "open", "powered"})
_PROPS_TRAPDOOR = frozenset({"facing", "half", "open", "powered", "waterlogged"})
_PROPS_LADDER = frozenset({"facing", "waterlogged"})
_PROPS_CHEST = frozenset({"facing", "type", "waterlogged"})
_PROPS_BARREL = frozenset({"facing", "open"})
_PROPS_BREWING_STAND = frozenset({"has_bottle_0", "has_bottle_1", "has_bottle_2"})
_PROPS_FURNACE = frozenset({"facing", "lit"})
_PROPS_PLATE = frozenset({"powered"})
_PROPS_NONE = frozenset()

#: 上游类 -> 族名。只列「不止一种类要落到同一族」的情况；台阶/楼梯按后缀另判。
_CLASS_FAMILY: dict[str, str] = {
    "DoorBlock": "door",
    "BNWoodlikeDoor": "door",
    "TrapDoorBlock": "trapdoor",
    "FenceBlock": "fence",
    "FenceGateBlock": "fence_gate",
    "WallBlock": "wall",
    "BNPane": "pane",
    "LadderBlock": "ladder",
    "ChestBlock": "chest",
    "BlockChestOfDrawers": "drawers",
    "BNBrewingStand": "brewing_stand",
    "BlockNetherFurnace": "furnace",
    "BlockCincinnasiteForge": "forge",
    "PressurePlateBlock": "plate",
}


def _upstream_root() -> Path:
    """仓库内上游根目录；与 `blocks.py` 的 `upstream/BetterNether` 同一约定。"""
    return Path(__file__).resolve().parents[2] / "upstream" / "BetterNether"


@lru_cache(maxsize=4)
def _classes(root: str) -> dict[str, str]:
    """`betternether:<name>` -> 上游类名。读不到就退出，绝不退回「没有类信息」的空表。"""
    from bnlib import props

    loaded = props.load_props(Path(root))
    if not loaded:
        raise SystemExit(
            f"读不到上游方块属性表（{Path(root) / 'src/main/generated/block_properties.json'}）："
            "没有 class 字段就无法判定「碰撞/功能不完整方块」的移除范围，拒绝继续。")
    return {key.split(":", 1)[-1]: value.cls for key, value in loaded.items()}


def _block_class(ident: str) -> str:
    return _classes(str(_upstream_root())).get(_bare(ident), "")


def _bare(ident: str) -> str:
    """`betternether:bone_wall` -> `bone_wall`；无命名空间时按本包处理。"""
    namespace, _, name = ident.partition(":")
    return name if name else namespace


def is_removed_block(ident: str) -> bool:
    """仅排除本包已确认移除的方块，不误删原版或其它命名空间。"""
    namespace, _, name = ident.partition(":")
    if not name:
        namespace, name = "betternether", namespace
    if namespace != "betternether":
        return False
    if name.endswith(SHAPE_SUFFIXES) or name.endswith(OPENABLE_SUFFIXES):
        return True
    if name.endswith(BOOK_SUFFIXES):
        return True
    if name.endswith(SEAT_SUFFIXES) or name in SEAT_PREFIX_NAMES:
        return True
    if f"betternether:{name}" in REMOVED_BLOCK_IDS:
        return True
    return _block_class(name) in REMOVED_CLASSES


def _family(ident: str) -> str:
    """移除族名；未移除返回空串。台阶/楼梯按名字判，其余按上游类判。"""
    name = _bare(ident)
    if name.endswith("_stairs"):
        return "stairs"
    if name.endswith("_slab"):
        return "slab"
    if name.endswith(BOOK_SUFFIXES):
        return "bookshelf"
    if name.endswith(SEAT_SUFFIXES) or name in SEAT_PREFIX_NAMES:
        return "seat"
    return _CLASS_FAMILY.get(_block_class(name), "")


def _stairs_material(name: str) -> str:
    if name.startswith("bone_"):
        return "quartz"
    if name.startswith("soul_sandstone"):
        return "sandstone"
    return "nether_brick"


def _wall_material(name: str) -> str:
    if name.startswith("soul_sandstone"):
        return "sandstone"
    if name.startswith(("basalt", "blackstone")):
        return "blackstone"
    return "nether_brick"


def replacement_for(ident: str) -> tuple[str, frozenset[str] | None]:
    """`(替换后的 id, 允许保留的属性名)`；未被移除时返回 `(ident, None)`（属性不动）。

    台阶/楼梯按材质借原版同形块，保留高度与朝向；门洞、窗格、栅栏、容器同样借原版同形块，
    不让建筑出现空洞；只有确实没有同形对应的（间歇泉、钟乳石、雕像……）才落 `minecraft:air`，
    此时属性全部丢弃（`air` 不带状态，留着属性会让解析器认为它是带属性的方块）。
    """
    if not is_removed_block(ident):
        return ident, None
    name = _bare(ident)
    family = _family(ident)
    if family == "stairs":
        return f"minecraft:{_stairs_material(name)}_stairs", _PROPS_STAIRS
    if family == "slab":
        return f"minecraft:{_stairs_material(name)}_slab", _PROPS_SLAB
    if family == "wall":
        return f"minecraft:{_wall_material(name)}_wall", _PROPS_WALL
    if family == "pane":
        # 玻璃窗格保留透光，其余（辛辛那石栏杆）是金属栏杆。
        if "glass" in name:
            return "minecraft:glass_pane", _PROPS_PANE
        return "minecraft:iron_bars", _PROPS_PANE
    return _FAMILY_TARGET[family]


#: 族 -> (原版 id, 属性白名单)。台阶/楼梯/墙/窗格的目标随材质变化，见 `replacement_for`。
_FAMILY_TARGET: dict[str, tuple[str, frozenset[str]]] = {
    # 下界木族的门/活板门/栅栏借绯红木 —— 同维度、同属木质方块，几何与朝向一致。
    # 栅栏门**不能**用 `nether_brick_fence_gate`：原版只有 `nether_brick_fence`，没有对应的
    # 栅栏门（`data/minecraft/loot_table/blocks/` 里查无此 id），借绯红木栅栏门。
    "door": ("minecraft:crimson_door", _PROPS_DOOR),
    "trapdoor": ("minecraft:crimson_trapdoor", _PROPS_TRAPDOOR),
    "fence": ("minecraft:nether_brick_fence", _PROPS_FENCE),
    "fence_gate": ("minecraft:crimson_fence_gate", _PROPS_FENCE_GATE),
    "ladder": ("minecraft:ladder", _PROPS_LADDER),
    "chest": ("minecraft:chest", _PROPS_CHEST),
    "drawers": ("minecraft:barrel", _PROPS_BARREL),
    "brewing_stand": ("minecraft:brewing_stand", _PROPS_BREWING_STAND),
    "furnace": ("minecraft:furnace", _PROPS_FURNACE),
    "forge": ("minecraft:blast_furnace", _PROPS_FURNACE),
    "plate": ("minecraft:stone_pressure_plate", _PROPS_PLATE),
    # 书架：借原版书架（同形、同材质语义，结构里看起来仍然是书架）。
    # 座椅三族（chair/taburet/bar_stool）没有同形原版块，落 air。
    # 这两个族由 `_family()` 的 `_bookshelf/_chiseled_bookshelf` 与座椅后缀产生，此前
    # 忘了登记，于是任何模板只要出现一件 BN 书架/座椅，`replacement_id()` 直接 KeyError
    # 把整个 processResources 打断（当前不触发，只因源模板里的座椅已被提前清成 air）。
    "bookshelf": ("minecraft:bookshelf", _PROPS_NONE),
    "seat": ("minecraft:air", _PROPS_NONE),
    # 自定义类注册的几何/交互体：没有同形原版块，落 air 而不是借一个形状对不上的方块。
    "": ("minecraft:air", _PROPS_NONE),
}


def replacement_id(ident: str) -> str:
    """只取替换后的 id（结构、树模板、feature 共用同一套规则）。"""
    return replacement_for(ident)[0]


def filter_properties(ident: str, keys: object) -> list[str]:
    """替换目标能接受的属性名（按上游顺序），不接受的丢掉。"""
    _target, allowed = replacement_for(ident)
    if allowed is None:
        return list(keys)  # type: ignore[arg-type]
    return [key for key in keys if key in allowed]  # type: ignore[union-attr]

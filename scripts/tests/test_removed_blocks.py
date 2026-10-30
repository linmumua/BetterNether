"""用户删除异形/不完整方块时，发布清单与结构/树模板必须同时保持闭合。

两个"必须"贯穿全文件：

  * **不误删**：原版与其它命名空间、满格方块（`cincinnasite_brick_plate`）、植物、发光钟乳石
    一律不能被移除规则碰到 —— 用户只授权删「碰撞或功能不完整」的那些。
  * **不留悬空引用**：结构 NBT、树模板、worldgen 特征里指向已移除方块的条目必须换成原版同形块
    或整条停发。少一处，游戏里就是一座缺件的建筑，且 jigsaw 与 CE 都**静默**跳过，不会报错。
"""

import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bnlib import assets, nbt, structures, worldgen
from bnlib.publication import is_removed_block, replacement_for, replacement_id
from prepare_structure_resources import prepare

PROJECT_ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = PROJECT_ROOT / "upstream" / "BetterNether"
UPSTREAM_PROPERTIES = (UPSTREAM / "src/main/generated/block_properties.json")
# 1.21.4+ 客户端物品定义（`items/<id>.json`），资源包按 `<ns>:upstream/<id>` 引用。
UPSTREAM_ITEMS = UPSTREAM / "src/main/generated/assets/betternether/items"


def _gradle_path(key: str) -> Path | None:
    """`gradle.properties` 里的本机 jar 路径（非 ASCII 段是 `\\uXXXX` 转义）。"""
    path = PROJECT_ROOT / "gradle.properties"
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith(f"{key}="):
            continue
        raw = line.split("=", 1)[1].strip()
        if "\\u" in raw:
            raw = raw.encode("utf-8").decode("unicode_escape")
        return Path(raw)
    return None


def _local_vanilla_blocks() -> set[str] | None:
    """本地原版注册表：直接读**服务端 jar 自带的原版数据**，不靠命名正则猜。

    原版给每个方块都导出 `data/minecraft/loot_table/blocks/<id>.json`，这是服务端 jar 里
    真实存在的清单，比自己拼一份「看起来像原版 id」的字符串表可靠 —— 审查发现的
    `minecraft:nether_brick_fence_gate` 就是被这条查出来的（原版只有 `nether_brick_fence`）。
    """
    jar = _gradle_path("serverJar")
    if jar is None or not jar.is_file():
        return None
    prefix = "data/minecraft/loot_table/blocks/"
    with zipfile.ZipFile(jar) as archive:
        return {name[len(prefix):-len(".json")] for name in archive.namelist()
                if name.startswith(prefix) and name.endswith(".json")}


def test_every_replacement_target_exists_in_the_local_vanilla_registry():
    """所有移除族的目标 id 必须是本地原版注册表里真有的方块（air 除外）。

    这条是「替换成同形原版块」的硬前提：目标不存在时，结构 palette 会指向一个未注册方块，
    jigsaw 静默跳过 —— 玩家看到的是凭空少了一块的门/栅栏门，日志里什么都没有。
    """
    registry = _local_vanilla_blocks()
    if registry is None:
        import pytest
        pytest.skip("本机没有 gradle.properties 里配置的服务端 jar，无法校验原版注册表")
    assert len(registry) > 900, f"从服务端 jar 只读到 {len(registry)} 个方块，注册表读取已失效"

    entries = json.loads(UPSTREAM_PROPERTIES.read_text(encoding="utf-8"))
    removed = [entry["id"] for entry in entries if is_removed_block(entry["id"])]
    assert len(removed) > 200, f"移除规则只命中了 {len(removed)} 个方块，规则本身已失效"

    missing = sorted({(ident.split(":", 1)[1], replacement_id(ident)) for ident in removed
                      if replacement_id(ident) != "minecraft:air"
                      and replacement_id(ident).split(":", 1)[1] not in registry})
    assert not missing, f"替换目标在原版注册表里不存在：{missing}"


def test_vanilla_has_no_nether_brick_fence_gate():
    """审查发现的历史错误：原版有 `nether_brick_fence`，但**没有**对应的栅栏门。"""
    registry = _local_vanilla_blocks()
    if registry is None:
        import pytest
        pytest.skip("本机没有服务端 jar，无法校验原版注册表")
    assert "nether_brick_fence" in registry
    assert "crimson_fence_gate" in registry
    assert "nether_brick_fence_gate" not in registry


def sample_tree(block_id="betternether:bone_stairs", properties=None):
    tree = (nbt.TAG_COMPOUND, {
        "size": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 1)] * 3)),
        "palette": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            (nbt.TAG_COMPOUND, {
                "Name": (nbt.TAG_STRING, block_id),
                "Properties": (nbt.TAG_COMPOUND, properties or {
                    "facing": (nbt.TAG_STRING, "west"),
                    "half": (nbt.TAG_STRING, "top"),
                    "shape": (nbt.TAG_STRING, "straight"),
                    "waterlogged": (nbt.TAG_STRING, "false"),
                }),
            }),
            (nbt.TAG_COMPOUND, {"Name": (nbt.TAG_STRING, "betternether:bone_block")}),
        ])),
        "blocks": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            (nbt.TAG_COMPOUND, {
                "pos": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 0)] * 3)),
                "state": (nbt.TAG_INT, 0),
            }),
        ])),
    })
    return tree


def test_removed_covers_stairs_slabs_and_incomplete_shapes():
    for ident in ("betternether:bone_stairs", "betternether:willow_slab",
                  "nether_reed_roof_slab", "betternether:bone_wall",
                  "betternether:quartz_glass_framed_pane_red", "betternether:willow_door",
                  "betternether:nether_reed_ladder", "betternether:willow_fence",
                  "betternether:stalagnate_plate", "betternether:cincinnasite_lantern_small",
                  # 2026-10-29 容器类（chest / chest_of_drawers / brewing stand）解禁：
                  # 它们改由 `simple_storage_block` 提供真实容器，不再算「移除」。
                  # 留在这里的 furnace / forge / geyser 仍然没有 CE 机制可表达。
                  "betternether:cincinnasite_forge", "betternether:netherrack_furnace",
                  "betternether:geyser"):
        assert is_removed_block(ident), ident
    # 2026-10-30 反转：四种钟乳石里 bone_stalactite 作为合成原料被恢复发布
    # （见 publication.REMOVED_BLOCK_IDS 的注释），所以它**不再**属于移除集合。
    assert not is_removed_block("betternether:bone_stalactite")


def test_user_named_plants_are_removed_and_their_neighbours_are_not():
    """用户只点名 `neon_equisetum` 与 `soul_lily` 两种植物，一个变种都不能被顺手带走。

    `soul_lily_sapling` 是**独立注册**的方块：用户没有要求删它，也没有任何
    worldgen / 结构 / 配方引用它（它的去留由「无引用不发布」的通用规则决定，不由本规则决定）。
    """
    for ident in ("betternether:neon_equisetum", "betternether:soul_lily"):
        assert is_removed_block(ident), ident
        # 植物没有同形原版块，只能落 air
        assert replacement_id(ident) == "minecraft:air"

    for ident in ("betternether:soul_lily_sapling", "betternether:soul_vein",
                  "betternether:jungle_grass", "betternether:lumabus_seed",
                  "betterend:soul_lily", "minecraft:soul_lily"):
        assert not is_removed_block(ident), ident


def test_removal_never_touches_vanilla_full_blocks_or_plants():
    """删多了就是「用户没要求的东西被拿掉」——这一条是它的回归护栏。"""
    for ident in ("minecraft:stone_stairs", "betterend:stone_slab",
                  "betternether:willow_log", "betternether:barrel_cactus",
                  "betternether:rubeus_cone", "betternether:red_large_mushroom",
                  # 发光钟乳石：用户只点名了另外 4 种
                  "betternether:glowstone_stalactite",
                  "betternether:barrel", "betternether:quartz_glass"):
        assert not is_removed_block(ident), ident
    # 2026-10-30 反转：`cincinnasite_brick_plate` 虽是满格方块，但用户当轮口径是
    # 「可开关类方块全都删掉」，它已在 REMOVED_BLOCK_IDS 里逐条点名删除。
    # 这条断言改为锁住「它确实被删」，防止哪天被误恢复成可放置却没人发现。
    assert is_removed_block("betternether:cincinnasite_brick_plate")


def test_removed_blocks_client_item_definitions_are_not_released():
    """被移除方块的客户端物品定义不能释放，普通物品一个都不能被误删。

    **2026-10-29 再反转**：`*_chest` 方块（`ChestBlock`）用户要求「箱子都不要了」，
    重新进 `REMOVED_CLASSES` —— 所以它们的 `*_chest.json` 物品定义**不得释放**。
    `*_chest_boat` / `*_chestplate` 按名字误伤的警告仍然有效（它们不是被移除的方块）。

    反向断言同样重要：`*_chest_boat` 是船、`*_chestplate` 是盔甲，名字里都带 `chest`，
    但都不是被移除的方块，必须照常释放；否则就是用户提醒过的「按名字一刀切」。
    `assets.generate()` 走的是同一个判据（`removed_block_item_definitions` 是唯一调用点）。
    """
    analysis = assets.analyze(UPSTREAM)
    dropped = assets.removed_block_item_definitions(analysis)

    present = {path.name for path in UPSTREAM_ITEMS.glob("*.json")}
    chests = sorted(name for name in present if name.endswith("_chest.json"))
    assert len(chests) == 14, f"上游 *_chest.json 有 {len(chests)} 个，与 14 个的前提不符"
    assert set(chests) <= dropped, (
        f"箱子已重新移除，物品定义必须停发：{sorted(set(chests) - dropped)}")
    # 判据失效（比如 is_removed_block 恒假）时 dropped 会是空集，这条把它挡下来。
    assert len(dropped) > 200, f"只排除了 {len(dropped)} 个物品定义，判据已失效"

    kept = ("cincinnasite_chestplate.json",
            "cincinnasite_ingot.json",
            "nether_ruby.json", "bone_block.json", "magma_flower.json")
    missing = [name for name in kept if name not in present]
    assert not missing, f"上游这几个物品定义不见了，反向断言已失效：{missing}"
    for name in kept:
        assert name not in dropped, f"普通物品定义被误删：{name}"

    # 2026-10-29 反转：船/筏整族连同模型一起删除，所以 `*_boat.json` / `*_raft.json`
    # （含 `*_chest_boat` / `*_chest_raft`）必须停发 —— 否则客户端会引用不存在的船模型。
    # 名字里带 `chest` 不再是「误伤」的理由：判定看的是它属于哪一族，不是字面。
    boats = (name for name in present if name.endswith("_boat.json") or name.endswith("_raft.json"))
    leaked = sorted(name for name in boats if name not in dropped)
    assert not leaked, f"船/筏物品定义没有停发（会缺模型）：{leaked}"


def test_shape_replacements_keep_geometry_and_drop_unknown_properties():
    assert replacement_id("betternether:bone_stairs") == "minecraft:quartz_stairs"
    assert replacement_id("betternether:soul_sandstone_slab") == "minecraft:sandstone_slab"
    # 门洞、窗格、栅栏不能留空洞，也不能留一块形状对不上的方块
    assert replacement_id("betternether:willow_door") == "minecraft:crimson_door"
    assert replacement_id("betternether:cincinnasite_bars") == "minecraft:iron_bars"
    assert replacement_id("betternether:quartz_glass_pane_red") == "minecraft:glass_pane"
    assert replacement_id("betternether:bone_wall") == "minecraft:nether_brick_wall"
    # 没有同形原版块的几何/交互体落 air
    assert replacement_id("betternether:geyser") == "minecraft:air"

    target, allowed = replacement_for("betternether:cincinnasite_stairs")
    assert target == "minecraft:nether_brick_stairs"
    assert "half" in allowed and "axis" not in allowed
    assert replacement_for("betternether:willow_log") == ("betternether:willow_log", None)


def test_palette_replacement_keeps_geometry_indices_and_unrelated_entries():
    source = sample_tree()
    changed, stats = structures.replace_removed_palette_blocks(source)
    old_entries = source[1]["palette"][1][1]
    entries = changed[1]["palette"][1][1]
    assert len(entries) == len(old_entries)
    assert entries[0][1]["Name"][1] == "minecraft:quartz_stairs"
    assert entries[0][1]["Properties"] == old_entries[0][1]["Properties"]
    assert entries[1] == old_entries[1]
    assert changed[1]["blocks"] == source[1]["blocks"]
    assert old_entries[0][1]["Name"][1] == "betternether:bone_stairs"
    assert stats["placements"] == stats["palette_entries"] == 1
    second, empty = structures.replace_removed_palette_blocks(changed)
    assert second is changed and empty["placements"] == 0


def test_fence_gate_replacement_is_a_real_vanilla_block_and_keeps_its_properties():
    """栅栏门必须换成**真存在**的原版栅栏门，且 facing/in_wall/open/powered 一个都不丢。

    换错 id（例如不存在的 `nether_brick_fence_gate`）时，palette 会被 jigsaw 静默跳过 ——
    建筑上就是一个空缺的门洞，没有任何日志。
    """
    source = sample_tree("betternether:nether_reed_gate", {
        "facing": (nbt.TAG_STRING, "east"),
        "in_wall": (nbt.TAG_STRING, "false"),
        "open": (nbt.TAG_STRING, "true"),
        "powered": (nbt.TAG_STRING, "true"),
    })
    changed, stats = structures.replace_removed_palette_blocks(source)
    entry = changed[1]["palette"][1][1][0][1]
    assert entry["Name"][1] == "minecraft:crimson_fence_gate"
    assert entry["Properties"][1] == {
        "facing": (nbt.TAG_STRING, "east"),
        "in_wall": (nbt.TAG_STRING, "false"),
        "open": (nbt.TAG_STRING, "true"),
        "powered": (nbt.TAG_STRING, "true"),
    }
    assert stats["placements"] == 1


def test_replacement_drops_properties_the_vanilla_block_would_reject():
    """`cincinnasite_slab` 的 palette 里混着一个 `half`：带到原版台阶上会让整条条目解析失败。"""
    source = sample_tree("betternether:cincinnasite_slab", {
        "type": (nbt.TAG_STRING, "top"),
        "waterlogged": (nbt.TAG_STRING, "false"),
        "half": (nbt.TAG_STRING, "top"),
    })
    changed, _stats = structures.replace_removed_palette_blocks(source)
    entry = changed[1]["palette"][1][1][0][1]
    assert entry["Name"][1] == "minecraft:nether_brick_slab"
    assert set(entry["Properties"][1]) == {"type", "waterlogged"}


def test_air_replacement_removes_every_property():
    source = sample_tree("betternether:geyser", {"powered": (nbt.TAG_STRING, "false")})
    changed, _stats = structures.replace_removed_palette_blocks(source)
    entry = changed[1]["palette"][1][1][0][1]
    assert entry["Name"][1] == "minecraft:air"
    assert "Properties" not in entry and "properties" not in entry


def test_multi_palette_is_rewritten_not_rejected():
    """多调色板：`state` 同时编码调色板下标与条目下标，改写 id 不动任何一个下标。"""
    def palette(ident):
        return (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            (nbt.TAG_COMPOUND, {"Name": (nbt.TAG_STRING, ident)}),
        ]))

    tree = (nbt.TAG_COMPOUND, {
        "size": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 2)] * 3)),
        "palettes": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            palette("betternether:bone_wall"),
            palette("betternether:bone_block"),
        ])),
        # 位宽 1：state 0 = 调色板 0 条目 0（已移除）；state 2 = 调色板 1 条目 0（保留）
        "blocks": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            (nbt.TAG_COMPOUND, {
                "pos": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 0)] * 3)),
                "state": (nbt.TAG_INT, 0),
            }),
            (nbt.TAG_COMPOUND, {
                "pos": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 1)] * 3)),
                "state": (nbt.TAG_INT, 2),
            }),
        ])),
    })
    changed, stats = structures.replace_removed_palette_blocks(tree)
    palettes = changed[1]["palettes"][1][1]
    assert palettes[0][1][1][0][1]["Name"][1] == "minecraft:nether_brick_wall"
    assert palettes[1][1][1][0][1]["Name"][1] == "betternether:bone_block"
    assert changed[1]["blocks"] == tree[1]["blocks"]
    assert stats["placements"] == 1


def test_tree_template_conversion_uses_the_same_publication_rule(tmp_path):
    path = tmp_path / "bone.nbt"
    tree = sample_tree()
    nbt.write_file(path, "", tree[0], tree[1])
    converted = worldgen.convert_template(path)
    assert converted["palette"][0]["id"] == "minecraft:quartz_stairs"
    assert converted["palette"][0]["properties"]["facing"] == "west"
    assert converted["palette"][1]["id"] == "betternether:bone_block"


def test_tree_template_drops_properties_the_replacement_rejects(tmp_path):
    path = tmp_path / "slab.nbt"
    tree = sample_tree("betternether:cincinnasite_slab", {
        "type": (nbt.TAG_STRING, "top"),
        "half": (nbt.TAG_STRING, "top"),
    })
    nbt.write_file(path, "", tree[0], tree[1])
    converted = worldgen.convert_template(path)
    assert converted["palette"][0]["id"] == "minecraft:nether_brick_slab"
    assert converted["palette"][0]["properties"] == {"type": "top"}


def test_weighted_entries_placing_removed_blocks_are_dropped():
    """`vegetation_magma_land` 同时放间歇泉与岩浆花：摘掉间歇泉那一条，岩浆花必须留下。"""
    node = {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "betternether:geyser", "weight": 40},
        {"data": "betternether:magma_flower", "weight": 30},
    ]}}
    sanitized, dropped, emptied = worldgen.strip_removed_placements(node)
    assert dropped == 1 and not emptied
    assert [entry["data"] for entry in sanitized["to_place"]["entries"]] == ["betternether:magma_flower"]


class _StubData:
    """只提供 `placed` / `configured` 的最小替身，用来测分类层而不碰上游文件。"""

    def __init__(self, placed, configured):
        self._placed = placed
        self._configured = configured

    def placed(self, ident):
        return self._placed.get(ident)

    def configured(self, ident):
        return self._configured.get(ident)


def test_feature_of_a_removed_algorithmic_type_is_stopped():
    """`betternether:soul_lily` 的方块 id 在 Java 算法里，JSON 里没有 id 可摘 → 整条停发。"""
    data = _StubData(
        placed={"betternether:patch_soul_lily": {"feature": {"type": "betternether:soul_lily"}}},
        configured={},
    )
    assert worldgen.removed_placement_reason(data, "betternether:patch_soul_lily") == \
        worldgen.REMOVED_BLOCK_REASON


def test_structural_block_reference_stops_the_whole_feature():
    """`block_column` 的 `layers[].provider` 是结构性引用：摘不掉，只能整条停发。

    这条同时钉住「必须沿特征引用走到真正持有方块 id 的那份 JSON」——`patch_neon_equisetum`
    自己只写了 `"feature": "betternether:neon_equisetum"`，只看一层就会漏。
    """
    data = _StubData(
        placed={"betternether:patch_neon_equisetum": {"feature": "betternether:neon_equisetum"}},
        configured={"betternether:neon_equisetum": {
            "type": "minecraft:block_column",
            "layers": [{"height": 1, "provider": {"id": "betternether:neon_equisetum",
                                                  "properties": {"shape": "top"}}}],
        }},
    )
    assert worldgen.removed_placement_reason(data, "betternether:patch_neon_equisetum") == \
        worldgen.REMOVED_BLOCK_REASON


def test_nested_configured_reference_is_followed_to_the_removed_block():
    data = _StubData(
        placed={"betternether:outer": {"feature": "betternether:middle"}},
        configured={
            "betternether:middle": {"type": "wover:random_patch",
                                    "feature": {"feature": "betternether:inner"}},
            "betternether:inner": {"type": "minecraft:block_column", "layers": [
                {"height": 1, "provider": {"id": "betternether:neon_equisetum"}}]},
        },
    )
    assert worldgen.removed_placement_reason(data, "betternether:outer") == \
        worldgen.REMOVED_BLOCK_REASON


def test_dropping_one_candidate_does_not_kill_the_feature():
    """摘掉间歇泉那一条候选后岩浆花仍在：这条特征必须继续发布，不能被判死。"""
    data = _StubData(
        placed={"betternether:vegetation_magma_land": {"feature": "betternether:veg"}},
        configured={"betternether:veg": {
            "type": "wover:random_patch",
            "feature": {"feature": {"type": "minecraft:simple_block", "to_place": {
                "type": "minecraft:weighted",
                "entries": [{"data": "betternether:geyser", "weight": 40},
                            {"data": "betternether:magma_flower", "weight": 30}],
            }}},
        }},
    )
    assert worldgen.removed_placement_reason(data, "betternether:vegetation_magma_land") is None


def test_removed_block_features_are_stopped_and_reported_as_excluded():
    """真实上游跑一遍分类：引用了已移除方块的特征必须停发，而且**不能**记成「未解释的跳过」。

    `skipped` 的契约是空（上游每一条 step 9 特征都能解释）；用户移除方块造成的停发记在
    `excluded` 里，门禁据此逐条断言 —— 这份清单就是「哪些内容因为删方块而不再生成」的
    唯一账本，少一条会重新把未注册方块写进世界，多一条会静默少内容。

    2026-10-30 更新：清单此前只写了两条，之后藤蔓、座椅、书架、酿造台陆续移除后一直
    没同步（测试早已红）。本次把三棵「树干/种子方块已删」的树一并纳入：
      * tree_willow / tree_old_willow —— 需要 `willow_trunk` / `willow_branch`
      * tree_wart                     —— 需要 `wart_seed`
    """
    report = worldgen.build(Path(__file__).resolve().parents[2] / "upstream/BetterNether")
    assert set(report.excluded) == {
        # 方块被移除、JSON 里能摘掉整条候选的
        "betternether:patch_neon_equisetum",
        "betternether:patch_soul_lily",
        "betternether:patch_black_vine",
        "betternether:patch_gloomsculk_vine",
        "betternether:patch_golden_lumabus_vine",
        "betternether:patch_whispering_gourd_vine",
        "betternether:patch_stalagnate",
        "betternether:vegetation_nether_poor_grassland",
        # 方块 id 写死在 Java 算法里、只能整条停发的（REMOVED_FEATURE_TYPES）
        "betternether:tree_willow",
        "betternether:tree_old_willow",
        "betternether:tree_wart",
    }
    assert set(report.excluded.values()) == {worldgen.REMOVED_BLOCK_REASON}
    for ref in report.excluded:
        assert ref not in report.supported and ref not in report.skipped
    # 摘得掉候选的那条特征仍在支持集里（间歇泉被摘、岩浆花保留）
    assert "betternether:vegetation_magma_land" in report.supported


def test_feature_that_only_places_a_removed_block_is_reported_as_emptied():
    """摘空的特征必须整条停发，而不是写出一条什么也放不下的空特征。"""
    node = {"type": "minecraft:simple_block", "to_place": {"type": "minecraft:weighted", "entries": [
        {"data": "betternether:geyser", "weight": 1},
    ]}}
    _sanitized, dropped, emptied = worldgen.strip_removed_placements(node)
    assert dropped == 1 and emptied


def test_resource_processing_is_scoped_and_idempotent(tmp_path):
    path = tmp_path / "datapacks/betternether-structures/data/betternether/structure/test.nbt"
    path.parent.mkdir(parents=True)
    tree = sample_tree()
    nbt.write_file(path, "", tree[0], tree[1])
    unrelated = tmp_path / "other-package.nbt"
    unrelated.write_bytes(path.read_bytes())
    before = unrelated.read_bytes()
    assert prepare(tmp_path)["placements"] == 1
    assert unrelated.read_bytes() == before
    stable = path.read_bytes()
    assert prepare(tmp_path)["placements"] == 0
    assert path.read_bytes() == stable

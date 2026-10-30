"""植物支撑规则必须来自上游真值，且只有可证实的才发布。

为什么要测：支撑规则一旦猜错，后果不是"样子不对"，而是**方块存在但不存活**或者
**长在本不该长的方块上**。所以这里锁住四件事：

1. 规则逐条指回上游注册处（`registry/block/*.java` 的 `.addTrait(...)`），
   方向读的是 `canSurvive`（`pos.below()` / `pos.above()` / `FACING`）；
2. 白名单只由**可证实**的来源展开：本模组自己的标签（生成文件完整）、
   Java 里字面写出的原版方块常量、以及运行期一定存在的 `minecraft:*` 标签；
3. wover 公共标签的两层成员都必须有可复核来源：基表来自**官方 WorldWeaver 仓库里
   已提交的生成数据**（`wover-tag-api/src/main/generated/data/wover/tags/block/**`，
   见 `support.WOVER_TAG_BASES` 的来源注释），增量来自上游树的同名生成文件；基表里的
   `#wover:*` 引用要递归展开到不动点。语义本身 CE 表达不了的（`neon_equisetum` 的
   「同类 ∪ 树叶 ∪ 任何坚固面」OR、`soul_lily` 按 SHAPE 看四邻）**仍然列阻塞**，
   绝不写一个永远不会放行或永远破坏的配置；
4. 渲染出来的片段能被 YAML 解析成 CE 结构（`behavior.bottom_blocks` 等），
   且与 `blocks.py` 的 `behavior_yaml` 契约一致。

全部用真实上游树跑（只读），不 mock，也不写仓库内文件。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks, support  # noqa: E402

UPSTREAM = ROOT / "upstream/BetterNether"

pytestmark = pytest.mark.skipif(
    not (UPSTREAM / "src/main/java/org/betterx/betternether/registry/block").is_dir(),
    reason="需要真实上游树",
)


@pytest.fixture(scope="module")
def rules() -> dict[str, support.SupportRule]:
    return support.load_support_rules(UPSTREAM)


def _behavior(block_id: str, rules, **kwargs) -> dict:
    """把渲染结果套进最小方块骨架并解析成 dict，验证它真的是合法 CE 结构。"""
    entries = support.support_behaviors(block_id, rules=rules, **kwargs)
    if not entries:
        return {}
    document = yaml.safe_load("block:\n" + support.render_yaml(entries))
    return document["block"]


def test_rules_trace_back_to_registration(rules):
    """每个规则都必须是注册处的 `addTrait`，不是按名字推算。"""
    assert len(rules) == 36
    # 没有支撑 trait 的方块不该出现在表里（否则等于凭空给方块加限制）。
    assert "nether_reed" not in rules
    assert "stalagnate_stem" not in rules
    for rule in rules.values():
        assert rule.trait.startswith(("NetherSurvival.", "SurvivesOnSolidTrait."))
        assert rule.direction in {
            support.DIR_DOWN, support.DIR_UP, support.DIR_FACING,
            support.DIR_MIXED, support.DIR_UNKNOWN,
        }


def test_class_body_is_limited_to_the_declaring_class():
    """`canSurvive` 只能取**本类层级**的声明：一个文件里后一个类的方法不能算到前一个头上。

    这是真实存在的布局：`BlockNetherGrass.java` 里 `BaseBlockNetherGrass` 之后还有
    `NetherGrass`/`JunglePlant` 等嵌套类，整文件搜索会拿到别人的方法，进而判错方向。
    """
    import tempfile

    source = (
        "package x;\n"
        "class First extends Other {\n"
        "    void helper() { int a = 0; }\n"
        "}\n"
        "class Second extends Other {\n"
        "    public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {\n"
        "        return world.getBlockState(pos.above()).isAir();\n"
        "    }\n"
        "}\n"
    )
    with tempfile.TemporaryDirectory() as tmp:
        blocks_dir = Path(tmp) / "src/main/java/org/betterx/betternether/blocks"
        blocks_dir.mkdir(parents=True)
        (blocks_dir / "First.java").write_text(source, encoding="utf-8")

        body, evidence = support._class_body(Path(tmp), "First")
        assert body is None, "前一个类没有 canSurvive，不能把后一个类的算进来"
        assert "First" in evidence

        body, evidence = support._class_body(Path(tmp), "Second")
        assert body is not None
        assert "pos.above()" in body
        assert support._direction_of(body) == support.DIR_UP


def test_nested_class_method_is_not_attributed_to_the_outer_class():
    """嵌套类里的 canSurvive 属于嵌套类；外层类要按「没有」处理，交给 extends 链。"""
    import tempfile

    source = (
        "package x;\n"
        "abstract class Outer extends Base {\n"
        "    static class Inner extends Base {\n"
        "        public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {\n"
        "            return world.getBlockState(pos.below()).isAir();\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    with tempfile.TemporaryDirectory() as tmp:
        blocks_dir = Path(tmp) / "src/main/java/org/betterx/betternether/blocks"
        blocks_dir.mkdir(parents=True)
        (blocks_dir / "Outer.java").write_text(source, encoding="utf-8")

        outer_body, _ = support._class_body(Path(tmp), "Outer")
        assert outer_body is None
        inner_body, _ = support._class_body(Path(tmp), "Inner")
        assert inner_body is not None
        assert support._direction_of(inner_body) == support.DIR_DOWN


def test_real_upstream_class_bodies_do_not_leak_across_classes():
    """真实上游里，`BaseBlockNetherGrass` / `BlockNetherGrass` 本类层级都没有 canSurvive。"""
    for cls in ("BaseBlockNetherGrass", "BlockNetherGrass", "BlockRedMold"):
        body, evidence = support._class_body(UPSTREAM, cls)
        assert body is None, f"{cls} 不该读到别的类的 canSurvive（{evidence}）"
    # 而真正声明了它的类必须读得到。
    body, _ = support._class_body(UPSTREAM, "BlockMold")
    assert body is not None and "pos.below()" in body


def test_verified_subset_is_explicit(rules):
    """可发布集合是有限的：多一个都意味着有规则没经过证实就过了门。

    本轮解阻的是「wover 标签两层成员（基表 + 本模组增量）」：基表现在有官方
    已提交的生成数据可复核（见 support.WOVER_TAG_BASES 的注释），所以
    `netherrack` / `sculk_like` / `soil_or_logs` 三个标签能展开成完整白名单。
    """
    verified = {block_id for block_id, rule in rules.items() if rule.verified}
    assert verified == {
        # gravel（Java 里字面写出的原版方块）
        "agave", "barrel_cactus", "nether_cactus",
        # 本模组自己的标签，生成文件完整
        "jellyfish_mushroom_sapling", "soul_vein",
        # netherGround = NETHER_STONES ∪ SOUL_GROUND ∪ 菌丝 ∪ nylium（基表用原版标签）
        "black_apple", "black_apple_seed", "black_bush", "egg_plant",
        "feather_fern", "ink_bush", "ink_bush_seed", "smoker",
        # netherMycelium：基表为空，只有 betternether:nether_mycelium
        "orange_mushroom", "red_mold", "gray_mold", "giant_mold_sapling",
        # soulGroundOrFarmland
        "soul_lily_sapling",
        # SurvivesOnSolidTrait（BCLib 官方语义：坚固面 ∪ 整方块树叶）
        "gloomgrass", "pale_gloomgrass", "gloomwisp_vine",
        "wall_moss", "wall_mushroom_brown", "wall_mushroom_red",
        # boneBlocks + facing
        "bone_mushroom",
        # netherrack：基表 5 条原版 id + 本模组增量
        "hook_mushroom", "eye_seed",
        # netherrack ∪ nylium ∪ sculk_like
        "nether_grass", "swamp_grass", "jungle_plant",
        # magma_block/red_sand/sand ∪ sculk_like
        "magma_flower",
        # soil_or_logs：基表 = logs ∪ planks ∪ soil ∪ terrain（递归展开）
        "soul_grass", "bone_grass", "sepia_bone_grass",
    }
    assert len(verified) == 34


def test_nether_ground_uses_vanilla_tags_plus_expanded_increments(rules):
    """wover 标签在运行期不存在，所以**原版标签原样写**、`wover:` 标签一律展开成显式 id。"""
    behavior = _behavior("black_bush", rules)["behavior"]
    assert behavior["bottom_block_tags"] == [
        "minecraft:base_stone_nether",
        "minecraft:nylium",
    ]
    # soul_ground 的官方基表是两条显式 id（不再是推断出来的 soul_fire_base_blocks）。
    assert "minecraft:soul_sand" in behavior["bottom_blocks"]
    assert "minecraft:soul_soil" in behavior["bottom_blocks"]
    # 增量（生成文件里 betternether 自己那部分）必须逐条展开，且不能出现 wover 标签。
    assert "betternether:netherrack_moss" in behavior["bottom_blocks"]
    assert "betternether:nether_mycelium" in behavior["bottom_blocks"]
    assert not any(item.startswith("wover:") for item in behavior["bottom_block_tags"])
    # 来源必须在 notes 里写清楚（官方仓库 + 提交，可复核）。
    notes = support.inspect_support("black_bush", rules=rules).notes
    assert any("WorldWeaver" in note for note in notes)


def test_wall_plants_attach_with_blacklist_mode(rules):
    """墙生植物：支撑在 FACING.getOpposite()、判定是「任何坚固面」→ 黑名单模式、列表留空。"""
    result = support.inspect_support("wall_moss", rules=rules)
    assert result.required_properties == ("facing",)
    behavior = _behavior("wall_moss", rules)["behavior"]
    assert behavior == {"type": "directional_attached_block", "blacklist": True}
    # 没声明 facing 就不能挂贴面行为（否则配置不生效）。
    assert support.support_behaviors(
        "wall_moss", rules=rules, available_properties=set()) == []


def test_solid_rules_are_published_with_the_leaves_gap_noted(rules):
    """`SurvivesOnSolidTrait` 语义已由官方源码证实，所以默认发布；树叶分支写进 notes。"""
    behavior = _behavior("gloomgrass", rules)["behavior"]
    assert behavior == {
        "type": "sturdy_base_block",
        "direction": "down",
        "support_types": ["full"],
        "stackable": False,
        "delay": 0,
    }
    notes = support.inspect_support("gloomgrass", rules=rules).notes
    assert any("整方块树叶" in note for note in notes)
    # 自堆叠的变体（gloomwisp_vine 的 `down.getBlock() == this`）要拿到 stackable。
    assert _behavior("gloomwisp_vine", rules)["behavior"]["stackable"] is True


def test_published_rules_never_use_an_empty_whitelist(rules):
    """白名单模式 + 空列表 = 永远不放行。发布集合里不允许出现这种配置。"""
    for block_id, rule in rules.items():
        if not rule.verified:
            continue
        for entry in support.support_behaviors(block_id, rules=rules):
            text = "\n".join(entry)
            if "blacklist: false" in text:
                assert any(key in text for key in
                           ("bottom_blocks:", "bottom_block_tags:",
                            "above_blocks:", "above_block_tags:",
                            "attached_blocks:", "attached_block_tags:")), block_id


def test_gravel_whitelist_is_the_literal_vanilla_block(rules):
    """`withBlocks(Blocks.GRAVEL)` → 只有原版砂砾，不夹带任何猜出来的条目。"""
    behavior = _behavior("agave", rules)
    assert behavior["behavior"] == {
        "type": "bush_block",
        "blacklist": False,
        "stackable": False,
        "delay": 0,
        "bottom_blocks": ["minecraft:gravel"],
    }
    assert "bottom_block_tags" not in behavior["behavior"]


def test_own_tag_is_expanded_instead_of_referenced(rules):
    """`betternether:nether_sand` 展开成显式 id：CE 包不提供方块标签，写标签等于不生效。"""
    behavior = _behavior("soul_vein", rules)["behavior"]
    assert behavior["bottom_blocks"] == ["minecraft:soul_sand", "betternether:veined_sand"]
    assert "bottom_block_tags" not in behavior


def test_nylium_members_come_from_generated_tag_file(rules):
    """成员逐条等于 `data/betternether/tags/block/nether_nylium.json`，不是原版 nylium 标签。"""
    behavior = _behavior("jellyfish_mushroom_sapling", rules)["behavior"]
    assert "bottom_block_tags" not in behavior
    assert behavior["bottom_blocks"] == [
        "minecraft:crimson_nylium",
        "betternether:jungle_grass",
        "betternether:mushroom_grass",
        "betternether:sepia_mushroom_grass",
        "betternether:swampland_grass",
        "minecraft:warped_nylium",
    ]


def test_self_stack_becomes_bounded_stack(rules):
    """上游「支撑物可以是自己」没有上界，CE 需要具体 max_height —— 取舍必须留痕。"""
    result = support.inspect_support("nether_cactus", rules=rules)
    behavior = _behavior("nether_cactus", rules)["behavior"]
    assert behavior["stackable"] is True
    assert behavior["max_height"] == 255
    assert any("无限自堆叠" in note for note in result.notes)
    # 没有同类自堆叠的方块不能平白获得堆叠能力。
    assert _behavior("agave", rules)["behavior"]["stackable"] is False


def test_wover_base_tables_are_self_consistent():
    """基表里只允许「显式 id」或 `#` 标签引用，且必须自洽。

    这张表是**手抄**官方生成数据的，抄错一个成员不会报错、只会让植物少一块能站的地，
    所以这里锁住可机械检查的部分：`#wover:*` 引用必须都能在这张表里解析到（拼错即失败），
    而 `betternether:` 只能出现在增量层 —— wover 不可能注册本模组的方块。
    """
    for tag, entries in support.WOVER_TAG_BASES.items():
        assert tag.startswith("wover:"), tag
        for entry in entries:
            if entry.startswith("#"):
                nested = entry[1:]
                assert ":" in nested, f"{tag} 的标签引用缺少命名空间：{entry}"
                if nested.startswith("wover:"):
                    assert nested in support.WOVER_TAG_BASES, (
                        f"{tag} 引用了没有基表的 {nested}")
            else:
                assert ":" in entry, f"{tag} 的成员不是带命名空间的 id：{entry}"
                assert not entry.startswith("betternether:"), (
                    f"{tag} 的基表里出现了本模组方块 {entry}（那是增量层的东西）")


def test_common_tag_paths_come_from_the_official_constants():
    """常量名 → 标签路径必须逐条落在官方 `CommonBlockTags.java` 的写法上。

    官方里不少常量名和路径不同名（`NETHERRACK` → `surfaces/nether/netherrack`、
    `SOIL_OR_LOGS` → `surfaces/soil_or_logs`），按命名习惯推路径会静默查到别的标签。
    """
    expected = {
        "NETHER_STONES": "surfaces/nether/stones",
        "SOUL_GROUND": "surfaces/soul_ground",
        "NETHER_MYCELIUM": "surfaces/nether/mycelium",
        "NETHERRACK": "surfaces/nether/netherrack",
        "SCULK_LIKE": "surfaces/sculk_like",
        "SOIL_OR_LOGS": "surfaces/soil_or_logs",
    }
    for const, path in expected.items():
        assert support.WOVER_TAG_PATHS[const] == path
        assert f"wover:{path}" in support.WOVER_TAG_BASES, path


def test_netherrack_tag_expands_from_official_base_plus_increments(rules):
    """`wover:surfaces/nether/netherrack`：官方基表 5 条原版 id + 本模组 12 条增量。"""
    assert support.WOVER_TAG_BASES["wover:surfaces/nether/netherrack"] == (
        "minecraft:crimson_nylium",
        "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:netherrack",
        "minecraft:warped_nylium",
    )
    behavior = _behavior("hook_mushroom", rules)["behavior"]
    assert behavior["type"] == "hanging_block"
    assert behavior["above_blocks"][:5] == [
        "minecraft:crimson_nylium", "minecraft:nether_gold_ore",
        "minecraft:nether_quartz_ore", "minecraft:netherrack",
        "minecraft:warped_nylium",
    ]
    assert "betternether:netherrack_moss" in behavior["above_blocks"]
    # 基表全是显式 id，所以这里不该出现任何标签字段。
    assert "above_block_tags" not in behavior


def test_sculk_like_tag_is_vanilla_sculk_plus_increments(rules):
    """`wover:surfaces/sculk_like` 基表只有 `minecraft:sculk`，其余全来自本模组增量。"""
    assert support.WOVER_TAG_BASES["wover:surfaces/sculk_like"] == ("minecraft:sculk",)
    behavior = _behavior("magma_flower", rules)["behavior"]
    assert behavior["bottom_blocks"] == [
        "minecraft:sculk",
        "betternether:bleached_gloomsculk",
        "betternether:gloomsculk_geode",
        "betternether:molten_gloomsculk",
        "betternether:veined_gloomsculk",
        "minecraft:magma_block",
        "minecraft:red_sand",
        "minecraft:sand",
    ]


def test_soil_or_logs_resolves_recursively_from_the_official_tables(rules):
    """`soil_or_logs` 本模组没有任何增量文件，白名单完全来自官方基表（含嵌套的 soil/terrain）。

    这是本轮最容易被"猜"坏的一条：只看名字会以为是「土或原木」，而官方基表其实是
    `#minecraft:logs ∪ #minecraft:planks ∪ #wover:surfaces/soil ∪ #wover:surfaces/terrain`，
    terrain 又往下牵到 end/stones、mycelium、nether/terrain、ores/nether、netherrack、
    soul_ground —— 所以必须递归展开到不动点。
    """
    assert support.WOVER_TAG_BASES["wover:surfaces/soil_or_logs"] == (
        "#minecraft:logs", "#minecraft:planks",
        "#wover:surfaces/soil", "#wover:surfaces/terrain",
    )
    behavior = _behavior("soul_grass", rules)["behavior"]
    assert behavior["bottom_block_tags"] == [
        "minecraft:logs",
        "minecraft:planks",
        "minecraft:dirt",
        "minecraft:base_stone_overworld",
        "minecraft:dripstone_replaceable_blocks",
        "minecraft:nylium",
    ]
    blocks = set(behavior["bottom_blocks"])
    # 来自 soil / terrain 直接成员
    assert {"minecraft:end_stone", "minecraft:farmland", "minecraft:sand",
            "minecraft:grass_block", "minecraft:bone_block"} <= blocks
    # 来自嵌套的 nether/terrain → netherrack / ores/nether / soul_ground
    assert {"minecraft:blackstone", "minecraft:netherrack",
            "minecraft:soul_sand", "minecraft:nether_quartz_ore"} <= blocks
    # 本模组增量：terrain 与 nether/terrain 两层的生成文件都读到了
    assert {"betternether:nether_mycelium", "betternether:soul_sandstone",
            "betternether:jungle_grass"} <= blocks
    assert not any(item.startswith("wover:") for item in behavior["bottom_block_tags"])


def test_multi_direction_survival_stays_blocked(rules):
    """`BlockSoulLily.canSurvive` 按 SHAPE 分支，单条 CE 行为表达不了。

    源码（`blocks/BlockSoulLily.java:206-221`）：`big_top_side_*` 四个状态只看**水平邻居**
    是不是自己，其余状态只看 `pos.below()`。CE 的支撑行为作用于方块的所有状态，
    无论选「下方」还是「水平贴面」，另一半状态都会立刻破掉 —— 所以只能阻塞，不能近似。
    """
    source = (UPSTREAM / "src/main/java/org/betterx/betternether/blocks/BlockSoulLily.java"
              ).read_text(encoding="utf-8")
    assert "SoulLilyShape.BIG_TOP_SIDE_N" in source and "pos.north()" in source
    assert "down.getBlock() == this" in source
    assert rules["soul_lily"].direction == support.DIR_MIXED
    assert "CE 单条支撑行为表达不了" in rules["soul_lily"].blocked
    assert support.support_behaviors("soul_lily", rules=rules) == []
    assert support.support_behaviors("soul_lily", rules=rules, allow_unverified=True) == []


def test_vine_or_semantics_stays_blocked_with_the_bclib_evidence(rules):
    """`BlockNeonEquisetum extends BaseVineBlock.Growing` 的 OR 语义 CE 表达不了。

    BCLib `AbstractVineBlock.canSurvive`（官方 release/26.3）= `up.is(this)`
    ∪ `up.is(BlockTags.LEAVES)` ∪ `canSupportCenter(pos.above(), DOWN)`。
    CE 的 hanging_block 只能在白名单模式下列成员，拿不到「任何坚固面」那一支 ——
    方向虽然读出来了，发布就等于让方块在坚固面下方破掉，所以仍然不发布。
    """
    rule = rules["neon_equisetum"]
    assert rule.direction == support.DIR_UP
    assert "AbstractVineBlock" in rule.direction_evidence
    assert "BaseVineBlock.Growing" in support.BCLIB_BASE_CLASSES
    assert "任何坚固面" in rule.blocked and "hanging_block" in rule.blocked
    assert support.support_behaviors("neon_equisetum", rules=rules) == []
    assert support.support_behaviors(
        "neon_equisetum", rules=rules, allow_unverified=True) == []


def test_facing_rule_requires_the_property(rules):
    """贴面行为要 `facing` 属性；没有声明就必须挡住，而不是写一段不生效的配置。"""
    result = support.inspect_support(
        "bone_mushroom", rules=rules, available_properties=set())
    assert result.entries == []
    assert "facing" in result.blocked
    assert support.inspect_support(
        "bone_mushroom", rules=rules).required_properties == ("facing",)


def test_unpublished_ids_are_dropped_with_a_note(rules):
    """白名单里本包没发布的 id 会让 CE 找不到方块，必须剔除并留痕。"""
    published = {"betternether:jungle_grass"}
    behavior = _behavior("jellyfish_mushroom_sapling", rules, available_ids=published)
    assert behavior["behavior"]["bottom_blocks"] == [
        "minecraft:crimson_nylium", "betternether:jungle_grass", "minecraft:warped_nylium",
    ]
    notes = support.inspect_support(
        "jellyfish_mushroom_sapling", rules=rules, available_ids=published).notes
    assert any("没有发布" in note for note in notes)


def test_unknown_block_has_no_support_behavior(rules):
    """没规则的方块返回空片段 + 明确说明，避免生成器把「无规则」当成「已处理」。"""
    result = support.inspect_support("nether_reed", rules=rules)
    assert result.entries == []
    assert result.blocked is None
    assert any("没有支撑 trait" in note for note in result.notes)


def test_render_matches_blocks_py_contract(rules):
    """缩进必须与 `blocks.py:behavior_yaml` 完全一致，否则并进配置就是错层 YAML。"""
    entries = support.support_behaviors("agave", rules=rules)
    assert support.render_yaml(entries) == blocks.behavior_yaml(entries)


def test_rules_are_deterministic(rules):
    """同一棵树两次读取必须完全一致（生成器要求幂等）。"""
    again = support.load_support_rules(UPSTREAM)
    assert again == rules


def test_tag_requirements_scope_the_blocker():
    """阻塞/解阻项都要变成可派工清单：常量、基表、增量、展开结果、缺口。"""
    requirements = support.tag_requirements(UPSTREAM)
    assert set(requirements) == {
        "wover:surfaces/nether/stones",
        "wover:surfaces/soul_ground",
        "wover:surfaces/nether/mycelium",
        "wover:surfaces/nether/netherrack",
        "wover:surfaces/sculk_like",
        "wover:surfaces/soil_or_logs",
    }
    entry = requirements["wover:surfaces/nether/netherrack"]
    assert entry["constant"] == "CommonBlockTags.NETHERRACK"
    assert entry["base_tags"] == list(
        support.WOVER_TAG_BASES["wover:surfaces/nether/netherrack"])
    assert "betternether:netherrack_moss" in entry["file_members"]
    assert "minecraft:netherrack" in entry["blocks"]
    assert "WorldWeaver" in entry["needs"], "基表来源必须可复核"
    # 本模组没有增量文件时要说「没有」，不能装作有。
    logs = requirements["wover:surfaces/soil_or_logs"]
    assert logs["file_members"] == []
    assert "没有往这个标签加成员" in logs["needs"]
    assert "#wover:surfaces/terrain" in logs["base_tags"]


def test_published_whitelists_never_reference_a_wover_tag(rules):
    """CE 包不提供方块标签，`wover:` 标签在运行期不存在 —— 出现在配置里等于没写。"""
    for block_id, rule in rules.items():
        if not rule.verified:
            continue
        for entry in support.support_behaviors(block_id, rules=rules):
            for line in entry:
                assert "wover:" not in line, (block_id, line)


def test_blocked_report_covers_every_unpublished_rule(rules):
    """阻塞清单必须与「不可发布」集合一一对应，不能有沉默的漏项。"""
    report = support.blocked_report(rules)
    unpublished = {block_id for block_id, rule in rules.items() if not rule.verified}
    assert set(report) == unpublished
    assert set(report) == {
        # canSurvive = 同类 ∪ `#minecraft:leaves` ∪ 任何坚固面（OR，CE 单条行为表达不了）
        "neon_equisetum",
        # canSurvive 按 SHAPE 看水平邻居/下方（两套方向，CE 单条行为表达不了）
        "soul_lily",
    }
    assert all(reason for reason in report.values())


# --------------------------------------------------------------------------- 连接断裂层
#
# 上游「邻居没了怎么办」写在 `updateShape` / `destroy`，不在 `canSurvive`。这一组锁住
# `bnlib.support` 的破坏判据层：谁有判据、判据看哪个方向、能不能落成 CE 行为，
# 以及「断开要不要掉落」这份交给 Java 侧的清单。

#: 上游 A 类（`Blocks.AIR`，不掉落）+ B 类（`destroyBlock(..., true)`，真掉落）。
#: 值 = (方向, 判据种类)；方向/种类必须与源码里的判据一致。
BREAKDOWN_TARGETS = {
    # 上方是「自己 ∪ 任何装饰支撑面（坚固面 ∪ 整方块树叶）」
    "anchor_tree_vine": (support.DIR_UP, support.CRITERION_SOLID),
    # 下方是「自己 ∪ 眼球」（本类），上方是 BCLib 藤蔓的 OR（基类）→ 两个方向
    "eye_vine": (support.DIR_BOTH, support.CRITERION_SOLID),
    # BaseVineBlock / BaseSimpleVineBlock：`up.is(this)` ∪ 树叶 ∪ 任何坚固面
    "lumabus_vine": (support.DIR_UP, support.CRITERION_SOLID),
    "golden_lumabus_vine": (support.DIR_UP, support.CRITERION_SOLID),
    "golden_vine": (support.DIR_UP, support.CRITERION_SOLID),
    "black_vine": (support.DIR_UP, support.CRITERION_SOLID),
    "blooming_vine": (support.DIR_UP, support.CRITERION_SOLID),
    "gloomsculk_vine": (support.DIR_UP, support.CRITERION_SOLID),
    # BaseVineBlock.Growing：套用 canSurvive，下方还判「自己」
    "whispering_gourd_vine": (support.DIR_BOTH, support.CRITERION_SOLID),
    # 下方 = 自己 ∪（下界地面 且四周有岩浆），上方看同名段
    "nether_reed_stem": (support.DIR_BOTH, support.CRITERION_SELF_OR_WHITELIST),
    # 贴面支撑 ∪ 下方灵魂沙
    "wart_seed": (support.DIR_MIXED, support.CRITERION_SOLID),
    # 同类链递归 destroyBlock(..., true)
    "netherrack_stalactite": (support.DIR_BOTH, support.CRITERION_RECURSIVE),
}


@pytest.fixture(scope="module")
def breakdown() -> dict[str, support.BreakdownRule]:
    return support.load_breakdown_rules(UPSTREAM)


def test_breakdown_covers_every_connection_block(breakdown):
    """11 个被漏掉的方块逐个有记录，方向与判据种类与上游源码一致。"""
    for name, (direction, criterion) in BREAKDOWN_TARGETS.items():
        assert name in breakdown, f"{name} 没有破坏判据记录"
        rule = breakdown[name]
        assert rule.breakdown_direction == direction, (name, rule.breakdown_direction)
        assert rule.criterion == criterion, (name, rule.criterion)
        assert rule.evidence, f"{name} 缺证据串"


def test_breakdown_evidence_points_back_at_upstream_sources(breakdown):
    """每条记录都必须指回一个文件（不能是「按名字推的」）。"""
    assert "BlockAnchorTreeVine.java" in breakdown["anchor_tree_vine"].evidence
    assert "BlockEyeVine.java" in breakdown["eye_vine"].evidence
    assert "BlockNetherReed.java" in breakdown["nether_reed_stem"].evidence
    assert "BlockWartSeed.java" in breakdown["wart_seed"].evidence
    assert "BlockStalactite.java" in breakdown["netherrack_stalactite"].evidence
    # 藤蔓家族声明在未 vendored 的 BCLib 基类里，必须如实写出来。
    for name in ("black_vine", "golden_vine", "lumabus_vine", "blooming_vine"):
        assert "未 vendored" in breakdown[name].evidence
        assert "Base" in breakdown[name].evidence


def test_vine_family_stays_blocked_with_the_leaves_branch_named(breakdown):
    """「自己 ∪ 树叶 ∪ 任何坚固面」CE 表达不了：树叶那一支两支行为都拿不到。

    这不是保守：下界藤蔓的世界生成会挂在树叶顶盖下（锚树藤就是挂在
    `ANCHOR_TREE_LEAVES` 下），发布一个只认「坚固面 ∪ 同类」的行为等于让它们生成后
    立刻破掉。
    """
    for name in ("anchor_tree_vine", "black_vine", "golden_vine", "blooming_vine",
                 "gloomsculk_vine", "lumabus_vine", "golden_lumabus_vine",
                 "whispering_gourd_vine", "eye_vine"):
        rule = breakdown[name]
        assert rule.blocked, f"{name} 不该被发布"
        assert "树叶" in rule.blocked, (name, rule.blocked)
        assert "hanging_block" in rule.blocked or "sturdy_base_block" in rule.blocked
        assert support.breakdown_behaviors(name, rules=breakdown) == []


def test_anchor_tree_vine_names_the_decoration_support_or(breakdown):
    """`isDecorationSupport` 本身就是「坚固面 ∪ 整方块树叶」，这一支必须点名。"""
    source = (UPSTREAM / "src/main/java/org/betterx/betternether/blocks/BlockAnchorTreeVine.java"
              ).read_text(encoding="utf-8")
    assert "isDecorationSupport" in source
    assert breakdown["anchor_tree_vine"].blocked.count("isDecorationSupport") >= 1


def test_eye_vine_records_the_event_versus_continuous_gap(breakdown):
    """eye_vine 的回收只在「下方发生变化」时生效，CE 每次邻居变化都重算 —— 这条必须写明。

    否则照白名单发布，tip 段（下方本来就是空气）一放下就被回收。
    """
    rule = breakdown["eye_vine"]
    assert "每次邻居变化" in rule.blocked
    assert "direction == Direction" in rule.blocked
    # 「下方是自己或眼球」→ 眼球家族逐个列出来（不从名字猜）。
    assert set(rule.whitelist_blocks) == {"betternether:eyeball",
                                          "betternether:eyeball_small"}
    # 上方那一支来自 BaseVineBlock，方向必须是 both 而不是 down。
    assert rule.breakdown_direction == support.DIR_BOTH


def test_nether_reed_stem_names_the_lava_adjacency(breakdown):
    """芦苇的判据里夹着「支撑方块四周必须有岩浆」，CE 的支撑行为看不到这一点。"""
    rule = breakdown["nether_reed_stem"]
    assert "岩浆" in rule.blocked
    assert "同时看上方与下方" in rule.blocked


def test_wart_seed_whitelist_comes_from_the_material_slot(breakdown):
    """`wart_seed` 由 `WartMaterial` 的槽位注册，白名单只能从那里读（不是猜）。"""
    rule = breakdown["wart_seed"]
    assert rule.whitelist_blocks == ("minecraft:soul_sand",)
    assert any("WartMaterial" in note for note in rule.notes)
    assert rule.breakdown_direction == support.DIR_MIXED
    assert rule.blocked and "贴面" in rule.blocked


def test_stalactite_cascade_is_recursive_and_drops_upstream(breakdown):
    """石笋是真掉落（`destroyBlock(..., true)`），而且 CE 没有递归级联这种行为。"""
    rule = breakdown["netherrack_stalactite"]
    assert rule.recursive is True
    assert rule.drops_source == support.DROPS_UPSTREAM
    assert rule.drops_on_breakdown is True
    assert "递归" in rule.blocked
    # 同类（`instanceof BlockStalactite`）→ 五种石笋都在白名单里
    assert len(rule.whitelist_blocks) == 5
    assert "betternether:bone_stalactite" in rule.whitelist_blocks


def test_stalagnate_trunk_gets_no_self_destruct(breakdown):
    """`strapagnate_trunk` 上游没有 canSurvive/updateShape：挖中段上面照旧悬空，不许加自毁。"""
    source = (UPSTREAM / "src/main/java/org/betterx/betternether/blocks/BlockStalagnate.java"
              ).read_text(encoding="utf-8")
    for marker in ("canSurvive", "updateShape", "destroyBlock", "public void destroy("):
        assert marker not in source, f"上游 BlockStalagnate 出现了 {marker}，结论需要复核"
    assert "stalagnate_trunk" not in breakdown
    assert "betternether:stalagnate_trunk" not in support.breakdown_candidates(UPSTREAM)
    assert support.breakdown_behaviors("stalagnate_trunk", rules=breakdown) == []


def test_candidates_and_rules_agree(breakdown):
    """门禁靠「上游候选」与「我们的记录」两份真值交叉核对，所以两边必须一一对齐。"""
    candidates = support.breakdown_candidates(UPSTREAM)
    assert set(candidates) == {rule.block_id for rule in breakdown.values()}
    assert len(candidates) == len(breakdown)


def test_breakdown_layer_is_deterministic(breakdown):
    assert support.load_breakdown_rules(UPSTREAM) == breakdown


def test_drop_manifest_lists_direction_and_drop_decision(breakdown):
    """掉落清单是给 Java 侧的输入：方块、断哪个方向、掉落还是消失、为什么没落成行为。"""
    manifest = support.breakdown_manifest(UPSTREAM)
    assert manifest["summary"]["total"] == len(breakdown)
    assert manifest["summary"]["drops"] == len(breakdown)
    assert manifest["summary"]["blocked"] == sum(1 for r in breakdown.values() if r.blocked)

    entry = manifest["blocks"]["betternether:black_vine"]
    assert entry["breakdown_direction"] == support.DIR_UP
    assert entry["drops_on_breakdown"] is True
    assert entry["drops_source"] == support.DROPS_REQUIREMENT
    assert entry["blocked"]
    assert entry["ce_behavior"]["published"] is False

    stalactite = manifest["blocks"]["betternether:netherrack_stalactite"]
    assert stalactite["drops_source"] == support.DROPS_UPSTREAM
    assert stalactite["recursive_cascade"] is True

    # 每个条目都要说清「断哪个方向 / 掉落还是消失」，不能留空。
    for block_id, item in manifest["blocks"].items():
        assert item["breakdown_direction"] in {
            support.DIR_UP, support.DIR_DOWN, support.DIR_BOTH,
            support.DIR_FACING, support.DIR_MIXED, support.DIR_UNKNOWN}, block_id
        assert item["drops_source"] in {support.DROPS_UPSTREAM, support.DROPS_REQUIREMENT}
        assert isinstance(item["drops_on_breakdown"], bool)


def test_generate_writes_the_manifest_into_reports(tmp_path):
    """清单必须落成产物里的文件（Java 侧读它），不是只活在 Python 里。"""
    ce_root = tmp_path / "out/craftengine/betternether"
    ce_root.mkdir(parents=True)
    payload = support.generate(UPSTREAM, ce_root)
    assert payload["files"] == 1
    target = tmp_path / "out/reports/plant-breakdown.json"
    assert target.is_file()
    written = json.loads(target.read_text(encoding="utf-8"))
    assert written["blocks"]["betternether:eye_vine"]["drops_on_breakdown"] is True
    assert payload["drops"] == written["summary"]["drops"]
    # 阻塞的方块要以 unsupported 的形式出现在生成报告里（不许静默）。
    assert any("black_vine" in item for item in payload["unsupported"])


def test_material_registration_scan_reaches_slot_registered_blocks():
    """槽位注册（`blocks/complex/*Material.java`）必须能被扫到，id 由上游生成数据给出。"""
    scan = support.material_registration_scan(UPSTREAM)
    assert scan["scanned"] >= 10
    assert scan["ids"]["BlockWartSeed"] == ["betternether:wart_seed"]
    assert scan["classes"]["BlockWartSeed"].endswith("Material.java")
    # wart_seed 已经被破坏判据层覆盖，所以不该再出现在「未索引」里。
    assert "BlockWartSeed" not in scan["unindexed"]
    # stalagnate_trunk 的类同样要被数到（它没有判据，但注册事实必须可见）。
    assert scan["ids"]["BlockStalagnate"] == ["betternether:stalagnate_trunk"]

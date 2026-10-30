#!/usr/bin/env python3
"""装备分类拆分 + 配方恢复 + 丢弃原因结构化的回归测试。

三件事各自对应一个「玩家能感知到坏了」的后果，所以分开锁：

  * **装备分类**（任务 A）：43 件装备原先全堆在 `misc`，分类退化成垃圾桶就等于没有分类。
    判据不是「有个 category 键」，而是「工具/武器/护甲各落到自己的叶子分类，
    且非装备（锻造模板 / 船 / 药水）不被吸进去」。
  * **配方恢复**（任务 B）：上位 tag 曾被当成「不可表达」整族丢弃 —— 9 种木板（以及依赖
    木板的 40+ 条配方）因此断链。CE 的 ingredient / addition 直接接受 `#namespace:tag`，
    所以这里锁的是「tag 被写出去」而不是「tag 被展开」。
  * **丢弃原因**（任务 C）：只写一句「引用了未定义的 id」，484 条就答不出「哪些等
    blocks.py 扩面、哪些要用户裁决」。锁的是结构化字段能被统计、blocked 能被筛出来。

跑法：`python -m pytest scripts/tests -q`
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import items  # noqa: E402

#: 与 `tools/verify_block_contracts.BLOCK_ID_PATTERN` 同口径：本包 id 的完整匹配。
_BN_ID = re.compile(r"betternether:[a-z0-9_]+")

ROOT = SCRIPTS.parent
UPSTREAM = ROOT / "upstream/BetterNether"
GENERATED = ROOT / "plugin/build/generated/bn/craftengine/betternether"


def _require_upstream() -> Path:
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    return UPSTREAM


def _require_generated() -> Path:
    if not (GENERATED / "configuration/blocks").is_dir():
        pytest.skip("生成目录不存在，先跑一次生成器")
    return GENERATED


def _plans() -> dict[str, str]:
    upstream = _require_upstream()
    ce = _require_generated()
    return {plan.item_id: plan.category for plan in items.plan_items(upstream, ce)[0]}


def _planned_recipes():
    """按 `items_yaml` 的口径跑一遍配方：把本次会写出的物品也算作已定义。"""
    upstream = _require_upstream()
    ce = _require_generated()
    plans, _ = items.plan_items(upstream, ce)
    extra = tuple(f"{items.NAMESPACE}:{plan.item_id}" for plan in plans)
    blocks, report = items.plan_recipes(upstream, ce, extra)
    return blocks, report


# ---------------------------------------------------------------- 装备分类（任务 A）


def test_gear_family_covers_every_kind():
    """`GEAR_FAMILY` 必须覆盖全部工具/护甲种类，且只产出已声明的三个分类。

    漏一个键的后果不是报错，而是 `_category()` 里查表失败 —— 于是有人图省事给 `None`
    兜底，那件装备就静默回到 misc。集合关系在这里卡死。
    """
    kinds = set(items.TOOL_KINDS) | set(items.ARMOR_KINDS)
    unclassified = kinds - set(items.GEAR_FAMILY)
    assert not unclassified, f"未归类的装备种类：{sorted(unclassified)}"
    declared = {category.name for category in items.EXTRA_CATEGORIES}
    assert set(items.GEAR_FAMILY.values()) == declared


def test_equipment_lands_in_misc():
    """与 betterend 一致：没有 tools/weapons/armor 分页，所有装备归 misc；矿物材料归 stones。"""
    plans = _plans()
    assert plans, "一个物品都没规划出来"

    for item_id in ("nether_ruby_pickaxe", "cincinnasite_axe", "nether_ruby_sword",
                    "flaming_ruby_spear", "nether_ruby_chestplate", "cincinnasite_helmet",
                    "flaming_ruby_upgrade_smithing_template",
                    "agave_medicine"):
        assert plans.get(item_id) == f"{items.NAMESPACE}:misc", (
            f"{item_id} 应归 misc，实际 {plans.get(item_id)}")
    assert plans.get("glowstone_pile") == f"{items.NAMESPACE}:stones"


def test_diamond_suffix_still_resolves_as_gear():
    """`_carrier()` 原有的 `_diamond` 后缀特判不能破坏：
    `cincinnasite_pickaxe_diamond` 仍是钻石档镐；分类按新规归 misc。"""
    assert items._carrier("cincinnasite_pickaxe_diamond") == (
        "diamond_pickaxe", "minecraft:pickaxes")
    assert items._kind_of("cincinnasite_pickaxe_diamond") == "pickaxe"
    assert items._category("cincinnasite_pickaxe_diamond") == f"{items.NAMESPACE}:misc"
    # 反例：三段式模板 id 里含 cincinnasite 片段，但它是物品、不是工具。
    assert items._kind_of("cincinnasite_diamond_upgrade_smithing_template") is None


def test_extra_category_interface_is_usable_by_blocks():
    """blocks.py 复用这套常量来写 categories/lang，接口形状必须稳定：
    `id` / `lang_key` / `label` 拼出来的文本要和 blocks.py 现有写法逐字对齐。"""
    for category in items.EXTRA_CATEGORIES:
        assert category.id == f"{items.NAMESPACE}:{category.name}"
        assert category.lang_key == f"category.{items.NAMESPACE}.{category.name}"
        assert len(category.label) == 2 and all(category.label), f"{category.name}: 缺中/英文名"
        assert category.zh.startswith("更好的地狱："), f"{category.name}: 中文名没跟既有风格一致"
        assert ":" not in category.icon_hint, "icon 建议必须是裸 id（blocks.py 自己补命名空间）"

    icons = {category.icon_hint for category in items.EXTRA_CATEGORIES}
    assert len(icons) == len(items.EXTRA_CATEGORIES), "分类图标不能重复"


def test_extra_category_icons_are_generated():
    """分类图标必须真的存在：指向一个本包没生成的 id 就是空图标格子。"""
    upstream = _require_upstream()
    ce = _require_generated()
    known = items.defined_ids(ce)
    planned = {f"{items.NAMESPACE}:{plan.item_id}"
               for plan in items.plan_items(upstream, ce)[0]}
    for category in items.EXTRA_CATEGORIES:
        full = f"{items.NAMESPACE}:{category.icon_hint}"
        assert full in known or full in planned, f"{category.name}: 图标 {full} 不存在"


# ---------------------------------------------------------------- 配方恢复（任务 B）


def test_tag_ingredients_are_not_dropped():
    """多成员 tag 不再被丢弃：CE 的 ingredient 直接接受 `#namespace:tag`。

    意图是「别再用『tag 成员不唯一』当丢弃理由」—— 那会让 9 种木板整族断链。
    """
    upstream = _require_upstream()
    blocks, report = _planned_recipes()
    tags = items._load_tags(upstream)

    multi = {ref for ref, members in tags.items()
             if len(members) > 1 and ref.startswith("#betternether:")}
    assert multi, "上游没有多成员 tag，测试失去意义"

    text = "\n".join(blocks)
    emitted_tags = {line.split('"')[1] for line in text.splitlines() if '"#' in line}
    for ref in ("#betternether:stalagnate_logs", "#betternether:wart_logs"):
        assert ref in emitted_tags, f"{ref} 没有被写出（多成员 tag 又被丢了）"

    for name, reason in report.dropped.items():
        assert reason.kind != items.DROP_TAG_MULTI_MEMBER, f"{name}: tag 多成员不该再导致丢弃"
        assert not all(missing.startswith("#") and len(tags.get(missing, [])) > 1
                       for missing in reason.missing_ids), (
            f"{name}: 仍因多成员 tag 被丢：{reason.missing_ids}")


def _recipe_body(text: str, name: str) -> str:
    """只要配方本体（去掉上方注释行）：断言「值里有没有某物」时不能把注释算进去。"""
    return "\n".join(line for line in _recipe_block(text, name).splitlines()
                     if not line.strip().startswith("#"))


def _recipe_block(text: str, name: str) -> str:
    """从整份产物里切出单条配方，**连同它上面紧邻的注释行**（出处 / 替换说明）。"""
    marker = f"  betternether:{name}:"
    assert marker in text, f"产物里没有 {name}"
    index = text.index(marker)
    comments: list[str] = []
    for line in reversed(text[:index].splitlines()):
        if not line.strip().startswith("#"):
            break
        comments.insert(0, line)
    tail = text[index:].split("\n  betternether:", 1)[0]
    return "\n".join([*comments, tail])


def test_tag_references_survive_yaml_parsing():
    """tag 引用必须能被 YAML 真正读成值，而不是被当成注释吃掉。

    YAML 里行内 `#` 之后是注释，`S: #c:chests` 会被读成空值 —— CE 随后报「缺少 ingredient」，
    而报错信息完全指不回真正的原因。这条测试直接解析产物，把那个坑堵死。
    """
    yaml = pytest.importorskip("yaml")
    blocks, _ = _planned_recipes()
    payload = yaml.safe_load("recipes:\n" + "".join(blocks))
    recipes = payload["recipes"]

    tags = [value for body in recipes.values()
            for value in _iter_ingredient_values(body) if isinstance(value, str)
            and value.startswith("#")]
    assert tags, "产物里一个 tag 引用都没解析出来（引号丢了？）"
    assert "#betternether:stalagnate_logs" in tags
    assert "#betternether:flaming_ruby/repair" in tags


def _iter_ingredient_values(body: dict):
    """配方的全部原料值（有序/无序/锻造三种写法）。"""
    for key in ("ingredients", "ingredient", "template_type", "base", "addition"):
        value = body.get(key)
        if isinstance(value, dict):
            yield from value.values()
        elif isinstance(value, list):
            yield from value
        elif value is not None:
            yield value



def test_plank_recipes_are_recovered():
    """9 种木材的木板配方必须在（上游这 9 条 shapeless 全部引用 `#betternether:<wood>_logs`）。

    它们是箱子、桶、书架、工作台等 40+ 条下游配方的根，缺一条就断一族。
    """
    blocks, report = _planned_recipes()
    names = {
        "stalagnate_planks", "anchor_tree_planks", "gloomwood_planks",
        "gloomwood_dark_planks", "mushroom_fir_planks", "nether_sakura_planks",
        "rubeus_planks", "wart_planks", "willow_planks",
    }
    missing = names - set(report.emitted_ids)
    assert not missing, f"这些木板配方仍未生成：{sorted(missing)}"

    text = "".join(blocks)
    for name in sorted(names):
        block = _recipe_block(text, name)
        assert '"#betternether:' in block and "_logs" in block, (
            f"{name}: 原料应写成 tag 引用，实际 {block[:120]}")


def test_flaming_ruby_smithing_recovered():
    """flaming_ruby 的 10 条锻造升级必须恢复，addition 直接输出
    `#betternether:flaming_ruby/repair`（CE 的 addition 支持 tag 写法）。"""
    blocks, report = _planned_recipes()
    recovered = [name for name in report.emitted_ids if name.startswith("flaming_ruby_")]
    assert len(recovered) == 10, f"flaming_ruby 锻造只恢复了 {len(recovered)} 条：{recovered}"

    text = "".join(blocks)
    for name in recovered:
        block = _recipe_block(text, name)
        assert "type: smithing_transform" in block, f"{name}: 类型不对"
        assert 'addition: "#betternether:flaming_ruby/repair"' in block, (
            f"{name}: addition 应是 tag 引用，实际 {block[:160]}")


def test_chest_boats_are_gone_with_the_boats():
    """箱子船随「船全删」一起移除：物品与配方都不该出现在产物里。

    历史：用户 2026-10-30 要求「船全删」，`*_boat` / `*_raft` 整族不再发布
    （`anchor_tree_chest_boat` 也以 `_boat` 结尾，被同一条规则覆盖）。
    所以上游那 11 条 `*_chest_boat` 配方**不应**再生成 —— 配方产物是已删物品，
    `items.py` 会把它们丢进 `removed`。

    这条测试守的是「别又漏回去」：如果哪天有人只恢复了物品模型而没恢复配方语义，
    或者反过来把配方放出来但物品不存在，这里会立刻红。
    """
    upstream = _require_upstream()
    _blocks, report = _planned_recipes()
    upstream_boats = sorted(path.stem
                            for path in (upstream / items.RECIPE_DIR).glob("*_chest_boat.json"))
    assert upstream_boats, "上游没有箱子船配方，这条测试的前提已失效"

    leaked = set(upstream_boats) & set(report.emitted_ids)
    assert not leaked, f"箱子船配方不该生成（船只整族已删）：{sorted(leaked)}"


def test_step_material_recipes_are_recovered_with_full_blocks():
    """台阶原料配方必须**能合成**：把本包不发布的台阶换成同材质整块。

    用户裁决：原本能合成的都要能合成。上游用台阶（1 台阶），本包台阶不发布
    （`publication.SHAPE_SUFFIXES`，异形块吃方块状态预算），所以改用同材质完整方块。
    这**不是等价替换** —— 材料成本上升了，因此必须逐条留痕（配方注释 + 报告
    `substitutions`），不允许悄悄改成本。

    2026-10-31 收缩到 3 条：原先还包含 11 种木材的 `barrel` 与 `chiseled_bookshelf`，
    但这两族已按用户要求整族移除（`BarrelBlock` 在 `REMOVED_CLASSES`、
    雕纹书架命中文 `BOOK_SUFFIXES`），配方产物不再存在，这条断言自然不该再期待它们。
    """
    blocks, report = _planned_recipes()
    expected = (
        # 三个非木族的台阶配方
        ("bone_tile", "cincinnasite_tile_large", "farmland"),
    )
    names = set(expected[0])
    assert len(names) == 3, f"期望 3 条，实际 {len(names)} 条"

    missing = names - set(report.emitted_ids)
    assert not missing, f"这些台阶原料配方仍未生成：{sorted(missing)}"
    assert items.DROP_STEP_MATERIAL not in report.dropped_by_kind(), (
        "还有配方因台阶原料被丢，恢复没做干净")

    text = "".join(blocks)
    for name in sorted(names):
        applied = report.substitutions.get(name)
        assert applied, f"{name}: 恢复了但没有替换留痕"
        assert len(applied) == 1, f"{name}: 期望 1 条替换，实际 {applied}"
        entry = applied[0]
        assert entry["from"].endswith("_slab"), f"{name}: 被替换的应是台阶，实际 {entry['from']}"
        assert entry["to"].startswith(f"{items.NAMESPACE}:"), f"{name}: 替代物应是本包整块"
        assert not entry["to"].endswith("_slab"), f"{name}: 替代物不能还是台阶"
        assert "成本" in entry["reason"], f"{name}: 原因里必须写明成本变化，实际 {entry['reason']}"
        # 配方注释里也要能看到替换，读配置的人不必去翻报告 —— 但**只能写描述，不能写裸 id**
        # （裸 id 会被 verify_block_contracts 的 removal.reference 当成真实引用）。
        block = _recipe_block(text, name)
        comments = "\n".join(line for line in block.splitlines()
                             if line.strip().startswith("#"))
        assert "原料替换 1 处：" in comments, f"{name}: 配方注释里没有替换说明"
        assert "改用同材质整块" in comments, f"{name}: 注释没说明替换成什么"
        assert entry["from"] not in comments and entry["to"] not in comments, (
            f"{name}: 注释里不许出现裸 id（{entry['from']} / {entry['to']}）")

    # 写出去的配方里不应再有本包台阶当原料（原版台阶不受影响）—— 见下一条测试。
    for recipe_id, applied in report.substitutions.items():
        block = _recipe_block(text, recipe_id)
        for entry in applied:
            assert entry["to"] in block, f"{recipe_id}: 替代物没出现在配方里"


def test_no_unpublished_slab_left_as_ingredient():
    """产物/原料里不允许出现「本包不发布的台阶」当原料：那是 CE 里不存在的 id。

    原版台阶（`minecraft:*_slab`）不受影响 —— 它们本来就存在。
    """
    blocks, _ = _planned_recipes()
    text = "".join(blocks)
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped.startswith(("'", "- ", "ingredient:", "addition:", "base:")):
            continue
        for token in ("betternether:",):
            if token in stripped and "_slab" in stripped:
                assert False, f"配方原料里仍有本包台阶：{stripped}"



# ---------------------------------------------------------------- 丢弃原因（任务 C）


def test_drop_reasons_are_structured():
    """丢弃原因必须能分类统计：只写一句话，484 条就答不出「哪些等扩面、哪些要裁决」。"""
    _, report = _planned_recipes()
    assert report.dropped, "当前应有丢弃项，否则这条测试没有意义"

    counts = report.dropped_by_kind()
    assert set(counts) <= set(items.DROP_KINDS)
    assert len(counts) >= 3, f"原因种类太少，分类没起作用：{counts}"
    assert items.DROP_PRODUCT_REMOVED in counts, "已移除产物这一类必须存在"
    assert items.DROP_PRODUCT_SEAT in counts, "bclib 座椅这一类必须存在"
    # 台阶原料已按用户裁决恢复（换成同材质整块），这一类必须归零 ——
    # 它若重新出现，说明有人又让台阶悄悄导致丢弃了。
    assert items.DROP_STEP_MATERIAL not in counts, (
        f"台阶原料又有丢弃项：{counts}；应按 substitutions 记录后恢复")

    for name, reason in report.dropped.items():
        assert reason.kind in items.DROP_KINDS, f"{name}: 未知 kind {reason.kind}"
        assert reason.product_status in {"published", "removed", "seat", "unplaced", "unknown"}
        assert reason.detail.strip(), f"{name}: 缺可读说明"
        if reason.kind != items.DROP_TAG_MULTI_MEMBER:
            assert reason.missing_ids, f"{name}: 缺 missing_ids，无法追溯"


def test_tag_membership_gaps_are_reported():
    """写出的标签必须「要么有人声明成员、要么出现在缺口清单里」——不许静默失效。

    CE 侧某个 tag 有没有成员，取决于物品自己的 `settings.tags`
    （`ItemManager.customItemTags` 由 `ItemSettings.tags()` 填充）。没人声明的 tag
    在 CE 眼里就是空标签，`parseIngredient` 会抛 `resource.recipe.ingredient.invalid_tag`
    并跳过这条配方 —— 不炸包，但配方不加载。生成期把缺口列出来，才不会等玩家发现。
    """
    ce = _require_generated()
    declared = items.declared_tags(ce)
    _, report = _planned_recipes()
    gaps = report.tags_without_members(declared)

    for tag in report.tags_used:
        if tag.startswith("#minecraft:"):
            assert tag not in gaps, "原版标签由游戏提供，不该被列成缺口"
            continue
        assert tag in gaps or tag[1:] in declared, (
            f"{tag}: 既没有物品声明成员，也没进缺口清单（会静默失效）")

    # 缺口必须指名道姓地给出「哪个标签、卡住哪些配方」，否则无法据此补齐。
    for tag, recipes in gaps.items():
        assert tag.startswith("#betternether:"), f"{tag}: 缺口标签不该来自其它命名空间"
        assert recipes, f"{tag}: 缺口没有列出受影响的配方"
        assert all(name in report.emitted_ids for name in recipes)



def test_tag_membership_gap_clears_when_blocks_declare_the_tag(tmp_path):
    """blocks 侧补上 `settings.tags` 后，这条缺口必须**自动变绿**，检查本身不许删。

    这个检查的价值全在「它是数据驱动的」：`declared_tags()` 只读 ce_root 里真实的
    `settings.tags`，所以方块 owner 一补声明，`tags_without_members` 里对应项就消失，
    配方从「CE 记 invalid_tag 后跳过」变成「真正可用」。用一份假 ce_root 证明这一点。
    """
    ce = tmp_path / "ce"
    (ce / "configuration/blocks").mkdir(parents=True)
    (ce / "configuration/blocks/N1-wood-families.yml").write_text(
        "items:\n"
        "  betternether:stalagnate_log:\n"
        "    settings:\n"
        "      tags:\n"
        "        - betternether:stalagnate_logs\n",
        encoding="utf-8")

    assert "betternether:stalagnate_logs" in items.declared_tags(ce)
    _, report = _planned_recipes()
    gaps = report.tags_without_members(items.declared_tags(ce))
    assert "#betternether:stalagnate_logs" not in gaps, (
        "方块声明了成员，缺口却没消失 —— 检查没跟着数据走")
    # 其它没人声明的标签仍在缺口里（这只是清掉一个，不是关掉检查）。
    assert "#betternether:flaming_ruby/repair" in gaps


def test_generated_items_yaml_contains_no_removed_block_id():
    """产物全文（**含注释**）里不得出现任何已移除方块的 id。

    `verify_block_contracts.py` 的 `removal.reference` 是按文本扫描的：注释里写一句
    「台阶 -> 整块」的替换说明，只要带了裸 id（`betternether:<x>_slab`），就等于在产物里
    留了一个已移除方块的引用。门禁抓得对的，所以这里提前在单元测试层卡住，
    别等跑门禁才发现。替换的可机读明细在 `reports/item-recipes.json` 的 `substitutions`。

    扫描口径与门禁一致：用 `betternether:[a-z0-9_]+` 匹配**完整** id
    （子串匹配会把 `..._chest_boat` 误判成已移除的 `..._chest`）。
    """
    from bnlib.publication import is_removed_block

    ce = _require_generated()
    text = (ce / "configuration/items" / items.OUTPUT_NAME).read_text(encoding="utf-8")
    assert text.strip(), "产物是空的"

    offenders = sorted({match.group(0) for match in _BN_ID.finditer(text)
                        if is_removed_block(match.group(0))})
    assert not offenders, f"产物里出现已移除方块的 id：{offenders}"


# ---------------------------------------------------------------- 物品可获取性


def test_agave_leaf_has_a_source():
    """`agave_leaf` 必须拿得到：上游 agave 的掉落表在 `age=3` 时掉 2~5 个叶子。

    blocks.py 给每个方块写死 `loot: {template: default:loot_table/self}`（只掉自己），
    所以这一步由 items.py 在 blocks 之后**按上游 loot JSON 补写**。这条测试锁两件事：
    物品确实有来源（报告里不是「未补写」），且方块 loot 里真的有那条按状态判定的叶子。
    """
    yaml = pytest.importorskip("yaml")
    upstream = _require_upstream()
    ce = _require_generated()

    _, report = _planned_recipes()
    loot_only = items.loot_only_items(upstream, (p.split(":", 1)[1] for p in report.produced),
                                      report.produced)
    # 判据本身：agave_leaf 无配方产物、但上游掉落表里有它。
    assert "betternether:agave_leaf" in items._upstream_sources(upstream)[1]

    summary = items.generate(upstream, ce)
    status = summary["extra_drops"].get("betternether:agave_leaf", "")
    assert status.startswith("已"), f"agave_leaf 的掉落没补上：{status!r}"
    assert "betternether:agave_leaf" not in {row["id"] for row in summary["unobtainable_items"]}

    block_files = list((ce / "configuration/blocks").glob("*.yml"))
    assert block_files, "生成目录里没有方块配置"
    agave = None
    for path in block_files:
        data = yaml.safe_load(path.read_text(encoding="utf-8"))
        if "betternether:agave" in (data.get("blocks") or {}):
            agave = data["blocks"]["betternether:agave"]
            break
    assert agave is not None, "生成产物里没有 betternether:agave"

    pools = agave["loot"]["pools"]
    leaf = [pool for pool in pools
            if any(entry.get("item") == "betternether:agave_leaf"
                   for entry in pool.get("entries") or [])]
    assert leaf, f"agave 的 loot 里没有龙舌兰叶：{agave['loot']}"
    pool = leaf[0]
    assert any(condition.get("type") == "match_block_property" for condition in pool["conditions"]), (
        "龙舌兰叶必须按 age=3 才掉，否则等于改了上游规则")
    count = pool["entries"][0]["functions"][0]["count"]
    assert str(count) == "2~5", f"数量应与上游一致（2~5），实际 {count}"
    # 掉落段不能出现嵌套 `loot:`（那是坏结构，CE 读不到内层）。
    assert "loot" not in agave["loot"], f"loot 段里又嵌了一层 loot：{agave['loot']}"


def test_unobtainable_items_are_reported():
    """既无配方产物、也无掉落来源的物品必须列进 `unobtainable_items`，不许隐形。

    6 件 hammer/excavator 上游同样没有配方（`recipe/` 里搜不到对应文件），属于上游
    只靠创造模式给的工具；本包照原样不配方化，但缺口要写在报告里。
    """
    upstream = _require_upstream()
    ce = _require_generated()
    plans, _ = items.plan_items(upstream, ce)
    _, report = _planned_recipes()
    loot_only = items.loot_only_items(upstream, (p.item_id for p in plans), report.produced)
    summary = items.generate(upstream, ce)
    rows = summary["unobtainable_items"]
    ids = {row["id"] for row in rows}

    expected = {
        "betternether:cincinnasite_hammer",
        "betternether:cincinnasite_excavator",
        "betternether:cincinnasite_hammer_diamond",
        "betternether:cincinnasite_excavator_diamond",
        "betternether:nether_ruby_hammer",
        "betternether:nether_ruby_excavator",
    }
    assert expected <= ids, f"这些工具没进不可获得清单：{sorted(expected - ids)}"
    # 上游依据必须逐条写清楚，而不是只给一个 id。
    for row in rows:
        if row["id"] in expected:
            assert row["upstream_recipe_product"] is False
            assert row["upstream_loot_table"] is False
            assert "创造模式" in row["note"], f"{row['id']}: 说明里没写出上游依据"
            # 反证：上游确实没有以它为准的配方文件。
            name = row["id"].split(":", 1)[1]
            assert not (upstream / items.RECIPE_DIR / f"{name}.json").is_file()

    # 有来源的物品不许出现在这份清单里（否则清单会失去意义）。
    for item_id in ("betternether:agave_leaf", "betternether:nether_ruby_pickaxe",
                    "betternether:stalagnate_planks"):
        assert item_id not in ids, f"{item_id} 有来源，不该进不可获得清单"
    assert loot_only is not None


def test_every_substitution_is_marked_non_equivalent():
    """每条受控替换都要标 `equivalent: false`，且 reason 写明差异在哪。

    审查口径：替换一共 40 条（27 改成本 / 11 改语义 / 2 处同族收敛），没有一条是等价的 ——
    报告里必须能一眼看出这点，不能靠读者从 from/to 自己推断。
    """
    _, report = _planned_recipes()
    assert report.substitutions, "当前应有替换条目"
    for recipe_id, applied in report.substitutions.items():
        for entry in applied:
            assert entry["equivalent"] is False, f"{recipe_id}: 没标 equivalent=false"
            assert entry["from"] != entry["to"]
            assert entry["reason"].strip(), f"{recipe_id}: 缺原因"
            assert ("成本" in entry["reason"] or "近似" in entry["reason"]
                    or "未发布" in entry["reason"]), (
                f"{recipe_id}: reason 没写出差异：{entry['reason']}")


def test_recipe_report_lands_in_reports_dir(tmp_path):
    """结构化统计要落盘到包外的 reports/（沿用 block-manifest.json 的目录约定）。"""
    upstream = _require_upstream()
    ce = tmp_path / "ce"
    (ce / "configuration").mkdir(parents=True)
    summary = items.generate(upstream, ce)
    report_path = Path(summary["recipe_report"])
    assert report_path.is_file(), f"没写出配方报告：{report_path}"
    assert report_path.parent.name == "reports"

    payload = json.loads(report_path.read_text(encoding="utf-8"))
    assert payload["emitted"] == summary["recipes"]
    assert payload["dropped_total"] == summary["recipes_dropped"]
    assert payload["dropped_by_reason"] == summary["recipes_dropped_by_reason"]
    assert payload["dropped"], "报告里没有逐条明细"

    # 每条都要有四个字段，且 `blocked` 与 `reason_kind == step_material` 严格同义：
    # 这两处一旦漂移，「等用户裁决」的筛选就会漏项。
    for name, reason in payload["dropped"].items():
        assert set(reason) == {"reason_kind", "detail", "missing_ids",
                               "product_status", "blocked"}, f"{name}: 字段不全"
        assert reason["blocked"] == (reason["reason_kind"] == items.DROP_STEP_MATERIAL), (
            f"{name}: blocked 与 reason_kind 不一致")

    # 替换清单必须逐条可查：原原料 -> 替代原料 -> 原因。
    assert "substitutions" in payload, "报告里没有 substitutions 段"
    assert summary["recipe_substitutions"] == payload["substitutions"]

    # 这个临时 ce_root 里没有任何方块定义，所以「台阶 -> 整块」不会发生；
    # 出现的只应是**与发布状态无关**的显式 tag 替换（`#c:chests`）。
    # 反向确认替换分两类：一类跟着 ce_root 的发布状态走，一类是写死的近似。
    for name, applied in payload["substitutions"].items():
        for entry in applied:
            assert entry["from"] in items.TAG_SUBSTITUTIONS, (
                f"{name}: 空 ce_root 下不该出现 {entry['from']} 的替换")




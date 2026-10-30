#!/usr/bin/env python3
"""items / ore_drops 数据模块的独立测试。

这些测试编码的是**意图**，不只是行为：

  * 配方只允许引用 CE 真实存在的 id —— 引用不闭合的配方会让整个 CE 包加载失败，
    所以这条不是"风格"，是"能不能启动"；
  * loot 表引用的 item 必须闭合 —— 少一个就是挖矿掉出空物；
  * 重复运行必须字节一致 —— 生成目录禁止手改，产物必须能从输入重建；
  * 上游没有的配方必须带 `added_by_request` 标记 —— 不允许把自造内容冒充上游。

跑法：`python -m pytest scripts/tests -q`
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import items, ore_drops  # noqa: E402

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


# ---------------------------------------------------------------- schema 契约


def test_recipe_types_are_ce_serializers():
    """只允许 CE 官方文档里的 recipe type 名（docs/recipes.md：`shaped` / `shapeless` 不带
    `crafting_` 前缀）。带前缀的写法曾让 CE 对 548 条有序配方报「缺少 ingredient」。"""
    allowed = {
        "shaped", "shapeless", "stonecutting", "smithing_transform",
        "smithing_trim", "smelting", "blasting", "smoking", "campfire_cooking", "brewing",
    }
    assert set(items.RECIPE_TYPES.values()) <= allowed


def test_generated_recipes_use_ce_keys():
    """生成的 bn-items.yml 必须用 CE 的键：有序配方写 `ingredients`（不是上游 JSON 的 `key`），
    锻造升级写 `template_type`（不是 `template`，否则 CE 报「无效的模板」）。"""
    import yaml

    ce = _require_generated()
    data = yaml.safe_load((ce / "configuration/items/bn-items.yml").read_text(encoding="utf-8"))
    recipes = data.get("recipes") or {}
    assert recipes, "生成文件里一条配方都没有"
    for rid, body in recipes.items():
        kind = body["type"]
        assert not kind.startswith("crafting_"), f"{rid}: type 不能带 crafting_ 前缀"
        assert "key" not in body, f"{rid}: 有序配方原料必须写在 ingredients 下"
        assert "template" not in body, f"{rid}: 锻造模板字段必须是 template_type"
        if kind == "shaped":
            symbols = set("".join(body["pattern"])) - {" "}
            assert symbols <= set(body["ingredients"]), f"{rid}: pattern 里有未定义的符号"
        if kind == "smithing_transform":
            assert body.get("template_type"), f"{rid}: 缺 template_type"
            assert body.get("base"), f"{rid}: 缺 base"


def test_carriers_are_vanilla_items():
    """material 必须是原版物品名（不带命名空间），否则 CE 会当成自定义 id 找不到。"""
    upstream = _require_upstream()
    ce = _require_generated()
    plans, _ = items.plan_items(upstream, ce)
    assert plans, "一个物品都没规划出来，说明发现逻辑坏了"
    for plan in plans:
        assert ":" not in plan.carrier, f"{plan.item_id}: carrier 不能带命名空间"
        assert plan.carrier.islower(), f"{plan.item_id}: carrier 必须是小写原版 id"


# ---------------------------------------------------------------- 引用闭合


def test_recipe_references_closed():
    """写出的每条配方，其全部引用都必须是原版 id 或本包已定义 id。

    不用正则去猜 YAML 文本（配方的 id 行本身也长得像引用），而是回到上游 JSON
    重新取引用再逐条解析 —— 这才是「闭合」的真实判据。
    """
    upstream = _require_upstream()
    ce = _require_generated()
    known = items.defined_ids(ce)
    tags = items._load_tags(upstream)

    blocks, report = items.plan_recipes(upstream, ce)
    assert report.emitted == len(blocks)
    assert blocks, "一条配方都没生成，闭合测试就没有意义"

    recipe_dir = upstream / items.RECIPE_DIR
    for name in report.emitted_ids:
        data = items._read_json(recipe_dir / f"{name}.json")
        kind = items.RECIPE_TYPES[data["type"]]
        for ref in items._references(kind, data):
            assert items._resolve(ref, known, tags) is not None, (
                f"{name}: 引用了未闭合的 {ref}")

    # 产物里不允许出现解析失败留下的 None。
    for block in blocks:
        assert ": None" not in block, f"配方里有未解析的值：{block[:80]}"


def test_dropped_recipes_have_reasons():
    """丢弃的配方必须带可读原因，不能静默消失。"""
    upstream = _require_upstream()
    ce = _require_generated()
    _, report = items.plan_recipes(upstream, ce)
    for name, reason in report.dropped.items():
        assert reason.detail.strip(), f"{name}: 丢弃原因为空"
        assert reason.kind in items.DROP_KINDS, f"{name}: 未知的 reason_kind {reason.kind}"


def test_ore_loot_items_closed():
    """矿物掉落表引用的 item 必须都由本包定义（ore_drops 负责那 4 个产物）。

    矿物产物现在由 items.py 写进**同一个** `bn-items.yml`（`defined_ids` 会跳过自己的产物
    文件以免「第二次运行全都算已定义」），所以这 4 个 id 必须由调用方显式当已知 id 传进来
    —— 与 `items.generate()` 的做法一致。
    """
    upstream = _require_upstream()
    ce = _require_generated()
    known = items.defined_ids(ce, ore_drops.item_ids())
    refs = items.loot_item_references(upstream)
    assert refs, "没读到任何掉落表"

    for ore in ore_drops.ORE_BLOCKS:
        assert ore in refs, f"{ore}: 上游没有对应的掉落表"
        for ref in refs[ore]:
            if ref.startswith(f"{items.NAMESPACE}:"):
                assert ref in known, f"{ore}: 掉落引用了未定义的 {ref}"


def test_loot_gaps_are_reported():
    """loot 引用了但本包未定义的 id 必须被列出来，而不是悄悄留空。"""
    upstream = _require_upstream()
    ce = _require_generated()
    gaps = items.loot_gaps(upstream, ce)
    known = items.defined_ids(ce)
    for gap in gaps:
        assert gap not in known
        assert gap.startswith(f"{items.NAMESPACE}:")


# ---------------------------------------------------------------- added_by_request


def test_upstream_recipes_are_marked_with_source():
    """上游配方块必须带 `recipe/<file>.json` 出处注释，便于逐条回溯。"""
    upstream = _require_upstream()
    ce = _require_generated()
    blocks, _ = items.plan_recipes(upstream, ce)
    for block in blocks:
        first = block.splitlines()[0]
        assert first.strip().startswith("# "), f"配方缺少出处注释：{block[:60]}"
        assert ("recipe/" in first) or ("added_by_request" in first)


def test_added_by_request_is_labelled(monkeypatch):
    """上游没有的配方必须显式标 added_by_request，不能伪装成上游数据。"""
    upstream = _require_upstream()
    ce = _require_generated()
    spec = {
        "id": "test_added_recipe",
        "type": "smelting",
        "ingredient": "minecraft:cobblestone",
        "result": {"id": "minecraft:stone", "count": 1},
        "cookingtime": 200,
        "experience": 0.1,
    }
    monkeypatch.setattr(items, "ADDED_BY_REQUEST", (spec,))
    blocks, report = items.plan_recipes(upstream, ce)
    assert report.added_by_request == 1
    marked = [b for b in blocks if "added_by_request" in b]
    assert len(marked) == 1, "added_by_request 配方没有被标记"
    assert "test_added_recipe" in marked[0]


# ---------------------------------------------------------------- 产物 schema


def test_output_yaml_parses_and_uses_verified_keys(tmp_path):
    """产物必须是合法 YAML，且只用 CE jar 里核实过的键。

    键集合来自 `javap` 反查各 Serializer 的常量池：
      * item    —— material / settings / data / texture / textures / model / behavior / category
      * recipe  —— type / group / pattern / key / ingredients / ingredient /
                   template / base / addition / result / category / time / experience
      * result  —— id / count
    多写一个 CE 不认识的键不会报错，只会静默失效 —— 所以这里必须卡死。
    """
    yaml = pytest.importorskip("yaml")
    upstream = _require_upstream()
    ce = tmp_path / "ce"
    (ce / "configuration").mkdir(parents=True)
    items.generate(upstream, ce)

    payload = yaml.safe_load((ce / "configuration/items" / items.OUTPUT_NAME).read_text(encoding="utf-8"))
    assert isinstance(payload, dict)
    assert payload.get("items"), "产物里没有 items 段"

    item_keys = {"material", "category", "settings", "data", "texture",
                 "textures", "model", "behavior"}
    recipe_keys = {"type", "group", "pattern", "key", "ingredients", "ingredient",
                   "template", "base", "addition", "result", "category", "time",
                   "experience", "merge_components", "merge_enchantments",
                   "template_type"}
    result_keys = {"id", "count"}

    for item_id, body in payload["items"].items():
        extra = set(body) - item_keys
        assert not extra, f"{item_id}: 用了未核实的 item 键 {extra}"
        assert body.get("data", {}).get("item_name"), f"{item_id}: 缺 item_name"

    for recipe_id, body in (payload.get("recipes") or {}).items():
        extra = set(body) - recipe_keys
        assert not extra, f"{recipe_id}: 用了未核实的 recipe 键 {extra}"
        assert body.get("type") in items.RECIPE_TYPES.values()
        assert set(body["result"]) <= result_keys


# ---------------------------------------------------------------- 幂等


def test_generate_is_idempotent(tmp_path):
    """同一输入跑两次，产物必须逐字节一致（生成目录禁止手改）。"""
    upstream = _require_upstream()
    ce = tmp_path / "ce"
    (ce / "configuration").mkdir(parents=True)

    first = items.generate(upstream, ce)
    snapshot = (ce / "configuration/items" / items.OUTPUT_NAME).read_bytes()
    second = items.generate(upstream, ce)
    again = (ce / "configuration/items" / items.OUTPUT_NAME).read_bytes()

    assert snapshot == again
    assert first["items"] == second["items"]
    assert first["recipes"] == second["recipes"]
    assert first["files"] >= 1
    assert isinstance(first["warnings"], list)
    assert isinstance(first["unsupported"], list)


def test_generate_contract_keys(tmp_path):
    """集成契约：返回 dict 必须含 files/warnings/unsupported/written。"""
    upstream = _require_upstream()
    ce = tmp_path / "ce"
    (ce / "configuration").mkdir(parents=True)
    summary = items.generate(upstream, ce, selected=())
    for key in ("files", "warnings", "unsupported", "written"):
        assert key in summary, f"缺少集成契约键 {key}"
    assert isinstance(summary["files"], int) and summary["files"] >= 1


# ---------------------------------------------------------------- ore_drops 不回归


def test_ore_drops_public_api_intact():
    """矿物系统的既有 API 不能回归（blocks.py / extract_bn.py 依赖它）。"""
    for name in ("configure", "loot", "loot_snippet", "loot_body_snippet",
                 "items_sections", "item_ids", "assert_references", "copy_item_textures"):
        assert callable(getattr(ore_drops, name)), f"ore_drops.{name} 丢失"


def test_ore_drops_loot_snippet_shape():
    """`loot_snippet()` 必须仍产出 `loot:` + ore 模板参数，缩进可配置。"""
    upstream = _require_upstream()
    ore_drops.configure(upstream)
    snippet = ore_drops.loot_snippet("cincinnasite_ore")
    assert "loot:" in snippet
    assert "default:loot_table/ore" in snippet
    assert "ore_block: betternether:cincinnasite_ore" in snippet
    assert "ore_drop: betternether:cincinnasite" in snippet


def test_ore_drops_configure_is_idempotent():
    """重复 configure 结果一致，且不依赖调用次数。"""
    upstream = _require_upstream()
    first = ore_drops.configure(upstream)
    second = ore_drops.configure(upstream)
    assert first == second
    assert set(first) == set(ore_drops.ORE_BLOCKS)

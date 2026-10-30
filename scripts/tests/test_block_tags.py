#!/usr/bin/env python3
"""配方用到的自定义标签：成员必须自己声明，否则 CE 报 `invalid_tag` 并跳过整条配方。

这些测试编码的是意图，不是行为：

  * 9 种木板合不出来，根因不是配方写错，而是配方里的 `#betternether:<wood>_logs`
    没有任何成员声明 —— CE 在**物品**注册表里查这个 tag
    （`ItemManager.itemIdsByTag` ← `AbstractRecipeSerializer.parseVanillaIngredient`），
    查不到就整条丢弃，日志只有一行 invalid_tag。所以判据落在产物上：
    每种木头的四个方块（log / bark / stripped_log / stripped_bark）都要真的带上它。
  * 标签写在哪一侧是硬要求：配方读物品注册表，方块 `settings.tags` 走
    `TagUtils` / `Registries.BLOCK`，登记的是方块标签 —— 写错侧等于没写。
    上游对同名 tag 的两份都做了定义，所以两边都要有。
  * 成员表来自上游 `data/betternether/tags/{item,block}/**`，不是这里再抄一份
    名单：上游加一种木头时，这里应该自动跟着变。

跑法：`python -m pytest scripts/tests/test_block_tags.py -q`
"""

from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import pytest
import yaml

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import blocks  # noqa: E402

NAMESPACE = "betternether"
UPSTREAM = SCRIPTS.parent / "upstream/BetterNether"
TAG_ROOT = UPSTREAM / "src/main/generated/data" / NAMESPACE / "tags"

# 上游九种木头（`data/betternether/tags/item/<wood>_logs.json` 的九个文件）。
WOODS = ("stalagnate", "anchor_tree", "gloomwood", "gloomwood_dark", "mushroom_fir",
         "nether_sakura", "rubeus", "wart", "willow")
PARTS = ("log", "bark", "stripped_log", "stripped_bark")


def item_tags(configs: dict[str, dict], ident: str) -> list[str]:
    return (configs[ident].get("settings") or {}).get("tags") or []


def block_tags(configs: dict[str, dict], ident: str) -> list[str]:
    merges = (configs[ident].get("settings") or {}).get("merges") or {}
    return merges.get("tags") or []


@pytest.fixture(scope="module")
def upstream_item_tags() -> dict[str, tuple[str, ...]]:
    tags = blocks.load_registry_tags(UPSTREAM, "item")
    assert tags, "读不到上游物品标签表，这条测试没有依据"
    return tags


def test_loader_reads_the_wood_log_tags_from_upstream(upstream_item_tags) -> None:
    """成员表来自上游 datagen：九族 `<wood>_logs` 各四个方块，一个不多一个不少。"""
    for wood in WOODS:
        tag = f"{NAMESPACE}:{wood}_logs"
        members = {ident for ident, tags in upstream_item_tags.items() if tag in tags}
        assert members == {f"{NAMESPACE}:{wood}_{part}" for part in PARTS}, (tag, sorted(members))


def test_every_wood_block_declares_the_item_tag(block_item_configs, upstream_item_tags) -> None:
    """配方按物品 tag 解析，所以这 36 个方块对应的**物品**段必须声明 `<wood>_logs`。"""
    for wood in WOODS:
        tag = f"{NAMESPACE}:{wood}_logs"
        for part in PARTS:
            ident = f"{NAMESPACE}:{wood}_{part}"
            assert ident in block_item_configs, f"{ident} 没有生成物品配置"
            assert tag in item_tags(block_item_configs, ident), (
                f"{ident} 的物品段没有声明 {tag}：引用它的配方会被 CE 整条丢弃（invalid_tag）")


def test_every_wood_block_declares_the_block_tag_too(block_configs) -> None:
    """上游对同名 tag 的方块那份也做了定义，方块段照样声明（不覆盖原有 mineable/logs）。"""
    for wood in WOODS:
        tag = f"{NAMESPACE}:{wood}_logs"
        for part in PARTS:
            ident = f"{NAMESPACE}:{wood}_{part}"
            tags = block_tags(block_configs, ident)
            assert tag in tags, f"{ident} 的 settings.tags 缺 {tag}"
            assert "minecraft:mineable/axe" in tags, f"{ident} 丢失了原有的挖掘工具标签"
            assert "minecraft:logs" in tags, f"{ident} 丢失了原有的 minecraft:logs"


def test_repair_tag_is_declared_on_the_crystal_item(block_item_configs,
                                                    upstream_item_tags) -> None:
    """`#betternether:flaming_ruby/repair` 的成员是方块，同样只能由它自己的物品段声明。"""
    tag = f"{NAMESPACE}:flaming_ruby/repair"
    members = {ident for ident, tags in upstream_item_tags.items() if tag in tags}
    assert f"{NAMESPACE}:gloomsculk_crystal" in members, sorted(members)
    published = [ident for ident in members if ident in block_item_configs]
    assert published, "该标签在本包里没有任何已发布成员，声明也就无从校验"
    for ident in published:
        assert tag in item_tags(block_item_configs, ident), ident


def test_tag_loader_skips_nested_references(upstream_item_tags) -> None:
    """`#wover:is_obsidian` 这种嵌套引用不是成员 id，不能被当成成员写出去。"""
    assert all(not ident.startswith("#") for ident in upstream_item_tags)
    nested = (TAG_ROOT / "block/obsidian_breaker_mineable.json").read_text(encoding="utf-8")
    assert "#wover:is_obsidian" in nested, "上游数据变了：请重新确认这条规则的依据"
    assert "#wover:is_obsidian" not in upstream_item_tags


def _recipe_tag_refs(root: Path) -> set[str]:
    """产物里被**配方**引用的 `#betternether:*` 标签（读文件，不读中间数据结构）。"""
    refs: set[str] = set()

    def walk(node: object) -> None:
        if isinstance(node, dict):
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)
        elif isinstance(node, str) and node.startswith(f"#{NAMESPACE}:"):
            refs.add(node[1:])

    for path in sorted(root.rglob("*.yml")):
        # `categories/` 里的 `- '#betternether:stones'` 是分类引用语法，不是物品标签。
        if path.parent.name == "categories":
            continue
        walk(yaml.safe_load(path.read_text(encoding="utf-8")) or {})
    return refs


def _declared_item_tags(root: Path) -> set[str]:
    """产物里被**物品定义**声明过的标签：任何 yml 的 `items.<id>.settings.tags`。

    刻意扫整个 `configuration/`：方块自带的 `block_item` 定义写在 `blocks/*.yml` 的
    `items:` 段里（不是 `items/` 目录），只扫 `items/*.yml` 会漏掉全部方块物品。
    """
    declared: set[str] = set()
    for path in sorted(root.rglob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        items = data.get("items")
        if not isinstance(items, dict):
            continue
        for body in items.values():
            tags = (body or {}).get("settings", {}).get("tags") or []
            declared.update(tag for tag in tags if isinstance(tag, str))
    return declared


def test_recipe_tag_ingredients_are_all_declared(blocks_output: Path,
                                                 item_configs: dict[str, dict]) -> None:
    """**从最终产物读**：配方里出现的每个 `#betternether:` 都必须有物品声明它。

    这是本次回归的正面判据：只要有一个标签没人声明，那条配方在游戏里就是失效的，
    而 CE 只打一行 `invalid_tag` 日志、不中断加载。断言直接读磁盘上的 yml ——
    中间数据结构正确而产物错误（例如写到了别的层级、被别的生成器覆盖）正是要抓的 bug。
    """
    # 配方由 items.py 写出（`item_configs` 夹具会先跑它），没有配方就无从校验标签引用。
    assert item_configs, "物品/配方产物为空，这条断言测不到东西"
    referenced = _recipe_tag_refs(blocks_output / "configuration")
    assert referenced, "产物里没有任何自定义标签引用，这条断言测不到东西"
    declared = _declared_item_tags(blocks_output / "configuration")
    assert referenced <= declared, (
        "这些标签被配方引用，但产物里没有任何物品声明它（CE 会跳过整条配方）："
        f"{sorted(referenced - declared)}")


def test_the_guard_fails_when_a_declaration_is_dropped(blocks_output: Path,
                                                       item_configs: dict[str, dict],
                                                       tmp_path: Path) -> None:
    """反例：故意清掉某个标签的全部声明后，上面那条判据必须变红。

    没有这条，上面那条断言可能只是「什么都没检查」也能绿（例如两条集合都空、
    或扫描路径失效）。这里复制一份产物、从物品定义里删掉 `betternether:willow_logs`，
    再跑同一套收集函数。
    """
    assert item_configs
    copied = tmp_path / "configuration"
    shutil.copytree(blocks_output / "configuration", copied)
    tag = f"{NAMESPACE}:willow_logs"
    stripped = 0
    for path in sorted(copied.rglob("*.yml")):
        text = path.read_text(encoding="utf-8")
        if tag not in text:
            continue
        kept = [line for line in text.splitlines()
                if not (line.strip() == f"- {tag}" and line.startswith(" " * 8))]
        if len(kept) != len(text.splitlines()):
            path.write_text("\n".join(kept) + "\n", encoding="utf-8")
            stripped += 1
    assert stripped, "产物里找不到该标签的声明，反例无从构造"

    referenced = _recipe_tag_refs(copied)
    declared = _declared_item_tags(copied)
    assert tag in referenced, "该标签仍应被配方引用"
    assert tag not in declared, "清空后不应再被视为已声明"
    assert referenced - declared == {tag}, (
        f"判据没能抓到被清空的标签：{sorted(referenced - declared)}")


def test_loader_degrades_when_upstream_data_is_missing(tmp_path: Path) -> None:
    """上游标签目录不存在时返回空表，而不是让整批方块失败。"""
    assert blocks.load_registry_tags(tmp_path, "item") == {}
    assert json.dumps(blocks.load_registry_tags(tmp_path, "block")) == "{}"

#!/usr/bin/env python3
"""分类产物：装备分类（tools / weapons / armor）必须由 items.py 的数据驱动。

这些测试编码的是意图，不是行为：

  * 「categories 文件里有 tools」不代表玩家能看见它 —— 显示名来自 lang 的
    `category.betternether.tools`，漏一条就是裸键。所以分类文件与 lang 必须遍历
    **同一份清单**，而不是各写一份白名单（旧版就是这样，加分类要改两个地方）。
  * 分类图标写错 id 不会报错，只会渲染成空气 —— 图标必须在真实存在的物品里挑，
    所以断言落在「上游真的注册过这个 id」上。
  * 装备分类的成员是**物品**，数据归 items.py 所有：它还没写 `EXTRA_CATEGORIES` 时
    blocks.py 必须照样跑通（三个基础叶子 + misc），不能因为另一个 owner 的进度整批失败。

跑法：`python -m pytest scripts/tests/test_block_categories.py -q`
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import blocks  # noqa: E402

NAMESPACE = "betternether"
UPSTREAM = SCRIPTS.parent / "upstream/BetterNether"

CJK = re.compile(r"[\u4e00-\u9fff]")

# 分类的固定骨架：与「更好的末地」一致的 10 个叶子分类（顺序即展示顺序）。
BASE_LEAVES = tuple(blocks.CATEGORY_ORDER)


def _extra() -> list[dict]:
    return blocks.extra_categories()


def _leaf_keys(category_config: dict) -> list[str]:
    """分类文件里声明过的叶子裸名（不含可见根）。"""
    root = category_config[f"{NAMESPACE}:{NAMESPACE}"]
    return [ref.lstrip("#").split(":", 1)[1] for ref in root["list"]]


def test_every_leaf_is_hidden_with_empty_list(category_config) -> None:
    """10 个叶子分类都是 hidden + list 留空（成员由 item 的 `category:` 键推入）。"""
    for key in BASE_LEAVES:
        leaf = category_config[f"{NAMESPACE}:{key}"]
        assert leaf["hidden"] is True, f"{NAMESPACE}:{key} 应当是 hidden 叶子分类"
        assert leaf["list"] == [], f"{NAMESPACE}:{key} 的 list 必须留空，成员由 item 推入"
        assert leaf["name"] == f"<!i><white><l10n:category.{NAMESPACE}.{key}></white>"
        assert leaf["icon"], f"{NAMESPACE}:{key} 没有图标"


def test_root_references_every_leaf_in_priority_order(category_config) -> None:
    """根分类的 `#子分类` 引用顺序 == priority 顺序；根永远最先（priority 1）。"""
    root = category_config[f"{NAMESPACE}:{NAMESPACE}"]
    assert root["priority"] == 1
    expected = list(blocks.CATEGORY_ORDER)
    assert _leaf_keys(category_config) == expected, (
        "叶子顺序必须是 CATEGORY_ORDER；顺序即 CE 里的展示顺序")

    priorities = [category_config[leaf]["priority"]
                  for leaf in [f"{NAMESPACE}:{key}" for key in expected]]
    assert priorities == list(range(2, 2 + len(expected))), priorities


def test_lang_uses_the_same_leaf_list_as_the_category_file(category_config,
                                                          category_langs) -> None:
    """lang 里每个叶子都要有条目，且中英文键集一致（否则另一种语言显示裸键）。"""
    en, zh = category_langs["en_us"], category_langs["zh_cn"]
    assert set(en) == set(zh), (
        f"中英分类 lang 键集不一致：{sorted(set(en) ^ set(zh))}")
    for key in _leaf_keys(category_config) + ["name", "lore"]:
        full = f"category.{NAMESPACE}.{key}"
        assert full in zh, f"{full} 在 zh_cn 里缺条目（游戏里显示裸键）"
        assert CJK.search(zh[full]), f"{full} 没有中文：{zh[full]!r}"
        assert en[full] != zh[full], f"{full} 中英相同，等于没汉化：{zh[full]!r}"


def test_all_leaf_icons_are_real(category_config) -> None:
    """每个叶子的图标都必须是真实存在的 id（写错时 CE 只把图标渲染成空气）。"""
    published = blocks.load_item_ids(UPSTREAM)
    assert published, "读不到上游 item_registrations.json，图标无法取证"
    for key in BASE_LEAVES:
        icon = category_config[f"{NAMESPACE}:{key}"]["icon"]
        assert icon.startswith(f"{NAMESPACE}:"), icon


def test_base_categories_still_get_block_icons(category_config, block_manifest) -> None:
    """基础叶子的图标仍是该分类里真实收录的方块（不是装备物品）。"""
    icons_by_category: dict[str, set[str]] = {}
    for entry in block_manifest:
        icons_by_category.setdefault(entry["category"], set()).add(entry["id"])
    for key in BASE_LEAVES + (blocks.CATEGORY_MISC,):
        icon = category_config[f"{NAMESPACE}:{key}"]["icon"]
        assert icon in icons_by_category[f"{NAMESPACE}:{key}"], icon


def test_every_item_category_is_declared(category_config, item_configs) -> None:
    """物品声明的每个 `category:` 都必须在分类文件里存在。

    装备分类的成员全靠 item 的 `category:` 键推进去：分类没声明时 CE 加载不报错，
    只是那些物品在创造菜单里翻不到 —— 所以判据落在**两边的产物**上。
    """
    declared = {f"{NAMESPACE}:{key}" for key in _leaf_keys(category_config)}
    referenced = {body["category"] for body in item_configs.values() if "category" in body}
    assert referenced, "items 配置里没有任何 category 引用，这条断言测不到东西"
    assert referenced <= declared, (
        f"这些物品分类没有在分类文件里声明（创造菜单里翻不到）：{sorted(referenced - declared)}")


def test_category_leaves_follow_category_order() -> None:
    """有成员的分类按 CATEGORY_ORDER 顺序输出，且图标必须在 published 里。

    旧版这条断言「恒输出 CATEGORY_ORDER 的 10 个叶子」，但那个语义已经作废：
    现在的规则是**没有成员的分类整个不生成**（`openable` 一族方块已全部移除，
    它连同 CATEGORY_ORDER 里的一并去掉了）。所以这里改成给全每个分类一个成员，
    再断言顺序与图标来源。
    """
    icons = {f"{blocks.NAMESPACE}:{key}": f"{blocks.NAMESPACE}:{key}_icon"
             for key in blocks.CATEGORY_ORDER}
    leaves = blocks.build_category_leaves(icons, set(icons.values()), extra=[])
    assert [leaf["key"] for leaf in leaves] == list(blocks.CATEGORY_ORDER), \
        "有成员时叶子顺序必须与 CATEGORY_ORDER 完全一致"
    assert all(leaf["icon"] for leaf in leaves), "叶子没有图标时也要退回可用图标"


def test_category_with_unpublished_icon_is_dropped() -> None:
    """图标不在 published 里 → 整个分类跳过，不写进产物。

    这防的是「以后有人改成从别处取图标」：分类图标写错 id 不会报错，只会渲染成空气。
    """
    icons = {f"{blocks.NAMESPACE}:{key}": f"{blocks.NAMESPACE}:{key}_icon"
             for key in blocks.CATEGORY_ORDER}
    # published 故意漏掉第一个分类的图标：它必须被整体跳过，其余照常输出。
    first = blocks.CATEGORY_ORDER[0]
    published = set(icons.values()) - {icons[f"{blocks.NAMESPACE}:{first}"]}

    leaves = blocks.build_category_leaves(icons, published, extra=[])

    assert first not in [leaf["key"] for leaf in leaves], \
        "图标未发布的分类必须整个跳过"
    assert len(leaves) == len(blocks.CATEGORY_ORDER) - 1, \
        "只跳过那一个分类，其余全部保留"


def test_unknown_icon_hint_falls_back_to_a_published_id() -> None:
    """建议图标不存在时退到同族铌钙石件；连它都没有就用调用方给的兜底 id。"""
    entry = {"key": "tools", "icon_hint": "definitely_not_an_item"}
    fallback = f"{NAMESPACE}:basalt_bricks"
    published = {f"{NAMESPACE}:cincinnasite_pickaxe", fallback}
    assert blocks.extra_category_icon(entry, published, fallback) == \
        f"{NAMESPACE}:cincinnasite_pickaxe"
    assert blocks.extra_category_icon(entry, {fallback}, fallback) == fallback


@pytest.mark.parametrize("entry", [
    {"name": "betternether:tools", "en": "Tools", "zh": "工具", "icon_hint": "x"},
    {"id": "weapons", "name_en": "Weapons", "name_zh": "武器", "icon": "y"},
])
def test_entry_shapes_are_normalised(entry) -> None:
    """两种形态（namespace 前缀 / 裸名、`en` / `name_en`）都要归一成同一份数据。"""
    normalized = []
    normalized.append(blocks._entry_field(entry, "name", "key", "id"))
    normalized.append(blocks._entry_field(entry, "en", "name_en", "en_us"))
    name, en = normalized
    assert name.split(":", 1)[-1] in {"tools", "weapons"}
    assert en in {"Tools", "Weapons"}

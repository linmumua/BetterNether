#!/usr/bin/env python3
"""语言产物的回归测试：**生成出来的**中英文文件必须覆盖每一个已发布显示文本。

这些测试编码的是意图，不是行为：

  * 「字典里有 34 个条目」不代表游戏里看得见中文 —— 判据必须落在**产物**上：
    配置里每一条 `<lang:...>` 都必须在语言文件里有值，否则玩家看到的是裸键
    `item.betternether.agave_leaf`，而构建日志只会报一个与它无关的「回退英文 34」；
  * 上游 zh_cn 缺中文时不许回退英文 —— 那正是这次要补的 34 个方块与 40 个物品名；
  * 上游已经有的中文一个字都不许改 —— 汉化不是重命名，改掉就是给老玩家换名字。

跑法：`python -m pytest scripts/tests/test_langs.py -q`
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import extract_bn  # noqa: E402
from bnlib import items  # noqa: E402
from bnlib.blocks import LANGS_OUTPUT_NAME  # noqa: E402

ROOT = SCRIPTS.parent
UPSTREAM = ROOT / "upstream/BetterNether"
LANG_SRC = UPSTREAM / "src/main/resources/assets/betternether/lang"
BLOCKS_SCRIPT = SCRIPTS / "bnlib/blocks.py"
NAMESPACE = "betternether"

CJK = re.compile(r"[\u4e00-\u9fff]")

# 上一次构建里仍显示英文的 34 个方块（`[警告] zh_cn 有 34 个方块缺上游译名`）。
# 写死是**故意的**：这张表就是这次汉化的验收对象，任何一条退回英文都应该立刻失败。
PREVIOUSLY_ENGLISH_BLOCKS = {
    "anchor_tree_bookshelf", "anchor_tree_chiseled_bookshelf", "crimson_bookshelf",
    "gloomgrass", "gloomsculk_crystal", "gloomsculk_vine", "gloomwisp_vine",
    "gloomwood_barrel", "gloomwood_bookshelf", "gloomwood_chiseled_bookshelf",
    "gloomwood_crafting_table", "gloomwood_dark_barrel", "gloomwood_dark_bookshelf",
    "gloomwood_dark_chiseled_bookshelf", "gloomwood_dark_crafting_table",
    "mushroom_fir_bookshelf", "mushroom_fir_chiseled_bookshelf", "mushroom_fir_trunk",
    "nether_mushroom_bookshelf", "nether_mushroom_chiseled_bookshelf",
    "nether_reed_bookshelf", "nether_reed_chiseled_bookshelf",
    "nether_sakura_bookshelf", "nether_sakura_chiseled_bookshelf",
    "pale_gloomgrass", "rubeus_bookshelf", "rubeus_chiseled_bookshelf",
    "stalagnate_bookshelf", "stalagnate_chiseled_bookshelf", "warped_bookshelf",
    "wart_bookshelf", "wart_chiseled_bookshelf", "willow_bookshelf",
    "willow_chiseled_bookshelf",
}


@pytest.fixture(scope="module")
def generated(tmp_path_factory) -> Path:
    """跑 blocks.py + items.generate + build_langs，返回 CE 包根目录。

    不用长期存在的 `plugin/build/`：那份产物可能比代码旧，测它就测不出本次改动。
    """
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    out = tmp_path_factory.mktemp("bn-langs")
    ce_root = out / "craftengine" / NAMESPACE
    subprocess.run(
        [sys.executable, str(BLOCKS_SCRIPT), "--upstream", str(UPSTREAM),
         "--output", str(ce_root)],
        check=True, capture_output=True,
    )
    manifest = json.loads((out / "reports/block-manifest.json").read_text(encoding="utf-8"))
    entries = manifest["blocks"]
    selected = [entry["id"] for entry in entries]
    # 物品配置就是「items 引用了哪些语言键」的真值来源；不跑它，这条回归就测不到物品名。
    items.generate(UPSTREAM, ce_root, selected)
    extract_bn.build_langs(UPSTREAM, ce_root, extract_bn.lang_keys_from_manifest(entries))
    return ce_root


def _lang(ce_root: Path, code: str) -> dict[str, str]:
    path = ce_root / f"configuration/langs/{LANGS_OUTPUT_NAME}"
    assert path.is_file(), f"没有生成 {path.name}"
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    sections = [value for key, value in data.items() if key.startswith("lang#")]
    assert len(sections) == 2, (
        f"{path.name} 的段名必须带 lang# 前缀（en_us/zh_cn 各一段），实际：{list(data)}")
    matching = [section for section in sections if code in section]
    assert len(matching) == 1, (
        f"{path.name} 里 {code} 这一层必须恰好出现一次，否则 CE 侧该语种的译名会缺失")
    return matching[0][code]


def _refs(generated: Path) -> list[str]:
    return extract_bn.published_lang_refs(generated)


def test_scan_does_not_require_builtin_container_titles(tmp_path: Path) -> None:
    config = tmp_path / "configuration"
    config.mkdir()
    (config / "sample.yml").write_text(
        'title: "<lang:container.chest> <lang:container.barrel>"\n'
        'name: "<lang:item.betternether.agave_leaf> <lang:container.typo>"\n',
        encoding="utf-8",
    )
    assert extract_bn.published_lang_refs(tmp_path) == [
        "container.typo", "item.betternether.agave_leaf",
    ], "仅原版已知标题由客户端提供；未知键不能被一起豁免"


def _published_blocks(ce_root: Path) -> list[dict]:
    """本次生成写出的方块清单（`blocks.py` 落在包的 `reports/` 下）。"""
    manifest = json.loads(
        (ce_root.parents[1] / "reports/block-manifest.json").read_text(encoding="utf-8"))
    return manifest["blocks"]


def _independent_item_keys(ce_root: Path) -> set[str]:
    """独立于 `published_lang_refs` 的旁证：直接扫 items 配置里的物品显示键。

    用另一条路径（正则扫 `repr(body)`）复算一遍，避免「扫描器坏了」和「产物缺键」
    互相掩盖 —— 扫描器失效时这里仍能看见真实存在的键，断言随即暴露不一致。
    """
    keys: set[str] = set()
    for path in (ce_root / "configuration").rglob("*.yml"):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for body in (data.get("items") or {}).values():
            keys.update(re.findall(r"<lang:(item\.betternether\.[^>]+)>", repr(body)))
    return keys


def test_scan_sees_the_published_display_text(generated: Path) -> None:
    """自检：扫描必须真的看到方块与物品两类引用。

    扫描失效时「引用 ⊆ 语言条目」会静默成立 —— 那正是物品名整批丢失时的样子
    （语言文件里一条 item 键都没有，检查却全绿）。两条判据都从**本次生成的产物**
    推导，而不是写死一个会随发布数量漂移的阈值。
    """
    refs = _refs(generated)
    blocks = [key for key in refs if key.startswith(f"block.{NAMESPACE}.")]
    item_keys = [key for key in refs if key.startswith(f"item.{NAMESPACE}.")]

    # 方块侧：扫描必须覆盖已发布方块的语言键。仅允许极少数方块共用/省略标题键
    # （例如 stalagnate_trunk 与 stalagnate 共用同一个标题）。
    published = {entry["id"].split(":", 1)[1] for entry in _published_blocks(generated)}
    seen = {key.split(f"block.{NAMESPACE}.", 1)[1] for key in blocks}
    uncovered = published - seen
    assert len(uncovered) <= 2, (
        f"扫描漏掉了 {len(uncovered)} 个发布方块的语言键（扫描路径可能已失效）："
        f"{sorted(uncovered)[:10]}")

    # 物品侧独立取证：items 配置里出现的 item.betternether.* 键必须被扫描全部看到。
    expected_items = _independent_item_keys(generated)
    assert expected_items, "items 配置里没有任何 betternether 物品显示名"
    assert set(item_keys) == expected_items, (
        "扫描到的物品语言键与 items 配置不一致（扫描路径可能失效）："
        f"漏 {sorted(expected_items - set(item_keys))[:10]} / "
        f"多 {sorted(set(item_keys) - expected_items)[:10]}")


def test_every_published_key_has_chinese(generated: Path) -> None:
    """配置里引用的每个语言键，中文文件里都必须有中文值（不是裸键、不是空串）。"""
    zh = _lang(generated, "zh_cn")
    refs = _refs(generated)
    absent = [key for key in refs if key not in zh]
    assert absent == [], f"这些已发布的显示文本在 zh_cn.yml 里没有条目（游戏里显示裸键）：{absent}"
    not_chinese = [key for key in refs if not CJK.search(zh[key])]
    assert not_chinese == [], (
        "这些条目没有中文（回退成英文或混进了 id）："
        f"{[(key, zh[key]) for key in not_chinese]}"
    )


def test_no_published_text_falls_back_to_english(generated: Path) -> None:
    """中英对照：已发布文本的中英值不允许相同。

    相同就是回退英文 —— 这正是上次构建里 34 个方块 + 40 个物品名的状态。
    """
    zh = _lang(generated, "zh_cn")
    en = _lang(generated, "en_us")
    same = [key for key in _refs(generated) if key in en and zh.get(key) == en[key]]
    assert same == [], f"这些显示文本仍是英文（未汉化）：{[(key, en[key]) for key in same]}"


def test_previously_english_blocks_are_chinese_now(generated: Path) -> None:
    """上次构建回退英文的 34 个方块，现在必须有中文；且上游 zh_cn 仍然没有它们。

    第二句是关键：如果哪天上游把这批补了中文，这条会失败 —— 提醒把这批条目
    从补充表里删掉，让上游译名接管（补充表不该盖住上游）。
    """
    zh = _lang(generated, "zh_cn")
    upstream_zh = json.loads((LANG_SRC / "zh_cn.json").read_text(encoding="utf-8"))
    for name in sorted(PREVIOUSLY_ENGLISH_BLOCKS):
        key = f"block.{NAMESPACE}.{name}"
        assert key in zh, f"{key} 又没条目了"
        assert CJK.search(zh[key]), f"{key} 又回退成英文了：{zh[key]}"
        assert key not in upstream_zh, (
            f"上游已经提供了 {key} 的中文（{upstream_zh.get(key)}），"
            "请从 SUPPLEMENTARY_ZH 里删掉同名条目，让上游译名优先"
        )


def test_upstream_chinese_is_never_renamed(generated: Path) -> None:
    """上游已有中文的条目必须逐字沿用 —— 汉化不是重命名。"""
    zh = _lang(generated, "zh_cn")
    upstream_zh = json.loads((LANG_SRC / "zh_cn.json").read_text(encoding="utf-8"))
    renamed = {
        key: (upstream_zh[key], zh.get(key))
        for key in upstream_zh
        if key in zh and zh[key] != upstream_zh[key]
    }
    assert renamed == {}, f"这些上游译名被改掉了（老玩家会看到换名）：{renamed}"


def test_lang_files_follow_ce_contract(generated: Path) -> None:
    """CE 的 langs 契约：段名带 `lang#`、语言层与文件名一致、中英键集一致。

    错一个文件就被**静默丢弃**，日志一字不差 —— 所以这条只能靠测试兜。
    """
    zh = _lang(generated, "zh_cn")
    en = _lang(generated, "en_us")
    assert set(zh) == set(en), (
        "中英语言文件的键集不一致（少的那些在另一种语言下会显示裸键）："
        f"{sorted(set(en) - set(zh))[:5]} / {sorted(set(zh) - set(en))[:5]}"
    )

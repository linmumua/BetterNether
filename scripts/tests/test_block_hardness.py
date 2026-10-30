#!/usr/bin/env python3
"""硬度产物：只在「上游数值明显来自整块属性拷贝」时按原版同类对齐。

这些测试编码的是意图，不是行为：

  * 用户反馈的是「空手挖太慢」，但**不能**因此把 260 个方块一起调软 —— 木材 2、
    黑曜石 50、藤蔓 0.2、仙人掌 0.4、紫水晶簇 1.5 都与原版同类一致，改小就是给
    老玩家换手感。真正是拷贝事故的只有玻璃类（黑曜石/铌钙石块的整块属性拷贝）。
  * 上游**显式**写下的取值（`orange_mushroom` 0.5、巨型蘑菇 1.0/2.0）不算事故，
    一律不动 —— 否则「对齐原版」会变成「按我的口味重做平衡」。
  * 硬度降下来还不够：玻璃类原来还带着黑曜石拷贝来的「必须钻石镐」，那会让空手
    依然挖不掉，等于改了个数字而已。两条必须一起钉住。
  * 每条改动都必须能指回一个原版方块，并且出现在 `reports/hardness-adjustments.json`
    里 —— 悄悄发生的变化无法被需求方核对。

跑法：`python -m pytest scripts/tests/test_block_hardness.py -q`
"""

from __future__ import annotations

import dataclasses
import json
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import blocks, props  # noqa: E402

NAMESPACE = "betternether"
UPSTREAM = SCRIPTS.parent / "upstream/BetterNether"


@pytest.fixture(scope="module")
def upstream_props() -> dict:
    values = props.load_props(UPSTREAM)
    assert values, "缺少上游属性时不能把默认硬度当成已验证结果"
    return values


def hardness(configs: dict[str, dict], name: str) -> float:
    ident = f"{NAMESPACE}:{name}"
    assert ident in configs, f"{ident} 没有生成配置"
    return configs[ident]["settings"]["merges"]["hardness"]


def upstream_hardness(values: dict, name: str) -> float:
    return values[f"{NAMESPACE}:{name}"].hardness


# ---------------------------------------------------------------- 四个方向


def test_glass_aligns_to_vanilla_glass(block_manifest, block_configs, upstream_props) -> None:
    """玻璃类按原版 `minecraft:glass` 0.3：它们的 50 / 3 是整块属性拷贝的副产品。"""
    glass = [entry for entry in block_manifest if entry["class"] in blocks.GLASS_CLASSES]
    assert len(glass) >= 20, f"玻璃类方块集合太小，这个断言可能没真的在检查：{len(glass)}"
    for entry in glass:
        name = entry["id"].split(":", 1)[1]
        assert hardness(block_configs, name) == blocks.VANILLA_GLASS_HARDNESS, (
            f"{name}（{entry['class']}）的硬度应当是原版玻璃 "
            f"{blocks.VANILLA_GLASS_HARDNESS}，实际 {hardness(block_configs, name)}")
    # 上游确实给过 50 / 3：没有这两个对照，上面那条断言在「上游本来就 0.3」时也会绿。
    assert upstream_hardness(upstream_props, "obsidian_glass") == 50
    assert upstream_hardness(upstream_props, "quartz_glass_framed") == 3


def test_glass_does_not_require_a_tool(block_manifest, block_configs) -> None:
    """玻璃类不再有工具**硬门槛**，但 `correct_tools` 仍按上游给出。

    `require_correct_tools` / `required_break_power` 是门槛（上游值从黑曜石/铌钙石块
    拷贝而来，与原版玻璃「任何工具都能挖」相反），两项必须一起放开 —— CE 里
    「关掉布尔要求但留着破坏力」仍然拒绝空手（`test_mining_contract.py` 里那条注释
    就是踩过的坑）。

    `correct_tools` 是「挖得**更快**的工具族」，纯效率提示：清空它会让镐子挖玻璃
    不再加速，所以这里要求它仍在、且就是上游 mineable 的 pickaxe 族。
    抗爆 `resistance` 也不动 —— 防爆玻璃是有意设计。
    """
    glass = [entry for entry in block_manifest if entry["class"] in blocks.GLASS_CLASSES]
    assert glass
    for entry in glass:
        merges = block_configs[entry["id"]]["settings"]["merges"]
        assert merges["require_correct_tools"] is False, entry["id"]
        assert merges.get("required_break_power", 0) == 0, entry["id"]
        tools = merges.get("correct_tools") or []
        assert "minecraft:iron_pickaxe" in tools, (
            f"{entry['id']} 丢了 correct_tools 的镐族：镐子挖玻璃不再加速")
        assert all(tool.endswith("_pickaxe") for tool in tools), (
            f"{entry['id']} 的 correct_tools 夹带了非镐族工具：{tools}")
        assert "minecraft:mineable/pickaxe" in merges["tags"], entry["id"]
    # 抗爆仍是上游值：obsidian_glass 1200、framed 系列 0.3 —— 只有硬度与门槛被对齐。
    assert block_configs["betternether:obsidian_glass"]["settings"]["merges"]["resistance"] == 1200
    assert block_configs["betternether:quartz_glass_framed"]["settings"]["merges"]["resistance"] == 0.3


def test_upstream_intentional_values_are_kept(block_configs, upstream_props) -> None:
    """上游显式设计的取值一律不动（需求方已裁决）。

    * `orange_mushroom` 0.5 —— 上游注释写明是拿 `destroyTime(0.5F)` 覆盖预设
      `instabreak()` 的有意选择；
    * 巨型蘑菇 1.0 / 2.0 —— 上游显式 `.strength(1)` / `mushroomStem()` 预设。
    它们都跟原版同类方块不同，但属于设计取值，不是整块拷贝事故。
    """
    for name in ("orange_mushroom", "brown_large_mushroom", "giant_mold",
                 "red_large_mushroom", "jellyfish_mushroom"):
        assert hardness(block_configs, name) == upstream_hardness(upstream_props, name), name
    assert hardness(block_configs, "orange_mushroom") == 0.5
    assert hardness(block_configs, "red_large_mushroom") == 2


def test_wood_keeps_upstream_two(block_configs, upstream_props) -> None:
    """木材 2 与原版原木一致，**不许**动。"""
    for name in ("willow_log", "anchor_tree_bark", "willow_planks", "stalagnate_stem"):
        assert hardness(block_configs, name) == upstream_hardness(upstream_props, name) == 2, name


def test_obsidian_family_keeps_vanilla_fifty(block_configs, upstream_props) -> None:
    """黑曜石系 50 与原版黑曜石一致，**不许**动（只有玻璃被对齐）。"""
    for name in ("obsidian_bricks", "obsidian_tile", "obsidian_rod_tiles",
                 "blue_obsidian", "weeping_obsidian"):
        assert hardness(block_configs, name) == upstream_hardness(upstream_props, name) == 50, name


# ---------------------------------------------------------------- 反例：不要无差别改


@pytest.mark.parametrize("name,expected", [
    ("cincinnasite_bricks", 3),    # 铌钙石砖：上游 3，与同类金属/石砖同级
    ("cincinnasite_block", 3),
    ("basalt_bricks", 2),          # 玄武岩砖：与石材同级
    ("gloomsculk_crystal", 1.5),   # 原版紫水晶簇 1.5
    ("nether_ruby_block", 5),      # 原版红石块/金属块一档
    ("veined_sand", 0.5),          # 原版沙 0.5
])
def test_values_with_a_vanilla_counterpart_are_untouched(name, expected, block_configs,
                                                        upstream_props) -> None:
    """这些数值都能指回一个原版同类方块，改小就是「无差别下调」。"""
    assert upstream_hardness(upstream_props, name) == expected
    assert hardness(block_configs, name) == expected


def test_report_lists_exactly_the_changed_blocks(block_configs, block_manifest,
                                                 upstream_props, reports_dir) -> None:
    """报告 == 配置里与上游不一致的那些方块，一条不多一条不少。"""
    # 家具（`kind == "furniture"`）没有 `blocks:` 段：它们是 `furniture_item`，
    # 硬度由 `settings` 之外的另一套机制承载（家具靠 hit_times/sounds，没有 merges.hardness），
    # 所以 `hardness()` 读不到值、会被误判成「与上游不一致」。
    furniture = {entry["id"].split(":", 1)[1] for entry in block_manifest
                 if entry["kind"] == "furniture"}
    changed = {
        entry["id"].split(":", 1)[1]
        for entry in block_manifest
        if entry["id"].split(":", 1)[1] not in furniture
        and hardness(block_configs, entry["id"].split(":", 1)[1])
        != upstream_hardness(upstream_props, entry["id"].split(":", 1)[1])
    }
    report = json.loads((reports_dir / "hardness-adjustments.json").read_text(encoding="utf-8"))
    listed = {entry["id"].split(":", 1)[1] for entry in report["blocks"]}
    assert listed == changed, (
        f"报告与实际改动不一致：报告多 {sorted(listed - changed)[:5]} / "
        f"报告少 {sorted(changed - listed)[:5]}")
    for entry in report["blocks"]:
        assert entry["upstream"] != entry["applied"], entry
        assert entry["reference"], f"{entry['id']} 没有写出依据"
    # 反例：木材与黑曜石系绝不出现在改动清单里。
    assert not {name for name in listed if name.endswith(("_log", "_bark", "_planks"))}
    assert not {name for name in listed if "obsidian" in name and "glass" not in name}
    # 改动集合**只有玻璃类**：上游其余 240 个方块的值一个都没被动过。
    assert listed == {entry["id"].split(":", 1)[1] for entry in block_manifest
                      if entry["class"] in blocks.GLASS_CLASSES
                      and upstream_props[entry["id"]].hardness != blocks.VANILLA_GLASS_HARDNESS}


# ---------------------------------------------------------------- 规则本身


def test_adjustment_only_fires_on_copy_artifacts(upstream_props) -> None:
    """规则是「看上游怎么得到这个数」，不是「数值大就调小」。"""
    wood = upstream_props[f"{NAMESPACE}:willow_log"]
    assert blocks.hardness_adjustment(wood) is None
    # 把木头改成一个「偏大」的数也**不会**被这条规则碰：木材在原版就是 2，偏大不是拷贝事故。
    assert blocks.hardness_adjustment(dataclasses.replace(wood, hardness=50)) is None
    glass = upstream_props[f"{NAMESPACE}:obsidian_glass"]
    adjustment = blocks.hardness_adjustment(glass)
    assert adjustment is not None
    assert adjustment[0] == blocks.VANILLA_GLASS_HARDNESS
    assert "glass" in adjustment[1], adjustment[1]
    # 花草与巨型蘑菇都不在这条规则里：上游给的是设计取值，不是整块拷贝。
    for name in ("orange_mushroom", "red_large_mushroom", "nether_cactus"):
        p = upstream_props[f"{NAMESPACE}:{name}"]
        assert blocks.hardness_adjustment(p) is None, name
        assert blocks.aligned_hardness(p) == p.hardness, name

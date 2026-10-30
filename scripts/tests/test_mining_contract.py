"""适用工具控制效率，只有上游确需正确工具的方块才限制空手掉落。"""

from pathlib import Path
import sys

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from bnlib import blocks, props


@pytest.fixture(scope="module")
def upstream_data():
    upstream = ROOT / "upstream/BetterNether"
    values = props.load_props(upstream)
    assert values, "缺少上游属性时不能把默认硬度当成已验证结果"
    return values, props.load_mineable_tools(upstream), props.load_break_power(upstream)


def settings(data, name):
    values, tools, powers = data
    ident = f"betternether:{name}"
    p = values[ident]
    text = blocks.settings_yaml(ident, p, tools.get(ident, frozenset()),
                                powers.get(ident, 0), None, [], blocks.KIND_SOLID)
    parsed = yaml.safe_load("blocks:\n  test:block:\n" + text)
    return p, parsed["blocks"]["test:block"]["settings"]["merges"]


def test_all_normal_wood_keeps_hand_drops_and_axe_efficiency(upstream_data):
    values, tools, _ = upstream_data
    wood = [ident.split(":", 1)[1] for ident, p in values.items()
            if not p.requires_tool and "axe" in tools.get(ident, ())
            and ident.endswith(("_log", "_bark", "_planks"))]
    assert {"willow_log", "willow_stripped_log", "willow_planks"} <= set(wood)
    for name in wood:
        p, actual = settings(upstream_data, name)
        assert actual["hardness"] == p.hardness, name
        assert actual.get("require_correct_tools") is False, (
            f"{name}: 斧头更快不能被误写成空手不能掉落")
        assert actual.get("required_break_power", 0) == 0, (
            f"{name}: 仅关闭工具布尔仍不够，遗留破坏力会再次拒绝空手")
        assert "minecraft:mineable/axe" in actual["tags"], name


@pytest.mark.parametrize("name,tier", [("cincinnasite_ore", 3), ("nether_ruby_ore", 4)])
def test_ores_still_require_the_original_pickaxe_tier(upstream_data, name, tier):
    p, actual = settings(upstream_data, name)
    assert p.requires_tool is True
    assert actual["hardness"] == p.hardness
    assert actual["require_correct_tools"] is True
    assert actual["required_break_power"] == tier
    assert "minecraft:mineable/pickaxe" in actual["tags"]
    assert "minecraft:diamond_pickaxe" in actual["correct_tools"]
    assert not any(tool.endswith("_axe") for tool in actual["correct_tools"])


def test_glass_does_not_require_a_pickaxe_just_because_pickaxes_are_faster(upstream_data):
    p, actual = settings(upstream_data, "quartz_glass")
    assert not p.requires_tool
    assert actual["require_correct_tools"] is False
    assert actual.get("required_break_power", 0) == 0
    assert actual["hardness"] == p.hardness == 0.3
    assert "minecraft:mineable/pickaxe" in actual["tags"]


def test_zero_hardness_plant_is_not_given_an_artificial_tool_gate(upstream_data):
    p, actual = settings(upstream_data, "agave")
    assert actual["hardness"] == p.hardness == 0
    assert actual.get("require_correct_tools", False) is False
    assert actual.get("required_break_power", 0) == 0


def _family_of(tool: str) -> str | None:
    """工具 id -> 所属工具族（`block_props.TOOL_FAMILIES` 反查）。"""
    for family, members in props.TOOL_FAMILIES.items():
        if tool in members:
            return family
    return None


def test_tool_gate_follows_upstream_requires_tool_exactly(upstream_data):
    """全量核对：`require_correct_tools` 只由上游 `reqTool` 决定，工具族只来自 mineable。

    这条把「斧头更快 -> 空手不能掉落」的误映射钉死：`correct_tools` 只在
    `requires_tool` 为真时出现，且列出的每个工具都必须属于该方块 mineable 标签里的族。
    反向也查：`requires_tool` 为真时不得因为 mineable 里有某族就漏掉那一族。

    期望值走 `blocks.requires_correct_tool`：唯一的例外是玻璃类 —— 它们的 `reqTool`
    是从黑曜石 / 铌钙石块整块属性拷贝来的（与原版玻璃「任何工具都能挖」相反），
    与硬度对齐一起改掉。例外只有这一处，函数里写明了依据。
    """
    values, tools, powers = upstream_data
    checked = 0
    for ident, p in values.items():
        name = ident.split(":", 1)[1]
        _p, actual = settings(upstream_data, name)
        families = set(tools.get(ident, ()))
        requires_tool = blocks.requires_correct_tool(p)
        assert actual["require_correct_tools"] is requires_tool, name
        expected_power = powers.get(ident, 0) if requires_tool else 0
        assert int(actual.get("required_break_power", 0)) == expected_power, name

        if not requires_tool:
            if p.cls in blocks.GLASS_CLASSES:
                # 玻璃类：硬门槛放开，但 `correct_tools` 仍按上游 mineable 族给出 ——
                # 它是「挖得更快」的效率提示，不是门槛（清空会让镐子挖玻璃不再加速）。
                got = {_family_of(tool) for tool in actual["correct_tools"]}
                assert None not in got, f"{name}: correct_tools 混入了未知族工具"
                assert got == families, (
                    f"{name}: 玻璃类的 correct_tools 族 {got} 与 mineable 族 {families} 不一致")
            else:
                assert "correct_tools" not in actual, (
                    f"{name}: 不需要正确工具的方块不该列出 correct_tools")
        elif families:
            got = [_family_of(tool) for tool in actual["correct_tools"]]
            assert None not in got, f"{name}: correct_tools 混入了未知族工具"
            assert set(got) == families, (
                f"{name}: correct_tools 的族 {set(got)} 与 mineable 族 {families} 不一致")

        # mineable 标签只增不减（效率），与 require_correct_tools 是两件事。
        for family in families:
            assert f"minecraft:mineable/{family}" in actual["tags"], name
        checked += 1
    assert checked > 400, f"只核对了 {checked} 个方块，覆盖面异常"


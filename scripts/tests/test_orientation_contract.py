#!/usr/bin/env python3
"""原木/树皮的 `axis` 朝向契约：CE 硬编码按点击面设置，配置必须能被它驱动。

## 机制（本地 CE 字节码核对，不是推断）

`craft-engine-paper-plugin-26.10-SNAPSHOT.jar` 的
`net.momirealms.craftengine.bukkit.block.behavior.HardcodedPropertyBlockBehavior` 把几个
属性名注册进 `HARD_CODED_PROPERTY_DATA`：`axis` / `facing` / `facing_clockwise` /
`rotation`。其中 `axis` 的放置逻辑读 `BlockPlaceContext#getClickedFace().axis()`：

    state.with(axisProperty, context.getClickedFace().axis())

也就是玩家**点哪个面**，方块就沿该面的轴摆放 —— 这是 CE 内建行为，配置侧唯一要做的
就是声明 `axis` 属性（`type: axis`）并给出三种取值的外观。如果漏声明 axis，
原木永远竖着、无法横放；如果外观旋转与上游 blockstate 对不上，横放时年轮会转到侧面。

这些断言同时锁三件事：

  1. 已发布的原木/树皮（含去皮）都声明了 `axis`，且 `type` 是 CE 的硬编码 `axis`；
  2. `axis` 的默认值是 `y`（原版 `RotatedPillarBlock` 的默认态；只影响「不带属性放置」）；
  3. 每个 `axis=<v>` 外观的模型与旋转，必须和上游 blockstate 的同名变体一致。
"""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks  # noqa: E402

GENERATED = ROOT / "plugin/build/generated/bn/craftengine/betternether/configuration"
MANIFEST = ROOT / "plugin/build/generated/bn/reports/block-manifest.json"
CE_JAR = Path("C:/PluginLibs/craft-engine-paper-plugin-26.10-SNAPSHOT.jar")

UPSTREAM = ROOT / "upstream/BetterNether"
UPSTREAM_BLOCKSTATES = (
    UPSTREAM / "src/main/generated/assets/betternether/blockstates",
    UPSTREAM / "src/main/resources/assets/betternether/blockstates",
)

AXIS_PROPERTY = "axis"
AXIS_VALUES = ("x", "y", "z")


def _load_generated() -> dict[str, dict]:
    if not (GENERATED / "blocks").is_dir():
        pytest.skip("生成目录不存在，先跑一次生成器")
    defs: dict[str, dict] = {}
    for path in sorted((GENERATED / "blocks").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        defs.update(data.get("blocks") or {})
    return defs


def _load_manifest() -> dict[str, dict]:
    if not MANIFEST.is_file():
        pytest.skip("缺少 block-manifest.json，先跑一次生成器")
    entries = json.loads(MANIFEST.read_text(encoding="utf-8"))["blocks"]
    return {entry["id"]: entry for entry in entries}


def _upstream_axis_variants(name: str) -> dict[str, set[tuple[str, int, int]]]:
    """上游 blockstate 里凡带 `axis=<v>` 的变体 -> 该轴下第一份 (模型, x 旋转, y 旋转)。

    变体键可能还有别的属性（`rubeus_bark` 是 `axis=x,shape=bottom`），按逗号拆开取
    `axis` 那一段，不能直接 `split("=")[1]`。
    """
    for directory in UPSTREAM_BLOCKSTATES:
        path = directory / f"{name}.json"
        if not path.is_file():
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        variants = payload.get("variants") or {}
        found: dict[str, set[tuple[str, int, int]]] = {}
        for key, value in variants.items():
            props = {}
            for part in key.split(","):
                name_, _sep, raw = part.partition("=")
                props[name_.strip()] = raw.strip()
            axis = props.get("axis")
            if not axis:
                continue
            entries = value if isinstance(value, list) else [value]
            for entry in entries:
                if not isinstance(entry, dict) or not isinstance(entry.get("model"), str):
                    continue
                found.setdefault(axis, set()).add((
                    entry["model"], int(entry.get("x", 0)), int(entry.get("y", 0))))
                break
        if found:
            return found
    return {}


def _appearance_models(appearance: dict) -> list[tuple[str, int, int]]:
    """一个外观实际渲染的 (模型路径, x, y) 列表（单模型与 `models:` 列表两种形态）。"""
    entries = appearance.get("models")
    if not entries:
        entries = [appearance.get("model")] if appearance.get("model") else []
    result: list[tuple[str, int, int]] = []
    for entry in entries:
        if isinstance(entry, str):
            result.append((entry, 0, 0))
        elif isinstance(entry, dict) and isinstance(entry.get("path"), str):
            result.append((entry["path"], int(entry.get("x", 0)), int(entry.get("y", 0))))
    return result


# ------------------------------------------------------------------ CE 语义（jar）


def test_ce_jar_implements_axis_from_clicked_face():
    """`axis` 必须在 CE 的硬编码属性表里，且放置逻辑真的读点击面。"""
    if not CE_JAR.is_file():
        pytest.skip(f"找不到 CE jar：{CE_JAR}")
    with zipfile.ZipFile(CE_JAR) as archive:
        name = ("net/momirealms/craftengine/bukkit/block/behavior/"
                "HardcodedPropertyBlockBehavior.class")
        try:
            data = archive.read(name)
        except KeyError:
            pytest.skip("CE jar 结构变化，找不到 HardcodedPropertyBlockBehavior")
    # class 常量池里的 UTF8 字符串在这份字节里是明文，可以直接判定行为接的是什么。
    for needle in (b"axis", b"getClickedFace", b"facing"):
        assert needle in data, f"CE 的 HardcodedPropertyBlockBehavior 里没有 {needle!r}"
    assert b"getHorizontalDirection" in data or b"getNearestLookingDirection" in data, (
        "facing 的玩家朝向逻辑不见了，CE 版本可能已变")


# ------------------------------------------------------------------ 配置结构


def test_every_published_pillar_declares_ce_axis_property():
    """所有声明了 axis 的已发布方块：`type: axis`（否则不触发 CE 硬编码）且默认 y。"""
    defs = _load_generated()
    pillars = {bid: body for bid, body in defs.items()
               if AXIS_PROPERTY in ((body.get("states") or {}).get("properties") or {})}
    assert len(pillars) >= 30, f"只找到 {len(pillars)} 个带 axis 的方块，判定路径可能失效"
    for bid, body in pillars.items():
        prop = body["states"]["properties"][AXIS_PROPERTY]
        assert prop.get("type") == "axis", (
            f"{bid}: axis 的 type 不是 CE 的 axis（{prop.get('type')}），点击面逻辑不会生效")
        # 原版 RotatedPillarBlock 默认态是 axis=y。
        assert prop.get("default") == "y", f"{bid}: axis 默认值不是 y（{prop.get('default')}）"


def test_published_logs_and_barks_have_all_three_axis_appearances():
    """已发布原木/树皮/去皮原木必须三种轴都落地 —— 漏一种就有一个轴向没外观。"""
    defs = _load_generated()
    expected = {
        "betternether:willow_log",
        "betternether:willow_bark",
        "betternether:willow_stripped_log",   # 曾经漏成单状态：只能竖放
        "betternether:willow_stripped_bark",
        "betternether:nether_sakura_log",
        "betternether:nether_sakura_stripped_log",
        "betternether:anchor_tree_log",
        "betternether:gloomwood_log",
        "betternether:wart_log",
        "betternether:stalagnate_stem",
        "betternether:cincinnasite_bricks_pillar",
    }
    missing_ids = expected - set(defs)
    assert not missing_ids, f"这些原木没有发布：{sorted(missing_ids)}"
    for bid in sorted(expected):
        appearances = (defs[bid].get("states") or {}).get("appearances") or {}
        for value in AXIS_VALUES:
            assert f"axis_{value}" in appearances, f"{bid}: 缺少 axis={value} 外观"


def test_axis_appearance_rotation_matches_upstream_blockstate():
    """逐个 `axis=<v>` 外观核对：模型与 x/y 旋转必须和上游 blockstate 同义。"""
    defs = _load_generated()
    checked = 0
    for bid, body in defs.items():
        properties = (body.get("states") or {}).get("properties") or {}
        if AXIS_PROPERTY not in properties:
            continue
        name = bid.split(":", 1)[1]
        upstream = _upstream_axis_variants(name)
        if not upstream:
            continue
        appearances = (body.get("states") or {}).get("appearances") or {}
        for value, expected in upstream.items():
            # 外观名可能是 `axis_x`，也可能还带别的属性（`rubeus_bark` 是
            # `axis_x_shape_bottom`）—— 按前缀取该轴下的全部外观。
            matching = {name: app for name, app in appearances.items()
                        if name == f"axis_{value}" or name.startswith(f"axis_{value}_")}
            assert matching, f"{bid}: 上游有 axis={value}，生成物没有对应外观"
            actual: list[tuple[str, int, int]] = []
            for appearance in matching.values():
                actual.extend(_appearance_models(appearance))
            if not actual:
                # 透明载体（`entity_renderer`）没有方块模型，朝向用 display rotation 表达：
                # 竖轴不需要旋转，横轴必须有旋转，否则横放的原木仍是竖的。
                for name, appearance in matching.items():
                    renderer = appearance.get("entity_renderer") or {}
                    assert renderer.get("item"), f"{bid}[{name}] 透明载体没有 item"
                    if value != "y":
                        assert renderer.get("rotation"), (
                            f"{bid}[{name}] 透明载体缺少 rotation，横轴会被渲染成竖的")
                checked += 1
                continue
            # 上游第一份模型（含旋转）必须出现在该轴外观的模型集合里。
            assert expected & set(actual), (
                f"{bid}[axis={value}] 朝向与上游不一致：上游 {sorted(expected)}，生成 {actual}")
            checked += 1
    assert checked >= 60, f"只核对了 {checked} 个 axis 外观，覆盖不足"


def test_vertical_default_axis_is_y_not_a_horizontal_axis():
    """回归：默认值不能取上游 blockstate 的第一个变体（很多原木第一个是 axis=x）。"""
    defs = _load_generated()
    horizontal_first = [
        bid for bid, body in defs.items()
        if ((body.get("states") or {}).get("properties") or {}).get(AXIS_PROPERTY, {})
        .get("default") == "x"
    ]
    assert horizontal_first == [], f"这些方块的 axis 默认成了横轴：{horizontal_first}"


# ------------------------------------------------------------------ 生成器单元


def test_compile_forces_y_default_even_if_first_variant_is_x():
    """直接喂一个「axis=x 排第一」的 blockstate，默认值仍必须是 y。"""
    raw = {
        "states": [
            ({"axis": "x"}, (("demo_log", 90, 90, False),)),
            ({"axis": "y"}, (("demo_log", 0, 0, False),)),
            ({"axis": "z"}, (("demo_log", 90, 0, False),)),
        ]
    }
    spec = blocks.compile_plant_states(
        "demo_log", raw, ("axis",), lambda stem: True)
    assert spec is not None
    axis = next(prop for prop in spec["properties"] if prop[0] == "axis")
    assert axis[1] == "axis"
    assert axis[2] == "y", "axis 默认值被上游变体顺序带偏了"

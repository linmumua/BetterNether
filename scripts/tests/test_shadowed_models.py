#!/usr/bin/env python3
"""`reports/shadowed-models.txt` 只能列「CE 真的会生成的名字」。

## 这份名单干什么、为什么必须精确

`extract_bn.py` 用它决定**哪些上游模型不释放**。名单多列一个名字，后果不是少一张图，
而是整条引用链断掉：

    models/block/smoker_top.json   被排除（名单里有 smoker_top）
    models/item/smoker.json        parent 是 block/smoker_top，被连带排除（parent_shadowed）
    configuration/blocks:  items.smoker.model -> betternether:item/smoker   ← 指向不存在的文件
                           state.entity_renderer.item -> 同一个物品

2026-10 的真实事故就是这么来的：旧实现按「方块贴图名」推算名单（旧设计让 CE 按贴图名生成
模型），透明共享状态改造后方块只生成自己的名字，那 8 个贴图名永远不会被生成，
名单于是变成纯副作用 —— `betternether:smoker` 的物品和方块都没有外观。

所以要测的不是「函数能跑」，而是：**名单里的每个名字都真的会被 CE 生成**。

运行：`python -m pytest scripts/tests/test_shadowed_models.py`
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "upstream" / "BetterNether"
BLOCKS_SCRIPT = ROOT / "scripts" / "bnlib" / "blocks.py"
NAMESPACE = "betternether"

# 事故里被错列进名单的贴图名（它们都是「贴图名恰好等于某个上游模型文件名」的方块）。
# 写死是**故意的**：哪天有人把按贴图名推算的逻辑加回来，这几条立刻失败。
STALE_NAMES = {
    "black_apple_seed",
    "bone_post",
    "giant_lucis_side",
    "nether_brick_post",
    "nether_reed_fence_side",
    "smoker_top",
    "soul_lily_small",
    "soul_vein_1",
}


@pytest.fixture(scope="module")
def generated(tmp_path_factory) -> Path:
    """跑一次 blocks.py，返回生成目录（`reports/` 与 `configuration/` 都在里面）。"""
    if not UPSTREAM.is_dir():
        pytest.skip(f"找不到上游仓库：{UPSTREAM}")
    out = tmp_path_factory.mktemp("bn-blocks")
    ce_root = out / "craftengine" / NAMESPACE  # reports 落在 out/reports
    subprocess.run(
        [sys.executable, str(BLOCKS_SCRIPT),
         "--upstream", str(UPSTREAM), "--output", str(ce_root)],
        check=True, capture_output=True,
    )
    return out


def _shadowed(generated: Path) -> set[str]:
    path = generated / "reports" / "shadowed-models.txt"
    assert path.is_file(), "blocks.py 没有产出 shadowed-models.txt"
    return {line.strip() for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()}


def _models_ce_generates(generated: Path) -> set[str]:
    """配置里 `path:` 紧跟 `generation:` 的模型名（相对 `block/`）——CE 才会生成它。"""
    generated_models: set[str] = set()
    for path in sorted((generated / "craftengine" / NAMESPACE / "configuration").rglob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}

        def walk(node: object) -> None:
            if isinstance(node, dict):
                if isinstance(node.get("path"), str) and "generation" in node:
                    generated_models.add(node["path"].split(":", 1)[-1][len("block/"):])
                for value in node.values():
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        walk(data)
    return generated_models


def test_shadowed_only_lists_models_ce_actually_generates(generated: Path) -> None:
    """名单 ⊆ 真的会被生成的模型名。

    多列一个名字 = 那个上游模型被丢弃 = 依赖它的子模型全部悬空（smoker 事故）。
    """
    shadowed = _shadowed(generated)
    generates = _models_ce_generates(generated)
    # 自检用已知生成模型，不把可变的发布数量锁死；用户删除台阶/楼梯会合法减少模型数。
    expected_examples = {"bleached_gloomsculk"}
    assert expected_examples <= generates, f"未解析出代表性 CE 生成模型：{sorted(expected_examples - generates)}"
    extra = shadowed - generates
    assert extra == set(), (
        "这些名字在名单里，但 CE 根本不会生成它们（释放上游模型即可，无需避让）："
        f"{sorted(extra)}"
    )


def test_stale_texture_named_entries_are_gone(generated: Path) -> None:
    """按「贴图名」推算出来的那批条目必须消失（smoker_top 正是 smoker 事故的根）。"""
    shadowed = _shadowed(generated)
    assert shadowed & STALE_NAMES == set(), (
        "名单里又出现了按贴图名推算的条目："
        f"{sorted(shadowed & STALE_NAMES)}"
    )


def test_smoker_item_model_is_not_blocked(generated: Path) -> None:
    """`block/smoker_top` 不是生成名，因此不能再挡掉 `item/smoker` 的父级。

    这里只断言「名单不含 smoker_top」；真正有没有释放由 assets.py 决定，
    端到端由 `test_assets.py::test_every_model_parent_resolves` 与 verifyAssets 兜住。
    """
    assert "smoker_top" not in _shadowed(generated)

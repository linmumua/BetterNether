"""blocks 产物级回归测试的共享夹具（分类 / 硬度 / 音效）。

三个测试文件都读**同一份本次生成的产物**：`blocks.py` 跑一次约 7 秒，各文件各跑一遍
只会让全量测试变慢，却测不到额外的东西。所以按 session 作用域生成一次，三边共用。

刻意不用长期存在的 `plugin/build/`：那份产物可能比代码旧，测它就测不出本次改动。
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent
UPSTREAM = ROOT / "upstream/BetterNether"
BLOCKS_SCRIPT = SCRIPTS / "bnlib/blocks.py"
NAMESPACE = "betternether"

if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib.blocks import LANGS_OUTPUT_NAME  # noqa: E402


@pytest.fixture(scope="session")
def blocks_output(tmp_path_factory) -> Path:
    """本次生成的 CE 包根目录（`…/craftengine/betternether`）。"""
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    out = tmp_path_factory.mktemp("bn-blocks")
    ce_root = out / "craftengine" / NAMESPACE
    subprocess.run(
        [sys.executable, str(BLOCKS_SCRIPT), "--upstream", str(UPSTREAM),
         "--output", str(ce_root)],
        check=True, capture_output=True,
    )
    return ce_root


@pytest.fixture(scope="session")
def reports_dir(blocks_output: Path) -> Path:
    """`reports/` 在包外（`craftengine/` 下的一切都会被 CE 当成包内容扫描）。"""
    return blocks_output.parents[1] / "reports"


@pytest.fixture(scope="session")
def block_manifest(reports_dir: Path) -> list[dict]:
    """本次收录的方块清单（含 class / kind / category，是断言的上游侧真值）。"""
    data = json.loads((reports_dir / "block-manifest.json").read_text(encoding="utf-8"))
    return data["blocks"]


@pytest.fixture(scope="session")
def block_configs(blocks_output: Path) -> dict[str, dict]:
    """所有 `configuration/blocks/*.yml` 里的 `blocks:` 段，按 id 合并。"""
    result: dict[str, dict] = {}
    for path in sorted((blocks_output / "configuration/blocks").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        result.update(data.get("blocks") or {})
    return result


@pytest.fixture(scope="session")
def block_item_configs(blocks_output: Path) -> dict[str, dict]:
    """`configuration/blocks/*.yml` 里的 `items:` 段（方块自带的 `block_item` 定义）。

    与 `block_configs` 分开：**物品**标签（`settings.tags`）只能写在这一段，
    配方解析读的是物品注册表。
    """
    result: dict[str, dict] = {}
    for path in sorted((blocks_output / "configuration/blocks").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        result.update(data.get("items") or {})
    return result


@pytest.fixture(scope="session")
def item_configs(blocks_output: Path, block_manifest: list[dict]) -> dict[str, dict]:
    """所有 `configuration/items/*.yml` 里的 `items:` 段，按 id 合并。

    物品配置是「分类到底被谁引用」的真值来源：装备分类的成员全靠 item 的
    `category:` 键推进去，不跑 items.py 就测不到这条链路（与 `test_langs.py` 同一做法）。
    """
    from bnlib import items  # noqa: PLC0415 —— 只在需要物品产物的测试里付导入成本

    items.generate(UPSTREAM, blocks_output, [entry["id"] for entry in block_manifest])
    result: dict[str, dict] = {}
    for path in sorted((blocks_output / "configuration/items").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        result.update(data.get("items") or {})
    return result


@pytest.fixture(scope="session")
def category_config(blocks_output: Path) -> dict[str, dict]:
    path = blocks_output / f"configuration/categories/{NAMESPACE}.yml"
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return data.get("categories") or {}


@pytest.fixture(scope="session")
def category_langs(blocks_output: Path) -> dict[str, dict[str, str]]:
    """唯一语言文件里 `translations#categories_*` 段各自的键值层。

    两个语种现在在**同一个文件**里（各占一个段），CE 的语言码取自段的内层键，
    与文件名无关（见 extract_bn.merge_lang_sections 的说明）。
    """
    path = blocks_output / f"configuration/langs/{LANGS_OUTPUT_NAME}"
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    sections = [value for key, value in data.items() if key.startswith("translations#")]
    assert sections, f"{path.name} 里没有任何 translations# 段，实际：{list(data)}"
    result: dict[str, dict[str, str]] = {}
    for code in ("en_us", "zh_cn"):
        layer = next((section[code] for section in sections if code in section), None)
        assert layer is not None, f"{path.name} 缺少 {code} 的分类译名层"
        result[code] = layer
    return result

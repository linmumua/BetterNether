#!/usr/bin/env python3
"""结构 NBT 的座椅剥离：座椅必须无残余，其它调色板条目必须一字不动。

## 为什么要有这组测试

用户明确**不发布座椅**（`chair` / `taburet` / `bar_stool`：模型来自 bclib，本包不释放父模型），
但上游结构模板的 palette 里仍然引用它们。jigsaw 装配时找不到定义的方块会被**静默跳过** ——
玩家看到的是一座缺了椅子的建筑，日志毫无痕迹；`tools/verify_structures.py` 也会判
「未注册调色板方块」。

修法是在转换阶段把它们显式映射成 `minecraft:air` 并删掉 properties。风险有两个方向，
所以正反都要钉住：

  * **没删干净** —— 门禁继续红，建筑继续缺椅子（`test_source_fragments_have_no_seats`）；
  * **删过头** —— palette 索引重排或状态被顺手改写，会让整段建筑的方块错位
    （`test_other_palette_entries_are_untouched`、`test_blocks_indices_are_unchanged`）。

运行：`python -m pytest scripts/tests/test_structure_seats.py`
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import nbt  # noqa: E402
from bnlib import structures  # noqa: E402
from bnlib.blocks import is_seat  # noqa: E402

SOURCE_PACK = ROOT / "plugin/src/main/resources/datapacks/betternether-structures"
CONVERTER = ROOT / "scripts/convert_structure_seats.py"

SEAT_ID = "betternether:stalagnate_taburet"
STONE_ID = "betternether:cincinnasite_bricks"


# ---------------------------------------------------------------- 造样本


def _tree(entries, blocks):
    """拼一个结构模板树：`entries` 是 (id, properties|None)，`blocks` 是 palette 索引。"""
    palette = []
    for ident, properties in entries:
        fields = {"id": (nbt.TAG_STRING, ident)}
        if properties is not None:
            fields["properties"] = (
                nbt.TAG_COMPOUND,
                {key: (nbt.TAG_STRING, value) for key, value in properties.items()},
            )
        palette.append((nbt.TAG_COMPOUND, fields))
    return (nbt.TAG_COMPOUND, {
        "size": (nbt.TAG_LIST, (nbt.TAG_INT, [(nbt.TAG_INT, 3)] * 3)),
        "palette": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, palette)),
        "blocks": (nbt.TAG_LIST, (nbt.TAG_COMPOUND, [
            (nbt.TAG_COMPOUND, {
                "pos": (nbt.TAG_LIST, (nbt.TAG_INT, [
                    (nbt.TAG_INT, x), (nbt.TAG_INT, 0), (nbt.TAG_INT, 0),
                ])),
                "state": (nbt.TAG_INT, index),
            })
            for x, index in enumerate(blocks)
        ])),
    })


def _write(path: Path, tree) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    nbt.write_file(path, "", tree[0], tree[1])


def _palette(tree):
    return [fields for _, fields in tree[1]["palette"][1][1]]


def _states(tree):
    return [fields["state"][1] for _, fields in tree[1]["blocks"][1][1]]


# ---------------------------------------------------------------- 合成样本


def test_seat_becomes_air_without_properties(tmp_path: Path) -> None:
    """座椅条目 -> `minecraft:air`，properties 必须消失（air 没有方块状态）。"""
    path = tmp_path / "seat.nbt"
    _write(path, _tree(
        [(STONE_ID, None), (SEAT_ID, {"facing": "north", "waterlogged": "false"})],
        [0, 1],
    ))

    totals = structures.strip_source_fragments(tmp_path, is_seat)
    assert totals["palette_entries"] == 1
    assert totals["by_block"] == {SEAT_ID: 1}
    assert totals["placements"] == 1

    _, root_tag, payload = nbt.read_file(path)
    entry = _palette((root_tag, payload))[1]
    assert entry["id"][1] == structures.SEAT_REPLACEMENT_ID
    assert "properties" not in entry and "Properties" not in entry


def test_other_palette_entries_are_untouched(tmp_path: Path) -> None:
    """非座椅条目逐字段保持原样，palette 顺序与长度不变。"""
    path = tmp_path / "mixed.nbt"
    original = _tree(
        [
            (STONE_ID, None),
            (SEAT_ID, {"facing": "east"}),
            ("minecraft:oak_stairs", {"facing": "north", "half": "bottom"}),
        ],
        [0, 1, 2],
    )
    _write(path, original)
    before = _palette(original)

    structures.strip_source_fragments(tmp_path, is_seat)

    _, root_tag, payload = nbt.read_file(path)
    after = _palette((root_tag, payload))
    assert len(after) == len(before), "palette 长度变了，索引会被整体错位"
    for index in (0, 2):
        assert after[index] == before[index], f"第 {index} 个条目不是座椅，却被改动了"


def test_blocks_indices_are_unchanged(tmp_path: Path) -> None:
    """`blocks` 的坐标与 palette 索引不动：座椅位置变成空气，而不是让别的方块顶上来。

    如果实现改成「删掉 blocks 条目」，位置会空出来、后续方块可能被顶替；这里锁住索引不变。
    """
    path = tmp_path / "indices.nbt"
    _write(path, _tree([(STONE_ID, None), (SEAT_ID, None)], [0, 1, 0, 1]))

    structures.strip_source_fragments(tmp_path, is_seat)

    _, root_tag, payload = nbt.read_file(path)
    assert _states((root_tag, payload)) == [0, 1, 0, 1]


def test_conversion_is_idempotent(tmp_path: Path) -> None:
    """再跑一次必须什么都不做（没有命中时不重建树、不写文件）。"""
    path = tmp_path / "seat.nbt"
    _write(path, _tree([(SEAT_ID, {"facing": "north"})], [0]))

    structures.strip_source_fragments(tmp_path, is_seat)
    digest = path.read_bytes()
    totals = structures.strip_source_fragments(tmp_path, is_seat)

    assert totals["palette_entries"] == 0 and totals["files"] == []
    assert path.read_bytes() == digest


def test_templates_without_seats_are_not_rewritten(tmp_path: Path) -> None:
    """没有座椅的模板一个字节都不能动（避免无谓重排、避免误伤）。"""
    with_seat = tmp_path / "a" / "seat.nbt"
    clean = tmp_path / "b" / "clean.nbt"
    _write(with_seat, _tree([(SEAT_ID, None)], [0]))
    _write(clean, _tree([(STONE_ID, None), ("minecraft:air", None)], [0, 1]))
    clean_digest = clean.read_bytes()

    totals = structures.strip_source_fragments(tmp_path, is_seat)

    assert [entry["file"] for entry in totals["files"]] == ["a/seat.nbt"]
    assert clean.read_bytes() == clean_digest


# ---------------------------------------------------------------- 构建阶段接线


def test_build_stage_strips_seats(tmp_path: Path) -> None:
    """`extract_bn.build_structure_pack()` 这条构建路径同样必须剥离座椅。

    单独测一次接线：将来若有人只在迁移脚本里改，构建产物会悄悄把座椅带回资源包。
    """
    import extract_bn  # noqa: PLC0415

    upstream = tmp_path / "upstream"
    fragment = upstream / "src/main/resources/data/betternether/structure/city/city_probe.nbt"
    _write(fragment, _tree(
        [(STONE_ID, None), ("betternether:nether_mushroom_chair", {"facing": "south"})],
        [0, 1, 1],
    ))
    out = tmp_path / "out"

    report = extract_bn.build_structure_pack(upstream, out)

    assert report["seat_palette_entries"] == 1
    assert report["seat_placements"] == 2, "两处放置都引用了那把椅子"

    written = out / ("datapacks/betternether-structures/data/betternether/"
                     "structure/nether_city/city_probe.nbt")
    _, root_tag, payload = nbt.read_file(written)
    entry = _palette((root_tag, payload))[1]
    assert entry["id"][1] == structures.SEAT_REPLACEMENT_ID
    assert "properties" not in entry


# ---------------------------------------------------------------- 真实源片段


@pytest.mark.skipif(not SOURCE_PACK.is_dir(), reason="结构源目录不存在")
def test_source_fragments_have_no_seats() -> None:
    """jar 直接取用的 `plugin/src` 片段里不得残留任何座椅调色板条目。"""
    files = sorted(SOURCE_PACK.rglob("*.nbt"))
    assert len(files) == 67, f"结构片段数量变了（{len(files)}），确认是否漏拷"

    residual = []
    for path in files:
        _, root_tag, payload = nbt.read_file(path)
        for fields in _palette((root_tag, payload)):
            ident = (fields.get("id") or fields.get("Name"))[1]
            local = ident.split(":", 1)[1] if ":" in ident else ident
            if is_seat(local):
                residual.append(f"{path.name}: {ident}")
    assert residual == [], "座椅仍在调色板里：\n" + "\n".join(residual[:10])


@pytest.mark.skipif(not SOURCE_PACK.is_dir(), reason="结构源目录不存在")
def test_converter_check_passes_on_source() -> None:
    """迁移脚本的 `--check` 必须为绿（有残留则退出码 1）。"""
    result = subprocess.run(
        [sys.executable, str(CONVERTER), "--check"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "没有座椅残留" in result.stdout


def test_converter_check_fails_on_residual(tmp_path: Path) -> None:
    """`--check` 对仍有座椅的目录必须非零退出 —— 否则门禁会假绿。"""
    _write(tmp_path / "p" / "seat.nbt", _tree([(SEAT_ID, None)], [0]))
    result = subprocess.run(
        [sys.executable, str(CONVERTER), "--check", "--root", str(tmp_path / "p")],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    assert result.returncode == 1
    assert SEAT_ID in result.stdout


def test_converter_check_rejects_empty_directory(tmp_path: Path) -> None:
    """空目录也要失败：rglob 出 0 个文件时「无残留」是假绿。"""
    empty = tmp_path / "empty"
    empty.mkdir()
    result = subprocess.run(
        [sys.executable, str(CONVERTER), "--check", "--root", str(empty)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )
    assert result.returncode != 0

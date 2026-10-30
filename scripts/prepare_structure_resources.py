#!/usr/bin/env python3
"""仅处理 processResources 的副本；原始结构 NBT 不改，最终包不留已移除的自定义方块。

同时负责**结构 id 的发布布局**（见 `bnlib/public_structures.py`）：从本包自己的
`data/betternether/worldgen/structure/*.json` 复制出 `data/mumu/worldgen/structure/*.json`，
把旧命名空间的 `structure_set` 搬成 `data/mumu/worldgen/structure_set/*.json` 并删掉旧的
那一份。复制件就是发布件，源目录不必再维护第二份会漂移的 JSON。
"""

from __future__ import annotations

import argparse
from pathlib import Path

from bnlib import nbt, public_structures, structures


def prepare(root: Path) -> dict:
    """把资源输出中的全部结构按发布规则重写，保留索引与结构数量。"""
    structure_root = root / "datapacks/betternether-structures/data/betternether/structure"
    if not structure_root.is_dir():
        raise ValueError(f"找不到已复制的结构资源：{structure_root}")
    totals = structures.empty_stats()
    for path in sorted(structure_root.rglob("*.nbt")):
        root_name, tag, payload = nbt.read_file(path)
        tree, stats = structures.replace_removed_palette_blocks((tag, payload))
        if stats["templates"]:
            nbt.write_file(path, root_name, tree[0], tree[1])
            structures.merge_stats(totals, stats)
    return totals


def publish(root: Path) -> dict:
    """发布公开结构 id 与唯一一份放置集（只作用于输出副本）。"""
    return public_structures.publish(root / public_structures.PACK_DIR)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path, help="processResources 的输出目录")
    args = parser.parse_args()
    totals = prepare(args.root)
    print(f"[结构发布] 替换用户移除方块 {totals['placements']} 处，"
          f"涉及模板 {totals['templates']} 个；源 NBT 未修改。")
    print(f"[结构发布] 清理残留：方块实体数据 {totals.get('dropped_entity_data', 0)} 处、"
          f"已移除实体 {totals.get('dropped_entities', 0)} 条"
          f"（forge/furnace/chest_of_drawers/brewing_stand、bclib:chair 等）。")
    report = publish(args.root)
    print(f"[结构发布] 公开结构 {public_structures.PUBLIC_STRUCTURE_NAMESPACE}: "
          f"{report['structures']} 个定义，{report['structure_sets']} 份放置集，"
          f"改写放置引用 {report['rewritten_refs']} 处；"
          f"旧命名空间 {public_structures.LEGACY_STRUCTURE_NAMESPACE} 的定义保留、不再拥有放置。")


if __name__ == "__main__":
    main()

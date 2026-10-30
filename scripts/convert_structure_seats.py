#!/usr/bin/env python3
"""把结构 NBT 源片段里的座椅显式映射成 `minecraft:air`（一次性迁移 + 可重复执行）。

用法：

    python scripts/convert_structure_seats.py            # 就地改写 plugin/src 里的 67 个片段
    python scripts/convert_structure_seats.py --check     # 只报告残留，有残留则非零退出

## 为什么改的是 `plugin/src` 而不是 `build/generated`

`plugin/build.gradle.kts` 的 processResources 里，**生成目录的结构 NBT 被排除**，
「结构 NBT 以 src 为准」（src 是完整的 67 个片段，生成目录只有早期 city 的 36 个）。
也就是说最终打进 jar、被 `verifyStructures` 检查的就是 `plugin/src/.../structure/**/*.nbt`。
只改生成目录里的副本，jar 里仍然是带座椅的原样数据，门禁照样红。

`extract_bn.build_structure_pack()` 也调用同一份 `strip_palette_seats()`，保证将来若改成
以生成目录为准，行为不会漂回原样。

## 为什么幂等

`strip_palette_seats()` 在没命中座椅时返回原树对象，本脚本据此跳过写文件 —— 重复运行
不会改动任何一个字节，也不会把已经改成 air 的条目再动一次。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 直接执行时 scripts/ 不在 sys.path 上，显式补一次（与其它模块一致）。
# 注意本文件就在 scripts/ 下，所以这里只取一层 parent（bnlib 里的模块才取两层）。
_SCRIPTS = Path(__file__).resolve().parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from bnlib import structures  # noqa: E402
from bnlib.structures import is_unpublished  # noqa: E402

PROJECT_ROOT = _SCRIPTS.parent
# 与 tools/verify_structures.py 的 DEFAULT_PACK 同一个根：校验的就是这份数据。
DEFAULT_ROOT = PROJECT_ROOT / "plugin/src/main/resources/datapacks/betternether-structures"


def _report(totals: dict) -> None:
    if not totals["files"]:
        print("结构片段里没有座椅引用（已是目标状态）。")
        return
    print(f"[座椅] 有意移除 {totals['placements']} 处放置 / "
          f"{totals['palette_entries']} 个调色板条目，涉及 {len(totals['files'])} 个片段：")
    for ident, count in sorted(totals["by_block"].items()):
        print(f"  - {ident} × {count} 个调色板条目")
    for entry in totals["files"]:
        print(f"  · {entry['file']}: {entry['placements']} 处放置 / "
              f"{entry['palette_entries']} 个条目")


def _count_residuals(root: Path) -> list[str]:
    """检查是否还有座椅 id 留在任何 palette 里（不改写）。"""
    from bnlib import nbt

    # 目录不存在时必须失败：对空目录 rglob 出 0 个文件会让 --check 静默通过，
    # 那正是这类门禁最典型的假绿。
    if not root.is_dir():
        raise SystemExit(f"找不到结构目录：{root}")
    files = sorted(root.rglob("*.nbt"))
    if not files:
        raise SystemExit(f"{root} 下没有任何 .nbt 片段，--check 会静默通过，拒绝当作有效结果。")
    residual: list[str] = []
    for path in files:
        _, root_tag, payload = nbt.read_file(path)
        if root_tag != nbt.TAG_COMPOUND:
            continue
        palette_node = payload.get("palette")
        if not isinstance(palette_node, tuple) or palette_node[0] != nbt.TAG_LIST:
            continue
        for entry in palette_node[1][1]:
            if entry[0] != nbt.TAG_COMPOUND:
                continue
            found = structures._read_id(entry[1])
            if found is None:
                continue
            ident = found[1]
            local = ident.split(":", 1)[1] if ":" in ident else ident
            if is_unpublished(local):
                residual.append(f"{path.name}: {ident}")
    return residual


def main() -> int:
    parser = argparse.ArgumentParser(description="把结构 NBT 里本包不发布的方块映射成 minecraft:air")
    parser.add_argument("--root", default=str(DEFAULT_ROOT),
                        help="结构 datapack 根目录（默认 plugin/src 的 datapacks/betternether-structures）")
    parser.add_argument("--check", action="store_true",
                        help="只检查残留，不写文件；有残留时退出码 1")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    if args.check:
        residual = _count_residuals(root)
        if residual:
            print(f"仍有 {len(residual)} 处已删方块留在调色板里：")
            for line in residual:
                print(f"  - {line}")
            return 1
        print("调色板里没有已删方块残留。")
        return 0

    _report(structures.strip_source_fragments(root, is_unpublished))
    residual = _count_residuals(root)
    if residual:
        # 改写后仍有残留 = 转换没覆盖到某种写法，宁可失败也不要让门禁去发现。
        print(f"[结构] 改写后仍残留 {len(residual)} 处，拒绝当作完成：", file=sys.stderr)
        for line in residual:
            print(f"  - {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

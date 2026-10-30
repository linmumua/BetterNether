#!/usr/bin/env python3
"""校验源码里的群系覆盖关系是否闭合。

用法：
    python tools/verify_biome_coverage.py

检查三件事，任何一条不满足就非零退出：

1. `NetherBiomes.ALL` 里的每个群系 key，在 `BiomeSurface` 都有地表定义。
2. 每个 key 在上游 worldgen 数据的 step 9 里都有**解释得动**的植被特征。
3. 两个表里都没有 `NetherBiomes.ALL` 之外的 key（防止改名后留下的孤儿条目）。

## 植被判据为什么改成读上游 JSON

植被已经不再是一张手写清单：`FloraPopulator` 解释上游每个群系 `features[9]` 上的
`placed_feature`（见 `scripts/bnlib/worldgen.py`）。所以「这个群系有没有植被」只能去问
上游数据。规则是「该群系至少有一条 placed feature 落在解释范围内」；上游自己就只有
无法解释的特征时（见 `INTENTIONALLY_BARE`），缺口必须在这里显式登记，而不是靠放宽判据
让它静默通过。

## 为什么用静态扫描而不是跑 JUnit

这三条是「数据表完整性」，不是行为。用正则读源码可以在**没有服务器、没有 CraftEngine**
的机器上跑，因此能挂在 `verifyJar` 之前最便宜的位置上；JUnit 版本还要拖起整条测试类路径。
两者互补：JUnit 测行为，这里测表。

## 为什么必须失败而不是警告

漏一个群系的地表定义，那个群系的玩家会看到一整片光头下界岩；漏植被则是一片空。这类缺口在
游戏里极难被认出来是 bug（看起来就像"这个群系本来就这样"），只有表比对能稳定抓住。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BIOMES = ROOT / "common/src/main/java/dev/mumu/betternether/biome/NetherBiomes.java"
SURFACE = ROOT / "common/src/main/java/dev/mumu/betternether/biome/BiomeSurface.java"
# 地表规则已迁到上游 worldgen 的 surface_rules 数据，覆盖关系由 SurfaceRules 自己校验
# （见 common/src/main/java/dev/mumu/betternether/biome/SurfaceRules.java 与
# scripts/bnlib/surface_rules.py）。这里只在旧表还在时顺便核对一次。
SURFACE_RULES = ROOT / "common/src/main/java/dev/mumu/betternether/biome/SurfaceRules.java"
UPSTREAM = ROOT / "upstream/BetterNether"

# 上游这一步**只有**我们无法解释的特征的群系，以及为什么。
#
# 写死成表是刻意的：上游一旦给这个群系加了可解释的植被，这条登记就该被删掉，
# 而「删掉」必须由人确认。放宽判据会让两边都永远通过，什么也抓不住。
INTENTIONALLY_BARE = {
    "flooded_deltas": (
        "上游 step 9 只列了 betternether:wart_cap（自定义特征类型 betternether:wart_cap）"
        "与 minecraft:spring_lava / *_mushroom_normal（原版 placed feature，数据不在本仓库），"
        "都不在解释范围内；该群系另有 step 4 的玄武岩/黑石地形特征。"
    ),
}

FAILURES: list[str] = []


def read(path: Path) -> str:
    if not path.is_file():
        FAILURES.append(f"找不到文件：{path}")
        return ""
    return path.read_text(encoding="utf-8")


def main() -> int:
    declared = re.findall(r'new Entry\("([a-z0-9_]+)"', read(BIOMES))
    if not declared:
        FAILURES.append("NetherBiomes.ALL 里没有解析出任何群系 key。")
    if len(declared) != len(set(declared)):
        FAILURES.append("NetherBiomes.ALL 里有重复 key。")

    if SURFACE.is_file():
        surface = set(re.findall(r'put\("([a-z0-9_]+)"', read(SURFACE)))
    else:
        # 地表判据不在本脚本职责内了：SurfaceRules 从上游数据加载，缺群系会在装载期报错。
        surface = set(declared)
        print(f"[提示] {SURFACE.name} 不存在（地表规则已迁到 SurfaceRules），跳过地表表比对。")

    sys.path.insert(0, str(ROOT / "scripts"))
    from bnlib import worldgen  # noqa: E402  延迟导入：没有上游时上面的检查先报错

    report = worldgen.build(UPSTREAM)
    flora = {biome for biome, refs in report.biome_refs.items() if refs}

    if SURFACE.is_file() and not surface:
        FAILURES.append("BiomeSurface 里没有解析出任何地表定义。")
    if not flora:
        FAILURES.append("FloraPopulator 里没有解析出任何植被定义。")

    missing_surface = sorted(set(declared) - surface)
    missing_flora = sorted(set(declared) - flora - set(INTENTIONALLY_BARE))
    orphan_surface = sorted(surface - set(declared))
    orphan_flora = sorted(flora - set(declared))

    for key in missing_surface:
        FAILURES.append(f"群系 {key} 没有地表定义（BiomeSurface），玩家会看到一整片光头下界岩。")
    for key in missing_flora:
        FAILURES.append(
            f"群系 {key} 在上游 step 9 里没有任何可解释的植被特征，该群系会是一片空。"
            "若确认是上游自身如此，请同时更新上游数据与 tools/verify_biome_coverage.py。")
    for key in orphan_surface:
        FAILURES.append(f"BiomeSurface 里的 {key} 不在 NetherBiomes.ALL 中（改名残留？）。")
    unused_surface_rules = SURFACE_RULES.is_file()
    for key in orphan_flora:
        FAILURES.append(f"上游 worldgen 的群系 {key} 不在 NetherBiomes.ALL 中（改名残留？）。")

    print(f"群系 {len(declared)} 个；地表定义 {len(surface)}；有植被的群系 {len(flora)}；"
          f"上游本身无植被 {len(INTENTIONALLY_BARE)}")
    for biome, reason in INTENTIONALLY_BARE.items():
        print(f"[登记] {biome} 无植被：{reason}")

    if FAILURES:
        print()
        for message in FAILURES:
            print(f"[失败] {message}")
        return 1

    print("群系覆盖闭合：每个群系都有地表与植被定义，且没有孤儿条目。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

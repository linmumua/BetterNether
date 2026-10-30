"""地形 placed feature 的导出：与 `worldgen.py` 的植被导出并列，互不覆盖。

## step 下标：按上游数据，不按「这类特征本该在哪一步」推

`GenerationStep.Decoration` 的枚举序号（`javap -p -c` 读过 26.3 的
`GenerationStep$Decoration.<clinit>`）是：

```
0 raw_generation   1 lakes            2 local_modifications   3 underground_structures
4 surface_structures 5 strongholds     6 underground_ores      7 underground_decoration
8 fluid_springs     9 vegetal_decoration 10 top_layer_modification
```

但**上游把这些特征真正写进了哪一格**要按 23 个群系 JSON 的实际下标统计，不能按名称推：

| index | 出现在该格的地形项 |
|------:|--------------------|
| 0 | `replace_soul_sandstone`（3 群系） |
| 1 | `lava_pits_dense/sparse`、`lava_swamp`、`lava_terrace`、`flooded_lava_pit`、`magma_blob`、`molten_gloomsculk*`、`gloomsculk_geode_*` |
| 4 | `patch_stalactite`(17)、`patch_stalagmite`(13)、`patch_basalt_*`、`patch_blackstone_*`、`patch_bone_stalagmite_on_ground`、`obsidian_crystal`、`bones`、`jungle_bones`、`forest_litter`、`patch_smoker` |
| 7 | 矿（`cincinnasite_ore` 21 等）+ `gloomwood_ceiling_debris` |
| 9 | `sculk_vein_floor/wall/ceiling` + 全部植被 |

也就是说上游把尖锥/骨堆放在 **4 = surface_structures**、把矿放在 **7 = underground_decoration**。
种子是 `setFeatureSeed(seed, deco + index, 10000L * step)`，**step 必须就是数据里的那个下标**
（4 就是 4），改成「本意上的 7」反而会把整条随机流改掉。

## 为什么单独一份

`worldgen.py` 只读群系 `features[9]`（vegetal_decoration），因为植被全在那一步。
地形的重头在**别的步**：

| step | 名称 | 地形内容 |
|-----:|------|----------|
| 0 | `raw_generation` | `replace_soul_sandstone` |
| 1 | `lakes` | 熔岩坑（dense/sparse/swamp/terrace）、`flooded_lava_pit`、`magma_blob`、`molten_gloomsculk(_near_lava)`、`gloomsculk_geode_*` |
| 4 | `underground_decoration` | 尖锥/石笋（netherrack/basalt/blackstone/bone）、`obsidian_crystal`、`bones`/`jungle_bones`/`forest_litter` |
| 9 | `vegetal_decoration` | 只有 `sculk_vein_floor/wall/ceiling` 属于地形 |

所以这里按「步白名单 + step 9 前缀白名单」取，写进 **`terrain.json`**（与植被的
`manifest.json` 分开两份）：两个 populator 各读各的，既不会漏也不会双放。

## 明确不导出的

* **step 7 `underground_ores`**：`cincinnasite_ore` / `nether_ruby_*` / `nether_lapis_ore` /
  `nether_redstone_ore` 以及 `gloomwood_ceiling_debris` 都是 `minecraft:ore` 配置特征，
  归 `OrePopulator` 那条线；这里再放一遍就是翻倍。
* **step 9 的植被/树**：已经在 `manifest.json` 里。

## 复用而不是重写

递归引用收集（`_collect_references`）、NBT 模板转换（`convert_template`）、方块 tag 解析
（`resolve_block_tags`）全部直接调 `bnlib.worldgen` 里已有的实现 —— 同一件事只有一份代码，
不会出现「地形侧的模板转换少读一个 TAG」这种分叉。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from . import worldgen as wg

#: 地形的三个主步 —— **就是上游 biome JSON 里的真实下标**（见模块文档的统计表）。
TERRAIN_STEPS: tuple[int, ...] = (0, 1, 4)

#: step 9 里唯一属于地形的条目（`NetherTerrainPlaced.SCULK_VEIN_*`，装饰步但放的是方块）。
STEP9_TERRAIN_PREFIXES: tuple[str, ...] = ("betternether:sculk_vein",)

#: 输出清单名（`/worldgen-upstream/terrain.json`，与 `TerrainFeaturePopulator.MANIFEST` 一致）。
MANIFEST_NAME = "terrain.json"

#: 结构模板根。**不能**复用 `worldgen.template_path`：那个只认 `trees/` 前缀，
#: 而地形的 `bones` / `jungle_bones` / `forest_litter` 用的是 `bone_01`、
#: `jungle_bones_1`、`upside_down_forest/tree_fallen` 这些**不在 `trees/` 下**的位置。
STRUCTURE_ROOT = "src/main/resources/data/betternether/structure"


def template_path(upstream: Path, location: str) -> tuple[str, Path]:
    """`betternether:bone_01` → (`bone_01`, `.../structure/bone_01.nbt`)。

    返回的相对名同时决定输出位置（`templates/<name>.json`），与
    `WorldgenLoader#template` 里 `RESOURCE_ROOT + "/templates/" + name + ".json"` 对齐。
    """
    name = location.split(":", 1)[1]
    return name, upstream / STRUCTURE_ROOT / f"{name}.nbt"

STEP_NAMES = {
    0: "raw_generation",
    1: "lakes",
    4: "underground_decoration",
    7: "underground_ores",
    9: "vegetal_decoration",
}


def biome_steps(data: wg.WorldgenData) -> dict[str, dict[str, list[str]]]:
    """每个群系的「步 → placed feature 列表」。步键升序，列表保持上游顺序。

    **不在这里分配 index**：全局 index 是 FeatureSorter 的拓扑顺序，同一 step 内 flora 与本脚本
    的条目共享同一个下标空间，两边各算一套必然错位。调用方（Java 侧调度器）按
    「该 biome 的 features[step] 顺序」统一分配。
    """
    out: dict[str, dict[str, list[str]]] = {}
    for path in sorted(data.biome_dir.glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        steps = raw.get("features") or []
        per_step: dict[str, list[str]] = {}
        for step in TERRAIN_STEPS:
            if step >= len(steps):
                continue
            kept: list[str] = []
            for ref in steps[step]:
                ident = str(ref)
                if ident in kept or data.placed(ident) is None:
                    continue
                kept.append(ident)
            if kept:
                per_step[str(step)] = kept
        if len(steps) > 9:
            kept = []
            for ref in steps[9]:
                ident = str(ref)
                if not ident.startswith(STEP9_TERRAIN_PREFIXES) or ident in kept:
                    continue
                if data.placed(ident) is None:
                    continue
                kept.append(ident)
            if kept:
                per_step["9"] = kept
        out[path.stem] = per_step
    return out


def biome_refs(data: wg.WorldgenData) -> dict[str, list[str]]:
    """每个群系的地形 placed feature，顺序 = (step 升序, step 内上游列表顺序)。

    顺序不能改：`molten_gloomsculk_near_lava` 的注释明确要求它排在
    `lava_pits_sparse` 之后（它贴在已挖好的坑边），而 `patch_blackstone_*` 的
    `terracotta_clump` → `random_patch(pillar)` 是同一个 `wover:sequence` 内的次序。
    """
    out: dict[str, list[str]] = {}
    for path in sorted(data.biome_dir.glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        steps = raw.get("features") or []
        kept: list[str] = []
        for step in TERRAIN_STEPS:
            if step < len(steps):
                kept.extend(str(ref) for ref in steps[step])
        if len(steps) > 9:
            kept.extend(str(ref) for ref in steps[9]
                        if str(ref).startswith(STEP9_TERRAIN_PREFIXES))
        deduped: list[str] = []
        for ref in kept:
            if ref in deduped:
                continue
            if data.placed(ref) is None:
                # 上游没有这个 placed_feature 文件（原版 id 之类），交给装载器也编译不出来。
                continue
            deduped.append(ref)
        out[path.stem] = deduped
    return out


def template_refs(data: wg.WorldgenData, supported: list[str]) -> dict[str, str]:
    """支持的 placed feature 里 `wover:template` 引用到的模板 → 输出相对路径。"""
    found: dict[str, str] = {}

    def walk(node: Any, depth: int) -> None:
        if depth > 12 or not isinstance(node, dict):
            return
        if node.get("type") == "wover:template":
            items = (node.get("structures") or {}).get("items") or []
            for item in items:
                value = (item or {}).get("value") or {}
                location = value.get("location")
                if isinstance(location, str):
                    name = location.split(":", 1)[1]
                    found.setdefault(location, f"templates/{name}.json")
        if "feature" in node:
            walk(node["feature"], depth + 1)
        subs = node.get("features")
        if isinstance(subs, list):
            for sub in subs:
                walk(sub, depth + 1)
        elif isinstance(subs, dict):
            walk(subs, depth + 1)

    for ref in supported:
        placed = data.placed(ref)
        if isinstance(placed, dict):
            walk(placed, 0)
    return found


def write(upstream: Path, out: Path) -> dict[str, object]:
    """把地形 placed/configured 特征、模板与清单写到 `out/worldgen-upstream/`。"""
    data = wg.WorldgenData(upstream)
    steps = biome_steps(data)
    refs = {biome: [ref for entries in per_step.values() for ref in entries]
            for biome, per_step in steps.items()}

    supported: list[str] = []
    for biome in refs.values():
        for ref in biome:
            if ref not in supported:
                supported.append(ref)

    # 方块被用户移除后，指向它们的特征不能再交给运行期编译。
    # 这里与植被侧（`bnlib.worldgen` 的 `removed_placement_reason`）用**同一个判据**：
    # 结构性写死引用 / 加权候选被摘空 / 算法型特征 → 整条停发。
    # 漏了这一步的后果是实测到的：`patch_stalactite`、`patch_basalt_stalactite`、
    # `patch_smoker` 等特征仍会被编译，编译期解析区块方块（4 种钟乳石、`smoker`）失败，
    # 装载器于是报「CraftEngine 包缺少 6 个世界生成方块 id」—— 而那些方块是**按要求移除**的，
    # 不是缺口。它们同时仍留在 `biomes` 里占位（保 featureIndex），只是不再进 `supported`。
    stopped: dict[str, str] = {}
    kept_supported: list[str] = []
    for ref in supported:
        if ref.startswith("minecraft:"):
            kept_supported.append(ref)
            continue
        reason = wg.removed_placement_reason(data, ref)
        if reason:
            stopped[ref] = reason
            continue
        kept_supported.append(ref)
    supported = kept_supported

    report = wg.Report(biome_refs=refs, supported=supported)
    wg._collect_references(data, supported, report)
    report.templates = template_refs(data, supported)

    used: set[str] = set()
    for ref in supported:
        wg._used_tags(data, data.placed(ref), used)
    report.block_tags = wg.resolve_block_tags(upstream, used)

    root = out / wg.OUTPUT_DIR
    written: list[str] = []
    copies: list[tuple[str, Path]] = []
    for ident, relative in report.placed_files.items():
        copies.append((relative, data.placed_dir / f"{ident.split(':', 1)[1]}.json"))
    for ident, relative in report.feature_files.items():
        copies.append((relative, data.feature_dir / f"{ident.split(':', 1)[1]}.json"))
    for relative, source in sorted(copies):
        if not source.is_file():
            raise SystemExit(f"{relative}: 上游缺 {source}，拒绝写一份引用悬空的数据。")
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(
            json.dumps(json.loads(source.read_text(encoding="utf-8")),
                       ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8")
        written.append(relative)

    for location, relative in sorted(report.templates.items()):
        _, source = template_path(upstream, location)
        if not source.is_file():
            raise SystemExit(f"模板 {location} 在上游找不到：{source}")
        payload = wg.convert_template(source)
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
                          encoding="utf-8")
        written.append(relative)

    manifest: dict[str, Any] = {
        # 新 schema：{群系: {步: [placed feature…]}}，步键升序、步内保持上游顺序。
        "biomes": steps,
        # 过渡期兼容：等 Java 侧改成读 `biomes[biome][step]` 后删掉这张扁平表。
        "biomesFlat": refs,
        "supported": supported,
        # 主动停发的特征（方块已按用户要求移除，编译也放不出东西）。
        # 与 `supported` 互斥；写出来是为了让「缺口」和「主动停发」可区分 ——
        # 单看 `supported` 少了谁，分不清是漏了还是本来就不该有。
        "excluded": stopped,
        "blockTags": report.block_tags,
        "placedFeatures": report.placed_files,
        "features": report.feature_files,
        "templates": report.templates,
    }
    manifest_path = root / MANIFEST_NAME
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
    written.append(f"{wg.OUTPUT_DIR}/{MANIFEST_NAME}")

    return {
        "files": len(written),
        "written": written,
        "supported": len(supported),
        "biomes": sum(len(entries) for entries in refs.values()),
        "templates": len(report.templates),
    }


def main(argv: list[str] | None = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(description="编译上游 worldgen 地形数据")
    parser.add_argument("--upstream", required=True, help="上游 BetterNether 源码根")
    parser.add_argument("--output", required=True, help="输出根目录（jar 资源根）")
    args = parser.parse_args(argv)

    result = write(Path(args.upstream), Path(args.output))
    print(f"[地形] 导出 {result['supported']} 条 placed feature（群系引用 {result['biomes']} 处），"
          f"模板 {result['templates']} 个，写出 {result['files']} 个文件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""把上游的 surface_rules JSON 原样抄进生成产物，供 Java 端解释执行。

## 为什么抄 JSON 而不是继续手抄一张表

`common/.../biome/BiomeSurface.java` 原先是一张「每个群系 → 顶部/亚表层/天花板 + 占比」的表，
由人从上游 Java 的 `surface()` 里读出来。那张表自己在 javadoc 里列了三件表达不了的事：噪声成块、
精确占比、`stoneDepthCheck` 的深层亚表层。上游自己已经把规则编译成 JSON 落在
`worldgen/surface_rules/<biome>.json`，直接搬过来解释执行，比再抄一遍强。现在这套规则由
`common/.../biome/SurfaceRules.java` 直接解释执行这 23 个 JSON，所以这里只负责**原样搬运**，
不做任何数值改写（不要在这里换算 scale、阈值或种子）。

## 解释端的语义来源（改动这里的 JSON 前请先读）

`SurfaceRules.java` 的每个节点都是照源码抄的，不是照观感调的：

* 原版侧（Minecraft 26.3 的 `material` 规则系统，用 `javap -c` 从服务端 jar 读出）：
  `stone_depth` 是 `depth <= 1 + offset + surface + secondary`（depth 是 **1 基**计数），
  `hole` 是 `surfaceDepth() <= 0`，`vertical_gradient` 用 positional factory 抽 `nextFloat()`，
  `noise_threshold` 用世界自己的 `NormalNoise`。
* wover 侧（WorldWeaver `release/26.3`）：`threshold_condition` / `volume_threshold_condition` 是
  `noise.eval(x * scale_x[, y * scale_y], z * scale_z) + roughness > threshold`，其中 `scale` 是
  **频率乘数**（不是"每片多少格"）；`rough_noise_condition` 用世界噪声 + 每 chunk 的随机流；
  `switch_rule` 取 `max(0, selector) % len`。
* 选择器：`wover:nether_noise` 的 JSON 字节会被 codec 丢掉（所有文件共用同一个 provider）；
  `betternether:nether_grasslands` / `nether_mushroom_forrest_edge` 是本仓库那两个 provider，
  按 1 基的 `stoneDepthAbove` 分支。

因此：**改这些 JSON 之前先确认对应节点在 Java 侧的语义**，否则改出来的是"看起来变了"的差别。

## 为什么放在 `worldgen-upstream/` 而不是 `datapack/`

`plugin/build.gradle.kts` 的 `processResources` 会把 `<out>` 整个并进 jar 根目录。放在
`datapack/` 下会被服务端当成数据包加载 —— 但这 23 个文件是 **wover** 的 surface_rule 注册表，
原版/Paper 不认识 `wover:` 类型，更不会去读 `betternether:nether_grasslands` 这种选择器，
加载它们只会得到一堆「未知类型」的报错。放 `worldgen-upstream/` 下，它们就只是 jar 里的资源，
由 `dev.mumu.betternether.biome.SurfaceRules` 用 classpath 读。

## 为什么写 index.txt

classpath 里列目录（jar 内）没有可移植的写法，而把 23 个文件名硬编码在 Java 里等于又抄了一遍。
所以这里额外写一行一个文件的 `index.txt`，Java 端照着它逐个加载，数量对不上就直接失败。
"""

from __future__ import annotations

import json
import shutil
from pathlib import Path

# 上游生成文件的位置（相对 `--upstream`）。
SOURCE_REL = Path("src/main/generated/data/betternether/wover/worldgen/surface_rules")

# 产物内的位置：故意不在 `datapack/` 下，原版不能加载它们（见模块 docstring）。
TARGET_REL = Path("worldgen-upstream/surface_rules")

# 期望的文件数。写死一个下界而不是精确值：上游加群系时这里不该炸，
# 但少一个就是迁移器漏抄，必须炸。
EXPECTED_MIN = 23

INDEX_NAME = "index.txt"


def copy_rules(upstream: Path, out: Path) -> dict[str, object]:
    """把上游的 surface_rules 全量复制到 ``<out>/worldgen-upstream/surface_rules/``。

    返回一份可打印的报告，也供测试直接断言。

    * 只接受 ``.json``，且必须是能解析的 JSON 对象 —— 上游产物是生成出来的，
      一旦出现截断文件，在这里报出来比在 Java 端「未知类型/空规则」好查得多。
    * 同时写出 ``index.txt``：一行一个群系键（去掉 ``.json``），按文件名字典序。
    """
    source = upstream / SOURCE_REL
    if not source.is_dir():
        raise SystemExit(f"上游 surface_rules 目录不存在：{source}")

    target = out / TARGET_REL
    target.mkdir(parents=True, exist_ok=True)

    copied: list[str] = []
    for path in sorted(source.glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(payload, dict) or "ruleSource" not in payload:
            raise SystemExit(f"上游 surface rule 结构异常（缺 ruleSource）：{path}")
        shutil.copyfile(path, target / path.name)
        copied.append(path.stem)

    if len(copied) < EXPECTED_MIN:
        raise SystemExit(
            f"surface_rules 只找到 {len(copied)} 个文件，少于预期的 {EXPECTED_MIN} 个；"
            f"上游目录可能不完整：{source}"
        )

    (target / INDEX_NAME).write_text(
        "\n".join(copied) + "\n", encoding="utf-8", newline="\n"
    )

    return {
        "target": str(TARGET_REL).replace("\\", "/"),
        "count": len(copied),
        "biomes": copied,
    }

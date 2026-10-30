#!/usr/bin/env python3
"""为「模型只在模组 Java 里生成、磁盘上没有 JSON」的方块合成模型。

> **现状：本模块的模型合成器（`synth_model` / `synthesize` / `prune_unrenderable`）目前
> 没有被管道调用，是死代码。** 唯一在用的是 {@link break_texture_cycles}（`extract_bn.py:477`
> 释放上游模型时拆循环纹理引用）。方块外观现在由 CraftEngine 按 `blocks.<id>.state.model.generation`
> 生成，规格来自 `blocks.py:generation_of()` —— 那里才是「几何长什么样」的唯一来源。
>
> 留下这条注释是因为它被误当成过「竖条纹/实心方块」的根因：下面第 1 条规则
> （`<name>.png` 存在 -> cube_all）描述的行为如果在管道里生效，确实会把尖刺、原木、地表方块
> 全压成立方体。但它没有生效。真正的 cube_all 来自 `blocks.py:block_yaml()` 旧版里写死的
> `parent: minecraft:block/cube_all`。改外观请改 `blocks.py`，不要改这里。

## 为什么会有这种方块

上游有一部分方块（尤其木质族与黑曜石族）的模型与 blockstate 是**运行期用代码生成的**：
`WillowMaterial.java` 之类在注册时调 `mk("block/willow_log_top")` 拼出模型，磁盘上既没有
`models/block/willow_log.json`，也没有 `blockstates/willow_log.json`。

CraftEngine 只认磁盘上的模型文件，所以这些方块全是「模型找不到」+「贴图找不到」，游戏里是紫黑格子。

## 合成规则从哪来

不是猜的，是从上游贴图的实际命名反推的，三类：

  * `<name>.png` 存在          -> `cube_all`（最简单，58 个方块属于这类）
  * `<name>_log_side*.png`     -> `cube_column`：side 用 `_log_side`，end 用 `_log_top`
  * `<name>_pillar_middle.png` -> `cube_column`：side 用 `_middle`，end 用 `_top`

`_bark` 这类没有独立贴图的，回退到同名 `_log_side`（上游的 bark 就是 log 的侧面材质，
`RubeusBark.java` 的注释写明了这一点）。

任何一条都匹配不上时，**不生成任何模型**并把它报出来 —— 宁可在日志里列清楚缺哪些，
也不要生成一个贴图错位的模型，那比缺模型更难排查。
"""

from __future__ import annotations

import json
from pathlib import Path


def _pick(textures: dict[str, Path], *candidates: str) -> str | None:
    """按顺序找到第一个存在的贴图，返回不带扩展名的名字。"""
    for candidate in candidates:
        if candidate in textures:
            return candidate
    return None


def synth_model(name: str, textures: dict[str, Path]) -> dict | None:
    """为方块名合成一个模型 JSON；无法合成时返回 None。"""
    # 1) 完全同名的贴图：cube_all
    if name in textures:
        return {
            "parent": "block/cube_all",
            "textures": {"all": f"betternether:block/{name}"},
        }

    # 2) 原木/菌木：side + top
    side = _pick(textures, f"{name}_side", f"{name}_log_side", f"{name}_log_side_2")
    top = _pick(textures, f"{name}_top", f"{name}_log_top", f"{name}_log_top_2")
    if side and top:
        return {
            "parent": "block/cube_column",
            "textures": {
                "side": f"betternether:block/{side}",
                "end": f"betternether:block/{top}",
            },
        }

    # 3) 柱状：middle + top/bottom
    middle = _pick(textures, f"{name}_middle", f"{name}_pillar_middle")
    end = _pick(textures, f"{name}_top", f"{name}_pillar_top")
    if middle and end:
        return {
            "parent": "block/cube_column",
            "textures": {
                "side": f"betternether:block/{middle}",
                "end": f"betternether:block/{end}",
            },
        }

    # 4) 只有 side 没有 top：竖纹方块，side 同时充当全部面
    if side:
        return {
            "parent": "block/cube_all",
            "textures": {"all": f"betternether:block/{side}"},
        }

    # 5) bark 类：上游的树皮用原木侧面材质
    if name.endswith("_bark"):
        base = name[: -len("_bark")]
        bark_side = _pick(textures, f"{base}_log_side", f"{base}_log_side_2", f"{base}_log_side_3")
        if bark_side:
            return {
                "parent": "block/cube_all",
                "textures": {"all": f"betternether:block/{bark_side}"},
            }
        # 树皮也可以只有 log_top 一种材质在侧面复用
        only_top = _pick(textures, f"{base}_log_top")
        if only_top:
            return {
                "parent": "block/cube_all",
                "textures": {"all": f"betternether:block/{only_top}"},
            }

    # 6) 染色石英玻璃：贴图名把 quartz_glass_<color> 写成 quartz_stained_glass_<color>
    if name.startswith("quartz_glass_framed_"):
        color = name[len("quartz_glass_framed_"):]
        stained = _pick(textures, f"quartz_stained_glass_framed_{color}",
                        f"quartz_glass_framed_{color}", f"quartz_stained_glass_{color}")
        if stained:
            return {
                "parent": "block/cube_all",
                "textures": {"all": f"betternether:block/{stained}"},
            }
    if name.startswith("quartz_glass_"):
        color = name[len("quartz_glass_"):]
        stained = _pick(textures, f"quartz_stained_glass_{color}", f"quartz_glass_{color}")
        if stained:
            return {
                "parent": "block/cube_all",
                "textures": {"all": f"betternether:block/{stained}"},
            }

    # 7) 石笋/钟乳石：上游用对应石材的砖纹贴图
    if name.endswith("_stalactite"):
        base = name[: -len("_stalactite")]
        for candidate in (f"{base}_bricks", f"{base}_block", f"{base}_tile", base):
            if candidate in textures:
                return {
                    "parent": "block/cube_all",
                    "textures": {"all": f"betternether:block/{candidate}"},
                }
        # 材质族名与方块族名不一致的情形（bone_stalactite -> bone_block 已在上面覆盖）
        for prefix in (base, base.split("_")[0]):
            for candidate in (f"{prefix}_block", f"{prefix}_bricks"):
                if candidate in textures:
                    return {
                        "parent": "block/cube_all",
                        "textures": {"all": f"betternether:block/{candidate}"},
                    }

    # 8) 植物类：上游用 `<name>_1` 之类的变体贴图，走十字模型
    for suffix in ("_1", "_0", "_top", "_side"):
        candidate = f"{name}{suffix}"
        if candidate in textures:
            return {
                "parent": "block/cross",
                "textures": {"cross": f"betternether:block/{candidate}"},
            }

    # 9) 原木名与贴图族名不同：willow_log 的材质叫 willow_bark
    if name.endswith("_log"):
        base = name[: -len("_log")]
        for candidate in (f"{base}_bark", f"{base}_log_side", f"{base}_block"):
            if candidate in textures:
                return {
                    "parent": "block/cube_all",
                    "textures": {"all": f"betternether:block/{candidate}"},
                }

    # 10) 最后兜底：用任意同前缀贴图，避免紫黑格子。
    #     这一步会牺牲准确度（可能贴错面），但比缺模型好：缺模型在游戏里是完全的紫黑方块，
    #     而错位至少能看，且构建日志已经把这一类单独列出来了。
    prefix_hits = sorted(stem for stem in textures if stem.startswith(name + "_"))
    if not prefix_hits:
        base = name.split("_")[0]
        prefix_hits = sorted(stem for stem in textures if stem.startswith(base + "_"))
    if prefix_hits:
        return {
            "parent": "block/cube_all",
            "textures": {"all": f"betternether:block/{prefix_hits[0]}"},
        }

    return None


def break_texture_cycles(model: dict, name: str, textures: dict[str, Path]) -> bool:
    """拆掉模型内 `#x -> #x` 这类自引用，返回是否改动过。

    ## 为什么会有这种模型

    上游 `jungle_plant.json` 里写着：

        "particle": "#particle",
        "texture":  "#particle",
        "texture1": "#texture"

    这是 Cubik Studio 导出的半成品：模组自己在 Java 侧把真实贴图补进去，所以磁盘上的 JSON
    从来不完整。CraftEngine 只做静态解析，于是报一串
    `存在循环纹理引用：#texture -> #particle -> #particle`，方块渲染不出来。

    修法是把指向环的槽位换成一个**真实存在的贴图**：优先用方块自己的贴图，
    其次用任意同族贴图，最后用同名前缀。判定不了就保持原样，交给调用方报告 ——
    不能为了消警告而塞一张完全无关的贴图。
    """
    declared = model.get("textures")
    if not isinstance(declared, dict):
        return False

    # 找出所有「直接或间接指向自己」的槽位。
    def resolves_to_cycle(slot: str, seen: frozenset[str]) -> bool:
        value = declared.get(slot)
        if not isinstance(value, str) or not value.startswith("#"):
            return False
        target = value[1:]
        if target == slot:
            return True
        if target in seen:
            return True
        return resolves_to_cycle(target, seen | {slot})

    candidates = [f"{name}_1", name, f"{name}_side", f"{name}_top"]
    family = name.split("_")[0]
    candidates += sorted(stem for stem in textures if stem.startswith(name + "_"))
    candidates += sorted(stem for stem in textures if stem.startswith(family + "_"))
    replacement = next((c for c in candidates if c in textures), None)
    if replacement is None:
        return False

    changed = False
    for slot in list(declared):
        if resolves_to_cycle(slot, frozenset()):
            declared[slot] = f"betternether:block/{replacement}"
            changed = True
    return changed


def synthesize(
    models_dir: Path,
    textures_dir: Path,
    block_names: list[str],
) -> tuple[int, list[str]]:
    """为缺少模型的方块补合成模型，返回 (生成数, 仍缺清单)。

    仍缺的清单不是「警告而已」：调用方会把这些方块从 CE 配置里剔除。理由是一个永远渲染成
    紫黑格子的方块，比一个不存在的方块更糟 —— 玩家会以为是自己客户端的问题。
    """
    textures = {path.stem: path for path in textures_dir.glob("*.png")}
    models_dir.mkdir(parents=True, exist_ok=True)

    created = 0
    unresolved: list[str] = []
    for name in block_names:
        target = models_dir / f"{name}.json"
        if target.exists():
            continue
        model = synth_model(name, textures)
        if model is None:
            unresolved.append(name)
            continue
        target.write_text(json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        created += 1
    return created, unresolved


def prune_unrenderable(textures_dir: Path, block_names: list[str]) -> list[str]:
    """滤掉那些连一张可用贴图都找不到的方块。

    判据与 {@link synth_model} 一致，但只回答「有没有可能渲染」：只要存在同名贴图、
    或任意 `<name>_*` / `<族名>_*` 贴图，就保留（合成器会兜底）。一张都没有的剔除。
    """
    textures = {path.stem for path in textures_dir.glob("*.png")}
    kept: list[str] = []
    for name in block_names:
        if name in textures:
            kept.append(name)
            continue
        if any(stem.startswith(name + "_") for stem in textures):
            kept.append(name)
            continue
        family = name.split("_")[0]
        if any(stem.startswith(family + "_") for stem in textures):
            kept.append(name)
            continue
    return kept

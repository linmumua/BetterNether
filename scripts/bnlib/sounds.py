#!/usr/bin/env python3
"""自定义声音发布开关：按用户要求，不再发布 BetterNether 音频与声音事件。

保留生成器钩子及零值报告，让集成器仍能核验本阶段执行过；不读取或复制上游声音。
完整生成入口会先清空自己的构建目录，因此旧 sounds.yml / ogg 不会进入新产物。
已部署的旧资源不在本模块权限范围内，更新时须同步替换本包资源，不能只覆盖新增文件。
原版 minecraft:* 方块音效与群系替代音不受影响。
"""

from __future__ import annotations

from pathlib import Path
from typing import Sequence


# 保留稳定路径常量，供生成契约测试检查没有重新发布旧资源。
CONFIG_REL = "configuration/sounds.yml"
ASSET_REL = "resourcepack/assets/betternether/sounds"


def generate(
    upstream: Path,
    ce_root: Path,
    selected: Sequence[str] = (),
) -> dict[str, object]:
    """不写出任何自定义声音；返回显式禁用报告，而不是伪装迁移成功。"""
    return {
        "files": 0,
        "written": [],
        "warnings": [],
        "unsupported": [],
        "disabled": True,
        "reason": "用户要求移除本包全部自定义声音",
        "events": 0,
        "sound_files": 0,
        "sound_entries": {},
    }

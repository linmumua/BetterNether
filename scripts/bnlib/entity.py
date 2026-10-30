#!/usr/bin/env python3
"""别名模块：集成器的 HOOK_ORDER 要求文件名是单数 `entity.py`，实现放在 `entities.py`。

两个名字都暴露同一个 `generate`，所以 `from bnlib.entity import generate` 与
`from bnlib.entities import generate` 行为完全一致。这里只做再导出，不放任何逻辑 ——
两处各写一份实现必然漂移。
"""

from __future__ import annotations

from bnlib.entities import CARRIERS, generate

__all__ = ["CARRIERS", "generate"]

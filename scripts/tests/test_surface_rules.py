#!/usr/bin/env python3
"""`bnlib.surface_rules` 的迁移测试。

跑法（stdlib，不需要 pytest）：

    python -m unittest discover -s scripts/tests -p "test_*.py"

## 在验什么

Java 端（`dev.mumu.betternether.biome.SurfaceRules`）**只**从产物里读这 23 个 JSON 和一份
`index.txt`。这里的断言针对的是「迁移器会不会让 Java 端读到残缺的一套规则」：

  * 文件少了         -> 某些群系的世界生成悄悄退回下界岩，没有任何报错
  * 放进 datapack/   -> 原版把 wover 的自定义类型当未知规则读，日志刷屏
  * index.txt 少一行 -> 那个群系在 Java 端根本不会被加载，表现同上
  * JSON 被截断      -> Java 端解析失败，整个世界生成起不来
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from bnlib import surface_rules  # noqa: E402

UPSTREAM = SCRIPTS.parent / "upstream" / "BetterNether"


class SurfaceRulesCopyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.out = Path(self._tmp.name)
        self.report = surface_rules.copy_rules(UPSTREAM, self.out)
        self.target = self.out / surface_rules.TARGET_REL

    def test_copies_all_twenty_three_biomes(self) -> None:
        files = sorted(p.name for p in self.target.glob("*.json"))
        self.assertEqual(23, len(files), f"实际复制了 {files}")
        self.assertEqual(23, self.report["count"])

    def test_every_biome_has_a_rule_file_with_a_rule_source(self) -> None:
        for name in self.report["biomes"]:
            payload = json.loads((self.target / f"{name}.json").read_text(encoding="utf-8"))
            self.assertIn("ruleSource", payload, f"{name} 缺 ruleSource")
            self.assertEqual(f"betternether:{name}", payload.get("biome"), name)

    def test_index_lists_exactly_the_copied_biomes(self) -> None:
        lines = (self.target / surface_rules.INDEX_NAME).read_text(
            encoding="utf-8").split()
        self.assertEqual(sorted(self.report["biomes"]), sorted(lines))
        self.assertEqual(23, len(lines))

    def test_rules_are_not_placed_under_the_datapack_root(self) -> None:
        # 原版会加载 datapack/ 下的所有数据包文件；wover 的自定义类型不是原版规则。
        self.assertFalse(
            str(surface_rules.TARGET_REL).replace("\\", "/").startswith("datapack/"),
            "surface rules 不能放进 datapack/，原版加载不了 wover 类型",
        )

    def test_index_txt_is_not_counted_as_a_rule_file(self) -> None:
        self.assertFalse((self.target / f"{surface_rules.INDEX_NAME}.json").exists())


if __name__ == "__main__":
    unittest.main()

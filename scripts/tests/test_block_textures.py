#!/usr/bin/env python3
"""`blocks.texture_ref` 的单元测试 —— 贴图命名空间归一化。

## 为什么需要这组测试

集成阶段 verifyAssets 抓到过一个真实事故：51 处贴图被生成成
`betternether:block/minecraft:block/<texture>`（双重命名空间）。
成因是上游模型里的贴图引用有三种写法，而拼接代码一律套了
`f"{NAMESPACE}:block/{value}"`：

  * `betternether:block/foo` —— 本包贴图
  * `minecraft:block/bar`    —— 显式原版贴图
  * `block/baz`              —— 裸路径（MC 按原版命名空间解析）

前两种一旦再套前缀就会变成不存在的资源，CE 判定方块无外观。
这组测试把三种写法、以及 8 个真实受害方块的输入全部钉死。

运行：`python -m unittest discover -s scripts/tests -p "test_*.py"`
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from bnlib import blocks  # noqa: E402
from bnlib.namespace import NAMESPACE  # noqa: E402


class TextureRefTest(unittest.TestCase):
    """`texture_ref` 必须保留已有命名空间，只给裸路径补全。"""

    def test_keeps_existing_minecraft_namespace(self) -> None:
        # 事故的核心：这一步以前会拼成 betternether:block/minecraft:block/x。
        self.assertEqual(blocks.texture_ref("minecraft:block/sculk"),
                         "minecraft:block/sculk")
        self.assertEqual(blocks.texture_ref("minecraft:block/netherrack"),
                         "minecraft:block/netherrack")
        self.assertEqual(blocks.texture_ref("minecraft:block/warped_planks"),
                         "minecraft:block/warped_planks")

    def test_keeps_existing_local_namespace(self) -> None:
        self.assertEqual(blocks.texture_ref("betternether:block/cincinnasite_forged"),
                         "betternether:block/cincinnasite_forged")

    def test_bare_block_path_is_vanilla(self) -> None:
        # 裸 `block/xxx` 在 MC 里按原版命名空间解析，不是本包。
        self.assertEqual(blocks.texture_ref("block/brown_mushroom_block"),
                         "minecraft:block/brown_mushroom_block")
        self.assertEqual(blocks.texture_ref("block/nether_wart_block"),
                         "minecraft:block/nether_wart_block")
        self.assertEqual(blocks.texture_ref("block/mushroom_block_inside"),
                         "minecraft:block/mushroom_block_inside")

    def test_bare_name_gets_local_namespace(self) -> None:
        # 没有 `block/` 前缀的裸名保持旧行为：按本包命名空间补全。
        self.assertEqual(blocks.texture_ref("cincinnasite_forged"),
                         f"{NAMESPACE}:block/cincinnasite_forged")

    def test_never_produces_double_namespace(self) -> None:
        """任何输入都不得产出 `xxx:block/minecraft:block/...`。"""
        for value in (
            "minecraft:block/sculk",
            "block/sculk",
            "betternether:block/sculk",
            "sculk",
            "minecraft:block/minecraft:block/sculk",
        ):
            with self.subTest(value=value):
                self.assertNotIn("block/minecraft:block/", blocks.texture_ref(value))

    def test_is_idempotent(self) -> None:
        """重复归一化不得改变结果 —— 防止调用链上被套两次前缀。"""
        for value in ("minecraft:block/sculk", "block/sculk", "sculk"):
            once = blocks.texture_ref(value)
            self.assertEqual(blocks.texture_ref(once), once)


class RealVictimBlocksTest(unittest.TestCase):
    """集成阶段被 verifyAssets 判成「无外观」的 8 个方块的真实贴图输入。

    每一项都是上游模型里 `textures` 字段的原值；测试要求归一化后
    保持/落到 `minecraft:block/*`，绝不能带本地命名空间。
    """

    # 方块名 -> 上游模型声明的第一张贴图（真实值）
    VICTIMS = {
        "bleached_gloomsculk": "minecraft:block/sculk",
        "brown_large_mushroom": "block/brown_mushroom_block",
        "netherite_fire_bowl": "minecraft:block/netherite_block",
        "netherrack_stalactite": "minecraft:block/netherrack",
        "netherrack_wall": "block/netherrack",
        "wall_mushroom_brown": "block/brown_mushroom_block",
        "wall_mushroom_red": "block/mushroom_block_inside",
        "wart_seed": "block/nether_wart_block",
    }

    def test_victims_normalize_to_vanilla(self) -> None:
        for name, raw in self.VICTIMS.items():
            with self.subTest(block=name):
                resolved = blocks.texture_ref(raw)
                self.assertTrue(
                    resolved.startswith("minecraft:block/"),
                    f"{name}: {raw!r} 归一化成了 {resolved!r}，应指向 minecraft:block/*",
                )
                self.assertNotIn(NAMESPACE, resolved)

    def test_victims_have_no_double_namespace(self) -> None:
        for name, raw in self.VICTIMS.items():
            with self.subTest(block=name):
                self.assertNotIn("block/minecraft:block/", blocks.texture_ref(raw))


class VanillaTextureDetectionTest(unittest.TestCase):
    """`texture_is_vanilla` 用于生成期判断贴图归属。"""

    def test_detects_vanilla(self) -> None:
        self.assertTrue(blocks.texture_is_vanilla("minecraft:block/sculk"))
        self.assertTrue(blocks.texture_is_vanilla(blocks.texture_ref("block/sculk")))

    def test_detects_local(self) -> None:
        self.assertFalse(blocks.texture_is_vanilla("betternether:block/sculk"))


class TerrainCoverGenerationTest(unittest.TestCase):
    """地表覆盖方块必须使用上游的 bottom/side/top 三贴图几何。"""

    def test_prefers_local_side_over_vanilla_bottom_texture(self) -> None:
        bottoms = {
            "bleached_gloomsculk": "minecraft:block/sculk",
            "molten_gloomsculk": "minecraft:block/sculk",
            "veined_gloomsculk": "minecraft:block/netherrack",
        }
        for name, bottom in bottoms.items():
            with self.subTest(block=name):
                side = f"{name}_side"
                top = f"{name}_top"
                selected = blocks.generation_texture_of(
                    name, [bottom, side, top], {side, top})
                self.assertEqual(selected, side)
                generated = blocks.generation_of(name, selected, {side, top}, Path())
                self.assertEqual(generated["parent"], "minecraft:block/cube_bottom_top")
                self.assertEqual(generated["textures"], {
                    "top": f"betternether:block/{top}",
                    "side": f"betternether:block/{side}",
                    "bottom": bottom,
                })


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""`tools/verify_assets.py` 的外观判据测试 —— 不许误报，也不许放过真缺口。

## 为什么要这组测试

`verifyAssets` 是 `verifyJar` 的依赖，它一旦误报就把整条构建卡住；反过来，
它一旦被「放宽到能过」就把紫黑格子放进了产物。这两件事在 2026-10 同时发生过：

  * 69 个方块被 `state.entity_renderer` / `states.appearances` 接管了外观，
    校验器却只认 `block/<方块名>`，于是**误报 66 项**；
  * `betternether:smoker` 的物品模型指向 `item/smoker`，那个文件被
    `reports/shadowed-models.txt` 连带排除，是**真缺口**，而校验器当时也报不出来
    （它只按「同名方块有没有 state.texture」兜底）。

所以这里正反两面都钉死：合法写法必须通过，坏写法必须失败。

运行：`python -m pytest scripts/tests/test_verify_assets.py`
（也可 `python scripts/tests/test_verify_assets.py`，只用标准库）
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

sys.path.insert(0, str(_SCRIPTS.parent / "tools"))

import verify_assets  # noqa: E402

NAMESPACE = "betternether"


class PackBuilder:
    """在临时目录里拼一个最小可校验的 CE 资源包。

    `pack.yml` 的 namespace 必须与目录名一致（校验器第 5 条），所以目录固定叫 `betternether`。
    """

    def __init__(self, root: Path) -> None:
        self.root = root
        self.pack = root / NAMESPACE
        self.assets = self.pack / "resourcepack" / "assets" / NAMESPACE
        self.write("pack.yml", f"namespace: {NAMESPACE}\n")
        self._boilerplate()

    def _boilerplate(self) -> None:
        """一份本身完全合法的样板条目（一个方块 + 一个物品 + 一条 id 引用）。

        校验器自检里有一条：解析不到任何 `items` 条目、或解析不到任何 id 引用时，
        「id 闭合」会静默通过 —— 那种情况会被判成校验路径失效。每个用例都带上样板，
        让被测条目之外的东西保持干净，断言才只反映被测写法。
        """
        self.write("configuration/items/probe.yml", f"""blocks:
  {NAMESPACE}:probe_block:
    state:
      auto_state: solid
      model:
        path: {NAMESPACE}:block/probe
items:
  {NAMESPACE}:probe_item:
    data:
      item_name: '<!i><white>probe</white>'
    model:
      path: {NAMESPACE}:item/probe_item
    behavior:
      type: block_item
      block: {NAMESPACE}:probe_block
""")
        self.model("block/probe", '{"parent": "minecraft:block/cube_all"}')
        self.model("item/probe_item", '{"parent": "minecraft:item/generated"}')

    def write(self, relative: str, text: str) -> None:
        path = self.pack / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def model(self, relative: str, body: str) -> None:
        self.write(f"resourcepack/assets/{NAMESPACE}/models/{relative}.json", body)

    def texture(self, relative: str) -> None:
        # 这两条判据只看文件是否存在；PNG 内容要到 check_animations 才解析。
        path = self.assets / "textures" / f"{relative}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\x89PNG\r\n\x1a\n")

    def config(self, name: str, body: str) -> None:
        self.write(f"configuration/blocks/{name}.yml", body)

    def verify(self) -> verify_assets.Report:
        return verify_assets.verify(self.pack)


def entity_renderer_block(block: str, item_model_yaml: str) -> str:
    """溢出方块的写法：透明共享状态 + `entity_renderer` 指向自己的物品。"""
    return f"""blocks:
  {NAMESPACE}:{block}:
    state:
      auto_state:
        type: sugar_cane
        id: transparent
      transparent: true
      entity_renderer:
        item: {NAMESPACE}:{block}
items:
  {NAMESPACE}:{block}:
    data:
      item_name: '<!i><white>X</white>'
{item_model_yaml}    behavior:
      type: block_item
      block: {NAMESPACE}:{block}
"""


class EntityRendererTest(unittest.TestCase):
    """`state.entity_renderer`：外观由 item_display 渲染物品模型。"""

    def test_block_whose_item_model_exists_passes(self) -> None:
        """物品模型文件在包里 —— 方块和物品都有外观，不许报错。"""
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("plants", entity_renderer_block(
                "ink_bush",
                "    model:\n      path: betternether:item/ink_bush\n",
            ))
            pack.model("item/ink_bush", '{"parent": "minecraft:item/generated"}')
            pack.texture("item/ink_bush")
            self.assertEqual(pack.verify().failures, [])

    def test_block_whose_item_model_is_missing_fails_both_sides(self) -> None:
        """物品模型不存在、也没有 generation —— 这就是 smoker 事故，两侧都必须报。

        方块侧要报：否则「写了 entity_renderer」就变成免检通道；
        物品侧要报：否则物品图标照样是紫黑格子。
        """
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", entity_renderer_block(
                "smoker",
                "    model:\n      path: betternether:item/smoker\n",
            ))
            report = pack.verify()
            self.assertEqual(len(report.failures), 2, "\n".join(report.failures))
            block_failure, item_failure = report.failures
            self.assertIn("方块 betternether:smoker", block_failure)
            self.assertIn("entity_renderer", block_failure)
            self.assertIn("betternether:smoker 的 model 指向 betternether:item/smoker",
                          item_failure)

    def test_block_whose_item_model_is_generated_passes(self) -> None:
        """物品模型靠 `generation` 生成（`ink_bush` 的真实形态）—— 不算缺外观。"""
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("plants", entity_renderer_block(
                "ink_bush",
                "    model:\n"
                "      path: betternether:block/ink_bush\n"
                "      generation:\n"
                "        parent: minecraft:block/cube_all\n"
                "        textures:\n"
                "          all: betternether:block/ink_bush_bark\n",
            ))
            pack.texture("block/ink_bush_bark")
            self.assertEqual(pack.verify().failures, [])

    def test_entity_renderer_pointing_at_undefined_item_fails(self) -> None:
        """指向一个根本没定义的物品时不能算有外观。"""
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", entity_renderer_block(
                "ghost",
                "    model:\n      path: betternether:block/ghost\n"
                "      generation:\n"
                "        parent: minecraft:block/cube_all\n"
                "        textures:\n"
                "          all: betternether:block/ghost\n",
            ).replace(f"item: {NAMESPACE}:ghost", f"item: {NAMESPACE}:no_such_item"))
            pack.texture("block/ghost")
            report = pack.verify()
            self.assertTrue(
                any("方块 betternether:ghost" in message for message in report.failures),
                "\n".join(report.failures),
            )


class StateModelTest(unittest.TestCase):
    """`state.model`：模型名与方块名可以不同，也可以交给 CE 生成。"""

    def test_state_model_generated_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("stone", f"""blocks:
  {NAMESPACE}:basalt_stalactite:
    state:
      auto_state: solid
      model:
        path: {NAMESPACE}:block/basalt_stalactite_1
        generation:
          parent: minecraft:block/cube_all
          textures:
            all: {NAMESPACE}:block/basalt
""")
            pack.texture("block/basalt")
            self.assertEqual(pack.verify().failures, [])

    def test_state_model_file_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("stone", f"""blocks:
  {NAMESPACE}:reused_model:
    state:
      auto_state: solid
      model:
        path: {NAMESPACE}:block/some_other_model
""")
            pack.model("block/some_other_model",
                       '{"parent": "minecraft:block/cube_all"}')
            self.assertEqual(pack.verify().failures, [])


class StatesAppearancesTest(unittest.TestCase):
    """异形族：`states.appearances.*.model` 才是外观，方块名不再是模型名。"""

    def _config(self, model_path: str, generation: str = "") -> str:
        return f"""blocks:
  {NAMESPACE}:stalagnate_door:
    states:
      properties:
        half:
          type: double_block_half
          default: lower
      appearances:
        bottom:
          auto_state: solid
          model:
            path: {model_path}
{generation}      variants:
        half=lower:
          appearance: bottom
"""

    def test_appearance_model_file_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", self._config(f"{NAMESPACE}:block/stalagnate_door_bottom_left"))
            pack.model("block/stalagnate_door_bottom_left",
                       '{"parent": "minecraft:block/cube_all"}')
            self.assertEqual(pack.verify().failures, [])

    def test_appearance_generation_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", self._config(
                f"{NAMESPACE}:block/stalagnate_door_ce_bottom",
                f"            generation:\n"
                f"              parent: minecraft:block/cube_all\n"
                f"              textures:\n"
                f"                all: {NAMESPACE}:block/door_side\n",
            ))
            pack.texture("block/door_side")
            self.assertEqual(pack.verify().failures, [])

    def test_missing_appearance_model_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", self._config(f"{NAMESPACE}:block/nope"))
            report = pack.verify()
            self.assertTrue(
                any("方块 betternether:stalagnate_door" in message
                    for message in report.failures),
                "\n".join(report.failures),
            )


class NoAppearanceTest(unittest.TestCase):
    """什么来源都没有时必须失败 —— 这条保证上面几条不是「一律放行」。"""

    def test_bare_block_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            pack.config("misc", f"""blocks:
  {NAMESPACE}:orphan:
    state:
      auto_state: solid
""")
            report = pack.verify()
            self.assertTrue(
                any("方块 betternether:orphan" in message for message in report.failures),
                "\n".join(report.failures),
            )


class ParentClosureTest(unittest.TestCase):
    """`check_models` 的父级闭合只认「包内文件」或「真的带 generation 的路径」。

    旧实现把每个方块名都当成「CE 会生成」，于是任何撞上方块名的悬空 parent 都能过 ——
    这条测试锁住收紧后的行为。
    """

    def test_parent_pointing_at_transparent_block_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pack = PackBuilder(Path(tmp))
            # `black_apple` 是透明方块：方块段不写模型，物品段用 item/ 路径，
            # 所以 `block/black_apple` 既没有文件、也不会被生成。
            pack.config("plants", entity_renderer_block(
                "black_apple",
                "    model:\n      path: betternether:item/black_apple\n",
            ))
            pack.model("item/black_apple", '{"parent": "minecraft:item/generated"}')
            pack.texture("item/black_apple")
            pack.model("block/child", '{"parent": "betternether:block/black_apple"}')
            report = pack.verify()
            self.assertTrue(
                any("block/child.json" in message and "parent" in message
                    for message in report.failures),
                "\n".join(report.failures),
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""`bnlib.assets` 的资产门禁测试。

跑法（不需要 pytest，stdlib 就够）：

    python scripts/tests/test_assets.py

## 这些测试在验什么

不是「函数能跑」，而是**资源包在客户端眼里是否自洽**。每一条都对应一种
「日志看起来正常、游戏里却是紫黑格子」的失败：

  * 父模型断了        -> CE 解析不到 parent，整个模型作废，回退成统一占位外观
  * 贴图引用断了      -> CE 报缺贴图，方块不渲染
  * 动画 mcmeta 丢了  -> 贴图退化成一条被拉长的静态图，日志毫无痕迹
  * blockstates 混进来 -> 用上游状态表覆盖 CE 自己分配的状态，渲染错乱
  * 坏模型混进来      -> CE 加载时逐个报循环纹理 / 缺贴图

所以断言都写成「遍历产出的每一个文件，逐个验证」，不是抽一两个样本。
样本断言只能证明那一两个对，不能证明包是自洽的。
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from bnlib import assets  # noqa: E402
from bnlib.namespace import NAMESPACE  # noqa: E402
from bnlib.publication import is_removed_block  # noqa: E402

UPSTREAM = SCRIPTS.parent / "upstream" / "BetterNether"

# 上游自己就是坏的、且没有任何方块引用的模型。清单写死在这里是**故意的**：
# 它同时是「排除逻辑有没有真的生效」的判据。上游修好它们时这条会失败，
# 提醒我们回来确认，而不是静默跟随。
BROKEN_UPSTREAM_MODELS = {
    "models/block/stairs_with_top.json",
    "models/block/wall_mushrooms_red_1.json",
    "models/block/wall_mushrooms_red_2.json",
}

# 上游动画贴图数量（26.3）。硬编码是刻意的：它是「既有 79 个 mcmeta 必须保留」
# 这条要求的守门数字，上游增删动画时应当有人回来看一眼。
EXPECTED_ANIMATIONS = 79


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class AssetsGateTest(unittest.TestCase):
    """一次性生成到临时目录，之后所有断言都对着真实产物做。"""

    @classmethod
    def setUpClass(cls) -> None:
        if not UPSTREAM.is_dir():
            raise unittest.SkipTest(f"找不到上游仓库：{UPSTREAM}")
        cls._tmp = Path(tempfile.mkdtemp(prefix="bn-assets-test-"))
        cls.ce_root = cls._tmp / "craftengine" / NAMESPACE
        cls.report = assets.generate(UPSTREAM, cls.ce_root)
        cls.pack = cls.ce_root / "resourcepack"
        cls.assets_root = cls.pack / "assets" / NAMESPACE

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls._tmp, ignore_errors=True)

    # -- 契约 -------------------------------------------------------------- #

    def test_interface_is_two_positional_args(self) -> None:
        """集成器按 `generate(upstream, ce_root)` 调用，必须返回 dict。"""
        report = assets.generate(UPSTREAM, self.ce_root)
        self.assertIsInstance(report, dict)
        for key in ("files", "warnings", "unsupported",
                    "upstream", "copied", "reachable", "excluded", "hard_limits"):
            self.assertIn(key, report, f"报告缺少契约键 {key}")
        self.assertIsInstance(report["files"], int)
        self.assertIsInstance(report["warnings"], list)
        self.assertIsInstance(report["unsupported"], list)

    def test_idempotent(self) -> None:
        """重复运行必须逐字节一致，且不多不少。"""
        before = {p.relative_to(self.pack).as_posix(): _digest(p)
                  for p in self.pack.rglob("*") if p.is_file()}
        assets.generate(UPSTREAM, self.ce_root)
        after = {p.relative_to(self.pack).as_posix(): _digest(p)
                 for p in self.pack.rglob("*") if p.is_file()}
        self.assertEqual(before, after, "第二次运行的产物与第一次不一致（不幂等）")

    def test_writes_stay_inside_resourcepack(self) -> None:
        """所有输出都在 resourcepack/ 内，绝不碰 configuration/ 或别处。"""
        for path in self._tmp.rglob("*"):
            if not path.is_file():
                continue
            self.assertTrue(
                str(path.resolve()).startswith(str(self.pack.resolve())),
                f"写出了资源包根目录之外：{path}")

    # -- 模型 -------------------------------------------------------------- #

    def test_every_model_parent_resolves(self) -> None:
        """逐个模型验证 parent：要么在本包内，要么是原版/外部命名空间。

        `wover:` 出现在产物里就是失败 —— 它是上游依赖的库模组，我们没释放它的模型，
        留着它等于给 CE 一个永远解析不到的父级。
        """
        model_files = {p.relative_to(self.assets_root).as_posix()
                       for p in self.assets_root.rglob("models/**/*.json")}
        failures = []
        for relpath in sorted(model_files):
            payload = json.loads((self.assets_root / relpath).read_text(encoding="utf-8"))
            parent = payload.get("parent")
            if not isinstance(parent, str):
                continue
            if ":" not in parent:
                failures.append(f"{relpath}: parent 缺命名空间 -> {parent}")
                continue
            namespace, path = parent.split(":", 1)
            if namespace == NAMESPACE:
                if f"models/{path}.json" not in model_files:
                    failures.append(f"{relpath}: 父模型不存在 -> {parent}")
            elif namespace == "wover":
                failures.append(f"{relpath}: 父级仍指向未释放的库模组 -> {parent}")
        self.assertEqual(failures, [], "父级不闭合：\n" + "\n".join(failures[:20]))

    def test_every_texture_reference_resolves(self) -> None:
        """逐个模型验证直连贴图引用：本命名空间必须在包里，原版路径不得是旧式复数目录。"""
        failures = []
        for path in sorted(self.assets_root.rglob("models/**/*.json")):
            payload = json.loads(path.read_text(encoding="utf-8"))
            for slot, value in (payload.get("textures") or {}).items():
                if not isinstance(value, str) or value.startswith("#"):
                    continue
                namespace, _, tex_path = value.partition(":")
                if not tex_path:
                    namespace, tex_path = "minecraft", value
                if namespace == NAMESPACE:
                    if not (self.assets_root / "textures" / f"{tex_path}.png").is_file():
                        failures.append(f"{path.name}:{slot} -> {value} 缺贴图")
                elif namespace == "minecraft" and tex_path.split("/", 1)[0] in ("blocks", "items"):
                    failures.append(f"{path.name}:{slot} -> {value} 是 1.12 旧路径")
        self.assertEqual(failures, [], "贴图引用不闭合：\n" + "\n".join(failures[:20]))

    def test_broken_upstream_models_excluded(self) -> None:
        """上游 3 个坏模型必须被排除，且报告里点名。"""
        for relpath in BROKEN_UPSTREAM_MODELS:
            self.assertFalse((self.assets_root / relpath).exists(),
                             f"坏模型被释放了：{relpath}")
        excluded = {name for group in self.report["excluded"].values() for name in group}
        for relpath in BROKEN_UPSTREAM_MODELS:
            model_id = f"{NAMESPACE}:" + relpath[len("models/"):-len(".json")]
            self.assertIn(model_id, excluded, f"报告没交代为什么排除 {model_id}")

    # -- 动画 -------------------------------------------------------------- #

    def test_animation_mcmeta_preserved(self) -> None:
        """79 个动画元数据必须原样保留，且每一个都有配对的 png。"""
        metas = sorted(self.assets_root.rglob("*.png.mcmeta"))
        self.assertEqual(len(metas), EXPECTED_ANIMATIONS,
                         f"动画元数据数量不对：{len(metas)} != {EXPECTED_ANIMATIONS}")
        for meta in metas:
            png = meta.with_name(meta.name[: -len(".mcmeta")])
            self.assertTrue(png.is_file(), f"{meta.name} 没有配对的 png")
            payload = json.loads(meta.read_text(encoding="utf-8"))
            self.assertIn("animation", payload, f"{meta.name} 不是动画元数据")
        self.assertEqual(self.report["copied"]["animations"], EXPECTED_ANIMATIONS)

    # -- 物品 / 装备 -------------------------------------------------------- #

    def test_item_models_and_textures_recovered(self) -> None:
        """此前只释放 4 张物品贴图、0 个物品模型；现在必须向模组看齐。"""
        item_models = list((self.assets_root / "models/item").rglob("*.json"))
        item_textures = list((self.assets_root / "textures/item").rglob("*.png"))
        self.assertGreaterEqual(len(item_models), 500, f"物品模型太少：{len(item_models)}")
        self.assertGreaterEqual(len(item_textures), 170, f"物品贴图太少：{len(item_textures)}")

    def test_item_definitions_relocated_and_resolvable(self) -> None:
        """客户端物品定义放在 `items/upstream/`，避免与 CE 生成的同名文件互相覆盖。

        发布范围写成**「上游全集 − 真实过滤集合」的精确等式**，不写数量下限：下限只会
        在「多发了不该发的」时沉默，而上游增删方块时又要回来改数字。过滤只有两种，
        两种都能从证据重算，不抄实现里的名单：
          * 对应方块已被移除 —— `publication.is_removed_block()`（方块不发布，物品定义
            就没有主人，例如 14 个 `*_chest.json` 的 `ChestBlock`）
          * 定义引用的模型没进包 —— 用**产出的模型集**反查（bclib 座椅就是这样浮出来的），
            不是按 `_chair`/`_bar_stool` 后缀猜
        等式两头都卡：多发一份没人引用的、少发一份该有的，都会红。
        """
        relocated = self.assets_root / "items" / "upstream"
        self.assertTrue(relocated.is_dir(), "items/upstream/ 不存在")

        model_files = {p.relative_to(self.assets_root).as_posix()
                       for p in self.assets_root.rglob("models/**/*.json")}

        def unreleased_model_refs(payload: dict) -> list[str]:
            """定义里指向「本包没释放的模型」的引用。"""
            return [model_id for model_id in assets._item_definition_model_refs(payload)
                    if model_id.split(":", 1)[0] == NAMESPACE
                    and f"models/{model_id.split(':', 1)[1]}.json" not in model_files]

        # 上游 `items/` 里有 `debug/` 子目录（6 个定义），按相对路径比，不能只比文件名。
        upstream_root = UPSTREAM / "src/main/generated/assets/betternether/items"
        upstream_items = {path.relative_to(upstream_root).as_posix(): path
                          for path in upstream_root.rglob("*.json")}
        self.assertTrue(upstream_items, f"上游没有物品定义可对照：{UPSTREAM}")

        removed = {name for name in upstream_items
                   if is_removed_block(f"{NAMESPACE}:{name[:-len('.json')]}")}
        self.assertTrue(removed, "移除规则一个物品定义都没命中，等式失去意义")
        orphan = {name for name, path in upstream_items.items()
                  if name not in removed
                  and unreleased_model_refs(json.loads(path.read_text(encoding="utf-8")))}

        expected = set(upstream_items) - removed - orphan
        released = {p.relative_to(relocated).as_posix() for p in relocated.rglob("*.json")}
        self.assertEqual(sorted(released - expected), [],
                         "发布了本不该发布的物品定义（上游没有 / 方块已移除 / 引用未释放模型）")
        self.assertEqual(sorted(expected - released), [],
                         "漏发了该发布的物品定义")

        # 顶层 `items/*.json` 不能有文件：那是 CE 自己生成的位置。
        top_level = [p for p in (self.assets_root / "items").glob("*.json")]
        self.assertEqual(top_level, [], "items/ 顶层出现了文件，会和 CE 生成的物品定义冲突")

        # 定义里引用的模型必须真实存在。
        missing = [f"{path.relative_to(relocated).as_posix()} -> {model_id}"
                   for path in sorted(relocated.rglob("*.json"))
                   for model_id in unreleased_model_refs(
                       json.loads(path.read_text(encoding="utf-8")))]
        self.assertEqual(missing, [], "物品定义引用了不存在的模型：\n" + "\n".join(missing[:20]))

    def test_equipment_assets_wired(self) -> None:
        """装备资源与它的贴图必须成对出现，且 texture 引用能落地。"""
        equipment_dir = self.assets_root / "equipment"
        self.assertTrue(equipment_dir.is_dir(), "equipment/ 不存在")
        files = sorted(equipment_dir.glob("*.json"))
        self.assertEqual(len(files), 3, f"装备资源数量不对：{len(files)}")

        for path in files:
            payload = json.loads(path.read_text(encoding="utf-8"))
            layers = payload.get("layers") or {}
            self.assertTrue(layers, f"{path.name} 没有 layers")
            for layer, entries in layers.items():
                for entry in entries:
                    texture = entry.get("texture")
                    self.assertIsInstance(texture, str, f"{path.name} 的 texture 不是字符串")
                    namespace, _, tex_path = texture.partition(":")
                    if not tex_path:
                        namespace, tex_path = "minecraft", texture
                    if namespace == NAMESPACE:
                        expected = self.assets_root / "textures/entity/equipment" / layer / f"{tex_path}.png"
                        self.assertTrue(expected.is_file(), f"{path.name} 缺贴图 {expected}")

    # -- blockstates -------------------------------------------------------- #

    def test_blockstates_are_never_copied(self) -> None:
        """blockstates 一个都不能释放：会与 CE 自己分配的状态表打架。"""
        self.assertFalse((self.assets_root / "blockstates").exists(),
                         "释放了 blockstates，会和 CE 的 state.auto_state 冲突")
        self.assertEqual(self.report["blockstates"]["copied"], 0)

    def test_blockstates_are_still_analyzed(self) -> None:
        """不释放不等于不看：可达性必须靠它算，权重变体也必须被发现。"""
        blockstates = self.report["blockstates"]
        self.assertGreater(blockstates["analyzed"], 600)
        self.assertIn("jungle_plant", blockstates["weighted_variants"])
        self.assertGreater(blockstates["multipart"], 0)

    # -- 硬限制 ------------------------------------------------------------ #

    def test_hard_limits_declared(self) -> None:
        """三条硬限制必须写在报告里，且带计数与近似说明。"""
        declared = {limit["id"]: limit for limit in self.report["hard_limits"]}
        for required in ("emissive_shader", "weighted_random_model", "mod_client_animation"):
            self.assertIn(required, declared, f"硬限制 {required} 没有声明")
            self.assertTrue(declared[required]["evidence"], f"{required} 缺少计数证据")
            self.assertTrue(declared[required]["approximation"], f"{required} 缺少近似说明")

    def test_emissive_textures_shipped_but_not_claimed_emissive(self) -> None:
        """`_e` 贴图随包释放（否则连普通贴图都没有），但不得宣称会发光。"""
        emissive = list(self.assets_root.rglob("textures/block/*_e.png"))
        self.assertGreater(len(emissive), 0, "_e 贴图没释放")
        declared = {limit["id"]: limit for limit in self.report["hard_limits"]}
        self.assertIn("不会发光", declared["emissive_shader"]["approximation"])

    def test_unsupported_dirs_declared(self) -> None:
        """不释放的目录必须在 unsupported 里交代，不能静默丢。"""
        unsupported = set(self.report["unsupported"])
        for directory in ("shaders", "materials", "materialmaps", "optifine",
                          "patterns", "sounds", "textures/gui", "textures/minimap"):
            self.assertIn(directory, unsupported, f"{directory} 没有交代原因")

    # -- 可达性 ------------------------------------------------------------ #

    def test_reachability_reported(self) -> None:
        """可达数必须小于等于释放数，且不可达的要有名单可查。"""
        reachable = self.report["reachable"]
        self.assertLessEqual(reachable["models"], self.report["copied"]["models"])
        self.assertGreater(reachable["models"], 2000)
        self.assertGreater(reachable["textures"], 900)
        self.assertGreater(reachable["item_models"], 400)
        self.assertIsInstance(reachable["unreachable_models"], list)


if __name__ == "__main__":
    unittest.main(verbosity=2)

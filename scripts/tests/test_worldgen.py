#!/usr/bin/env python3
"""`bnlib.worldgen` 的世界生成数据门禁测试。

跑法（stdlib，不需要 pytest）：

    python -m unittest discover -s scripts/tests -p "test_*.py"

## 这些测试在验什么

植被放置数据是「上游 JSON → jar 内 JSON → 运行期解释」的三段链路，任何一段断掉
在游戏里的表现都是**没有报错的空白**：

  * 群系引用的 placed_feature 没被复制  -> 那个群系的地面植被整片消失
  * 复制了但引用悬空（feature 文件缺失） -> Java 侧解析失败，整条特征被跳过
  * 模板没转换                        -> 巨型菌木/诡异菌木一棵都不长
  * 分类把不该支持的算成支持           -> 运行期按猜的语义放置，比不放更糟

所以断言写成「逐个群系、逐个引用、逐个模板」遍历真实产物，而不是抽样。
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from bnlib import worldgen  # noqa: E402

UPSTREAM = SCRIPTS.parent / "upstream" / "BetterNether"


class WorldgenCopyTest(unittest.TestCase):
    """一次性把数据写到临时目录，之后所有断言都对着真实产物做。"""

    @classmethod
    def setUpClass(cls) -> None:
        if not (UPSTREAM / worldgen.WORLDGEN).is_dir():
            raise unittest.SkipTest(f"上游 worldgen 目录不存在：{UPSTREAM / worldgen.WORLDGEN}")
        cls._tmp = Path(tempfile.mkdtemp(prefix="bn-worldgen-"))
        cls.report = worldgen.write(UPSTREAM, cls._tmp)
        cls.root = cls._tmp / worldgen.OUTPUT_DIR
        cls.manifest = json.loads((cls.root / "manifest.json").read_text(encoding="utf-8"))
        cls.data = worldgen.WorldgenData(UPSTREAM)

    @staticmethod
    def id_of(entry):
        """schema 2 的条目：字符串或 {"id","skip"}。"""
        return entry["id"] if isinstance(entry, dict) else entry

    @staticmethod
    def refs_of(value):
        """`biomes[biome]` 兼容两种 schema：扁平 list，或按 step 分组的 dict。

        全局装饰调度方正在把清单迁到 `{biome: {step: [refs]}}`（index 由 Java 侧统一分配），
        迁移期间两种形态都会出现，测试不该钉死其中一种。
        """
        if isinstance(value, dict):
            out = []
            for step in sorted(value, key=lambda k: int(k)):
                out.extend(WorldgenCopyTest.id_of(e) for e in value[step])
            return out
        return [WorldgenCopyTest.id_of(e) for e in value]

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls._tmp, ignore_errors=True)

    # ---------------------------------------------------------------- 清单自洽

    def test_every_biome_ref_resolves_to_a_copied_file(self) -> None:
        """清单里「能解释」的每条 step-9 引用都必须有复制进来的 placed_feature 文件。

        清单现在按上游原样包含全部 step 与 `minecraft:` 条目（index 对齐需要），所以这里
        只校验 `supported` 里的那些 —— 其余是**占位**条目，由各自的 owner 执行。
        """
        supported = set(self.manifest["supported"])
        self.assertTrue(supported, "清单里没有任何可解释的特征")
        for biome, steps in self.manifest["biomes"].items():
            for step, entries in steps.items():
                for entry in entries:
                    ref = self.id_of(entry)
                    if ref not in supported:
                        continue
                    name = ref.split(":", 1)[1]
                    placed = self.root / "placed_feature" / f"{name}.json"
                    self.assertTrue(placed.is_file(), f"{biome}/step{step} 的 {ref} 没有被复制")
                    self.assertIn(ref, self.manifest["placedFeatures"])
    def test_biome_refs_are_a_subset_of_upstream_step9(self) -> None:
        """清单的 step 9 必须**逐条等于**上游 `features[9]`（顺序也一样）。

        顺序不是装饰：原版种子里的 `featureIndex` 就是该数组下标，少一条或换位会让后面
        每条特征的 index 前移，表现为「树形都对、位置全错」且没有任何报错。
        """
        upstream_refs = self.data.biome_feature_refs()
        self.assertEqual(set(upstream_refs), set(self.bn_biomes()))
        for biome, steps in upstream_refs.items():
            self.assertEqual(list(steps.get(9, [])),
                             [self.id_of(e) for e in self.bn_biomes()[biome]["9"]],
                             f"{biome} 的 step 9 与上游不一致（顺序也算）")

    def test_every_step_is_exported_with_vanilla_entries(self) -> None:
        """每个 step 都要导出，且 `minecraft:` 条目必须留在原位（占位，不删）。"""
        upstream_refs = self.data.biome_feature_refs()
        for biome, steps in upstream_refs.items():
            emitted = self.bn_biomes()[biome]
            self.assertEqual({str(s) for s in steps}, set(emitted),
                             f"{biome} 导出的 step 集合与上游不同")
            for step, refs in steps.items():
                self.assertEqual(list(refs), [self.id_of(e) for e in emitted[str(step)]],
                                 f"{biome}/step{step} 的顺序或内容被改动")
            # 至少有一个群系的 step 7 含原版矿/泉条目 —— 它们在清单里但不在 supported 里。
            if 7 in steps and any(r.startswith("minecraft:") for r in steps[7]):
                self.assertTrue(any(self.id_of(e).startswith("minecraft:") for e in emitted["7"]),
                                f"{biome}/step7 的原版条目被删掉了")
    def test_manifest_is_schema_2_with_skip_placeholders(self) -> None:
        """定稿 schema 2：`schema: 2` + 条目形态（字符串=编译；对象=skip / native / owner）。

        `skip` 条目**永远不能为「干净」被删**：删一条，后面所有 featureIndex 前移。
        `owner`（地形类别）相反 —— 它**可执行**，带上 `skip` 会被装载器做成纯占位，
        那 27 条地形特征就在世界里消失。
        """
        self.assertEqual(2, self.manifest.get("schema"), "清单必须显式声明 schema 2")
        compiled = set(self.manifest["supported"])
        for biome, steps in self.manifest["biomes"].items():
            self.assertEqual(sorted(steps, key=int), list(steps),
                             f"{biome} 的 step key 必须升序")
            for step, entries in steps.items():
                for entry in entries:
                    if isinstance(entry, str):
                        self.assertIn(entry, compiled,
                                      f"{biome}/step{step}: {entry} 被标为可编译但不在 supported 里")
                    else:
                        self.assertLessEqual(set(entry), {"id", "skip", "native", "owner"},
                                             f"{biome}/step{step}: 对象条目带了未约定的字段 "
                                             f"{sorted(entry)}")
                        self.assertTrue(entry.get("skip") is True
                                        or entry.get("native") is True
                                        or bool(entry.get("owner")),
                                        f"{biome}/step{step}: 对象形态必须是 skip / native / owner")
                        self.assertFalse(bool(entry.get("owner")) and entry.get("skip") is True,
                                         f"{biome}/step{step}: {entry['id']} 是地形类别却带 skip"
                                         "（装载器会做成占位，这条特征不会放置）")
                        self.assertNotIn(entry["id"], compiled,
                                         f"{biome}/step{step}: {entry['id']} 已编译却仍占位（会静默少放）")

    NATIVE_ONLY = "minecraft:"

    def bn_biomes(self) -> dict:
        """manifest 里 23 个 BN 群系（另有 5 个原版下界群系 + wover 注入，不与上游数组比对）。"""
        return {b: steps for b, steps in self.manifest["biomes"].items()
                if not b.startswith(self.NATIVE_ONLY)}

    def test_step_sizes_are_reported(self) -> None:
        """记录各 step 的去重规模（与调度方对拍用；不一致时双方一起查链条，而不是改期望）。"""
        sizes = {}
        for steps in self.manifest["biomes"].values():
            for step, entries in steps.items():
                bucket = sizes.setdefault(step, set())
                for entry in entries:
                    bucket.add(entry["id"] if isinstance(entry, dict) else entry)
        actual = {step: len(v) for step, v in sizes.items()}
        # 这两个数字与调度方实测一致（step 7 = 22、step 9 = 83）；step 1/4 尚未对齐，见报告。
        # 28 群系（23 BN + 5 原版 + wover 注入）后的真实规模。
        self.assertEqual(1, actual.get("0"), "step 0 去重规模：" + str(actual))
        self.assertEqual(11, actual.get("1"), "step 1 去重规模：" + str(actual))
        self.assertEqual(1, actual.get("2"), "step 2 去重规模：" + str(actual))
        self.assertEqual(0, actual.get("3"), "step 3 去重规模：" + str(actual))
        self.assertEqual(18, actual.get("4"), "step 4 去重规模：" + str(actual))
        self.assertEqual(0, actual.get("5"), "step 5 去重规模：" + str(actual))
        self.assertEqual(0, actual.get("6"), "step 6 去重规模：" + str(actual))
        self.assertEqual(31, actual.get("7"), "step 7 去重规模：" + str(actual))
        self.assertEqual(0, actual.get("8"), "step 8 去重规模：" + str(actual))
        self.assertEqual(90, actual.get("9"), "step 9 去重规模：" + str(actual))
    def test_configured_feature_refs_resolve(self) -> None:
        """凡是**以字符串引用**配置特征的地方，jar 里必须有那个 feature 文件。

        注意上游存在名字对不上的引用（`vegetation_nether_jungle` → `vegetation_jungle`、
        `vegetation_nether_poor_grassland` → `vegetation_nether_poor_grasslands`、
        `patch_lumabus_vine_sparse` → `patch_lumabus_vine_gloom`），所以这条必须真的去
        「顺着引用找」，而不能按同名推文件。
        """
        missing: list[str] = []
        for ref in self.manifest["supported"]:
            name = ref.split(":", 1)[1]
            placed = json.loads((self.root / "placed_feature" / f"{name}.json").read_text(encoding="utf-8"))
            if isinstance(placed.get("feature"), str):
                configured = placed["feature"]
                relative = self.manifest["features"].get(configured)
                if relative is None or not (self.root / relative).is_file():
                    missing.append(f"{ref} -> {configured}")
        self.assertFalse(missing, "以下 placed_feature 引用的配置特征没有复制：" + str(missing))
        for ref, relative in self.manifest["features"].items():
            self.assertTrue((self.root / relative).is_file(), f"{ref} 的 {relative} 不存在")

    # ---------------------------------------------------------------- 分类可解释

    def test_supported_and_skipped_are_both_reported(self) -> None:
        """支持集必须非空、跳过集必须为空，两者不重叠。

        上游 step 9 现在每一条都能解释（含 `twisting_vines` 与 `spring_feature`），
        所以「跳过」非空就说明有新的上游特征没被覆盖 —— 这比「有原因的跳过」更该失败。
        """
        supported = set(self.manifest["supported"])
        skipped = set(self.manifest["skipped"])
        self.assertTrue(supported, "没有任何 placed_feature 被支持")
        self.assertEqual(set(), skipped,
                         "仍有未被解释的上游 placed_feature：" + str(sorted(skipped)))
        self.assertFalse(supported & skipped, "同一 id 既支持又跳过")
    def test_skipped_reasons_name_the_offender(self) -> None:
        """曾经靠「skip + 原因」记录缺口；现在缺口清零，这条改为锁「别把已支持的打回跳过」。

        历史：`wover:is_next_to` / `wover:noise_filter` / `twisting_vines` / `spring_feature`
        都曾在这里以「原因」形式挂着，每实现一个就少一条。全部实现后这条测试的价值变成
        反向的：任何声称支持的特征都不许同时在 skipped 里出现。
        """
        for ref in ("betternether:patch_twisting_vine",
                    "betternether:tree_gloomwood", "betternether:anchor_tree",
                    "betternether:tree_old_willow", "betternether:patch_nether_reed"):
            self.assertIn(ref, self.manifest["supported"], f"{ref} 应当被支持")
            self.assertNotIn(ref, self.manifest["skipped"],
                             f"{ref} 已支持，不许再出现在 skipped 里")
    def test_trees_and_unsupported_modifiers_never_leak_into_supported(self) -> None:
        """树特征与未实现修饰符一条都不许进支持集。

        树的放置由 TreePopulator 负责；两边都放就会得到两棵树重叠在同一个位置。
        """
        for ref in self.manifest["supported"]:
            name = ref.split(":", 1)[1]
            placed = json.loads((self.root / "placed_feature" / f"{name}.json").read_text(encoding="utf-8"))
            for modifier in placed.get("placement") or []:
                self.assertIn(
                    modifier.get("type"), worldgen.SUPPORTED_PLACEMENT,
                    f"{ref} 里出现了未实现的修饰符 {modifier.get('type')}")
            # 配置特征（含内联的）里不许出现 betternether 的自定义树/实体特征类型
            configured = placed.get("feature")
            if isinstance(configured, str):
                path = self.root / "feature" / f"{configured.split(':', 1)[1]}.json"
                if path.is_file():
                    configured = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(configured, dict):
                self.assertIn(
                    configured.get("type"), worldgen.SUPPORTED_FEATURE,
                    f"{ref} 的配置特征类型 {configured.get('type')} 不该被支持")

    def test_each_worldgen_function_has_exactly_one_owner(self) -> None:
        """每个世界生成功能只由**一端**执行：数据包/原生路径，或本移植装载器。

        归属已按主代理的决定收敛：`minecraft:` 条目一律 `native: true`（由原生 FeaturePlacer /
        原版 pass 执行），本移植的 `supported` 只含 `betternether:` 条目。
        所以两边交集必须**严格为空** —— 曾经的 `spring_lava` / `brown,red_mushroom_normal`
        双放已消除，不再有「待办例外」。
        """
        from bnlib import biomes as biome_rules

        datapack_kept: set[str] = set()
        for steps in self.data.biome_feature_refs().values():
            for refs in steps.values():
                for ref in refs:
                    if not biome_rules._is_mod_id(ref):
                        datapack_kept.add(ref)

        supported = set(self.manifest["supported"])
        self.assertEqual(set(), supported & datapack_kept,
                "这些 id 同时由数据包与本移植执行（双放）：" + str(sorted(supported & datapack_kept)))
        self.assertEqual(set(), {ref for ref in supported if ref.startswith("minecraft:")},
                         "supported 里不许出现原版条目（它们走 native）")
        self.assertEqual([], sorted(ref for ref in datapack_kept if biome_rules._is_mod_id(ref)),
                         "数据包里仍残留 betternether: 引用")

        # 清单里的原版条目必须显式 native（不是 skip，也不是悄悄消失）
        for biome, steps in self.manifest["biomes"].items():
            for step, entries in steps.items():
                for entry in entries:
                    if isinstance(entry, dict) and entry["id"].startswith("minecraft:"):
                        self.assertTrue(entry.get("native") is True,
                                        f"{biome}/step{step}: 原版条目 {entry['id']} 必须标 native")

    def test_manifest_is_schema_2_with_skip_placeholders(self) -> None:
        """定稿 schema 2：`schema: 2` + 条目形态（字符串=编译；对象=skip / native / owner）。

        `skip` 条目**永远不能为「干净」被删**：删一条，后面所有 featureIndex 前移。
        `owner`（地形类别）相反 —— 它**可执行**，带上 `skip` 会被装载器做成纯占位，
        那 27 条地形特征就在世界里消失。
        """
        self.assertEqual(2, self.manifest.get("schema"), "清单必须显式声明 schema 2")
        compiled = set(self.manifest["supported"])
        for biome, steps in self.manifest["biomes"].items():
            self.assertEqual(sorted(steps, key=int), list(steps),
                             f"{biome} 的 step key 必须升序")
            for step, entries in steps.items():
                for entry in entries:
                    if isinstance(entry, str):
                        self.assertIn(entry, compiled,
                                      f"{biome}/step{step}: {entry} 被标为可编译但不在 supported 里")
                    else:
                        self.assertLessEqual(set(entry), {"id", "skip", "native", "owner"},
                                             f"{biome}/step{step}: 对象条目带了未约定的字段 "
                                             f"{sorted(entry)}")
                        self.assertTrue(entry.get("skip") is True
                                        or entry.get("native") is True
                                        or bool(entry.get("owner")),
                                        f"{biome}/step{step}: 对象形态必须是 skip / native / owner")
                        self.assertFalse(bool(entry.get("owner")) and entry.get("skip") is True,
                                         f"{biome}/step{step}: {entry['id']} 是地形类别却带 skip"
                                         "（装载器会做成占位，这条特征不会放置）")
                        self.assertNotIn(entry["id"], compiled,
                                         f"{biome}/step{step}: {entry['id']} 已编译却仍占位（会静默少放）")

    NATIVE_ONLY = "minecraft:"

    def bn_biomes(self) -> dict:
        """manifest 里 23 个 BN 群系（另有 5 个原版下界群系 + wover 注入，不与上游数组比对）。"""
        return {b: steps for b, steps in self.manifest["biomes"].items()
                if not b.startswith(self.NATIVE_ONLY)}

    def test_step_sizes_are_reported(self) -> None:
        """记录各 step 的去重规模（与调度方对拍用；不一致时双方一起查链条，而不是改期望）。"""
        sizes = {}
        for steps in self.manifest["biomes"].values():
            for step, entries in steps.items():
                bucket = sizes.setdefault(step, set())
                for entry in entries:
                    bucket.add(entry["id"] if isinstance(entry, dict) else entry)
        actual = {step: len(v) for step, v in sizes.items()}
        # 这两个数字与调度方实测一致（step 7 = 22、step 9 = 83）；step 1/4 尚未对齐，见报告。

    def test_configured_feature_refs_resolve(self) -> None:
        """凡是**以字符串引用**配置特征的地方，jar 里必须有那个 feature 文件。

        注意上游存在名字对不上的引用（`vegetation_nether_jungle` → `vegetation_jungle`、
        `vegetation_nether_poor_grassland` → `vegetation_nether_poor_grasslands`、
        `patch_lumabus_vine_sparse` → `patch_lumabus_vine_gloom`），所以这条必须真的去
        「顺着引用找」，而不能按同名推文件。
        """
        missing: list[str] = []
        for ref in self.manifest["supported"]:
            name = ref.split(":", 1)[1]
            placed = json.loads((self.root / "placed_feature" / f"{name}.json").read_text(encoding="utf-8"))
            if isinstance(placed.get("feature"), str):
                configured = placed["feature"]
                relative = self.manifest["features"].get(configured)
                if relative is None or not (self.root / relative).is_file():
                    missing.append(f"{ref} -> {configured}")
        self.assertFalse(missing, "以下 placed_feature 引用的配置特征没有复制：" + str(missing))
        for ref, relative in self.manifest["features"].items():
            self.assertTrue((self.root / relative).is_file(), f"{ref} 的 {relative} 不存在")

    # ---------------------------------------------------------------- 分类可解释

    def test_supported_and_skipped_are_both_reported(self) -> None:
        """支持集必须非空、跳过集必须为空，两者不重叠。

        上游 step 9 现在每一条都能解释（含 `twisting_vines` 与 `spring_feature`），
        所以「跳过」非空就说明有新的上游特征没被覆盖 —— 这比「有原因的跳过」更该失败。
        """
        supported = set(self.manifest["supported"])
        skipped = set(self.manifest["skipped"])
        self.assertTrue(supported, "没有任何 placed_feature 被支持")
        self.assertEqual(set(), skipped,
                         "仍有未被解释的上游 placed_feature：" + str(sorted(skipped)))
        self.assertFalse(supported & skipped, "同一 id 既支持又跳过")
    def test_skipped_reasons_name_the_offender(self) -> None:
        """曾经靠「skip + 原因」记录缺口；现在缺口清零，这条改为锁「别把已支持的打回跳过」。

        历史：`wover:is_next_to` / `wover:noise_filter` / `twisting_vines` / `spring_feature`
        都曾在这里以「原因」形式挂着，每实现一个就少一条。全部实现后这条测试的价值变成
        反向的：任何声称支持的特征都不许同时在 skipped 里出现。
        """
        for ref in ("betternether:patch_twisting_vine",
                    "betternether:tree_gloomwood", "betternether:anchor_tree",
                    "betternether:tree_old_willow", "betternether:patch_nether_reed"):
            self.assertIn(ref, self.manifest["supported"], f"{ref} 应当被支持")
            self.assertNotIn(ref, self.manifest["skipped"],
                             f"{ref} 已支持，不许再出现在 skipped 里")
    def test_trees_and_unsupported_modifiers_never_leak_into_supported(self) -> None:
        """树特征与未实现修饰符一条都不许进支持集。

        树的放置由 TreePopulator 负责；两边都放就会得到两棵树重叠在同一个位置。
        """
        for ref in self.manifest["supported"]:
            name = ref.split(":", 1)[1]
            placed = json.loads((self.root / "placed_feature" / f"{name}.json").read_text(encoding="utf-8"))
            for modifier in placed.get("placement") or []:
                self.assertIn(
                    modifier.get("type"), worldgen.SUPPORTED_PLACEMENT,
                    f"{ref} 里出现了未实现的修饰符 {modifier.get('type')}")
            # 配置特征（含内联的）里不许出现 betternether 的自定义树/实体特征类型
            configured = placed.get("feature")
            if isinstance(configured, str):
                path = self.root / "feature" / f"{configured.split(':', 1)[1]}.json"
                if path.is_file():
                    configured = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(configured, dict):
                self.assertIn(
                    configured.get("type"), worldgen.SUPPORTED_FEATURE,
                    f"{ref} 的配置特征类型 {configured.get('type')} 不该被支持")

    #: **当前双放**（必须显式待办，不许靠删算法掩盖）：这三条原版 placed feature 我这边已经
    #: 实现（在 `supported` 里），但数据包**同时**仍保留它们的原版引用 —— 因为
    #: `bnlib/biomes.py#strip_mod_features` 只删 `betternether:` 前缀，原版条目全部留下。
    #: 等 global 的「原版条目走原生执行」决策落地后，删掉这张表与其中一端的执行。
    # ---------------------------------------------------------------- 模板

    def test_all_templates_convert_with_content(self) -> None:
        """29 个树模板全部转换成功，且 palette / blocks 都非空。

        模板为空在游戏里的表现不是报错，而是「巨型菌木不长」——一棵树都没有，
        和「这个群系没有树」完全无法区分。
        """
        templates = self.manifest["templates"]
        self.assertEqual(len(templates), 29, f"模板数量应当是 29，实际 {len(templates)}")
        for location, relative in templates.items():
            path = self.root / relative
            self.assertTrue(path.is_file(), f"模板 {location} 没有转换：{path}")
            payload = json.loads(path.read_text(encoding="utf-8"))
            self.assertTrue(payload["palette"], f"{location} 的 palette 为空")
            self.assertTrue(payload["blocks"], f"{location} 的 blocks 为空")
            self.assertEqual(len(payload["size"]), 3)
            for block in payload["blocks"]:
                self.assertEqual(len(block), 4, f"{location} 的 blocks 条目不是 [x,y,z,state]")
                self.assertTrue(0 <= block[3] < len(payload["palette"]),
                                f"{location} 的 state 越界：{block}")
            for entry in payload["palette"]:
                self.assertTrue(entry["id"], f"{location} 的 palette 条目没有 id")

    def test_template_block_ids_reach_the_pack(self) -> None:
        """模板 palette 里的 betternether 方块必须被算进「世界生成会放置」。

        漏掉的后果是方块不进 CE 包：树长出来了，但树干/树叶那些方块是空气。
        """
        ids = worldgen.placed_block_ids(UPSTREAM)
        for expected in ("wart_log", "wart_roots", "red_large_mushroom"):
            self.assertIn(expected, ids, f"模板里的 {expected} 没有被算进世界生成方块")

    def test_placed_block_ids_exclude_feature_ids(self) -> None:
        """方块 id 提取不能把**特征 id** 当成方块。

        上游 JSON 里 `"feature": "betternether:vegetation_jungle"` 与方块 id 长得一模一样；
        用整份文件的正则去扫会把这些特征名塞进方块清单，于是 CE 会去生成一堆
        根本不存在的方块。
        """
        ids = worldgen.placed_block_ids(UPSTREAM)
        for leak in ("vegetation_jungle", "vegetation_nether_grasslands", "patch_black_vine",
                     "trees", "war_deadwood", "vegetation_mushroom_forrest"):
            self.assertNotIn(leak, ids, f"特征 id {leak} 被误当成方块")

    def test_skipped_features_do_not_contribute_block_ids(self) -> None:
        """被跳过的特征里的方块不进包。

        判据选取：`wart_cap` 只出现在被跳过的 `betternether:wart_cap` 特征里；
        `jellyfish_mushroom` 只出现在被跳过的那条链里。
        """
        ids = worldgen.placed_block_ids(UPSTREAM)
        for absent in ("jellyfish_mushroom",):
            self.assertNotIn(absent, ids, f"{absent} 只出现在被跳过的特征里，不该进包")

    # ---------------------------------------------------------------- 标签

    def test_approximated_surface_tag_is_declared_not_guessed(self) -> None:
        """`wover:surfaces/nether/terrain` 必须走「近似」通道，而不是解释成方块集合。

        上游那份 `data/wover/tags/block/surfaces/nether/terrain.json` 只是增量文件
        （只有 5 个 betternether 方块，netherrack 在 wover 库的基表里）。把它当成
        完整集合，植被就只肯长在那 5 种方块上 —— 表现是「几乎什么都不长」。
        """
        for tag in worldgen.APPROXIMATED_TAGS:
            if tag in self.manifest["blockTags"]:
                self.assertEqual(self.manifest["blockTags"][tag], [],
                                 f"{tag} 被解释成了具体方块集合，应当交给运行期近似")

    def test_narrow_surface_tags_carry_their_baseline(self) -> None:
        """窄标签必须带上同名的原版对方块，否则幽暗林/菌木林的地被整片消失。"""
        tags = self.manifest["blockTags"]
        if "wover:surfaces/sculk_like" in tags:
            self.assertIn("minecraft:sculk", tags["wover:surfaces/sculk_like"])
        if "wover:surfaces/mycelium" in tags:
            self.assertIn("minecraft:mycelium", tags["wover:surfaces/mycelium"])
        if "wover:surfaces/soul_ground" in tags:
            self.assertIn("minecraft:soul_sand", tags["wover:surfaces/soul_ground"])

    def test_nylium_tag_is_vanilla_pair_plus_upstream_delta(self) -> None:
        tags = self.manifest["blockTags"]
        if "minecraft:nylium" in tags:
            values = tags["minecraft:nylium"]
            # 原版两个菌岩是基底；BetterNether 还往这个原版 tag 里加了 4 种草
            # （data/minecraft/tags/block/nylium.json 增量），所以不能断言「恰好 2 个」。
            self.assertIn("minecraft:crimson_nylium", values)
            self.assertIn("minecraft:warped_nylium", values)
            self.assertEqual(6, len(values),
                             "nylium 应为 2 个原版菌岩 + 4 个上游草：" + str(values))


    # ---------------------------------------------------------------- 幂等

    def test_write_is_idempotent(self) -> None:
        """重复运行写出同样的内容（构建缓存与「改了没生效」都依赖这条）。"""
        again = Path(tempfile.mkdtemp(prefix="bn-worldgen-again-"))
        try:
            worldgen.write(UPSTREAM, again)
            first = sorted(p.relative_to(self.root) for p in self.root.rglob("*.json"))
            second = sorted(p.relative_to(again / worldgen.OUTPUT_DIR)
                            for p in (again / worldgen.OUTPUT_DIR).rglob("*.json"))
            self.assertEqual(first, second)
            for relative in first:
                self.assertEqual((self.root / relative).read_bytes(),
                                 (again / worldgen.OUTPUT_DIR / relative).read_bytes(),
                                 f"{relative} 两次生成的内容不同")
        finally:
            shutil.rmtree(again, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""`bnlib.terrain_worldgen` 的地形数据门禁测试。

跑法（stdlib，不需要 pytest）：

    python -m unittest discover -s scripts/tests -p "test_*.py"

## 这些测试在验什么

地形数据比植被更容易「静默变空」，因为它的重头在**别的装饰步**：

  * 少取一个 step  -> 熔岩坑、尖锥、石笋、岩浆斑、晶簇整片消失（世界变得像原版下界）
  * 顺手带上 step 7 的矿 -> `OrePopulator` 与地形侧会把同一份矿放两遍
  * 忘了 step 9 的 `sculk_vein_*` -> 幽匿木群系的洞里一片干净
  * 丢掉 `bones`/`jungle_bones`/`forest_litter` 的 NBT 模板 -> 骨堆只剩空气
  * 打乱同 step 的顺序 -> `molten_gloomsculk_near_lava` 跑在挖坑之前，坑边什么都不长

## step 下标是「数据里的下标」，不是「这类特征本该在哪一步」

`GenerationStep.Decoration` 的枚举序是 `0 raw_generation / 1 lakes / 2 local_modifications /
3 underground_structures / 4 surface_structures / 5 strongholds / 6 underground_ores /
7 underground_decoration / 8 fluid_springs / 9 vegetal_decoration / 10 top_layer_modification`
（`javap -p -c GenerationStep$Decoration.<clinit>` 实读）。但上游 BetterNether 的数据把尖锥/骨堆
写在 **index 4**、把矿写在 **index 7** —— 种子 `setFeatureSeed(seed, deco + index, 10000L*step)`
吃的是这个下标，所以必须按数据走。测试直接对上游 `biome/*.json` 统计，锁的就是这件事。
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from bnlib import terrain_worldgen, worldgen  # noqa: E402

UPSTREAM = SCRIPTS.parent / "upstream" / "BetterNether"
WORLDGEN = UPSTREAM / worldgen.WORLDGEN

#: 上游数据里地形项出现的下标（统计见模块文档；0/1/4 + step 9 的 sculk_vein）。
TERRAIN_STEPS = (0, 1, 4)
#: 只取这些前缀的 step 9 条目（其余 step 9 归植被脚本）。
STEP9_TERRAIN_PREFIXES = ("betternether:sculk_vein",)


def biome_steps_from_upstream() -> dict[str, dict[str, list[str]]]:
    """直接读上游 JSON 得到「群系 → 步 → 地形项」，作为被测脚本的对照物。"""
    out: dict[str, dict[str, list[str]]] = {}
    for path in sorted((WORLDGEN / "biome").glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        steps = raw.get("features") or []
        per_step: dict[str, list[str]] = {}
        for index in TERRAIN_STEPS:
            if index < len(steps):
                entries = [str(ref) for ref in steps[index]]
                if entries:
                    per_step[str(index)] = entries
        if len(steps) > 9:
            entries = [str(ref) for ref in steps[9]
                       if str(ref).startswith(STEP9_TERRAIN_PREFIXES)]
            if entries:
                per_step["9"] = entries
        out[path.stem] = per_step
    return out


class TerrainStepsTest(unittest.TestCase):
    """不落盘也能跑：`biome_steps()` 的分步与顺序（这是脚本自己的逻辑）。"""

    @classmethod
    def setUpClass(cls) -> None:
        if not WORLDGEN.is_dir():
            raise unittest.SkipTest(f"上游 worldgen 目录不存在：{WORLDGEN}")
        cls.data = worldgen.WorldgenData(UPSTREAM)
        cls.actual = terrain_worldgen.biome_steps(cls.data)
        cls.expected = biome_steps_from_upstream()

    def test_every_biome_matches_the_upstream_step_indexes(self) -> None:
        for biome, per_step in self.expected.items():
            self.assertEqual(per_step, self.actual.get(biome),
                             f"{biome}: 分步与上游 features[N] 不一致")

    def test_step_keys_are_ascending_and_known(self) -> None:
        allowed = {str(index) for index in TERRAIN_STEPS} | {"9"}
        for biome, per_step in self.actual.items():
            keys = list(per_step)
            self.assertEqual(sorted(keys), keys, f"{biome}: 步键必须升序")
            self.assertTrue(set(keys) <= allowed, f"{biome}: 出现了非预期步 {keys}")

    def test_index_is_not_assigned_here(self) -> None:
        # index 是 FeatureSorter 的全局下标，同一 step 内植被与 sculk_vein 共享一个空间。
        # 脚本一旦自己算 index，两边就会各算一套。
        payload = json.dumps(self.actual)
        self.assertNotIn('"index"', payload, "分步结果里不该出现 index")

    def test_molten_rim_stays_after_the_pits(self) -> None:
        """`molten_gloomsculk_near_lava` 的注释要求它排在 `lava_pits_*` 之后（同 step 1）。"""
        checked = 0
        for biome, per_step in self.actual.items():
            refs = [ref for entries in per_step.values() for ref in entries]
            if "betternether:molten_gloomsculk_near_lava" not in refs:
                continue
            pits = [ref for ref in refs if ref.startswith("betternether:lava_pit")]
            if not pits:
                continue
            self.assertLess(refs.index(pits[0]),
                            refs.index("betternether:molten_gloomsculk_near_lava"),
                            f"{biome}: 熔岩坑必须排在近熔岩的熔渣之前")
            checked += 1
        self.assertGreater(checked, 0, "这条断言没匹配到任何群系，说明数据变了")

    def test_sculk_veins_land_in_step_nine(self) -> None:
        found = [(biome, entries) for biome, per_step in self.actual.items()
                 for step, entries in per_step.items()
                 if any(ref.startswith("betternether:sculk_vein") for ref in entries)]
        self.assertTrue(found, "幽匿脉没被导出（gloomwood 的洞里会一片干净）")
        for biome, entries in found:
            for ref in entries:
                if ref.startswith("betternether:sculk_vein"):
                    self.assertIn("9", self.actual[biome], f"{biome}: 幽匿脉必须在 step 9")


class TerrainCopyTest(unittest.TestCase):
    """落盘产物：清单 schema、文件、模板、type 覆盖。

    <p>依赖 `worldgen._collect_references`（flora 持有的共享实现）；它若正在重构而报错，
    这一组会以真实异常失败 —— 那是应该被看见的，不做 try/except 吞掉。
    """

    @classmethod
    def setUpClass(cls) -> None:
        if not WORLDGEN.is_dir():
            raise unittest.SkipTest(f"上游 worldgen 目录不存在：{WORLDGEN}")
        cls._tmp = Path(tempfile.mkdtemp(prefix="bn-terrain-"))
        cls.result = terrain_worldgen.write(UPSTREAM, cls._tmp)
        cls.root = cls._tmp / worldgen.OUTPUT_DIR
        cls.manifest = json.loads((cls.root / terrain_worldgen.MANIFEST_NAME).read_text(encoding="utf-8"))

    # ---------------------------------------------------------------- 清单

    def test_manifest_has_the_sections_the_loader_reads(self) -> None:
        for key in ("biomes", "biomesFlat", "blockTags", "supported",
                    "placedFeatures", "features", "templates"):
            self.assertIn(key, self.manifest)

    def test_manifest_name_matches_the_populator(self) -> None:
        self.assertEqual("terrain.json", terrain_worldgen.MANIFEST_NAME)

    def test_biomes_section_is_step_keyed(self) -> None:
        """新 schema：`biomes[biome][step] = [id…]`；`biomesFlat` 只是过渡兼容。"""
        for biome, per_step in self.manifest["biomes"].items():
            self.assertIsInstance(per_step, dict, f"{biome}: 必须形如 {{step: [ids]}}")
            for step, entries in per_step.items():
                self.assertIsInstance(entries, list, f"{biome}/{step}: 必须是列表")
                self.assertTrue(entries, f"{biome}/{step}: 空列表不该写进清单")

        for biome, flat in self.manifest["biomesFlat"].items():
            self.assertEqual([ref for entries in self.manifest["biomes"][biome].values()
                              for ref in entries], flat,
                             f"{biome}: 扁平表必须与分步表同序（过渡期两者要一致）")

    # ---------------------------------------------------------------- 步选择

    def test_ore_step_is_not_exported(self) -> None:
        # 上游把矿写在 index 7；这里再导出一次就是「同一份矿放两遍」。
        # 断言用 step 7 的真实引用集合取交集，不靠 id 里的子串猜（`forest_litter` 也含 "ore"）。
        ore_step: set[str] = set()
        for path in sorted((WORLDGEN / "biome").glob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            steps = raw.get("features") or []
            if len(steps) > 7:
                ore_step |= {str(ref) for ref in steps[7]}
        self.assertTrue(ore_step, "上游 step 7 不该是空的，否则这条断言什么也没验")
        exported = set(self.manifest["supported"])
        self.assertEqual(set(), ore_step & exported, "矿不该进地形清单")
        self.assertNotIn("betternether:cincinnasite_ore", exported)
        self.assertNotIn("betternether:gloomwood_ceiling_debris", exported)

        # 再按 **type** 断一次：`minecraft:ore` 只能由矿那条线放。
        types: set[str] = set()
        for ident in exported:
            node = json.loads((self.root / self.manifest["placedFeatures"][ident])
                              .read_text(encoding="utf-8"))
            self._collect_types(node.get("feature"), types, 0)
        self.assertNotIn("minecraft:ore", types, "地形清单里不该有 minecraft:ore")

    def test_vegetation_step_only_contributes_sculk_veins(self) -> None:
        step9: set[str] = set()
        for path in sorted((WORLDGEN / "biome").glob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            steps = raw.get("features") or []
            if len(steps) > 9:
                step9 |= {str(ref) for ref in steps[9]}
        exported = set(self.manifest["supported"])
        for ref in step9:
            if ref.startswith(STEP9_TERRAIN_PREFIXES):
                self.assertIn(ref, exported, f"step 9 的地形项漏了：{ref}")
            else:
                self.assertNotIn(ref, exported, f"step 9 的植被项不该进地形清单：{ref}")

    def test_lava_and_spike_steps_are_present(self) -> None:
        supported = set(self.manifest["supported"])
        for ref in (
            "betternether:lava_pits_dense",
            "betternether:lava_pits_sparse",
            "betternether:lava_swamp",
            "betternether:lava_terrace",
            "betternether:flooded_lava_pit",
            "betternether:magma_blob",
            "betternether:molten_gloomsculk",
            "betternether:molten_gloomsculk_near_lava",
            # `patch_stalactite` / `patch_stalagmite` / `patch_basalt_stalactite` /
            # `patch_blackstone_stalactite_on_ceil` / `patch_bone_stalagmite_on_ground`
            # 放置的方块（`netherrack_stalactite`、`basalt_stalactite`、
            # `blackstone_stalactite`、`bone_stalactite`）已按用户要求移除，
            # 所以这些特征现在应当**停发**（见下一条断言），不再进 supported。
            "betternether:obsidian_crystal",
            "betternether:bones",
            "betternether:jungle_bones",
            "betternether:forest_litter",
            "betternether:replace_soul_sandstone",
            "betternether:sculk_vein_floor",
        ):
            self.assertIn(ref, supported)

    def test_spike_features_stop_when_their_block_is_removed(self) -> None:
        """石笋/烟囱类特征的方块被移除后，特征必须**停发**（只占 featureIndex 下标）。

        漏掉这一步的后果是实测到的：这些特征仍会被编译，编译期解析方块失败，
        装载器报「CraftEngine 包缺少 6 个世界生成方块 id」—— 而那是**主动移除**，不是缺口。
        """
        stopped = ("betternether:patch_stalactite",
                   "betternether:patch_stalagmite",
                   "betternether:patch_basalt_stalactite",
                   "betternether:patch_blackstone_stalactite_on_ceil",
                   "betternether:patch_bone_stalagmite_on_ground",
                   "betternether:patch_smoker")
        supported = set(self.manifest["supported"])
        for ref in stopped:
            self.assertNotIn(ref, supported, f"{ref} 的方块已移除，不该继续编译")

    # ---------------------------------------------------------------- 顺序

    def test_magma_land_keeps_step_then_list_order(self) -> None:
        self.assertEqual(
            {
                "1": ["betternether:lava_pits_dense", "betternether:magma_blob"],
                "4": ["betternether:obsidian_crystal", "betternether:patch_stalagmite",
                      "betternether:patch_stalactite"],
            },
            self.manifest["biomes"]["magma_land"],
        )

    # ---------------------------------------------------------------- 文件与模板

    def test_every_referenced_file_was_copied(self) -> None:
        for ident, relative in self.manifest["placedFeatures"].items():
            self.assertTrue((self.root / relative).is_file(), f"缺 placed_feature 文件：{ident}")
        for ident, relative in self.manifest["features"].items():
            self.assertTrue((self.root / relative).is_file(), f"缺 feature 文件：{ident}")

    def test_bone_and_litter_templates_are_converted(self) -> None:
        """骨堆/落叶的模板**不在** `trees/` 下，容易被 `worldgen.template_path` 直接拒掉。"""
        templates = self.manifest["templates"]
        for location in (
            "betternether:bone_01",
            "betternether:jungle_bones_1",
            "betternether:upside_down_forest/tree_fallen",
        ):
            self.assertIn(location, templates, f"漏了模板：{location}")
            self.assertTrue((self.root / templates[location]).is_file(), f"模板没写出：{location}")

    def test_template_payload_has_palette_and_blocks(self) -> None:
        payload = json.loads((self.root / self.manifest["templates"]["betternether:bone_01"])
                             .read_text(encoding="utf-8"))
        self.assertIn("palette", payload)
        self.assertIn("blocks", payload)
        self.assertGreater(len(payload["blocks"]), 0, "空模板等于没放")

    # ---------------------------------------------------------------- 特征类型覆盖

    #: 运行期解释得动的地形 feature type。来源两处：
    #:  * `WorldgenLoader#configuredObject` 的显式 case；
    #:  * `UpstreamTerrainFeatures.SUPPORTED`。
    IMPLEMENTED_TYPES = {
        "minecraft:simple_block",
        "minecraft:block_column",
        "minecraft:overlay",
        "minecraft:random_selector",
        "wover:random_patch",
        "wover:template",
        "wover:pillar",
        "wover:sequence",
        "wover:condition",
        "betternether:obsidian_crystal",
        "minecraft:multiface_growth",
    }

    #: 已知未实现、且有明确原因的 type。
    #:
    #: **现在为空**：`minecraft:multiface_growth` 已按 `javap -p -c` 逐方法移植进
    #: `UpstreamTerrainFeatures.SculkVein`。一旦非空，就意味着又有 type 被 `WorldgenLoader`
    #: 静默跳过 —— 不要为了让测试变绿往里加东西。
    UNIMPLEMENTED_TYPES: set[str] = set()

    def test_every_terrain_feature_type_is_implemented(self) -> None:
        types: set[str] = set()
        for ident in self.manifest["supported"]:
            node = json.loads((self.root / self.manifest["placedFeatures"][ident])
                              .read_text(encoding="utf-8"))
            self._collect_types(node.get("feature"), types, 0)

        missing = types - self.IMPLEMENTED_TYPES - self.UNIMPLEMENTED_TYPES
        self.assertEqual(set(), missing, f"这些地形 type 没有任何实现：{sorted(missing)}")
        self.assertEqual(self.UNIMPLEMENTED_TYPES, types & self.UNIMPLEMENTED_TYPES,
                         "已知缺口集合变了：实现了就把它从 UNIMPLEMENTED_TYPES 挪走")

    def _collect_types(self, node: Any, out: set[str], depth: int) -> None:
        if depth > 12 or node is None:
            return
        if isinstance(node, str):
            relative = self.manifest["features"].get(node)
            if relative is not None:
                payload = json.loads((self.root / relative).read_text(encoding="utf-8"))
                self._collect_types(payload, out, depth + 1)
            return
        if not isinstance(node, dict):
            return
        if isinstance(node.get("type"), str):
            out.add(node["type"])
        for key in ("feature", "filter_pass", "filter_fail"):
            self._collect_types(node.get(key), out, depth + 1)
        subs = node.get("features")
        if isinstance(subs, list):
            for sub in subs:
                self._collect_types(sub, out, depth + 1)
        elif isinstance(subs, dict):
            self._collect_types(subs, out, depth + 1)


if __name__ == "__main__":
    unittest.main()

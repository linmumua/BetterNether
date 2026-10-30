#!/usr/bin/env python3
"""`bnlib.worldgen._collect_references` 的**嵌套引用闭合**门禁。

跑法（stdlib，不需要 pytest）：

    python -m unittest scripts.tests.test_worldgen_nested_refs -v

## 这些测试在验什么

分类只回答「这条链解释得动吗」，真正决定 jar 里有没有文件的是 `_collect_references`。
它此前只顺着 `random_patch` / `overlay` / `random_selector` 走，于是上游这两种嵌套形态
**整段被漏掉**：

  * `wover:sequence.features` —— 一串按序执行的 placed feature:
      `betternether:flooded_lava_pit` → `extend_basalt` / `flooded_lava_pit_surface` / `mark`
      （前者在 `flooded_deltas` 的 **step 1**，两个钟乳石补丁在 **step 4**）
      `betternether:patch_blackstone_stalactite_on_ceil` / `..._stalagmite_on_ground`
      → `terracotta_clump`
  * `wover:condition.filter_pass` / `filter_fail` —— 条件成立/不成立各跑一条 placed feature:
      `betternether:flooded_lava_pit_surface` → `lava_pit` / `basalt_or_air`

漏掉它们不会报错：清单照旧把整条链登记成 supported，运行期在缺文件那一层解析失败、整条
特征被跳过，世界里就少一层岩浆湖底/黑石钟乳。所以断言写成「从这几个真实根出发，逐个
nested id 都必须在 `placed_files` / `feature_files` 里，且源文件真实存在」。

## 为什么用真实上游数据而不是造一份

造数据只能证明「我按自己想的形状写了 walker」。这里的根与嵌套 id 全部来自上游 JSON 实读，
将来上游换成别的嵌套写法则这条测试会先红，而不是等到运行期丢文件。

## 为什么另外钉一次步骤顺序

`_collect_references` 只填 `placed_files` / `feature_files`，不碰 `biome_steps`；但它是
`build()` 里最后一步，谁在遍历里「顺手排序/去重」就会让 `manifest.biomes[biome][step]`
的数组下标错位 —— 而那个下标就是原版种子里的 `featureIndex`。所以这里对**上游原样顺序**
再断言一次（manifest 由 `write()` 从 `biome_steps` 1:1 投影而来，不重排）。
"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))

from bnlib import terrain_worldgen, worldgen  # noqa: E402

UPSTREAM = SCRIPTS.parent / "upstream" / "BetterNether"


class NestedReferenceClosureTest(unittest.TestCase):
    """从真实上游的嵌套根出发，逐个 id 验文件闭合。"""

    @classmethod
    def setUpClass(cls) -> None:
        if not (UPSTREAM / worldgen.WORLDGEN).is_dir():
            raise unittest.SkipTest(f"上游 worldgen 目录不存在：{UPSTREAM / worldgen.WORLDGEN}")
        cls.data = worldgen.WorldgenData(UPSTREAM)

    def collect(self, roots: list[str]) -> worldgen.Report:
        report = worldgen.Report()
        worldgen._collect_references(self.data, list(roots), report)
        return report

    def assert_placed_collected(self, report: worldgen.Report, ident: str) -> None:
        self.assertIn(ident, report.placed_files,
                      f"{ident} 的 placed_feature 文件没被收集（嵌套引用漏了一段）")
        source = worldgen.WorldgenData.source_path("placed_feature", ident)
        self.assertTrue(source.is_file(), f"{ident} 登记了却没有源文件：{source}")

    # ------------------------------------------------------------------ sequence

    def test_sequence_children_are_collected(self) -> None:
        """`wover:sequence.features` 的每个子项都是一个独立的 placed_feature 文件。"""
        report = self.collect(["betternether:flooded_lava_pit"])
        for ident in ("betternether:extend_basalt",
                      "betternether:flooded_lava_pit_surface",
                      "betternether:mark"):
            self.assert_placed_collected(report, ident)

    def test_sequence_children_are_collected_for_stalactite_patches(self) -> None:
        """两个黑石钟乳/石笋补丁都在 sequence 里引用 `terracotta_clump`。"""
        for root in ("betternether:patch_blackstone_stalactite_on_ceil",
                     "betternether:patch_blackstone_stalagmite_on_ground"):
            with self.subTest(root=root):
                report = self.collect([root])
                self.assert_placed_collected(report, root)
                self.assert_placed_collected(report, "betternether:terracotta_clump")

    # ----------------------------------------------------------------- condition

    def test_condition_branches_are_collected(self) -> None:
        """`filter_pass` / `filter_fail` 各指向一条 placed feature，两条都要收。"""
        report = self.collect(["betternether:flooded_lava_pit_surface"])
        self.assert_placed_collected(report, "betternether:lava_pit")
        self.assert_placed_collected(report, "betternether:basalt_or_air")

    def test_condition_branch_also_pulls_its_configured_feature(self) -> None:
        """分支再往下一层是配置特征：只收 placed 文件等于交出悬空引用。"""
        report = self.collect(["betternether:flooded_lava_pit_surface"])
        self.assertIn("betternether:lava_pit", report.feature_files,
                      "lava_pit 的配置特征文件没被收集")
        self.assertTrue(
            worldgen.WorldgenData.source_path("feature", "betternether:lava_pit").is_file())

    # -------------------------------------------------------------- 闭合与稳定性

    def test_transitive_walk_is_closed_and_deterministic(self) -> None:
        """整条链闭合：登记的每个 placed 文件都能继续被解析，且两次结果相同。"""
        root = "betternether:flooded_lava_pit"
        first = self.collect([root])
        second = self.collect([root])
        self.assertEqual(first.placed_files, second.placed_files,
                         "同一条链两次收集结果不同 —— walker 里混进了顺序相关状态")
        self.assertEqual(first.feature_files, second.feature_files)
        for ident in first.placed_files:
            self.assertIsNotNone(self.data.placed(ident), f"{ident} 登记了但解析不到 JSON")
        for ident in first.feature_files:
            self.assertIsNotNone(self.data.configured(ident), f"{ident} 的配置特征解析不到")

    def test_supported_set_stays_closed_after_the_walk(self) -> None:
        """`build()` 产出的整份文件清单必须条条有源文件。

        这条不是抽样：`supported` 将来一旦包含 `sequence` / `condition` 形式的链，
        这里就是「文件是否真被收全」的唯一门禁。
        """
        report = worldgen.build(UPSTREAM)
        self.assertTrue(report.supported, "分类结果为空，这条断言什么都没证明")
        for ident in report.placed_files:
            source = worldgen.WorldgenData.source_path("placed_feature", ident)
            self.assertTrue(source.is_file(), f"placed {ident} 缺源文件：{source}")
        for ident in report.feature_files:
            source = worldgen.WorldgenData.source_path("feature", ident)
            self.assertTrue(source.is_file(), f"configured {ident} 缺源文件：{source}")

    # ------------------------------------------------------------ 步骤与下标顺序

    def test_biome_step_index_order_matches_upstream(self) -> None:
        """每个群系每个 step 的数组内容与顺序都必须**逐条等于**上游 JSON。

        这就是 `manifest.biomes[biome][step][index]` 的来源：下标即原版种子里的
        `featureIndex`，少一条或换位会让该 step 后面每条特征都换一条随机流。
        """
        report = worldgen.build(UPSTREAM)
        self.assertTrue(report.biome_steps, "biome_steps 为空，这条断言什么都没证明")
        for path in sorted((UPSTREAM / worldgen.WORLDGEN / "biome").glob("*.json")):
            biome = path.stem
            with self.subTest(biome=biome):
                raw = json.loads(path.read_text(encoding="utf-8"))
                expected = {step: [str(ref) for ref in refs]
                            for step, refs in enumerate(raw.get("features") or [])}
                self.assertIn(biome, report.biome_steps, f"{biome} 没有进分类结果")
                actual = report.biome_steps[biome]
                self.assertEqual(sorted(expected), sorted(actual),
                                 f"{biome} 的 step 集合被改动")
                for step in sorted(expected):
                    self.assertEqual(expected[step], list(actual[step]),
                                     f"{biome}/step{step} 的条目或顺序与上游不一致")


class MergedWorldgenProductTest(unittest.TestCase):
    """两份导出写进**同一个** `worldgen-upstream/` 根之后的**产物**契约。

    ## 为什么必须测合并产物

    `extract_bn` 先跑 `worldgen.write()`（植被）、再跑 `terrain_worldgen.write()`（地形），
    两者落在同一个根下。所以「文件齐不齐」不能看任何单份清单的 `placedFeatures`，要看
    **根里实际有没有那个 JSON**：植被清单里的 `skip` 条目，文件是由地形那次写出来的。
    只测 `_collect_references` 一个函数，等于只测了「我以为的产物」。

    ## 发布契约（本类钉住的部分）

      * 普通 id               → 本移植编译并放置，文件必须在根里；
      * `{"native": true}`     → 原版走注册表，根里**不要求**文件；
      * `{"owner": "terrain"}` → **可执行**（类别标注），文件由地形那次写进根里；
      * `{"skip": true}`       → 本移植侧谁都不执行（矿 / 已停发），不要求文件。

    191 处占位里绝大多数属于 `owner` 那一类 —— 它们既不是缺口，也不能被做成占位：
    运行期只有一个装饰计划，把它标成 skip 就等于那 69 个槽位在世界里消失。
    """

    @classmethod
    def setUpClass(cls) -> None:
        if not (UPSTREAM / worldgen.WORLDGEN).is_dir():
            raise unittest.SkipTest(f"上游 worldgen 目录不存在：{UPSTREAM / worldgen.WORLDGEN}")
        cls._tmp = Path(tempfile.mkdtemp(prefix="bn-worldgen-merge-"))
        worldgen.write(UPSTREAM, cls._tmp)
        terrain_worldgen.write(UPSTREAM, cls._tmp)
        cls.root = cls._tmp / worldgen.OUTPUT_DIR
        cls.manifest = json.loads((cls.root / "manifest.json").read_text(encoding="utf-8"))
        cls.terrain = json.loads(
            (cls.root / terrain_worldgen.MANIFEST_NAME).read_text(encoding="utf-8"))
        cls.data = worldgen.WorldgenData(UPSTREAM)

    @classmethod
    def tearDownClass(cls) -> None:
        shutil.rmtree(cls._tmp, ignore_errors=True)

    # -------------------------------------------------------------- 小工具

    @staticmethod
    def flatten(steps: dict[str, list[Any]]) -> list[tuple[int, int, Any]]:
        """`{step: [entry…]}` → [(step, index, entry)]，下标就是 featureIndex 的来源。"""
        rows: list[tuple[int, int, Any]] = []
        for step in sorted(steps, key=int):
            for index, entry in enumerate(steps[step]):
                rows.append((int(step), index, entry))
        return rows

    def entries(self) -> list[tuple[str, int, int, Any]]:
        out: list[tuple[str, int, int, Any]] = []
        for biome, steps in self.manifest["biomes"].items():
            for step, index, entry in self.flatten(steps):
                out.append((biome, step, index, entry))
        return out

    def placed_file(self, ident: str) -> Path:
        return self.root / "placed_feature" / f"{ident.split(':', 1)[1]}.json"

    def feature_file(self, ident: str) -> Path:
        return self.root / "feature" / f"{ident.split(':', 1)[1]}.json"

    def walk_product(self, ident: str, kinds: dict[str, set[str]], depth: int = 0) -> None:
        """在**写出来的文件**里顺着嵌套引用走一遍，把命中的类型与缺失的 id 记进 `kinds`。

        `kinds` 三个键：`placed`/`feature` 是被引用到的 id，`missing` 是根里找不到文件的 id。
        """
        if depth > 12:
            return
        placed = self.placed_file(ident)
        if placed.is_file():
            kinds["placed"].add(ident)
            node = json.loads(placed.read_text(encoding="utf-8"))
            self.walk_configured(node.get("feature"), kinds, depth + 1)
            return
        configured = self.feature_file(ident)
        if configured.is_file():
            kinds["feature"].add(ident)
            self.walk_configured(json.loads(configured.read_text(encoding="utf-8")),
                                 kinds, depth + 1)
            return
        if not ident.startswith("minecraft:"):
            kinds["missing"].add(ident)

    def walk_configured(self, node: Any, kinds: dict[str, set[str]], depth: int) -> None:
        if depth > 12:
            return
        if isinstance(node, str):
            self.walk_product(node, kinds, depth + 1)
            return
        if not isinstance(node, dict):
            return
        kind = node.get("type")
        if kind == "wover:random_patch":
            self.walk_configured(node.get("feature"), kinds, depth + 1)
        elif kind in ("minecraft:overlay", "wover:sequence"):
            subs = node.get("features")
            subs = subs if isinstance(subs, list) else [subs]
            for sub in subs:
                self.walk_configured(sub, kinds, depth + 1)
        elif kind == "minecraft:random_selector":
            for entry in node.get("features") or []:
                if isinstance(entry, dict):
                    self.walk_configured(entry.get("feature"), kinds, depth + 1)
            if "default" in node:
                self.walk_configured(node["default"], kinds, depth + 1)
        elif kind == "wover:condition":
            for key in ("filter_pass", "filter_fail"):
                if key in node:
                    self.walk_configured(node[key], kinds, depth + 1)

    def predicate_tags(self, ident: str, depth: int = 0) -> set[str]:
        """写出来的 placed/configured 文件里出现的所有 `matching_block_tag`。"""
        tags: set[str] = set()
        if depth > 12:
            return tags

        def walk_predicate(predicate: Any) -> None:
            if not isinstance(predicate, dict):
                return
            if predicate.get("type") == "minecraft:matching_block_tag":
                tags.add(str(predicate.get("tag")))
            for sub in predicate.get("predicates") or []:
                walk_predicate(sub)
            if isinstance(predicate.get("predicate"), dict):
                walk_predicate(predicate["predicate"])

        def walk_configured(node: Any, level: int) -> None:
            if level > 12:
                return
            if isinstance(node, str):
                for path in (self.feature_file(node), self.placed_file(node)):
                    if path.is_file():
                        walk_file(path, level + 1)
                        return
                return
            if not isinstance(node, dict):
                return
            kind = node.get("type")
            if kind == "wover:random_patch":
                walk_configured(node.get("feature"), level + 1)
            elif kind in ("minecraft:overlay", "wover:sequence"):
                subs = node.get("features")
                subs = subs if isinstance(subs, list) else [subs]
                for sub in subs:
                    walk_configured(sub, level + 1)
            elif kind == "minecraft:random_selector":
                for entry in node.get("features") or []:
                    if isinstance(entry, dict):
                        walk_configured(entry.get("feature"), level + 1)
                if "default" in node:
                    walk_configured(node["default"], level + 1)
            elif kind == "wover:condition":
                for key in ("filter_pass", "filter_fail"):
                    if key in node:
                        walk_configured(node[key], level + 1)

        def walk_file(path: Path, level: int) -> None:
            node = json.loads(path.read_text(encoding="utf-8"))
            for modifier in node.get("placement") or []:
                if isinstance(modifier, dict):
                    walk_predicate(modifier.get("predicate"))
            if path.parent.name == "placed_feature":
                walk_configured(node.get("feature"), level + 1)
            else:
                walk_configured(node, level + 1)

        for path in (self.placed_file(ident), self.feature_file(ident)):
            if path.is_file():
                walk_file(path, depth)
                return tags
        return tags

    # ------------------------------------------------------------ 条目与文件

    def test_every_executable_entry_has_a_file_in_the_merged_root(self) -> None:
        """普通 id 与 `owner` 条目都必须在**合并后的根**里有文件；`skip` 条目才允许没有。"""
        compiled = set(self.manifest["supported"])
        for biome, step, index, entry in self.entries():
            if isinstance(entry, dict):
                ref = entry["id"]
                if entry.get("native"):
                    continue
                if entry.get("skip"):
                    continue
            else:
                ref = entry
                self.assertIn(ref, compiled, f"{biome}/step{step}/{index} 的 {ref} 不在 supported 里")
            self.assertTrue(self.placed_file(ref).is_file(),
                            f"{biome}/step{step}/{index} 的 {ref} 在合并产物里没有 placed 文件")

    def test_terrain_owner_entries_are_exactly_the_terrain_manifest(self) -> None:
        """本清单标 `owner: terrain` 的 id 集合必须与地形清单的条目**完全一致**。

        两边任何一侧多出一条，都意味着「有人执行但没人负责」或「两边都会放置」。
        """
        listed = {entry if isinstance(entry, str) else entry["id"]
                  for _, _, _, entry in self.entries()}
        owned = {entry["id"] for _, _, _, entry in self.entries()
                 if isinstance(entry, dict) and entry.get("owner")}
        self.assertTrue(owned, "清单里没有任何 owner 条目 —— 地形那 69 处占位被当成缺口了")
        self.assertEqual(sorted(owned), sorted(self.manifest["owners"]),
                         "entries 里的 owner 与清单的 owners 表不一致")

        expected = {ref for ref in self.terrain["supported"]
                    if ref in listed
                    and ref not in set(self.manifest["supported"])
                    and not ref.startswith("minecraft:")}
        self.assertEqual(sorted(expected), sorted(owned),
                         "地形清单与本清单的 owner 归属对不上（多标或漏标）")

    def test_owner_entries_are_executable_in_the_product(self) -> None:
        """`owner` 条目在**产物里**必须是可执行的：没有 `skip`、没有 `native`。

        运行期只有一个装饰计划（`NetherTerrainGenerator` 里单一
        `DecorationScheduler.load(manifest)`，按 step 拆阶段执行），它**不 load
        `terrain.json`**。所以这些槽位若带上 `skip: true`，`WorldgenLoader#explicitSkip`
        会把它们做成纯占位 —— 69 个槽位对应的 27 条地形特征在世界里直接消失。
        `owner` 只标注类别，不是「别人会放」。
        """
        owners = set(self.manifest["owners"])
        self.assertTrue(owners, "清单里没有任何 owner 条目 —— 地形类别的 69 个槽位被当成缺口了")
        seen: set[str] = set()
        for biome, step, index, entry in self.entries():
            if not isinstance(entry, dict) or not entry.get("owner"):
                continue
            where = f"{biome}/step{step}/{index} 的 {entry['id']}"
            self.assertEqual(worldgen.TERRAIN_OWNER, entry["owner"], f"{where} 的 owner 取值非法")
            self.assertNotIn("skip", entry,
                             f"{where} 带了 skip：装载器会把它做成占位，这条特征就消失了")
            self.assertNotIn("native", entry, f"{where} 是地形特征，不该走原版执行路径")
            self.assertTrue(self.placed_file(entry["id"]).is_file(),
                            f"{where} 可执行却没有 placed 文件")
            seen.add(entry["id"])
        self.assertEqual(owners, seen, "owners 表与实际 owner 条目不一致")

    def test_unowned_skips_are_ore_or_stopped_plants(self) -> None:
        """没有 owner 的 `skip` 条目只能是「矿」或「因移除方块停发」两类。

        这条是缺口清单的口径：把 191 处占位都算缺口是错的，但把「没人执行」也算成有人执行
        更糟 —— 它会静默消失。反过来，`skip` 之外不该再出现「有人执行」的模糊地带：
        每条条目要么普通 id、要么 native、要么 owner、要么无语义的 skip。
        """
        terrain_ids = set(self.terrain["supported"])
        # 地形侧的停发记在 terrain.json 的 `excluded` 段（与 supported 互斥），
        # 植被侧记在 manifest 的 `excluded` 段；两者合起来才是「主动停发」全集。
        excluded = set(self.manifest["excluded"]) | set(self.terrain.get("excluded") or {})
        for biome, step, index, entry in self.entries():
            if isinstance(entry, str):
                continue
            ref = entry["id"]
            where = f"{biome}/step{step}/{index} 的 {ref}"
            self.assertLessEqual(set(entry), {"id", "skip", "native", "owner"},
                                 f"{where} 带了清单里没有约定的字段：{sorted(entry)}")
            if entry.get("owner") or not entry.get("skip"):
                continue
            if ref.startswith("minecraft:"):
                continue
            self.assertNotIn(ref, terrain_ids,
                             f"{ref} 是地形类别却没标 owner（{where}）")
            # 停发的三类：矿（由 OrePopulator 走别的路径）、已移除方块导致的停发（`excluded`，
            # 由 `removed_placement_reason` 判出）、以及 `ceiling_debris` 这类专用特征。
            self.assertTrue(ref in excluded or "_ore" in ref or ref.endswith("ceiling_debris"),
                            f"{where} 既没 owner 也不在 excluded：要么补 owner，要么说明谁在放它")

    # -------------------------------------------------------------- 产物闭合

    def test_nested_refs_close_in_the_written_product(self) -> None:
        """地形侧每条链（含 sequence / condition）在**写出来的文件**里都能走到底。"""
        kinds: dict[str, set[str]] = {"placed": set(), "feature": set(), "missing": set()}
        for ident in self.terrain["supported"]:
            self.walk_product(ident, kinds)
        self.assertEqual(set(), kinds["missing"],
                         f"产物里存在悬空引用（{len(kinds['missing'])} 条）")
        for ident in ("betternether:extend_basalt", "betternether:flooded_lava_pit_surface",
                      "betternether:mark"):
            self.assertIn(ident, kinds["placed"],
                          f"{ident} 没在产物里被走到 —— sequence/condition 那段又被漏了")
        # `terracotta_clump` 只被 `patch_blackstone_stalactite_on_ceil` /
        # `patch_blackstone_stalagmite_on_ground` 引用，而这两条特征放置的
        # `blackstone_stalactite` 已按用户要求移除 → 两条特征整条停发，它也就不再进产物。
        # 这不是漏扫，所以断言它**不该**出现（若哪天它重新出现，说明停发逻辑被改坏了）。
        self.assertNotIn("betternether:terracotta_clump", kinds["placed"],
                         "terracotta_clump 所属特征已停发，产物里不该再有它")

    def test_block_tags_cover_every_predicate_tag_in_the_product(self) -> None:
        """产物里出现的每个 `matching_block_tag` 都必须在两份清单的 `blockTags` 里。"""
        known = set(self.manifest["blockTags"]) | set(self.terrain["blockTags"])
        seen: set[str] = set()
        for ident in list(self.manifest["supported"]) + list(self.terrain["supported"]):
            seen |= self.predicate_tags(ident)
        self.assertTrue(seen, "产物里一个 matching_block_tag 都没走到，这条断言什么都没证明")
        self.assertEqual(set(), seen - known, f"这些 tag 没人解析：{sorted(seen - known)}")

    # -------------------------------------------------------------- 步骤下标

    def test_written_manifest_keeps_upstream_step_and_index_order(self) -> None:
        """写出来的 `biomes[biome][step][index]` 必须逐条等于上游 `features[step]`。"""
        for path in sorted((UPSTREAM / worldgen.WORLDGEN / "biome").glob("*.json")):
            biome = path.stem
            with self.subTest(biome=biome):
                raw = json.loads(path.read_text(encoding="utf-8"))
                expected = {str(step): [str(ref) for ref in refs]
                            for step, refs in enumerate(raw.get("features") or [])}
                emitted = self.manifest["biomes"][biome]
                self.assertEqual(sorted(expected), sorted(emitted),
                                 f"{biome} 导出的 step 集合与上游不同")
                for step, refs in expected.items():
                    actual = [entry if isinstance(entry, str) else entry["id"]
                              for entry in emitted[step]]
                    self.assertEqual(refs, actual, f"{biome}/step{step} 的条目或顺序被改动")


if __name__ == "__main__":
    unittest.main()

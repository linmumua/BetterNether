#!/usr/bin/env python3
"""公开结构 id（`mumu:<建筑名>`）的发布契约与门禁行为。

## 为什么要有这组测试

`/locate structure mumu:nether_city` 能不能用，完全取决于产物里那三份文件：

  * `data/betternether/worldgen/structure/<名字>.json`（作者定义，读旧存档的 starts）；
  * `data/mumu/worldgen/structure/<名字>.json`（公开定义，`/locate` 解析的那个 id）；
  * `data/mumu/worldgen/structure_set/<名字>.json`（唯一一份自然放置）。

任何一份缺失或写错命名空间，症状都**不在构建期**：玩家敲命令得到「找不到」，
或者更糟 —— 同一座建筑按两套 salt 各生成一次，密度悄悄翻倍。所以这里正反都钉：

  * 发布侧（`bnlib/public_structures.py`）：定义复制、放置搬迁、不变量；
  * 门禁侧（`tools/verify_structures.py` / `tools/verify_jar.py`）：允许公开 id、
    但旧命名空间的放置与其它位置的 `mumu:` 仍要判失败。

`jungle_temples` 单独钉一遍：它上游就没有自己的放置集，是挂在 `pyramid` 的集合里
（weight 1）。「每个结构一个 structure_set」是错的理解，照那个理解补一套 density
会改掉丛林神庙的稀有度 —— 这正是本组测试要挡住的一类「看起来合理的补法」。

运行：`python -m pytest scripts/tests/test_public_structures.py`
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "tools"))

from bnlib import public_structures  # noqa: E402
import prepare_structure_resources  # noqa: E402
import verify_structures  # noqa: E402

SOURCE_PACK = ROOT / "plugin/src/main/resources/datapacks/betternether-structures"
PUBLIC = public_structures.PUBLIC_STRUCTURE_NAMESPACE
LEGACY = public_structures.LEGACY_STRUCTURE_NAMESPACE
VERIFY = ROOT / "tools/verify_structures.py"

#: 兄弟工程「更好的末地」的结构 id 契约。两边发布到同一个 `mumu:` 命名空间，
#: 同一台服务器上两个数据包会合并注册表，重名的结构会互相覆盖。
SIBLING = ROOT.parent / "更好的末地/scripts/dntlib/structure_ids.py"


# ---------------------------------------------------------------- 工具


def _digest_tree(root: Path) -> dict[str, str]:
    """`相对路径 -> sha256`，用于「重复运行逐字节一致」的判定。"""
    manifest: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file():
            relative = path.relative_to(root).as_posix()
            manifest[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
    return manifest


def _author_names() -> list[str]:
    directory = SOURCE_PACK / "data" / LEGACY / "worldgen" / "structure"
    return sorted(path.stem for path in directory.glob("*.json"))


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _legacy_set_path(name: str) -> Path:
    return SOURCE_PACK / "data" / LEGACY / "worldgen" / "structure_set" / f"{name}.json"


@pytest.fixture(scope="session")
def published(tmp_path_factory) -> Path:
    """把源结构包复制一份并跑完整发布流程（与 processResources 的 doLast 同一条路径）。

    复制而不是就地改：源目录里的 NBT 与 JSON 是本组的输入，任何一步写坏都不该回灌仓库。
    跑 `prepare()` 是必要的 —— 门禁会核对结构调色板里的方块是否已注册，而未处理的
    源 NBT 里还留着不发布的台阶/楼梯。
    """
    root = tmp_path_factory.mktemp("published")
    pack = root / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    import shutil

    shutil.copytree(SOURCE_PACK, pack)
    prepare_structure_resources.prepare(root)
    public_structures.publish(pack)
    return pack


def _public_sets(pack: Path) -> dict[str, object]:
    directory = pack / "data" / PUBLIC / "worldgen" / "structure_set"
    return {path.stem: _read(path) for path in sorted(directory.glob("*.json"))}


def _placed_counts(documents: dict[str, object]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for document in documents.values():
        for entry in document.get("structures", []):
            rid = entry["structure"]
            counts[rid] = counts.get(rid, 0) + 1
    return counts


# ---------------------------------------------------------------- 发布：定义


def test_every_author_definition_gets_a_public_copy(published: Path) -> None:
    names = _author_names()
    assert len(names) == 10, f"源目录里的结构数量变了（{len(names)}），先确认是新增还是误删"

    directory = published / "data" / PUBLIC / "worldgen" / "structure"
    published_names = sorted(path.stem for path in directory.glob("*.json"))
    assert published_names == names


def test_public_copy_is_field_for_field_identical(published: Path) -> None:
    """公开定义只换 id（由路径决定），结构参数一个字段都不许变。

    改 `start_height` / `size` / `terrain_adaptation` 会让 `/locate` 找到的建筑与
    自然生成的那座不是同一座，还会把「只发布 id」悄悄变成「顺手调平衡」。
    """
    for name in _author_names():
        author = _read(published / "data" / LEGACY / "worldgen" / "structure" / f"{name}.json")
        published_doc = _read(published / "data" / PUBLIC / "worldgen" / "structure" / f"{name}.json")
        assert published_doc == author, f"{name}: 公开定义与作者定义不一致"


def test_public_definitions_keep_author_namespace_references(published: Path) -> None:
    """模板池、群系 tag、处理器都没迁移，公开定义必须继续引用 `betternether:`。

    这里同时反向钉一句：公开**定义**里出现任何 `mumu:` 都是错的 —— 那意味着有人把
    `start_pool` / `biomes` 也改了名，而那两份注册表文件并不在 `mumu:` 下。
    """
    for name in _author_names():
        document = _read(published / "data" / PUBLIC / "worldgen" / "structure" / f"{name}.json")
        assert document["start_pool"].startswith(f"{LEGACY}:"), name
        assert document["biomes"].startswith(f"#{LEGACY}:"), name
        text = json.dumps(document, ensure_ascii=False)
        assert f"{PUBLIC}:" not in text, f"{name}: 公开定义里不应出现 {PUBLIC}: 引用"


def test_author_definitions_survive_publication(published: Path) -> None:
    """旧定义必须照旧发布：已存档区块的 `structures.starts` 按作者原 id 记着。"""
    directory = published / "data" / LEGACY / "worldgen" / "structure"
    assert sorted(path.stem for path in directory.glob("*.json")) == _author_names()


# ---------------------------------------------------------------- 发布：放置


def test_author_namespace_keeps_no_placement(published: Path) -> None:
    """一套放置只能存在一份：旧的 `betternether:` structure_set 必须消失。"""
    legacy_sets = published / "data" / LEGACY / "worldgen" / "structure_set"
    remaining = sorted(path.name for path in legacy_sets.glob("*.json")) if legacy_sets.is_dir() else []
    assert remaining == [], f"旧命名空间仍留有放置集：{remaining}"


def test_each_public_id_is_placed_exactly_once(published: Path) -> None:
    counts = _placed_counts(_public_sets(published))
    expected = {f"{PUBLIC}:{name}" for name in _author_names()}
    assert set(counts) == expected, f"放置集的 id 集合与公开定义不一致：{sorted(set(counts) ^ expected)}"
    assert sorted(counts.values()) == [1] * len(expected), f"有结构被放置多次：{counts}"


def test_placement_numbers_are_carried_over_verbatim(published: Path) -> None:
    """spacing / separation / salt / type 逐字段抄旧集合，不重算、不发明。"""
    published_sets = _public_sets(published)
    for name, document in published_sets.items():
        author = _read(_legacy_set_path(name))
        assert document["placement"] == author["placement"], f"{name}: 放置参数被改动了"


def test_jungle_temples_shares_the_pyramid_placement(published: Path) -> None:
    """jungle_temples 上游就没有自己的放置集，挂在 pyramid 的集合里。

    按「每个结构一份 structure_set」的错理解补一套，等于凭空发明间距与盐值 ——
    丛林神庙会变成另一种密度。这里把上游的真实形态钉住：同一份集合、各 weight 1。
    """
    assert "jungle_temples" not in _public_sets(published), "jungle_temples 不该有自己的放置集"

    pyramid = _public_sets(published)["pyramid"]
    entries = {entry["structure"]: entry["weight"] for entry in pyramid["structures"]}
    assert entries == {f"{PUBLIC}:pyramid": 1, f"{PUBLIC}:jungle_temples": 1}

    upstream = _read(ROOT / "upstream/BetterNether/src/main/generated/data/betternether/worldgen/structure_set/pyramid.json")
    assert pyramid["placement"] == upstream["placement"], "pyramid 集合的密度必须与上游一致"
    assert len(pyramid["structures"]) == len(upstream["structures"])


def test_publish_is_idempotent(tmp_path: Path) -> None:
    """重复发布逐字节一致：构建可能在同一份 processResources 输出上再跑一遍。"""
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SOURCE_PACK, pack)
    public_structures.publish(pack)
    first = _digest_tree(pack)
    public_structures.publish(pack)
    assert _digest_tree(pack) == first


# ---------------------------------------------------------------- 发布：不变量（合成包）


def _synthetic_pack(root: Path, names: list[str], sets: dict[str, list[str]]) -> Path:
    """造一个最小结构包：`names` 是作者定义，`sets` 是 `集合名 -> [结构名]`。"""
    pack = root / public_structures.PACK_DIR
    for name in names:
        path = pack / "data" / LEGACY / "worldgen" / "structure" / f"{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"type": "minecraft:jigsaw", "biomes": "#minecraft:is_nether"}), encoding="utf-8")
    for set_name, members in sets.items():
        path = pack / "data" / LEGACY / "worldgen" / "structure_set" / f"{set_name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({
            "placement": {"type": "minecraft:random_spread", "salt": 1, "separation": 4, "spacing": 8},
            "structures": [{"structure": f"{LEGACY}:{member}", "weight": 1} for member in members],
        }), encoding="utf-8")
    return pack


def test_set_referencing_an_unpublished_structure_is_rejected(tmp_path: Path) -> None:
    """放置集引用了没有定义的 id：它就是一座永不生成的结构，必须构建期就炸。"""
    pack = _synthetic_pack(tmp_path, ["a"], {"s": ["a", "ghost"]})
    with pytest.raises(ValueError, match="ghost"):
        public_structures.publish(pack)


def test_unplaced_structure_is_rejected(tmp_path: Path) -> None:
    pack = _synthetic_pack(tmp_path, ["a", "b"], {"s": ["a"]})
    with pytest.raises(ValueError, match="没有被任何 structure_set 放置"):
        public_structures.publish(pack)


def test_double_placement_is_rejected(tmp_path: Path) -> None:
    pack = _synthetic_pack(tmp_path, ["a"], {"s1": ["a"], "s2": ["a"]})
    with pytest.raises(ValueError, match="被放置 2 次"):
        public_structures.publish(pack)


def test_missing_definition_directory_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="找不到结构定义目录"):
        public_structures.publish(tmp_path)


# ---------------------------------------------------------------- 门禁：verify_structures


def _ids(pack: Path) -> tuple[set[str], set[str]]:
    """按门禁同一口径列出两个命名空间的结构 id。"""

    def collect(namespace_name: str) -> set[str]:
        directory = pack / "data" / namespace_name / "worldgen" / "structure"
        if not directory.is_dir():
            return set()
        return {f"{namespace_name}:{path.stem}" for path in directory.glob("*.json")}

    return collect(LEGACY), collect(PUBLIC)


def _run_gates(pack: Path) -> list[str]:
    """跑门禁里的「结构/放置」部分，返回失败信息（不清空别人的结果就先清一遍）。"""
    verify_structures.FAILURES.clear()
    verify_structures.NOTES.clear()
    directory_pack, _ = verify_structures.open_directory(pack)
    legacy_ids, public_ids = _ids(pack)
    verify_structures.check_structure_sets(directory_pack, legacy_ids | public_ids, public_ids)
    verify_structures.check_public_layout(directory_pack, legacy_ids, public_ids)
    return list(verify_structures.FAILURES)


def test_gates_accept_the_published_layout(published: Path) -> None:
    assert _run_gates(published) == []


def test_gates_reject_a_surviving_legacy_placement(published: Path, tmp_path: Path) -> None:
    """旧放置集留一份 = 同一座建筑按两套 salt 各生成一次。"""
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    stale = pack / "data" / LEGACY / "worldgen" / "structure_set" / "altars.json"
    stale.parent.mkdir(parents=True, exist_ok=True)
    stale.write_text(_legacy_set_path("altars").read_text(encoding="utf-8"), encoding="utf-8")

    failures = _run_gates(pack)
    assert any("旧命名空间仍保留放置集" in message for message in failures), failures


def test_gates_reject_a_duplicated_placement(published: Path, tmp_path: Path) -> None:
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    source = pack / "data" / PUBLIC / "worldgen" / "structure_set" / "altars.json"
    (source.parent / "altars_second.json").write_text(source.read_text(encoding="utf-8"), encoding="utf-8")

    failures = _run_gates(pack)
    assert any("被放置 2 次" in message for message in failures), failures


def test_gates_reject_a_public_id_without_author_definition(published: Path, tmp_path: Path) -> None:
    """少了旧定义，旧世界继续建造时会丢掉进度。"""
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    (pack / "data" / LEGACY / "worldgen" / "structure" / "altars.json").unlink()

    failures = _run_gates(pack)
    assert any("旧世界的区块数据" in message for message in failures), failures


def test_gates_reject_an_author_definition_without_public_twin(published: Path, tmp_path: Path) -> None:
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    (pack / "data" / PUBLIC / "worldgen" / "structure" / "altars.json").unlink()

    failures = _run_gates(pack)
    assert any("没有对应的公开定义" in message for message in failures), failures


def test_gates_reject_a_public_set_referencing_the_legacy_id(published: Path, tmp_path: Path) -> None:
    """放置集里写 `betternether:` 的 id：那座结构没有放置，`/locate mumu:x` 永远找不到。"""
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    path = pack / "data" / PUBLIC / "worldgen" / "structure_set" / "altars.json"
    document = _read(path)
    document["structures"][0]["structure"] = f"{LEGACY}:altars"
    path.write_text(json.dumps(document), encoding="utf-8")

    failures = _run_gates(pack)
    assert any("放置集应引用" in message for message in failures), failures


def test_gates_reject_an_unplaced_public_id(published: Path, tmp_path: Path) -> None:
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    (pack / "data" / PUBLIC / "worldgen" / "structure_set" / "altars.json").unlink()

    failures = _run_gates(pack)
    assert any("没有被任何 structure_set 放置" in message for message in failures), failures


def test_public_definitions_are_validated_like_author_ones(published: Path, tmp_path: Path) -> None:
    """公开定义也要过 start_pool / 群系 tag 检查 —— 只扫旧命名空间等于给它开后门。"""
    import shutil

    pack = tmp_path / public_structures.PACK_DIR
    pack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(published, pack)
    path = pack / "data" / PUBLIC / "worldgen" / "structure" / "jungle_temples.json"
    document = _read(path)
    document["start_pool"] = f"{LEGACY}:does_not_exist/start"
    path.write_text(json.dumps(document), encoding="utf-8")

    pool_root = pack / "data" / LEGACY / "worldgen" / "template_pool"
    pool_ids = {f"{LEGACY}:{item.relative_to(pool_root).with_suffix('').as_posix()}"
                for item in pool_root.rglob("*.json")}

    verify_structures.FAILURES.clear()
    verify_structures.NOTES.clear()
    directory_pack, _ = verify_structures.open_directory(pack)
    verify_structures.check_structures(directory_pack, set(), pool_ids)
    failures = list(verify_structures.FAILURES)
    assert any(f"structure/{PUBLIC}/jungle_temples.json" in message and "does_not_exist" in message
               for message in failures), failures


def test_gates_note_the_pre_publication_source_tree() -> None:
    """源目录（发布前）没有公开定义：只提示，不假装发布布局已验证。"""
    verify_structures.FAILURES.clear()
    verify_structures.NOTES.clear()
    directory_pack, _ = verify_structures.open_directory(SOURCE_PACK)
    legacy_ids, public_ids = _ids(SOURCE_PACK)
    assert public_ids == set()
    verify_structures.check_public_layout(directory_pack, legacy_ids, public_ids)
    assert verify_structures.FAILURES == []
    assert any("发布前的源布局" in message for message in verify_structures.NOTES)


def test_full_gate_script_passes_on_the_published_pack(published: Path) -> None:
    """整份门禁脚本（含模板闭合、群系 tag、调色板注册）在发布产物上必须真跑通。"""
    completed = subprocess.run(
        [sys.executable, str(VERIFY), str(published)],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    assert f"{PUBLIC}" in completed.stdout


# ---------------------------------------------------------------- 门禁：verify_jar


def _jar_namespace_failures(tmp_path: Path, entries: dict[str, str]) -> list[str]:
    """把给定文件塞进一个 zip，跑「过期命名空间」那条门禁。"""
    import zipfile

    import verify_jar

    archive_path = tmp_path / "pack.jar"
    with zipfile.ZipFile(archive_path, "w") as archive:
        for name, text in entries.items():
            archive.writestr(name, text)
    verify_jar.FAILURES.clear()
    with zipfile.ZipFile(archive_path) as archive:
        verify_jar.check_no_upstream_namespace(archive, set(archive.namelist()))
    return list(verify_jar.FAILURES)


def test_verify_jar_allows_public_structure_set_ids(tmp_path: Path) -> None:
    """`structure_set.structures[].structure` 用 `mumu:` 是**正确**的，不能判成残留。"""
    document = json.dumps({
        "placement": {"type": "minecraft:random_spread", "salt": 1, "separation": 4, "spacing": 8},
        "structures": [{"structure": "mumu:nether_city", "weight": 1}],
    })
    entries = {
        public_structures.PACK_DIR + "/" + public_structures.public_structure_set_path("nether_city"): document,
    }
    assert _jar_namespace_failures(tmp_path, entries) == []


def test_verify_jar_rejects_public_id_outside_the_structure_field(tmp_path: Path) -> None:
    """同一个文件里把 `mumu:` 用在别处（模板池/方块）仍是残留，照样判失败。"""
    document = json.dumps({
        "placement": {"type": "minecraft:random_spread", "salt": 1, "separation": 4, "spacing": 8},
        "structures": [{"structure": "mumu:nether_city", "weight": 1, "start_pool": "mumu:nether_city/start"}],
    })
    entries = {
        public_structures.PACK_DIR + "/" + public_structures.public_structure_set_path("nether_city"): document,
    }
    failures = _jar_namespace_failures(tmp_path, entries)
    assert any("过期前缀" in message for message in failures), failures


def test_verify_jar_rejects_public_reference_inside_a_definition(tmp_path: Path) -> None:
    """公开**定义**里出现 `mumu:` 是错的：模板池与群系 tag 都没迁移。"""
    document = json.dumps({
        "type": "minecraft:jigsaw",
        "biomes": "#betternether:has_structure/nether_city",
        "start_pool": "mumu:nether_city/start",
    })
    entries = {
        public_structures.PACK_DIR + "/" + public_structures.public_structure_path("nether_city"): document,
    }
    failures = _jar_namespace_failures(tmp_path, entries)
    assert any("过期前缀" in message for message in failures), failures


# ---------------------------------------------------------------- 与兄弟工程冲突


def _sibling_public_names() -> set[str]:
    """读「更好的末地」的公开结构名单；读不到就跳过（跨工程路径不保证存在）。"""
    if not SIBLING.is_file():
        pytest.skip(f"找不到兄弟工程的结构 id 契约：{SIBLING}")
    spec = importlib.util.spec_from_file_location("sibling_structure_ids", SIBLING)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return set(module.ALL_PUBLIC_STRUCTURES)


def test_public_names_do_not_collide_with_the_other_pack() -> None:
    """两个插件的结构包都发布到 `mumu:`：同名的结构会在同一台服务器上互相覆盖。

    地狱这边是 `nether_city` 一类的地狱建筑，末地那边是 `end_*`；一旦哪天有人把
    名字改重，两边都还能各自构建通过，只有同服启动时注册表才打架。这里把「互不相交」
    钉成显式断言。
    """
    sibling = _sibling_public_names()
    mine = {f"{PUBLIC}:{name}" for name in _author_names()}
    overlap = mine & {f"{PUBLIC}:{name}" for name in sibling}
    assert overlap == set(), f"与另一份数据包重名：{sorted(overlap)}"

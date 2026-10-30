"""可爬藤蔓的**真正契约**：载体本身必须是 `#minecraft:climbable` 里的原版方块。

## 机制（对着实际 jar 与 tag 文件核过，不是推断）

判定点只有一处：`LivingEntity#onClimbable()` → `getInBlockState().is(BlockTags.CLIMBABLE)`
（`gradle.properties` 里 `serverJar` 指向的 `mucore-26.3` 服务端字节码）。
这段逻辑**客户端也跑一遍**：玩家能不能往上走，先由客户端按它自己世界里的方块状态算出来。
而 CE 给原版客户端下发的正是**视觉载体状态**（`cache/visual_block_states.json` 里全是
`note_block`/`sugar_cane`/…），所以载体就是客户端眼里的方块。

由此两条硬结论：

  * 借 `sugar_cane` 当载体 = 客户端认为自己贴着一根甘蔗 = **爬不上去**；
  * `settings.tags: [minecraft:climbable]` 挂在**自定义方块自己的** holder 上
    （服务端逻辑、以及装了 CE 客户端 mod 的玩家读得到），**替不了**载体。

运行期 tag 实测（serverJar 的 `data/minecraft/tags/block/climbable.json`）：
ladder / vine / scaffolding / weeping_vines(+`_plant`) / twisting_vines(+`_plant`) /
cave_vines(+`_plant`) —— **没有** sugar_cane。

## 改造后（真实状态 + 模型）的对应关系

植物不再共用「透明宿主 + item_display」：每个外观各占一个真实状态。可爬方块因此
**必须从可爬池（{@link blocks.CLIMBABLE_POOLS}）里取槽位** —— 那三个藤蔓池同时也
是无碰撞池，所以「能爬」与「能穿过去」可以同时成立。

## 本文件锁住什么

  * 三个藤蔓池都对应真正的可爬原版方块（不是碰运气）；
  * `plant_slot_plan` 对可爬方块给出的池族全部落在可爬池里；
  * 产物里可爬方块的 `auto_state` 只出现在可爬池，且不可爬的植物不会被顺手变成可爬；
  * `settings.tags` 里的 climbable 与挖掘 tag 并存。

真正的玩家移动仍未离线实测（没有启动测试端）：本文件证明的是配置与运行期 tag 契约。
"""

from __future__ import annotations

import json
import os
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks  # noqa: E402

UPSTREAM = ROOT / "upstream/BetterNether"
# 同 test_state_budget：默认读构建目录，可用 `BN_GENERATED_ROOT` 指向临时产物。
_ARTIFACTS = Path(os.environ.get("BN_GENERATED_ROOT", str(ROOT / "plugin/build/generated/bn")))
GENERATED = _ARTIFACTS / "craftengine/betternether/configuration"

CLIMBABLE = "minecraft:climbable"
# 组名 -> 分到这个组的原版方块（`docs/blocks.md`「可用的 auto_state 组」）。
POOL_BLOCK = {
    "cave_vine": "minecraft:cave_vines",
    "weeping_vine": "minecraft:weeping_vines",
    "twisting_vine": "minecraft:twisting_vines",
    # 不可爬的对照：甘蔗不在 climbable 里，所以借它的植物默认爬不上去。
    "sugar_cane": "minecraft:sugar_cane",
}


def _server_jar() -> Path | None:
    props = ROOT / "gradle.properties"
    if not props.is_file():
        return None
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("serverJar="):
            raw = line.split("=", 1)[1].strip()
            try:
                return Path(raw.encode().decode("unicode_escape"))
            except UnicodeDecodeError:
                return Path(raw)
    return None


def _climbable_tag() -> frozenset[str]:
    """运行期 `minecraft:climbable` 的方块集合（直接读服务端 jar 的 tag json）。"""
    jar = _server_jar()
    if jar is None or not jar.is_file():
        pytest.skip(f"找不到 serverJar（{jar}），无法核对运行期 {CLIMBABLE}")
    with zipfile.ZipFile(jar) as archive:
        try:
            payload = archive.read("data/minecraft/tags/block/climbable.json")
        except KeyError:
            pytest.skip("serverJar 里没有 data/minecraft/tags/block/climbable.json")
    return frozenset(entry for entry in json.loads(payload)["values"]
                     if not entry.startswith("#"))


# ------------------------------------------------------------------ 池的真值


def test_every_climbable_pool_is_genuinely_climbable():
    """可爬池都对应真正的可爬原版方块 —— 选它们当载体才不是碰运气。"""
    tag = _climbable_tag()
    # `vine` 不是 CE `AutoStateGroup` 的合法组名（枚举里没有它），所以可爬只能借三个藤蔓组。
    assert blocks.CLIMBABLE_POOLS == blocks.VINE_STATE_POOLS
    assert "vine" in blocks.AUTO_STATE_GROUPS or "vine" not in blocks.CLIMBABLE_POOLS
    for pool in blocks.CLIMBABLE_POOLS:
        assert pool in POOL_BLOCK, f"{pool} 没有对应的原版方块对照"
        assert POOL_BLOCK[pool] in tag, f"{pool} 组不是可爬方块"


def test_non_climbable_reference_pool_is_not_climbable():
    """对照：`sugar_cane` 不在 climbable 里，所以它不能当可爬方块的载体。"""
    tag = _climbable_tag()
    assert POOL_BLOCK["sugar_cane"] not in tag
    assert "sugar_cane" not in blocks.CLIMBABLE_POOLS


def test_upstream_climbable_truth_is_the_upstream_tag_not_the_class_name():
    """可爬的唯一真值来自上游 climbable tag，与「藤蔓视觉类」是两回事。"""
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    from bnlib import props  # noqa: PLC0415

    tag = frozenset(props.load_tag(UPSTREAM, "climbable"))
    for name in ("betternether:soul_vein",
                 "betternether:stalagnate_trunk",
                 "betternether:neon_equisetum"):
        assert name in tag, f"{name} 应在上游 climbable 里（按类名推会漏配）"
    assert "betternether:gloomwisp_vine" not in tag, "按类名推会误加 gloomwisp_vine"
    # 类名推断整体下线：`BlockGloomwispVine extends Block`（上游源码），没有 climbable 语义，
    # 所以它既不进可爬池、也不再按「藤蔓视觉类」单独占藤蔓池。
    assert not hasattr(blocks, "VINE_CLASSES")


# ------------------------------------------------------------------ 池族选择


def test_climbable_blocks_only_get_climbable_pools():
    """可爬方块的池族必须全部落在可爬池里，任何碰撞档位都一样。"""
    for tier in ("none", "partial"):
        family, _collision, why = blocks.plant_slot_plan(
            "betternether:demo_vine", tier, True)
        assert set(family) == set(blocks.CLIMBABLE_POOLS), (tier, family)
        assert "可爬" in why


def test_non_climbable_plant_never_gets_a_climbable_only_plan():
    """不可爬的植物不能被顺手变成可爬：它的池族必须以无碰撞池开头，且不是「只有藤蔓」。"""
    family, collision, _why = blocks.plant_slot_plan(
        "betternether:demo_plant", "none", False)
    assert collision == "empty"
    assert family[0] in blocks.NO_COLLISION_POOLS
    assert set(family) != set(blocks.CLIMBABLE_POOLS)


# ------------------------------------------------------------------ 产物


def _load_defs() -> dict:
    if not (GENERATED / "blocks").is_dir():
        pytest.skip("生成目录不存在，先跑一次生成器")
    defs = {}
    for path in sorted((GENERATED / "blocks").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        defs.update(data.get("blocks") or {})
    return defs


def _upstream_climbable_blocks() -> set[str]:
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    from bnlib import props  # noqa: PLC0415

    return set(props.load_tag(UPSTREAM, "climbable"))


def _pools(body: dict) -> list[str]:
    found = []
    state = body.get("state") or {}
    if isinstance(state.get("auto_state"), str):
        found.append(state["auto_state"])
    for appearance in ((body.get("states") or {}).get("appearances") or {}).values():
        if isinstance(appearance.get("auto_state"), str):
            found.append(appearance["auto_state"])
    return found


def test_product_climbable_blocks_use_only_climbable_pools():
    """产物侧的双向闭合：可爬方块 ↔ 只借可爬池。

    只写 tags 不换载体的方块会让玩家「看起来可爬、实际贴不住」，这条断言专门挡它。
    """
    defs = _load_defs()
    tag = _upstream_climbable_blocks()
    climbable_ids = {bid for bid in defs if bid in tag}
    assert climbable_ids, "已发布集合里没有任何上游 climbable 成员，真值异常"

    offenders: list[str] = []
    for block_id in sorted(climbable_ids):
        for pool in _pools(defs[block_id]):
            if pool not in blocks.CLIMBABLE_POOLS:
                offenders.append(f"{block_id} → {pool}")
    assert offenders == [], (
        "这些可爬方块借了不可爬的载体（客户端不会产生攀爬）：\n  " + "\n  ".join(offenders))


def _upstream_vine_classes() -> dict[str, str]:
    """已发布方块的 上游 id → 类名（用于认「视觉上是悬挂植物」）。"""
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    from bnlib import props  # noqa: PLC0415

    return {block_id: entry.cls for block_id, entry in props.load_props(UPSTREAM).items()}


def test_product_only_vines_use_the_vine_pools():
    """反向：借藤蔓池的方块必须真的是藤蔓（可爬，或上游就是悬挂植物）。"""
    defs = _load_defs()
    tag = _upstream_climbable_blocks()
    classes = _upstream_vine_classes()
    offenders: list[str] = []
    for block_id, body in defs.items():
        vine_pools = [pool for pool in _pools(body) if pool in blocks.VINE_STATE_POOLS]
        if not vine_pools:
            continue
        if block_id in tag:
            continue
        if classes.get(block_id) in blocks.VINE_CLASSES:
            continue
        offenders.append(f"{block_id} → {sorted(set(vine_pools))}")
    assert offenders == [], (
        "这些非藤蔓方块借了悬挂池：\n  " + "\n  ".join(offenders))


# ------------------------------------------------------------------ tag 仍然要写


def test_settings_yaml_keeps_climbable_alongside_mineable_tags():
    """`settings.tags` 里 climbable 与挖掘 tag 并存；它是补充，不能覆盖挖掘标签。"""
    text = blocks.settings_yaml(
        f"{blocks.NAMESPACE}:demo_vine", None, frozenset({"hoe"}), 0, None,
        [CLIMBABLE], blocks.KIND_PLANT)
    body = yaml.safe_load("blocks:\n  demo:test:\n" + text)["blocks"]["demo:test"]
    tags = ((body.get("settings") or {}).get("merges") or {}).get("tags") or []
    assert CLIMBABLE in tags
    assert "minecraft:mineable/hoe" in tags


def test_single_state_plant_takes_a_real_state_from_its_family():
    """单状态（无 `states:`）的植物同样借真实状态，不再写 entity_renderer。"""
    body = yaml.safe_load(
        "blocks:\n  demo:test:\n    state:\n"
        + blocks.state_body("betternether:demo_vine", None, True, blocks.KIND_PLANT,
                            "cave_vine"))["blocks"]["demo:test"]["state"]
    assert body["auto_state"] == "cave_vine"
    assert body["model"]["path"] == "betternether:block/demo_vine"
    assert "entity_renderer" not in body
    assert "transparent" not in body

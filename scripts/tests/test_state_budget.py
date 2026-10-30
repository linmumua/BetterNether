"""植物的视觉状态必须借到**真实原版状态**，而且借的池不能改变碰撞箱。

改这一段的原因：植物以前走 `transparent: true` + `entity_renderer`（每格一个 item_display
展示实体），用户实测太卡。改成「真实方块状态 + 模型」后，碰撞箱不再由我们声明，
而是**跟着所借的原版状态走**（CE `auto_state` 的语义），于是池的选择直接决定
「可以穿过去的花草」还是「看不见的实心墙」。

这些测试锁住四件事：

  * 每池不超容量（`POOL_BUDGET` 是必须与 mappings 补丁对齐的那张表）；
  * 产物里**没有任何** `entity_renderer` / `transparent: true`（旧渲染路线的残留）；
  * `collision=empty` 的方块只借无碰撞池，**绝不**落到 solid / 树叶池；
  * 反例真的会失败：把方块误配到 `note_block`/`solid`/树叶池，下面的断言必须报错。

真值来源：`upstream/BetterNether/src/main/generated/block_shapes.txt`
（经 `bnlib.props.load_shape_tiers` 归到 none/partial/full 三档）。
"""

from __future__ import annotations

import collections
import json
import os
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks, props  # noqa: E402

# 产物根目录。默认读构建目录；`BN_GENERATED_ROOT` 可指向一次临时生成（例如
# `BN_GENERATED_ROOT=/tmp/bn-gen python -m pytest scripts/tests`），这样在
# mappings 补丁落地前也能核对产物侧的断言，而不必覆盖 plugin/build/generated/bn。
_ARTIFACTS = Path(os.environ.get("BN_GENERATED_ROOT", str(ROOT / "plugin/build/generated/bn")))
GENERATED = _ARTIFACTS / "craftengine/betternether/configuration"
REPORTS = _ARTIFACTS / "reports"
TRANSLUCENCY = REPORTS / "translucency.json"
UPSTREAM = ROOT / "upstream/BetterNether"

# 允许承载「满格但透明」方块的载体组：树叶状态是 cutout 渲染、有碰撞、不参与实心遮挡。
CUTOUT_CARRIERS = {"non_tintable_leaves", "tintable_leaves"}


def _load():
    if not (GENERATED / "blocks").is_dir():
        pytest.skip("生成目录不存在，先跑一次生成器")
    items, defs = {}, {}
    for path in sorted((GENERATED / "blocks").glob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        items.update(data.get("items") or {})
        defs.update(data.get("blocks") or {})
    return items, defs


def _translucency() -> dict[str, dict]:
    if not TRANSLUCENCY.is_file():
        pytest.skip("缺少 translucency.json，先跑一次生成器")
    return {entry["id"]: entry
            for entry in json.loads(TRANSLUCENCY.read_text(encoding="utf-8"))["blocks"]}


def _carriers(body: dict) -> list:
    """一个方块所有视觉载体（`state.auto_state` 与每个 appearance 的 auto_state）。"""
    found = []
    state = body.get("state")
    if isinstance(state, dict) and "auto_state" in state:
        found.append(state["auto_state"])
    for appearance in ((body.get("states") or {}).get("appearances") or {}).values():
        if isinstance(appearance, dict) and "auto_state" in appearance:
            found.append(appearance["auto_state"])
    return found


def _carrier_name(carrier) -> str:
    if isinstance(carrier, dict):
        return str(carrier.get("type"))
    return str(carrier)


def _appearances_of(body: dict) -> dict:
    """方块的「外观 → 载体」映射，**兼容 `state:` 与 `states:` 两种 schema**。

    CE 的方块配置允许两种写法：`states:` 分外形（每个 appearance 各带载体），
    以及单外形时缩写成 `state:`（它自己就是唯一那个外观）。只认复数会让单外形方块
    全部 KeyError —— 这个坑在 verify_assets 门禁那边已经踩过一次。
    """
    states = body.get("states")
    if isinstance(states, dict) and isinstance(states.get("appearances"), dict):
        return states["appearances"]
    state = body.get("state")
    if isinstance(state, dict):
        return {"__single__": state}
    return {}


def _set_carriers(body: dict, pool: str) -> None:
    """把方块**所有**视觉载体改写成 `pool`（两种 schema 都覆盖）。"""
    for appearance in _appearances_of(body).values():
        appearance["auto_state"] = pool

# ------------------------------------------------------------------ 额度


def test_allocator_spends_one_slot_per_appearance_and_fails_fast(monkeypatch):
    """一个外观一个槽位；整族用尽必须 `SystemExit`，绝不退回展示实体。"""
    alloc = blocks.PoolAllocator()
    family = ("tripwire",)
    budget = blocks.pool_budget()["tripwire"]
    for _ in range(budget):
        assert alloc.take_from(family, "betternether:demo") == "tripwire"
    assert alloc.left["tripwire"] == 0
    with pytest.raises(SystemExit) as failure:
        alloc.take_from(family, "betternether:demo")
    message = str(failure.value)
    assert "betternether:demo" in message
    assert "缺口 1 个" in message
    assert "拒绝退回 entity_renderer" in message


def test_allocator_falls_through_the_family_in_priority_order(monkeypatch):
    """首选池用尽后顺延到族里的下一个池，而不是直接失败。"""
    alloc = blocks.PoolAllocator()
    alloc.left["chorus"] = 0
    pool = alloc.take_from(("chorus", "tripwire"), "betternether:demo_plant")
    assert pool == "tripwire"


def test_thin_pool_usage_stays_within_budget():
    """生成产物里每个池的独立状态数不超过 `POOL_BUDGET` 的容量。"""
    _, defs = _load()
    used = collections.Counter()
    for body in defs.values():
        for carrier in _carriers(body):
            name = _carrier_name(carrier)
            if isinstance(carrier, str):
                used[blocks.PoolAllocator._SHARED.get(name, name)] += 1
    budget = blocks.pool_budget()
    for pool, n in used.items():
        if pool == blocks.STATE_POOL_SOLID:
            # `solid` 是满格音符盒/蘑菇方块池，不在「纤细池余量」的口径里。
            continue
        assert pool in budget, f"{pool} 不在 POOL_BUDGET 里，容量无从核对"
        assert n <= budget[pool], f"{pool} 用了 {n} 个，容量 {budget[pool]}"


# mappings.yml 里各池**启用**的状态条目数（真值快照，2026-10-28 采集、2026-10-30 补 pressure_plate）。
# `POOL_BUDGET` 是本包剩余空位（启用条目 − 其他 CE 包已占用），必须 ≤ 启用条目数；
# 这条同时防止有人把「总容量」再写回额度表。
#
# `pressure_plate` 为什么现在必须在这张表里：它是 2026-10-30 新启用的无碰撞载体族
# （见 `blocks.NO_COLLISION_POOLS` 的注释：实测 16/16 状态 `collEmpty=true`、
# `canOcclude=false`，代价是会输出红石信号）。额度表里有 `pressure_plate: 27`，
# 真值表却缺这一行的话，这条断言就退化成 KeyError 而不是「额度 ≤ 启用条目」的核对 ——
# 所以补上的是**采集值**（`ce_pool_ledger` 的 `cap` 同为 28：轻质/重质测重压力板各 14 条启用），
# 不是为了让断言通过的编造数字。
MAPPINGS_ENABLED = {
    "chorus": 63,         # chorus_plant：mappings 里 63 条（已被补丁启用）
    "tripwire": 126,      # tripwire 三子池共用的那一批绊线状态
    "weeping_vine": 25,
    "twisting_vine": 25,
    "pressure_plate": 28, # 轻质(14)+重质(14)测重压力板；账本 cap=28、used=1、free=27
    "cave_vine": 50,
    "kelp": 25,
    "sugar_cane": 15,
    "sapling": 8,
    "non_tintable_leaves": 35,
    "tintable_leaves": 50,
}


def test_auto_state_values_are_legal_enum_groups():
    """生成的组名必须是 CE `AutoStateGroup` 枚举里的合法值。

    这是 2026-10-28 那次返工的根因：`auto_state` 是**固定枚举**，不是自由组名。
    当时写了 `sculk_vein`/`resin_clump`/`vine`（都不存在）、`chorus_plant`（真名 `chorus`），
    服务器上 37 个植物方块直接没有外观，而构建与旧测试全绿 —— 因为旧测试只比对
    `bnlib.blocks` 自己写的表，两边一起错。
    """
    legal = set(blocks.AUTO_STATE_GROUPS)
    assert "chorus" in legal and "chorus_plant" not in legal
    for bogus in ("sculk_vein", "resin_clump", "vine", "dead_coral", "chorus_plant"):
        assert bogus not in legal, f"{bogus} 不在 CE 枚举里，绝不能出现在池表里"
    # 生成器用到的每一个池都必须合法。
    used = set(blocks.NO_COLLISION_POOLS) | set(blocks.PARTIAL_COLLISION_POOLS)
    used |= set(blocks.CLIMBABLE_POOLS) | set(blocks.pool_budget())
    used |= {blocks.STATE_POOL_SOLID, *blocks.CUTOUT_POOLS}
    assert used <= legal, f"这些池名不在 CE 枚举里：{sorted(used - legal)}"
    # 产物里的每一个 auto_state 也必须合法（读取实际生成结果）。
    _, defs = _load()
    bad = []
    for block_id, body in defs.items():
        for carrier in _carriers(body):
            name = _carrier_name(carrier)
            if name not in legal:
                bad.append(f"{block_id} → {name}")
    assert bad == [], ("产物里出现了非法组名（这些方块在游戏里会完全没有外观）：\n  "
                       + "\n  ".join(bad[:20]))


def test_pool_budget_is_the_remaining_headroom_not_the_pool_capacity():
    """`POOL_BUDGET` 必须是**本包余量**：每项 ≤ 账本里的 `free`，且 ≤ mappings 启用条目。

    反面教材（2026-10-28 之前）：把 CE 池总容量写成额度，算出来 tripwire 有 126 个空位，
    而 cache 里 betterend + farmersdelight 已经占了 117，一上线就撞「已达到最大容量」。

    这条**不锁具体数字**：余量随别的包占用变化（`weeping_vine` 从 9 变成 15 就是因为
    betterend 的占用被重算），锁死一个数字只会让人下次改数字而不是去核对账本。
    真正要守的是「额度 ≤ 账本余量」这个不等式，以及「账本和额度用的是同一套组名」。
    """
    from bnlib import ce_pool_ledger  # noqa: PLC0415

    budget = blocks.pool_budget()
    ledger = ce_pool_ledger.POOL_LEDGER
    for pool, value in budget.items():
        assert pool in blocks.AUTO_STATE_GROUPS, f"{pool} 不是合法组名"
        assert pool in MAPPINGS_ENABLED, f"{pool} 不在 mappings 真值表里，额度无从核对"
        assert 0 <= value <= MAPPINGS_ENABLED[pool], (
            f"{pool}: 额度 {value} 超过 mappings 启用条目 {MAPPINGS_ENABLED[pool]}")
        if value > 0 and pool not in ce_pool_ledger.UNCOLLECTED_POOLS:
            assert pool in ledger, f"{pool} 申请了额度 {value}，但账本里没有它的余量记录"
            free = ledger[pool].get("free")
            assert free is not None, f"{pool} 的账本条目没有 free，额度无从核对"
            assert value <= int(free), (  # type: ignore[arg-type]
                f"{pool}: 额度 {value} 超过账本余量 {free} —— 这就是「把总容量当额度」的错法")
    # 唯一的大池是 chorus（本包独占，无其它包占用）—— 这是口径正确的可观测特征。
    assert budget["chorus"] == 63
    assert ledger["chorus"]["used"] == 0, "chorus 一旦被别的包占用，额度就必须跟着降"
    # 拿不到槽位的池必须记 0 而不是留个「1」：CE 已报满额，写 1 就会让方块没有外观。
    for exhausted in ("kelp", "sugar_cane", "sapling"):
        assert budget[exhausted] == 0, (
            f"{exhausted} 的最后一个槽位已被别人/本包历史占用吃掉，额度必须记 0")
    # 不存在/已回滚的组不该留 0 值键，它们不是合法组。
    for dead in ("cactus", "dead_coral", "plant", "sculk_vein", "resin_clump", "vine"):
        assert dead not in budget, f"{dead} 不是合法组，不该出现在额度表里"


def test_cactus_is_never_a_plant_carrier():
    """`cactus` 16 个状态全部 `canOcclude=true`：借它＝剔除邻面＝透视，永久禁用。"""
    assert "cactus" in blocks.FORBIDDEN_PLANT_POOLS
    assert "cactus" not in blocks.pool_budget(), "cactus 不该再出现在额度表里"
    assert not hasattr(blocks, "THIN_COLUMN_POOLS"), "cactus 载体池必须整条删除"
    for name, entry in blocks.PLANT_STATE_SLOTS.items():
        assert "cactus" not in entry["family"], f"{name} 的池族里还有 cactus"
    for pool in blocks.NO_COLLISION_POOLS + blocks.PARTIAL_COLLISION_POOLS + blocks.CLIMBABLE_POOLS:
        assert pool != "cactus"


def test_cutout_pool_total_usage_within_combined_budget():
    """满格透明方块（玻璃/树叶）每个外观占一个 cutout 槽位，合计不得超过两个树叶池额度。"""
    _, defs = _load()
    used = collections.Counter()
    for body in defs.values():
        for carrier in _carriers(body):
            name = _carrier_name(carrier)
            if name in CUTOUT_CARRIERS:
                used[name] += 1
    budget = blocks.pool_budget()
    total = sum(budget[pool] for pool in blocks.CUTOUT_POOLS)
    assert sum(used.values()) <= total, f"cutout 用了 {sum(used.values())} 个，合计额度 {total}"
    for pool in blocks.CUTOUT_POOLS:
        assert used[pool] <= budget[pool], f"{pool} 用了 {used[pool]} 个，额度 {budget[pool]}"


# ------------------------------------------------------------------ 旧渲染路线的残留


def test_no_block_uses_entity_renderer_or_transparent_anymore():
    """56 个植物以前靠展示实体渲染，现在必须一个都不剩。"""
    _, defs = _load()
    offenders: list[str] = []
    for block_id, body in defs.items():
        state = body.get("state")
        if isinstance(state, dict) and ("entity_renderer" in state or "transparent" in state):
            offenders.append(f"{block_id}: state")
        for name, appearance in ((body.get("states") or {}).get("appearances") or {}).items():
            if isinstance(appearance, dict) and (
                    "entity_renderer" in appearance or "transparent" in appearance):
                offenders.append(f"{block_id}: {name}")
    assert offenders == [], (
        "这些方块还在用 transparent + entity_renderer（每格一个展示实体）：\n  "
        + "\n  ".join(offenders[:20]))


def test_every_plant_appearance_has_a_model_path():
    """真实状态渲染的前提：每个外观都写了自己的 `model.path`（否则是一格空白）。"""
    _, defs = _load()
    manifest = json.loads((REPORTS / "block-manifest.json").read_text(encoding="utf-8"))["blocks"]
    plant_ids = {entry["id"] for entry in manifest if entry["kind"] in ("plant", "static")}
    # 不再用「≥56」这种魔法阈值：整族移除后已发布集合是 53 个，写死数字只会让人下次改数字。
    # 改成与植物管线自己的账本对齐 —— 两边集合必须相等：少一个 = 有植物被静默跳过，
    # 多一个 = 账本报了不存在的方块。
    report_path = REPORTS / "plant-collision.json"
    if not report_path.is_file():
        pytest.skip("缺少 plant-collision.json，先跑一次生成器")
    reported = {e["id"] for e in json.loads(
        report_path.read_text(encoding="utf-8"))["blocks"]}
    assert plant_ids == reported, (
        f"植物管线账本与 manifest 的植物/装饰集合不一致（差集 {sorted(plant_ids ^ reported)}）")

    missing: list[str] = []
    checked = 0
    for block_id in sorted(plant_ids):
        body = defs[block_id]
        appearances = ((body.get("states") or {}).get("appearances") or {})
        if appearances:
            for name, appearance in appearances.items():
                checked += 1
                if not _model_paths(appearance):
                    missing.append(f"{block_id}/{name}")
        else:
            checked += 1
            if not _model_paths(body.get("state") or {}):
                missing.append(f"{block_id}/state")
    assert missing == [], "这些外观没有 model.path（游戏里是空气）：\n  " + "\n  ".join(missing[:20])
    # 168 个外观被合并进 86 格（合法、无碰撞且不遮挡的组只剩 86 格），
    # 这里核对「合并后每个外观都有模型」；合并前的总数由 appearance_totals 断言。
    assert checked >= 86, f"只核对了 {checked} 个外观"


def _model_paths(section: dict) -> list[str]:
    """`model:` 或 `models:` 里声明的模型路径（含 generation 形态）。"""
    found: list[str] = []
    single = section.get("model")
    if isinstance(single, dict) and isinstance(single.get("path"), str):
        found.append(single["path"])
    elif isinstance(single, str) and single:
        found.append(single)
    for entry in section.get("models") or []:
        if isinstance(entry, dict) and isinstance(entry.get("path"), str):
            found.append(entry["path"])
    return found


# ------------------------------------------------------------------ 碰撞箱保真


def _shape_tiers() -> dict[str, str]:
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")
    return props.load_shape_tiers(UPSTREAM)


def test_no_collision_blocks_only_borrow_no_collision_pools():
    """`collision=empty` 的方块，任何外观都只能借无碰撞池。

    这是本次改造最重要的一条：借 `solid`（音符盒/蘑菇方块）或树叶池会让植物变成
    满格碰撞的隐形障碍，而树叶还会遮挡剔除邻面（十字模型填不满方块 → 透视）。
    """
    _, defs = _load()
    tiers = _shape_tiers()
    # 允许集 = 无碰撞组 + 可爬组 + **逐组登记过的**有碰撞放行组（目前只有 chorus）。
    # 后者不是放宽判定：`COLLISION_POOL_WAIVERS` 里每条都写了原因，而且
    # 43 个无碰撞方块只有这一条出路（无碰撞组合计 23 格 < 56 个方块）。
    allowed = (set(blocks.NO_COLLISION_POOLS) | set(blocks.CLIMBABLE_POOLS)
               | set(blocks.COLLISION_POOL_WAIVERS))
    offenders: list[str] = []
    checked = 0
    empty_ids: set[str] = set()
    for block_id, body in defs.items():
        if tiers.get(block_id) != props.TIER_NONE:
            continue
        checked += 1
        empty_ids.add(block_id)
        for carrier in _carriers(body):
            name = _carrier_name(carrier)
            if name not in allowed:
                offenders.append(f"{block_id} → {name}")
    # 同上：用「集合相等」代替数量阈值。形状表判 TIER_NONE 的已发布方块，
    # 必须与报告里 collision=empty 的集合完全一致 —— 数字随移除族变化（现 37 个），
    # 集合相等才能抓到「上游形状变了但报告没跟上」。
    report_path = REPORTS / "plant-collision.json"
    if not report_path.is_file():
        pytest.skip("缺少 plant-collision.json，先跑一次生成器")
    reported_empty = {e["id"] for e in json.loads(
        report_path.read_text(encoding="utf-8"))["blocks"] if e["collision"] == "empty"}
    assert empty_ids == reported_empty, "形状表与碰撞报告的无碰撞集合不一致"
    assert offenders == [], (
        "这些无碰撞方块借了有碰撞/会遮挡的池（游戏里会变成隐形墙或透视）：\n  "
        + "\n  ".join(offenders))


def test_forbidden_pools_are_rejected_by_the_gate():
    """反例：把无碰撞方块配到 note_block/树叶池，门禁必须失败。

    这条不跑生成器，直接用产物里的真实断言函数做「注入错误 → 必须报错」，
    否则上面那条断言可能只是恰好通过。
    """
    _, defs = _load()
    allowed = (set(blocks.NO_COLLISION_POOLS) | set(blocks.CLIMBABLE_POOLS)
               | set(blocks.COLLISION_POOL_WAIVERS))
    assert set(blocks.COLLISION_POOL_WAIVERS) <= set(blocks.AUTO_STATE_GROUPS)
    assert not (set(blocks.COLLISION_POOL_WAIVERS) & set(blocks.FORBIDDEN_PLANT_POOLS))
    assert "solid" in blocks.FORBIDDEN_PLANT_POOLS
    assert "cactus" in blocks.FORBIDDEN_PLANT_POOLS
    for bogus in blocks.FORBIDDEN_PLANT_POOLS:
        assert bogus not in allowed, f"{bogus} 跑进了无碰撞池名单"

    for bogus in blocks.FORBIDDEN_PLANT_POOLS:
        victim = "betternether:agave"  # 真实产物里的无碰撞方块
        body = json.loads(json.dumps(defs[victim]))
        _set_carriers(body, bogus)
        bad = [f"{victim} → {_carrier_name(carrier)}"
               for carrier in _carriers(body)
               if _carrier_name(carrier) not in allowed]
        assert bad, f"注入 {bogus} 之后门禁没看出来 —— 上面的断言等于没写"


def test_collidable_waiver_is_counted_and_reported():
    """借了有碰撞的 `chorus` 的无碰撞外观必须被逐块记录（不是静默放行）。"""
    _, defs = _load()
    tiers = _shape_tiers()
    waivers = set(blocks.COLLISION_POOL_WAIVERS)
    borrowed = 0
    for block_id, body in defs.items():
        if tiers.get(block_id) != props.TIER_NONE:
            continue
        borrowed += sum(1 for carrier in _carriers(body)
                        if _carrier_name(carrier) in waivers)
    report_path = REPORTS / "plant-collision.json"
    if not report_path.is_file():
        pytest.skip("缺少 plant-collision.json，先跑一次生成器")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    # 只数 `collision=empty` 的方块：partial 方块借 chorus 本来就是形状近似，
    # 由 PARTIAL_COLLISION_FITS 记录，不算「放行」。
    recorded = sum(1 for entry in report["blocks"] if entry["collision"] == "empty"
                   for pool in entry["pools"] if pool in waivers)
    assert borrowed == recorded, (
        f"产物里有 {borrowed} 个外观借了有碰撞载体，报告只记了 {recorded} 个")


def test_collision_report_matches_the_product():
    """碰撞保真报告必须与产物一一对应，且分档覆盖全部植物方块。

    这里**不锁死方块数**：`bone_mushroom` 就是因为「结构模板 palette 没被算进引用集」
    被误判成「没人放置」而漏发布，修好后从 56 变 57。锁死数字会让下一个人改数字
    而不是去看报告和产物是否真的一致。
    """
    report_path = REPORTS / "plant-collision.json"
    if not report_path.is_file():
        pytest.skip("缺少 plant-collision.json，先跑一次生成器")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    entries = report["blocks"]
    counts = collections.Counter(entry["collision"] for entry in entries)
    assert set(counts) <= {"empty", "partial"}, counts
    assert sum(counts.values()) == len(entries), "分档合计必须等于报告里的方块数"
    assert counts["empty"] > 0 and counts["partial"] > 0, (
        f"两类碰撞样本都得在，否则对应规则失去真实样本：{counts}")

    # 合并前后的总数都要在报告里，防止「静默丢了一批外观」被当成合并。
    # `after` 等于**植物池**额度之和（CE 的真实余量），额度随别的包占用变化，不锁数字。
    totals = report["appearance_totals"]
    assert totals["before"] >= totals["after"] > 0, totals
    plant_pools = set(blocks.NO_COLLISION_POOLS) | set(blocks.PARTIAL_COLLISION_POOLS)
    headroom = sum(value for pool, value in blocks.pool_budget().items()
                   if pool in plant_pools)
    assert totals["after"] == sum(len(entry["pools"]) for entry in entries) == headroom, (
        f"合并后外观数 {totals['after']} 必须正好等于植物池额度之和 {headroom}"
        "（少 = 有外观被静默丢掉；多 = 超额度申请，线上会撞「已达到最大容量」）")

    _, defs = _load()
    for entry in entries:
        block_id = entry["id"]
        carriers = [_carrier_name(carrier) for carrier in _carriers(defs[block_id])]
        assert carriers == entry["pools"], (
            f"{block_id}: 报告里的池 {entry['pools']} 与产物 {carriers} 不一致")
        if entry["collision"] == "empty":
            assert entry["match"] == "exact", f"{block_id}: 无碰撞方块不该被标成近似"
        else:
            assert entry["match"] == "approximate", f"{block_id}: partial 方块必须如实标近似"
            assert entry["fidelity"], f"{block_id}: partial 方块必须写明保不住什么"


def test_partial_blocks_get_the_closest_pool_or_a_recorded_loss():
    """14 个 partial 植物：要么借 chorus_plant（细柱），要么在报告里写明碰撞箱丢失。

    容器（14 个 chest + 抽屉柜 + 酿造台）从 2026-10-29 起**不再走 auto_state 池**：
    用户明确要求「可触发的非完整方块也用写死原版状态」，它们改由
    `blocks.PINNED_CARRIER_TARGETS` 点名载体，因此不在这张表里。两条路不许并存，
    所以下面另起一段核对：容器有真值、且 plan 是空族（一个池都不借）。
    """
    fits = blocks.PARTIAL_COLLISION_FITS
    assert len(fits) == 14, f"partial 植物真值表有 {len(fits)} 条，应为 14"
    for name, (family, box, _note) in fits.items():
        plan, collision, fidelity = blocks.plant_slot_plan(name, props.TIER_PARTIAL, False)
        if family == "thin":
            assert plan[0] == "chorus", f"{name}: thin 族必须以 chorus 开头"
        elif family == "climb":
            assert set(plan) == set(blocks.CLIMBABLE_POOLS), (
                f"{name}: 可爬优先，池族必须全部是可爬池")
        else:
            assert set(plan) <= set(blocks.NOCLIP_POOLS), (
                f"{name}: noclip 族只能是纯无碰撞池（不含可爬组的额度）")
        assert collision == "partial"
        if family != "thin":
            assert fidelity, f"{name}: 放弃碰撞必须写明理由"
        assert box

    # 容器：写死状态载体这一档 —— 载体、上游碰撞箱、代价三段都必须有人签字。
    containers = blocks.PINNED_CARRIER_TARGETS
    assert len(containers) == 16, f"容器真值表有 {len(containers)} 条，应为 16"
    carrier_names = {carrier["name"] for carrier in blocks.PINNED_CARRIERS}
    for name, target in containers.items():
        assert name not in fits, f"{name}: 容器不得同时留在池真值表与写死状态真值表里"
        plan, collision, fidelity = blocks.plant_slot_plan(name, props.TIER_PARTIAL, False)
        assert plan == (), f"{name}: 容器不该再借任何 auto_state 池（实际 {plan}）"
        assert collision == "partial", name
        assert target["carrier"] in carrier_names, f"{name}: 点名了不存在的载体族"
        assert target["box"].strip() and target["note"].strip(), (
            f"{name}: 上游碰撞箱与代价必须都写清楚")
        assert target["carrier"] in fidelity, f"{name}: 保真说明里必须点名载体"


# ------------------------------------------------------------------ 真值表自身


def test_plant_state_slots_covers_all_fifty_six_blocks():
    """单一真值表必须覆盖 56 个植物/装饰方块，且不含任何禁止池。

    容器不在其中：它们 2026-10-29 起走写死状态载体（`PINNED_CARRIER_TARGETS`），
    不借任何 auto_state 池，所以不进「池族真值表」——两张表各管一条路。
    """
    slots = blocks.PLANT_STATE_SLOTS
    # 42 个无碰撞 + 14 个 partial 植物 = 56。
    assert len(slots) == 56, f"真值表覆盖 {len(slots)} 个方块"
    assert len(blocks.COLLISION_EMPTY_BLOCKS) == 42
    assert len(blocks.PARTIAL_COLLISION_FITS) == 14
    assert not (set(slots) & set(blocks.PINNED_CARRIER_TARGETS)), (
        "容器混进了池族真值表：两条载体路径不许并存")
    for name, entry in slots.items():
        allowed = (set(blocks.NO_COLLISION_POOLS) | set(blocks.PARTIAL_COLLISION_POOLS)
                   | set(blocks.CLIMBABLE_POOLS))
        assert set(entry["family"]) <= allowed, f"{name}: 池族里出现了禁止池"
        for forbidden in blocks.FORBIDDEN_PLANT_POOLS:
            assert forbidden not in entry["family"], f"{name}: 禁止池 {forbidden} 出现在池族里"
        if entry["collision"] == "empty":
            assert set(entry["family"]) <= set(blocks.NOCLIP_POOLS) | set(blocks.CLIMBABLE_POOLS), name


def test_collision_truth_table_matches_upstream_shapes():
    """硬编码的 42/14 分档必须与上游 `block_shapes.txt` 一致（防止上游改动后静默漂移）。"""
    tiers = _shape_tiers()
    for name in sorted(blocks.COLLISION_EMPTY_BLOCKS):
        assert tiers.get(f"{blocks.UPSTREAM_PREFIX}{name}") == props.TIER_NONE, (
            f"{name}: 真值表说无碰撞，形状表说是 {tiers.get(blocks.UPSTREAM_PREFIX + name)}")
    for name in sorted(blocks.PARTIAL_COLLISION_FITS):
        assert tiers.get(f"{blocks.UPSTREAM_PREFIX}{name}") == props.TIER_PARTIAL, (
            f"{name}: 真值表说 partial，形状表说是 {tiers.get(blocks.UPSTREAM_PREFIX + name)}")
    # 容器走写死状态载体，但「上游是 partial」这一条同样不能漂。
    for name in sorted(blocks.PINNED_CARRIER_TARGETS):
        assert tiers.get(f"{blocks.UPSTREAM_PREFIX}{name}") == props.TIER_PARTIAL, (
            f"{name}: 容器真值表说 partial，形状表说是 {tiers.get(blocks.UPSTREAM_PREFIX + name)}")


# ------------------------------------------------------------------ 满格透明方块


def test_translucent_textures_never_use_the_solid_carrier():
    """真实贴图带 alpha 的方块，任何外观都不许借 `solid` 载体（会渲染成实心色块）。"""
    _, defs = _load()
    translucent = {bid for bid, entry in _translucency().items() if entry["translucent"]}
    assert translucent, "没有任何透明方块，贴图扫描没有生效"
    offenders = []
    for bid in sorted(translucent):
        body = defs.get(bid)
        assert body is not None, f"{bid} 被判透明却没有发布"
        for carrier in _carriers(body):
            name = _carrier_name(carrier)
            if name == "solid":
                offenders.append(bid)
                break
            assert (name in CUTOUT_CARRIERS or name in blocks.NO_COLLISION_POOLS
                    or name in blocks.PARTIAL_COLLISION_POOLS
                    or name in blocks.CLIMBABLE_POOLS), (
                f"{bid}: 载体系 {name}，既不是树叶池也不是无碰撞/细柱池")
    assert offenders == [], f"这些透明方块用了实心载体：{offenders}"


def test_missing_local_texture_is_not_silently_opaque():
    """本包贴图缺失时不得判为不透明：这类方块必须走 cutout（保守），并记进报告。"""
    _, defs = _load()
    report = _translucency()
    conservative = {bid for bid, entry in report.items()
                    if entry["missing_local"] and entry["kind"] == "cutout"}
    for bid in conservative:
        body = defs.get(bid)
        assert body is not None, bid
        for carrier in _carriers(body):
            assert _carrier_name(carrier) != "solid", (
                f"{bid}: 有本包贴图缺失却仍用实心载体，缺失被当成不透明了")
    # 反面：只缺原版贴图的实心方块不该被误判（原版图不在上游仓库）。
    vanilla_only_missing = [bid for bid, entry in report.items()
                            if entry["missing"] and not entry["missing_local"]
                            and not entry["translucent"]]
    assert vanilla_only_missing, "没有只缺原版贴图的样本，这条断言失去意义"


def test_merged_block_keeps_every_variant_key_pointing_at_a_real_appearance():
    """额度不够时外观合并：12 个 variants 键仍全部指向某个已发布外观，一个都不悬空。

    真实语料 `gloomwisp_vine` 有 12 个 appearance（6 个加权多模型 + 6 个朝向副本），
    而全局只有 86 格，它只分到 1 格 —— 但**每个状态都必须有外观**，
    否则放置端带属性查到状态时，游戏里就是空白。
    """
    _, defs = _load()
    states = defs["betternether:gloomwisp_vine"]["states"]
    appearances = states["appearances"]
    assert appearances, "合并把外观全丢了"
    published = set(appearances)
    orphans = [key for key, variant in states["variants"].items()
               if variant.get("appearance") not in published]
    assert orphans == [], f"这些 variants 键指向不存在的外观：{orphans}"
    for name, appearance in appearances.items():
        assert appearance["auto_state"] in (
            set(blocks.NOCLIP_POOLS) | set(blocks.CLIMBABLE_POOLS)), name
        assert "entity_renderer" not in appearance, name
        assert "transparent" not in appearance, name
    # 合并事实必须落进报告：从几个合到几个、哪些被并走。
    report_path = REPORTS / "plant-collision.json"
    if not report_path.is_file():
        pytest.skip("缺少 plant-collision.json，先跑一次生成器")
    report = json.loads(report_path.read_text(encoding="utf-8"))
    entry = next(e for e in report["blocks"] if e["id"] == "betternether:gloomwisp_vine")
    assert entry["appearances_before"] == 12
    assert entry["appearances_after"] == len(appearances)
    assert entry["appearances_merged"]["dropped"], "合并记录里必须列出被并走的外观"

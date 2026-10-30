"""CraftEngine 视觉状态池的**跨包共享余量账本**。

## 这份账本回答什么问题

CE 的 `AutoStateGroup`（`auto_state: tripwire` 这类组）是**跨包共享**的资源：同一个 CE
实例里，BetterEnd / FarmersDelight / 本包都在抢同一批原版状态。所以「本包能用多少个槽位」
不是 CE 的总容量，而是**账本里的 `free`（真实空位）**。

把总容量当成额度是这次改造里最容易犯、最难在本地发现的错误：配额写大了不会让任何测试红，
只会在服务器上表现为「视觉状态组 X 已达到 N 个槽位的最大容量，无法为 xxx 分配状态」——
那些方块**没有外观**，而构建是全绿的。

## 采集方式（重采必读）

每条池的 `cap` = `internal/configuration/mappings.yml` 里该组方块**未被注释**的状态条目数；
`used` = 目标服务器 `plugins/CraftEngine/cache/visual_block_states.json` 里**已经分配给别的包**
的状态数（按映射目标的原版方块聚合）；`free = cap - used` 才是本包能拿到的。碰撞/遮挡语义
另由 `scripts/ce_patch/StateCollisionProbe.java` 逐状态取证（输出 `collstates.tsv`）：
`collEmpty=true` 才是无碰撞、`canOcclude=false` 才不会挖掉邻面。

**什么时候必须重采**：`mappings.yml` 的启用条目变化、别的 CE 包占用变化、CE 升级。
本文件是数据快照，不随代码自动更新；`COLLECTED` 只有一个日期，就是为了让「这是哪天的账」
一眼可见。

## 谁能读这份数据

生成器（`scripts/bnlib/blocks.py` 的 `POOL_BUDGET` 是**本期申请额度**）与门禁
（`tools/verify_block_contracts.py` 的 `plant_states.state_slot_budget`）读同一份账本：
额度不得超过 `free`，账本自身必须满足 `cap - used == free`。

不在账本里的池**不代表不能用**，代表**没采集过**——门禁会因此判红，而不是默认放行。
"""

from __future__ import annotations

from typing import Mapping

#: 采集日期（IS08601）。mappings.yml / 别的包占用一旦变化，这个日期必须跟着变。
COLLECTED = "2026-10-28"

#: 采集口径：写在一处，免得每条都重复一遍。
SOURCE = (
    "cap = internal/configuration/mappings.yml 中该组未注释的状态条目数；"
    "used = plugins/CraftEngine/cache/visual_block_states.json 按映射目标原版方块聚合的已分配数；"
    "free = cap - used。碰撞/遮挡语义由 scripts/ce_patch/StateCollisionProbe.java 逐状态取证"
)

#: 池账本：CE `auto_state` 组名 → `cap`（总槽位）/ `used`（别的包已占）/ `free`（本包可用）。
#:
#: 键与 `blocks.POOL_BUDGET` 同名（组名的单数写法，与 CE `AutoStateGroup` 接受的一致）；
#: 同一个原版方块族的多个组（`tripwire`/`lower_tripwire`/`higher_tripwire`）共用一份账，
#: 记在 `tripwire` 这一条上。
_POOL_MEASUREMENTS: Mapping[str, Mapping[str, object]] = {
    "tripwire": {
        "cap": 126, "used": 87, "free": 39,
        "vanilla": "minecraft:tripwire",
        "note": "绊线三组（tripwire/lower/higher）共用同一批状态，合并记账。"
                "2026-10-29 校准：used 只记**别包**占用(87)，本包自己占的(13)是可复用的，不计入",
    },
    "cave_vine": {
        "cap": 50, "used": 49, "free": 1,
        "vanilla": "minecraft:cave_vines",
        "note": "mappings 里 age=25 两行必须保持启用，否则池只有 48",
    },
    "sapling": {
        "cap": 8, "used": 8, "free": 0,
        "vanilla": "minecraft:oak_sapling",
        "note": "8 个树苗各只放出 stage=1 一个状态（mappings 每个方块 1 条）；"
                "只剩 pale_oak_sapling 空着。本包额度 0，不需要它",
    },
    "kelp": {
        "cap": 25, "used": 25, "free": 0,
        "vanilla": "minecraft:kelp",
        "note": "",
    },
    "sugar_cane": {
        "cap": 15, "used": 15, "free": 0,
        "vanilla": "minecraft:sugar_cane",
        "note": "共享 id=transparent 的那个被清空模型的宿主也占这一格",
    },
    "weeping_vine": {
        "cap": 25, "used": 25, "free": 0,
        "vanilla": "minecraft:weeping_vines",
        "note": "2026-10-29 按测试端 cache(1120条)实测校准：weeping 已被占满(used=25)，"
                "旧账记 used=10/free=15 是过期快照；本包额度改 0，可爬改从 twisting 取",
    },
    "twisting_vine": {
        "cap": 25, "used": 5, "free": 20,
        "vanilla": "minecraft:twisting_vines",
        "note": "2026-10-29 按测试端 cache 实测校准：twisting 仅 5 已占、free=20（旧账记 used=24/free=1 "
                "正好记反了）。本包可爬植物的额度主要落在这里",
    },
    "pressure_plate": {
        "cap": 28, "used": 1, "free": 27,
        "vanilla": "minecraft:light_weighted_pressure_plate + heavy_",
        "note": "两组各 14 条启用（共 28）。实测 16/16 状态 collEmpty=true、canOcclude=false；"
                "代价是踩上去输出红石信号，对植物可接受",
    },
    "chorus": {
        "cap": 63, "used": 0, "free": 63,
        "vanilla": "minecraft:chorus_plant",
        "note": "mappings 里 63 条默认被注释，补丁启用后可用；有碰撞（10/16 全高柱）但不遮挡",
    },
    "cactus": {
        "cap": 15, "used": 4, "free": 11,
        "vanilla": "minecraft:cactus",
        "note": "**不可当植物载体**：16 个状态全部 canOcclude=true（借它 = 挖掉邻面 → 透视）",
    },
    # 下面两个是**满格 cutout 容器**，由 CE **内置** mappings 定义成组，
    # 用户 `internal/configuration/mappings.yml` 里 grep 不到这两个组名（命中 0），
    # 所以 `cap` 无法按用户文件采集 —— 这里如实写 None，不编一个数字出来。
    # `used` 仍是 cache 实测值（按组覆盖的原版树叶方块聚合）。
    "non_tintable_leaves": {
        "cap": None, "used": 58, "free": None,
        "vanilla": "minecraft:azalea_leaves/…（不染色树叶组）",
        "note": "CE 内置组，cap 不可采集。门禁跳过该池的余量断言，"
                "但产物占用仍受 POOL_BUDGET 约束；耗尽时靠 CE「视觉状态组已达最大容量」日志与 "
                "`PoolAllocator.cutout_overflow` 暴露",
    },
    "tintable_leaves": {
        "cap": None, "used": 32, "free": None,
        "vanilla": "minecraft:oak_leaves/…（可染色树叶组）",
        "note": "CE 内置组，cap 不可采集。口径同 non_tintable_leaves",
    },
}

#: 采集时**没有真实余量可对账**、但 `blocks.POOL_BUDGET` 里仍申请了额度的池。
#:
#: 现在只剩两个 CE **内置**组（树叶）：用户在 `mappings.yml` 里 grep 不到这两个组名，
#: `cap` 只能写 None。它们的账本条目仍然存在（`used` 是实测），只是「余量」这一列不可判定，
#: 门禁因此跳过余量断言、改为在报告里如实标注，产物占用仍受 `POOL_BUDGET` 约束。
#:
#: 空着不是遗漏：写任何 `cap` 数字都是编造。真要把它们纳入余量对账，得先从 CE jar 的
#: 内置 mappings 里把组的原始条目数取出来。
UNCOLLECTED_POOLS: tuple[str, ...] = (
    "non_tintable_leaves",
    "tintable_leaves",
)

#: 对外暴露的账本：每条都带上采集日期与口径，避免「这张表是哪来的」这种问题。
POOL_LEDGER: Mapping[str, Mapping[str, object]] = {
    pool: {"collected": COLLECTED, "source": SOURCE, **entry}
    for pool, entry in _POOL_MEASUREMENTS.items()
}


def ledger(collected: str | None = None) -> dict[str, dict[str, object]]:
    """账本副本（`collected` 非空时只取该次采集的条目，便于对照历史快照）。"""
    rows: dict[str, dict[str, object]] = {}
    for pool, entry in POOL_LEDGER.items():
        if collected is not None and entry.get("collected") != collected:
            continue
        rows[str(pool)] = dict(entry)
    return rows

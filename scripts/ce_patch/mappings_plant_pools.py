#!/usr/bin/env python3
"""给 CraftEngine 部署侧 ``mappings.yml`` 打「chorus_plant 视觉状态池」补丁。

## 这是什么

``<服务端>/plugins/CraftEngine/resources/internal/configuration/mappings.yml`` 是
**CraftEngine 插件自带的核心配置**，不是本项目（更好的地狱）的配置。它决定每个
原版方块有多少个 blockstate 可以被释放出来给 CE 自己用。

**CE 升级会覆盖 mappings.yml，升级后必须重跑 `--apply`。** 备份写在
``plugins/CraftEngine/_bak/``（不在 resources/ 下，避免被 CE 当配置扫描）。

    python scripts/ce_patch/mappings_plant_pools.py --check
    python scripts/ce_patch/mappings_plant_pools.py --apply
    python scripts/ce_patch/mappings_plant_pools.py --verify

## 硬事实：`auto_state` 的合法值是 CE 的固定枚举，不是自由命名的组

CE 从 ``craft-engine-paper-plugin-*.jar`` 的 ``AutoStateGroup`` 枚举里取值，合法值
**只有这 24 个**：

    NON_TINTABLE_LEAVES WATERLOGGED_NON_TINTABLE_LEAVES TINTABLE_LEAVES
    WATERLOGGED_TINTABLE_LEAVES LEAVES WATERLOGGED_LEAVES LOWER_TRIPWIRE
    HIGHER_TRIPWIRE NOTE_BLOCK BROWN_MUSHROOM RED_MUSHROOM MUSHROOM_STEM TRIPWIRE
    SUGAR_CANE CACTUS CAVE_VINES WEEPING_VINES TWISTING_VINES KELP CHORUS
    PRESSURE_PLATE SAPLING MUSHROOM SOLID

每个枚举值绑定固定的原版方块（例如 ``CHORUS`` → ``chorus_plant``）。后果：

* **写错组名不会报错退出，而是该方块完全没有外观**（`auto_state: chorus_plant`
  是错的，正确是 `auto_state: chorus`）；
* 往 mappings.yml 里加某个方块、但枚举里没有对应组，等于**白白释放了那个原版
  方块的状态**：世界里原生的这些方块会按 RHS 重渲染（连接形态塌成一种、甚至完全
  不可见），却没有任何自定义方块能借到这些空位 —— 纯副作用、零收益。

所以「能扩哪些方块」必须反过来从枚举倒推：枚举里有哪些组，才谈得上扩哪些方块。

## 判定标准（怎么算「能用」）

三个硬指标，全部从服务端字节码 / 反射实测核实：

1. **无碰撞** —— `Blocks` 注册链里有 `Properties.noCollision()`；
2. **不遮挡** —— `canOcclude == false`。26.3 里 `noCollision()` 同时把
   `canOcclude` 置 false（`BlockBehaviour$Properties.noCollision()` 字节码同时写
   `hasCollision=false` 和 `canOcclude=false`），而 `solidRender`（客户端面剔除依据）
   由 `canOcclude && 遮挡形状满格` 算出，所以无碰撞 ⇒ 不遮挡；
3. **不发光** —— 状态最大 `lightEmission` 必须为 0，否则借来的植物会自己照亮地形
   （`glow_lichen` 7、`light` 15、`fire` 15、`sea_pickle` 15 因此全部排除）。

取证工具：
* ``scripts/ce_patch/VanillaBlockStates.java`` —— 反射服务端 jar，打印每个原版方块
  的真实状态数 / 碰撞是否为空 / `canOcclude` / 最大发光 / 属性列表；
* ``scripts/ce_patch/StateCollisionProbe.java`` / ``PartialCarrierProbe.java`` ——
  逐状态的碰撞箱与「部分占用」判定。

## 本补丁做什么

只启用 ``chorus_plant`` 的 63 条映射（CE 默认文件里自己注释掉的那批，第 4492 行起）：

    chorus_plant[6 个连接面的 63 种组合] -> chorus_plant[全 true]

实测真值：

    chorus_plant  states=64  collEmpty=false  canOcclude=false
                  box=[0.1875,0,0.1875] -> [0.8125,1,0.8125]
    vine          states=32  collEmpty=true   canOcclude=false

``chorus_plant`` 的空位只适合**细柱类 / 站得上去的植物**（10/16 宽全高柱、不遮挡），
**不能给地面草**（那是满格碰撞感、玩家踩上去会被托住）。它也是枚举里唯一一个
「6 面 boolean + 不遮挡」的大池，所以是本包现在的主力池。

## 可用空位对照（`--verify` 打印的表）

| 池 | CE 组名 | 状态数 | 启用条目 | 说明 |
|---|---|---|---|---|
| chorus_plant | ``CHORUS`` | 64 | 63 | 细柱植物主力池 |
| tripwire | ``TRIPWIRE`` / ``HIGHER_TRIPWIRE`` / ``LOWER_TRIPWIRE`` | 128 | 126 | 默认池 |
| weeping_vines | ``WEEPING_VINES`` | 26 | 25 | 默认池 |
| cave_vines | ``CAVE_VINES`` | 52 | 50 | 默认池 |
| twisting_vines | ``TWISTING_VINES`` | 26 | 25 | 默认池 |
| kelp | ``KELP`` | 26 | 25 | 默认池 |
| sugar_cane | ``SUGAR_CANE`` | 16 | 15 | 默认池 |
| 8 种树苗 | ``SAPLING`` | 2×8 | 8 | 默认池 |
| cactus | ``CACTUS`` | 16 | 15 | **不可用**：16 状态全部 `canOcclude=true` |

合计可用空位 **86**（chorus 63 + tripwire 9 + weeping 9 + cave 1 + twisting 1 +
kelp 1 + sugar_cane 1 + sapling 1）。

## 明确不要碰（脚本拒绝生成）

* ``sculk_vein`` / ``resin_clump`` / ``vine`` —— **枚举里没有对应组**，加了只会让
  原生方块重渲染（vine 31 条会把丛林藤蔓的全部连接状态塌成「不可见」），零收益。
* ``dead_*_coral`` 族 15 个方块 —— 同上，没有组；而且 60 条全释放需要把世界里的
  死珊瑚整体改渲染成活珊瑚。
* ``spruce_leaves`` / ``birch_leaves`` 28 状态 —— 有组（``LEAVES`` 家族）但**满格
  碰撞**，只能给树叶类。
* ``kelp_plant`` / ``weeping_vines_plant`` / ``twisting_vines_plant`` —— 都只有
  **1** 个状态（`GrowingPlantBodyBlock` 没有任何 blockstate 属性），释放 0 槽位。
* ``glow_lichen`` / ``light`` / ``fire`` / ``sea_pickle`` —— 会发光。
* ``cactus`` —— 16 状态全部 `canOcclude=true`，借来会挖掉邻居的面。

## 用法

    --check            只报告：各池「启用条目 / 已占 / 真实空位」，合计 vs --target
    --apply            写入（先备份）；幂等，重复执行不会重复插入
    --verify           校验：YAML 可解析、每行属性合法、映射目标没被抢、补丁行齐全，
                       并重新打印三列表
    --revert-blocks X  把指定方块已启用的映射行恢复成注释（X 为逗号分隔方块名，
                       或 `@legacy-coral` 表示整族死珊瑚）。幂等。
    --profile balanced 唯一的 profile：chorus_plant 63 条
    --blocks a,b       覆盖 profile 的方块选择（目前只有 chorus_plant 合法）
    --target 162       真实外观需求数，用来算「还缺多少」

`--apply` 只**增删条目**：已存在且启用的行原样保留，被注释的同类行就地取消注释，
缺失的行插到该方块已有条目的末尾，不改动文件其余部分的排版与缩进。
"""

from __future__ import annotations

import argparse
import itertools
import json
import re
import shutil
import sys
import time
from pathlib import Path

DEFAULT_MAPPINGS = Path(
    r"E:/我的世界插件/folia测试端/plugins/CraftEngine/resources/internal/configuration/mappings.yml"
)
DEFAULT_CACHE = Path(
    r"E:/我的世界插件/folia测试端/plugins/CraftEngine/cache/visual_block_states.json"
)
DEFAULT_BACKUP_DIR = Path(r"E:/我的世界插件/folia测试端/plugins/CraftEngine/_bak")

# ---------------------------------------------------------------------------
# 数据表：全部从服务端 versions/26.3/mucore-26.3.jar 反射实测得出
#   states    状态总数
#   coll      默认状态碰撞形状为空
#   occl      canOcclude（客户端面剔除 / 光照遮挡）
#   light     所有状态里的最大 lightEmission
# ---------------------------------------------------------------------------
BLOCK_FACTS: dict[str, dict] = {
    "chorus_plant": dict(states=64, coll=False, occl=False, light=0,
                         note="碰撞 0.1875~0.8125 全高细柱，不遮挡"),
    "tripwire": dict(states=128, coll=True, occl=False, light=0, note="绊线"),
    "oak_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "spruce_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "birch_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "jungle_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "acacia_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "cherry_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "dark_oak_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "pale_oak_sapling": dict(states=2, coll=True, occl=False, light=0, note="树苗 stage"),
    "sugar_cane": dict(states=16, coll=True, occl=False, light=0, note="甘蔗 age"),
    "weeping_vines": dict(states=26, coll=True, occl=False, light=0, note="垂泪藤 head"),
    "twisting_vines": dict(states=26, coll=True, occl=False, light=0, note="缠怨藤 head"),
    "cave_vines": dict(states=52, coll=True, occl=False, light=0, note="洞穴藤 head"),
    "kelp": dict(states=26, coll=True, occl=False, light=0, note="海带 head"),
    # ---- 下面只用于校验 / 文档，不参与空位统计 ----
    "cactus": dict(states=16, coll=False, occl=True, light=0,
                   note="不可用：16 状态全部 canOcclude=true"),
    "spruce_leaves": dict(states=28, coll=False, occl=False, light=0, note="满格碰撞，只能给树叶类"),
    "birch_leaves": dict(states=28, coll=False, occl=False, light=0, note="满格碰撞，只能给树叶类"),
    "kelp_plant": dict(states=1, coll=True, occl=False, light=0, note="无属性，释放 0 槽"),
    "weeping_vines_plant": dict(states=1, coll=True, occl=False, light=0, note="无属性，释放 0 槽"),
    "twisting_vines_plant": dict(states=1, coll=True, occl=False, light=0, note="无属性，释放 0 槽"),
    "cave_vines_plant": dict(states=2, coll=True, occl=False, light=0, note="只有 berries 两个状态"),
    "glow_lichen": dict(states=128, coll=True, occl=False, light=7, note="发光 7"),
    "light": dict(states=32, coll=True, occl=False, light=15, note="发光 0~15"),
    "fire": dict(states=512, coll=True, occl=False, light=15, note="发光 15"),
    "sea_pickle": dict(states=8, coll=True, occl=False, light=15, note="发光"),
    **{
        f"{p}_coral": dict(states=s, coll=True, occl=False, light=0, note="枚举无组，禁止借出")
        for p, s in [(c, 2) for c in ("tube", "brain", "bubble", "fire", "horn")]
    },
    **{
        f"{p}_coral_fan": dict(states=2, coll=True, occl=False, light=0, note="枚举无组，禁止借出")
        for p in ("tube", "brain", "bubble", "fire", "horn")
    },
    **{
        f"{p}_coral_wall_fan": dict(states=8, coll=True, occl=False, light=0, note="枚举无组，禁止借出")
        for p in ("tube", "brain", "bubble", "fire", "horn")
    },
    "sculk_vein": dict(states=128, coll=True, occl=False, light=0, note="枚举无组，禁止借出"),
    "resin_clump": dict(states=128, coll=True, occl=False, light=0, note="枚举无组，禁止借出"),
    "vine": dict(states=32, coll=True, occl=False, light=0, note="枚举无组，禁止借出"),
}

SAPLINGS = [
    "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
    "acacia_sapling", "cherry_sapling", "dark_oak_sapling", "pale_oak_sapling",
]

# --verify / --check 的表格行。counted=False 的行只展示，不计入可用空位。
POOL_GROUPS: list[dict] = [
    dict(label="chorus_plant", blocks=["chorus_plant"], group="CHORUS", counted=True),
    dict(label="tripwire", blocks=["tripwire"],
         group="TRIPWIRE / HIGHER / LOWER", counted=True),
    dict(label="weeping_vines", blocks=["weeping_vines"], group="WEEPING_VINES", counted=True),
    dict(label="cave_vines", blocks=["cave_vines"], group="CAVE_VINES", counted=True),
    dict(label="twisting_vines", blocks=["twisting_vines"], group="TWISTING_VINES", counted=True),
    dict(label="kelp", blocks=["kelp"], group="KELP", counted=True),
    dict(label="sugar_cane", blocks=["sugar_cane"], group="SUGAR_CANE", counted=True),
    dict(label="sapling (8 种)", blocks=SAPLINGS, group="SAPLING", counted=True),
    dict(label="cactus", blocks=["cactus"], group="CACTUS", counted=False,
         note="不计入：16 状态全部 canOcclude=true，本包已弃用"),
]

CHORUS_DIRS = ("down", "east", "north", "south", "up", "west")

ENTRY_RE = re.compile(r"^(\s*)([a-z0-9_]+)(?:\[([^\]]*)\])?\s*:\s*([a-z0-9_]+)(?:\[([^\]]*)\])?\s*$")
COMMENT_ENTRY_RE = re.compile(r"^(\s*)#\s*([a-z0-9_]+)(?:\[([^\]]*)\])?\s*:\s*([a-z0-9_]+)(?:\[([^\]]*)\])?\s*$")
BLOCK_HEADER_RE = re.compile(r"^\s*####")


# ---------------------------------------------------------------------------
# 补丁行生成
# ---------------------------------------------------------------------------
def chorus_plant_entries() -> list[tuple[str, str, str]]:
    """chorus_plant 的 6 个 boolean 连接面，64 个状态。

    留「全 true」当映射目标（CE 默认文件里那 63 条注释行就是这个形状），
    其余 63 个逐条映射过去。对应 CE ``auto_state`` 组名 ``CHORUS``。
    """
    def state(vals: dict[str, bool]) -> str:
        body = ",".join(f"{d}={str(vals[d]).lower()}" for d in CHORUS_DIRS)
        return f"chorus_plant[{body}]"

    target_vals = {d: True for d in CHORUS_DIRS}
    target = state(target_vals)
    out = []
    for combo in itertools.product((False, True), repeat=len(CHORUS_DIRS)):
        vals = dict(zip(CHORUS_DIRS, combo))
        if vals == target_vals:
            continue
        out.append(("chorus_plant", state(vals), target))
    return out


POOL_BUILDERS = {
    "chorus_plant": chorus_plant_entries,
}

PROFILE_DEFAULT_BLOCKS = {
    "balanced": ["chorus_plant"],   # 63 条，CHORUS 组
}


def build_entries(profile: str, blocks: list[str] | None) -> list[tuple[str, str, str]]:
    chosen = blocks if blocks is not None else PROFILE_DEFAULT_BLOCKS[profile]
    entries: list[tuple[str, str, str]] = []
    for b in chosen:
        if b not in POOL_BUILDERS:
            raise SystemExit(
                f"拒绝生成 {b}：CE auto_state 枚举里没有对应组，加了只会白吃原版状态。"
                f"目前唯一合法可扩的是: {', '.join(POOL_BUILDERS)}")
        entries.extend(POOL_BUILDERS[b]())
    return entries


def legacy_coral_blocks() -> list[str]:
    return [f"dead_{c}_{s}" for c in ("tube", "brain", "bubble", "fire", "horn")
            for s in ("coral", "coral_fan", "coral_wall_fan")]


# ---------------------------------------------------------------------------
# mappings.yml 读写
# ---------------------------------------------------------------------------
def read_lines(path: Path) -> list[str]:
    with path.open(encoding="utf-8") as f:
        return f.read().split("\n")


def parse_state(text: str) -> tuple[str, dict[str, str]]:
    m = re.match(r"^([a-z0-9_]+)(?:\[([^\]]*)\])?$", text.strip())
    if not m:
        raise ValueError(f"无法解析的状态: {text}")
    block = m.group(1)
    props: dict[str, str] = {}
    if m.group(2):
        for part in m.group(2).split(","):
            if not part.strip():
                continue
            k, _, v = part.partition("=")
            props[k.strip()] = v.strip()
    return block, props


def collect_pools(lines: list[str]) -> tuple[dict[str, int], dict[str, int]]:
    """返回 (已启用条数 per block, 被注释条数 per block)。"""
    enabled: dict[str, int] = {}
    commented: dict[str, int] = {}
    for raw in lines:
        m = COMMENT_ENTRY_RE.match(raw)
        if m:
            commented[m.group(2)] = commented.get(m.group(2), 0) + 1
            continue
        m = ENTRY_RE.match(raw)
        if m:
            enabled[m.group(2)] = enabled.get(m.group(2), 0) + 1
    return enabled, commented


def entry_key(lhs: str, rhs: str) -> str:
    def norm(s: str) -> str:
        b, p = parse_state(s)
        return b + "[" + ",".join(f"{k}={p[k]}" for k in sorted(p)) + "]"
    return norm(lhs) + " -> " + norm(rhs)


def find_existing(lines: list[str], lhs: str, rhs: str) -> tuple[str, int] | None:
    """查找该行当前状态：('enabled'|'commented', 行号)。"""
    want = entry_key(lhs, rhs)
    for i, raw in enumerate(lines):
        for mode, rx in (("enabled", ENTRY_RE), ("commented", COMMENT_ENTRY_RE)):
            m = rx.match(raw)
            if not m:
                continue
            try:
                cur = entry_key(f"{m.group(2)}[{m.group(3) or ''}]",
                                f"{m.group(4)}[{m.group(5) or ''}]")
            except ValueError:
                continue
            if cur == want:
                return mode, i
    return None


def insert_position(lines: list[str], block: str) -> int | None:
    """该方块最后一行的下一个位置；没有该方块则返回 None。"""
    last = None
    for i, raw in enumerate(lines):
        m = COMMENT_ENTRY_RE.match(raw) or ENTRY_RE.match(raw)
        if m and m.group(2) == block:
            last = i
    return None if last is None else last + 1


SECTION_HEADERS = {
    "chorus_plant": "#### Chorus Plant (植物池扩展) ####",
}


def enabled_lhs_map(lines: list[str]) -> dict[str, str]:
    """已启用行的 LHS -> RHS（规范化），用来发现冲突，避免同一 LHS 被映射两次。"""
    out: dict[str, str] = {}
    for raw in lines:
        m = ENTRY_RE.match(raw)
        if not m:
            continue
        try:
            lhs_b, lhs_p = parse_state(f"{m.group(2)}[{m.group(3) or ''}]")
            rhs_b, rhs_p = parse_state(f"{m.group(4)}[{m.group(5) or ''}]")
        except ValueError:
            continue
        lhs = lhs_b + "[" + ",".join(f"{k}={lhs_p[k]}" for k in sorted(lhs_p)) + "]"
        rhs = rhs_b + "[" + ",".join(f"{k}={rhs_p[k]}" for k in sorted(rhs_p)) + "]"
        out[lhs] = rhs
    return out


def backup(path: Path, backup_dir: Path, tag: str) -> Path:
    backup_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    dst = backup_dir / f"{path.name}.bak-{tag}-{stamp}"
    shutil.copy2(path, dst)
    return dst


def apply_patch(path: Path, backup_dir: Path, entries: list[tuple[str, str, str]],
                force: bool = False) -> dict:
    lines = read_lines(path)

    # 先查冲突：同一个 LHS 已有别的目标（例如手工改过 mappings，或换过池组合）。
    # 这种情况必须先从备份恢复，不能硬插。
    existing = enabled_lhs_map(lines)
    conflicts = []
    for block, lhs, rhs in entries:
        lb, lp = parse_state(lhs)
        rb, rp = parse_state(rhs)
        key = lb + "[" + ",".join(f"{k}={lp[k]}" for k in sorted(lp)) + "]"
        want = rb + "[" + ",".join(f"{k}={rp[k]}" for k in sorted(rp)) + "]"
        if key in existing and existing[key] != want:
            conflicts.append(f"{lhs}: 已有目标 {existing[key]}，本次要写入 {want}")
    if conflicts and not force:
        raise SystemExit(
            f"发现 {len(conflicts)} 条冲突（同一状态被映射到不同的目标），未写入。\n"
            f"  请先用 _bak/ 里的备份恢复 mappings.yml，再重跑。\n"
            + "\n".join("  - " + c for c in conflicts[:10]))

    dst = backup(path, backup_dir, "before-plant-pool-expand")

    stats = {"already": 0, "uncommented": 0, "inserted": 0}
    for block, lhs, rhs in entries:
        found = find_existing(lines, lhs, rhs)
        if found is not None:
            mode, idx = found
            if mode == "enabled":
                stats["already"] += 1
                continue
            m = COMMENT_ENTRY_RE.match(lines[idx])
            lines[idx] = f"{m.group(1)}{lines[idx].lstrip().lstrip('#').strip()}"
            stats["uncommented"] += 1
            continue

        pos = insert_position(lines, block)
        new_line = f"  {lhs}: {rhs}"
        if pos is None:
            while lines and not lines[-1].strip():
                lines.pop()
            lines.append("")
            lines.append(f"  {SECTION_HEADERS.get(block, f'#### {block} (植物池扩展) ####')}")
            lines.append(new_line)
            lines.append("")
        else:
            lines.insert(pos, new_line)
        stats["inserted"] += 1

    text = "\n".join(lines)
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text, encoding="utf-8")
    return {**stats, "backup": str(dst)}


def revert_blocks(path: Path, backup_dir: Path, blocks: set[str]) -> dict:
    """把指定方块已启用的映射行恢复成注释状态，幂等。

    只动「已启用且方块名在 blocks 里」的行，缩进原样保留（变成 `<缩进># <原文>`）。
    """
    lines = read_lines(path)
    dst = backup(path, backup_dir, "before-revert")

    reverted = 0
    for i, raw in enumerate(lines):
        m = ENTRY_RE.match(raw)
        if not m or m.group(2) not in blocks:
            continue
        lines[i] = f"{m.group(1)}# {raw.strip()}"
        reverted += 1

    text = "\n".join(lines)
    if not text.endswith("\n"):
        text += "\n"
    path.write_text(text, encoding="utf-8")
    return {"reverted": reverted, "backup": str(dst)}


# ---------------------------------------------------------------------------
# 校验
# ---------------------------------------------------------------------------
def load_cache(path: Path) -> dict[str, int]:
    """visual_block_states.json 是 `自定义方块视觉状态 -> 借用到的原版状态`，
    按「映射目标的原版方块名」聚合成已占数。"""
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    used: dict[str, int] = {}
    for _, v in data.items():
        m = re.match(r"minecraft:([a-z0-9_]+)", v)
        if m:
            used[m.group(1)] = used.get(m.group(1), 0) + 1
    return used


def collect_targets(lines: list[str]) -> dict[str, set[str]]:
    """每个方块作为「映射目标」（RHS）出现的状态集合。"""
    out: dict[str, set[str]] = {}
    for raw in lines:
        if raw.strip().startswith("#"):
            continue
        m = ENTRY_RE.match(raw)
        if not m:
            continue
        b, p = parse_state(f"{m.group(4)}[{m.group(5) or ''}]")
        out.setdefault(b, set()).add(",".join(f"{k}={p[k]}" for k in sorted(p)))
    return out


def report_pools(lines: list[str], cache: dict[str, int], target: int) -> int:
    """打印「启用条目 / 已占 / 真实空位」三列表。

    已占 = cache 里映射目标落在该原版方块上的自定义方块外观数量。
    真实空位 = 启用条目 - 已占。只有 counted=True 的行计入合计。
    """
    enabled, _ = collect_pools(lines)

    total_free = 0
    print(f"{'池':18s}{'CE 组名':26s}{'状态数':>7s}{'启用条目':>9s}{'已占':>7s}{'空位':>7s}  备注")
    for row in POOL_GROUPS:
        blocks = row["blocks"]
        states = sum(BLOCK_FACTS.get(b, {}).get("states", 0) for b in blocks)
        cap = sum(enabled.get(b, 0) for b in blocks)
        used = sum(cache.get(b, 0) for b in blocks)
        free = max(cap - used, 0)
        note = row.get("note", "")
        if not row["counted"] and not note:
            note = "不计入可用空位"
        if row["counted"]:
            total_free += free
        print(f"{row['label']:18s}{row['group']:26s}{states:7d}{cap:9d}{used:7d}{free:7d}  {note}")

    print()
    print("注：`cave_vines_plant`（2 状态，只有 berries 属性）可能同属 CAVE_VINES 组，"
          "但当前 0 启用 / 0 已占，未验证，按口径不计入。")
    print(f"可用空位合计: {total_free} / 外观需求 {target}  "
          f"({'够用，余量 ' + str(total_free - target) if total_free >= target else '缺 ' + str(target - total_free)})")
    return total_free


def verify(path: Path, cache: dict[str, int], target: int, entries: list[tuple[str, str, str]]) -> int:
    problems: list[str] = []

    try:
        import yaml
    except ImportError:  # pragma: no cover
        yaml = None
    lines = read_lines(path)
    text = "\n".join(lines)

    if yaml is not None:
        try:
            data = yaml.safe_load(text)
            if not isinstance(data, dict) or "block_state_mappings" not in data:
                problems.append("YAML 解析结果缺少 block_state_mappings 顶层键")
        except Exception as exc:  # noqa: BLE001
            problems.append(f"YAML 解析失败: {exc}")
    else:
        problems.append("未安装 PyYAML，跳过 YAML 解析校验")

    # 每行结构 + 属性是否与实测状态一致
    enabled: dict[str, int] = {}
    for i, raw in enumerate(lines, 1):
        if raw.strip().startswith("#") or not raw.strip():
            continue
        m = ENTRY_RE.match(raw)
        if not m:
            if ":" in raw and not BLOCK_HEADER_RE.match(raw) and "block_state_mappings" not in raw \
                    and not raw.strip().startswith("$$"):
                problems.append(f"第 {i} 行不是合法映射条目: {raw.strip()}")
            continue
        block = m.group(2)
        enabled[block] = enabled.get(block, 0) + 1
        fact = BLOCK_FACTS.get(block)
        if fact is None:
            continue
        if m.group(3) and fact["states"] == 1:
            problems.append(f"第 {i} 行给 {block} 写了属性，但该方块只有 1 个状态（无属性）")
        if fact["light"]:
            problems.append(f"第 {i} 行借了会发光的方块 {block}（light={fact['light']}）")

    # 禁止：枚举里没有组的方块被借出（白吃原版状态、零收益）
    for block in sorted(enabled):
        fact = BLOCK_FACTS.get(block, {})
        if fact.get("note", "").startswith("枚举无组"):
            problems.append(f"{block}: CE auto_state 枚举里没有对应组，不应释放（已启用 {enabled[block]} 条）")

    # 禁止：某个方块的全部状态都被占用，同时还被当成映射目标 → 它无法再被表示
    targets = collect_targets(lines)
    for block in sorted(targets):
        fact = BLOCK_FACTS.get(block)
        if not fact:
            continue
        if enabled.get(block, 0) >= fact["states"]:
            problems.append(f"{block}: 全部 {fact['states']} 个状态都被借走，"
                            f"但它同时是映射目标 → 原版该方块会丢失显示")

    # 补丁是否已经落地
    for block, lhs, rhs in entries:
        if find_existing(lines, lhs, rhs) is None:
            problems.append(f"缺补丁行: {lhs}: {rhs}")

    total_free = report_pools(lines, cache, target)
    print()
    if problems:
        print(f"校验失败（{len(problems)} 项）：")
        for p in problems[:40]:
            print("  -", p)
        return 1
    print(f"校验通过：YAML 可解析、无非法组、无池被占满且目标未被抢、补丁行齐全；"
          f"可用空位 {total_free}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="CraftEngine mappings.yml chorus_plant 池补丁")
    ap.add_argument("--mappings", type=Path, default=DEFAULT_MAPPINGS)
    ap.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    ap.add_argument("--backup-dir", type=Path, default=DEFAULT_BACKUP_DIR)
    ap.add_argument("--profile", choices=sorted(PROFILE_DEFAULT_BLOCKS), default="balanced")
    ap.add_argument("--blocks", default=None,
                    help="逗号分隔，覆盖 profile 的方块选择（目前只有 chorus_plant 合法）")
    ap.add_argument("--target", type=int, default=162,
                    help="真实外观需求数，用来算还缺多少")
    ap.add_argument("--force", action="store_true",
                    help="即使同一状态已被映射到别的目标也强行写入（默认拒绝）")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--apply", action="store_true")
    mode.add_argument("--verify", action="store_true")
    mode.add_argument("--revert-blocks", default=None,
                      help="逗号分隔的方块名（或 @legacy-coral）→ 把已启用的行恢复成注释")
    args = ap.parse_args(argv)

    if not args.mappings.exists():
        raise SystemExit(f"找不到 mappings.yml: {args.mappings}")
    cache = load_cache(args.cache)

    if args.revert_blocks:
        want = [b.strip() for b in args.revert_blocks.split(",") if b.strip()]
        blocks = set(legacy_coral_blocks()) if "@legacy-coral" in want else set(want)
        res = revert_blocks(args.mappings, args.backup_dir, blocks)
        print(f"已恢复注释 {res['reverted']} 行（{', '.join(sorted(blocks))}）")
        print(f"备份: {res['backup']}")
        report_pools(read_lines(args.mappings), cache, args.target)
        return 0

    blocks = None if args.blocks is None else [b.strip() for b in args.blocks.split(",") if b.strip()]
    entries = build_entries(args.profile, blocks)

    print(f"profile={args.profile} 池={blocks or PROFILE_DEFAULT_BLOCKS[args.profile]} "
          f"目标启用条目={len(entries)}")
    if args.check:
        report_pools(read_lines(args.mappings), cache, args.target)
        return 0

    if args.apply:
        stats = apply_patch(args.mappings, args.backup_dir, entries, force=args.force)
        print(f"已应用：新增 {stats['inserted']} 行、取消注释 {stats['uncommented']} 行、"
              f"已存在 {stats['already']} 行")
        print(f"备份: {stats['backup']}")
        report_pools(read_lines(args.mappings), cache, args.target)
        return 0

    return verify(args.mappings, cache, args.target, entries)


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""结构 NBT 的座椅剥离：把 `is_seat` 命中的调色板方块显式映射成 `minecraft:air`。

## 为什么必须做这一步

用户明确**不发布座椅**（`chair` / `taburet` / `bar_stool`：模型来自 bclib，本包不释放
它的父模型，注册了也渲染不出来）。但上游结构模板的 palette 里仍然引用它们。

jigsaw 装配时，调色板里找不到定义的方块会被**静默跳过**：不抛异常、不刷日志，玩家看到的
是一座缺了椅子的建筑。`tools/verify_structures.py` 会把这类条目判成「未注册调色板方块」，
所以不处理的话构建门禁必然红。

## 为什么是「映射成 air」而不是「删掉 blocks 条目」

  * 坐标仍被占用，不会让别的方块（或空气以外的残留）顶到这个位置上；
  * `blocks` 列表长度与顺序不变，**调色板索引不需要重排** —— 这是「不误伤其它调色板条目」
    的前提：只要某个 palette 条目不是座椅，它的 id/properties 逐字段保持原样。

`air` 不带方块状态，所以座椅条目上的 `properties`（`facing`、`waterlogged` …）必须一并删除：
留着会让解析器认为这是一个带属性的方块。

## 幂等

没有命中座椅时返回**原树对象**（而不是重建一份），调用方据此跳过写文件：重复运行逐字节一致。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from bnlib import nbt

# 被移除的方块替换成什么。用 `minecraft:air` 而不是本包命名空间：本包不注册 air。
SEAT_REPLACEMENT_ID = "minecraft:air"


def is_unpublished(name: str) -> bool:
    """这个上游方块本包是否**不发布**（座椅/书架/酿造台/门/箱子… 一切已删类别）。

    结构 NBT 的 palette 里凡是引用了它的条目都必须显式换成 air —— 否则 jigsaw 装配时
    会静默跳过，玩家看到的是一座缺了东西的建筑，`verify_structures` 也会判红。
    """
    from bnlib.publication import is_removed_block
    return is_removed_block(f"betternether:{name}")

# 上游结构 NBT 用小写键（`id` / `properties`），原版 structure 格式用 `Name` / `Properties`。
# 两种都认，不按文件名猜。
ID_KEYS = ("id", "Name")
PROPERTY_KEYS = ("properties", "Properties")


def empty_stats() -> dict[str, Any]:
    """一份空的统计，字段固定，便于调用方累加。"""
    return {"palette_entries": 0, "placements": 0, "by_block": {}, "templates": 0,
            "dropped_entity_data": 0, "dropped_entities": 0}


def _read_id(fields: dict) -> tuple[str, str] | None:
    """取调色板条目的方块 id，返回 (键名, id)；两个键名都认。"""
    for key in ID_KEYS:
        node = fields.get(key)
        if isinstance(node, tuple) and node[0] == nbt.TAG_STRING:
            return key, node[1]
    return None


def strip_palette_seats(tree: Any, is_seat) -> tuple[Any, dict[str, Any]]:
    """把 palette 里的座椅条目改成 `minecraft:air` 并删除 properties。

    返回 `(树, 统计)`。统计里：

      * `palette_entries` —— 被替换的调色板条目数（`by_block` 给出每种座椅的明细）；
      * `placements`      —— 引用这些条目的方块放置数，也就是真正从建筑里消失的方块数。

    没有命中时原样返回入参（`is` 同一对象），调用方可以据此避免无谓重写。
    """
    tag, root = tree
    if tag != nbt.TAG_COMPOUND:
        return tree, empty_stats()

    palette_node = root.get("palette")
    if palette_node is None:
        if root.get("palettes") is not None:
            # 多调色板格式里 `blocks[].state` 的高位用于选 palette，语义与单一 palette 不同。
            # 本仓库的模板都是单一 palette；真的遇到多调色板时宁可失败，也不猜着改。
            raise SystemExit(
                "结构模板使用了多调色板（palettes），本转换只支持单一 palette；"
                "请先确认该模板的座椅语义，再扩展 strip_palette_seats。"
            )
        return tree, empty_stats()

    if palette_node[0] != nbt.TAG_LIST:
        raise SystemExit(f"palette 不是 TAG_List（实际 tag={palette_node[0]}），拒绝改写。")
    palette_elem_tag, entries = palette_node[1]

    replaced: dict[int, str] = {}
    by_block: dict[str, int] = {}
    rewritten_entries: list[Any] = []
    for index, entry in enumerate(entries):
        entry_tag, fields = entry
        found = _read_id(fields) if entry_tag == nbt.TAG_COMPOUND else None
        if found is None:
            rewritten_entries.append(entry)
            continue
        id_key, ident = found
        local = ident.split(":", 1)[1] if ":" in ident else ident
        if not is_seat(local):
            rewritten_entries.append(entry)
            continue
        # 只动这一个条目：删掉 properties，把 id 换成 air；其它键与其它条目逐字段保持原样。
        new_fields = {key: value for key, value in fields.items() if key not in PROPERTY_KEYS}
        new_fields[id_key] = (nbt.TAG_STRING, SEAT_REPLACEMENT_ID)
        rewritten_entries.append((entry_tag, new_fields))
        replaced[index] = ident
        by_block[ident] = by_block.get(ident, 0) + 1

    if not replaced:
        # 调色板没有被替换，但 `blocks[].nbt` 里仍可能引用已删方块（熔炉/抽屉柜…），
        # 那同样会让 jigsaw 装配时报错，所以仍要走一遍实体数据清理。
        bare_root = dict(root)
        dropped = _strip_removed_entity_data(bare_root, is_seat)
        if not dropped:
            return tree, empty_stats()
        stats = empty_stats()
        stats["dropped_entity_data"] = dropped
        stats["templates"] = 1
        return (tag, bare_root), stats

    placements = _count_placements(root.get("blocks"), replaced)
    new_root = dict(root)
    new_root["palette"] = (nbt.TAG_LIST, (palette_elem_tag, rewritten_entries))
    dropped_entities = _strip_removed_entity_data(new_root, is_seat)
    return (tag, new_root), {
        "palette_entries": len(replaced),
        "placements": placements,
        "by_block": by_block,
        "templates": 1,
        "dropped_entity_data": dropped_entities,
    }


def _strip_removed_entity_data(root: dict, is_removed) -> int:
    """删掉引用已删方块的**方块实体数据**（`blocks[].nbt`）。

    `blocks[].nbt` 是方块实体（熔炉/箱子/抽屉柜…）的内容。调色板条目已经换成 air，
    但这块数据还留着 `id: betternether:forge` —— jigsaw 装配到这里时会照着它去创建
    一个不存在的方块实体，服务端直接报错。所以引用已删方块的 nbt 必须一并抹掉。

    返回被清掉的条目数，供报告核对。
    """
    blocks_node = root.get("blocks")
    if blocks_node is None or blocks_node[0] != nbt.TAG_LIST:
        return 0
    elem_tag, items = blocks_node[1]
    dropped = 0
    new_items: list[Any] = []
    for item in items:
        item_tag, fields = item
        if item_tag != nbt.TAG_COMPOUND:
            new_items.append(item)
            continue
        entity = fields.get("nbt")
        if not isinstance(entity, tuple) or entity[0] != nbt.TAG_COMPOUND:
            new_items.append(item)
            continue
        found = _read_id(entity[1])
        if found is None:
            new_items.append(item)
            continue
        ident = found[1]
        local = ident.split(":", 1)[1] if ":" in ident else ident
        if not is_removed(local):
            new_items.append(item)
            continue
        # 只摘掉 nbt 这个键，其余字段（pos/state）保持原样 —— 那一格已经是空气了。
        kept = {key: value for key, value in fields.items() if key != "nbt"}
        new_items.append((item_tag, kept))
        dropped += 1
    if not dropped:
        return 0
    root["blocks"] = (nbt.TAG_LIST, (elem_tag, new_items))
    return dropped


def _count_placements(blocks_node: Any, replaced: dict[int, str]) -> int:
    """统计有多少个方块放置引用了被替换的调色板条目。"""
    if blocks_node is None:
        return 0
    if blocks_node[0] != nbt.TAG_LIST:
        return 0
    _elem_tag, items = blocks_node[1]
    placements = 0
    for item in items:
        item_tag, fields = item
        if item_tag != nbt.TAG_COMPOUND:
            continue
        state = fields.get("state")
        if isinstance(state, tuple) and state[0] == nbt.TAG_INT and state[1] in replaced:
            placements += 1
    return placements


def merge_stats(total: dict[str, Any], part: dict[str, Any]) -> dict[str, Any]:
    """把单个模板的统计累加进总计。"""
    total["palette_entries"] += part["palette_entries"]
    total["placements"] += part["placements"]
    total["templates"] += part["templates"]
    # 两个实体清理计数此前没被累加 —— 于是抽取路径清掉多少实体数据从来不进报告。
    total["dropped_entity_data"] = (
        total.get("dropped_entity_data", 0) + part.get("dropped_entity_data", 0))
    total["dropped_entities"] = (
        total.get("dropped_entities", 0) + part.get("dropped_entities", 0))
    for ident, count in part["by_block"].items():
        total["by_block"][ident] = total["by_block"].get(ident, 0) + count
    return total


def strip_source_fragments(root, is_seat) -> dict[str, Any]:
    """把一棵目录树下的所有结构 NBT 就地剥离座椅，返回汇总统计。

    只重写真的命中的文件；命中的文件用 `nbt.write_file` 重新序列化（gzip mtime=0，稳定）。
    """
    root = Path(root)
    if not root.is_dir():
        raise SystemExit(f"找不到结构目录：{root}")

    totals = empty_stats()
    files: list[dict[str, Any]] = []
    for path in sorted(root.rglob("*.nbt")):
        root_name, root_tag, payload = nbt.read_file(path)
        tree, stats = strip_palette_seats((root_tag, payload), is_seat)
        if stats["templates"] == 0:
            continue
        nbt.write_file(path, root_name, tree[0], tree[1])
        merge_stats(totals, stats)
        files.append({
            "file": str(path.relative_to(root)).replace("\\", "/"),
            "palette_entries": stats["palette_entries"],
            "placements": stats["placements"],
        })
    totals["files"] = files
    return totals


#: 结构调色板里引用、但本包**只有家具形态、没有方块形态**的 id。
#:
#: 这三个方块在 2026-10-29「家具化解禁」时从 `REMOVED_BLOCK_IDS` 移出，改由
#: `bnlib/furniture.py` 以 `furniture_item` 发布 —— 于是它们作为**物品**存在，方块 id
#: 在游戏里根本不存在。但结构 NBT 的调色板是按**方块**引用的（三个 id 合计 512 处），
#: jigsaw 装配时会静默跳过这些格子，建筑出现空洞且日志无痕迹。
#:
#: 处理方式与座椅/酿造台一致：替换成 air。宁可少一件贴墙摆设，也不留一个永不装配的引用 ——
#: 「宁缺不错」是本项目对上游复刻的一贯取舍（见 publication.py 的 REMOVED_BLOCK_IDS 注释）。
STRUCTURE_UNAVAILABLE_BLOCKS: frozenset[str] = frozenset({
    "betternether:cincinnasite_fire_bowl",
    "betternether:cincinnasite_pedestal",
    "betternether:cincinnasite_pot",
})


def _rewrite_palette(palette_node: Any) -> tuple[Any, dict[int, str], dict[str, int]]:
    """改写一条 palette：只换 id、只留替换目标认得的属性，其余条目逐字段原样保留。

    返回 `(新 palette 节点, {条目索引: 原 id}, {原 id: 条目数})`。索引**不重排**，所以
    `blocks[].state` 不需要任何映射修正 —— 这是「不误伤其它调色板条目」的前提。
    """
    from bnlib.publication import filter_properties, is_removed_block, replacement_id

    if palette_node[0] != nbt.TAG_LIST:
        raise ValueError("palette 必须是 TAG_List")
    element_tag, entries = palette_node[1]
    rewritten: list[Any] = []
    replaced: dict[int, str] = {}
    by_block: dict[str, int] = {}
    for index, entry in enumerate(entries):
        entry_tag, fields = entry
        found = _read_id(fields) if entry_tag == nbt.TAG_COMPOUND else None
        if found is None:
            rewritten.append(entry)
            continue
        unavailable = found[1] in STRUCTURE_UNAVAILABLE_BLOCKS
        if not unavailable and not is_removed_block(found[1]):
            rewritten.append(entry)
            continue
        key, ident = found
        # 只有家具形态的那批直接变 air：`replacement_id` 是按「已删方块」的族规则推算的，
        # 对这些从未被判定为已删的 id 给不出有意义的目标。
        target = "minecraft:air" if unavailable else replacement_id(ident)
        updated = dict(fields)
        updated[key] = (nbt.TAG_STRING, target)
        for property_key in PROPERTY_KEYS:
            node = updated.get(property_key)
            if node is None:
                continue
            if target == "minecraft:air" or node[0] != nbt.TAG_COMPOUND:
                # air 不带状态；非 compound 的属性节点没有可过滤的键，直接丢弃。
                updated.pop(property_key, None)
                continue
            allowed = filter_properties(ident, node[1].keys())
            kept = {name: value for name, value in node[1].items() if name in allowed}
            if kept:
                updated[property_key] = (node[0], kept)
            else:
                updated.pop(property_key, None)
        rewritten.append((entry_tag, updated))
        replaced[index] = ident
        by_block[ident] = by_block.get(ident, 0) + 1
    return (nbt.TAG_LIST, (element_tag, rewritten)), replaced, by_block


def _state_bits(sizes: list[int]) -> int:
    """多调色板里 `blocks[].state` 的条目索引位宽（原版 `StructureTemplate` 用 `ceillog2`）。"""
    largest = max(sizes) if sizes else 1
    bits = 0
    while (1 << bits) < largest:
        bits += 1
    return bits


def _count_multi_palette_placements(blocks_node: Any,
                                   replaced: list[dict[int, str]],
                                   sizes: list[int]) -> int:
    """多调色板下引用被替换条目的放置数。

    原版打包方式：`state = 调色板下标 << bits | 条目下标`，`bits` 由**最大**调色板条数决定。
    下标越界的条目直接跳过（宁可少数，不去猜一个文件的私有编码）。
    """
    if blocks_node is None or blocks_node[0] != nbt.TAG_LIST:
        return 0
    bits = _state_bits(sizes)
    mask = (1 << bits) - 1
    placements = 0
    for item in blocks_node[1][1]:
        item_tag, fields = item
        if item_tag != nbt.TAG_COMPOUND:
            continue
        state = fields.get("state")
        if not isinstance(state, tuple) or state[0] != nbt.TAG_INT:
            continue
        value = int(state[1])
        entry, palette_index = value & mask, value >> bits
        if 0 <= palette_index < len(replaced) and entry in replaced[palette_index]:
            placements += 1
    return placements


def _replace_palette(tree: Any) -> tuple[Any, dict[str, Any]]:
    """发布阶段替换用户移除的方块；只改 palette id 与不合法的属性，索引与源文件不动。

    ## 多调色板

    `palettes` 是「若干条 palette 的列表」，`blocks[].state` 同时编码调色板下标与条目下标。
    本函数对**每一条** palette 走同一套改写，且不增删条目、不重排顺序，因此 state 里的两个
    下标都保持有效 —— 不需要、也不能去重算 state。单 palette（`palette`）是同一逻辑的特例。
    """
    tag, root = tree
    if tag != nbt.TAG_COMPOUND:
        return tree, empty_stats()
    if "palettes" in root:
        palettes = root["palettes"]
        if palettes[0] != nbt.TAG_LIST:
            raise ValueError("palettes 必须是 TAG_List")
        element_tag, items = palettes[1]
        rewritten: list[Any] = []
        replaced: list[dict[int, str]] = []
        sizes: list[int] = []
        by_block: dict[str, int] = {}
        for item in items:
            item_tag, fields = item
            palette = (item_tag, fields)
            new_palette, hit, counts = _rewrite_palette(palette)
            rewritten.append(new_palette)
            replaced.append(hit)
            sizes.append(len(new_palette[1][1]))
            for ident, count in counts.items():
                by_block[ident] = by_block.get(ident, 0) + count
        if not any(replaced):
            return tree, empty_stats()
        updated_root = dict(root)
        updated_root["palettes"] = (nbt.TAG_LIST, (element_tag, rewritten))
        return (tag, updated_root), {
            "palette_entries": sum(len(hit) for hit in replaced),
            "placements": _count_multi_palette_placements(root.get("blocks"), replaced, sizes),
            "by_block": by_block,
            "templates": 1,
        }

    palette = root.get("palette")
    if palette is None:
        return tree, empty_stats()
    new_palette, replaced, by_block = _rewrite_palette(palette)
    if not replaced:
        return tree, empty_stats()
    updated_root = dict(root)
    updated_root["palette"] = new_palette
    return (tag, updated_root), {
        "palette_entries": len(replaced),
        "placements": _count_placements(root.get("blocks"), replaced),
        "by_block": by_block,
        "templates": 1,
    }


#: 结构里出现、但提供它的方块/功能整族已移除的**实体** id。
#: `bclib:chair` 是座椅实体：座椅三族（chair/taburet/bar_stool）已从发布集合移除，
#: 调色板里那一格已被替换成 air，但 `entities[]` 里仍留着「在这一格生成一把椅子」的指令。
#: 原版对未知实体类型是静默丢弃、不会崩，但那是漏清理 —— 这里显式抹掉。
REMOVED_ENTITY_IDS: frozenset[str] = frozenset({"bclib:chair"})


def _strip_removed_entities(root: dict) -> int:
    """删掉 `entities[]` 里引用已移除实体的条目（当前只有 `bclib:chair`）。

    这些条目与调色板是**两条独立的残留**：调色板那一格已经变成 air，但实体列表还要求
    在原位生成一个座椅实体。返回被删的条目数，供报告核对。
    """
    node = root.get("entities")
    if node is None or node[0] != nbt.TAG_LIST:
        return 0
    elem_tag, items = node[1]
    dropped = 0
    kept_items: list[Any] = []
    for item in items:
        item_tag, fields = item
        if item_tag != nbt.TAG_COMPOUND:
            kept_items.append(item)
            continue
        inner = fields.get("nbt")
        if not isinstance(inner, tuple) or inner[0] != nbt.TAG_COMPOUND:
            kept_items.append(item)
            continue
        found = _read_id(inner[1])
        if found is None or found[1] not in REMOVED_ENTITY_IDS:
            kept_items.append(item)
            continue
        dropped += 1
    if not dropped:
        return 0
    root["entities"] = (nbt.TAG_LIST, (elem_tag, kept_items))
    return dropped


def replace_removed_palette_blocks(tree: Any) -> tuple[Any, dict[str, Any]]:
    """发布阶段：替换已移除方块的调色板条目，并清理**两条独立的实体残留**。

    为什么包一层：调色板替换与实体清理是两条互不相干的残留路径 ——
    即使调色板一个条目都没命中（`_replace_palette` 直接早退），`blocks[].nbt` 与
    `entities[]` 里仍可能引用已删的方块实体 / 座椅实体。早期版本只在调色板命中时才
    往下走，于是「palette 早已被清成 air、方块实体数据却留着」这种组合永远清不掉。
    """
    tag, root = tree
    if tag != nbt.TAG_COMPOUND:
        return tree, empty_stats()
    replaced_tree, stats = _replace_palette(tree)
    rtag, rroot = replaced_tree
    writable = dict(rroot)
    dropped_nbt = _strip_removed_entity_data(writable, is_unpublished)
    dropped_ents = _strip_removed_entities(writable)
    if dropped_nbt == 0 and dropped_ents == 0:
        return replaced_tree, stats
    merged = dict(stats)
    merged["dropped_entity_data"] = stats.get("dropped_entity_data", 0) + dropped_nbt
    merged["dropped_entities"] = stats.get("dropped_entities", 0) + dropped_ents
    merged["templates"] = max(stats.get("templates", 0), 1)
    return (rtag, writable), merged

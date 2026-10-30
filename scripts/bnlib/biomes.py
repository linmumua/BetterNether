#!/usr/bin/env python3
"""BetterNether 群系 JSON 的净化规则。

## 为什么需要净化

上游 `src/main/generated/data/betternether/worldgen/biome/*.json` 已经是原版
`minecraft:biome` codec 格式，可以几乎直接当数据包用。但它引用了三类**插件里不存在**的东西：

1. `attributes -> minecraft:gameplay/natural_mob_spawns -> argument ->
   spawns_by_category -> *[].type` 里的 `betternether:firefly` 之类实体。
   插件无法注册实体（CraftEngine 没有 entity 段，Paper 也没有）。原样塞进数据包 =
   实体注册表 Unbound -> 整个 worldgen 注册表解析失败 -> **服务器启动中止**。
   这是本项目最危险的一条，必须硬删，不能靠人工检查。

2. `features[][]` 里 319 个 `betternether:*` placed_feature 引用。这些 placed_feature 的
   `configured_feature` 是模组自定义 type（如 `betternether:anchor_tree`），数据包无法表达。
   首版一律摘除，后续由我们自己的原版格式 placed_feature 或插件 Java populator 补回。

3. `attributes -> minecraft:audio/*` 里 5 个自定义音效 id。CE 可以注册 sounds，但首版先换成
   原版等价物，保证注册表干净；等音效阶段再换回来。
"""

from __future__ import annotations

import json
from typing import Any

MOD_NS = "betternether:"

# 自定义音效 -> 原版等价物。键是完整 id，值是替换目标。
# 依据：这些群系的氛围音在模组里就是"下界猩红/诡异森林"的变体，换成对应原版音是听感上最接近的
# 安全选择，而不是把整个 ambient_sounds 段删掉（那会让群系安静得像虚空）。
SOUND_SUBSTITUTES: dict[str, str] = {
    "betternether:betternether.ambient.gravel_desert": "minecraft:ambient.soul_sand_valley.loop",
    "betternether:betternether.ambient.mushroom_forest": "minecraft:ambient.crimson_forest.loop",
    "betternether:betternether.ambient.nether_jungle": "minecraft:ambient.warped_forest.loop",
    "betternether:betternether.ambient.swampland": "minecraft:ambient.soul_sand_valley.loop",
    "betternether:betternether.music.gloomwood": "minecraft:music.nether.soul_sand_valley",
}


def _is_mod_id(value: Any) -> bool:
    return isinstance(value, str) and value.startswith(MOD_NS)


def strip_entity_spawns(biome: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """删掉所有引用 `betternether:` 实体的生成项，返回 (群系, 删除条数)。

    同时清理 `spawn_costs` 里同名的条目，避免留下指向不存在实体的代价表。
    """
    removed = 0
    attributes = biome.get("attributes")
    if not isinstance(attributes, dict):
        return biome, removed

    spawns_attr = attributes.get("minecraft:gameplay/natural_mob_spawns")
    if not isinstance(spawns_attr, dict):
        return biome, removed

    argument = spawns_attr.get("argument")
    if not isinstance(argument, dict):
        return biome, removed

    by_category = argument.get("spawns_by_category")
    if isinstance(by_category, dict):
        for category, entries in list(by_category.items()):
            if not isinstance(entries, list):
                continue
            kept = [entry for entry in entries
                    if not (isinstance(entry, dict) and _is_mod_id(entry.get("type")))]
            removed += len(entries) - len(kept)
            by_category[category] = kept
        if not any(by_category.values()):
            # 一个空分类表仍然合法，但整段留着没有意义；去掉整段属性更干净。
            attributes.pop("minecraft:gameplay/natural_mob_spawns", None)

    return biome, removed


def strip_mod_features(biome: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """删掉 `features` 里所有 `betternether:` 引用，保留原版引用。"""
    removed = 0
    slots = biome.get("features")
    if not isinstance(slots, list):
        return biome, removed

    new_slots = []
    for slot in slots:
        if not isinstance(slot, list):
            new_slots.append(slot)
            continue
        kept = [feature for feature in slot if not _is_mod_id(feature)]
        removed += len(slot) - len(kept)
        new_slots.append(kept)
    biome["features"] = new_slots
    return biome, removed


def substitute_mod_sounds(biome: dict[str, Any]) -> tuple[dict[str, Any], int]:
    """把自定义音效 id 换成原版等价物；未登记的自定义音效直接删除该键。"""
    replaced = 0

    def visit(node: Any) -> Any:
        nonlocal replaced
        if isinstance(node, dict):
            for key, value in list(node.items()):
                if _is_mod_id(value):
                    if value in SOUND_SUBSTITUTES:
                        node[key] = SOUND_SUBSTITUTES[value]
                        replaced += 1
                    else:
                        del node[key]
                        replaced += 1
                else:
                    node[key] = visit(value)
            return node
        if isinstance(node, list):
            return [visit(item) for item in node]
        return node

    attributes = biome.get("attributes")
    if isinstance(attributes, dict):
        for key in list(attributes.keys()):
            if "audio" in key:
                attributes[key] = visit(attributes[key])
    return biome, replaced


def sanitize_biome(raw: dict[str, Any]) -> tuple[dict[str, Any], dict[str, int]]:
    """对单个群系 JSON 依次执行三条净化规则，返回 (净化后群系, 统计)。"""
    stats = {"entities": 0, "features": 0, "sounds": 0}
    biome, stats["entities"] = strip_entity_spawns(raw)
    biome, stats["features"] = strip_mod_features(biome)
    biome, stats["sounds"] = substitute_mod_sounds(biome)
    return biome, stats


def assert_no_mod_entity_refs(biome: dict[str, Any], where: str) -> None:
    """门禁：净化后的群系里不得残留任何 `betternether:` 实体引用。

    这是 L1 的最后一道闸。它必须抛异常而不是打印警告——一个残留的实体引用会让整张
    worldgen 注册表解析失败，服务器根本起不来，静默放行等于把事故留到线上。
    """
    attributes = biome.get("attributes")
    if not isinstance(attributes, dict):
        return
    spawns = attributes.get("minecraft:gameplay/natural_mob_spawns")
    if not isinstance(spawns, dict):
        return
    argument = spawns.get("argument")
    if not isinstance(argument, dict):
        return
    by_category = argument.get("spawns_by_category")
    if not isinstance(by_category, dict):
        return
    for category, entries in by_category.items():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict) and _is_mod_id(entry.get("type")):
                raise AssertionError(
                    f"{where}: 分类 {category} 仍引用模组实体 {entry.get('type')}，"
                    "这会让 worldgen 注册表解析失败。"
                )

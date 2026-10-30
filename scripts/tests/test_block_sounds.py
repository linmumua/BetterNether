#!/usr/bin/env python3
"""音效产物：玻璃听起来要像玻璃，花草不能像挖石头。

这些测试编码的是意图，不是行为：

  * 用户反馈「玻璃声音是石头」。根因不是手写错表，而是 `SOUND_EVENTS` **没有覆盖**
    上游导出的每一个 SoundType —— 缺一条就掉进 netherrack 兜底，玩家听到的是石头音，
    而配置本身完全合法、构建也不报错。所以这里钉住「每个上游 SoundType 都有条目」，
    而不是逐条比对某几个方块（那只会在下一次新增方块时静默失守）。
  * 「是玻璃」的判据是上游**类名**（`GlassBlockTrait` 挂在这些类上），不是 id 后缀：
    按后缀猜的话 `quartz_glass_framed` 会被算进去、而任何改了名的玻璃都会被漏掉。
  * 五个事件必须都是原版 `sounds.json` 里真实存在的事件：像 CROP 这种族内事件不统一的，
    按 `<族>.<事件>` 拼会拼出 `block.crop.step` 这种不存在的 id，客户端只会静默没声音。

跑法：`python -m pytest scripts/tests/test_block_sounds.py -q`
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bnlib import blocks, props  # noqa: E402

NAMESPACE = "betternether"
UPSTREAM = SCRIPTS.parent / "upstream/BetterNether"
EVENTS = ("break", "step", "place", "hit", "fall")
# 上游用这三个 SoundType 的方块在原版里都是花草/作物（草、蕨、根、菌、作物），
# 声音绝不该是石头。这里只用来挑选待核查的方块集合。
PLANT_SOUND_TYPES = frozenset({"GRASS", "CROP", "FUNGUS"})


@pytest.fixture(scope="module")
def upstream_props() -> dict:
    values = props.load_props(UPSTREAM)
    assert values, "缺少上游属性时不能把兜底音效当成已验证结果"
    return values


def sounds_of(configs: dict[str, dict], name: str) -> dict[str, str]:
    ident = f"{NAMESPACE}:{name}"
    assert ident in configs, f"{ident} 没有生成配置"
    return configs[ident]["settings"]["sounds"]


def family(sounds: dict[str, str]) -> set[str]:
    """一个方块用到的音效族（`minecraft:block.glass.break` -> `minecraft:block.glass`）。"""
    return {value.rsplit(".", 1)[0] for value in sounds.values()}


# ---------------------------------------------------------------- 家族对齐


def test_glass_uses_the_glass_family(block_manifest, block_configs) -> None:
    """玻璃类全部用原版 glass 家族（上游把 SoundType 抄成了 STONE / IRON）。"""
    glass = [entry for entry in block_manifest if entry["class"] in blocks.GLASS_CLASSES]
    assert len(glass) >= 20, f"玻璃类集合太小，断言可能没真的在检查：{len(glass)}"
    for entry in glass:
        name = entry["id"].split(":", 1)[1]
        assert family(sounds_of(block_configs, name)) == {"minecraft:block.glass"}, (
            f"{name}（{entry['class']}）不是玻璃音效：{sounds_of(block_configs, name)}")


def test_wood_uses_the_wood_family(block_configs) -> None:
    """木材按上游 SoundType 分到木音效（原木/树皮用 stem 或 nether_wood，都不是石头）。"""
    for name, expected in (("willow_log", "minecraft:block.stem"),
                           ("anchor_tree_bark", "minecraft:block.stem"),
                           ("willow_planks", "minecraft:block.nether_wood"),
                           ("gloomwood_barrel", "minecraft:block.nether_wood")):
        assert family(sounds_of(block_configs, name)) == {expected}, name


def test_stone_block_is_not_glass(block_configs) -> None:
    """反例：石头/黑曜石系仍用它们自己的音效，没有被玻璃规则误伤。"""
    assert family(sounds_of(block_configs, "obsidian_bricks")) == {"minecraft:block.stone"}
    assert family(sounds_of(block_configs, "basalt_bricks")) == {"minecraft:block.basalt"}
    assert family(sounds_of(block_configs, "cincinnasite_block")) == {"minecraft:block.metal"}


def test_plants_no_longer_sound_like_stone(block_configs, upstream_props) -> None:
    """花草/作物不再掉进 netherrack 兜底（那正是「花草木头都是石头声」的成因）。"""
    plants = [name for name, p in upstream_props.items()
              if p.sound in PLANT_SOUND_TYPES
              and f"{NAMESPACE}:{name.split(':', 1)[1]}" in block_configs]
    assert plants
    for ident in plants:
        name = ident.split(":", 1)[1]
        actual = family(sounds_of(block_configs, name))
        assert "minecraft:block.netherrack" not in actual, (
            f"{name} 掉进了 netherrack 兜底（上游 SoundType={upstream_props[ident].sound}）")


# ---------------------------------------------------------------- 表本身


def test_every_upstream_sound_type_has_a_mapping(block_manifest, upstream_props) -> None:
    """发布集合里出现的每个 SoundType 都必须有条目 —— 少一条就会静默变石头声。"""
    dirty = "path:block.netherrack.break"  # 上游属性文件里的脏值，落到 netherrack 是有意的
    missing = sorted({
        upstream_props[entry["id"]].sound for entry in block_manifest
        if upstream_props[entry["id"]].sound not in blocks.SOUND_EVENTS
        and upstream_props[entry["id"]].sound not in blocks.SOUND_EVENT_OVERRIDES
        and upstream_props[entry["id"]].sound != dirty
    })
    assert missing == [], f"这些上游 SoundType 没有音效映射，会掉进石头音兜底：{missing}"


def test_crop_family_uses_real_vanilla_events(block_configs, upstream_props) -> None:
    """CROP 的事件不属于同一个族：放置是 `item.crop.plant`，踩踏/落地走草地音。"""
    crops = [ident.split(":", 1)[1] for ident, p in upstream_props.items()
             if p.sound == "CROP" and p.cls not in blocks.GLASS_CLASSES]
    assert crops
    sample = [name for name in crops if f"{NAMESPACE}:{name}" in block_configs]
    assert sample, "没有任何 CROP 方块被发布，这条断言测不到东西"
    for name in sample:
        actual = sounds_of(block_configs, name)
        assert actual["place"] == "minecraft:item.crop.plant", name
        assert actual["step"] == "minecraft:block.grass.step", name
        assert actual["fall"] == "minecraft:block.grass.fall", name
        assert actual["break"] == "minecraft:block.crop.break", name
        # 不能出现「族名 + 事件」拼出来的假事件。
        assert "minecraft:block.crop.step" not in actual.values(), name


def test_every_block_declares_the_five_events(block_configs) -> None:
    """CE 的 `settings.sounds` 少一个事件，那个动作就静默无声。"""
    for ident, config in block_configs.items():
        sounds = config["settings"]["sounds"]
        assert tuple(sounds) == EVENTS, f"{ident} 的事件不齐：{sounds}"


def test_no_sound_references_the_removed_custom_namespace(block_configs) -> None:
    """自定义音效已从包里移除：任何一条 `betternether:<event>` 都是「配置在、音频不在」。"""
    offenders = {
        ident: sounds for ident, config in block_configs.items()
        for sounds in [config["settings"]["sounds"]]
        if any(value.startswith(f"{NAMESPACE}:") for value in sounds.values())
    }
    assert offenders == {}, f"这些方块的音效指向了已删除的自定义音效：{offenders}"


def test_sound_family_prefers_the_class_over_the_id_suffix(upstream_props) -> None:
    """判据是类名：`quartz_glass_framed` 的 SoundType 被抄成 IRON，仍按玻璃发声。"""
    framed = upstream_props[f"{NAMESPACE}:quartz_glass_framed"]
    assert framed.sound == "IRON", "上游值变了：请重新确认这条规则的依据"
    assert blocks.sound_family(framed) == blocks.GLASS_SOUND_FAMILY
    # 反例：不在玻璃类里的金属块，仍按金属发声。
    metal = upstream_props[f"{NAMESPACE}:cincinnasite_block"]
    assert blocks.sound_family(metal) == metal.sound == "IRON"

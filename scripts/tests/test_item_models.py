"""物品图标必须指向**真的存在、且贴图槽位闭合**的模型。

## 两类缺陷，都是「配置写着有外观、客户端渲染成缺失材质」

1. **已有模型却又让 CE 生成一份**：矿物产物物品原来写 `texture: betternether:item/<name>`。
   CE 的 `texture:` 是「单张纹理 → 自动生成同名模型」；而 `assets.py` 已经把上游
   `models/item/<name>.json`（`parent: item/generated` + `layer0`）原样释放到**同一个 path**。
   两边撞车：CE 会对已存在的模型文件报错/覆盖掉上游那份。改用 `model.path` 只引用不生成。

2. **把模板模型当图标**：`betternether:block/stalagnate_trunk` 的 `elements` 六个面写的是
   `#side`/`#top`，它自己的 `textures` 只声明了 `particle: "#side"` —— side/top 由子模型
   （`stalagnate_stem_1`、`stalagnate_middle_*`）补。直接引用它 = 槽位解析不到 = 缺失材质。
   上游真值在 `items/stalagnate_stem.json`：`stalagnate_trunk` 这个方块在上游和
   `stalagnate_stem` 是同一个物品（`StalagnateMaterial` 里 trunk 只加方块侧 trait，
   stem 的 `buildModel` 明写 `externalModelDelegatedItem(block/stalagnate_stem_1)`）。

本文件的断言都只读上游文件与模块函数，不需要先生成资源包。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks, ore_drops  # noqa: E402

UPSTREAM = ROOT / "upstream/BetterNether"
TRUNK_TEMPLATE = "betternether:block/stalagnate_trunk"


def _require_upstream() -> None:
    if not UPSTREAM.is_dir():
        pytest.skip(f"上游源码未就位：{UPSTREAM}")


def _resolver():
    return lambda ref: blocks.model_file_of(UPSTREAM, ref)


def _closed(ref: str) -> bool:
    return blocks.model_slots_closed(ref, _resolver())


def _ore_items() -> dict:
    _require_upstream()
    ore_items, _ = ore_drops.items_sections(UPSTREAM)
    return yaml.safe_load("items:\n" + "".join(ore_items))["items"]


# ------------------------------------------------------------------ 矿物产物物品


def test_ore_drop_items_reference_the_released_item_models():
    """4 个矿物物品必须引用已释放的上游模型，而不是让 CE 再生成一份同名模型。"""
    items = _ore_items()
    assert set(items) == {f"betternether:{name}" for name in ore_drops.ITEMS}
    for item_id, (_material, texture) in ore_drops.ITEMS.items():
        body = items[f"betternether:{item_id}"]
        assert "texture" not in body, f"{item_id}: 仍在用 texture:（会与已释放模型撞名）"
        path = (body.get("model") or {}).get("path")
        assert path == f"betternether:item/{texture}", f"{item_id}: 图标模型是 {path}"
        # items 段永不生成模型：只引用。
        assert "generation" not in (body.get("model") or {}), item_id
        assert blocks.model_file_of(UPSTREAM, path) is not None, (
            f"{item_id}: {path} 在上游不存在，assets 不会释放它")


def test_ore_drop_item_models_are_slot_closed():
    """图标模型的每个 `#slot` 都必须解析得到贴图（否则客户端是缺失材质）。"""
    for item_id, (_material, texture) in ore_drops.ITEMS.items():
        ref = f"betternether:item/{texture}"
        assert _closed(ref), f"{item_id}: {ref} 槽位不闭合"


# ------------------------------------------------------------------ 模板模型当图标


def test_stalagnate_trunk_icon_comes_from_the_shared_stem_item():
    """`stalagnate_trunk` 的图标取上游同一物品（stem）的模型，且必须是闭合模型。"""
    _require_upstream()
    ref = blocks.item_model_ref(UPSTREAM, "stalagnate_trunk")
    assert ref == "betternether:block/stalagnate_stem_1", (
        f"图标模型应来自 items/stalagnate_stem.json 的委托目标，实际是 {ref}")
    assert _closed(ref), f"{ref} 槽位不闭合"


def test_stalagnate_trunk_template_model_cannot_be_used_as_an_icon():
    """反面钉死：blockstate 的模板模型 `block/stalagnate_trunk` 不是闭合模型。

    它只声明 `particle: "#side"`，`side`/`top` 由子模型补 —— 这正是「直接引用它会缺材质」
    的原因。闭合判据必须把它判成 False，否则这条规则形同虚设。
    """
    _require_upstream()
    path = blocks.model_file_of(UPSTREAM, TRUNK_TEMPLATE)
    assert path is not None, "模板模型文件不见了，测试失去意义"
    assert not _closed(TRUNK_TEMPLATE)
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["textures"] == {"particle": "#side"}, "模板结构变了，请重新核对这条断言"
    faces = {face["texture"] for element in payload["elements"]
             for face in element["faces"].values()}
    assert faces == {"#top", "#side"}
    # 而它的子模型补上了这两槽 —— 所以 item 那句委托是正确解法。
    assert _closed("betternether:block/stalagnate_stem_1")


def test_every_upstream_item_model_is_slot_closed():
    """上游全部 item 模型都闭合 —— 物品图标只要照抄 `items/` 的委托目标就不会缺材质。"""
    _require_upstream()
    total = 0
    for tree in blocks.UPSTREAM_ASSET_TREES:
        directory = UPSTREAM / tree / "models/item"
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("*.json")):
            total += 1
            ref = f"betternether:item/{path.stem}"
            assert _closed(ref), f"{ref} 槽位不闭合"
    assert total > 0, "没有扫到任何上游 item 模型"

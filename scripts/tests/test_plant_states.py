"""世界生成带属性放置的植物，必须在 CE 配置里真的声明这些属性。

为什么要测：放置端按 `id + properties` 取状态（`CraftEngineLookup.lookup(id, Map)`）。
只要配置里没声明 `top`/`shape`/`facing`/`age`，CE 那边这个方块就只有一种内部状态，
带属性查不到 → 退回默认态 → 「芦苇没有顶、巨菇塌成一格、藤蔓挂反」。

本文件的测试全部在临时目录里用**手写的 blockstate JSON** 跑编译，
不依赖真实上游，也不写任何仓库内文件。它们锁住四件事：

1. 属性类型/取值/默认值来自 blockstate，不是猜的；
2. 每个外观都写自己的 `auto_state` + `model.path`（真实状态 + 模型），
   不再有 `transparent: true` / `entity_renderer` 的展示实体路线；
3. 朝向按 vanilla 的 `x`/`y` 原样写进 `model.x/.y`（与上游变体同义）；
4. 上游同一状态列了多个随机模型时，`models:` 权重列表**全部保留**。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks  # noqa: E402

UPSTREAM = ROOT / "upstream/BetterNether"


def _upstream_blockstates() -> Path:
    path = UPSTREAM / "src/main/resources/assets/betternether/blockstates"
    if not path.is_dir():
        pytest.skip(f"上游源码未就位：{path}")
    return path


def _show(yaml_text: str) -> dict:
    """把生成的片段套进一个最小 YAML 文档后解析（片段本身不是完整文档）。

    `state:`/`states:` 段的缩进是给 `blocks:` 用的，这里按同样的缩进包一层。
    """
    return yaml.safe_load("blocks:\n  demo:test:\n" + yaml_text)


def _states(yaml_text: str) -> dict:
    return _show(yaml_text)["blocks"]["demo:test"]["states"]


def _write(tmp_path: Path, files: dict[str, str]) -> tuple[Path, Path]:
    """写出 {相对路径: 内容}，返回 (blockstates 目录, models 目录)。"""
    blockstates = tmp_path / "blockstates"
    models = tmp_path / "models/block"
    blockstates.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for name, payload in files.items():
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(payload, encoding="utf-8")
    return blockstates, models


def _slots(spec: dict) -> str:
    """把 spec 渲染成 `states:` 文本：每个外观一个无碰撞池槽位 + 自己的模型。"""
    allocator = blocks.PoolAllocator()

    def take() -> str:
        return allocator.take_from(blocks.NO_COLLISION_POOLS, "betternether:demo")

    return blocks.pooled_states_yaml(spec, take)


def _axes(appearance: dict) -> list[tuple[int, int]]:
    """一个外观里所有模型的 (x, y) 旋转对（上游同一状态可能有多个随机备选）。"""
    entries = appearance.get("models") or ([appearance["model"]]
                                           if isinstance(appearance.get("model"), dict) else [])
    return [(entry.get("x", 0), entry.get("y", 0)) for entry in entries]


def _model_exists(*stems: str):
    allowed = set(stems)
    return lambda stem: stem in allowed


def test_boolean_property_compiles_to_two_slot_backed_appearances(tmp_path):
    """`top=false/true` 的两段芦苇：两个外观、两条变体、同一个透明状态。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_reeds.json": json.dumps({"variants": {
            "top=false": {"model": "betternether:block/demo_reeds_false"},
            "top=true": {"model": "betternether:block/demo_reeds_true"},
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_reeds")
    spec = blocks.compile_plant_states(
        "demo_reeds", raw, ("top",), _model_exists("demo_reeds_false", "demo_reeds_true"))
    text = _slots(spec)
    states = _states(text)

    assert states["properties"]["top"] == {
        "type": "string", "default": "false", "values": ["false", "true"]}
    # 取值是字符串 "false"/"true"，配置里必须带引号，否则 YAML 会解析成布尔
    assert 'default: "false"' in text
    assert set(states["appearances"]) == {"top_false", "top_true"}
    assert states["variants"] == {
        "top=false": {"appearance": "top_false"},
        "top=true": {"appearance": "top_true"},
    }
    for name, appearance in states["appearances"].items():
        # 每个外观各占一个真实无碰撞池槽位，且写着自己的模型
        assert appearance["auto_state"] in blocks.NO_COLLISION_POOLS, name
        assert appearance["model"]["path"].startswith(f"{blocks.NAMESPACE}:block/"), name
        assert "transparent" not in appearance, name
        assert "entity_renderer" not in appearance, name


def test_each_appearance_takes_its_own_slot_from_the_shared_allocator(tmp_path):
    """不同方块的每个外观各消耗一个槽位，且都落在无碰撞池里。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_a.json": json.dumps({"variants": {
            "age=0": {"model": "betternether:block/demo_a_0"},
            "age=1": {"model": "betternether:block/demo_a_1"},
        }}),
        "blockstates/demo_b.json": json.dumps({"variants": {
            "facing=up": {"model": "betternether:block/demo_b"},
            "facing=down": {"model": "betternether:block/demo_b", "x": 180},
        }}),
    })
    raw_a = blocks.load_variant_states((blockstates,), "demo_a")
    raw_b = blocks.load_variant_states((blockstates,), "demo_b")
    spec_a = blocks.compile_plant_states("demo_a", raw_a, ("age",), _model_exists("demo_a_0", "demo_a_1"))
    spec_b = blocks.compile_plant_states("demo_b", raw_b, ("facing",), _model_exists("demo_b"))

    allocator = blocks.PoolAllocator()
    taken: list[str] = []

    def render(spec: dict) -> dict:
        def take() -> str:
            pool = allocator.take_from(blocks.NO_COLLISION_POOLS, "betternether:demo")
            taken.append(pool)
            return pool

        return _states(blocks.pooled_states_yaml(spec, take))

    states_a = render(spec_a)
    states_b = render(spec_b)
    # 每个外观各占一格（同池也不复用同一个原版状态，CE 里一个状态只能绑一个模型）
    assert len(taken) == len(states_a["appearances"]) + len(states_b["appearances"]) == 4
    assert len(set(taken)) == 1, "同类池应当从同一个首选池里连续取"
    for states in (states_a, states_b):
        for name, appearance in states["appearances"].items():
            assert appearance["auto_state"] in blocks.NO_COLLISION_POOLS, name


def test_direction_property_keeps_model_orientation_through_model_axes(tmp_path):
    """六向疣种：模型不变，朝向按上游变体的 `x`/`y` 写进 `model.x/.y`。

    改造前这些朝向靠 item_display 的 `rotation` 表达（展示实体）；改成真实状态后
    直接沿用 vanilla 变体的 `x`/`y`，与 CE `state.model.x/.y` 的语义一致。
    """
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_seed.json": json.dumps({"variants": {
            "facing=up": [{"model": "betternether:block/demo_seed_01"},
                          {"model": "betternether:block/demo_seed_02"}],
            "facing=down": [{"model": "betternether:block/demo_seed_01", "x": 180}],
            "facing=north": [{"model": "betternether:block/demo_seed_01", "x": 90}],
            "facing=south": [{"model": "betternether:block/demo_seed_01", "x": 90, "y": 180}],
            "facing=east": [{"model": "betternether:block/demo_seed_01", "x": 90, "y": 90}],
            "facing=west": [{"model": "betternether:block/demo_seed_01", "x": 90, "y": 270}],
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_seed")
    spec = blocks.compile_plant_states(
        "demo_seed", raw, ("facing",), _model_exists("demo_seed_01", "demo_seed_02"))
    states = _states(_slots(spec))

    assert states["properties"]["facing"]["type"] == "direction"
    rotations = {name: _axes(appearance)
                 for name, appearance in states["appearances"].items()}
    assert rotations == {
        "facing_up": [(0, 0), (0, 0)],            # 上游列了两个随机备选模型，都保留
        "facing_down": [(180, 0)],
        "facing_north": [(90, 0)],
        "facing_south": [(90, 180)],
        "facing_east": [(90, 90)],
        "facing_west": [(90, 270)],
    }
    # 同一个模型可以出现在多个外观里：每个外观各占一个真实状态，互不影响
    assert all("entity_renderer" not in appearance
               for appearance in states["appearances"].values())


def test_string_shape_needs_two_properties_to_be_addressable(tmp_path):
    """鬼火藤那类：只声明 shape+spent 时，`shape=top` 单独写会命中别人，必须成对写。"""
    variants = {}
    for shape, model in (("bottom", "demo_vine_t4"), ("middle", "demo_vine_t3"),
                         ("top", "demo_vine_head")):
        for spent in ("false", "true"):
            for rotation in ("east", "north", "south", "west"):
                if shape == "top" and spent == "true":
                    model_name = "demo_vine_t5"
                else:
                    model_name = model
                key = f"rotation={rotation},shape={shape},spent={spent}"
                variants[key] = {"model": f"betternether:block/{model_name}"}
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_vine.json": json.dumps({"variants": variants}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_vine")
    spec = blocks.compile_plant_states(
        "demo_vine", raw, ("shape", "spent"),
        _model_exists("demo_vine_t4", "demo_vine_t3", "demo_vine_head", "demo_vine_t5"))
    states = _states(_slots(spec))

    keys = set(states["variants"])
    assert keys == {"shape=bottom", "shape=middle", "shape=top,spent=false", "shape=top,spent=true"}
    # `rotation` 是 CE 硬编码的 int 属性名，上游是字符串取值 —— 只能不声明，且必须写进报告
    assert "rotation" in states["properties"] or any(
        "rotation" in reason for reason in spec["dropped"])
    assert "rotation" not in states["properties"]


def test_no_hidden_carrier_items_are_emitted():
    """改造后不再需要 `_ce_*` 隐藏载体 item —— 它们是 entity_renderer 的模型托盘。

    真正的断言在产物侧（`test_transparent_models.test_no_ce_carrier_items`）：
    这里只钉住「生成器不再有产出这类 item 的函数」，防止有人把它加回来。
    """
    assert not hasattr(blocks, "plant_state_item_yaml")
    assert not hasattr(blocks, "plant_state_item_id")


def test_appearance_is_dropped_for_missing_model_with_reason(tmp_path):
    """上游缺模型文件时不写一个渲染不出来的外观，并在报告里点名。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_mushroom.json": json.dumps({"variants": {
            "shape=top": {"model": "betternether:block/demo_mushroom_top"},
            "shape=bottom": {"model": "betternether:block/demo_mushroom_bottom"},
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_mushroom")
    spec = blocks.compile_plant_states(
        "demo_mushroom", raw, ("shape",), _model_exists("demo_mushroom_top"))
    states = _states(_slots(spec))

    assert list(states["appearances"]) == ["shape_top"]
    assert set(states["variants"]) == {"shape=top"}
    assert any("demo_mushroom_bottom" in reason for reason in spec["dropped"])


def test_multipart_blockstate_is_not_forced_into_variants(tmp_path):
    """multipart（栅栏/墙那种）不是「属性→一个模型」的映射，应返回 None 而不是硬编。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_fence.json": json.dumps({"multipart": [
            {"apply": {"model": "betternether:block/demo_fence_post"}}]}),
    })
    assert blocks.load_variant_states((blockstates,), "demo_fence") is None


def test_property_types_are_string_tag_compatible():
    """属性类型必须是 StringTag 兼容的 `string`/方向枚举。

    放置端 `CraftEngineLookup.lookup(id, Map<String,String>)` 用 `tag.putString` 建 CompoundTag，
    而 CE 的 `BooleanProperty.unpack` 只认 ByteTag、`IntegerProperty.unpack` 只认 NumericTag
    （javap `Property.unpack` 核对）。声明成 `boolean`/`int` 会让 `getBlockState` 抛异常、
    退回默认态 —— 属性等于白声明。这条测试就是防止有人「顺手」改回去。
    """
    assert blocks._property_type(["false", "true"])[0] == "string"
    assert blocks._property_type(["0", "1", "2", "3"])[0] == "string"
    assert blocks._property_type(["north", "south"])[0] == "horizontal_direction"
    assert blocks._property_type(["north", "up", "down"])[0] == "direction"
    assert blocks._property_type(["small", "big_top_center"])[0] == "string"
    # 形如布尔/数字的字符串取值必须加引号，否则 YAML 解析成 bool/int
    assert blocks._yaml_scalar("false", "string") == '"false"'
    assert blocks._yaml_scalar("0", "string") == '"0"'
    assert blocks._yaml_scalar("top", "string") == "top"


def test_pooled_states_keep_every_random_alternative(tmp_path):
    """上游同一状态列多个随机模型时，满格方块必须用 `models:` 权重列表全保留。

    这条是「随机备选不是可随意丢项」的钉子：CE 的方块外观支持
    `models: [{path, weight}]`（官方 flame_cane.yml 的写法），vanilla 语义是按方块位置
    随机挑一个 —— 所以 `willow_log` 的 `_2`、`willow_trunk` 的 `t0/t1` 都不该丢。
    """
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_bark.json": json.dumps({"variants": {
            "axis=y": [{"model": "betternether:block/demo_bark"},
                       {"model": "betternether:block/demo_bark_2"}],
            "axis=z": [{"model": "betternether:block/demo_bark", "x": 90},
                       {"model": "betternether:block/demo_bark_2", "x": 90}],
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_bark")
    spec = blocks.compile_plant_states(
        "demo_bark", raw, ("axis",), _model_exists("demo_bark", "demo_bark_2"))
    states = _states(blocks.pooled_states_yaml(spec, lambda: blocks.STATE_POOL_SOLID))

    axis_y = states["appearances"]["axis_y"]["models"]
    assert [entry["path"] for entry in axis_y] == [
        "betternether:block/demo_bark", "betternether:block/demo_bark_2"]
    assert all(entry["weight"] == 1 for entry in axis_y)
    # 随机备选被保留时不该在报告里说「丢掉了」
    assert not any("随机备选模型未使用" in reason for reason in spec["dropped"])


def test_hardcoded_property_is_renamed_instead_of_dropped(tmp_path):
    """`rotation` 被 CE 硬编码成 int：改名 `vine_dir` 保住朝向语义，而不是放弃方向。"""
    variants = {}
    for rotation, y in (("north", None), ("east", 90), ("south", 180), ("west", 270)):
        for shape in ("bottom", "middle", "top"):
            entry = {"model": f"betternether:block/demo_vine_{shape}"}
            if y and shape == "top":
                entry["y"] = y
            variants[f"rotation={rotation},shape={shape},spent=false"] = entry
    # 上游还有 `rotation=random`（由 datagen 表示「每个位置随机挑一个朝向」）
    variants["rotation=random,shape=bottom,spent=false"] = {
        "model": "betternether:block/demo_vine_bottom"}
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_vine.json": json.dumps({"variants": variants}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_vine")
    renamed = {
        **raw,
        "states": [
            ({(blocks.PROPERTY_RENAMES.get(prop, prop)): value
              for prop, value in values.items()}, models)
            for values, models in raw["states"]
        ],
    }
    spec = blocks.compile_plant_states(
        "demo_vine", renamed, ("shape", "spent", "vine_dir"),
        _model_exists("demo_vine_bottom", "demo_vine_middle", "demo_vine_top"))
    states = _states(_slots(spec))

    assert states["properties"]["vine_dir"]["type"] == "string"
    assert "rotation" not in states["properties"], "rotation 被 CE 硬编码成 int，绝不能声明"
    assert "vine_dir" in blocks.PROPERTY_RENAMES.values()
    # 四个朝向都真的体现在模型的 `y` 上（顶段模型才随朝向变）
    rotations = {name: _axes(appearance)
                 for name, appearance in states["appearances"].items()
                 if name.startswith("shape_top")}
    assert rotations == {
        "shape_top_spent_false_vine_dir_north": [(0, 0)],
        "shape_top_spent_false_vine_dir_east": [(0, 90)],
        "shape_top_spent_false_vine_dir_south": [(0, 180)],
        "shape_top_spent_false_vine_dir_west": [(0, 270)],
    }


def test_broken_model_texture_reference_is_repaired():
    """上游坏模型按同族合法材质改写引用，产出一个可落盘的修复模型（不是删掉外观）。"""
    payload = {
        "textures": {
            "particle": "blocks/mushroom_red_new",
            "texture": "blocks/mushroom_red_new",
            "inside": "block/mushroom_block_inside",
        },
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16]}],
    }
    entry = blocks.build_model_repair("wall_mushrooms_red_1", lambda _stem: payload)

    assert entry is not None
    assert entry["path"] == "block/wall_mushrooms_red_1_ce"
    assert entry["textures"]["texture"] == "minecraft:block/red_mushroom_block"
    assert entry["textures"]["particle"] == "minecraft:block/red_mushroom_block"
    assert entry["textures"]["inside"] == "minecraft:block/mushroom_block_inside"
    assert entry["elements"] == payload["elements"], "几何本体必须原样搬过来"
    # 没有修复规则的模型不伪造修复
    assert blocks.build_model_repair("agave", lambda _stem: payload) is None


def test_every_upstream_state_gets_its_own_variant_key(tmp_path):
    """上游每个状态都必须能被变体键命中，不能靠「回退默认外观」蒙过去。

    真实反例（`gloomwisp_vine`）：上游 `rotation=random` 与 `rotation=north` 用的是同一份
    模型，压缩后的键只写了 `vine_dir=north`，于是 `vine_dir=random` 放置时会回退到
    **默认外观**（连 `shape` 都跟着错）。这条测试锁住「同组内取值不同但外观相同的成员
    也要有显式键」。
    """
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_vine.json": json.dumps({"variants": {
            "rotation=north,shape=top": [{"model": "betternether:block/vine_head"}],
            "rotation=random,shape=top": [{"model": "betternether:block/vine_head"},
                                          {"model": "betternether:block/vine_head", "y": 90}],
            "rotation=east,shape=top": [{"model": "betternether:block/vine_head", "y": 90}],
            "rotation=north,shape=middle": [{"model": "betternether:block/vine_middle"}],
            "rotation=random,shape=middle": [{"model": "betternether:block/vine_middle"}],
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_vine")
    renamed = {
        **raw,
        "states": [
            ({(blocks.PROPERTY_RENAMES.get(prop, prop)): value
              for prop, value in values.items()}, models)
            for values, models in raw["states"]
        ],
    }
    spec = blocks.compile_plant_states(
        "demo_vine", renamed, ("shape", "vine_dir"),
        _model_exists("vine_head", "vine_middle"))
    states = _states(_slots(spec))

    keys = set(states["variants"])
    assert "shape=top,vine_dir=random" in keys, f"random 没被覆盖：{sorted(keys)}"
    assert "shape=top,vine_dir=north" in keys
    # `vine_dir=random` 上游列了两个模型（本体 + 转 90 度的副本），
    # 所以它现在有**自己**的外观（两个加权模型都要保留）；关键是这个键必须存在，
    # 不能因为「外观与 north 不同」就退化成回退默认外观。
    random_app = states["appearances"][states["variants"]["shape=top,vine_dir=random"]["appearance"]]
    north_app = states["appearances"][states["variants"]["shape=top,vine_dir=north"]["appearance"]]
    assert len(random_app["models"]) == 2
    assert isinstance(north_app["model"], dict), "单模型写单数 model:"
    assert random_app["auto_state"] in blocks.NO_COLLISION_POOLS
    assert north_app["auto_state"] in blocks.NO_COLLISION_POOLS
    # shape=middle 只有一个模型 → 压缩成 `shape=middle`，把两个 vine_dir 都盖住
    assert states["variants"]["shape=middle"]["appearance"].startswith("shape_middle")


def test_multi_model_appearance_keeps_every_model_on_one_real_state(tmp_path):
    """上游同一状态列了多个模型（客户端按位置随机挑）时全部保留，挂在**一个**真实状态上。

    真实语料是 `gloomwisp_vine`：`shape=top,spent=false` 的每个朝向都列了 4 个相位模型
    （`vine_dir=random` 更是 16 个 = 4 模型 × 4 朝向），其余外观只差一个朝向。
    CE 的 `models: [{path, weight}]` 只在**非透明**外观里被解析（`transparent: true`
    会短路模型读取），所以这些外观必须各占一个真实视觉状态 —— 这正是本次改造的方向。
    """
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_wisp.json": json.dumps({"variants": {
            "shape=top,spent=false,vine_dir=random": [
                {"model": f"betternether:block/{stem}", "y": y}
                for y in (0, 90, 180, 270)
                for stem in ("vine_head", "vine_t0", "vine_t1", "vine_t2")
            ],
            "shape=top,spent=false,vine_dir=north": [
                {"model": "betternether:block/vine_head"}],
            "shape=bottom,spent=false,vine_dir=north": [
                {"model": "betternether:block/vine_t4"}],
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_wisp")
    renamed = {
        **raw,
        "states": [
            ({(blocks.PROPERTY_RENAMES.get(prop, prop)): value
              for prop, value in values.items()}, models)
            for values, models in raw["states"]
        ],
    }
    spec = blocks.compile_plant_states(
        "demo_wisp", renamed, ("shape", "spent", "vine_dir"),
        _model_exists("vine_head", "vine_t0", "vine_t1", "vine_t2", "vine_t4"))
    states = _states(_slots(spec))

    weighted = {name: appearance for name, appearance in states["appearances"].items()
                if len(appearance.get("models", [])) > 1}
    assert len(weighted) == 1, "只有 `vine_dir=random` 列了多个模型"
    appearance = next(iter(weighted.values()))
    assert len(appearance["models"]) == 16, "上游 16 个加权模型必须全保留"
    assert {entry["weight"] for entry in appearance["models"]} == {1}
    assert len({(entry["path"], entry.get("y")) for entry in appearance["models"]}) == 16
    assert appearance["auto_state"] in blocks.NO_COLLISION_POOLS
    assert "entity_renderer" not in appearance
    assert "transparent" not in appearance

    # 槽位不够时由分配器 fail-fast（不在这里做单模型近似）
    allocator = blocks.PoolAllocator()
    allocator.left = {pool: 0 for pool in allocator.left}
    with pytest.raises(SystemExit) as failure:
        allocator.take_from(blocks.NO_COLLISION_POOLS, "betternether:demo_wisp")
    assert "拒绝退回 entity_renderer" in str(failure.value)


def test_every_configured_block_avoids_hardcoded_property_names():
    """白名单本身要自洽：不能声明被 CE 硬编码成别的类型的属性名。"""
    assert blocks.STATE_PROPS, "白名单不能为空，否则这个功能等于没接上"
    assert blocks.UNSAFE_PROPERTY_NAMES, "必须显式列出不能声明的属性名"
    for name, props in blocks.STATE_PROPS.items():
        assert props, f"{name} 没有声明任何属性"
        for prop in props:
            assert prop not in blocks.UNSAFE_PROPERTY_NAMES, (
                f"{name}: {prop} 被 CE 硬编码成别的类型（"
                f"{blocks.UNSAFE_PROPERTY_NAMES[prop]}），声明了会抛异常")


def test_spike_size_compiles_to_eight_transparent_appearances(tmp_path):
    """石笋/钟乳石 8 档粗细：8 个外观、8 个模型，全部共用同一个透明状态。"""
    variants = {f"size={i}": {"model": f"betternether:block/demo_spike_{i}"}
                for i in range(8)}
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_spike.json": json.dumps({"variants": variants}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_spike")
    spec = blocks.compile_plant_states(
        "demo_spike", raw, ("size",),
        _model_exists(*[f"demo_spike_{i}" for i in range(8)]))
    states = _states(_slots(spec))

    assert states["properties"]["size"] == {
        "type": "string", "default": "0",
        "values": [str(i) for i in range(8)]}
    assert len(states["appearances"]) == 8
    assert set(states["variants"]) == {f"size={i}" for i in range(8)}
    for name, appearance in states["appearances"].items():
        assert appearance["auto_state"] in blocks.NO_COLLISION_POOLS, name


def test_axis_log_uses_real_states_with_model_rotation(tmp_path):
    """原木的 axis 必须印到真实视觉状态上，朝向靠 model 的 x/y（与 vanilla 变体同义）。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_log.json": json.dumps({"variants": {
            "axis=y": {"model": "betternether:block/demo_log"},
            "axis=z": {"model": "betternether:block/demo_log", "x": 90},
            "axis=x": {"model": "betternether:block/demo_log", "x": 90, "y": 90},
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_log")
    spec = blocks.compile_plant_states(
        "demo_log", raw, ("axis",), _model_exists("demo_log"))
    taken: list[str] = []

    def take_pool() -> str:
        taken.append(f"slot{len(taken)}")
        return blocks.STATE_POOL_SOLID

    text = blocks.pooled_states_yaml(spec, take_pool)
    states = _states(text)

    assert states["properties"]["axis"] == {"type": "axis", "default": "y"}
    # 每个外观各占一个真实状态（满格方块的碰撞箱来自所借状态）
    assert len(taken) == len(states["appearances"]) == 3
    models = {name: appearance["model"] for name, appearance in states["appearances"].items()}
    assert models["axis_y"] == {"path": "betternether:block/demo_log"}
    assert models["axis_z"] == {"path": "betternether:block/demo_log", "x": 90}
    assert models["axis_x"] == {
        "path": "betternether:block/demo_log", "x": 90, "y": 90}
    # 满格方块不走 entity_renderer，也不写 transparent
    for appearance in states["appearances"].values():
        assert "entity_renderer" not in appearance
        assert "transparent" not in appearance


def test_variant_that_omits_a_property_still_gets_a_key(tmp_path):
    """上游允许变体键省略属性：`natural=false` 不写 facing，也必须能单独定位。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_leaves.json": json.dumps({"variants": {
            "facing=north,natural=true": {"model": "betternether:block/demo_leaves_side"},
            "facing=up,natural=true": {"model": "betternether:block/demo_leaves_top"},
            "natural=false": {"model": "betternether:block/demo_leaves"},
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_leaves")
    spec = blocks.compile_plant_states(
        "demo_leaves", raw, ("facing", "natural"),
        _model_exists("demo_leaves_side", "demo_leaves_top", "demo_leaves"))
    states = _states(blocks.pooled_states_yaml(spec, lambda: blocks.STATE_POOL_SOLID))

    assert set(states["variants"]) == {"facing=north", "facing=up", "natural=false"}
    assert states["variants"]["natural=false"]["appearance"] == "natural_false"
    assert states["properties"]["natural"]["default"] == "true"


def test_unsafe_property_names_are_dropped_before_compiling(tmp_path):
    """被 CE 硬编码的属性名要在编译前剔除，且原因写进 dropped，而不是悄悄丢掉方向。"""
    blockstates, _ = _write(tmp_path, {
        "blockstates/demo_vine.json": json.dumps({"variants": {
            "rotation=east,shape=bottom": {"model": "betternether:block/demo_vine_bottom"},
            "rotation=north,shape=bottom": {"model": "betternether:block/demo_vine_bottom"},
        }}),
    })
    raw = blocks.load_variant_states((blockstates,), "demo_vine")
    declared = tuple(prop for prop in ("shape", "rotation")
                     if prop not in blocks.UNSAFE_PROPERTY_NAMES)
    spec = blocks.compile_plant_states(
        "demo_vine", raw, declared, _model_exists("demo_vine_bottom"))

    assert [prop for prop, *_ in spec["properties"]] == ["shape"]
    states = _states(_slots(spec))
    assert set(states["variants"]) == {"shape=bottom"}
    assert any("rotation" in reason for reason in spec["dropped"])


def test_real_nether_mycelium_declares_blue_with_two_upstream_models():
    """真实上游语料：`nether_mycelium` 的 `blue` 必须声明，两种菌丝各占一个满格状态。

    为什么：上游 `MushroomFirFeature.BLUE_MYCELIUM` 用
    `nether_mycelium[blue=true]` 放蘑菇冷杉脚下那层蓝色菌丝，
    `blockstates/nether_mycelium.json` 里 `blue=true` 指向另一个模型
    `nether_mycelium_blue`。这个属性既不在 surface_rules JSON 里、也不在 Java 类的
    名字里能被自动推断，只能写进 `STATE_PROPS`；漏了它 CE 只有一种内部状态，
    放置端带属性查询退回默认态 → 整片蓝菌丝显示成普通菌丝（本块唯一的视觉缺口）。
    """
    blockstates = _upstream_blockstates()
    models_dir = blockstates.parent / "models" / "block"
    raw = blocks.load_variant_states((blockstates,), "nether_mycelium")
    assert raw is not None, "上游 blockstate 缺失或不是 variants 形式"

    assert blocks.STATE_PROPS["nether_mycelium"] == ("blue",)
    spec = blocks.compile_plant_states(
        "nether_mycelium", raw, blocks.STATE_PROPS["nether_mycelium"],
        lambda stem: (models_dir / f"{stem}.json").is_file())
    assert spec["dropped"] == [], "两个上游模型都应解析到，不该有剔除记录"

    text = blocks.pooled_states_yaml(spec, lambda: blocks.STATE_POOL_SOLID)
    states = _states(text)

    # 默认值 = 上游 `registerDefaultState(... setValue(IS_BLUE, false))` 的真值；
    # 取值是字符串 "true"/"false"，配置里必须带引号，否则 YAML 会解析成布尔。
    assert states["properties"]["blue"] == {
        "type": "string", "default": "false", "values": ["false", "true"]}
    assert 'default: "false"' in text
    assert states["variants"] == {
        "blue=false": {"appearance": "blue_false"},
        "blue=true": {"appearance": "blue_true"},
    }

    # 两个外观分别指向上游那两个模型；四个 y 旋转副本原样保留（客户端按位置随机挑）
    appearances = states["appearances"]
    assert set(appearances) == {"blue_false", "blue_true"}
    for name, path in (("blue_false", "betternether:block/nether_mycelium"),
                       ("blue_true", "betternether:block/nether_mycelium_blue")):
        models = appearances[name]["models"]
        assert {entry["path"] for entry in models} == {path}
        assert {entry.get("y", 0) for entry in models} == {0, 90, 180, 270}

    # 满格方块借 solid 池，绝不落到植物的载体池，也不走 entity_renderer
    assert blocks.STATE_POOL_SOLID not in blocks.NO_COLLISION_POOLS
    assert blocks.STATE_POOL_SOLID not in blocks.PARTIAL_COLLISION_POOLS
    assert blocks.STATE_POOL_SOLID in blocks.FORBIDDEN_PLANT_POOLS
    assert blocks.STATE_POOL_SOLID not in blocks.VINE_STATE_POOLS
    for appearance in appearances.values():
        assert appearance["auto_state"] == blocks.STATE_POOL_SOLID
        assert "entity_renderer" not in appearance
        assert "transparent" not in appearance

"""`tools/verify_block_contracts.py` 的负例回归：门禁必须真的**拒绝**违规。

每个用例都构造一份迷你生成产物（只有被测方块），再让门禁去读它。真值仍然来自真实的
上游 `upstream/BetterNether`（`block_properties.json` / `mineable/*` / `climbable`），
所以测试不会把「门禁自己写死的期望值」当成上游事实。

刻意覆盖两类失败：
  * **违规必须判红**（挖掘门槛、方向覆盖、藤蔓标签、删除引用、透明实心外观各自一条）；
  * **规则缺证据必须判「未实现」**，不能悄悄算通过（植物支撑）。
"""

from __future__ import annotations

import dataclasses
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "tools"))

import verify_block_contracts as gate  # noqa: E402
from bnlib import props, support  # noqa: E402

UPSTREAM = ROOT / "upstream/BetterNether"
BLOCK_DIR = "craftengine/betternether/configuration/blocks"
ASSETS = "craftengine/betternether/resourcepack/assets/betternether"


# --------------------------------------------------------------------------- 夹具

def upstream_merges(name: str, **overrides) -> dict:
    """按上游真值拼出「合规的」settings.merges，供各用例在其上做单点破坏。"""
    ident = f"betternether:{name}"
    values = props.load_props(UPSTREAM)
    assert ident in values, f"上游没有 {ident}，测试前提不成立"
    prop = values[ident]
    families = props.load_mineable_tools(UPSTREAM).get(ident, frozenset())
    merges = {
        "hardness": prop.hardness,
        "require_correct_tools": prop.requires_tool,
        # 上游「不要工具」时门槛必须是 0（mineable 只说明「哪把工具更快」，
        # 玻璃就是这样：镐更快但空手也能挖掉），这是项目已定下的语义。
        "required_break_power": (props.load_break_power(UPSTREAM).get(ident, 0)
                                 if prop.requires_tool else 0),
        "correct_tools": ([f"minecraft:iron_{family}" for family in sorted(families)]
                          if prop.requires_tool else []),
        "tags": [f"minecraft:mineable/{family}" for family in sorted(families)],
    }
    merges.update(overrides)
    return merges


def write_pack(tmp_path: Path, blocks: dict, extra_files: dict[str, str] | None = None) -> Path:
    root = tmp_path / "bn"
    block_dir = root / BLOCK_DIR
    block_dir.mkdir(parents=True)
    (block_dir / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True, sort_keys=False),
        encoding="utf-8")
    for relative, content in (extra_files or {}).items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    return root


def write_model(root: Path, stem: str, texture: str, parent: str = "minecraft:block/cube_all") -> str:
    """在包里放一个真模型；返回 `model.path` 该写的值。"""
    model = root / ASSETS / "models/block" / f"{stem}.json"
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_text(
        '{"parent": "%s", "textures": {"all": "betternether:block/%s"}}'
        % (parent, texture), encoding="utf-8")
    return f"betternether:block/{stem}"


def write_texture(root: Path, stem: str, transparent_pixels: int = 0, total: int = 64,
                  alpha: int = 0) -> None:
    """写一张 8x8 贴图：`transparent_pixels` 个像素的 alpha 置 `alpha`，其余全不透明。"""
    from PIL import Image

    side = int(total ** 0.5)
    assert side * side == total
    image = Image.new("RGBA", (side, side), (200, 120, 80, 255))
    for index in range(transparent_pixels):
        image.putpixel((index % side, index // side), (10, 20, 30, alpha))
    target = root / ASSETS / "textures/block" / f"{stem}.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)


def run(capsys, root: Path, *args) -> tuple[int, str]:
    code = gate.main(["--generated", str(root), "--upstream", str(UPSTREAM), *args])
    return code, capsys.readouterr().out


# --------------------------------------------------------------------------- 基线

def test_compliant_pack_has_no_violation(tmp_path, capsys):
    """合规产物：只有「未实现规则」拦着，把 --allow-unimplemented 传进去就该绿。"""
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
            "state": {
                "auto_state": "solid",
                # `generation` 直接给贴图：不依赖模型 JSON 是否落盘，聚焦在校验逻辑本身
                "model": {"path": "betternether:block/stone",
                          "generation": {"parent": "minecraft:block/cube_all",
                                         "textures": {"all": "betternether:block/stone"}}},
            },
        },
    })
    write_texture(root, "stone")

    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[FAIL]" not in out
    assert "mining.hardness  已检查 1 项" in out
    assert "transparency.solid_appearance  已检查 1 项" in out


# --------------------------------------------------------------------------- 玻璃类不变量

def _glass_merges(**overrides) -> dict:
    """按「原版玻璃手感」拼 merges：硬度 0.3、空手可挖、镐仍然更快。"""
    merges = {
        "hardness": 0.3,
        "require_correct_tools": False,
        "required_break_power": 0,
        "correct_tools": [f"minecraft:iron_{family}"
                          for family in sorted(props.load_mineable_tools(UPSTREAM)
                                               .get("betternether:obsidian_glass", frozenset()))],
        "tags": ["minecraft:mineable/pickaxe"],
    }
    merges.update(overrides)
    return merges


def test_glass_aligned_to_vanilla_hand_feel_passes(tmp_path, capsys):
    """玻璃按原版手感（0.3、空手可挖）写，并且登记在豁免表里，就应该通过。

    这条与 `mining.hardness` 是**两套判据**：后者比对上游 destroyTime（此处 50），
    前者要求玻璃类统一落在「空手能挖」的规格上。两者同时成立的唯一办法就是登记豁免。
    """
    root = write_pack(tmp_path, {
        "betternether:obsidian_glass": {"settings": {"merges": _glass_merges()}},
    })

    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[FAIL]" not in out
    assert "mining.glass_alignment  已检查 1 项" in out


def test_glass_left_at_upstream_hardness_is_rejected(tmp_path, capsys):
    """玻璃没登记、仍按上游 destroyTime=50 与钻石镐门槛写 = 空手挖十几秒，必须判红。

    这正是「新增一种玻璃却忘记登记」会走到的路径：它**恰好**等于上游 destroyTime，
    所以 `mining.hardness` 不会报，只有这条类级不变量能抓住。
    """
    merges = upstream_merges("obsidian_glass")  # 上游原值：hardness 50、requiresTool True
    root = write_pack(tmp_path, {
        # 换一个未登记的 id，模拟「上游新增玻璃忘了加豁免」
        "betternether:blue_obsidian_glass": {"settings": {"merges": merges}},
    })
    # 让蓝玻璃也脱离豁免表：用 monkeypatch 更贴近「表里没有它」
    import tools.verify_block_contracts as gate  # noqa: PLC0415

    original = dict(gate.GLASS_MINING_ALIGNMENT)
    try:
        gate.GLASS_MINING_ALIGNMENT.pop("betternether:blue_obsidian_glass", None)
        code, out = run(capsys, root, "--allow-unimplemented")
    finally:
        gate.GLASS_MINING_ALIGNMENT.clear()
        gate.GLASS_MINING_ALIGNMENT.update(original)

    assert code != 0, out
    assert "mining.glass_alignment" in out
    assert "blue_obsidian_glass" in out


def test_registered_glass_with_a_tool_gate_is_rejected(tmp_path, capsys):
    """登记过的玻璃若又写回 `require_correct_tools: true`，同样判红 —— 豁免不等于放任。"""
    root = write_pack(tmp_path, {
        "betternether:obsidian_glass": {
            "settings": {"merges": _glass_merges(require_correct_tools=True,
                                                 required_break_power=4)},
        },
    })

    code, out = run(capsys, root, "--allow-unimplemented")
    assert code != 0, out
    assert "mining.glass_alignment" in out


# --------------------------------------------------------------------------- 挖掘门槛

def test_wood_that_requires_a_tool_is_rejected(tmp_path, capsys):
    """空手能挖的原木被写成「要求正确工具」= 玩家空手挖不掉，必须判红。"""
    merges = upstream_merges("anchor_tree_log", require_correct_tools=True,
                             correct_tools=["minecraft:iron_axe"])
    root = write_pack(tmp_path, {"betternether:anchor_tree_log": {"settings": {"merges": merges}}})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "mining.tool_gate" in out
    assert "betternether:anchor_tree_log" in out


def test_wrong_hardness_is_rejected(tmp_path, capsys):
    expected = props.load_props(UPSTREAM)["betternether:anchor_tree_planks"].hardness
    merges = upstream_merges("anchor_tree_planks", hardness=expected + 1.5)
    root = write_pack(tmp_path, {"betternether:anchor_tree_planks": {"settings": {"merges": merges}}})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "mining.hardness" in out


def test_mineable_tag_from_the_wrong_family_is_rejected(tmp_path, capsys):
    """玻璃上游是镐；写成斧标签会让斧头加速、镐不加速。"""
    merges = upstream_merges("quartz_glass", tags=["minecraft:mineable/axe"])
    root = write_pack(tmp_path, {"betternether:quartz_glass": {"settings": {"merges": merges}}})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "mining.mineable_tag" in out
    assert "minecraft:mineable/pickaxe" in out


# --------------------------------------------------------------------------- 方向覆盖

def _states(root: Path, axes_variants: dict) -> dict:
    """三轴家具：两个外观（竖直/水平），贴图都是不透明，透明规则不插手。"""
    for stem, texture in (("axis_vertical", "axis_vertical"), ("axis_horizontal", "axis_horizontal")):
        write_texture(root, texture)
    models = {"vertical": write_model(root, "axis_vertical", "axis_vertical"),
              "horizontal": write_model(root, "axis_horizontal", "axis_horizontal")}
    return {
        "properties": {"axis": {"type": "axis", "default": "y"}},
        "appearances": {
            name: {"auto_state": "solid", "model": {"path": path}}
            for name, path in models.items()
        },
        "variants": axes_variants,
    }


def test_state_without_any_variant_coverage_is_rejected(tmp_path, capsys):
    """只映射了 axis=x：y/z 会静默回退到第一个外观（=朝向丢失）。"""
    root = write_pack(tmp_path, {
        "betternether:anchor_tree_log": {
            "settings": {"merges": upstream_merges("anchor_tree_log")},
            "states": _states(tmp_path / "bn", {"axis=x": {"appearance": "horizontal"}}),
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "orientation.state_coverage" in out
    assert "axis=y" in out and "axis=z" in out


def test_full_variant_coverage_passes(tmp_path, capsys):
    root = write_pack(tmp_path, {
        "betternether:anchor_tree_log": {
            "settings": {"merges": upstream_merges("anchor_tree_log")},
            "states": _states(tmp_path / "bn", {
                "axis=x": {"appearance": "horizontal"},
                "axis=y": {"appearance": "vertical"},
                "axis=z": {"appearance": "horizontal"},
            }),
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] orientation.state_coverage" not in out
    assert code == 0, out


def test_variant_key_naming_undeclared_property_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {
        "betternether:anchor_tree_log": {
            "settings": {"merges": upstream_merges("anchor_tree_log")},
            "states": _states(tmp_path / "bn", {
                "axis=x": {"appearance": "horizontal"},
                "axis=y": {"appearance": "vertical"},
                "axis=z": {"appearance": "horizontal"},
                "facing=north": {"appearance": "missing_appearance"},
            }),
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "orientation.variant_property" in out
    assert "orientation.variant_appearance" in out


# --------------------------------------------------------------------------- 藤蔓可爬

def _soul_vein(merges: dict) -> dict:
    """soul_vein 上游还有一条可证实的支撑规则（灵魂沙），不写出会被 plant_support 判红；
    藤蔓用例只关心 climbable，所以把它一并写对，让违规来源唯一。"""
    return {"settings": {"merges": merges},
            "behavior": {"type": "bush_block", "blacklist": False, "stackable": False,
                         "delay": 0, "bottom_blocks": ["minecraft:soul_sand"]}}


def _climb_state(host: str, host_id: str | None = None) -> dict:
    """一个透明外观，宿主由参数决定（`transparent: true` 必须配 entity_renderer）。"""
    auto: dict = {"type": host}
    if host_id is not None:
        auto["id"] = host_id
    return {
        "auto_state": auto,
        "transparent": True,
        "entity_renderer": {"type": "item_display", "item": "betternether:soul_vein"},
    }


def _climb_state_real(host: str, host_id: str | None = None) -> dict:
    """**真实状态**载体（不再外挂展示实体）：植物改造之后的标准写法。

    `plant_states.no_entity_renderer` 之后，植物不能再靠 entity_renderer 出外观；
    只关心「载体是不是真可爬」的用例应该用这一种，否则违规来源就不唯一了。
    """
    auto: dict = {"type": host}
    if host_id is not None:
        auto["id"] = host_id
    return {
        "auto_state": auto,
        "model": {"path": "betternether:block/soul_vein",
                  "generation": {"parent": "minecraft:block/cross",
                                 "textures": {"cross": "betternether:block/soul_vein"}}},
    }


def _soul_vein_climbable(**state_overrides) -> dict:
    body = _soul_vein(upstream_merges(
        "soul_vein",
        tags=[*upstream_merges("soul_vein")["tags"], "minecraft:climbable"]))
    body["state"] = {**_climb_state_real("cave_vine", "climbable"), **state_overrides}
    return body


def test_vine_missing_climbable_tag_is_rejected(tmp_path, capsys):
    """上游把 soul_vein 放进 climbable 标签；漏写标签就爬不上去。"""
    root = write_pack(tmp_path, {
        "betternether:soul_vein": _soul_vein(upstream_merges("soul_vein")),
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "vine.climbable_missing" in out


def test_vine_with_climbable_tag_passes(tmp_path, capsys):
    root = write_pack(tmp_path, {"betternether:soul_vein": _soul_vein_climbable()})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] vine.climbable_missing" not in out
    assert "[FAIL] vine.climbable_host_missing" not in out
    assert code == 0, out


def test_climbable_tag_on_a_block_upstream_never_marked_is_rejected(tmp_path, capsys):
    """上游 climbable 标签里没有 gloomwisp_vine；声明了就是凭空给玩家一堵能爬的墙。"""
    merges = upstream_merges("gloomwisp_vine", tags=["minecraft:climbable"])
    root = write_pack(tmp_path, {"betternether:gloomwisp_vine": {"settings": {"merges": merges}}})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "vine.climbable_extra" in out


def test_climbable_tag_with_a_non_climbable_carrier_is_rejected(tmp_path, capsys):
    """反面核心：写了 tag 却借 sugar_cane 当载体 —— 玩家客户端不会起攀，必须判红。

    这正是「只查 tags」的假绿：服务端 tag 有了，客户端眼里那个方块仍是甘蔗。
    """
    body = _soul_vein(upstream_merges(
        "soul_vein",
        tags=[*upstream_merges("soul_vein")["tags"], "minecraft:climbable"]))
    body["state"] = _climb_state("sugar_cane", "transparent")
    root = write_pack(tmp_path, {"betternether:soul_vein": body})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "vine.climbable_host_missing" in out
    assert "sugar_cane" in out
    # 原有 tags 契约仍然成立（没缺 tag），违规只来自载体。
    assert "[FAIL] vine.climbable_missing" not in out


def test_climbable_block_without_any_carrier_is_rejected(tmp_path, capsys):
    """可爬方块没有任何 visual 载体时无法证明客户端读得到可爬方块，同样判红。"""
    root = write_pack(tmp_path, {
        "betternether:soul_vein": _soul_vein(upstream_merges(
            "soul_vein",
            tags=[*upstream_merges("soul_vein")["tags"], "minecraft:climbable"])),
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "vine.climbable_host_missing" in out


def test_non_climbable_block_borrowing_the_shared_climb_id_is_rejected(tmp_path, capsys):
    """不可爬的方块借了共享 id=climbable —— 整片植物会被顺带变成可爬。"""
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
            "state": _climb_state("cave_vine", "climbable"),
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "vine.climbable_host_misuse" in out


def test_non_climbable_block_may_use_other_legit_hosts(tmp_path, capsys):
    """不在上游 climbable 里的方块仍可借别的合法本体宿主（例如共用真实薄池）。

    本条只禁「可爬共享 id」，不把视觉池选择扩大成新的合规要求。
    """
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
            # 载体从 `weeping_vine` 换成 `twisting_vine`：本期 `bnlib.blocks.POOL_BUDGET`
            # 里 weeping_vine 的额度是 0，用它当夹具会先撞 `plant_states.state_slot_budget`
            # （被测点是「可爬共享 id」这条禁令，不该被额度抢戏）。twisting_vine 同样是
            # 真实薄池、同样不在可爬共享 id 上，本条断言的语义不变。
            "state": _climb_state("twisting_vine"),
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] vine.climbable_host_misuse" not in out
    assert "[FAIL] vine.climbable_host_missing" not in out
    assert code == 0, out


# --------------------------------------------------------------------------- 删除引用

def test_reference_to_a_removed_block_is_rejected(tmp_path, capsys):
    root = write_pack(
        tmp_path,
        {"betternether:cincinnasite_block": {"settings": {"merges": upstream_merges("cincinnasite_block")}}},
        {"craftengine/betternether/configuration/items/leftover.yml":
         "items:\n  leftover:\n    behavior:\n      type: block_item\n      block: betternether:anchor_tree_stairs\n"},
    )
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "removal.reference" in out
    assert "betternether:anchor_tree_stairs" in out


def test_build_reports_are_out_of_scope_for_runtime_references(tmp_path, capsys):
    """`reports/**` 不进 jar（processResources 排除），不能拿它当阻塞项。"""
    root = write_pack(
        tmp_path,
        {"betternether:cincinnasite_block": {"settings": {"merges": upstream_merges("cincinnasite_block")}}},
        {"reports/block-inventory.json": '{"removed": ["betternether:anchor_tree_stairs"]}'},
    )
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "removal.reference" in out and "已检查" in out
    assert "anchor_tree_stairs" not in out


# --------------------------------------------------------------------------- 透明实心外观

def _glass_like(root: Path, host: str, transparent_pixels: int = 32, alpha: int = 0) -> dict:
    write_texture(root, "bubble", transparent_pixels=transparent_pixels, alpha=alpha)
    return {
        "settings": {"merges": upstream_merges("quartz_glass")},
        "state": {
            "auto_state": host,
            "model": {"path": write_model(root, "bubble_glass", "bubble")},
        },
    }


def test_alpha_texture_on_opaque_host_is_rejected(tmp_path, capsys):
    """50% 全透明像素的贴图挂在 `solid`（不透明宿主）上 = 镂空处画成黑块。"""
    root = write_pack(tmp_path, {})
    write_pack_into = root / BLOCK_DIR / "test.yml"
    blocks = {"betternether:quartz_glass": _glass_like(root, "solid")}
    write_pack_into.write_text(yaml.safe_dump({"blocks": blocks}, allow_unicode=True),
                               encoding="utf-8")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] transparency.solid_appearance" in out
    assert "min_alpha=0" in out


def test_same_alpha_texture_on_cutout_host_passes(tmp_path, capsys):
    root = write_pack(tmp_path, {})
    blocks = {"betternether:quartz_glass": _glass_like(root, "tintable_leaves")}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] transparency.solid_appearance" not in out
    assert code == 0, out


def test_opaque_texture_on_opaque_host_passes(tmp_path, capsys):
    root = write_pack(tmp_path, {})
    blocks = {"betternether:quartz_glass": _glass_like(root, "solid", transparent_pixels=0)}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] transparency.solid_appearance" not in out
    assert code == 0, out


def test_transparent_without_entity_renderer_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {
        "betternether:black_apple": {
            "settings": {"merges": upstream_merges("black_apple")},
            "state": {
                "auto_state": {"type": "sugar_cane", "id": "transparent"},
                "transparent": True,
            },
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "transparency.missing_renderer" in out


def test_missing_texture_is_reported_instead_of_passing(tmp_path, capsys):
    """贴图不存在时不能按「不透明」放过 —— 那是把裂图当成合规。"""
    root = write_pack(tmp_path, {
        "betternether:quartz_glass": {
            "settings": {"merges": upstream_merges("quartz_glass")},
            "state": {"auto_state": "solid",
                      "model": {"path": "betternether:block/does_not_exist"}},
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "transparency.unresolved_texture" in out


def test_appearance_without_host_is_reported(tmp_path, capsys):
    root = write_pack(tmp_path, {
        "betternether:quartz_glass": {
            "settings": {"merges": upstream_merges("quartz_glass")},
            "state": {"model": {"path": "betternether:block/does_not_exist"}},
        },
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "transparency.host_unknown" in out


# --------------------------------------------------------------------------- 未实现规则

# --------------------------------------------------------------------------- 植物支撑

def _bush(bottom_blocks: list[str], **extra) -> dict:
    entry = {"type": "bush_block", "blacklist": False, "stackable": False, "delay": 0,
             "bottom_blocks": list(bottom_blocks)}
    entry.update(extra)
    return {"settings": {"merges": upstream_merges("agave")}, "behavior": entry}


def test_verified_support_rule_matching_product_passes(tmp_path, capsys):
    """上游 NetherSurvival.gravel() → CE bush_block 下方白名单 = 砾石。"""
    root = write_pack(tmp_path, {"betternether:agave": _bush(["minecraft:gravel"])})
    code, out = run(capsys, root)
    assert "[FAIL] plant_support.contract" not in out, out
    assert code == 0, out


def test_support_whitelist_that_disagrees_with_upstream_is_rejected(tmp_path, capsys):
    """白名单写错 = 植物活在本该死的地方 / 本该活的地方不活。"""
    root = write_pack(tmp_path, {"betternether:agave": _bush(["minecraft:stone"])})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.contract" in out
    assert "minecraft:gravel" in out


def test_support_rule_without_any_declared_behavior_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {
        "betternether:agave": {"settings": {"merges": upstream_merges("agave")}},
    })
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.contract" in out


def test_blocked_upstream_rule_fails_with_explicit_reason(tmp_path, capsys):
    """受阻规则不能静默跳过，也不能被 --allow-unimplemented 放过。

    具体哪个方块受阻随 `bnlib/support.py` 的证据进度变化，所以这里**动态**取一个
    当前受阻的方块；一个都没有时跳过（并说明原因），绝不写死某个方块的名字。
    """
    from bnlib import support

    rules = gate._support_rules(UPSTREAM)
    blocked = sorted(name for name, rule in rules.items() if rule.blocked)
    if not blocked:
        pytest.skip("当前 support.py 没有受阻规则，无法验证受阻分支")
    name = blocked[0]
    block_id = f"betternether:{name}"
    values = props.load_props(UPSTREAM)
    assert block_id in values, f"受阻方块 {block_id} 不在上游方块表里"

    root = write_pack(tmp_path, {block_id: {"settings": {"merges": upstream_merges(name)}}})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.blocked" in out
    assert block_id in out

    code_allowed, out_allowed = run(capsys, root, "--allow-unimplemented")
    assert code_allowed == 1, out_allowed
    assert "[FAIL] plant_support.blocked" in out_allowed


def test_support_declared_where_upstream_has_no_trait_is_rejected(tmp_path, capsys):
    """凭空加限制会让方块永远长不出来。"""
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
            "behavior": {"type": "bush_block", "blacklist": False,
                         "bottom_blocks": ["minecraft:stone"]},
        },
    })
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.unexpected" in out


def test_whitelist_mode_without_any_support_field_is_rejected(tmp_path, capsys):
    """`blacklist: false` 却一个白名单字段都没有 = 永远不放行。"""
    root = write_pack(tmp_path, {
        "betternether:agave": {
            "settings": {"merges": upstream_merges("agave")},
            "behavior": {"type": "bush_block", "blacklist": False, "delay": 0},
        },
    })
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.empty_whitelist" in out


def test_blacklist_mode_may_have_no_explicit_list(tmp_path, capsys):
    """`blacklist: true`（黑名单/任何坚固面）允许空列表。"""
    entry = {"type": "bush_block", "blacklist": True, "delay": 0}
    root = write_pack(tmp_path, {"betternether:agave": {
        "settings": {"merges": upstream_merges("agave")}, "behavior": entry}})
    _code, out = run(capsys, root)
    assert "[FAIL] plant_support.empty_whitelist" not in out, out


def test_facing_rule_without_facing_property_is_rejected(tmp_path, capsys):
    """贴面支撑只靠 `facing` 表达：没声明该属性，directional_attached_block 不会生效。"""
    from bnlib import support

    rules = gate._support_rules(UPSTREAM)
    facing = sorted(name for name, rule in rules.items()
                    if getattr(rule, "direction", None) == support.DIR_FACING
                    and not rule.blocked)
    assert facing, "上游没有可证实的贴面（facing）支撑规则，测试前提不成立"
    name = facing[0]
    root = write_pack(tmp_path, {f"betternether:{name}": {
        "settings": {"merges": upstream_merges(name)},
        "behavior": {"type": "directional_attached_block", "blacklist": False,
                     "attached_blocks": ["minecraft:nether_bricks"]},
    }})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.facing_missing" in out


def _bush_entry(bottom_blocks=("minecraft:gravel",)) -> dict:
    return {"type": "bush_block", "blacklist": False, "stackable": False, "delay": 0,
            "bottom_blocks": list(bottom_blocks)}


def test_support_behavior_inside_plural_behaviors_passes(tmp_path, capsys):
    """多行为方块写 `behaviors:` 列表：支撑行为在里面同样要能被读到并比对。"""
    root = write_pack(tmp_path, {"betternether:agave": {
        "settings": {"merges": upstream_merges("agave")},
        "behaviors": [_bush_entry(), {"type": "ageable_block", "max_age": 3}],
    }})
    code, out = run(capsys, root)
    assert "[FAIL] plant_support.contract" not in out, out
    assert "[FAIL] plant_support.unexpected" not in out, out
    assert code == 0, out


def test_plural_behaviors_still_trip_unexpected(tmp_path, capsys):
    """复数写法同样要抓「上游没有支撑 trait 却声明了支撑行为」。"""
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
            "behaviors": [{"type": "strippable_block", "stripped": "betternether:bone_block"},
                          _bush_entry(("minecraft:stone",))],
        },
    })
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.unexpected" in out


def test_duplicate_support_behavior_type_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {"betternether:agave": {
        "settings": {"merges": upstream_merges("agave")},
        "behaviors": [_bush_entry(), _bush_entry()],
    }})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.duplicate" in out


def test_behavior_and_behaviors_together_are_rejected(tmp_path, capsys):
    """两份声明同时存在时 CE 只会用其中一份，另一份静默失效 —— 必须报出来。"""
    root = write_pack(tmp_path, {"betternether:agave": {
        "settings": {"merges": upstream_merges("agave")},
        "behavior": _bush_entry(),
        "behaviors": [_bush_entry()],
    }})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.malformed" in out


def test_non_mapping_behavior_entry_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {"betternether:agave": {
        "settings": {"merges": upstream_merges("agave")},
        "behaviors": ["bush_block"],
    }})
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.malformed" in out


def test_support_whitelist_referencing_unpublished_block_is_rejected(tmp_path, capsys):
    """attached_blocks 同样要查：support.py 只在传了 available_ids 时才剔除。"""
    from bnlib import support

    rules = gate._support_rules(UPSTREAM)
    facing = sorted(name for name, rule in rules.items()
                    if getattr(rule, "direction", None) == support.DIR_FACING
                    and not rule.blocked)
    root = write_pack(tmp_path, {
        "betternether:agave": _bush(["minecraft:gravel", "betternether:not_published_here"]),
        f"betternether:{facing[0] if facing else 'wall_moss'}": {
            "settings": {"merges": upstream_merges(facing[0] if facing else "wall_moss")},
            "states": {"properties": {"facing": {"type": "horizontal_direction",
                                                 "default": "north"}},
                       "appearances": {}, "variants": {}},
            "behavior": {"type": "directional_attached_block", "blacklist": False,
                         "attached_blocks": ["betternether:not_published_here"]},
        },
    })
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] plant_support.dangling_block" in out
    assert "not_published_here" in out


def test_every_declared_rule_is_either_checked_or_unimplemented(tmp_path, capsys):
    """防止「规则被删掉但输出照样绿」：每条声明过的规则都要出现在报告里。"""
    root = write_pack(tmp_path, {
        "betternether:cincinnasite_block": {
            "settings": {"merges": upstream_merges("cincinnasite_block")},
        },
    })
    _code, out = run(capsys, root, "--allow-unimplemented")
    for rule in gate.RULE_DESCRIPTIONS:
        assert rule in out, f"报告里缺少规则 {rule}"


def test_single_semi_transparent_pixel_on_opaque_host_is_rejected(tmp_path, capsys):
    """判据是「任一 alpha < 255」：64 个像素里 1 个 @128 也必须算透明。"""
    root = write_pack(tmp_path, {})
    blocks = {"betternether:quartz_glass": _glass_like(root, "solid",
                                                       transparent_pixels=1, alpha=128)}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root)
    assert code == 1, out
    assert "[FAIL] transparency.solid_appearance" in out
    assert "min_alpha=128" in out


def test_single_fully_transparent_pixel_on_opaque_host_is_rejected(tmp_path, capsys):
    root = write_pack(tmp_path, {})
    blocks = {"betternether:quartz_glass": _glass_like(root, "solid",
                                                       transparent_pixels=1, alpha=0)}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root)
    assert code == 1, out
    assert "min_alpha=0" in out


def test_palette_texture_without_alpha_is_treated_as_opaque(tmp_path, capsys):
    """调色板图没有 tRNS 就是完全不透明：判据来自 transparency.inspect_texture。"""
    from PIL import Image

    root = write_pack(tmp_path, {})
    palette = Image.new("P", (8, 8), 0)
    palette.putpalette([200, 120, 80] + [0, 0, 0] * 255)
    target = root / ASSETS / "textures/block" / "paletted.png"
    target.parent.mkdir(parents=True, exist_ok=True)
    palette.save(target)
    model = root / ASSETS / "models/block" / "paletted.json"
    model.parent.mkdir(parents=True, exist_ok=True)
    model.write_text('{"parent": "minecraft:block/cube_all", '
                     '"textures": {"all": "betternether:block/paletted"}}', encoding="utf-8")
    blocks = {"betternether:cincinnasite_block": {
        "settings": {"merges": upstream_merges("cincinnasite_block")},
        "state": {"auto_state": "solid", "model": {"path": "betternether:block/paletted"}},
    }}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root)
    assert "[FAIL] transparency.solid_appearance" not in out, out
    assert code == 0, out


def test_translucent_texture_inherited_from_local_parent_is_rejected(tmp_path, capsys):
    """子模型只覆盖 `side`，父模型声明 `all`；沿 parent 合并后仍要看得到父模型的贴图。"""
    root = write_pack(tmp_path, {})
    write_texture(root, "inherited_alpha", transparent_pixels=8)
    write_texture(root, "child_side", transparent_pixels=0)

    base = root / ASSETS / "models/block" / "parent_base.json"
    base.parent.mkdir(parents=True, exist_ok=True)
    base.write_text('{"parent": "minecraft:block/cube_all", '
                    '"textures": {"all": "betternether:block/inherited_alpha"}}',
                    encoding="utf-8")
    child = root / ASSETS / "models/block" / "child_model.json"
    child.write_text('{"parent": "betternether:block/parent_base", '
                     '"textures": {"side": "betternether:block/child_side"}}',
                     encoding="utf-8")

    blocks = {"betternether:cincinnasite_block": {
        "settings": {"merges": upstream_merges("cincinnasite_block")},
        "state": {"auto_state": "solid", "model": {"path": "betternether:block/child_model"}},
    }}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root)
    assert code == 1, out
    assert "inherited_alpha" in out
    assert "min_alpha=0" in out


def test_parent_cycle_does_not_hang_the_gate(tmp_path, capsys):
    """parent 成环时必须停下来（生成器侧用 `seen` 保底，门禁同样）。"""
    root = write_pack(tmp_path, {})
    write_texture(root, "loop_tex", transparent_pixels=0)
    first = root / ASSETS / "models/block" / "loop_a.json"
    first.parent.mkdir(parents=True, exist_ok=True)
    first.write_text('{"parent": "betternether:block/loop_b", '
                     '"textures": {"all": "betternether:block/loop_tex"}}', encoding="utf-8")
    second = root / ASSETS / "models/block" / "loop_b.json"
    second.write_text('{"parent": "betternether:block/loop_a"}', encoding="utf-8")

    blocks = {"betternether:cincinnasite_block": {
        "settings": {"merges": upstream_merges("cincinnasite_block")},
        "state": {"auto_state": "solid", "model": {"path": "betternether:block/loop_a"}},
    }}
    (root / BLOCK_DIR / "test.yml").write_text(
        yaml.safe_dump({"blocks": blocks}, allow_unicode=True), encoding="utf-8")
    code, out = run(capsys, root)
    # 贴图不透明 → 不该因为成环而误报透明，也不该卡死
    assert "[FAIL] transparency.solid_appearance" not in out, out
    assert code == 0, out


def test_generation_model_without_local_json_is_not_a_gap(tmp_path, capsys):
    """`generation:` 的模型由 CE 合成，磁盘上没有 JSON；不能当成「模型缺失」。"""
    root = write_pack(tmp_path, {"betternether:cincinnasite_block": {
        "settings": {"merges": upstream_merges("cincinnasite_block")},
        "state": {"auto_state": "solid", "model": {
            "path": "betternether:block/generated_only",
            "generation": {"parent": "minecraft:block/cube_all",
                           "textures": {"all": "betternether:block/gen_tex"}}}},
    }})
    write_texture(root, "gen_tex", transparent_pixels=0)
    code, out = run(capsys, root)
    assert "[FAIL] transparency.unresolved_texture" not in out, out
    assert code == 0, out


# --------------------------------------------------------------------------- 连接断裂层
#
# 支撑门禁（`plant_support.contract`）只看 `.addTrait(...)` + `canSurvive`，看不到
# `updateShape` 里「邻居没了就 Blocks.AIR」那一整套 —— 藤蔓家族、芦苇、石笋柱都在那条
# 盲区里，而且门禁当时是绿的。这一组锁住新的三条规则。

def compliant_block(name: str, *, behavior: dict | None = None, carrier: str = "solid",
                    **merges) -> dict:
    """按上游真值拼一个「除被测点外一切合规」的方块配置。

    `carrier` 默认 `solid`（满格方块的标准宿主）。**植物必须显式传 `tripwire` 之类的
    无碰撞载体**：`plant_states.collision_pool_mismatch` 之后，植物借 `solid` 就是
    「变成实心障碍」，夹具不能再拿它当默认值。
    """
    config: dict = {
        "settings": {"merges": upstream_merges(name, **merges)},
        "state": {
            "auto_state": carrier,
            "model": {"path": f"betternether:block/{name}",
                      "generation": {"parent": "minecraft:block/cube_all",
                                     "textures": {"all": f"betternether:block/{name}"}}},
        },
    }
    if behavior is not None:
        config["behavior"] = behavior
    return {f"betternether:{name}": config}


def breakdown_behavior(name: str) -> dict:
    """`bnlib.support` 给出的候选破坏行为（解析成产物里该写的那一段）。"""
    entries = support.breakdown_behaviors(name, upstream=UPSTREAM)
    assert entries, f"{name} 没有可发布的破坏行为"
    parsed = gate.parse_support_lines(list(entries[0]))
    assert parsed is not None
    return dict(parsed)


def test_slot_registered_material_classes_are_counted(tmp_path, capsys):
    """`blocks/complex/*Material.java` 的槽位注册必须在门禁里明确计数（原来是盲区）。"""
    # 基线方块从 `moss_cover` 换成 `jungle_moss`：moss_cover 现在有可证实的支撑规则，
    # 夹具不写那条 bush_block 行为就会先撞 `plant_support.contract`；本条只测槽位注册计数。
    root = write_pack(tmp_path, compliant_block("jungle_moss", carrier="tripwire"))
    write_texture(root, "jungle_moss")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "plant_support.material_registration_unscanned  已检查" in out
    # stalagnate_trunk 没有破坏判据，但它的注册事实必须可见（否则「读不到」会被当成「没有」）。
    assert "BlockStalagnate" in out


def test_update_shape_rule_without_any_record_is_rejected(tmp_path, capsys, monkeypatch):
    """上游有破坏判据、产物既没行为、support 里也没记录 → 判红（这次的盲区形态）。"""
    root = write_pack(tmp_path, compliant_block("moss_cover", carrier="tripwire"))
    write_texture(root, "moss_cover")

    candidates = gate._breakdown_candidates(UPSTREAM)
    assert "betternether:moss_cover" in candidates
    rules = dict(gate._breakdown_rules(UPSTREAM))
    rules.pop("moss_cover")          # 模拟「有人把这条记录删了」
    monkeypatch.setitem(gate._BREAKDOWN_CANDIDATE_CACHE, UPSTREAM, candidates)
    monkeypatch.setitem(gate._BREAKDOWN_RULES_CACHE, UPSTREAM, rules)

    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_support.update_shape_unmapped" in out
    assert "betternether:moss_cover" in out
    assert "没有任何人负责" in out


def test_blocked_update_shape_rule_is_reported_not_silently_skipped(tmp_path, capsys):
    """记录存在但 CE 表达不了时：不判红，但必须在报告里看得见（含差在哪一支）。"""
    # 夹具方块从 `moss_cover` 换成 `basalt_stalactite`：moss_cover 的破坏判据现在已被
    # 可证实的支撑规则覆盖（`covered_by_support_rule=True`），根本走不到 blocked 分支；
    # 石笋族「递归破坏整串并掉落」是仍然判「CE 表达不了」的那一类，语义更贴本条标题。
    rule = gate._breakdown_rules(UPSTREAM)["basalt_stalactite"]
    assert rule.blocked and not rule.covered_by_support_rule, (
        "夹具前提变了：basalt_stalactite 已经不再是被判「CE 表达不了」的判据")

    root = write_pack(tmp_path, compliant_block("basalt_stalactite", carrier="tripwire"))
    write_texture(root, "basalt_stalactite")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[OK]   plant_support.update_shape_unmapped" in out
    assert "betternether:basalt_stalactite：断开方向" in out
    assert "CE 表达不了" in out


def test_publishable_breakdown_rule_must_exist_in_the_product(tmp_path, capsys, monkeypatch):
    """判为「可等价发布」的破坏判据没写进产物 = 玩家看到「该断的不断」→ 判红。

    夹具说明：真实规则表里现在**找不到**「既没被判 blocked、又没被可证实支撑规则覆盖」
    的判据（`covered_by_support_rule=True` 的那些由 `plant_support.contract` 管，见
    `test_publishable_breakdown_rule_with_the_behavior_passes`），所以这条用例像相邻的
    `test_update_shape_rule_without_any_record_is_rejected` 一样，用**真实判据**
    （`brown_large_mushroom` 的 bush_block 白名单）把 `blocked` 摘掉来构造「可发布」形态，
    而不是删掉断言。门禁代码本身不动。
    """
    rules = dict(gate._breakdown_rules(UPSTREAM))
    real = rules["brown_large_mushroom"]
    assert real.blocked and not real.covered_by_support_rule
    rules["brown_large_mushroom"] = dataclasses.replace(real, blocked=None)
    monkeypatch.setitem(gate._BREAKDOWN_RULES_CACHE, UPSTREAM, rules)

    root = write_pack(tmp_path, compliant_block("brown_large_mushroom", carrier="tripwire"))
    write_texture(root, "brown_large_mushroom")
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_support.breakdown_contract" in out
    assert "破坏行为与上游真值不符" in out
    assert "betternether:brown_large_mushroom" in out


def test_publishable_breakdown_rule_with_the_behavior_passes(tmp_path, capsys):
    """写对了就绿：白名单成员逐条来自上游（这里就是 `potted_plant` 的家具白名单）。

    这份迷你产物只发布了 `potted_plant` 自己，白名单里那些家具 id 当然「未发布」，
    所以 `dangling_block` 会照实报红 —— 这里只断言**破坏判据这一条**不再违规。
    """
    behavior = breakdown_behavior("potted_plant")
    assert behavior["type"] == "bush_block"
    assert "betternether:acacia_chair" in behavior["bottom_blocks"]

    root = write_pack(tmp_path, compliant_block("potted_plant", behavior=behavior))
    write_texture(root, "potted_plant")
    _code, out = run(capsys, root, "--allow-unimplemented")
    assert "[FAIL] plant_support.breakdown_contract" not in out
    assert "[OK]   plant_support.breakdown_contract" in out


def test_breakdown_rules_are_declared_and_counted(tmp_path, capsys):
    """三条新规则都必须出现在 RULE_DESCRIPTIONS 里，并且真的被检查过。"""
    for rule in ("plant_support.update_shape_unmapped",
                 "plant_support.breakdown_contract",
                 "plant_support.material_registration_unscanned"):
        assert rule in gate.RULE_DESCRIPTIONS

    root = write_pack(tmp_path, compliant_block("moss_cover", carrier="tripwire"))
    write_texture(root, "moss_cover")
    _code, out = run(capsys, root, "--allow-unimplemented")
    for rule in ("plant_support.update_shape_unmapped",
                 "plant_support.material_registration_unscanned"):
        assert f"[OK]   {rule}  已检查" in out


# --------------------------------------------------------------------------- 植物载体契约
#
# 这次改造把植物/装饰从「transparent + entity_renderer」换成「真实状态 + 模型」。换完之后
# **碰撞箱与遮挡都由借用的原版状态决定**，所以门禁盯的是两件最坏的事：
#
#   * 植物变成实心障碍（借到满格碰撞的载体）；
#   * 透过植物看到地下（借到会遮挡剔除的载体，`cactus` 就属于这一类）。
#
# 上游真值仍然来自 `block_shapes.txt`，碰撞/遮挡语义来自 `scripts/ce_patch/collstates.tsv`
# 的逐状态取证；测试不写死「门禁自己期望的形状」。

def plant_state(carrier: str, *, host_id: str | None = None, entity: bool = False,
                model: str = "plant") -> dict:
    """一个植物外观：真实状态载体（+ 本包模型），可选外挂 entity_renderer。"""
    auto: dict = {"type": carrier}
    if host_id is not None:
        auto["id"] = host_id
    view: dict = {
        "auto_state": auto,
        "model": {"path": f"betternether:block/{model}",
                  "generation": {"parent": "minecraft:block/cross",
                                 "textures": {"cross": f"betternether:block/{model}"}}},
    }
    if entity:
        view["transparent"] = True
        view["entity_renderer"] = {"type": "item_display", "item": "betternether:plant_ce_1"}
    return view


def plant_pack(tmp_path: Path, blocks: dict) -> Path:
    """把 `{名字: 外观}` 拼成一包植物配置（merges 全部取上游真值）。"""
    configs = {
        f"betternether:{name}": {"settings": {"merges": upstream_merges(name)},
                                 "state": view}
        for name, view in blocks.items()
    }
    return write_pack(tmp_path, configs)


#: 上游碰撞为 empty、且没有支撑 trait 的方块：拿来做「除被测点外一切合规」的基线。
#:
#: 2026-10 整族移除方块之后这份名单重挑过，两个坑都记在这里：
#:   * `wart_seed` / `stalagnate_seed` 已被 `bnlib.publication.REMOVED_BLOCK_IDS` 收走，
#:     再拿它们当产物就会撞 `removal.reference`（现在只测槽位额度，不该被删除引用抢戏）；
#:   * `moss_cover` 仍在发布，但上游已经能证实它的支撑规则（bush_block 黑名单），
#:     拿它当基线会必然撞 `plant_support.contract`（夹具里没写那条行为）。
#: 换成仍然「无支撑 trait、无破坏判据、未移除」的树苗族 + `jungle_moss`。
CLEAN_PLANTS = ("jungle_moss", "anchor_tree_sapling", "gloomwood_sapling", "willow_sapling")


@pytest.fixture(autouse=True)
def complete_ledger(monkeypatch):
    """把账本里**尚未采集**的池补成 `free == 额度`，免得无关用例被「缺账」绊住。

    真实账本只有 `non_tintable_leaves` / `tintable_leaves` 落在
    `bnlib.ce_pool_ledger.UNCOLLECTED_POOLS`（CE 内置组，cap 采集不到）。这两个池在账本里
    **有条目**（cap/free 为 None、used 实测），所以「申请了额度就必须有账」这条不会再被它们
    绊住，门禁对它们改为跳过余量断言 + 报告标注。这里的补账只兜住「额度 > 0 却完全没条目」
    的情况，缺账行为本身由 `test_budget_without_ledger_entry_is_rejected` 单独覆盖。
    """
    real = gate.pool_ledger()
    for pool, limit in gate.pool_budgets().items():
        if pool not in real and limit > 0:
            real[pool] = {"cap": limit, "used": 0, "free": limit,
                          "collected": "测试脚手架", "source": "测试脚手架"}
    monkeypatch.setattr(gate, "pool_ledger",
                        lambda: {pool: dict(row) for pool, row in real.items()})
    yield


def _budget(monkeypatch, budgets: dict[str, int], ledger: dict[str, dict] | None = None) -> None:
    """把「本期额度 + 余量账本」换成一眼能看懂的小表。

    这些用例只测对账逻辑本身，所以两张表的数字都写成自洽的最小值；真实采集值由
    `test_pool_capacities_come_from_the_generator` 与 `test_pool_ledger_is_self_consistent` 守。
    """
    monkeypatch.setattr(gate, "pool_budgets", lambda: dict(budgets))
    if ledger is None:
        ledger = {pool: {"cap": limit, "used": 0, "free": limit,
                         "collected": "2026-10-28", "source": "测试夹具"}
                  for pool, limit in budgets.items()}
    monkeypatch.setattr(gate, "pool_ledger",
                        lambda: {pool: dict(row) for pool, row in ledger.items()})


def test_plant_with_real_state_carrier_passes(tmp_path, capsys, monkeypatch):
    """正例：植物借无碰撞载体（绊线）+ 真实模型，植物载体这组规则应当全绿。"""
    _budget(monkeypatch, {"tripwire": 126})
    # `moss_cover` 现在带可证实的支撑规则，夹具不写 bush_block 行为会先撞
    # `plant_support.contract`；本条只测载体碰撞语义，换成同样 collision=empty 的
    # `jungle_moss`（无支撑 trait、无破坏判据、未移除）。
    root = plant_pack(tmp_path, {"jungle_moss": plant_state("tripwire")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[OK]   plant_states.collision_pool_mismatch  已检查 1 项" in out
    assert "[OK]   plant_states.occluding_carrier  已检查 1 项" in out


def test_pool_names_not_in_the_enum_are_rejected(tmp_path, capsys, monkeypatch):
    """`sculk_vein` / `resin_clump` / `vine` **不是** CE 的合法组名，必须判红。

    它们曾经被当成「扩容新增的无碰撞池」写进产物，结果服务器上这批方块完全没有外观
    （CE 加载时报「无效的常量」）。这条用例把那次返工的根因钉住。
    """
    _budget(monkeypatch, {"sculk_vein": 63, "resin_clump": 63, "vine": 31})
    root = plant_pack(tmp_path, {
        "moss_cover": plant_state("sculk_vein"),
        "jungle_moss": plant_state("resin_clump"),
        "wart_seed": plant_state("vine"),
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code != 0, out
    assert "[FAIL] plant_states.known_auto_state_group" in out
    for bogus in ("sculk_vein", "resin_clump", "vine"):
        assert bogus in out


def test_plant_still_using_entity_renderer_is_rejected(tmp_path, capsys):
    """反例：植物还挂着 entity_renderer（展示实体）必须报出来。"""
    root = plant_pack(tmp_path, {"moss_cover": plant_state("tripwire", entity=True)})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.no_entity_renderer" in out
    assert "betternether:moss_cover" in out
    assert "entity_renderer" in out


def test_entity_renderer_exemption_is_per_block_id_only(tmp_path, capsys, monkeypatch):
    """豁免表逐 id 生效：登记了某个方块，不等于豁免「同族」的其它方块。"""
    monkeypatch.setattr(gate, "ENTITY_RENDERER_EXEMPTIONS",
                        {"betternether:moss_cover": "只有这块必须走展示实体：模型在客户端 mod 里"})
    root = plant_pack(tmp_path, {
        "moss_cover": plant_state("tripwire", entity=True),
        "jungle_moss": plant_state("tripwire", entity=True),
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    section = out.split("[FAIL] plant_states.no_entity_renderer")[1].split("[")[0]
    assert "betternether:jungle_moss" in section
    assert "betternether:moss_cover" not in section


def test_entity_renderer_exemption_without_reason_does_not_count(tmp_path, capsys, monkeypatch):
    """空原因的豁免不算数：豁免表必须写明「为什么只能走展示实体」。"""
    monkeypatch.setattr(gate, "ENTITY_RENDERER_EXEMPTIONS", {"betternether:moss_cover": "  "})
    root = plant_pack(tmp_path, {"moss_cover": plant_state("tripwire", entity=True)})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.no_entity_renderer" in out


def test_empty_collision_plant_on_a_full_collision_pool_is_rejected(tmp_path, capsys):
    """反例核心：上游 collision=empty 的植物借到满格碰撞的载体 = 植物变成实心障碍。"""
    root = plant_pack(tmp_path, {"moss_cover": plant_state("non_tintable_leaves")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.collision_pool_mismatch" in out
    assert "betternether:moss_cover" in out
    assert "non_tintable_leaves" in out
    # 树叶不遮挡：只该撞「碰撞箱」这条，不该被算成透视。
    assert "[FAIL] plant_states.occluding_carrier" not in out


def test_empty_collision_plant_on_note_block_is_rejected(tmp_path, capsys):
    """反例：音符盒载体既是满格碰撞、又会遮挡剔除 —— 两条都要报。"""
    root = plant_pack(tmp_path, {"moss_cover": plant_state("note_block")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.collision_pool_mismatch" in out
    assert "[FAIL] plant_states.occluding_carrier" in out
    assert "note_block" in out
    assert "透视" in out


def test_plant_on_mushroom_block_pool_is_rejected(tmp_path, capsys):
    """蘑菇方块族也是完整方块：`solid` 之外的第二个遮挡入口。"""
    root = plant_pack(tmp_path, {"moss_cover": plant_state("mushroom_stem")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.occluding_carrier" in out
    assert "mushroom_stem" in out


def test_cactus_pool_is_never_a_plant_carrier(tmp_path, capsys):
    """`cactus` 16 个状态全部 `canOcclude=true`（collstates.tsv 取证）→ 借它就是透视。

    连「上游本身是仙人掌」的方块也不例外：遮挡剔除与「你是不是仙人掌」无关。
    """
    root = plant_pack(tmp_path, {"moss_cover": plant_state("cactus")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.occluding_carrier" in out
    assert "[FAIL] plant_states.collision_pool_mismatch" in out
    assert "cactus" in out
    # 仙人掌不再是「允许登记的部分碰撞池」，所以连登记的口子都没有。
    assert "cactus" not in gate.PARTIAL_COLLISION_POOLS


def test_partial_plant_on_cactus_pool_is_rejected(tmp_path, capsys):
    """partial 方块借仙人掌同样判红：不走近似登记，直接按遮挡/形状不符拦。"""
    root = plant_pack(tmp_path, {"giant_mold": plant_state("cactus")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.occluding_carrier" in out
    assert "betternether:giant_mold" in out


def test_slot_usage_within_budget_passes(tmp_path, capsys, monkeypatch):
    """正例：三个植物占 tripwire 三个槽位，本期额度 3 → 通过。"""
    _budget(monkeypatch, {"tripwire": 3})
    root = plant_pack(tmp_path, {name: plant_state("tripwire") for name in CLEAN_PLANTS[:3]})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[OK]   plant_states.state_slot_budget" in out


def test_slot_usage_over_budget_is_rejected(tmp_path, capsys, monkeypatch):
    """反例：四个植物挤 tripwire（额度 3）→ 超额必须报出池名与数量。"""
    _budget(monkeypatch, {"tripwire": 3})
    root = plant_pack(tmp_path, {name: plant_state("tripwire") for name in CLEAN_PLANTS})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.state_slot_budget" in out
    assert "占用了 4 个槽位" in out and "本期额度只有 3" in out


def test_shared_carrier_id_counts_as_a_single_slot(tmp_path, capsys, monkeypatch):
    """共享原版状态（`id: transparent`）全包只占 1 个槽位，不能按外观数累加。"""
    _budget(monkeypatch, {"sugar_cane": 1})
    root = plant_pack(tmp_path, {
        name: plant_state("sugar_cane", host_id="transparent") for name in CLEAN_PLANTS
    })
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[OK]   plant_states.state_slot_budget" in out


def test_pool_without_any_budget_entry_is_rejected(tmp_path, capsys, monkeypatch):
    """额度表里没有的池不能默认放行：那等于「无法证明不超限」。"""
    _budget(monkeypatch, {"tripwire": 3})
    root = plant_pack(tmp_path, {"moss_cover": plant_state("unheard_of_pool")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.state_slot_budget" in out
    assert "unheard_of_pool" in out


def test_budget_without_ledger_entry_is_rejected(tmp_path, capsys, monkeypatch):
    """断言 1：额度表里有、账本里没有的池 = 没采集过，必须判红（不能默认放行）。"""
    _budget(monkeypatch, {"tripwire": 9, "sapling": 1},
            ledger={"tripwire": {"cap": 126, "used": 117, "free": 9}})
    root = plant_pack(tmp_path, {"moss_cover": plant_state("tripwire")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.state_slot_budget" in out
    assert "sapling" in out and "没有它的余量记录" in out


def test_budget_over_ledger_free_slots_is_rejected(tmp_path, capsys, monkeypatch):
    """断言 2：把 CE 池总容量当额度（额度 > 真实余量）必须判红。"""
    _budget(monkeypatch, {"tripwire": 126},
            ledger={"tripwire": {"cap": 126, "used": 117, "free": 9}})
    root = plant_pack(tmp_path, {"moss_cover": plant_state("tripwire")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.state_slot_budget" in out
    assert "超过了真实余量 9" in out


def test_inconsistent_ledger_is_rejected(tmp_path, capsys, monkeypatch):
    """断言 3：账本自身 `cap - used == free`，抄错就必须红（否则前两条在验错的东西）。"""
    _budget(monkeypatch, {"tripwire": 5},
            ledger={"tripwire": {"cap": 126, "used": 117, "free": 5}})
    root = plant_pack(tmp_path, {"moss_cover": plant_state("tripwire")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.state_slot_budget" in out
    assert "不自洽" in out


def test_pool_budgets_come_from_the_generator():
    """额度表的来源必须是生成器的常量，而不是测试文件里的期望值。"""
    from bnlib import blocks  # noqa: PLC0415

    budgets = gate.pool_budgets()
    assert budgets, "读不到 bnlib.blocks.POOL_BUDGET，规则会静默失去真值"
    assert budgets == {str(pool): int(limit) for pool, limit in blocks.POOL_BUDGET.items()}


def test_pool_ledger_is_self_consistent():
    """真实账本必须自洽：cap - used == free，且三者非负（重采时抄错的直接拦下）。

    这条读的是**模块数据本身**（不是门禁读到的副本），因为它验的是采集结果；
    `complete_ledger` 脚手架补的那几条只存在于门禁侧。
    """
    from bnlib import ce_pool_ledger  # noqa: PLC0415

    rows = dict(ce_pool_ledger.POOL_LEDGER)
    assert rows, "读不到 bnlib.ce_pool_ledger.POOL_LEDGER"
    for pool, entry in rows.items():
        assert entry.get("collected"), f"{pool} 没有采集日期"
        assert entry.get("source"), f"{pool} 没有采集口径"
        assert int(entry["used"]) >= 0, f"{pool} 的 used 出现负数：{entry}"  # type: ignore[arg-type]
        if entry.get("cap") is None or entry.get("free") is None:
            # CE 内置 mappings 的组（树叶两池）：用户 mappings.yml 里没有这个组名，
            # 总槽位采集不到。**必须两个一起是 None** —— 只写一个 None 说明是半抄的。
            assert entry.get("cap") is None and entry.get("free") is None, (
                f"{pool} 的 cap/free 只缺一个，看起来是抄了一半：{entry}"
            )
            assert pool in ce_pool_ledger.UNCOLLECTED_POOLS, (
                f"{pool} 没有 cap/free，必须登记在 UNCOLLECTED_POOLS 里"
            )
            continue
        assert pool not in ce_pool_ledger.UNCOLLECTED_POOLS, (
            f"{pool} 已经采到 cap/free，不该再挂在 UNCOLLECTED_POOLS 里"
        )
        cap, used, free = int(entry["cap"]), int(entry["used"]), int(entry["free"])  # type: ignore[arg-type]
        assert min(cap, used, free) >= 0, f"{pool} 出现负数：{entry}"
        assert cap - used == free, f"{pool} 不自洽：{cap} - {used} != {free}"
    # `tripwire` 三组共用一份账：分成三条会让额度断言重复计算。
    assert "lower_tripwire" not in rows and "higher_tripwire" not in rows


def test_partial_plant_on_partial_pool_is_recorded_by_the_generator(tmp_path, capsys,
                                                                   monkeypatch):
    """正例：partial 方块借 chorus（形状差异明显），生成器表里有签字说明 → 通过。"""
    _budget(monkeypatch, {"chorus": 63})
    root = plant_pack(tmp_path, {"giant_mold": plant_state("chorus")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[FAIL] plant_states.partial_collision_unrecorded" not in out


def test_partial_collision_without_a_generator_record_is_rejected(tmp_path, capsys, monkeypatch):
    """反例：生成器表里没有这块，却把它放进了形状差异明显的池 → 判红。"""
    _budget(monkeypatch, {"chorus": 63})
    fits, _problem = gate.partial_collision_fits()
    assert fits is not None
    pruned = {name: record for name, record in fits.items() if name != "giant_mold"}
    monkeypatch.setattr(gate, "partial_collision_fits", lambda: (pruned, ""))
    root = plant_pack(tmp_path, {"giant_mold": plant_state("chorus")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.partial_collision_unrecorded" in out
    assert "betternether:giant_mold" in out
    assert "chorus" in out
    assert "没有这块的条目" in out


def test_partial_collision_with_an_empty_note_is_rejected(tmp_path, capsys, monkeypatch):
    """保真说明是空字符串的登记不算签字（生成器表里确实有几条空说明）。"""
    _budget(monkeypatch, {"chorus": 63})
    fits, _problem = gate.partial_collision_fits()
    assert fits is not None
    record = fits["giant_mold"]
    patched = dict(fits)
    patched["giant_mold"] = (record[0], record[1], "   ")
    monkeypatch.setattr(gate, "partial_collision_fits", lambda: (patched, ""))
    root = plant_pack(tmp_path, {"giant_mold": plant_state("chorus")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.partial_collision_unrecorded" in out
    assert "保真说明是空的" in out


def test_partial_plant_on_a_dangerous_pool_is_rejected(tmp_path, capsys, monkeypatch):
    """危险载体自带红石语义：就算形状登记过，也必须有人签字才允许。

    夹具说明：`bnlib.blocks` 侧已经把 `pressure_plate` 从「危险/禁止」改成
    「无碰撞、可用但需登记」，所以门禁的 `DANGEROUS_POOLS` 目前是**空表** —— 这条分支
    对真实数据不可达。为了让这条安全网不因为表变空而悄悄失效，这里显式把
    `pressure_plate` 作为危险载体喂给门禁（只改常量，不改判定逻辑），
    同时保留「登记缺失必须判红」的断言。
    """
    monkeypatch.setattr(gate, "DANGEROUS_POOLS", {"pressure_plate": "红石信号副作用"})
    _budget(monkeypatch, {"pressure_plate": 1})
    fits, _problem = gate.partial_collision_fits()
    assert fits is not None
    # `willow_trunk` 已被移除（removal.reference 会先红），换成仍发布的 partial 方块。
    pruned = {name: record for name, record in fits.items() if name != "giant_mold"}
    monkeypatch.setattr(gate, "partial_collision_fits", lambda: (pruned, ""))
    root = plant_pack(tmp_path, {"giant_mold": plant_state("pressure_plate")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 1, out
    assert "[FAIL] plant_states.partial_collision_unrecorded" in out
    assert "pressure_plate" in out


def test_partial_plant_on_a_collision_free_pool_is_reported_as_a_note(tmp_path, capsys,
                                                                     monkeypatch):
    """落在无碰撞池里的 partial 方块不判红（有意放弃碰撞），但事实必须写在报告里。"""
    _budget(monkeypatch, {"tripwire": 126})
    # `willow_trunk` 已被移除（产物里出现就会被 `removal.reference` 判红），
    # 换成仍然发布、上游 collision=partial 且没有支撑/破坏规则的 `giant_mold`。
    root = plant_pack(tmp_path, {"giant_mold": plant_state("tripwire")})
    code, out = run(capsys, root, "--allow-unimplemented")
    assert code == 0, out
    assert "[FAIL] plant_states.partial_collision_unrecorded" not in out
    assert "betternether:giant_mold：上游" in out


def test_partial_collision_rule_is_unimplemented_without_the_generator_table(tmp_path, capsys,
                                                                            monkeypatch):
    """读不到 PARTIAL_COLLISION_FITS 时标「未实现」，绝不默认放行。"""
    _budget(monkeypatch, {"chorus": 63})
    monkeypatch.setattr(gate, "partial_collision_fits",
                        lambda: (None, "bnlib.blocks 里没有 PARTIAL_COLLISION_FITS 表"))
    root = plant_pack(tmp_path, {"giant_mold": plant_state("chorus")})
    code, out = run(capsys, root)
    assert code == 2, out
    assert "[UNIMPLEMENTED] plant_states.partial_collision_unrecorded" in out

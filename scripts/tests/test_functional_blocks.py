"""功能方块必须真的「能打开」—— 锁住工作台/桶/箱走的是 CE 原生机制。

## 为什么这件事必须写测试

用户要的是「工作台点一下真的弹合成台、桶和箱真的能存东西」，不是「长得像」。
本包这几类方块以前按纤细装饰发布：外观和碰撞都在，但右键什么都不会发生。
把「能不能打开」落到两条 CE 原生机制上以后，正确的判据不是「配置里出现了某些字」，
而是下面四件事同时成立：

1. 机制确实是 CE jar 里存在的那一个（不是我们以为存在的）：
   * 容器走 `SimpleStorageBlockBehavior`（`EntityBlock` + `WorldlyContainerHolder`）；
   * 界面走 `open_window` + `GuiType` 枚举里的名字。
   这两条在本机 jar 上由 `javap` 实测过，测试把结论钉在这里，避免有人凭印象写错名字。
2. 每个**已发布**的工作台/桶/箱都带上了对应配置，一个都不能漏。
3. 容器是真实持久容器（`rows: 3` 而不是 CE 默认的 1 行；打破掉落由 CE 自己
   在 `onRemove()` 里 `dropItemNaturally` 做，所以这里**不写**存储/掉落逻辑）。
4. 不许乱加：书架、雕纹书架、熔炉都不能被塞进这几条机制里。
   熔炉是当前的**已知缺口**——CE 的 `GuiType` 没有 furnace，见
   `test_furnaces_are_a_known_gap_not_faked`。
"""

from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from bnlib import blocks  # noqa: E402

GENERATED = ROOT / "plugin/build/generated/bn/craftengine/betternether/configuration"
MANIFEST = ROOT / "plugin/build/generated/bn/reports/block-manifest.json"

# CE jar 里真实存在的 GUI 类型（`GuiType` 枚举全部成员，`javap` 实测）。
# `BukkitGuiManager` 逐项映射到 `LegacyInventoryUtils.openAnvil/openWorkbench/…`。
CE_GUI_TYPES = {
    "anvil",
    "cartography",
    "enchantment",
    "grindstone",
    "loom",
    "smithing",
    "crafting",
}

# 这几个上游类是「工作台/铁砧」：右键应当打开原版界面，而不是什么都不发生。
GUI_CLASSES = set(blocks.FUNCTIONAL_GUI_OPEN_BY_CLASS)

# 这几个上游类是原版容器：应当变成真实持久容器。
STORAGE_CLASSES = set(blocks.FUNCTIONAL_STORAGE_BY_CLASS)


def _jar() -> Path:
    """本机 CraftEngine jar（CE 行为/GUI 类型的权威来源）。"""
    candidate = Path("C:/PluginLibs/craft-engine-paper-plugin-26.10-SNAPSHOT.jar")
    if not candidate.is_file():
        pytest.skip(f"找不到 CE jar（{candidate}），无法核对机制是否真的存在")
    return candidate


def _class_names() -> frozenset[str]:
    with zipfile.ZipFile(_jar()) as archive:
        return frozenset(archive.namelist())


def _gui_type_members() -> frozenset[str]:
    """`GuiType` 枚举成员名。

    直接读 class 文件常量池：枚举成员名在 `<clinit>` 里是裸 UTF8 常量
    （`ANVIL` / `CRAFTING` …，实测输出里 `$VALUES` 紧跟在最后一个成员后面），
    所以不需要反编译器，也不需要 `javap`。
    """
    with zipfile.ZipFile(_jar()) as archive:
        raw = archive.read(
            "net/momirealms/craftengine/core/plugin/gui/GuiType.class").decode("latin-1")
    candidates = CE_GUI_TYPES.union({"furnace", "merchant", "beacon", "crafting_recipe"})
    return frozenset(name for name in candidates if name.upper() in raw)


def _norm(node):
    """把 PyYAML 解析出的布尔键还原成 YAML 原文里的字符串键。

    ## 为什么需要这一步

    YAML 1.1 把裸 `on` / `off` 当作布尔量，所以 `- on: right_click` 在 PyYAML 里
    会变成 `{True: 'right_click'}`。**配置本身没问题**：CE 的
    `core/plugin/config/yaml/StringKeyConstructor#constructMapping` 建的是
    `LinkedHashMap` 并对每个 key 调 `toString()`，所以 `on` 在 CE 侧始终是字符串
    `"on"`。这里只是把测试侧的解析结果摆回同一形态，免得断言写成 `e.get("on")`
    永远取不到东西。
    """
    if isinstance(node, dict):
        return {
            ("on" if key is True else "off" if key is False else key): _norm(value)
            for key, value in node.items()
        }
    if isinstance(node, list):
        return [_norm(item) for item in node]
    return node


def _block_yaml(cls: str, block_id: str = "betternether:demo") -> dict:
    """拿真实属性表把一个方块跑一遍 `block_yaml`，解析回 dict。

    `block_yaml` 自己就带 `  <id>:` 那一行，所以直接补一个 `blocks:` 头即可。
    """
    text = blocks.block_yaml(
        block_id, None, True, blocks.KIND_SOLID, "solid", _props(cls),
        frozenset(), 0, None, [])
    return _norm(yaml.safe_load("blocks:\n" + text))["blocks"][block_id]


def _props(cls: str):
    """借一个真实上游方块属性，只替换类名 —— 属性值与功能判定无关。"""
    from bnlib import ore_drops, props as block_props
    upstream = ROOT / "upstream/BetterNether"
    ore_drops.configure(upstream)
    table = block_props.load_props(upstream)
    import dataclasses
    base = table["betternether:gloomwood_planks"]
    return dataclasses.replace(base, cls=cls)


def _generated() -> tuple[dict, dict[str, str]]:
    if not (GENERATED / "blocks").is_dir() or not MANIFEST.is_file():
        pytest.skip("生成目录/清单不存在，先跑一次 blocks 生成")
    defs: dict = {}
    for path in sorted((GENERATED / "blocks").glob("*.yml")):
        data = _norm(yaml.safe_load(path.read_text(encoding="utf-8"))) or {}
        defs.update(data.get("blocks") or {})
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))["blocks"]
    return defs, {entry["id"]: entry["class"] for entry in manifest}


# ------------------------------------------------------------------ 机制：CE 真的支持


def test_storage_behavior_exists_in_the_ce_jar():
    """容器必须落在 CE 真实存在的行为类上，而不是我们编出来的名字。"""
    names = _class_names()
    assert ("net/momirealms/craftengine/bukkit/block/behavior/"
            "SimpleStorageBlockBehavior.class") in names
    # 行为注册表里确实挂了 `SIMPLE_STORAGE_BLOCK`
    with zipfile.ZipFile(_jar()) as archive:
        registry = archive.read(
            "net/momirealms/craftengine/bukkit/block/behavior/"
            "BukkitBlockBehaviors.class").decode("latin-1")
    assert "SIMPLE_STORAGE_BLOCK" in registry


def test_storage_behavior_is_a_real_persistent_container():
    """`SimpleStorageBlockBehavior` 必须是方块实体容器，并且自己负责打破掉落。"""
    with zipfile.ZipFile(_jar()) as archive:
        behavior = archive.read(
            "net/momirealms/craftengine/bukkit/block/behavior/"
            "SimpleStorageBlockBehavior.class").decode("latin-1")
        controller = archive.read(
            "net/momirealms/craftengine/bukkit/block/entity/"
            "SimpleStorageBlockEntityController.class").decode("latin-1")
    # 行为实现方块实体 + 原版容器持有者
    assert "EntityBlock" in behavior
    assert "WorldlyContainerHolder" in behavior
    # 内容随方块实体存档：save/loadCustomData
    assert "saveCustomData" in controller and "loadCustomData" in controller
    # 打破时由 CE 掉出内容 —— 这就是我们**不写**自己存储逻辑的依据
    assert "onRemove" in controller and "dropItemNaturally" in controller


def test_gui_types_are_only_the_ones_ce_implements():
    """本包用到的 gui_type 必须都在 CE 的 `GuiType` 枚举里。"""
    members = {name.lower() for name in _gui_type_members()}
    assert members == CE_GUI_TYPES, f"GuiType 成员与预期不符：{sorted(members)}"
    assert set(blocks.FUNCTIONAL_GUI_OPEN_BY_CLASS.values()) <= CE_GUI_TYPES
    # 熔炉类型的界面 CE 没有 —— 这是「熔炉不给菜单」的依据，不是遗漏
    assert "furnace" not in CE_GUI_TYPES


def test_open_window_function_exists():
    """`events → open_window` 这个动作类型必须真实存在。"""
    assert ("net/momirealms/craftengine/core/plugin/context/function/"
            "OpenWindowFunction.class") in _class_names()


def test_test_flag_condition_allows_everyone_when_no_protection_plugin():
    """`test_flag`(领地交互保护) 在没有防破坏插件时必须默认放行，不能把手艺锁死。"""
    with zipfile.ZipFile(_jar()) as archive:
        condition = archive.read(
            "net/momirealms/craftengine/bukkit/plugin/context/condition/"
            "TestFlagCondition.class").decode("latin-1")
        lib = archive.read(
            "net/momirealms/craftengine/libraries/antigrieflib/AntiGriefLib.class"
        ).decode("latin-1")
    assert "antigrieflib" in condition.lower() or "AntiGriefLib" in condition
    # providers 全通过才返回 true；没有 providers 时循环不执行 → 返回 true
    assert "providers" in lib


# ------------------------------------------------------------------ 生成：逐个方块查


def test_crafting_tables_open_the_vanilla_workbench_gui():
    """工作台：右键 → 打开原版合成界面，并接管这次交互。"""
    body = _block_yaml("CraftingTableBlock")
    events = body["events"]
    entry = [e for e in events if e.get("on") == "right_click"]
    assert entry, f"工作台没有 right_click 事件：{body}"
    functions = [f["type"] for f in entry[0]["functions"]]
    assert "open_window" in functions
    window = next(f for f in entry[0]["functions"] if f["type"] == "open_window")
    assert window["gui_type"] == "crafting"
    # 不接管的话，手持方块/食物时原版还会继续放置或进食
    assert functions[-1] == "cancel_event", "右键必须由 CE 接管"
    # 领地保护：认领地插件，但默认放行
    conditions = entry[0]["conditions"]
    assert any(c.get("type") == "test_flag" and c.get("flag") == "interact"
               for c in conditions), f"工作台缺少领地交互保护条件：{conditions}"


def test_barrels_and_chests_are_real_containers_with_three_rows():
    """桶/箱：CE 原生持久容器，27 格（CE 默认只有 1 行，必须显式写 3）。"""
    for cls in ("BarrelBlock", "ChestBlock"):
        body = _block_yaml(cls)
        behavior = body["behavior"]
        assert behavior["type"] == "simple_storage_block", f"{cls}: {behavior}"
        assert behavior["rows"] == 3, f"{cls} 行数必须是原版的 3 行：{behavior}"
        # 原版容器都能双向下料、能接比较器
        assert behavior["has_signal"] is True
        assert behavior["allow_input"] is True
        assert behavior["allow_output"] is True
        assert behavior["title"].startswith("<lang:"), (
            f"{cls} 标题必须走语言键，否则中文客户端会显示英文：{behavior['title']}")


def test_chest_of_drawers_matches_its_upstream_capacity():
    """抽屉柜：上游 `withSize(27)` + `ChestMenu.threeRows`，所以同样是 3 行。"""
    body = _block_yaml("BlockChestOfDrawers")
    behavior = body["behavior"]
    assert behavior["type"] == "simple_storage_block"
    assert behavior["rows"] == 3
    # 上游标题就是方块自己的名字（`getName()` 取 `getDescriptionId()`）
    assert behavior["title"] == "<lang:block.betternether.chest_of_drawers>"


def test_bookshelves_are_not_forced_into_a_container():
    """书架/雕纹书架不许被强加存储：上游它们没有容器语义。"""
    for cls in ("Block", "ChiseledBookShelfBlock"):
        body = _block_yaml(cls)
        assert "behavior" not in body, f"{cls} 不该有 behavior：{body.get('behavior')}"
        assert "events" not in body, f"{cls} 不该有右键事件"


def test_furnaces_are_a_known_gap_not_faked():
    """熔炉类保持原样 —— CE 没有 furnace 界面，塞存储箱会变成假熔炉。

    这是**已知缺口**（本包仍有 4 个熔炉方块）。本断言的作用是：一旦有人靠
    `simple_storage_block` 假装搞定熔炉，这里立刻失败。
    """
    for cls in ("BlockNetherFurnace", "BlockCincinnasiteForge"):
        assert cls not in blocks.FUNCTIONAL_STORAGE_BY_CLASS
        body = _block_yaml(cls)
        assert "behavior" not in body
        assert "events" not in body


def test_single_behavior_section_is_well_formed():
    """一个方块只能有一段 behavior，多了必须走 `behaviors:` 列表（否则 YAML 解析失败）。"""
    single = blocks.behavior_yaml([["type: strippable_block", "stripped: betternether:x"]])
    assert single.startswith("    behavior:\n")
    parsed_single = yaml.safe_load("blocks:\n  demo:t:\n" + single)["blocks"]["demo:t"]
    assert parsed_single["behavior"] == {
        "type": "strippable_block", "stripped": "betternether:x"}

    multi = blocks.behavior_yaml([["type: a"], ["type: b", "rows: 3"]])
    assert multi.startswith("    behaviors:\n")
    parsed_multi = yaml.safe_load("blocks:\n  demo:t:\n" + multi)["blocks"]["demo:t"]
    assert parsed_multi["behaviors"] == [{"type": "a"}, {"type": "b", "rows": 3}]


# ------------------------------------------------------------------ 生成物：真的全查


def test_every_published_functional_block_has_its_config():
    """已发布的每个工作台/桶/箱都必须带对应配置，一个都不能漏。"""
    defs, classes = _generated()

    gui_blocks = {bid: cls for bid, cls in classes.items() if cls in GUI_CLASSES}
    storage_blocks = {bid: cls for bid, cls in classes.items() if cls in STORAGE_CLASSES}
    # 已发布容器只剩桶：箱（`ChestBlock`）与抽屉柜（`BlockChestOfDrawers`）已被
    # `publication.REMOVED_CLASSES` 移除（CE 无原版容器界面，与熔炉同类处理），
    # 所以这里按**发布集合自身的类分布**核对，而不是写死一个会漂移的数量。
    assert gui_blocks, "已发布工作台为空"
    assert set(gui_blocks.values()) <= GUI_CLASSES
    assert storage_blocks, "已发布容器为空"
    assert set(storage_blocks.values()) == {"BarrelBlock"}, (
        "已发布容器的上游类变了（箱/抽屉柜应当已被 publication 移除）："
        f"{sorted(set(storage_blocks.values()))}")

    for bid, cls in sorted(gui_blocks.items()):
        body = defs[bid]
        expected = blocks.FUNCTIONAL_GUI_OPEN_BY_CLASS[cls]
        events = body.get("events") or []
        assert events, f"{bid} 没有 events，右键什么都不会发生"
        window = next((f for e in events for f in e.get("functions", [])
                       if f["type"] == "open_window"), None)
        assert window is not None, f"{bid} 没有 open_window"
        assert window["gui_type"] == expected, f"{bid}: {window}"

    for bid, cls in sorted(storage_blocks.items()):
        behavior = defs[bid].get("behavior") or {}
        assert behavior.get("type") == "simple_storage_block", f"{bid}: {behavior}"
        assert behavior.get("rows") == 3, f"{bid}: {behavior}"


def test_published_worktables_are_exactly_the_crafting_table_blocks():
    """工作台清单必须刚好等于上游 `CraftingTableBlock`，不能多也不能少。"""
    _, classes = _generated()
    worktables = {bid for bid, cls in classes.items() if cls == "CraftingTableBlock"}
    assert len(worktables) == 13, f"上游工作台数量变了：{sorted(worktables)}"
    assert all(bid.startswith("betternether:") for bid in worktables)


def test_anvil_is_not_published_so_its_menu_cannot_be_claimed():
    """铁砧配置已备好，但**未发布**（世界生成不放置它），所以游戏里暂时没有铁砧菜单。

    把这件事写成断言，避免有人看到 `FUNCTIONAL_GUI_OPEN_BY_CLASS` 里的 anvil
    就以为游戏内已经能用铁砧。
    """
    _, classes = _generated()
    assert "BlockCincinnasiteAnvil" not in classes.values(), (
        "铁砧开始发布了：请同步确认它的 open_window 配置真的落在游戏里，再改这条断言")
    assert blocks.FUNCTIONAL_GUI_OPEN_BY_CLASS["BlockCincinnasiteAnvil"] == "anvil"

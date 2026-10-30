#!/usr/bin/env python3
"""items 段永不写 `generation`；方块用到的模型必须在构建期就存在（文件或合成产物）。

## 为什么要有这组测试

`item_yaml` 的注释写着「items 段**永远只写 `path`，绝不写 `generation`**」，但代码里
曾经留了一个例外：`transparent: true` 的方块（植物/装饰）方块段不写 model，于是
由物品段替它写 `generation`。用户明确要求 items 段只能有 `path`，这个例外必须消失 ——
否则「模型只在一处生成」这条约束事实上没有执行点。

替代做法（本次实现的）：把 CE 的 `generation` 规格（parent + textures）在**构建期**
落成真实模型 JSON。于是：

  * items 段只剩 `path`，约束回到无例外的形态；
  * 方块与物品引用的是磁盘上的真实文件，不是运行期产物；
  * 不会为了「补模型」去改 state 写法（那会让 CE 对同一 path 生成两次，
    刷「无法生成模型，因为模型文件已存在」）。

植物改造后（真实状态 + 模型，不再有 `entity_renderer`）这条约束更强：
**每个外观的 `model.path` 必须在包里存在**，因为没有任何「展示实体」能替它兜底。

## 这些断言在守什么

  * `test_items_never_write_generation` —— 任何发布出去的 items 条目一旦带 `generation` 就失败。
  * `test_every_item_model_file_exists` —— items 既不能写 `generation`，它的 `path` 就必须能
    落到真实文件；这条把「偷偷靠 generation 兜底」变成不可能。
  * `test_synthesized_models_exist_and_resolve` —— 合成模型真的在包里，parent 与 textures 都能解析。
  * `test_no_entity_renderer_and_every_appearance_model_exists` —— 端到端：没有展示实体残留，
    且每个外观的 `model.path` 都在包里。
  * `test_no_ce_carrier_items` —— `_ce_*` 隐藏载体 item 只服务于旧的展示实体路线，必须清零。
  * `test_resource_pack_gate_passes` —— 整包过 `verify_assets`（67 项假失败的那道门）。

运行：`python -m pytest scripts/tests/test_transparent_models.py`
"""

from __future__ import annotations

import inspect
import json
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
UPSTREAM = ROOT / "upstream" / "BetterNether"
EXTRACT = ROOT / "scripts" / "extract_bn.py"
NAMESPACE = "betternether"

# 上游没有方块模型、也没有物品模型、又因溢出走了透明共享状态的方块（2026-10 实测）。
# 这里只锁**下限**：这几个就是「items 曾经被迫写 generation」的代表，少了说明退化。
# `potted_plant` / `soul_lily` 已按 `publication.REMOVED_BLOCK_IDS` 移除（removal-v7），
# 不再进入合成名单 —— 若继续把它们列为必须项，会与发布规则冲突而假失败。
# 测试侧还会再与**发布集合**求交（见 `test_synthesized_models_exist_and_resolve`），
# 所以以后再移除方块也无需回来改这里，但名单本身仍要显式列出「代表项」以防退化。
# 名单**不再做等值比较** —— 世界生成收录的方块会随上游/放置端增加（例如复杂树补齐后
# 多出 anchor_tree_vine、lucis_mushroom、mushroom_fir_trunk…），等值断言会让每次新增都
# 假失败。真正的闭合性由下面按包内引用推导的断言来保证。
SYNTHESIZED_MUST_INCLUDE = {
    "ink_bush",
    "red_large_mushroom",
    "willow_branch",
    "willow_trunk",
}


@pytest.fixture(scope="module")
def built(tmp_path_factory) -> Path:
    """跑一次完整迁移（blocks -> assets -> items ...），返回生成根目录。"""
    if not UPSTREAM.is_dir():
        pytest.skip(f"找不到上游仓库：{UPSTREAM}")
    out = tmp_path_factory.mktemp("bn-transparent")
    subprocess.run(
        [sys.executable, str(EXTRACT), "--upstream", str(UPSTREAM), "--output", str(out)],
        check=True, capture_output=True,
    )
    return out


def _ce_root(built: Path) -> Path:
    return built / "craftengine" / NAMESPACE


def _configs(built: Path):
    """逐条产出 (`blocks`|`items`, id, 定义, 文件)。"""
    for path in sorted((_ce_root(built) / "configuration").rglob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        for section in ("blocks", "items"):
            for block_id, body in (data.get(section) or {}).items():
                yield section, block_id, body or {}, path


def _models_root(built: Path) -> Path:
    return _ce_root(built) / "resourcepack/assets" / NAMESPACE / "models"


def _textures_root(built: Path) -> Path:
    return _ce_root(built) / "resourcepack/assets" / NAMESPACE / "textures"


def _resolves(value: str, root: Path, suffix: str) -> bool:
    """`ns:path` 是否能在包里或原版命名空间下解析。"""
    namespace, _, rel = value.partition(":")
    if not rel:
        namespace, rel = "minecraft", value
    if namespace == NAMESPACE:
        return (root / f"{rel}{suffix}").is_file()
    return namespace == "minecraft"


def test_items_never_write_generation(built: Path) -> None:
    """items 段的 `model` 只能是 `path` —— 这是用户的硬约束，没有例外。"""
    sections = list(_configs(built))
    items = [block_id for section, block_id, _, _ in sections if section == "items"]
    # 自检：扫不到条目时「没有违规」会静默成立，这条测试就白写了。
    # 改造后 `_ce_*` 隐藏载体 item 全部消失（118 个），条目数因此从 459 降到 341。
    assert len(items) > 300, f"只解析出 {len(items)} 个 items 条目，判定路径可能已失效"
    # 正向对照：同一套扫描必须在**方块段**看得到 generation，
    # 否则「items 里没有 generation」可能只是扫不到 `generation` 这个键。
    block_generation = [
        block_id for section, block_id, body, _ in sections
        if section == "blocks"
        and isinstance((body.get("state") or {}).get("model"), dict)
        and "generation" in body["state"]["model"]
    ]
    assert block_generation, "方块段也扫不到 generation，说明扫描逻辑失效"

    offenders = [block_id for section, block_id, body, _ in sections
                 if section == "items"
                 and isinstance(body.get("model"), dict)
                 and "generation" in body["model"]]
    assert offenders == [], f"这些 items 条目仍然写了 generation：{offenders}"


def _generated_block_models(built: Path) -> set[str]:
    """CE 会在加载时按 `generation` 生成的模型（相对 `models/`，不含扩展名）。

    这些路径在包里没有文件是**正常**的：`state.model.generation` 由 CE 生成一次，
    物品段引用同一个 path（items 段自己永远不写 generation）。
    """
    generated: set[str] = set()
    for section, _block_id, body, _ in _configs(built):
        if section != "blocks":
            continue
        for entry in _block_sections(body):
            for model in _model_entries(entry):
                if "generation" in model:
                    generated.add(model["path"].partition(":")[2])
    return generated


def _block_sections(body: dict) -> list[dict]:
    state = body.get("state")
    sections = [state] if isinstance(state, dict) else []
    sections += list((((body.get("states") or {}).get("appearances")) or {}).values())
    return [entry for entry in sections if isinstance(entry, dict)]


def _model_entries(section: dict) -> list[dict]:
    """`model:`（单数）与 `models:`（权重列表）里的模型字典。"""
    found: list[dict] = []
    single = section.get("model")
    if isinstance(single, dict):
        found.append(single)
    for entry in section.get("models") or []:
        if isinstance(entry, dict):
            found.append(entry)
    return found


def test_every_item_model_file_exists(built: Path) -> None:
    """items 的 `path` 必须能解析：要么包里有文件，要么由方块段的 `generation` 生成。"""
    generated = _generated_block_models(built)
    missing = []
    for section, block_id, body, _ in _configs(built):
        if section != "items":
            continue
        model = body.get("model")
        path = model.get("path") if isinstance(model, dict) else model
        if not isinstance(path, str):
            continue
        namespace, _, rel = path.partition(":")
        if namespace != NAMESPACE:
            continue
        if (_models_root(built) / f"{rel}.json").is_file():
            continue
        if rel in generated:
            continue
        missing.append(f"{block_id} -> {path}")
    assert missing == [], "物品模型没有落地：\n" + "\n".join(missing[:20])


def test_item_yaml_cannot_emit_generation() -> None:
    """签名层面锁死：`item_yaml` 没有 generation 参数，例外无法被悄悄加回来。"""
    sys.path.insert(0, str(ROOT / "scripts"))
    from bnlib import blocks  # noqa: PLC0415

    parameters = set(inspect.signature(blocks.item_yaml).parameters)
    assert "generation" not in parameters, (
        "item_yaml 又接受 generation 了 —— items 段只允许 path"
    )


def test_synthesized_models_exist_and_resolve(built: Path) -> None:
    """合成模型真的落盘、parent/textures 可解析，并且包内引用是闭合的。

    闭合性用**包内真实引用**判定，不用写死的名单：
      * 每个被 items/blocks 引用的 `betternether:block/<x>` 都必须在包里存在
        （上游自带或构建期合成，二者之一）；
      * 每个合成模型都必须被某处引用（不许留下死条目）；
      * 历史上那几个代表项必须在名单里（下限，防退化）。
    """
    report_path = built / "reports" / "synthesized-models.json"
    assert report_path.is_file(), "blocks.py 没有产出 synthesized-models.json"
    entries = json.loads(report_path.read_text(encoding="utf-8"))["models"]
    assert entries, "没有任何合成模型：透明方块的物品图标会缺失"

    names = {entry["path"].split("/", 1)[1] for entry in entries}
    # 下限只对**仍发布**的代表项生效：已按 publication 移除的方块不会再合成模型，
    # 若不过滤就假失败。这里从本次生成的方块清单取发布集合，避免名单与移除规则互相打架。
    manifest = json.loads(
        (built / "reports" / "block-manifest.json").read_text(encoding="utf-8"))["blocks"]
    published = {entry["id"].split(":", 1)[1] for entry in manifest}
    must_include = SYNTHESIZED_MUST_INCLUDE & published
    missing_floor = must_include - names
    assert not missing_floor, f"合成模型名单少了历史必须项：{sorted(missing_floor)}"

    for entry in entries:
        target = _models_root(built) / f"{entry['path']}.json"
        assert target.is_file(), f"合成模型没有落盘：{target}"
        payload = json.loads(target.read_text(encoding="utf-8"))
        # 两种合法形态：继承型（parent + textures）或上游那种 Cubik 直出本体
        # （elements + textures，用于修复坏贴图引用的壁菇模型）。
        assert "textures" in payload, f"{entry['path']} 没有 textures：{sorted(payload)}"
        # 合法形态有三种：继承型（parent + textures）、上游那种 Cubik 直出本体
        # （elements + textures），以及**座椅的合成模型**（parent + elements + textures）。
        # 第三种的必要性：座椅几何原本在 bclib（包外），只能用内联 elements 表达；
        # 同时要 `parent: minecraft:block/block` 才能拿到 display/gui 变换，
        # 否则物品图标在背包里是未旋转的裸模型。原版模型格式本来就允许子模型
        # 带 parent 且自己写 elements（子级 elements 覆盖父级）。
        assert ("parent" in payload) or ("elements" in payload), (
            f"{entry['path']} 既没有 parent 也没有 elements：{sorted(payload)}"
        )
        if "parent" in payload:
            assert _resolves(payload["parent"], _models_root(built), ".json"), (
                f"{entry['path']} 的 parent 解析不到：{payload['parent']}"
            )
        for slot, texture in payload["textures"].items():
            assert _resolves(texture, _textures_root(built), ".png"), (
                f"{entry['path']} 的 textures.{slot} 解析不到：{texture}"
            )

    referenced = _referenced_block_models(built)
    missing = sorted(path for path in referenced
                     if not (_models_root(built) / f"{path.partition(':')[2]}.json").is_file())
    assert missing == [], (
        "这些被引用的方块模型既不在上游、也没有合成：\n" + "\n".join(missing[:20]))
    # 引用集合统一去掉命名空间前缀后再比（`betternether:block/x` → `block/x`）
    referenced_rel = {path.partition(":")[2] for path in referenced}
    item_models_rel = {path.partition(":")[2] for path in _item_block_models(built)}
    dead = sorted(f"block/{name}" for name in names
                  if f"block/{name}" not in referenced_rel | item_models_rel)
    assert dead == [], f"合成模型没有任何引用（死条目）：{dead[:20]}"


def _referenced_block_models(built: Path) -> set[str]:
    """方块 `state`/`states.appearances` 直接引用的 `betternether:block/<x>` 模型路径。

    只算**方块本体**的引用：物品的 `model.path` 既可以是 CE 按 `generation` 生成的
    模型（包里没有文件是正常的），也可以是这里检查过的合成模型，所以物品那一侧单独查。
    """
    referenced: set[str] = set()

    def collect(model) -> None:
        # 带 `generation` 的模型由 CE 在加载时生成，包里本来就没有文件，跳过。
        if isinstance(model, dict) and "generation" in model:
            return
        path = model.get("path") if isinstance(model, dict) else model
        if isinstance(path, str) and path.startswith(f"{NAMESPACE}:block/"):
            referenced.add(path)

    for section, _block_id, body, _ in _configs(built):
        if section != "blocks":
            continue
        state = body.get("state")
        if isinstance(state, dict):
            collect(state.get("model"))
        for appearance in ((body.get("states") or {}).get("appearances") or {}).values():
            if not isinstance(appearance, dict):
                continue
            collect(appearance.get("model"))
            for entry in appearance.get("models") or []:
                collect(entry)
    return referenced


def _item_block_models(built: Path) -> set[str]:
    """物品 `model.path` 里指向 `betternether:block/*` 的模型路径。"""
    found: set[str] = set()
    for section, _block_id, body, _ in _configs(built):
        if section != "items":
            continue
        model = body.get("model")
        path = model.get("path") if isinstance(model, dict) else model
        if isinstance(path, str) and path.startswith(f"{NAMESPACE}:block/"):
            found.add(path)
    return found


def test_no_entity_renderer_and_every_appearance_model_exists(built: Path) -> None:
    """端到端：产物里没有展示实体，且每个外观的 `model.path` 都能在包里解析。

    多状态（`states.appearances:`）与单状态（`state:`）两条路都要查：
    漏掉任何一个外观，游戏里就是「这个状态没有外观」。
    """
    models_root = _models_root(built)
    offenders: list[str] = []
    missing: list[str] = []
    checked = 0
    for section, block_id, body, _ in _configs(built):
        if section != "blocks":
            continue
        for entry in _block_sections(body):
            for key in ("entity_renderer", "transparent"):
                if key in entry:
                    offenders.append(f"{block_id}: 仍有 {key}")
            for model in _model_entries(entry):
                path = model.get("path")
                if not isinstance(path, str) or not path.startswith(f"{NAMESPACE}:"):
                    continue
                checked += 1
                # 带 `generation` 的模型由 CE 在加载时生成，包里本来没有文件。
                if "generation" in model:
                    continue
                rel = path.partition(":")[2]
                if not (models_root / f"{rel}.json").is_file():
                    missing.append(f"{block_id} -> {path}")
    assert offenders == [], "仍有展示实体残留：\n" + "\n".join(offenders[:20])
    assert checked > 300, f"只核对了 {checked} 条模型引用，判定路径可能已失效"
    assert missing == [], "这些外观指向包里不存在的模型：\n" + "\n".join(missing[:20])


def _model_paths(section: dict) -> list[str]:
    """`model:` / `models:` 里的模型路径（不区分是否带 generation）。"""
    found: list[str] = []
    single = section.get("model")
    if isinstance(single, dict) and isinstance(single.get("path"), str):
        found.append(single["path"])
    for entry in section.get("models") or []:
        if isinstance(entry, dict) and isinstance(entry.get("path"), str):
            found.append(entry["path"])
    return found


def test_no_ce_carrier_items(built: Path) -> None:
    """`_ce_*` 隐藏载体 item 只服务于 entity_renderer，改造后不该再有产出。

    它们在创造菜单里看不见，但会留在物品注册表与资源包里；确认没有别处引用后清零。
    """
    carriers = [block_id for section, block_id, _, _ in _configs(built)
                if section == "items" and "_ce_" in block_id]
    assert carriers == [], f"这些隐藏载体 item 仍被生成：{sorted(carriers)[:10]}"


def test_resource_pack_gate_passes(built: Path) -> None:
    """整包必须过 verify_assets —— 防止「改了生成器又把门禁弄红/弄绿」。

    ## 这里曾经有一处放行，现在收掉了

    改造早期 `tools/verify_assets.py` 只从 `states.appearances.<外观>.model.path`
    （**单数**）收集外观模型，`models: [{path, weight}]` 权重列表它看不见，于是
    「只有多模型外观、且包里没有同名 block 模型」的方块被误报成紫黑格子（实测 13 个）。
    当时在测试里加了一份白名单把这批误报单独放行。

    现在门禁自己认 `models:` 列表了（`appearance_models` 也收 `context[-1] == "models"`），
    白名单因此删除：**任何** failure 都让测试失败，没有例外。
    门禁这个行为由 `test_assets_gate_sees_multi_model_appearances` 单独钉住，
    免得哪天又退回「只认单数 model.path」而这里仍然全绿。
    """
    sys.path.insert(0, str(ROOT / "tools"))
    import verify_assets  # noqa: PLC0415

    report = verify_assets.verify(_ce_root(built))
    assert report.failures == [], "资源包门禁真的有失败：\n" + "\n".join(report.failures[:20])


def test_assets_gate_sees_multi_model_appearances(tmp_path: Path) -> None:
    """门禁必须把 `states.appearances.<外观>.models[].path` 当成外观来源。

    这是上一条测试的前提：单状态植物（藤蔓的双模型）全部走 `models:` 权重列表，
    如果门禁只认单数 `model.path`，这 13 个方块会被误判成紫黑格子 ——
    而当年正是为了绕开这个误判才在测试里加了白名单。现在白名单已删，
    这条负责在门禁退回旧行为时立刻失败。
    """
    sys.path.insert(0, str(ROOT / "tools"))
    import verify_assets  # noqa: PLC0415

    root = tmp_path / "craftengine" / "betternether"
    cfg = root / "configuration"
    (cfg / "blocks").mkdir(parents=True)
    models = root / "resourcepack" / "assets" / "betternether" / "models" / "block"
    models.mkdir(parents=True)
    (models / "twin_a.json").write_text('{"parent":"minecraft:block/cross"}', encoding="utf-8")
    (models / "twin_b.json").write_text('{"parent":"minecraft:block/cross"}', encoding="utf-8")
    (cfg / "blocks" / "N6-plants.yml").write_text(
        "blocks:\n"
        "  betternether:twin_vine:\n"
        "    settings:\n"
        "      template: [default:settings/solid_1x1x1]\n"
        "    states:\n"
        "      appearances:\n"
        "        bottom_false:\n"
        "          auto_state: vine\n"
        "          models:\n"
        "            - path: betternether:block/twin_a\n"
        "              weight: 1\n"
        "            - path: betternether:block/twin_b\n"
        "              weight: 1\n",
        encoding="utf-8",
    )

    # 这个最小构造缺 pack.yml、也没有 items 段，会带出无关的完整性提示 ——
    # 只看「这个方块有没有被判紫黑」，不要求整包零失败。
    def purple(rep) -> list[str]:
        return [f for f in rep.failures if "twin_vine" in f]

    assert purple(verify_assets.verify(root)) == [], (
        "门禁看不见 `models:` 权重列表里的模型，把多模型外观的方块误判成紫黑：\n"
        + "\n".join(purple(verify_assets.verify(root))))

    # 反例：把列表里的模型**全部**删掉，门禁必须立刻判红 —— 否则「认得出」就成了恒真。
    # （只删一个不判红是有意的：多模型里只要还有一个可用，方块就不是紫黑；
    #   「每个模型都在包里」由 test_no_entity_renderer_and_every_appearance_model_exists 保证。）
    (models / "twin_a.json").unlink()
    (models / "twin_b.json").unlink()
    assert purple(verify_assets.verify(root)), (
        "多模型外观里的模型全都不在包里，门禁却没报 —— 这条判定被放宽成了恒真")

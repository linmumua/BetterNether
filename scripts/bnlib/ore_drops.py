#!/usr/bin/env python3
"""矿物掉落：把上游 loot_table 的「精准采集 / 时运」规则翻成 CraftEngine 的 `loot:` 配置。

## 这个模块解决什么

上游 BetterNether 的 4 个矿物方块各有一份 loot table JSON，规则是统一的三段式：

    alternatives:
      - 命中 silk_touch>=1        -> 掉**方块本身**（精准采集）
      - 否则                      -> 掉**矿物物品**，count=uniform(min,max)，
                                     再 apply_bonus(ore_drops)（时运倍增），
                                     再 explosion_decay（爆炸衰减）

CraftEngine 内置的 `default:loot_table/ore` 模板**就是这段规则**（模板原文见 CE jar 的
`resources/default_templates/configuration/templates/loot_tables.yml`），只要求传三个参数：
`ore_block`、`ore_drop`、`ore_drop_count`。所以本模块不自己发明掉落语法，只负责：

  1. 从上游 JSON **读出**真实数值（不硬编码，上游改了自动跟随）；
  2. 拼出 blocks.py 要写进 `blocks.<id>.loot:` 的 YAML 片段；
  3. 交出矿物产物物品与烧制配方的行（由 items.py 拼进唯一的 `configuration/items/` 产物）；
  4. 补齐物品贴图与语言条目。

## `ore_drop_count: 1~3` 为什么是可靠的

模板里它落在 `set_count.count:` 这个「数字提供器」位置。模板参数在 CE 里是**字符串拼接**
（`ArgumentString$Complex` 用 StringBuilder 拼完再 re-parse），所以写进配置的最终是字符串
`"1~3"`，随后由 `NumberProviders.fromString` 解析 —— 那个方法里明确有
`findRangeSeparator` + `UniformNumberProvider` 分支，即 `a~b` 就是 uniform(a,b)。
参考工程「更好的末地」的 `ore_drop_count: 1~3` 走的是同一条路。

## 为什么不把掉落表放进 `configuration/loot/`

CE 没有独立的 loot table 配置段（jar 里搜不到任何 `loot_tables:` 段类型）；
掉落表只能作为**模板**（`templates:` 段）或内联在方块的 `loot:` 下。
内置 `default:loot_table/ore` 已经覆盖了全部需求，所以不另建掉落表文件 —— 少一层就少一处会漂。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from bnlib.namespace import NAMESPACE

# 本项目收录的 4 个矿物方块（与 blocks.py 的 ORE_NAMES 一致）。
ORE_BLOCKS = (
    "cincinnasite_ore",
    "nether_lapis_ore",
    "nether_redstone_ore",
    "nether_ruby_ore",
)

# 上游 loot table 相对上游根目录的位置。
LOOT_DIR = "src/main/generated/data/betternether/loot_table/blocks"

# 物品产物：id -> (基础原版物品, 贴图名)
#
# `material` 是 CE 要求的「底层原版物品」（项圈/护甲那些例子都是这么写的），
# 客户端外观由物品模型覆盖，所以 material 只决定堆叠数与少数组件行为，按语义挑：
# 碎片->紫水晶碎片、锭->铁锭、宝石->钻石、粉堆->青金石。
ITEMS: dict[str, tuple[str, str]] = {
    "cincinnasite": ("amethyst_shard", "cincinnasite"),
    "cincinnasite_ingot": ("iron_ingot", "cincinnasite_ingot"),
    "nether_ruby": ("diamond", "nether_ruby"),
    "lapis_pile": ("lapis_lazuli", "lapis_pile"),
}

# 上游物品模型所在的两棵树（datagen 产出 + 手写资源）。`assets.py` 会把
# `models/item/**` 原样释放进资源包，所以「上游有这个文件」==「包里已经有这个模型」。
ITEM_MODEL_DIRS = (
    "src/main/generated/assets/betternether/models/item",
    "src/main/resources/assets/betternether/models/item",
)

# 烧制配方：每个矿物方块「精准采集拿到的那块」能烧成什么。
#
# 依据分两类，**不要混为一谈**：
#   * upstream —— 上游 `recipe/<name>.json` 真有这条，数值照抄（时间/经验/产物）。
#   * added    —— 上游没有。用户的原始需求是「精准采集挖下来要可以烧制直接变成矿物」，
#                 对这个矿物同样成立，所以补一条，产物取它本来该掉的矿物物品，
#                 时间/经验沿用上游 cincinnasite 那条的值（200t/0.5xp、高炉 100t/0.5xp）。
#
# 上游只有 cincinnasite_ore 有烧制配方（→ cincinnasite_ingot，**不是** cincinnasite 碎片），
# 另外三个矿物在 `generated/data/betternether/recipe/` 里没有任何 cooking 配方。
ORE_RECIPES: dict[str, tuple[str, float, str]] = {
    # 矿物方块 -> (产物, 经验, 依据)
    "cincinnasite_ore": ("cincinnasite_ingot", 0.5, "upstream"),
    "nether_ruby_ore": ("nether_ruby", 0.5, "added"),
    "nether_lapis_ore": ("lapis_pile", 0.5, "added"),
    "nether_redstone_ore": ("minecraft:redstone", 0.5, "added"),
}

# 上游把「碎片炼成锭」也是烧制（recipe/cincinnasite_ingot_from_shard_*.json，0.5xp）。
# 不带上这条，挖矿掉出来的 cincinnasite 碎片就没有任何用途。
EXTRA_RECIPES: tuple[tuple[str, str, float, str], ...] = (
    # (原料, 产物, 经验, 依据)
    ("cincinnasite", "cincinnasite_ingot", 0.5, "upstream"),
)

SMELTING_TIME = 200
BLASTING_TIME = 100

_loot: dict[str, "OreDrop"] | None = None
# `configure()` 传进来的上游根目录：物品模型是否存在只能对着真实文件判断。
_upstream: Path | None = None


@dataclass(frozen=True)
class OreDrop:
    """上游一个矿物方块的掉落规则。"""

    block: str  # 方块（精准采集时掉它自己）
    silk_item: str  # 精准采集掉落物
    drop_item: str  # 普通挖掘掉落物
    count_min: int
    count_max: int
    fortune: bool  # 是否挂了 apply_bonus(ore_drops)
    explosion_decay: bool
    source: str  # 出处（相对上游根目录），写进报告与注释


def _children(entry: dict) -> list[dict]:
    """取出 alternatives 的子项；结构不符就当上游格式变了，直接报错而不是猜。"""
    if entry.get("type") != "minecraft:alternatives":
        raise SystemExit(
            f"上游矿物 loot table 顶层不是 alternatives，而是 {entry.get('type')!r}；"
            "本模块的解析假设已失效，请重新核对上游格式。")
    children = entry.get("children")
    if not isinstance(children, list) or len(children) != 2:
        raise SystemExit(f"上游 alternatives 子项不是 2 个（实际 {children!r}），无法区分精准采集分支。")
    return children


def _is_silk_touch(child: dict) -> bool:
    """这个子项是不是「精准采集」分支？看 condition 里有没有 silk_touch。"""
    blob = json.dumps(child.get("condition", {}), ensure_ascii=False)
    return "silk_touch" in blob


def _uniform_range(child: dict) -> tuple[int, int]:
    """从 set_count 的 modifier 里取出 uniform(min,max)；没有就是 1。"""
    for modifier in child.get("modifier", []) or []:
        if modifier.get("type") != "minecraft:set_count":
            continue
        count = modifier.get("count")
        if not isinstance(count, dict):
            continue
        low = int(count.get("min", 1))
        high = int(count.get("max", low))
        return low, high
    return 1, 1


def _has_fortune(child: dict) -> bool:
    for modifier in child.get("modifier", []) or []:
        if modifier.get("type") == "minecraft:apply_bonus" and "fortune" in json.dumps(modifier):
            return True
    return False


def _has_explosion_decay(child: dict) -> bool:
    return any(
        modifier.get("type") == "minecraft:explosion_decay"
        for modifier in (child.get("modifier", []) or [])
    )


def configure(upstream: Path) -> dict[str, OreDrop]:
    """读上游 4 份 loot table，建立 name -> OreDrop 映射（幂等，重复调用只解析一次）。"""
    global _loot, _upstream
    _upstream = upstream
    base = upstream / LOOT_DIR
    parsed: dict[str, OreDrop] = {}

    for name in ORE_BLOCKS:
        path = base / f"{name}.json"
        if not path.is_file():
            raise SystemExit(f"找不到上游矿物掉落表：{path}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        pools = payload.get("pools") or []
        if not pools:
            raise SystemExit(f"{path.name}: pools 为空，上游格式与预期不符。")
        entry = pools[0]["entries"][0]
        silk_child = drop_child = None
        for child in _children(entry):
            if _is_silk_touch(child):
                silk_child = child
            else:
                drop_child = child
        if silk_child is None or drop_child is None:
            raise SystemExit(f"{path.name}: 没能分出精准采集/普通分支，拒绝生成一份掉落装错的矿物配置。")

        low, high = _uniform_range(drop_child)
        parsed[name] = OreDrop(
            block=f"{NAMESPACE}:{name}",
            silk_item=silk_child.get("name", ""),
            drop_item=drop_child.get("name", ""),
            count_min=low,
            count_max=high,
            fortune=_has_fortune(drop_child),
            explosion_decay=_has_explosion_decay(drop_child),
            source=f"{LOOT_DIR}/{name}.json",
        )

    _loot = parsed
    return parsed


def loot() -> dict[str, OreDrop]:
    if _loot is None:
        raise SystemExit("ore_drops.loot() 在 configure(upstream) 之前被调用。")
    return _loot


def _loot_body(name: str, indent: str) -> list[str]:
    """`template:` + `arguments:` 那几行的通用生成器（不含外层 `loot:`）。"""
    table = loot()
    if name not in table:
        # 非矿物：掉自己。与改动前的行为完全一致。
        return [f"{indent}template: default:loot_table/self"]

    record = table[name]
    if record.silk_item != record.block:
        raise SystemExit(
            f"{name}: 上游精准采集分支掉的是 {record.silk_item}，不是方块本身；"
            "内置 ore 模板只会掉 ore_block，本模块的假设已失效。")
    if not record.fortune:
        raise SystemExit(f"{name}: 上游掉落表没有 apply_bonus(fortune)，但我按有时运生成，拒绝悄悄改语义。")

    lines = [
        f"{indent}template: default:loot_table/ore",
        f"{indent}arguments:",
        f"{indent}  ore_block: {record.block}",
        f"{indent}  ore_drop: {record.drop_item}",
    ]
    # 只有真的不是固定 1 个时才写 count，避免给「本来就没范围」的矿物添噪。
    if (record.count_min, record.count_max) != (1, 1):
        lines.append(f"{indent}  ore_drop_count: {record.count_min}~{record.count_max}")
    return lines


def loot_snippet(name: str, indent: str = "    ") -> str:
    """`blocks.<id>:` 下完整的 `loot:` 段（含外层 `loot:` 行），供 blocks.py 整段拼接。"""
    return "\n".join([f"{indent}loot:", *_loot_body(name, indent + "  ")]) + "\n"


def loot_body_snippet(name: str, indent: str = "      ") -> str:
    """只有 `template:` / `arguments:` 两截（外层 `loot:` 行已存在时用），缩进由调用方给。"""
    return "\n".join(_loot_body(name, indent)) + "\n"


# ------------------------------------------------------------------ 物品与配方


def _released_item_model(name: str) -> str | None:
    """上游已有 `models/item/<name>.json` 时返回它该被引用的模型 id。

    ## 为什么有模型就必须写 `model:` 而不是 `texture:`
    `texture:` 是 CE 的「单张贴图 → 自动生成同名模型」写法；`assets.py` 已经把上游那份
    `models/item/<name>.json` 原样释放到**同一个 path**，两边撞车：CE 会对已存在的模型文件
    报错或覆盖掉上游那份（上游是 `parent: item/generated` + `layer0`，本来就是物品图标）。
    改成 `model.path` 只引用不生成，`items` 段不落任何 `generation`。

    没有这个文件（上游物品图标另有来源）才继续用 `texture:` 让 CE 生成。
    """
    if _upstream is None:
        raise SystemExit("ore_drops._released_item_model() 在 configure(upstream) 之前被调用。")
    for relative in ITEM_MODEL_DIRS:
        if (_upstream / relative / f"{name}.json").is_file():
            return f"{NAMESPACE}:item/{name}"
    return None


def _item_block(item_id: str, material: str, texture: str) -> str:
    """一个矿物产物物品。

    形状对齐参考工程（更好的末地 `b7/part`）：`data.item_name` + 物品模型。
    `material:` 给的是底层原版物品，决定堆叠数等基础组件；参考工程的纯材料物品并不写它，
    写上只为让「碎片/锭/宝石/粉堆」的堆叠与手感落到合理的原版物品上。
    `category:` 与生成的方块物品一致，玩家能在 CE 浏览器「石头」页里找到它们。

    图标来源按「包里有没有这个模型文件」二选一，见 {@link _released_item_model}。
    """
    model_ref = _released_item_model(texture)
    look = (f"    model:\n      path: {model_ref}\n" if model_ref
            else f"    texture: {NAMESPACE}:item/{texture}\n")
    return (
        f"  {NAMESPACE}:{item_id}:\n"
        f"    material: {material}\n"
        f"    category: {NAMESPACE}:stones\n"
        f"    data:\n"
        f"      item_name: '<!i><white><lang:item.{NAMESPACE}.{item_id}></white>'\n"
        + look
    )


def _recipe_block(key: str, kind: str, ingredient: str, result: str,
                  experience: float, time: int, origin: str, result_count: int = 1) -> str:
    note = "上游 recipe 有据" if origin == "upstream" else "上游无此配方，按用户需求补充"
    return (
        f"  {NAMESPACE}:{key}:\n"
        f"    type: {kind}\n"
        f"    category: misc\n"
        f"    time: {time}\n"
        f"    experience: {experience}\n"
        f"    ingredient: {ingredient if ':' in ingredient else f'{NAMESPACE}:{ingredient}'}\n"
        f"    result:\n"
        f"      id: {result if ':' in result else f'{NAMESPACE}:{result}'}\n"
        f"      count: {result_count}\n"
    )


def items_sections(upstream: Path) -> tuple[list[str], list[str]]:
    """矿物产物物品与烧制配方的行：(`items:` 段正文, `recipes:` 段正文)。

    本模块**不落盘**：`configuration/items/` 只允许一个 yml，由 items.py 统一拼装。
    两边的物品必须并进**同一个** `items:` 顶层映射（YAML 不允许重复顶层键，
    第二段 `items:` 会把第一段整段覆盖掉），所以这里只交出自己的行。
    """
    configure(upstream)
    table = loot()

    items = [_item_block(item_id, material, texture)
             for item_id, (material, texture) in sorted(ITEMS.items())]

    recipes: list[str] = []
    for name in sorted(ORE_BLOCKS):
        result, experience, origin = ORE_RECIPES[name]
        record = table[name]
        recipe_note = (
            f"  # {name} -> {result}；{origin}。挖掘掉落为 {record.drop_item} "
            f"x{record.count_min}~{record.count_max}，时运按 ore_drops 倍增。\n"
        )
        recipes.append(recipe_note)
        recipes.append(_recipe_block(
            f"{name}_smelting", "smelting", name, result,
            experience, SMELTING_TIME, origin))
        recipes.append(_recipe_block(
            f"{name}_blasting", "blasting", name, result,
            experience, BLASTING_TIME, origin))

    for ingredient, result, experience, origin in EXTRA_RECIPES:
        recipes.append(f"  # {ingredient} -> {result}（{origin}：碎片炼成锭）\n")
        recipes.append(_recipe_block(
            f"{ingredient}_to_{result}_smelting", "smelting", ingredient, result,
            experience, SMELTING_TIME, origin))
        recipes.append(_recipe_block(
            f"{ingredient}_to_{result}_blasting", "blasting", ingredient, result,
            experience, BLASTING_TIME, origin))

    return items, recipes


def lang_entries(upstream_lang: dict[str, str]) -> dict[str, str]:
    """物品名语言条目，键用本移植的命名空间（CE 按 `<lang:item.betternether.x>` 查）。"""
    entries: dict[str, str] = {}
    missing: list[str] = []
    for item_id in ITEMS:
        key = f"item.betternether.{item_id}"
        value = upstream_lang.get(key)
        if value is None:
            missing.append(item_id)
            continue
        entries[f"item.{NAMESPACE}.{item_id}"] = value
    if missing:
        raise SystemExit(f"上游语言文件缺这些物品名：{missing}；拒绝让游戏里显示裸 id。")
    return entries


def texture_files() -> list[str]:
    """需要随包释放的物品贴图名（不含 .png）。"""
    return sorted({texture for _, texture in ITEMS.values()})


def copy_item_textures(assets_src: Path, dest_root: Path) -> int:
    """把物品贴图拷进资源包，返回拷贝数量。

    物品外观是**硬引用**（`items.<id>.texture:`），少一张就是背包里的紫黑格子，
    而 verify_assets.py 只扫 `configuration/blocks/`，看不见 items/ 下的引用，
    所以这里必须自己保证拷到，并让调用方对 0 张直接失败。
    """
    src = assets_src / "textures/item"
    if not src.is_dir():
        raise SystemExit(f"找不到上游物品贴图目录：{src}")
    copied = 0
    for texture in texture_files():
        source = src / f"{texture}.png"
        if not source.is_file():
            raise SystemExit(f"缺物品贴图：{source}")
        target = dest_root / "textures/item" / f"{texture}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        copied += 1
        meta = source.with_name(source.name + ".mcmeta")
        if meta.is_file():
            target.with_name(target.name + ".mcmeta").write_bytes(meta.read_bytes())
    return copied


def item_ids() -> tuple[str, ...]:
    """本模块负责的物品 id（`items:` 段里的键），供 items.py 做配方闭合判据。

    `configuration/items/` 现在只有一个 yml，自己不再单独落盘；这些 id 不再靠
    「另一个文件已写进 ce_root」被动可见，必须由调用方显式当已知 id 传下去。
    """
    return tuple(f"{NAMESPACE}:{item_id}" for item_id in sorted(ITEMS))


def assert_references(known_block_ids: list[str]) -> None:
    """自检：配方/掉落的每个 id 都必须是「本包定义过的物品/方块」或原版物品。

    CE 对「原料/产物 id 不存在」是**硬报错**（参考工程记录过
    AbstractRecipeSerializer.parseIngredient 的行为），所以宁可构建期就炸。
    """
    ours = {f"{NAMESPACE}:{item_id}" for item_id in ITEMS}
    known = set(known_block_ids) | ours
    problems: list[str] = []

    for name, record in loot().items():
        for referenced in (record.block, record.silk_item, record.drop_item):
            if referenced.startswith(f"{NAMESPACE}:") and referenced not in known:
                problems.append(f"{name}: 掉落引用了不存在的 {referenced}")
    for name, (result, _, _) in ORE_RECIPES.items():
        if result.startswith(f"{NAMESPACE}:") and result not in known:
            problems.append(f"{name}: 烧制产物 {result} 不存在")
    for ingredient, result, _, _ in EXTRA_RECIPES:
        for referenced in (f"{NAMESPACE}:{ingredient}", result if ":" in result else f"{NAMESPACE}:{result}"):
            if referenced.startswith(f"{NAMESPACE}:") and referenced not in known:
                problems.append(f"{ingredient}: 配方引用了不存在的 {referenced}")

    if problems:
        raise SystemExit("矿物掉落自检失败：\n  " + "\n  ".join(problems))


def report() -> str:
    """构建日志用的一行摘要。"""
    table = loot()
    parts = []
    for name in sorted(table):
        record = table[name]
        parts.append(
            f"{name}: 掉 {record.drop_item} x{record.count_min}~{record.count_max}"
            f"{'+时运' if record.fortune else ''}，精准采集掉方块")
    return "；".join(parts)


def patch_generated_blocks(ce_root: Path) -> str:
    """把生成出来的 `blocks/*.yml` 里矿物的 `loot:` 从「掉自己」换成 ore 模板。

    ## 为什么需要这一步

    `blocks.py` 目前对**所有**方块写死 `loot: {template: default:loot_table/self}`，
    所以矿物挖下来掉的是方块本身，没有矿物物品、没有时运、精准采集也没意义。
    正确做法是让 `blocks.py` 自己按矿物分支生成（补丁说明见交付报告），但那份文件由另一个
    改动独占，本模块不能直接改它。

    所以在 `blocks.py` 跑完之后补这一刀：**产物由脚本生成**，重复运行结果一致
    （替换后的内容里不再有 `default:loot_table/self`，第二次跑什么也找不到）。

    ## 什么时候它自己会失效

    一旦 `blocks.py` 自己按矿物写出了 ore 模板（或任何非 `self` 的掉落），这里就什么都不做
    —— 判据是「这个矿物的 loot 段是否仍然等于掉自己」，而不是「有没有出现某个固定字符串」，
    这样别人换了实现方式也不会被这条兼容层误判成失败。

    只有「根本找不到矿物的 loot 段」才报错：那说明 blocks.py 的输出格式变了，
    这时静默放过等于把「挖矿不掉矿物」带进产物。
    """
    blocks_dir = ce_root / "configuration/blocks"
    if not blocks_dir.is_dir():
        raise SystemExit(f"找不到生成的方块配置目录：{blocks_dir}")

    wanted = set(loot().keys())
    patched = 0
    seen: set[str] = set()
    details: list[str] = []

    for path in sorted(blocks_dir.glob("*.yml")):
        raw = path.read_text(encoding="utf-8")
        if "default:loot_table/self" not in raw and not any(f":{n}:" in raw for n in wanted):
            continue

        lines = raw.splitlines(keepends=True)
        output: list[str] = []
        section: str | None = None
        current: str | None = None
        index = 0
        while index < len(lines):
            line = lines[index]
            stripped = line.strip()
            indent = len(line) - len(line.lstrip())

            if stripped and not stripped.startswith("#"):
                if indent == 0:
                    section = stripped.split(":", 1)[0].strip()
                    current = None
                elif section == "blocks" and indent == 2 and stripped.endswith(":"):
                    current = stripped[:-1].strip()
                    if current.split(":", 1)[-1] in wanted:
                        seen.add(current.split(":", 1)[-1])

            # 命中「矿物 + loot 段第一行就是掉自己」时，整段替换。
            if (section == "blocks" and current is not None
                    and current.split(":", 1)[-1] in wanted
                    and stripped == "template: default:loot_table/self"
                    and indent == 6):
                name = current.split(":", 1)[-1]
                details.append(f"{path.name}: {name}")
                output.append(loot_body_snippet(name, indent="      "))
                patched += 1
                index += 1
                continue

            output.append(line)
            index += 1

        new = "".join(output)
        if new != raw:
            path.write_text(new, encoding="utf-8")

    missing = wanted - seen
    if missing:
        raise SystemExit(
            f"在生成的 blocks/*.yml 里没找到这些矿物的方块段：{sorted(missing)}；"
            "blocks.py 的输出格式可能变了，拒绝产出一份「挖矿掉方块」的配置。")

    if patched:
        return f"补写 {patched} 个矿物的 loot：" + "、".join(details)
    return "矿物 loot 已由 blocks.py 直接生成，本步未改动"


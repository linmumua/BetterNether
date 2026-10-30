#!/usr/bin/env python3
"""资源包自洽性校验：CE 配置、模型 JSON、贴图文件三者必须互相对得上。

用法：
    python tools/verify_assets.py <jar 路径或 CE 资源包目录>

    python tools/verify_assets.py jars/BetterNether-0.1.0-folia26.3.jar
    python tools/verify_assets.py build/resources/plugin/craftengine/betternether

纯文件读取：不需要服务端、不需要 CraftEngine、不需要 Java。任何一条不满足就非零退出。
`verify_jar.py` 直接 import 本模块（`verify()`），所以不存在两份实现漂移。

门禁清单（每条都对应一个真实事故面）：

  1. CE 配置里每个 `texture:` 都指向存在的 PNG。
     事故 1 的配置侧：`state.texture` 写了一张不存在的图，CE 启动时刷
     「缺少纹理」，游戏内是紫黑格子，而日志里只是一堆 WARN。

  2. 每个模型 JSON 引用的贴图文件都存在。
     事故 1 的模型侧：上游有 130 个方块的模型是 Java 运行期生成的，磁盘上没有 JSON；
     另有一批方块的贴图名与方块名不同（如方块 `anchor_tree_bark` 的图叫
     `anchor_tree_log_side`）。按方块名硬拼贴图路径必然缺图。

  3. 模型内不得有循环纹理引用（含自引用 `#x -> #x`）。
     事故 2：上游 Cubik Studio 导出的半成品里有 `"particle": "#particle"`，
     CE 报「在 textures.particle 存在循环纹理引用」，模型直接不可用。

  4. 每个方块都有可渲染的外观。四条来源任一成立即可：同名模型文件、`state.texture`
     （含 `generation.textures.*` 槽位）能落到真图、`state.model` / `states.appearances.*.model`
     指向的模型存在（包内有文件，或带 `generation` 由 CE 生成）、
     `state.entity_renderer.item` 指向的物品模型存在。
     这条同时兜住事故 3 的反面：外观来源与 CE 自动生成二者至少要有一个成立，
     否则方块没有外观。事故 3 本身（自动生成撞上已存在的模型文件）由
     `scripts/bnlib/blocks.py` 只释放上游原样文件来避免，门禁在此只守「不能什么都没有」。

  5. `pack.yml` 存在，且 `namespace` 与目录名一致。

  6. 物品配置的 `model:` 必须指向存在的模型文件，或该路径带 `generation`（CE 会生成）。
     `verify_jar.py` 也复用它。

第 4/6 条不能只认「同名模型文件」：本移植的方块模型普遍由 CE 按 `state.model.generation`
生成，异形族把模型拆到 `states.appearances`，溢出方块用 `entity_renderer` 借物品模型渲染 ——
三种写法的模型都不叫 `block/<方块名>`。硬编码那个路径会制造大批假失败（实测 66 项），
而放宽成「写了 entity_renderer 就算有」又会放过真正的缺口（`smoker` 的物品模型曾被
`shadowed-models` 名单连带排除，方块与物品同时失去外观）。

外部引用（`minecraft:` 或无命名空间）的点：本包不提供原版贴图，无法逐张校验，
只验它的目录形态是否可能属于原版（`textures/block`、`textures/item` ……）；
目录形态都不对的（例如写成 `block` 的复数 `blocks/mushroom_red_new`）直接判失败——
那种引用在包内和原版都取不到图，必然是紫黑格子。
"""

from __future__ import annotations

import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

# jar 里 CE 资源包的根；目录输入时被校验的就是这个目录本身。
JAR_PACK_PREFIX = "craftengine/betternether/"
PACK_DIR_NAME = "betternether"

ASSETS = "resourcepack/assets"
MODELS_DIRNAME = "models"
TEXTURES_DIRNAME = "textures"

# 原版 assets/minecraft/textures/ 下的贴图根目录。外部引用只允许落在这些目录下，
# 否则它既不在本包、也不可能在原版里取到，运行期必然缺图。
VANILLA_TEXTURE_ROOTS = frozenset({
    "block", "item", "entity", "environment", "gui", "particle", "misc",
    "painting", "mob_effect", "effect", "font", "colormap", "map", "trims",
    "equipment",
})


# ---------------------------------------------------------------- 文件视图


class ZipSource:
    """把 jar 里的 `craftengine/betternether/**` 当成包根。"""

    def __init__(self, jar: Path) -> None:
        self.label = str(jar)
        self.directory_name = PACK_DIR_NAME
        self._zip = zipfile.ZipFile(jar)
        self.files = {
            name[len(JAR_PACK_PREFIX):]
            for name in self._zip.namelist()
            if name.startswith(JAR_PACK_PREFIX) and not name.endswith("/")
        }

    def exists(self, path: str) -> bool:
        return path in self.files

    def read(self, path: str) -> str:
        with self._zip.open(JAR_PACK_PREFIX + path) as handle:
            return handle.read().decode("utf-8")

    def read_bytes(self, path: str) -> bytes:
        with self._zip.open(JAR_PACK_PREFIX + path) as handle:
            return handle.read()

    def list(self, prefix: str) -> list[str]:
        return sorted(name for name in self.files if name.startswith(prefix))

    def close(self) -> None:
        self._zip.close()


class DirSource:
    """直接读磁盘上的 CE 资源包目录。

    允许传包根（含 `pack.yml`），也容忍传 `craftengine/betternether` 的上级
    （例如 `build/resources/plugin`），后者会自动下探一层。
    """

    def __init__(self, root: Path) -> None:
        base = root
        if not (base / "pack.yml").is_file():
            nested = base / JAR_PACK_PREFIX.rstrip("/")
            if (nested / "pack.yml").is_file():
                base = nested
        self.root = base
        self.label = str(base)
        self.directory_name = base.name
        self.files = {
            path.relative_to(base).as_posix()
            for path in base.rglob("*")
            if path.is_file()
        }

    def exists(self, path: str) -> bool:
        return path in self.files

    def read(self, path: str) -> str:
        return (self.root / path).read_text(encoding="utf-8")

    def read_bytes(self, path: str) -> bytes:
        return (self.root / path).read_bytes()

    def list(self, prefix: str) -> list[str]:
        return sorted(name for name in self.files if name.startswith(prefix))

    def close(self) -> None:
        return


def open_source(target: Path):
    if target.is_dir():
        return DirSource(target)
    return ZipSource(target)


# ---------------------------------------------------------------- 报告


@dataclass
class Report:
    blocks: int = 0
    items: int = 0
    recipes: int = 0
    categories: int = 0
    models: int = 0
    textures: int = 0
    external_refs: int = 0
    dangling_aliases: int = 0
    template_lines: int = 0
    templated_entries: int = 0
    id_refs: int = 0
    animations: int = 0
    failures: list[str] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    # CE 会按 `generation:` 生成的模型（相对 `block/` 或 `item/` 的路径）。
    # 这些模型在包里**没有文件**，但运行期存在 —— 判断「有没有外观」必须认它。
    generated_models: set[str] = field(default_factory=set)

    def fail(self, message: str) -> None:
        self.failures.append(message)


@dataclass
class ConfigEntry:
    """`configuration/**/*.yml` 里某个段下的一个条目。"""

    section: str
    block_id: str
    file: str
    line: int
    textures: list[tuple[str, int]] = field(default_factory=list)
    model: tuple[str, int] | None = None
    # 条目里有被跳过的 `${...}` 模板行：它的外观无法静态判定，只能报为「未校验」。
    templated: bool = False
    # ---- 外观来源（除 `texture:` 之外的两种 CE 机制） --------------------- #
    #
    # 只按 `block/<方块名>` 找模型会把下面两类正常方块判成「没有外观」：
    #
    #   * `state.entity_renderer.item` —— 溢出方块共用被清空模型的原版状态
    #     （`id: transparent`），外观由 item_display 渲染**物品模型**；
    #   * `states.appearances.*.model` —— 门/栅栏/墙/窗格/半砖等异形族按外观分模型
    #     （如门有 bottom_left/top_right 四份），模型名与方块名并不相同。
    state_model: tuple[str, int] | None = None
    entity_renderer_item: tuple[str, int] | None = None
    appearance_models: list[tuple[str, int]] = field(default_factory=list)


@dataclass
class IdRef:
    """一条 `ns:path` 形式的 id 引用，以及它「必须指向哪一类声明」。"""

    kind: str          # category / block / item / recipe / loot / entry
    value: str
    file: str
    line: int
    owner: str         # 发出这条引用的条目 id（排障用）


# ---------------------------------------------------------------- 解析


# 贴图/模型引用没写命名空间时，Minecraft 的 ResourceLocation 默认落到原版命名空间。
# 上游 BetterNether 的模型里 `block/netherrack` 就是这么写的（意思是原版的 netherrack），
# 按「本包」解析会把一堆正常模型判成缺图。
DEFAULT_REF_NAMESPACE = "minecraft"


def split_ref(value: str, default_namespace: str = DEFAULT_REF_NAMESPACE) -> tuple[str, str]:
    """`ns:path` -> (ns, path)；没有命名空间时按 default_namespace 解析。"""
    if ":" in value:
        namespace, path = value.split(":", 1)
        return namespace, path
    return default_namespace, value


def texture_status(source, namespace: str, value: str) -> str:
    """一张贴图引用在本包里的状态：pack（包内有图）/ external（原版图）/ missing。"""
    ref_namespace, path = split_ref(value)
    if source.exists(f"{ASSETS}/{ref_namespace}/{TEXTURES_DIRNAME}/{path}.png"):
        return "pack"
    # CE 有时按包内路径解析外部命名空间，命中即视为可用。
    if ref_namespace != namespace and source.exists(
        f"{ASSETS}/{namespace}/{TEXTURES_DIRNAME}/{path}.png"
    ):
        return "pack"
    root = path.split("/", 1)[0]
    if ref_namespace != namespace and root in VANILLA_TEXTURE_ROOTS:
        return "external"
    return "missing"


def model_path_of(namespace: str, relative: str) -> str:
    return f"{ASSETS}/{namespace}/{MODELS_DIRNAME}/{relative}.json"


def texture_path_of(namespace: str, relative: str) -> str:
    """贴图文件路径。`relative` 形如 `block/xxx` 或 `xxx`。"""
    if not relative.startswith("block/") and not relative.startswith("item/"):
        relative = f"block/{relative}"
    return f"{ASSETS}/{namespace}/textures/{relative}.png"


def parse_configs(source, report: Report) -> list[ConfigEntry]:
    """扫描 `configuration/` 下的配置，取出**外观从哪里来**的全部信息。

    不引入 YAML 依赖，只按缩进维护一个「容器键栈」，从而还原每行所处的键路径
    （`state.model.path`、`states.appearances.<外观>.model.path`、`state.entity_renderer.item`…）。
    含 `${var}` 的模板行（config_factory）跳过并计数。

    ## 为什么要连 `configuration/items/` 一起扫

    物品并不都在 `blocks/*.yml` 的 `items:` 段里：矿物产物物品与烧制配方写在
    `configuration/items/bn-items.yml`（与普通物品同一个文件）。只扫 blocks 目录会把它们的
    `texture:` 与 `model:` 全部漏掉 —— 门禁看起来通过，实际有整批物品没被校验过。

    ## 为什么不能只按缩进猜「这一行是贴图还是模型」

    两种写法都存在：

        model: betternether:block/x        # 简写，直接给模型 id
        model:
          path: betternether:block/x       # 完整写法，CE 的 model 段还带 generation

    只认第一种时，第二种的 `model:` 因为值为空而被跳过，`path:` 又不是 `texture`/`model`
    键 —— 结果整批物品的模型引用一次都没被核对。

    而且 `state` / `states` 都是多层嵌套（`states.appearances.<外观>.model.generation.textures.<槽位>`），
    只看「离 `textures:` 多近」会把异形族的每个外观、以及 `entity_renderer` 整个漏掉 ——
    那正是「方块明明有外观却被判成紫黑格子」的来源。

    ## `generated_models` 记的是什么

    `path:` 后面紧跟 `generation:` 的模型由 CraftEngine 在运行期生成（方块段、物品段都支持，
    `BaseItemModel` 实现 `ModelGenerationHolder`）。这些模型包里没有文件，运行期存在。
    把它们记下来，才能判断「引用它的方块/物品到底有没有外观」。
    """
    entries: list[ConfigEntry] = []
    files = [
        name for name in source.files
        if name.startswith("configuration/") and name.endswith(".yml")
    ]
    for path in sorted(files):
        section: str | None = None
        current: ConfigEntry | None = None
        # 条目内部的容器键栈：[(缩进, 键名)]，如 [("state"), ("model")]。
        containers: list[tuple[int, str]] = []
        # 当前条目里最近一条模型路径。`generation:` 紧跟在它的 `path:` 之后。
        last_model: str | None = None
        for lineno, line in enumerate(source.read(path).splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            indent = len(line) - len(line.lstrip())
            if "${" in line:
                # 含模板变量的行不做静态判定，只计数；它的条目记为「无法静态校验」。
                report.template_lines += 1
                if indent > 0 and current is not None:
                    current.templated = True
                continue
            if indent == 0:
                section = stripped.split(":", 1)[0].strip()
                current = None
                containers = []
                last_model = None
                continue
            if section not in ("blocks", "items"):
                continue
            if indent == 2:
                current = ConfigEntry(
                    section=section,
                    block_id=stripped.rstrip(":").strip(),
                    file=path,
                    line=lineno,
                )
                entries.append(current)
                containers = []
                last_model = None
                continue
            if current is None:
                continue
            # 回到更浅的层级时弹掉已结束的容器，context 就是这一行的键路径。
            while containers and containers[-1][0] >= indent:
                containers.pop()
            context = [name for _, name in containers]
            key, separator, raw = stripped.partition(":")
            if not separator:
                continue
            key = key.strip()
            value = raw.strip().strip("'\"")
            if not value:
                # 无值 = 开启一个子块。`generation:` 与它的 `path:` 成对出现，
                # 表示这个模型交给 CE 生成（方块段和物品段都支持）。
                if key == "generation" and last_model is not None:
                    report.generated_models.add(split_ref(last_model)[1])
                containers.append((indent, key))
                continue
            # `textures:` 段里的**任意**槽位名都是外观来源。槽位名由父模型决定
            # （`cube_all` 用 `all`、`cube_column` 用 `side`/`end`、`cube_bottom_top` 用
            # top/side/bottom、`cubik` 风格模型用 front/body/particle……），不能写死。
            # 只认 `all` 会把原木、地表方块的贴图当成「不存在」，从而误判成紫黑方块。
            # 这条同时也覆盖了 `generation.textures.<槽位>`（让 CE 生成模型的那批）。
            if context and context[-1] == "textures":
                current.textures.append((value, lineno))
                continue
            if key == "texture":
                current.textures.append((value, lineno))
                continue
            if context[-1:] == ["state"] and key == "model":
                # `state: model: ns:block/x` —— 简写形态。
                current.state_model = (value, lineno)
                last_model = value
                continue
            if context[-2:] == ["state", "model"] and key == "path":
                current.state_model = (value, lineno)
                last_model = value
                continue
            if (
                len(context) >= 2
                and context[-2] == "state"
                and context[-1] == "models"
                and key in ("- path", "path")
            ):
                # 单状态多模型：`state.models[- {path: …, weight: …, y: …}]`。
                # 与下面的 `states.appearances.<外观>.models` 是两回事 —— 前者是**一个**状态下
                # 随机挑一个模型（藤蔓、壁生苔藓、骨蘑菇这类贴墙植物走这条），后者是按状态分组。
                # 只认 appearances 会把这一整类方块误判成紫黑方块：门禁报「既没有模型文件」，
                # 而产物里 <方块名>_1.._n 这些变体模型其实都在。
                current.appearance_models.append((value, lineno))
                last_model = value
                continue
            if context[-1:] == ["entity_renderer"] and key == "item":
                # 溢出方块的外观来自 item_display 渲染的物品模型。
                current.entity_renderer_item = (value, lineno)
                continue
            if (
                len(context) >= 4
                and context[0] in ("state", "states")
                and context[1] == "appearances"
                and context[-1] == "model"
                and key == "path"
            ):
                # 异形族：`states.appearances.<外观>.model.path`，模型名与方块名无关。
                current.appearance_models.append((value, lineno))
                last_model = value
                continue
            if (
                len(context) >= 4
                and context[0] in ("state", "states")
                and context[1] == "appearances"
                and context[-1] == "models"
                and key in ("- path", "path")
            ):
                # 异形族的多模型形态：`states.appearances.<外观>.models[- {path: …, weight: …}]`。
                # 列表项的键被扫成 `- path`（`key.strip()` 保留前导 `- `），所以两种写法都认。
                # 只认单数 `model.path` 会把这类方块误判成紫黑 —— 实测单状态植物（藤蔓的
                # 双模型）全走这条路径。
                current.appearance_models.append((value, lineno))
                last_model = value
                continue
            if key == "path" and context[-1:] == ["model"]:
                current.model = (value, lineno)
                last_model = value
                continue
            if key == "model" and not context:
                current.model = (value, lineno)
                last_model = value
                continue

    if not files:
        report.fail("没有找到 configuration/ 下的 yml，校验路径可能已失效。")
    return entries


# ---------------------------------------------------------------- ID 闭合
#
# 这一段的判据全部来自**产物本身**：先把包里声明的 id 解析成索引，再核对每条引用能否
# 落到索引里。刻意不从生成器借任何清单/常量 —— 否则生成器写错一个 id，门禁会用同一份
# 错误期待值把错误判成正确。
#
# 会声明 id 的顶层段。CE 每个段是独立的 id 空间，跨段引用必须落到对应段里。
DECLARING_SECTIONS = ("blocks", "items", "recipes", "categories", "loot", "equipment", "entities")

# 这些命名空间由别处提供（`default:` 是 CraftEngine 内置模板，`minecraft:` 是原版），
# 本包不声明它们，也就无从逐条核对。
EXTERNAL_ID_NAMESPACES = frozenset({"minecraft", "default"})

# 一个 CE id 的形状：`namespace:path`，两侧都只允许小写字母、数字与 `_ . - /`。
#
# 必须严格：YAML 里 `- type: damage_item` 这种**列表项**本身就是 `键: 值`，宽松的
# 「含冒号就当 id」会把函数名（damage_item、cancel_event）和 MiniMessage 串
# （`<!i><gray><l10n:category.betternether.lore>`）全部当成 id，门禁会刷出几十条
# 假失败，然后把真正的悬空引用埋掉。
ID_SHAPE = re.compile(r"^[a-z0-9_.\-]+:[a-z0-9_./\-]+$")


def looks_like_id(value: str) -> bool:
    return bool(ID_SHAPE.match(value))


def has_double_namespace(value: str) -> bool:
    """引用里是否出现了**第二个**命名空间分隔符。

    ## 为什么要单独判这一条

    实测事故：生成器给原版贴图套上了自家命名空间，产出

        all: betternether:block/minecraft:block/netherrack

    它按 `ns:path` 解析出来是 `betternether` + `block/minecraft:block/netherrack`，
    路径里还带着一个冒号 —— 永远不可能命中任何文件。

    只报「包内缺少 xxx.png」会把排障方向引到「是不是少拷了一张图」，而真正的原因是
    生成器拼接 id 时重复加了前缀。这条判据把错误直接指到根因上。
    """
    if ":" not in value:
        return False
    return ":" in value.split(":", 1)[1]


def parse_ids(source, report: Report) -> tuple[dict[str, dict[str, str]], list[IdRef]]:
    """把 `configuration/**/*.yml` 解析成 (声明索引, 引用列表)。

    只做缩进级别的最小解析：顶层段 → 两空格缩进的条目 id → 条目内的键值。
    声明索引是 `段名 -> {id: 文件}`，引用列表记录「哪个条目的哪个键引用了哪个 id」。
    """
    declared: dict[str, dict[str, str]] = {}
    refs: list[IdRef] = []
    files = [
        name for name in source.files
        if name.startswith("configuration/") and name.endswith(".yml")
    ]
    if not files:
        report.fail("没有找到 configuration/ 下的 yml，ID 闭合校验路径可能已失效。")

    for path in sorted(files):
        section: str | None = None
        owner: str | None = None
        model_indent: int | None = None
        result_indent: int | None = None
        list_indent: int | None = None
        tags_indent: int | None = None
        for lineno, line in enumerate(source.read(path).splitlines(), 1):
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if "${" in line:
                continue
            indent = len(line) - len(line.lstrip())

            if indent == 0:
                section = stripped.split(":", 1)[0].strip()
                owner = None
                model_indent = result_indent = list_indent = tags_indent = None
                continue
            if section not in DECLARING_SECTIONS:
                continue
            if indent == 2:
                owner = stripped.rstrip(":").strip()
                declared.setdefault(section, {})[owner] = path
                model_indent = result_indent = list_indent = tags_indent = None
                continue
            if owner is None:
                continue

            # 离开子块就把缩进标记清掉，避免把后面的同名字段误认成子块内容。
            if model_indent is not None and indent <= model_indent:
                model_indent = None
            if result_indent is not None and indent <= result_indent:
                result_indent = None
            if list_indent is not None and indent <= list_indent and not stripped.startswith("- "):
                list_indent = None
            if tags_indent is not None and indent <= tags_indent and not stripped.startswith("- "):
                tags_indent = None

            # 列表项：`list: ['#ns:cat']` 与 `- '#ns:cat'` 两种写法都要认。
            # 但 `- type: damage_item` 这种「列表里的映射项」不是 id，必须先过形状校验。
            #
            # `- '#ns:tag'` 只在 `list:` 子块里才是**分类成员**。配方原料同样长这样
            # （`ingredients: - "#betternether:stalagnate_logs"`，CE 的 tag 引用写法），
            # 按列表项无条件当分类会让 9 条木板配方全被误报成悬空分类。
            #
            # `settings.tags:` 下的条目是**标签名**，不是 id：`- betternether:palm_logs`
            # 指的是一个标签，CE 侧由 `customItemTags` 维护，不指向某个方块/物品。
            if stripped.startswith("- "):
                if tags_indent is not None:
                    continue
                item = stripped[2:].strip().strip("'\"")
                if item.startswith("#"):
                    item = item[1:]
                    if list_indent is not None and looks_like_id(item):
                        refs.append(IdRef("category", item, path, lineno, owner))
                elif looks_like_id(item):
                    refs.append(IdRef("entry", item, path, lineno, owner))
                continue

            key, separator, raw = stripped.partition(":")
            if not separator:
                continue
            key = key.strip()
            value = raw.strip().strip("'\"")
            if not value:
                if key == "model":
                    model_indent = indent
                elif key == "result":
                    result_indent = indent
                elif key == "list":
                    list_indent = indent
                elif key == "tags":
                    # `settings.tags:` 后面是标签名清单，不是 id 引用（见上面的跳过逻辑）。
                    tags_indent = indent
                continue
            if not looks_like_id(value):
                # 材质名、音效键、函数名、MiniMessage 串、原版配方书分类……都不是 CE id。
                continue

            if key == "category":
                refs.append(IdRef("category", value, path, lineno, owner))
            elif key == "icon":
                refs.append(IdRef("entry", value, path, lineno, owner))
            elif key in ("block", "stripped", "target", "replacement", "ore_block", "source_block"):
                refs.append(IdRef("block", value, path, lineno, owner))
            elif key == "ingredient":
                refs.append(IdRef("entry", value, path, lineno, owner))
            elif key == "result":
                refs.append(IdRef("entry", value, path, lineno, owner))
            elif key == "id" and result_indent is not None:
                refs.append(IdRef("entry", value, path, lineno, owner))
            elif key == "template":
                # `template:` 在两个段里含义完全不同：
                #   * 方块 `loot:` 段 —— 战利品表模板（`default:loot_table/ore`）；
                #   * 配方 `smithing_transform` 段 —— **锻造模板物品**（升级模板）。
                # 一律当战利品表会把 6 条锻造配方全部误判成悬空引用。
                if section == "recipes":
                    refs.append(IdRef("entry", value, path, lineno, owner))
                else:
                    refs.append(IdRef("loot", value, path, lineno, owner))
            elif key == "parent" and model_indent is not None:
                refs.append(IdRef("model", value, path, lineno, owner))

    return declared, refs


def check_id_closure(
    source, namespace: str, report: Report,
    declared: dict[str, dict[str, str]], refs: list[IdRef],
) -> None:
    """CE 的 block / item / recipe / loot / category 引用必须闭合。

    ## 每条为什么必须存在

    CraftEngine 对这些引用**不做**启动期强校验：指向一个不存在的 id，加载不报错，
    只在玩家实际触发时表现为「挖了不掉东西」「合成表点了没反应」「分类里翻不到」。
    这类缺口在游戏里极难归因，只能在构建期按 id 闭合拦。

    ## 为什么不做「反向孤儿检查」

    声明了却没人引用的方块/物品是合法的（创造模式物品栏、命令、其它插件都可能引用）。
    孤儿检查会把它误报成错误，所以这里只查悬空引用这一个方向。
    """
    known = {
        "block": declared.get("blocks", {}),
        "item": declared.get("items", {}),
        "recipe": declared.get("recipes", {}),
        "category": declared.get("categories", {}),
        "loot": declared.get("loot", {}),
        # `entry` = 不限定是方块还是物品的引用（合成材料、图标、掉落结果……）。
        "entry": {**declared.get("blocks", {}), **declared.get("items", {})},
        "model": {},
    }
    labels = {
        "block": "方块", "item": "物品", "recipe": "配方",
        "category": "分类", "loot": "战利品表", "entry": "方块或物品", "model": "模型",
    }

    for ref in refs:
        ref_namespace, _, _path = ref.value.partition(":")
        if not _path:
            # 没有命名空间的值不是 CE id（材质名、音效键、原版配方书分类等），不参与闭合。
            continue
        if ref_namespace in EXTERNAL_ID_NAMESPACES:
            continue
        report.id_refs += 1
        if ref_namespace != namespace:
            report.fail(
                f"{ref.file}:{ref.line}: {ref.owner} 的 {ref.kind} 引用 {ref.value} 指向"
                f"未知命名空间 {ref_namespace}；本包只注册 {namespace}。"
            )
            continue
        if ref.kind == "model":
            # 模型引用由 check_item_models / check_models 按「CE 可生成」口径核对，
            # 这里只确认它没有指到别的命名空间。
            continue
        bucket = known.get(ref.kind, {})
        if ref.value not in bucket:
            report.fail(
                f"{ref.file}:{ref.line}: {ref.owner} 的 {ref.kind} 引用 {ref.value} 在本包里"
                f"没有对应的{labels.get(ref.kind, '声明')}；CE 加载不报错，玩家触发时才会表现为"
                "「挖了不掉东西 / 合成没反应 / 分类里翻不到」。"
            )

    report.blocks = len(declared.get("blocks", {}))
    report.items = len(declared.get("items", {}))
    report.recipes = len(declared.get("recipes", {}))
    report.categories = len(declared.get("categories", {}))

    # 静默失效地板：解析出 0 条声明时，上面所有闭合判断都自动成立。
    if not report.blocks:
        report.fail("configuration/ 里没有解析出任何 blocks 条目，ID 闭合校验会静默通过。")
    if not report.items:
        report.fail("configuration/ 里没有解析出任何 items 条目，ID 闭合校验会静默通过。")
    if not refs:
        report.fail("configuration/ 里没有解析出任何 id 引用，ID 闭合校验会静默通过。")


# ---------------------------------------------------------------- 动画与贴图文件


def png_size(data: bytes) -> tuple[int, int] | None:
    """读 PNG 的 IHDR 尺寸；不是合法 PNG 时返回 None。"""
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")


def check_animations(source, report: Report) -> None:
    """`.png.mcmeta` 动画元数据必须与贴图本身对得上。

    ## 这条门禁拦的是什么

    竖排多帧贴图靠旁挂的 `<贴图>.png.mcmeta` 声明 `animation`。写错的表现全是静默的：

      * 帧高不是贴图高的因数 —— 客户端按整张图切帧，动画错位；
      * `frames` 里的 index 超出帧数 —— 客户端丢帧或整张图退回静态；
      * 只有 `.mcmeta` 没有 `.png` —— 死文件，加载期被忽略；
      * `.png` 本身不是合法 PNG（拷贝截断）—— 紫黑格子。

    这几种在服务端日志里一个字都没有，只有进游戏盯着贴图才看得出来。
    """
    metas = sorted(
        name for name in source.files
        if name.startswith(f"{ASSETS}/") and name.endswith(".png.mcmeta")
    )
    validated = 0
    for path in metas:
        png = path[: -len(".mcmeta")]
        if not source.exists(png):
            report.fail(f"{path} 没有同名贴图 {png}：这个动画元数据是死文件。")
            continue
        try:
            payload = json.loads(source.read(path))
        except json.JSONDecodeError as error:
            report.fail(f"{path} 不是合法 JSON：{error}")
            continue
        if not isinstance(payload, dict):
            report.fail(f"{path} 顶层不是 JSON 对象。")
            continue
        animation = payload.get("animation")
        if animation is None:
            # 只有 gui.scaling 之类其它段，不是动画贴图，不参与帧校验。
            continue
        if not isinstance(animation, dict):
            report.fail(f"{path}: animation 不是对象。")
            continue

        size = png_size(source.read_bytes(png))
        if size is None:
            report.fail(f"{png} 不是合法 PNG（魔数或 IHDR 不对）：客户端会渲染成紫黑格子。")
            continue
        width, height = size
        frame_height = animation.get("height", width)
        if not isinstance(frame_height, int) or frame_height <= 0:
            report.fail(f"{path}: animation.height 必须是正整数，实际 {frame_height!r}。")
            continue
        if height % frame_height:
            report.fail(
                f"{path}: 贴图高 {height} 不是帧高 {frame_height} 的整数倍，"
                "客户端切帧会错位（表现为贴图被拉长/错帧）。"
            )
            continue
        frame_count = height // frame_height
        frames = animation.get("frames")
        if frames is not None:
            if not isinstance(frames, list) or not frames:
                report.fail(f"{path}: animation.frames 不是非空数组。")
                continue
            for index, frame in enumerate(frames):
                frame_index = frame.get("index") if isinstance(frame, dict) else frame
                if not isinstance(frame_index, int):
                    report.fail(f"{path}: animation.frames[{index}] 不是整数帧号。")
                    break
                if not 0 <= frame_index < frame_count:
                    report.fail(
                        f"{path}: animation.frames[{index}] 的帧号 {frame_index} 超出"
                        f"{frame_count} 帧（贴图高 {height} / 帧高 {frame_height}），客户端会丢帧。"
                    )
                    break
        validated += 1

    report.animations = validated
    if metas and validated == 0:
        report.fail(
            f"包内有 {len(metas)} 个 .png.mcmeta，却一个都没通过校验："
            "动画贴图会退化成拉长的静态图。"
        )


# ---------------------------------------------------------------- 各条检查


def check_pack_yml(source, report: Report) -> str:
    if not source.exists("pack.yml"):
        report.fail("缺少资源包描述符 pack.yml：CraftEngine 不会加载这个资源包。")
        return PACK_DIR_NAME
    namespace = None
    for line in source.read("pack.yml").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition(":")
        if separator and key.strip() == "namespace":
            namespace = value.strip().strip("'\"")
            break
    if not namespace:
        report.fail("pack.yml 里没有 namespace。")
        return PACK_DIR_NAME
    if namespace != source.directory_name:
        report.fail(
            f"pack.yml 的 namespace 是 {namespace}，与所在目录名 {source.directory_name} 不一致。"
        )
    return namespace


def check_config_textures(
    source, namespace: str, report: Report, entries: list[ConfigEntry]
) -> None:
    """第 1 条：CE 配置里的 `texture:` 必须是真实存在的 PNG。"""
    for entry in entries:
        if not entry.textures:
            continue
        local = entry.block_id.split(":", 1)[1] if ":" in entry.block_id else entry.block_id
        has_model = source.exists(model_path_of(namespace, f"block/{local}"))
        for value, lineno in entry.textures:
            if has_double_namespace(value):
                report.fail(
                    f"{entry.file}:{lineno}: {entry.block_id} 的 state.texture 引用 {value} "
                    "里出现了第二个命名空间分隔符 —— 生成器把原版贴图拼到了本包命名空间下。"
                    "正确形态是 `minecraft:<path>` 或 `"
                    f"{namespace}:<path>`，不是 `{namespace}:<...>/minecraft:<path>`。"
                )
                continue
            status = texture_status(source, namespace, value)
            if status == "external":
                report.external_refs += 1
                continue
            if status == "pack":
                continue
            ref_namespace, path = split_ref(value)
            expected = f"{ASSETS}/{ref_namespace}/{TEXTURES_DIRNAME}/{path}.png"
            if has_model:
                detail = "该方块另有同名模型文件，渲染不受影响，但这条引用是死引用，CE 加载时仍会刷「缺少纹理」。"
            else:
                detail = "该方块没有同名模型文件，渲染必然失败（游戏内是紫黑格子）。"
            report.fail(
                f"{entry.file}:{lineno}: {entry.block_id} 的 state.texture 引用 {value}，"
                f"包内缺少 {expected}；{detail}"
            )


def find_cycle(graph: dict[str, str]) -> list[str] | None:
    """在 `别名 -> 别名` 图里找一个环，返回环上的节点（首尾相同）。"""
    done: set[str] = set()
    visiting: list[str] = []

    def visit(node: str) -> list[str] | None:
        visiting.append(node)
        target = graph.get(node)
        if target is not None:
            if target in visiting:
                return visiting[visiting.index(target):] + [target]
            if target not in done:
                found = visit(target)
                if found:
                    return found
        visiting.pop()
        done.add(node)
        return None

    for node in graph:
        if node in done:
            continue
        found = visit(node)
        if found:
            return found
    return None


def collect_models(source) -> list[tuple[str, str, str]]:
    """返回 (包内路径, 命名空间, 模型相对名) —— 例：('.../block/agave.json', 'betternether', 'block/agave')。"""
    prefix = f"{ASSETS}/"
    models: list[tuple[str, str, str]] = []
    for path in sorted(source.files):
        if not path.startswith(prefix) or not path.endswith(".json"):
            continue
        parts = path[len(prefix):].split("/")
        if len(parts) >= 3 and parts[1] == MODELS_DIRNAME:
            models.append((path, parts[0], "/".join(parts[2:])[: -len(".json")]))
    return models


def model_available(source, generated_models: set[str], value: str) -> bool:
    """`ns:path` 指向的模型在运行期到底有没有：包内有文件，或 CE 会按 `generation` 生成。

    ## 为什么必须认「CE 会生成」

    参考工程与本移植都不在包里放方块模型：它们由 `state.model.generation`（方块段）
    或 `model.generation`（物品段）在运行期生成，包里没有文件。只判「文件存在」会把
    整批正常方块与物品判成紫黑格子 —— 这正是 `ink_bush`、`soul_lily` 这类
    「模型名与方块名不同、靠 generation 生成」的条目被误报的原因。
    """
    ref_namespace, relative = split_ref(value)
    if source.exists(model_path_of(ref_namespace, relative)):
        return True
    return relative in generated_models


def check_models(source, namespace: str, report: Report, generated_models: set[str]) -> None:
    """第 2 条（贴图存在）与第 3 条（无循环引用）。

    `models` 为空是**合法状态**，不是错误：参考工程与本移植都不在包里放方块模型，全部由
    CraftEngine 按 `state.texture` 生成。这里只在「有模型」时检查它们的贴图引用是否闭合，
    以及有没有循环引用。
    """
    models = collect_models(source)
    report.models = len(models)
    if not models:
        report.notes.append(
            "包内没有方块模型；方块模型全部由 CraftEngine 按 state.texture 生成"
            "（与参考工程口径一致）。"
        )
        return
        return

    for path, model_namespace, relative in models:
        name = f"{model_namespace}:{relative}"
        try:
            payload = json.loads(source.read(path))
        except json.JSONDecodeError as error:
            report.fail(f"{path} 不是合法 JSON：{error}")
            continue
        textures = payload.get("textures")
        if not isinstance(textures, dict):
            # 没有 textures 也要继续看 parent：父模型可能自己提供全部贴图。
            textures = {}

        # parent 闭合：指向本包但文件不存在时，CE 解析不到父模型会把整个模型作废，
        # 方块回退成统一的占位外观（现象是「一大片方块长得一模一样」）。
        #
        # ## 为什么「父模型不在包里」不等于坏
        #
        # 本移植刻意不释放两类父模型，它们仍然可解析：
        #
        #   1. **CE 会按 state.texture 生成同名模型**。子部件模型继承方块本体模型是常见写法
        #      （`soul_vein_2` 的 parent 是 `soul_vein_1`），而 `soul_vein_1` 正好是方块本体，
        #      由 CE 生成 —— 包内没有文件，运行期却有模型。
        #   2. **撞名子部件被刻意排除**（`reports/shadowed-models.txt`）。排除它们正是为了让
        #      CE 顺利生成同名模型，否则 CE 会报「模型文件已存在」。
        #
        # 判据因此是「父模型既不在包里、也不在 CE 的生成范围内」才算坏。生成范围取
        # 「方块配置里出现过的 state.texture/模型名」的并集 —— 读的是产物自身的配置，
        # 不引外部清单。
        parent = payload.get("parent")
        if isinstance(parent, str):
            parent_namespace, parent_path = split_ref(parent)
            if (
                parent_namespace == namespace
                and not source.exists(model_path_of(parent_namespace, parent_path))
                and parent_path not in generated_models
            ):
                report.fail(
                    f"{path}（{name}）的 parent {parent} 在包内不存在，也不在 CE 会生成的模型里；"
                    "CE 解析不到父模型会把这个模型整个作废，方块会退回占位外观。"
                )

        graph: dict[str, str] = {}
        for slot, value in textures.items():
            if not isinstance(value, str) or not value.startswith("#"):
                continue
            target = value[1:]
            if target in textures:
                graph[slot] = target
            else:
                # 别名可能由 parent 提供（如 block/cross 里的 #cross），不算错。
                report.dangling_aliases += 1

        cycle = find_cycle(graph)
        if cycle:
            trail = " -> ".join("#" + node for node in cycle)
            report.fail(
                f"{path}（{name}）的 textures 存在循环纹理引用：{trail}；"
                "CE 加载该模型时会报「存在循环纹理引用」，模型不可用。"
            )

        for slot, value in textures.items():
            if not isinstance(value, str) or value.startswith("#"):
                continue
            if has_double_namespace(value):
                report.fail(
                    f"{path}（{name}）的 textures.{slot} 引用 {value} 里出现了第二个命名空间"
                    "分隔符 —— 生成器把原版贴图拼到了本包命名空间下，这条引用永远取不到图。"
                )
                continue
            status = texture_status(source, namespace, value)
            if status == "external":
                report.external_refs += 1
                continue
            if status == "pack":
                continue
            ref_namespace, ref_path = split_ref(value)
            report.fail(
                f"{path}（{name}）的 textures.{slot} 引用 {value}，"
                f"包内缺少 {ASSETS}/{ref_namespace}/{TEXTURES_DIRNAME}/{ref_path}.png；"
                "CE 会刷「缺少纹理」，游戏内是紫黑格子。"
            )


def check_block_render(
    source, namespace: str, report: Report, entries: list[ConfigEntry],
    items_by_id: dict[str, ConfigEntry],
) -> None:
    """第 4 条：每个方块至少有一种可渲染的外观。

    ## 四条外观来源（CraftEngine 全都支持）

    只看 `block/<方块名>` 会把下面三类正常方块判成紫黑格子 —— 它们的外观都不叫那个名字：

      1. **同名方块模型文件** —— 上游本来就有 `models/block/<方块名>.json`；
      2. **`state.texture`（含 `generation.textures.*` 槽位）** —— CE 按贴图生成模型；
      3. **`state.model.path` / `states.appearances.*.model.path`** —— 方块显式引用模型，
         模型可以叫别的名字（门是 `bottom_left`、栅栏是 `post`、藤蔓是 `<名字>_1`），
         也可以由 `generation` 在运行期生成；
      4. **`state.entity_renderer.item`** —— 溢出方块共用被清空模型的原版状态
         （`id: transparent`），外观由 item_display 渲染**该物品的模型**。

    第 4 条的判据必须落到那个物品的模型上：物品模型解析不出来时，方块和物品会
    一起变成紫黑格子（`smoker` 就是这样漏的），所以这里不放宽成「写了 entity_renderer 就算有」。
    """
    blocks = [entry for entry in entries if entry.section == "blocks"]
    report.blocks = len(blocks)
    if not blocks:
        # 解析出 0 个方块时，「每个方块都能渲染」会自动成立——这是门禁最典型的静默失效。
        report.fail("configuration/blocks/*.yml 里没有解析出任何 blocks 条目，校验路径可能已失效。")
    for entry in blocks:
        local = entry.block_id.split(":", 1)[1] if ":" in entry.block_id else entry.block_id
        if source.exists(model_path_of(namespace, f"block/{local}")):
            continue
        if any(
            texture_status(source, namespace, value) in ("pack", "external")
            for value, _ in entry.textures
        ):
            continue
        if entry.state_model is not None and model_available(
            source, report.generated_models, entry.state_model[0]
        ):
            continue
        if any(
            model_available(source, report.generated_models, value)
            for value, _ in entry.appearance_models
        ):
            continue
        if entry.entity_renderer_item is not None and item_model_available(
            source, report.generated_models, items_by_id, entry.entity_renderer_item[0]
        ):
            continue
        if entry.templated:
            # 外观由 `${var}` 拼出来，静态看不到，不能判成坏。
            report.templated_entries += 1
            continue
        report.fail(
            f"{entry.file}:{entry.line}: 方块 {entry.block_id} 既没有模型文件 "
            f"{ASSETS}/{namespace}/{MODELS_DIRNAME}/block/{local}.json，"
            "也没有可用的 state.texture / state.model / states.appearances / "
            "entity_renderer，游戏内会是紫黑格子。"
        )


def item_model_available(
    source, generated_models: set[str],
    items_by_id: dict[str, ConfigEntry], item_id: str,
) -> bool:
    """这个物品在运行期有没有物品模型（`entity_renderer` 的渲染目标就是它）。"""
    entry = items_by_id.get(item_id)
    if entry is None or entry.model is None:
        return False
    return model_available(source, generated_models, entry.model[0])


def check_item_models(
    source, namespace: str, report: Report, entries: list[ConfigEntry]
) -> None:
    """第 6 条：物品的 `model:` 必须能解析出外观。

    ## 判据为什么不是「包内必须有这个模型文件」

    参考工程（更好的末地）的方块模型数量是 **0**：它的方块模型全部由 CraftEngine 在运行期
    按方块的 `generation` 生成，`items.model:` 只是指向那个**将要被生成**的模型。本移植照
    同一口径，包里也不放这些模型。

    所以「包内有模型文件」不是必要条件。一个物品能有外观，满足任一条即可：
      1. 包内确实有这个模型文件；
      2. 该路径在配置里带 `generation:`（CE 会生成它 —— 物品段、方块段都算）。
    两条都不满足才是真的紫黑格子。

    ## 为什么不再用「对应方块声明了 state.texture」兜底

    那条兜底是「方块名 = 模型名」时代的近似：只要同名方块有贴图就认为模型会被生成。
    现在模型名与方块名可能完全无关（`smoker` → `item/smoker` → `block/smoker_top`），
    近似会让「物品模型指向一个永远不存在的路径」蒙混过关 —— 那正是本次要抓的缺口。
    精确判据是「这个路径真的带 generation」。
    """
    for entry in entries:
        if entry.section != "items" or entry.model is None:
            continue
        value, lineno = entry.model
        ref_namespace, path = split_ref(value)
        if ref_namespace != namespace:
            continue
        if model_available(source, report.generated_models, value):
            continue
        report.fail(
            f"{entry.file}:{lineno}: {entry.block_id} 的 model 指向 {value}，"
            f"包内既没有 {model_path_of(ref_namespace, path)}，也没有 `generation` "
            "让 CraftEngine 生成它；物品会没有外观（紫黑格子）。"
        )


# ---------------------------------------------------------------- 耕地配置


# 方块级 `events:` 里的耕地段是「CE 没有 tillable 行为」的补偿实现，全靠 YAML 拼出来，
# 因此写错函数名/字段名（例如 `transform_block` 写成 `replace_block`、`match_item` 漏了
# `regex: true`）在游戏里表现为「右键锄头没反应」——不报错、不崩服，最难发现。
# 这段校验就是把「配置写对了吗」变成构建期可判定的事。
#
# 只做结构校验（字段名与目标方块 id 是否真实存在），不复刻 CE 的语义：条件表达式、
# 音效事件名是否存在于原版音效表，都不在这里判断。
REQUIRED_TILL_FUNCTIONS = ("transform_block", "play_sound", "swing_hand", "damage_item", "cancel_event")


def check_tillable(source, report: Report, entries: list[ConfigEntry]) -> None:
    """校验可耕土壤的 `events:` 段与耕地方块的作物标签。

    判定依据是生成产物本身：哪些方块带 `transform_block`，就必须能在同一个包里找到
    它指向的目标方块；目标方块必须带 `supports_crops`，否则「能耕但种不上」。
    """
    block_ids = {entry.block_id for entry in entries if entry.section == "blocks"}
    till_targets: dict[str, tuple[str, int]] = {}
    crop_supported: set[str] = set()

    for path in [name for name in source.list("configuration/blocks/") if name.endswith(".yml")]:
        section: str | None = None
        current_id: str | None = None
        current_indent = 0
        in_events = False
        seen_functions: set[str] = set()
        for lineno, line in enumerate(source.read(path).splitlines(), 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            indent = len(line) - len(line.lstrip())
            stripped = line.strip()

            if indent == 0:
                section = stripped.split(":", 1)[0].strip()
                current_id = None
                in_events = False
                continue
            if section != "blocks":
                continue
            if indent == 2:
                current_id = stripped.rstrip(":").strip()
                current_indent = indent
                in_events = False
                seen_functions = set()
                continue
            if current_id is None:
                continue
            # 离开 events 段（同级或更浅的键）就退出。
            if in_events and indent <= 4 and not stripped.startswith("- "):
                if stripped.rstrip(":") != "events":
                    in_events = False
            if indent == 4 and stripped.rstrip(":") == "events":
                in_events = True
                continue

            if "supports_crops" in stripped:
                crop_supported.add(current_id)

            if not in_events:
                continue
            key, separator, raw = stripped.lstrip("- ").partition(":")
            key = key.strip()
            value = raw.strip().strip("'\"")
            if key == "type" and value:
                seen_functions.add(value)
            elif key == "block" and value:
                till_targets[current_id] = (value, lineno)

        # 一个方块只要出现了 transform_block，就要求整套函数的其余部件也在，否则语义不完整
        # （没 cancel_event 会漏事件、没 damage_item 就不消耗耐久、没 play_sound 静默耕地）。
        if current_id is None or "transform_block" not in seen_functions:
            continue
        missing = [f for f in REQUIRED_TILL_FUNCTIONS if f not in seen_functions]
        if missing:
            report.fail(
                f"{path}: {current_id} 的耕地 events 缺少函数 {'、'.join(missing)}；"
                "耕地语义不完整（右键要么无反应、要么不消耗耐久/无音效）。"
            )
        if "match_item" not in seen_functions:
            report.fail(
                f"{path}: {current_id} 的耕地 events 缺少 match_item 条件，"
                "任何手持物品右键都会把它变成耕地。"
            )

    for block_id, (target, lineno) in till_targets.items():
        target_path, _, target_name = target.partition(":")
        if target not in block_ids:
            report.fail(
                f"{block_id} 的 transform_block 指向 {target}（第 {lineno} 行），"
                "但本包里没有这个方块；右键耕地会变成不存在的方块。"
            )
            continue
        if target_name not in {b.split(":", 1)[1] for b in crop_supported}:
            report.fail(
                f"{block_id} 耕地后的目标 {target} 没有挂 minecraft:supports_crops，"
                "原版作物无法种在上面（上游 NetherTerrainBlocks.java:198 是挂了的）。"
            )

    if till_targets:
        report.notes.append(
            f"{len(till_targets)} 个土壤方块可耕成 {len(set(t for t, _ in till_targets.values()))} 种耕地，"
            "events 结构已校验（函数齐全、目标存在、目标可种作物）。"
        )


# ---------------------------------------------------------------- 入口


def count_textures(source) -> int:
    prefix = f"{ASSETS}/"
    textures = 0
    for path in source.files:
        if not path.startswith(prefix) or not path.endswith(".png"):
            continue
        parts = path[len(prefix):].split("/")
        if len(parts) >= 3 and parts[1] == TEXTURES_DIRNAME:
            textures += 1
    return textures


def verify(target: Path) -> Report:
    """校验一个 jar 或资源包目录，返回报告（不打印、不退出）。"""
    source = open_source(target)
    report = Report()
    try:
        namespace = check_pack_yml(source, report)
        entries = parse_configs(source, report)
        items_by_id = {entry.block_id: entry for entry in entries if entry.section == "items"}
        check_config_textures(source, namespace, report, entries)
        check_models(source, namespace, report, report.generated_models)
        check_block_render(source, namespace, report, entries, items_by_id)
        check_item_models(source, namespace, report, entries)
        check_tillable(source, report, entries)
        declared, refs = parse_ids(source, report)
        check_id_closure(source, namespace, report, declared, refs)
        check_animations(source, report)
        report.textures = count_textures(source)

        if report.template_lines:
            report.notes.append(
                f"跳过 {report.template_lines} 行含 ${{...}} 模板变量的配置（config_factory 未启用）。"
            )
        if report.templated_entries:
            report.notes.append(
                f"{report.templated_entries} 个方块的外观由 ${{...}} 模板拼出，无法静态校验，未计入失败。"
            )
        if report.external_refs:
            report.notes.append(
                f"{report.external_refs} 处贴图引用指向原版（textures/block、textures/item 等），"
                "本包不提供原版贴图，未逐张校验。"
            )
        if report.dangling_aliases:
            report.notes.append(
                f"{report.dangling_aliases} 处别名引用未在本模型的 textures 里定义"
                "（通常由 parent 提供），未计入失败。"
            )
    finally:
        source.close()
    return report


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("用法: verify_assets.py <jar 路径或资源包目录>", file=sys.stderr)
        return 2
    target = Path(argv[1])
    if not target.exists():
        print(f"找不到资源包：{target}", file=sys.stderr)
        return 2

    report = verify(target)

    for message in report.notes:
        print(f"[提示] {message}")
    if report.failures:
        print()
        for message in report.failures:
            print(f"[失败] {message}")
        print(f"\n失败 {len(report.failures)} 项。")
        return 1

    print(
        f"资源包校验通过：{report.blocks} 个方块、{report.items} 个物品、"
        f"{report.recipes} 个配方、{report.categories} 个分类、"
        f"{report.models} 个模型、{report.textures} 张贴图、"
        f"{report.animations} 个动画贴图、{report.id_refs} 条 id 引用闭合"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))

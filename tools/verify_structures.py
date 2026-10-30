#!/usr/bin/env python3
"""校验 betternether-structures 数据包里的 jigsaw 结构自洽性。

用法：
    python tools/verify_structures.py [路径]

    <路径> 省略        → 校验源目录 plugin/src/main/resources/datapacks/betternether-structures
    <路径> 是 .jar     → 校验 jar 内的 datapacks/betternether-structures/**
    <路径> 是目录      → 校验该目录（应直接包含 data/ 与 pack.mcmeta）

只读，不改任何东西。任何一条不满足就非零退出。

门禁清单（每条都对应一个真实的启动中止面）：
  1. 每个 template_pool 元素引用的 location 都能解析到对应的 .nbt 模板。
     少一个模板，jigsaw 在装配阶段抛异常，整座结构不生成。
  2. 每个 .nbt 都真的可读：gzip 魔数 \\x1f\\x8b，解压后的根标签是 TAG_Compound。
     损坏的模板同样会让世界生成崩溃。
  3. structure 的 biomes tag 里每个 id 都存在于本工程的群系注册表或 minecraft:*。
     指向不存在的群系会让 worldgen 注册表 Unbound、服务器启动中止——这是最重的一条。
  4. structure 的 start_pool 存在，structure_set 引用的 structure 存在。
     悬空引用同样会让注册表解析失败。
  5. 公开结构 id（`data/mumu/worldgen/structure`）与作者定义一一对应、旧定义照旧发布、
     旧命名空间不残留放置集、每个公开 id 恰好被放置一次。
     `/locate structure mumu:<建筑名>` 依赖的正是这一层：少一个 id、多一套放置，都会让
     玩家定位到一座不存在的建筑，或让同一座建筑生成两遍。
"""

from __future__ import annotations

import gzip
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

NAMESPACE = "betternether"
#: 公开结构 id 的命名空间：`/locate structure mumu:<建筑名>` 里的那一半。
#: 定义、模板池、群系 tag、处理器列表仍然全部在 `betternether:` 下，只有「结构自己的 id」
#: 多出一份 `mumu:` 的定义与放置集，见 `scripts/bnlib/public_structures.py`。
PUBLIC_STRUCTURE_NAMESPACE = "mumu"
OUR_NAMESPACES = (NAMESPACE, PUBLIC_STRUCTURE_NAMESPACE)
PACK_DIR_NAME = "betternether-structures"
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_PACK = PROJECT_ROOT / "plugin/src/main/resources/datapacks" / PACK_DIR_NAME

GZIP_MAGIC = b"\x1f\x8b"
TAG_COMPOUND = 10

FAILURES: list[str] = []
NOTES: list[str] = []


def fail(message: str) -> None:
    FAILURES.append(message)


def note(message: str) -> None:
    NOTES.append(message)


# ---------------------------------------------------------------------------
# 最小 NBT 读取器
# ---------------------------------------------------------------------------
#
# 只为了读结构模板的 `palette`。这里不引第三方库：门禁要能在没有服务端、没有
# CraftEngine、没有 pip 依赖的机器上跑，而 NBT 的读取规则是稳定的小固定集合。
#
# 支持全部 12 种 tag，因为跳过未知 tag 必须知道它的长度 —— 只实现「用到的几种」
# 会在遇到 `block_entities` 里的嵌套结构时错位，读出来的 palette 就是垃圾。
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NbtError(Exception):
    pass


class _NbtReader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def _take(self, count: int) -> bytes:
        end = self.pos + count
        if end > len(self.data):
            raise NbtError(f"读到末尾时数据不足（需要 {count} 字节，剩 {len(self.data) - self.pos}）")
        chunk = self.data[self.pos:end]
        self.pos = end
        return chunk

    def u1(self) -> int:
        return self._take(1)[0]

    def i1(self) -> int:
        return struct.unpack(">b", self._take(1))[0]

    def u2(self) -> int:
        return struct.unpack(">H", self._take(2))[0]

    def i2(self) -> int:
        return struct.unpack(">h", self._take(2))[0]

    def i4(self) -> int:
        return struct.unpack(">i", self._take(4))[0]

    def i8(self) -> int:
        return struct.unpack(">q", self._take(8))[0]

    def f4(self) -> float:
        return struct.unpack(">f", self._take(4))[0]

    def f8(self) -> float:
        return struct.unpack(">d", self._take(8))[0]

    def string(self) -> str:
        # NBT 的字符串长度是「修改版 UTF-8 的字节数」，不是字符数。
        length = self.u2()
        return self._take(length).decode("utf-8", errors="replace")

    def payload(self, tag: int):
        if tag == TAG_BYTE:
            return self.i1()
        if tag == TAG_SHORT:
            return self.i2()
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            return self._take(self.i4())
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item_tag = self.u1()
            count = self.i4()
            if item_tag == TAG_END:
                return []
            return [self.payload(item_tag) for _ in range(count)]
        if tag == TAG_COMPOUND:
            result: dict[str, object] = {}
            while True:
                child = self.u1()
                if child == TAG_END:
                    return result
                name = self.string()
                result[name] = self.payload(child)
        if tag == TAG_INT_ARRAY:
            return [self.i4() for _ in range(self.i4())]
        if tag == TAG_LONG_ARRAY:
            return [self.i8() for _ in range(self.i4())]
        raise NbtError(f"未知的 tag 类型 {tag}")


def read_nbt(payload: bytes) -> dict:
    """解压并解析一个结构模板，返回根复合标签。

    结构模板的磁盘形态是「gzip 压缩的、带根名字的 NBT」：`TAG_Compound` + 2 字节名字长度
    + 名字 + 内容。这里把根名字读掉后返回内容。
    """
    try:
        raw = gzip.decompress(payload)
    except OSError as error:
        raise NbtError(f"gzip 解压失败：{error}") from error
    if not raw:
        raise NbtError("解压后为空")
    reader = _NbtReader(raw)
    root_tag = reader.u1()
    if root_tag != TAG_COMPOUND:
        raise NbtError(f"根标签类型是 {root_tag}，应为 TAG_Compound({TAG_COMPOUND})")
    reader.string()  # 根名字，结构模板里是空串
    root = reader.payload(TAG_COMPOUND)
    if not isinstance(root, dict):
        raise NbtError("根复合标签解析结果不是对象")
    return root


def collect_palette_blocks(root: dict) -> set[str]:
    """取出结构模板 palette 里出现的所有方块 id。

    ## 键名必须同时认 `id` 与 `Name`

    26.3 的结构模板把方块写成 `{"id": "minecraft:stone", ...}`；1.20 之前以及部分
    工具链导出的模板写的是 `{"Name": "minecraft:stone", "Properties": {...}}`。
    只读 `Name` 时，本工程全部 67 个模板都会解析出**空集** —— 于是「调色板方块是否
    已注册」这条门禁对着空集永远成立，看起来通过，实际一个方块都没核对过。
    （实测：nether_city/city_center_01.nbt 的 palette 条目键就是 `id`。）

    两种形态都要认，且优先 `id`：一份模板只会用其中一种，同时取到也无副作用。
    """
    names: set[str] = set()

    def absorb(entries: object) -> None:
        if not isinstance(entries, list):
            return
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            name = entry.get("id")
            if not isinstance(name, str) or not name:
                name = entry.get("Name")
            if isinstance(name, str) and name:
                names.add(name)

    absorb(root.get("palette"))
    for palette in root.get("palettes") or []:
        absorb(palette)
    return names


class Pack:
    """把「jar 内数据包」和「磁盘目录」统一成同一套读取接口。

    prefix 是数据包在容器里的键前缀（jar 内为 datapacks/betternether-structures，
    磁盘目录为空）；root 是磁盘根（jar 模式下为 None）。
    """

    def __init__(self, prefix: str, opener, *, root: Path | None = None):
        self.prefix = prefix.strip("/")
        self.root = root
        self._opener = opener

    def key(self, relative: str) -> str:
        return f"{self.prefix}/{relative}" if self.prefix else relative

    def locate(self, relative: str) -> Path:
        if self.root is None:
            raise RuntimeError("jar 模式的 Pack 没有磁盘根")
        return self.root / relative

    def exists(self, relative: str) -> bool:
        return self._opener(self.key(relative)) is not None

    def read_bytes(self, relative: str) -> bytes | None:
        return self._opener(self.key(relative))

    def read_json(self, relative: str):
        raw = self.read_bytes(relative)
        if raw is None:
            raise FileNotFoundError(self.key(relative))
        return json.loads(raw.decode("utf-8"))

    def list_files(self, relative_root: str, suffix: str) -> list[str]:
        """列出 relative_root 下所有以 suffix 结尾的文件，返回相对 relative_root 的路径。"""
        if self.root is not None:
            base = self.root / relative_root
            if not base.is_dir():
                return []
            return sorted(str(path.relative_to(base)).replace("\\", "/") for path in base.rglob(f"*{suffix}"))

        marker = f"{self.key(relative_root)}/"
        return sorted(
            name[len(marker) :] for name in self._names if name.startswith(marker) and name.endswith(suffix)
        )

    _names: frozenset[str] = frozenset()


class JarPack(Pack):
    def __init__(self, prefix: str, archive: zipfile.ZipFile):
        self._archive = archive
        self._names = frozenset(archive.namelist())
        super().__init__(prefix, self._read, root=None)

    def _read(self, key: str) -> bytes | None:
        if key not in self._names:
            return None
        with self._archive.open(key) as handle:
            return handle.read()


def open_jar(path: Path) -> tuple[Pack, Pack]:
    archive = zipfile.ZipFile(path)
    structures_prefix = f"datapacks/{PACK_DIR_NAME}"
    if not any(name.startswith(structures_prefix + "/") for name in archive.namelist()):
        fail(f"jar 内没有 {structures_prefix}/，结构包没有被装配进去。")
    # 群系包在 jar 内的根是 datapack/（构建期由 scripts/extract_bn.py 生成）。
    return JarPack(structures_prefix, archive), JarPack("datapack", archive)


def open_directory(path: Path) -> tuple[Pack, Pack | None]:
    def opener(key: str):
        candidate = path / key
        return candidate.read_bytes() if candidate.is_file() else None

    return Pack("", opener, root=path), None


def open_stack(target: Path) -> tuple[Pack | None, Pack | None]:
    if target.is_file() and zipfile.is_zipfile(target):
        return open_jar(target)
    if target.is_dir():
        return open_directory(target)
    fail(f"路径既不是 jar 也不是目录：{target}")
    return None, None


def check_pack_meta(pack: Pack) -> None:
    if not pack.exists("pack.mcmeta"):
        # 源目录里没有 pack.mcmeta 是正常的：它由 scripts/extract_bn.py 在构建期生成。
        # 只有产物（jar）里必须存在。
        if pack.root is None:
            fail("数据包缺少 pack.mcmeta。")
        else:
            note("源目录没有 pack.mcmeta（构建期由 scripts/extract_bn.py 生成），跳过。")
        return
    try:
        payload = pack.read_json("pack.mcmeta")
    except json.JSONDecodeError as error:
        fail(f"pack.mcmeta 不是合法 JSON：{error}")
        return
    if "min_format" not in (payload.get("pack") or {}):
        fail("pack.mcmeta 缺少 min_format。")


def check_templates(pack: Pack) -> tuple[set[str], dict[str, set[str]]]:
    """门禁 2：每个 .nbt 都要是 gzip 包且根标签是 TAG_Compound。

    返回 (模板相对路径集合, 模板 -> palette 方块 id 集合)，供模板池核对与方块注册核对使用。
    """
    relative_root = f"data/{NAMESPACE}/structure"
    templates = pack.list_files(relative_root, ".nbt")
    if not templates:
        fail(f"{relative_root} 下没有任何 .nbt 模板。")

    names: set[str] = set()
    palettes: dict[str, set[str]] = {}
    for relative in templates:
        names.add(f"{relative_root}/{relative}")

        raw = pack.read_bytes(f"{relative_root}/{relative}") or b""
        if raw[:2] != GZIP_MAGIC:
            fail(f"{relative}: 不是 gzip 包（魔数 {raw[:2]!r}），原版 StructureTemplate 读不了。")
            continue
        try:
            root = read_nbt(raw)
        except NbtError as error:
            fail(f"{relative}: {error}；原版 StructureTemplate 读不了这个模板。")
            continue
        palettes[relative] = collect_palette_blocks(root)

    if palettes and not any(palettes.values()):
        # 一个模板都没有 palette，说明解析路径已经失效（正常模板一定带 palette）。
        fail("所有 NBT 模板都解析不出 palette，palette 方块校验会静默通过。")
    return names, palettes


# 上游结构里出现、但本移植刻意不注册的方块。
#
# 键是模组 id，值是「为什么可以不存在」的理由 —— 显式映射/豁免必须是**可复核**的一句话，
# 不能是空值。留空表示「所有 betternether: 调色板方块都必须在 CE 包里注册」。
#
# 注意：这里是**逐 id** 的豁免，不做「按后缀批量放行」。批量放行（比如"所有 _stairs 都放过"）
# 会把「本该注册却漏了」的方块一起吞掉，门禁就退化成永远通过。
PALETTE_WAIVERS: dict[str, str] = {}

# 缺口按形状族归类，让失败信息能直接指派给对应 owner，而不是甩出几百行。
_PALETTE_FAMILIES: tuple[tuple[str, str], ...] = (
    ("_stairs", "楼梯"),
    ("_slab", "台阶"),
    ("_wall", "墙"),
    ("_fence", "栅栏"),
    ("_door", "门"),
    ("_trapdoor", "活板门"),
    ("_pane", "玻璃板"),
    ("_bars", "栏杆"),
    ("_plate", "压力板/板"),
    ("_lantern", "灯笼"),
    ("_pedestal", "基座"),
    ("_furnace", "功能方块"),
    ("_forge", "功能方块"),
    ("_brewing_stand", "功能方块"),
    ("_chest", "功能方块"),
    ("grass", "植物"),
    ("vine", "植物"),
    ("moss", "植物"),
    ("mushroom", "植物"),
    ("plant", "植物"),
    ("seeds", "植物"),
    ("reed", "植物"),
    ("flower", "植物"),
    ("pot", "装饰"),
    ("statue", "装饰"),
)


def palette_family(path: str) -> str:
    for token, family in _PALETTE_FAMILIES:
        if token in path:
            return family
    return "其它"


def collect_registered_blocks(target: Path) -> set[str] | None:
    """读 CE 配置里声明的方块 id，作为「结构调色板里的模组方块是否已注册」的依据。

    优先从被校验的容器里读（jar 内 `craftengine/betternether/configuration/blocks/**`），
    目录模式下退回构建产物目录。读不到就返回 None —— 调用方据此降级为「无法核对」，
    而不是当成「全部已注册」。
    """
    prefix = "craftengine/betternether/configuration/blocks/"

    def from_zip(archive: zipfile.ZipFile) -> set[str]:
        blocks: set[str] = set()
        for name in archive.namelist():
            if not (name.startswith(prefix) and name.endswith(".yml")):
                continue
            with archive.open(name) as handle:
                blocks.update(_parse_block_ids(handle.read().decode("utf-8", errors="replace")))
        return blocks

    if target.is_file() and zipfile.is_zipfile(target):
        with zipfile.ZipFile(target) as archive:
            blocks = from_zip(archive)
        return blocks or None

    for base in (
        target,
        PROJECT_ROOT / "plugin/build/generated/bn/craftengine/betternether",
        PROJECT_ROOT / "common/build/generated/bn/craftengine/betternether",
    ):
        if not base.is_dir():
            continue
        directory = base / "configuration/blocks"
        if not directory.is_dir():
            continue
        blocks: set[str] = set()
        for path in sorted(directory.glob("*.yml")):
            blocks.update(_parse_block_ids(path.read_text(encoding="utf-8", errors="replace")))
        if blocks:
            return blocks
    return None


def _parse_block_ids(text: str) -> set[str]:
    """从一份 CE 方块配置里取 `blocks:` 段下两空格缩进的条目 id。"""
    ids: set[str] = set()
    section: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if indent == 0:
            section = stripped.split(":", 1)[0].strip()
            continue
        if section != "blocks" or indent != 2:
            continue
        candidate = stripped.rstrip(":").strip()
        if ":" in candidate:
            ids.add(candidate)
    return ids


def check_palette_blocks(palettes: dict[str, set[str]], registered: set[str] | None) -> None:
    """门禁：结构调色板里的模组方块必须已注册，或在 PALETTE_WAIVERS 里显式豁免。

    ## 为什么这是最隐蔽的一类错误

    jigsaw 装配时，调色板里找不到定义的方块会被**静默跳过**：不抛异常、不刷日志，
    玩家看到的是一座缺了几面墙的建筑。上游 NBT 里写的是 `betternether:xxx`，命名空间
    一旦与 CE 注册的对不上，整座城市就变成空壳 —— 而构建、启动、日志全都正常。

    `minecraft:` 方块不核对（原版注册表里必然有），其它命名空间直接判失败。

    ## 为什么输出按族汇总而不是逐条刷屏

    实测缺口可达上百种 id / 数百处引用。逐条打印会把真正的信息淹掉，读的人只会去加
    waiver。这里按形状族给出「缺多少、样例是哪些、全量清单在提示里」，让每条失败都能
    直接指派：楼梯/台阶/墙 → 方块注册侧；植物/装饰/功能方块 → 内容范围决策侧。
    """
    if registered is None:
        note("读不到 CE 方块配置，跳过「结构调色板里的模组方块是否已注册」的逐条核对。")
        return

    missing_by_family: dict[str, set[str]] = {}
    checked = 0
    foreign = 0
    for template, blocks in sorted(palettes.items()):
        for block_id in sorted(blocks):
            namespace, _, path = block_id.partition(":")
            if not path:
                fail(f"{template}: 调色板里的方块 id {block_id!r} 没有命名空间。")
                continue
            if namespace == "minecraft":
                continue
            if namespace != NAMESPACE:
                foreign += 1
                fail(
                    f"{template}: 调色板引用了其它命名空间的方块 {block_id}；"
                    "本包只注册 minecraft 与 " + NAMESPACE + "，装配时这块会被静默跳过。"
                )
                continue
            checked += 1
            if block_id in registered or block_id in PALETTE_WAIVERS:
                continue
            missing_by_family.setdefault(palette_family(path), set()).add(block_id)

    if checked == 0 and not foreign:
        note("结构调色板里没有 " + NAMESPACE + " 方块，palette 注册核对未覆盖任何条目。")
        return

    missing_total = sum(len(ids) for ids in missing_by_family.values())
    if not missing_total:
        note(f"结构调色板里 {checked} 处 {NAMESPACE} 方块引用全部已注册。")
        return

    for family, ids in sorted(missing_by_family.items(), key=lambda item: -len(item[1])):
        samples = "、".join(sorted(ids)[:6])
        fail(
            f"结构调色板有 {len(ids)} 种「{family}」方块未在 CE 包里注册（样例：{samples}"
            + ("…" if len(ids) > 6 else "")
            + "）。jigsaw 装配时会静默跳过这些方块 —— 建筑缺砖、日志无痕迹。"
            "要么在 CE 里注册它们，要么把结构调色板改写成已注册的方块（显式映射）。"
        )

    all_missing = sorted(block for ids in missing_by_family.values() for block in ids)
    note(
        f"未注册调色板方块共 {missing_total} 种 / {checked} 处引用，全量清单："
        + "、".join(all_missing[:60])
        + ("…（其余 " + str(max(0, missing_total - 60)) + " 种见上按族统计）" if missing_total > 60 else "")
    )


def walk_pool_elements(
    elements: object, pool_label: str, trail: str,
) -> tuple[list[str], list[str]]:
    """递归收集一个元素列表里的模板位置与处理器引用。

    ## 为什么要递归

    `minecraft:list_pool_element` 把子元素数组内联在 `elements` 里。只遍历最外层会把
    这些子元素里的 `location` 与 `processors` 全部漏掉 —— 门禁看起来通过，实际有整批
    模板引用与处理器从未被核对过。
    """
    locations: list[str] = []
    processors: list[str] = []
    if not isinstance(elements, list):
        fail(f"{pool_label}: {trail} 的 elements 不是数组。")
        return locations, processors

    for index, entry in enumerate(elements):
        where = f"{trail}[{index}]"
        element = entry.get("element") if isinstance(entry, dict) else None
        if not isinstance(element, dict):
            fail(f"{pool_label}: {where} 缺少 element 对象。")
            continue

        element_type = element.get("type")
        nested = element.get("elements")
        if isinstance(nested, list) or element_type == "minecraft:list_pool_element":
            if not isinstance(nested, list) or not nested:
                fail(f"{pool_label}: {where} 是 list_pool_element，但 elements 不是非空数组。")
            else:
                child_locations, child_processors = walk_pool_elements(
                    nested, pool_label, f"{where}.elements"
                )
                locations += child_locations
                processors += child_processors

        location = element.get("location")
        if isinstance(location, str):
            locations.append(location)
        elif nested is None:
            fail(f"{pool_label}: {where} 缺少 location。")

        # `processors` 可以是单个引用、引用数组，或内联的处理器对象（那种不需要核对）。
        raw_processors = element.get("processors")
        if isinstance(raw_processors, str):
            processors.append(raw_processors)
        elif isinstance(raw_processors, list):
            processors += [item for item in raw_processors if isinstance(item, str)]

    return locations, processors


def check_pools(pack: Pack, templates: set[str]) -> tuple[int, set[str]]:
    """门禁 1：template_pool 里每个 location 都能落到一个模板文件。

    返回 (池数量, 池 id 集合)。池 id 供结构侧核对 start_pool 是否真的存在。
    """
    relative_root = f"data/{NAMESPACE}/worldgen/template_pool"
    pools = pack.list_files(relative_root, ".json")
    if not pools:
        fail(f"{relative_root} 下没有任何 template_pool。")

    pool_ids: set[str] = set()
    processor_ids: set[str] = set()
    for relative in pools:
        pool_ids.add(f"{NAMESPACE}:" + relative[: -len(".json")])
        try:
            pool = pack.read_json(f"{relative_root}/{relative}")
        except json.JSONDecodeError as error:
            fail(f"template_pool/{relative}: 不是合法 JSON：{error}")
            continue
        if not isinstance(pool, dict):
            fail(f"template_pool/{relative}: 顶层不是 JSON 对象。")
            continue

        elements = pool.get("elements")
        if not isinstance(elements, list) or not elements:
            fail(f"template_pool/{relative}: elements 不是非空数组。")
            continue

        if not isinstance(pool.get("fallback"), str):
            fail(f"template_pool/{relative}: 缺少 fallback（应为 minecraft:empty）。")

        locations, processors = walk_pool_elements(elements, f"template_pool/{relative}", "elements")
        for location in locations:
            if not location.startswith(f"{NAMESPACE}:"):
                fail(
                    f"template_pool/{relative}: 元素引用的 {location} 不在 {NAMESPACE} 命名空间。"
                )
                continue
            target = f"data/{NAMESPACE}/structure/{location.split(':', 1)[1]}.nbt"
            if target not in templates:
                fail(
                    f"template_pool/{relative}: 元素引用的 {location} 找不到模板 {target}；"
                    "jigsaw 装配阶段会抛异常，整座结构不生成。"
                )
        processor_ids.update(processors)

    check_processors(pack, processor_ids)
    return len(pools), pool_ids


def check_processors(pack: Pack, processor_ids: set[str]) -> None:
    """模板池引用的处理器列表必须存在。

    悬空的 `processors: betternether:xxx` 与悬空模板一样，只在装配阶段炸。
    """
    relative_root = f"data/{NAMESPACE}/worldgen/processor_list"
    available = {
        f"{NAMESPACE}:" + relative[: -len(".json")]
        for relative in pack.list_files(relative_root, ".json")
    }
    for processor in sorted(processor_ids):
        if processor.startswith("minecraft:"):
            continue
        if not processor.startswith(f"{NAMESPACE}:"):
            fail(f"模板池引用了其它命名空间的处理器 {processor}；本包只注册 {NAMESPACE}。")
            continue
        if processor not in available:
            fail(
                f"模板池引用的处理器 {processor} 找不到 "
                f"{relative_root}/{processor.split(':', 1)[1]}.json；"
                "jigsaw 装配阶段会因找不到处理器而失败。"
            )


def collect_biomes(biome_pack: Pack | None) -> set[str]:
    """本工程实际注册的群系 id。

    优先读数据包里的群系 JSON（jar 内为 datapack/**，构建目录同理）；
    源码树场景下退回解析 NetherBiomes.java 的 Entry 名单。
    """
    directories = [
        PROJECT_ROOT / "plugin/build/generated/bn/datapack" / f"data/{NAMESPACE}/worldgen/biome",
        PROJECT_ROOT / "common/build/generated/bn/datapack" / f"data/{NAMESPACE}/worldgen/biome",
    ]
    for directory in directories:
        if directory.is_dir():
            names = {f"{NAMESPACE}:{path.stem}" for path in directory.glob("*.json")}
            if names:
                return names

    java = PROJECT_ROOT / "common/src/main/java/dev/mumu/betternether/biome/NetherBiomes.java"
    if java.is_file():
        text = java.read_text(encoding="utf-8")
        names = {f"{NAMESPACE}:{match}" for match in re.findall(r'new Entry\("([a-z0-9_]+)"', text)}
        if names:
            return names

    if biome_pack is not None:
        note("群系注册表不在构建目录里；已退化为只用 minecraft:* 白名单核对，未能逐条验证 betternether 群系。")
    else:
        note("没能读到群系注册表，跳过「群系 id 是否存在」的逐条核对。")
    return set()


def check_biome_tag(pack: Pack, structure: dict, biomes: set[str], label: str) -> None:
    """门禁 3：biomes tag 里的每个 id 都必须存在。"""
    biomes_ref = structure.get("biomes")
    if not isinstance(biomes_ref, str):
        fail(f"{label}: 缺少 biomes。")
        return
    if not biomes_ref.startswith("#"):
        note(f"{label}: biomes 直接写了 id {biomes_ref}，跳过 tag 核对。")
        return

    tag_id = biomes_ref[1:]
    tag_namespace, _, tag_name = tag_id.partition(":")
    if tag_namespace not in OUR_NAMESPACES:
        note(f"{label}: biomes 用的是外部 tag {biomes_ref}，跳过逐条核对。")
        return

    tag_path = f"data/{tag_namespace}/tags/worldgen/biome/{tag_name}.json"
    if not pack.exists(tag_path):
        fail(f"{label}: biomes 引用的 tag {tag_id} 找不到 {tag_path}，worldgen 注册表会 Unbound。")
        return

    tag = pack.read_json(tag_path)
    values = tag.get("values")
    if not isinstance(values, list) or not values:
        fail(f"{tag_path}: values 不是非空数组。")
        return

    for value in values:
        entry = value.get("id") if isinstance(value, dict) else value
        if not isinstance(entry, str):
            fail(f"{tag_path}: 出现无法识别的条目 {value!r}。")
            continue
        if entry.startswith("#"):
            note(f"{tag_path}: 嵌套 tag {entry} 未展开核对。")
            continue
        if entry.startswith("minecraft:"):
            continue
        if biomes and entry not in biomes:
            fail(
                f"{tag_path}: 引用了本工程没有注册的群系 {entry}；"
                "这会让 worldgen 注册表解析失败、服务器启动中止。"
            )


def check_registry_shapes(pack: Pack) -> None:
    """门禁 5：每个 worldgen JSON 都必须是「原版注册表文件」形状，不是 CraftEngine 那种包配置形状。

    ## 这条门禁是怎么来的

    线上第一次启动直接崩在注册表解析：

        Unbound values in registry ... minecraft:worldgen/processor_list: [mumu:strip_structure_blocks]
        Failed to parse either. First: No key processors in
            MapLike[{"processor_lists":{"mumu:strip_structure_blocks":{"processors":[...]}}}]

    两个错误叠在一起：

    1. **多了一层 `processor_lists` 包装。** 原版 `data/<ns>/worldgen/processor_list/<name>.json`
       的内容**就是**处理器列表本身（顶层键 `processors`）。带段名的那套写法是 CE 的包配置
       才有的；CE 的 YAML 看多了很容易照搬过来。
    2. **`{"Name": "..."}` 是 1.20 之前的方块写法。** 26.3 的 `BlockState.CODEC` 只接受纯字符串
       id 或 `{"id":..., "properties":...}`，`{"Name":...}` 两种都不是 —— 这正是参考工程
       0.1.6 炸服的同款地雷，MIGRATION.md 里专门记过。

    两类错误都**不会**在写文件时暴露，只在服务器启动的注册表冻结阶段炸，而且整个服起不来。
    所以这里在构建期按顶层键的形状扫一遍：每个 worldgen 注册表目录都有它自己必须出现/禁止出现的
    顶层键，缺了或被包住都是硬错误。
    """
    # 每种注册表文件应有的顶层键（至少含其一）与禁止出现的包装键。
    shapes = {
        "processor_list": ({"processors"}, {"processor_lists"}),
        "template_pool": ({"name", "elements"}, {"template_pools"}),
        "structure": ({"type", "biomes"}, {"structures"}),
        "structure_set": ({"placement", "structures"}, {"structure_sets"}),
        "biome": ({"carvers", "features", "spawners", "attributes"}, {"biomes"}),
    }

    checked = 0
    for namespace_name in OUR_NAMESPACES:
        prefix = f"data/{namespace_name}/worldgen"
        for relative in pack.list_files(prefix, ".json"):
            name = f"{prefix}/{relative}"
            kind = relative.split("/", 1)[0]
            if kind not in shapes:
                continue
            required, forbidden = shapes[kind]
            payload = pack.read_json(name)
            if not isinstance(payload, dict):
                fail(f"{name}: 顶层不是 JSON 对象。")
                continue
            keys = set(payload)
            checked += 1

            wrapped = keys & forbidden
            if wrapped:
                fail(f"{name}: 多了包装层 {sorted(wrapped)}。原版注册表文件的内容就是列表本身，"
                     f"顶层应为 {sorted(required)}，不是带段名的包配置结构。")

            if not (keys & required):
                fail(f"{name}: 顶层缺少 {sorted(required)} 之一（实际顶层键 {sorted(keys)}），"
                     f"服务器解析注册表时会 Unbound 并拒绝启动。")

            # 旧式方块写法：{"Name": "minecraft:x"}。26.3 只认纯字符串 id 或 {"id":...}。
            raw = pack.read_bytes(name) or b""
            if b'"Name"' in raw:
                fail(f"{name}: 使用了 1.20 之前的方块写法 {{\"Name\": ...}}；"
                     f"26.3 的 BlockState.CODEC 只接受纯字符串 id 或 {{\"id\":..., \"properties\":...}}。")

    if checked == 0:
        fail("没有找到任何 worldgen 注册表文件，校验路径可能已失效。")


def check_structures(pack: Pack, biomes: set[str], pool_ids: set[str]) -> dict[str, set[str]]:
    """门禁 3/4：每个 structure 的 type / biomes tag / start_pool 都成立。

    ## 为什么必须遍历全部

    早先这里只核对写死的 `nether_city`。新增结构（其它 DS 模块正在并行落地）只要换个
    文件名，校验就完全不会碰它 —— 门禁看起来通过，实际那份结构从未被检查过。
    所以这里按目录遍历，一个都不漏。

    ## 为什么要覆盖两个命名空间

    公开结构多出一份 `data/mumu/worldgen/structure/<名字>.json`（内容与作者定义相同，
    只是换了 id）。只扫 `betternether:` 会把发布出来的那份完全跳过 —— 玩家敲
    `/locate structure mumu:x` 命中的正是那份，它的 `biomes` / `start_pool` 一旦悬空，
    服务器启动就中止。返回 `命名空间 -> id 集合`，让「发布布局」的检查另处接着用。
    """
    by_namespace: dict[str, set[str]] = {}
    for namespace_name in OUR_NAMESPACES:
        by_namespace[namespace_name] = _check_structure_namespace(pack, biomes, pool_ids, namespace_name)
    if not by_namespace[NAMESPACE]:
        fail(f"data/{NAMESPACE}/worldgen/structure 下没有任何 structure。")
    return by_namespace


def _check_structure_namespace(
    pack: Pack, biomes: set[str], pool_ids: set[str], namespace_name: str,
) -> set[str]:
    relative_root = f"data/{namespace_name}/worldgen/structure"
    files = pack.list_files(relative_root, ".json")
    if not files:
        return set()

    structure_ids = {f"{namespace_name}:" + relative[: -len(".json")] for relative in files}
    for relative in files:
        # 作者命名空间保持原样的标签；公开那份带上命名空间，两种布局在同一份输出里不会混。
        label = f"structure/{relative}" if namespace_name == NAMESPACE else f"structure/{namespace_name}/{relative}"
        try:
            structure = pack.read_json(f"{relative_root}/{relative}")
        except json.JSONDecodeError as error:
            fail(f"{label}: 不是合法 JSON：{error}")
            continue
        if not isinstance(structure, dict):
            fail(f"{label}: 顶层不是 JSON 对象。")
            continue

        structure_type = structure.get("type")
        if not isinstance(structure_type, str) or not structure_type.startswith("minecraft:"):
            fail(
                f"{label}: type 是 {structure_type!r}；数据包只能表达 minecraft:* 的"
                "结构类型（jigsaw / fortress / bastion 等）。"
            )

        check_biome_tag(pack, structure, biomes, label)

        start_pool = structure.get("start_pool")
        if not isinstance(start_pool, str):
            fail(f"{label}: 缺少 start_pool。")
            continue
        pool_namespace, _, pool_name = start_pool.partition(":")
        if pool_namespace not in OUR_NAMESPACES:
            fail(f"{label}: start_pool {start_pool} 不在本包的命名空间（{OUR_NAMESPACES}）下。")
            continue
        if start_pool not in pool_ids:
            pool_path = f"data/{pool_namespace}/worldgen/template_pool/{pool_name}.json"
            fail(f"{label}: start_pool {start_pool} 找不到 {pool_path}。")

    return structure_ids


def check_structure_sets(
    pack: Pack, structure_ids: set[str], public_ids: set[str],
) -> int:
    """门禁 4：每个 structure_set 的 placement 合法，且引用的结构都存在。

    ## 为什么也要遍历全部

    新增结构通常会配一个新的 structure_set。只核对写死的那个，等于对新增的放置规则
    完全不做检查 —— 而 `separation >= spacing` 这类参数错误会让原版在启动时直接报错。

    ## 发布后还多两条硬约束

    一旦包里存在公开结构（`data/mumu/worldgen/structure/*.json`），放置集就只允许出现在
    公开命名空间，并且每个公开 id 必须**恰好被引用一次**：

      * 旧 `betternether:` 放置集留着 = 同一座建筑按两套 salt 各生成一次（密度翻倍）；
      * 放置集里写 `betternether:` 的 id = `/locate structure mumu:x` 指向的那份没有放置；
      * 某个公开 id 一次都没被引用 = locate 永远找不到（`jungle_temples` 就只在
        `pyramid` 的集合里，错补一套自己的集合反而会改掉它的密度）。

    源码树（发布前）没有公开定义，这三条不适用，由 `check_public_layout` 打印提示。
    """
    targets: list[tuple[str, str]] = []
    for namespace_name in OUR_NAMESPACES:
        relative_root = f"data/{namespace_name}/worldgen/structure_set"
        targets.extend((namespace_name, relative) for relative in pack.list_files(relative_root, ".json"))
    if not targets:
        fail("data/<namespace>/worldgen/structure_set 下没有任何 structure_set：结构不会被放置。")
        return 0

    published = bool(public_ids)
    placed: dict[str, int] = {}
    for namespace_name, relative in targets:
        relative_root = f"data/{namespace_name}/worldgen/structure_set"
        label = (f"structure_set/{relative}" if namespace_name == NAMESPACE
                 else f"structure_set/{namespace_name}/{relative}")
        try:
            structure_set = pack.read_json(f"{relative_root}/{relative}")
        except json.JSONDecodeError as error:
            fail(f"{label}: 不是合法 JSON：{error}")
            continue
        if not isinstance(structure_set, dict):
            fail(f"{label}: 顶层不是 JSON 对象。")
            continue

        if published and namespace_name == NAMESPACE:
            fail(f"{label}: 公开结构已发布，旧命名空间仍保留放置集 —— "
                 f"同一座建筑会按两套 salt 各生成一座。放置集必须只在 "
                 f"{PUBLIC_STRUCTURE_NAMESPACE}: 下，且旧定义只用于读历史 starts。")

        placement = structure_set.get("placement") or {}
        spacing = placement.get("spacing")
        separation = placement.get("separation")
        if isinstance(spacing, int) and isinstance(separation, int) and separation >= spacing:
            fail(f"{label}: separation({separation}) 必须小于 spacing({spacing})，否则原版直接报错。")

        entries = structure_set.get("structures")
        if not isinstance(entries, list) or not entries:
            fail(f"{label}: structures 不是非空数组。")
            continue
        for index, entry in enumerate(entries):
            target = entry.get("structure") if isinstance(entry, dict) else None
            if not isinstance(target, str):
                fail(f"{label}: structures[{index}] 缺少 structure 字段。")
                continue
            if not target.startswith(tuple(f"{name}:" for name in OUR_NAMESPACES)):
                fail(f"{label}: structures[{index}] 引用 {target} 不在本包命名空间"
                     f"（{OUR_NAMESPACES}）。")
                continue
            if published and target not in public_ids:
                fail(f"{label}: structures[{index}] 引用 {target}；公开结构已发布，放置集应引用 "
                     f"{PUBLIC_STRUCTURE_NAMESPACE}: 的那份，否则 /locate structure "
                     f"{PUBLIC_STRUCTURE_NAMESPACE}:<名字> 指向的结构从未被放置。")
            if target not in structure_ids:
                target_path = f"data/{target.split(':', 1)[0]}/worldgen/structure/{target.split(':', 1)[1]}.json"
                fail(f"{label}: 引用了不存在的结构 {target}（缺 {target_path}）。")
            placed[target] = placed.get(target, 0) + 1

    for rid in sorted(public_ids):
        count = placed.get(rid, 0)
        if count == 0:
            fail(f"{PUBLIC_STRUCTURE_NAMESPACE}: {rid} 没有被任何 structure_set 放置："
                 f"/locate structure {rid} 永远找不到，也不会自然生成。")
        elif count > 1:
            fail(f"{PUBLIC_STRUCTURE_NAMESPACE}: {rid} 被放置 {count} 次：同一格会各生成一座，密度翻倍。")

    return len(targets)


def check_public_layout(pack: Pack, legacy_ids: set[str], public_ids: set[str]) -> None:
    """发布布局：公开 id 与作者定义一一对应，旧定义照旧发布。

    ## 两条都必须成立

      * 每个公开 id 都要有作者原 id 的定义 —— 已存档区块把 `structures.starts` 按生成时的
        id 记在 chunk 里，删掉旧定义，旧世界里的建筑会变成 "Unknown structure id"，
        继续建造时丢掉进度；
      * 每个作者定义都要有公开 id 的副本 —— 少一个，那座建筑就既不能被 locate，也不会
        被放置（放置集只引用公开 id，见 `check_structure_sets`）。

    源码树（发布前）没有 `data/mumu/worldgen/structure`，这两条不适用：那里是
    `scripts/prepare_structure_resources.py` 的输入，真正的发布布局由构建期从它生成。
    """
    if not public_ids:
        note(
            "校验目标里没有 " + f"data/{PUBLIC_STRUCTURE_NAMESPACE}/worldgen/structure"
            + "：这是发布前的源布局，公开结构 id 的布局校验只在 processResources 产物与 jar 上执行。"
        )
        return

    legacy_names = {rid.split(":", 1)[1] for rid in legacy_ids}
    public_names = {rid.split(":", 1)[1] for rid in public_ids}
    for name in sorted(public_names - legacy_names):
        fail(
            f"{PUBLIC_STRUCTURE_NAMESPACE}:{name} 有公开定义，但 "
            f"data/{NAMESPACE}/worldgen/structure/{name}.json 不见了：旧世界的区块数据"
            "按作者原 id 记 starts，缺了它会在继续建造时丢掉进度。"
        )
    for name in sorted(legacy_names - public_names):
        fail(
            f"data/{NAMESPACE}/worldgen/structure/{name}.json 没有对应的公开定义 "
            f"{PUBLIC_STRUCTURE_NAMESPACE}:{name}：它既不能被 locate，也没有放置集引用它。"
        )


def main(argv: list[str]) -> int:
    target = Path(argv[1]) if len(argv) > 1 else DEFAULT_PACK
    pack, biome_pack = open_stack(target)
    if pack is None:
        for message in FAILURES:
            print(f"  - {message}")
        return 1

    check_pack_meta(pack)
    check_registry_shapes(pack)
    templates, palettes = check_templates(pack)
    pools, pool_ids = check_pools(pack, templates)
    ids_by_namespace = check_structures(pack, collect_biomes(biome_pack), pool_ids)
    legacy_ids = ids_by_namespace[NAMESPACE]
    public_ids = ids_by_namespace[PUBLIC_STRUCTURE_NAMESPACE]
    structure_sets = check_structure_sets(pack, legacy_ids | public_ids, public_ids)
    check_public_layout(pack, legacy_ids, public_ids)
    check_palette_blocks(palettes, collect_registered_blocks(target))

    print(f"校验目标：{target}")
    print(f"  NBT 模板 {len(templates)} 个，template_pool {pools} 个，"
          f"structure {len(legacy_ids)} 个（{NAMESPACE}）/ {len(public_ids)} 个"
          f"（{PUBLIC_STRUCTURE_NAMESPACE}），structure_set {structure_sets} 个")
    palette_blocks = {block for blocks in palettes.values() for block in blocks}
    print(f"  调色板方块 {len(palette_blocks)} 种")
    for message in NOTES:
        print(f"  注意：{message}")

    if FAILURES:
        print(f"\n失败 {len(FAILURES)} 项：")
        for message in FAILURES:
            print(f"  - {message}")
        return 1

    print("全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""校验 BetterNether 的 Shadow 产物。

用法：
    python tools/verify_jar.py jars/BetterNether-<version>.jar

它只读 jar，不改任何东西。任何一条不满足就非零退出——这道门禁挂在 build / assemble /
publish 的关键路径上，失败即阻断发布。

门禁清单（每条都对应一个真实事故面）：
  1. 两个数据包都在，且都有 pack.mcmeta（缺了插件会拒绝启用，或被服务器当成非数据包）。
  2. 群系 JSON 集合与 `NetherBiomes.ALL` **逐项相等**（不是"至少 23 个"——那个数字一旦
     写死在门禁里，就成了和生成器共享的期待值，两边一起错时门禁照样通过）。
  3. **群系里不得残留 `betternether:` 实体引用**——这是 L1，残留会让 worldgen 注册表
     Unbound、服务器启动中止。
  4. 群系里引用的本包 feature / carver 必须真的有对应文件，或在显式 waiver 里说明。
  5. CE 资源包有 pack.yml，且 namespace 是 betternether。
  6. SnakeYAML 已重定位到 dev/mumu/betternether/libs/yaml，且不残留未重定位的 org/yaml。
  7. 不得把服务端 API 或 CraftEngine 打进来（org/bukkit、io/papermc、net/momirealms）。
  8. 描述符里的版本号已被展开（不残留 ${version}）。
  9. **CE 释放清单双向闭合**：清单里每一行都要有对应文件，`craftengine/betternether/`
    下每个文件也都必须在清单里。少一行 = 运行期不释放 = 游戏里缺资源；多一行 = 死条目。
 10. 资源包自洽性——直接复用 `verify_assets.py`，不复制第二份实现：CE 配置里每个
     `texture:` 都要落到真图、每个模型引用的贴图都要在包里、模型内不得有循环纹理引用、
     每个方块都要有可渲染的外观、物品配置的 `model:` 不得指向不存在的模型文件。
     这几条对应「启动刷几百行缺少纹理、游戏内紫黑格子」与「模型循环纹理引用」两类事故。
 11. 群系地表/植被表覆盖闭合——直接复用 `verify_biome_coverage.py`。
"""

from __future__ import annotations

import json
import re
import sys
import zipfile
from pathlib import Path

# 同目录的门禁脚本：只有一份实现，这里 import 复用，避免两份判定逻辑漂移。
sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_assets
import verify_biome_coverage

MOD_NS = "betternether:"
# 历史前缀：产物里出现它说明某处没改干净
STALE_NS = "mumu:"

# 公开结构 id 的命名空间：`/locate structure mumu:<建筑名>` 依赖它，见
# `scripts/bnlib/public_structures.py`。它**只**用来给结构自己的 id 起名 ——
# 方块、物品、群系、模板池、处理器、NBT 调色板一律仍是 MOD_NS，所以这里只放行
# 「`data/mumu/worldgen/structure*` 文档里作为结构 id 出现的 mumu: 字符串」，
# 其余位置出现 mumu: 仍然是残留（会被静默跳过或注册表 Unbound）。
PUBLIC_STRUCTURE_NS = "mumu:"
PUBLIC_STRUCTURE_ROOT = "datapacks/betternether-structures/data/mumu/worldgen/"

# CE 包在 jar 里的根，以及它旁边的释放清单。
CE_PACK_ROOT = "craftengine/betternether/"
CE_MANIFEST = "craftengine/betternether.manifest.txt"
# 群系包在 jar 里的根（构建期由 scripts/extract_bn.py 生成）。
BIOME_PACK_ROOT = "datapack/"
BIOME_DIR = "datapack/data/betternether/worldgen/biome/"

# 群系里出现、但本移植刻意不提供的 feature / carver。
#
# 键是完整 id，值是「为什么可以不存在」的理由 —— 豁免必须是可复核的一句话，不能是空值。
# 留空表示「群系里引用的每个 betternether: feature 都必须有对应的数据包文件」。
# 现状：scripts/bnlib/biomes.py 会把模组 feature 引用整段摘除（模组 configured_feature
# 类型无法用数据包表达），所以正常情况下这张表不需要条目；它存在是为了将来改成
# 「数据包 feature + 插件 populator 混合」时不至于无路可走。
FEATURE_WAIVERS: dict[str, str] = {}

FAILURES: list[str] = []
NOTES: list[str] = []


def fail(message: str) -> None:
    FAILURES.append(message)


def note(message: str) -> None:
    NOTES.append(message)


def read_json_from_zip(archive: zipfile.ZipFile, name: str):
    with archive.open(name) as handle:
        return json.loads(handle.read().decode("utf-8"))


def walk_strings(node):
    """遍历 JSON 里的所有字符串，用于找残留引用。"""
    if isinstance(node, dict):
        for value in node.values():
            yield from walk_strings(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk_strings(item)
    elif isinstance(node, str):
        yield node


def allowed_public_structure_ids(name: str, document) -> set[str]:
    """`data/mumu/worldgen/structure_set/*` 里作为**结构 id** 出现的 `mumu:` 字符串。

    只有公开放置集的 `structures[].structure` 有资格用 `mumu:` —— 那是 `/locate
    structure mumu:<建筑名>` 要解析的 id。同一个文件里的其它位置（`placement`、
    任何处理器/方块/模板池引用）出现 `mumu:` 仍然算残留，照样报错。

    公开**定义**（`data/mumu/worldgen/structure/*.json`）一个 `mumu:` 都不该有：
    它的 `start_pool` / `biomes` 仍指 `betternether:`，模板池与群系 tag 没有迁移。
    """
    if not name.startswith(PUBLIC_STRUCTURE_ROOT + "structure_set/"):
        return set()
    entries = document.get("structures") if isinstance(document, dict) else None
    allowed: set[str] = set()
    if isinstance(entries, list):
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            target = entry.get("structure")
            if isinstance(target, str) and target.startswith(PUBLIC_STRUCTURE_NS):
                allowed.add(target)
    return allowed


def check_no_upstream_namespace(archive: zipfile.ZipFile, names: set[str]) -> None:
    """产物里不得残留**过期**的命名空间 `mumu:` 的资源引用。

    ## 为什么这条必须存在

    这个移植的命名空间改过两次：上游 `betternether` → `mumu` → 又改回 `betternether`。
    每次改名，产物里残留旧前缀都会静默出错：

      * 在数据包里 —— 指向一个本插件从未注册的 id，注册表解析时 Unbound，服务器启动中止；
      * 在结构 NBT 的方块调色板里 —— 城市里那个方块找不到定义，生成时被跳过，玩家看到残缺建筑。

    后者尤其危险：它不会报错，只是少了几块砖。所以这里同时扫 JSON 与 NBT 的字符串。

    ## 为什么判据是 STALE_NS 而不是 UPSTREAM_NS

    当前 `NAMESPACE` 就**等于**上游的 `betternether`，所以「含 betternether: 就报错」是错的判据 ——
    那会把每一个正确的文件都判成坏的。真正的判据是「含用过但已废弃的前缀」。
    只扫带冒号的资源引用，`dev/mumu/betternether/` 这样的**包名**是合法的，不能误报。
    """
    scanned = 0
    for name in sorted(names):
        is_json = name.startswith(("datapack/", "datapacks/")) and name.endswith(".json")
        is_nbt = name.startswith("datapacks/") and name.endswith(".nbt")
        is_ce = name.startswith("craftengine/") and name.endswith((".yml", ".json"))
        if not (is_json or is_nbt or is_ce):
            continue
        scanned += 1
        with archive.open(name) as handle:
            raw = handle.read()

        if is_nbt:
            # NBT 是 gzip 压缩的二进制；解压后字符串是明文，可以直接找。
            try:
                import gzip
                payload = gzip.decompress(raw)
            except OSError as error:
                fail(f"{name} 不是合法 gzip：{error}")
                continue
            if STALE_NS.encode() in payload:
                fail(f"{name} 的方块调色板仍引用过期前缀 {STALE_NS}，"
                     f"该结构里的方块将因找不到定义而被跳过。")
            continue

        text = raw.decode("utf-8", errors="replace")
        if STALE_NS not in text:
            continue
        if name.startswith(PUBLIC_STRUCTURE_ROOT):
            # 公开结构文档：只放行 `structure_set.structures[].structure` 上的 mumu: id，
            # 其它位置一律仍按残留处理。
            try:
                document = json.loads(text)
            except json.JSONDecodeError as error:
                fail(f"{name} 不是合法 JSON：{error}")
                continue
            allowed = allowed_public_structure_ids(name, document)
            stray = sorted({
                value for value in walk_strings(document)
                if STALE_NS in value and value not in allowed
            })
            if stray:
                fail(f"{name} 里有 {len(stray)} 处过期前缀 {STALE_NS} 的引用"
                     f"（{('、'.join(stray[:3]))}{'…' if len(stray) > 3 else ''}）；"
                     f"公开结构文档只允许 structure_set 的 structures[].structure 使用 "
                     f"{PUBLIC_STRUCTURE_NS}，其余引用应为 {MOD_NS}。")
            continue
        fail(f"{name} 仍引用过期前缀 {STALE_NS}（应为 {MOD_NS}）。")

    if scanned == 0:
        fail("没有扫描到任何数据包或 CE 资源，校验路径可能已失效。")


def check_datapacks(archive: zipfile.ZipFile, names: set[str]) -> None:
    expected = {
        "datapack": "群系包",
        "datapacks/betternether-structures": "结构包",
    }
    for directory, label in expected.items():
        meta = f"{directory}/pack.mcmeta"
        if meta not in names:
            fail(f"{label}缺少 {meta}：插件会在引导阶段拒绝启用。")
            continue
        try:
            payload = read_json_from_zip(archive, meta)
            pack = payload.get("pack", {})
            if "min_format" not in pack:
                fail(f"{meta} 缺少 min_format。")
        except json.JSONDecodeError as error:
            fail(f"{meta} 不是合法 JSON：{error}")


def check_biomes(archive: zipfile.ZipFile, names: set[str]) -> int:
    prefix = "datapack/data/betternether/worldgen/biome/"
    biomes = sorted(name for name in names if name.startswith(prefix) and name.endswith(".json"))
    if len(biomes) < 23:
        fail(f"群系 JSON 只有 {len(biomes)} 个，应为 23 个。")

    for name in biomes:
        try:
            biome = read_json_from_zip(archive, name)
        except json.JSONDecodeError as error:
            fail(f"{name} 不是合法 JSON：{error}")
            continue

        # L1 门禁：实体引用
        attributes = biome.get("attributes")
        if isinstance(attributes, dict):
            spawns = attributes.get("minecraft:gameplay/natural_mob_spawns")
            if isinstance(spawns, dict):
                argument = spawns.get("argument")
                if isinstance(argument, dict):
                    by_category = argument.get("spawns_by_category")
                    if isinstance(by_category, dict):
                        for category, entries in by_category.items():
                            if not isinstance(entries, list):
                                continue
                            for entry in entries:
                                if isinstance(entry, dict):
                                    kind = entry.get("type")
                                    if isinstance(kind, str) and kind.startswith(MOD_NS):
                                        fail(
                                            f"{name}: 分类 {category} 引用模组实体 {kind}，"
                                            "会让 worldgen 注册表解析失败、服务器启动中止。"
                                        )

        # 模组 feature 引用（configured_feature 的 type 是模组自定义，数据包无法表达）
        slots = biome.get("features")
        if isinstance(slots, list):
            for slot in slots:
                if not isinstance(slot, list):
                    continue
                for feature in slot:
                    if isinstance(feature, str) and feature.startswith(MOD_NS):
                        fail(
                            f"{name}: 仍引用模组 feature {feature}；"
                            "数据包无法表达模组 configured_feature 类型。"
                        )
    return len(biomes)


def biome_keys_from_source() -> set[str] | None:
    """从 `NetherBiomes.ALL` 读出本工程声明的群系 key 集合。

    ## 为什么用 Java 源码当基准，而不是在门禁里写死一个数字

    旧版门禁写的是「群系 JSON 少于 23 个就失败」。那个 23 与生成器共享同一个易错期待值：
    如果生成器漏掉一个群系、而门禁也按 23 判，两边一起错，门禁照样通过。
    真正的基准是运行期注册表（`NetherBiomes.ALL`）——它决定服务器会去加载哪些群系 id。
    这里读源码做集合相等比对，两个方向都能抓住：少生成（注册表里有、jar 里没有）与
    多生成（jar 里有、注册表不认，白占体积且可能指向不存在的群系）。

    读不到源码时返回 None，由调用方降级为「只报数量」——而 `verify_biome_coverage`
    同样依赖这份源码，它会在那种情况下直接失败，所以不会出现两条都静默通过。
    """
    java = verify_biome_coverage.BIOMES
    if not java.is_file():
        return None
    text = java.read_text(encoding="utf-8")
    keys = set(re.findall(r'new Entry\("([a-z0-9_]+)"', text))
    return keys or None


def check_biome_registry(archive: zipfile.ZipFile, names: set[str]) -> int:
    """门禁 2：jar 里的群系集合必须与 `NetherBiomes.ALL` 完全一致。"""
    biomes = sorted(name for name in names if name.startswith(BIOME_DIR) and name.endswith(".json"))
    present = {name[len(BIOME_DIR): -len(".json")] for name in biomes}
    if not present:
        fail(f"jar 里没有 {BIOME_DIR}*.json，群系包是空的。")
        return 0

    declared = biome_keys_from_source()
    if declared is None:
        note("读不到 NetherBiomes.java，群系集合未与注册表逐项比对（verify_biome_coverage 会独立失败）。")
        return len(present)

    for key in sorted(declared - present):
        fail(
            f"NetherBiomes.ALL 声明了群系 {key}，但 jar 里没有 "
            f"{BIOME_DIR}{key}.json；该群系在世界里会是未注册状态。"
        )
    for key in sorted(present - declared):
        fail(
            f"jar 里有群系 {key}.json，但 NetherBiomes.ALL 没有声明它；"
            "这是不会被加载的死文件（或注册表漏了一条）。"
        )
    return len(present)


def check_feature_refs(archive: zipfile.ZipFile, names: set[str]) -> None:
    """门禁 4：群系引用的本包 feature / carver 必须闭合。

    ## 这条为什么必须存在

    `features` 里写一个 `betternether:xxx` 而包里没有对应 placed_feature 文件时，原版
    会在注册表解析阶段把该引用判为 Unbound，**整个服务器启动中止**。摘除引用（biomes.py
    的做法）是安全的；留着悬空引用是致命的。中间没有"警告一下继续跑"这种状态。

    反过来，本包自己产出的 placed_feature / configured_feature 文件也要形状正确：
    缺 `feature` / `type` 的文件会在加载期被丢弃，而方块照样声称自己会生成这片植被。
    """
    biomes = sorted(name for name in names if name.startswith(BIOME_DIR) and name.endswith(".json"))
    if not biomes:
        return

    mod_refs = 0
    for name in biomes:
        biome = read_json_from_zip(archive, name)
        if not isinstance(biome, dict):
            continue

        # features 是「按生成阶段分槽的二维数组」。
        slots = biome.get("features")
        if isinstance(slots, list):
            for slot in slots:
                if not isinstance(slot, list):
                    continue
                for feature in slot:
                    if not isinstance(feature, str) or not feature.startswith(MOD_NS):
                        continue
                    mod_refs += 1
                    path = f"datapack/data/betternether/worldgen/placed_feature/{feature.split(':', 1)[1]}.json"
                    if path not in names and feature not in FEATURE_WAIVERS:
                        fail(
                            f"{name}: features 引用了 {feature}，但 jar 里没有 {path}；"
                            "注册表会 Unbound，服务器启动中止。"
                            "（确实不提供的，请在 verify_jar.FEATURE_WAIVERS 里写明理由。）"
                        )

        # carvers 是按槽位的「单个 id 或 id 数组」。
        carvers = biome.get("carvers")
        if isinstance(carvers, dict):
            for slot, value in carvers.items():
                entries = value if isinstance(value, list) else [value]
                for carver in entries:
                    if not isinstance(carver, str) or not carver.startswith(MOD_NS):
                        continue
                    mod_refs += 1
                    path = f"datapack/data/betternether/worldgen/configured_carver/{carver.split(':', 1)[1]}.json"
                    if path not in names and carver not in FEATURE_WAIVERS:
                        fail(
                            f"{name}: carvers.{slot} 引用了 {carver}，但 jar 里没有 {path}；"
                            "注册表会 Unbound，服务器启动中止。"
                        )

    # 本包自己产出的 worldgen 文件也要形状正确。
    produced = 0
    for name in sorted(names):
        if name.startswith("datapack/data/betternether/worldgen/placed_feature/") and name.endswith(".json"):
            produced += 1
            payload = read_json_from_zip(archive, name)
            if not isinstance(payload, dict) or not isinstance(payload.get("feature"), str):
                fail(f"{name}: placed_feature 缺少 feature 字段；原版会丢弃这个文件，植被不会生成。")
        elif name.startswith("datapack/data/betternether/worldgen/configured_feature/") and name.endswith(".json"):
            produced += 1
            payload = read_json_from_zip(archive, name)
            if not isinstance(payload, dict) or not isinstance(payload.get("type"), str):
                fail(f"{name}: configured_feature 缺少 type 字段；原版会丢弃这个文件。")

    if mod_refs == 0:
        note(
            f"群系里没有 {MOD_NS} feature/carver 引用（由 bnlib/biomes.py 摘除，"
            "植被由插件侧 populator 承担）；feature 闭合只覆盖了本包自产的 "
            f"{produced} 个 worldgen 文件。"
        )
    else:
        note(f"群系里 {mod_refs} 条 {MOD_NS} feature/carver 引用已逐条核对闭合。")


def check_ce_manifest(archive: zipfile.ZipFile, names: set[str]) -> None:
    """门禁 9：CE 释放清单与包内容双向闭合。

    ## 为什么这条是「生成物不静默跳过」的核心

    插件在运行期照着 `craftengine/betternether.manifest.txt` 逐个 `getResource` 释放文件
    （见 `write_ce_manifest` 的说明：shadowJar 不写目录条目，遍历 jar 目录拿不到文件）。

    于是清单与真实内容之间任何一处不一致都是静默失败：

      * 清单少一行 → 那个文件永远不释放。CE 报「包已加载」，但方块缺模型/贴图。
      * 清单多一行 → 每次启动都尝试释放一个不存在的资源，日志里是"缺失"，实际是死条目。

    这正是并行开发最容易踩的坑：某个生成器在 `write_ce_manifest` **之后**写文件。
    所以这里两个方向都查。
    """
    if CE_MANIFEST not in names:
        fail(f"缺少 CE 释放清单 {CE_MANIFEST}：插件运行期无从知道该释放哪些文件。")
        return

    with archive.open(CE_MANIFEST) as handle:
        raw = handle.read().decode("utf-8", errors="replace")
    if "\r" in raw:
        # Windows 上 write_text 会把 '\n' 转成 '\r\n'，Java 按行切分后每行尾随 '\r'，
        # getResource 全部返回 null —— 清单看着有几千条，实际一条都读不到。
        fail(f"{CE_MANIFEST} 含 CR 字符：Java 侧按行读取后路径会尾随 \\r，全部释放失败。")
        return

    listed = [line for line in raw.split("\n") if line]
    if not listed:
        fail(f"{CE_MANIFEST} 是空的：运行期一个文件都不会释放。")
        return
    if len(set(listed)) != len(listed):
        fail(f"{CE_MANIFEST} 里有重复条目：清单应当每条文件路径只出现一次。")

    actual = {
        name[len(CE_PACK_ROOT):]
        for name in names
        if name.startswith(CE_PACK_ROOT) and not name.endswith("/")
    }
    for relative in sorted(set(listed) - actual):
        fail(
            f"{CE_MANIFEST} 列了 {relative}，但 jar 里没有 {CE_PACK_ROOT}{relative}；"
            "运行期释放时会报缺失（死条目）。"
        )
    for relative in sorted(actual - set(listed)):
        fail(
            f"{CE_PACK_ROOT}{relative} 没有出现在 {CE_MANIFEST} 里；"
            "运行期不会释放它 —— 游戏里表现为「配置存在但资源缺失」。"
        )

    # 内容类别的存在性：清单闭合但整类内容缺失同样是"静默跳过"。
    for directory, label in (
        ("configuration/blocks/", "方块配置"),
        ("configuration/items/", "物品配置"),
        ("configuration/langs/", "语言文件"),
    ):
        if not any(relative.startswith(directory) for relative in actual):
            fail(f"CE 包里没有任何{label}（{CE_PACK_ROOT}{directory}）：那部分内容整个缺失。")


def check_biome_coverage() -> None:
    """门禁 11：复用 `verify_biome_coverage.py`，不复制第二份实现。

    它校验的是「群系 key ↔ 地表定义 ↔ 植被定义」三张表是否闭合，与 jar 内容无关，
    所以直接跑那份脚本即可（Gradle 侧也有独立的 verifyBiomeCoverage 任务）。
    """
    print("[门禁] 运行 verify_biome_coverage.py …")
    code = verify_biome_coverage.main()
    if code != 0:
        fail("群系覆盖校验未通过（见上方 verify_biome_coverage 的输出）。")


def check_craftengine(archive: zipfile.ZipFile, names: set[str]) -> None:
    pack_yml = "craftengine/betternether/pack.yml"
    if pack_yml not in names:
        fail(f"缺少 CE 资源包描述符 {pack_yml}。")
        return
    with archive.open(pack_yml) as handle:
        text = handle.read().decode("utf-8")
    if "namespace: betternether" not in text:
        fail(f"{pack_yml} 的 namespace 不是 betternether。")

    # 语言文件按「目录里至少一个 yml，且里面确实有 en_us / zh_cn 两层的段」判断：
    # `configuration/langs/` 现在只有一个文件（`bn-lang.yml`），多个语种是同一文件里的
    # 不同段/不同内层键 —— CE 的语言码取自段的内层键，与文件名无关。
    langs = sorted(name for name in names
                   if name.startswith("craftengine/betternether/configuration/langs/")
                   and name.endswith(".yml"))
    if not langs:
        fail("缺少语言文件：craftengine/betternether/configuration/langs/ 下没有任何 yml。")
        return
    for code in ("en_us", "zh_cn"):
        if not any(f"  {code}:" in archive.read(lang).decode("utf-8") for lang in langs):
            fail(f"语言文件里没有 {code} 这一层（{langs}）；CE 侧对应语种的整段译名会缺失。")


def check_relocation(archive: zipfile.ZipFile, names: set[str]) -> None:
    relocated = [name for name in names if name.startswith("dev/mumu/betternether/libs/yaml/")]
    if not relocated:
        fail("SnakeYAML 未被重定位到 dev/mumu/betternether/libs/yaml。")

    leaked = [name for name in names if name.startswith("org/yaml/snakeyaml/")]
    if leaked:
        fail(f"存在未重定位的 SnakeYAML 类 {len(leaked)} 个，会与服务端内置 org.yaml 冲突。")


def check_exclusions(names: set[str]) -> None:
    forbidden = {
        "org/bukkit/": "Bukkit API",
        "io/papermc/": "Paper API",
        "net/momirealms/": "CraftEngine",
        "net/kyori/": "Adventure",
    }
    for prefix, label in forbidden.items():
        hits = [name for name in names if name.startswith(prefix)]
        if hits:
            fail(f"产物里打进了 {label}（{len(hits)} 个条目），运行期必须由服务端提供。")


def check_descriptor(archive: zipfile.ZipFile, names: set[str]) -> None:
    if "paper-plugin.yml" not in names:
        fail("缺少 paper-plugin.yml。")
        return
    with archive.open("paper-plugin.yml") as handle:
        text = handle.read().decode("utf-8")
    if "${version}" in text:
        fail("paper-plugin.yml 的 ${version} 未被展开。")
    for required in ("bootstrapper:", "CraftEngine"):
        if required not in text:
            fail(f"paper-plugin.yml 缺少 {required}。")


def check_asset_coherence(jar: Path) -> None:
    """资源包自洽性，复用 `tools/verify_assets.py`。

    ## 为什么必须存在

    CE 只把这些当 WARN 刷在日志里，游戏内却是紫黑格子，很容易漏掉：

      * 模型引用一张包里没有的贴图（上游有一批方块的贴图名与方块名不同）；
      * 模型内 `"particle": "#particle"` 这类自引用，CE 报「存在循环纹理引用」；
      * 方块既没有模型文件、`state.texture` 也落不到真图；
      * CE 配置里 `model:` 指向不存在的模型文件。

    ## 为什么不在这里再写一遍

    这些判定需要解析 CE 的 yml、遍历 models 与 textures、做别名图找环。
    重复实现必然与 `verify_assets.py` 漂移，所以这里只做汇总。
    """
    report = verify_assets.verify(jar)
    for message in report.failures:
        fail(message)
    for message in report.notes:
        note(message)


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: verify_jar.py <jar 路径>", file=sys.stderr)
        return 2

    jar = Path(sys.argv[1])
    if not jar.is_file():
        print(f"找不到产物：{jar}", file=sys.stderr)
        return 2

    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        check_datapacks(archive, names)
        biome_count = check_biome_registry(archive, names)
        check_biomes(archive, names)
        check_feature_refs(archive, names)
        check_ce_manifest(archive, names)
        check_craftengine(archive, names)
        check_no_upstream_namespace(archive, names)
        check_relocation(archive, names)
        check_exclusions(names)
        check_descriptor(archive, names)

    check_asset_coherence(jar)
    check_biome_coverage()

    for message in NOTES:
        print(f"[提示] {message}")
    if FAILURES:
        print()
        for message in FAILURES:
            print(f"[失败] {message}")
        print(f"\n产物校验未通过：{len(FAILURES)} 项。")
        return 1

    print(f"产物校验通过：{jar.name}（群系 {biome_count} 个，条目 {len(names)} 个）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""从上游 BetterNether（release/26.3）生成插件用的数据包与 CraftEngine 资源。

用法：
    python scripts/extract_bn.py --upstream upstream/BetterNether --output plugin/build/generated/bn

设计约束：
  * 只读上游，只写 --output。上游目录保持原样，方便随时重新拉取对比。
  * 幂等：重复运行得到同样结果，不依赖上一次的输出。
  * 失败要响：任何一条群系净化后仍引用模组实体，直接非零退出，绝不写出一份"看起来能跑"的产物。
"""

from __future__ import annotations

import argparse
import importlib
import inspect
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))

from bnlib import biomes as biome_rules  # noqa: E402
from bnlib import blocks as block_rules  # noqa: E402
from bnlib import models as model_synth  # noqa: E402
from bnlib import nbt  # noqa: E402
from bnlib import ore_drops  # noqa: E402
from bnlib import structures  # noqa: E402
from bnlib import surface_rules as surface_rule_copy  # noqa: E402
from bnlib import worldgen as worldgen_copy  # noqa: E402
from bnlib.namespace import NAMESPACE, UPSTREAM_PREFIX, is_foreign, rewrite_id  # noqa: E402

# 26.3 的数据包格式。凭据是参考工程已交付并在 26.3 上实测可用的 pack.mcmeta：
#   min_format: [121, 0]，群系包 max_format: 32767（宽松上界，允许未来版本继续加载）。
PACK_MIN_FORMAT = [121, 0]
PACK_MAX_FORMAT_OPEN = 32767

# 已发布配置里引用显示文本的写法：`<lang:block.betternether.x>` / `<lang:item.betternether.y>`。
# 语言条目要覆盖这个正则扫到的一切键（见 build_langs），否则游戏里显示裸键。
LANG_REF_PATTERN = re.compile(r"<lang:([A-Za-z0-9_.:/-]+)>")


# ---------------------------------------------------------------------------
# 生成器钩子：其他模块的统一入口
# ---------------------------------------------------------------------------
#
# 约定（唯一契约，新增模块照此实现即可被调度）：
#
#     # scripts/bnlib/<模块名>.py
#     def generate(
#         upstream: Path,
#         ce_root: Path,
#         selected: Sequence[str] = (),
#     ) -> dict[str, object]: ...
#
# 参数：
#   * `upstream` —— 上游 BetterNether 源码根，**只读**。
#   * `ce_root`  —— CE 包根（含 `pack.yml` 的那一层）。只允许在这里面写。
#   * `selected` —— 本次真正生成的方块 id 清单（`block-manifest.json` 的顺序）。
#                  资产/物品/实体这类「按方块过滤」的模块用它；不过滤的模块忽略即可。
#
# 返回 dict 至少包含：
#   * `files`       int        —— 本次写出的文件数（0 表示什么都没写，会被判为失败）
#   * `warnings`    list[str]  —— 已生成但存在缺口的地方（可见、可追踪，不阻断构建）
#   * `unsupported` list[str]  —— 明确不做/无法移植的条目（**显式声明**，而不是静默缺失）
#   可选 `written`  list[str]  —— 写出的相对路径，便于排障
#
# 失败必须抛异常。禁止 try/except 吞掉后返回空 dict —— 那等于静默产出空内容。
#
# ## 为什么用「发现 + 声明顺序」而不是硬编码一串 import
#
# 并行开发时模块会陆续落地，硬编码 import 会让「模块还没写完」和「模块写错了」变成
# 同一个 ImportError。这里改为扫描 bnlib/ 目录：源码里出现 `def generate(` 的模块才
# 被 import，其余模块根本不加载（避免把库模块的导入副作用带进来）。
#
# ## 为什么缺失也要失败
#
# HOOK_ORDER 里列出的是**必须存在**的生成器。它没落地，就意味着物品/资产/实体那部分
# 内容整个缺失 —— 而缺失的产物不会在游戏里报错，只会表现为「某些方块没有掉落物」
# 这类静默缺口。所以这里直接非零退出，报错信息给出模块名与契约。
HOOK_ORDER: tuple[str, ...] = ("assets", "items", "entity")

# 钩子函数名。所有生成器模块共用这一个入口名。
HOOK_API = "generate"

# 返回契约里必须出现的键与类型。缺一个就说明模块没有按契约汇报，构建期拦下。
REQUIRED_REPORT_KEYS: tuple[tuple[str, type], ...] = (
    ("files", int),
    ("warnings", list),
    ("unsupported", list),
)


def _accepts_selected(hook: Callable) -> bool:
    """钩子是否接受 `selected` 关键字参数。

    契约是 3 参，但并行开发期可能有模块停在早期的 2 参写法。这里按签名判断而不是
    一律传关键字：2 参模块会被**显式**记为签名漂移（进报告 + 打印），而不是让整条
    构建线因为一个 TypeError 停在一个看不出原因的位置。
    """
    try:
        parameters = inspect.signature(hook).parameters
    except (TypeError, ValueError):
        return False
    if "selected" in parameters:
        return True
    return any(p.kind is inspect.Parameter.VAR_KEYWORD for p in parameters.values())


def discover_generators() -> list[tuple[str, Callable]]:
    """按 HOOK_ORDER 优先、其余按名字排序，返回 [(模块名, generate 函数)]。

    ## 别名模块（`entity.py` 再导出 `entities.py`）

    契约要求必需模块的文件名是 `entity.py`，实现可以放在 `entities.py` 里，由 `entity.py`
    再导出同一个 `generate`。所以这里区分两种命中：

      * **定义** —— `generate.__module__` 就是该模块（真正的实现）；
      * **再导出** —— 别名模块，`generate.__module__` 指向别处。

    HOOK_ORDER 里的名字允许由再导出满足（这正是别名存在的意义），但调度时按
    **实现模块** 去重：否则 `entity` 与 `entities` 会把同一份实现跑两遍（幂等模块不会
    出错，但报告里会出现两条互相覆盖的记录，且顺序变得不可解释）。

    只 import 源码里真的出现 `def generate(` 的模块：既避免无关模块的导入副作用，
    也让「模块存在但忘了实现钩子」不会被误当成已接入。
    """
    package_dir = Path(__file__).with_name("bnlib")
    # 候选模块的判据是「**暴露** generate」，不是「源码里出现 `def generate(`」：
    # 别名模块（`entity.py`）只写 `from bnlib.entities import generate`，源码里没有
    # `def generate(` 字样，按字面过滤会把它整个漏掉，必需模块就永远缺失。
    # 代价是会把所有 bnlib 模块都 import 一次；这些模块都是纯函数/常量定义，
    # 导入没有副作用（真正的写盘都发生在 generate() 调用里）。
    candidates = [
        path for path in sorted(package_dir.glob("*.py"))
        if not path.stem.startswith("_")
    ]
    defined: dict[str, Callable] = {}
    reexported: dict[str, Callable] = {}
    for path in candidates:
        module = importlib.import_module(f"bnlib.{path.stem}")
        hook = getattr(module, HOOK_API, None)
        if not callable(hook):
            continue
        if getattr(hook, "__module__", None) == module.__name__:
            defined[path.stem] = hook
        else:
            reexported[path.stem] = hook

    ordered: list[tuple[str, Callable]] = []
    scheduled: set[str] = set()
    aliases: dict[str, str] = {}
    missing: list[str] = []
    for name in HOOK_ORDER:
        hook = defined.get(name) or reexported.get(name)
        if hook is None:
            missing.append(name)
            continue
        implementation = getattr(hook, "__module__", "").rsplit(".", 1)[-1]
        if implementation in scheduled:
            continue
        ordered.append((name, hook))
        scheduled.add(implementation)
        if implementation != name:
            aliases[name] = implementation

    if missing:
        raise SystemExit(
            "缺少生成器模块：scripts/bnlib/"
            + "、".join(f"{name}.py" for name in missing)
            + f"。它们必须提供 {HOOK_API}(upstream, ce_root, selected=()) -> dict"
            "（返回 files/warnings/unsupported）；"
            "缺失意味着那部分内容会整个不出现在产物里。"
            "（若该模块确实不参与本次交付，请从 extract_bn.py 的 HOOK_ORDER 里移除。）"
        )

    for name in sorted(defined):
        if name in scheduled:
            continue
        ordered.append((name, defined[name]))
        scheduled.add(name)

    for name, implementation in aliases.items():
        print(f"[生成器] {name} 是别名模块，实现来自 bnlib.{implementation}")
    return ordered


def summarize_generator_report(payload: dict) -> str:
    """把生成器的返回值压成一行摘要，避免把整份清单刷进构建日志。"""
    parts = []
    for key, value in payload.items():
        if isinstance(value, (bool, int, str)):
            parts.append(f"{key}={value}")
        elif isinstance(value, (list, tuple)):
            parts.append(f"{key}={len(value)} 项")
        elif isinstance(value, dict):
            parts.append(f"{key}={len(value)} 键")
    return "，".join(parts) if parts else "无统计"


def validate_generator_report(name: str, payload: object) -> dict[str, object]:
    """核对生成器的返回值是否满足契约；不满足直接失败。

    ## 为什么连返回值都要门禁

    「生成器跑了、也没抛异常」不等于「它真的写了东西」。返回契约强制模块报出
    `files`（写了几个文件）、`warnings`（哪些地方有缺口）、`unsupported`（哪些
    明确不移植）。少了这三个，产物是否完整就只能靠人去翻目录 —— 而这一步没人做。
    """
    if not isinstance(payload, dict):
        raise SystemExit(
            f"bnlib.{name}.{HOOK_API} 必须返回 dict，实际返回 {type(payload).__name__}。"
        )
    for key, expected in REQUIRED_REPORT_KEYS:
        if key not in payload:
            raise SystemExit(
                f"bnlib.{name}.{HOOK_API} 的返回值缺少 `{key}`（应为 {expected.__name__}）："
                "生成器必须显式汇报写了多少文件、有哪些缺口、哪些不支持。"
            )
        if not isinstance(payload[key], expected):
            raise SystemExit(
                f"bnlib.{name}.{HOOK_API} 的 `{key}` 类型是 "
                f"{type(payload[key]).__name__}，应为 {expected.__name__}。"
            )
    if payload["files"] < 0:
        raise SystemExit(f"bnlib.{name}.{HOOK_API} 的 `files` 不能为负数。")
    written = payload.get("written")
    if written is not None:
        if not isinstance(written, list) or not all(isinstance(item, str) for item in written):
            raise SystemExit(
                f"bnlib.{name}.{HOOK_API} 的 `written` 必须是字符串数组（写出的相对路径）。"
            )
    for key in ("warnings", "unsupported"):
        if not all(isinstance(item, str) for item in payload[key]):
            raise SystemExit(f"bnlib.{name}.{HOOK_API} 的 `{key}` 必须是字符串数组。")
    return payload


def run_generator_hooks(
    upstream: Path, ce_root: Path, out: Path, selected: list[str],
) -> dict[str, object]:
    """按固定顺序调用所有生成器钩子，并把结果落到 reports/generators.json。

    必须在 `write_ce_manifest` **之前**调用：清单是运行期释放资源的依据，晚于它写出的
    文件不会被释放，游戏里就是「配置存在但资源缺失」。
    """
    if not ce_root.is_dir():
        raise SystemExit(f"CE 包根目录不存在，生成器无处落盘：{ce_root}")

    hooks = discover_generators()
    order: list[str] = []
    reports: dict[str, object] = {}
    signature_drift: list[str] = []
    empty: list[str] = []

    for name, hook in hooks:
        order.append(name)
        print(f"[生成器] 运行 bnlib.{name}.{HOOK_API}（selected {len(selected)} 个 id）…")
        if _accepts_selected(hook):
            payload = hook(upstream, ce_root, selected=selected)
        else:
            signature_drift.append(name)
            print(
                f"[生成器][警告] bnlib.{name}.{HOOK_API} 不接受 selected 关键字参数，"
                "按 2 参调用；请同步到统一签名 generate(upstream, ce_root, selected=())。"
            )
            payload = hook(upstream, ce_root)

        payload = validate_generator_report(name, payload)
        reports[name] = payload

        for message in payload["warnings"]:
            print(f"[生成器][警告] {name}: {message}")
        for message in payload["unsupported"]:
            print(f"[生成器][不支持] {name}: {message}")
        if payload["files"] == 0:
            # `files == 0` 不等于失败：有的模块是纯只读校验（例如实体载体映射，它验证上游
            # 生物集合与 Java 载体表一致，本来就不写盘）。这类模块的"产出"是校验结论，
            # 不是文件。所以这里只响亮记录，不阻断。
            #
            # 内容是否真的缺失由产物侧门禁判定（verify_jar 要求 CE 包内 blocks/items/langs
            # 都非空）—— 门禁读产物，不读模块自述，这样两边不会共享同一个错误期待值。
            empty.append(name)
            print(f"[生成器][提示] {name} 写出 0 个文件（只读校验模块则属正常）。")
        print(f"[生成器] bnlib.{name} 完成：{summarize_generator_report(payload)}")

    # 顺序与缺口写进报告：门禁与排障都需要知道「谁先跑、谁报了缺口」。
    write_json(out / "reports/generators.json", {
        "order": order,
        "selected": len(selected),
        "signature_drift": signature_drift,
        "wrote_nothing": empty,
        "reports": reports,
    })
    return {"order": order, "reports": reports, "signature_drift": signature_drift}


def write_json(path: Path, payload: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def build_biome_pack(upstream: Path, out: Path) -> dict[str, object]:
    """把 23 个群系 JSON 净化后写进数据包，并生成 pack.mcmeta。

    目录用 `NAMESPACE` 变量，而不是硬写上游目录名：群系 id 由服务器按目录名注册，
    目录与 `pack.yml` 的 namespace 必须一致，否则群系注册到一半。
    """
    src = upstream / "src/main/generated/data/betternether/worldgen/biome"
    if not src.is_dir():
        raise SystemExit(f"找不到上游群系目录：{src}")

    dest = out / f"datapack/data/{NAMESPACE}/worldgen/biome"
    totals = {"biomes": 0, "entities": 0, "features": 0, "sounds": 0}
    names: list[str] = []

    for path in sorted(src.glob("*.json")):
        raw = json.loads(path.read_text(encoding="utf-8"))
        cleaned, stats = biome_rules.sanitize_biome(raw)
        # 门禁：净化后不得残留模组实体引用，否则直接失败（见 bnlib/biomes.py 的 L1 说明）。
        biome_rules.assert_no_mod_entity_refs(cleaned, path.name)
        write_json(dest / path.name, cleaned)

        totals["biomes"] += 1
        totals["entities"] += stats["entities"]
        totals["features"] += stats["features"]
        totals["sounds"] += stats["sounds"]
        names.append(path.stem)

    write_json(out / "datapack/pack.mcmeta", {
        "pack": {
            "description": "BetterNether biomes (26.3 port)",
            "min_format": PACK_MIN_FORMAT,
            "max_format": PACK_MAX_FORMAT_OPEN,
        }
    })

    return {"totals": totals, "names": names}


#: 上游标签里会嵌套引用它依赖库（wover 等）的标签。我们只随附 betternether 的标签，
#: 这些命名空间在原版侧解析不到，启动时会刷
#: `Couldn't load tag betternether:xxx as it is missing following references: #wover:yyy`。
#: 这里按上游库的定义补发一份等价标签。键是 `${命名空间}:${标签名}`，值说明它属于哪个
#: 注册表（block/item）、以及等价的成员列表。
NESTED_TAG_COMPAT: dict[str, dict[str, object]] = {
    # wover 的 is_obsidian：能被"黑曜石破坏"类工具处理的黑曜石族方块。
    "wover:is_obsidian": {
        "kind": "block",
        "values": ["minecraft:obsidian", "minecraft:crying_obsidian"],
    },
}


def build_tag_pack(upstream: Path, out: Path) -> dict[str, object]:
    """把上游 `tags/block` 与 `tags/item` 复制进数据包，并**过滤掉指向未注册方块的成员**。

    ## 为什么必须搬（这是长期隐藏的悬空引用源）

    配方与方块配置里到处写着 `#betternether:anchor_tree_logs` 这类原料标签
    （`items/bn-items.yml` 有 19 处），方块配置的 `tags:` 段也引用了 13 个 betternether
    标签。但上游的 `tags/` 目录从来没被搬进数据包 —— 生成产物里一个 tag 文件都没有，
    于是这些引用全部指向"不存在的标签"。

    ## 门禁：不许出现空标签

    上游 tag 的部分成员已经被我们移除了（`nether_reed_stem`、`potted_plant`、
    `mushroom_fir_trunk`、`stalagnate_trunk`、`willow_trunk`、
    `gloomsculk_geode_crystal`、`ink_bush_seed`、`nether_grass`）。直接照搬会让标签
    指向不存在的方块；而过滤后如果标签一个成员都不剩，写出去就是空标签
    （CE/原版会报「标签不包含任何物品」）。所以：

      * 成员只保留「已在 CE 里注册的 betternether 方块/物品」与「原版 id / 嵌套标签」；
      * **过滤后为空的标签整个不写出**（宁可没有这个标签，也不写出空标签）。
    """
    ns_dir = upstream / "src/main/generated/data/betternether/tags"
    if not ns_dir.is_dir():
        raise SystemExit(f"找不到上游标签目录：{ns_dir}")

    registered = _registered_ids(out)
    report = {"block": {}, "item": {}, "empty_skipped": [], "members_dropped": {}}
    for kind in ("block", "item"):
        src = ns_dir / kind
        if not src.is_dir():
            continue
        dest = out / f"datapack/data/{NAMESPACE}/tags/{kind}"
        kept_files = 0
        for path in sorted(src.rglob("*.json")):
            raw = json.loads(path.read_text(encoding="utf-8"))
            values = raw.get("values", [])
            keep: list[object] = []
            dropped: list[str] = []
            for value in values:
                if not isinstance(value, str):
                    keep.append(value)
                    continue
                if value.startswith("#"):          # 嵌套标签：原样保留
                    keep.append(value)
                    continue
                namespace, _, _name = value.partition(":")
                if namespace != "betternether":
                    keep.append(value)             # 原版 id 放行
                elif value in registered:
                    # 本包自己的 id：**必须**写成 required:false。
                    # vanilla 的标签加载器在 CE 注册物品之前就执行完（实测 17:12:29 vs 17:12:59），
                    # 而且 CE 方块/物品本来就不是 vanilla 注册表成员，所以这些成员在原版侧
                    # 永远解析不到 —— 直接写字符串会让每个标签每次启动刷一条
                    # `Couldn't load tag betternether:xxx as it is missing following references`。
                    # required:false 是原版为「可能不存在的成员」提供的正规写法：解析不到就跳过，
                    # 不报错、也不影响标签里其它（原版）成员。
                    keep.append({"id": value, "required": False})
                else:
                    dropped.append(value)
            rel = path.relative_to(src).as_posix()
            if dropped:
                report["members_dropped"][f"{kind}/{rel}"] = dropped
            if not keep:
                report["empty_skipped"].append(f"{kind}/{rel}")
                continue
            # **无条件**回写 values：`keep` 现在可能把本包 id 改写成 `{id, required:false}`
            # （见上面的说明），只在 dropped 非空时回写会让没有剔除成员的标签保留上游原文，
            # 那些纯字符串成员照样会在启动时刷 Couldn't load tag。
            rewritten = dict(raw)
            rewritten["values"] = keep
            write_json(dest / rel, rewritten)
            kept_files += 1
        report[kind]["written"] = kept_files

    # 门禁：写出的标签成员必须**全部**是已注册项。上面已经过滤过一遍，
    # 这里再断言一次是为了将来有人改动过滤逻辑时立刻炸出来，而不是又回到
    # 「标签指向不存在的方块 / 空标签」那种要等到服务器加载才暴露的状态。
    for kind, info in report.items():
        if kind not in ("block", "item"):
            continue
        dest_root = out / f"datapack/data/{NAMESPACE}/tags/{kind}"
        if not dest_root.is_dir():
            continue
        for path in sorted(dest_root.rglob("*.json")):
            values = json.loads(path.read_text(encoding="utf-8")).get("values", [])
            if not values:
                raise SystemExit(f"标签写成了空标签（禁止）：{kind}/{path.name}")
            for value in values:
                # 成员有两种形态：纯字符串（原版 id / 嵌套标签）与 `{id, required}` 字典
                # （本包 id，见上面 required:false 的说明）。两种都要查一遍是否已注册。
                if isinstance(value, dict):
                    ident = value.get("id")
                    if not isinstance(ident, str) or ident.startswith("#"):
                        continue
                    if value.get("required") is not False:
                        raise SystemExit(
                            f"本包标签成员必须写 required:false（否则原版侧解析不到会报错）："
                            f"{kind}/{path.name} → {value}")
                    if ident.split(":")[0] == "betternether" and ident not in registered:
                        raise SystemExit(
                            f"标签成员指向未注册 id：{kind}/{path.name} → {ident}。"
                            "要么补注册该方块/物品，要么把它从上游标签里剔除。")
                    continue
                if not isinstance(value, str) or value.startswith("#"):
                    continue
                if value.split(":")[0] != "betternether":
                    continue
                if value not in registered:
                    raise SystemExit(
                        f"标签成员指向未注册 id：{kind}/{path.name} → {value}。"
                        "要么补注册该方块/物品，要么把它从上游标签里剔除。")

    # 嵌套引用门禁：本包标签里出现的 `#命名空间:标签` 必须能在产物里解析到 —— 要么是原版
    # 自带的，要么是本包自己的，要么在上面的 NESTED_TAG_COMPAT 里补发过。否则启动日志会出现
    # `missing following references`，那是要等到服务器加载才暴露的状态，在这里立刻失败更省事。
    compat_ns = {key.split(":", 1)[0] for key in NESTED_TAG_COMPAT}
    allowed_ns = {"minecraft", NAMESPACE} | compat_ns
    for kind in ("block", "item"):
        dest_root = out / f"datapack/data/{NAMESPACE}/tags/{kind}"
        if not dest_root.is_dir():
            continue
        for path in sorted(dest_root.rglob("*.json")):
            for value in json.loads(path.read_text(encoding="utf-8")).get("values", []):
                if not isinstance(value, str) or not value.startswith("#"):
                    continue
                ns = value[1:].split(":", 1)[0]
                if ns not in allowed_ns:
                    raise SystemExit(
                        f"标签嵌套引用了未随附的命名空间：{kind}/{path.name} → {value}。"
                        "请在 NESTED_TAG_COMPAT 里为它补发等价标签。")

    # 补发兼容标签。放在门禁之后、return 之前：内容固定，不参与上面的成员校验。
    for key, spec in NESTED_TAG_COMPAT.items():
        namespace, _, name = key.partition(":")
        target = out / f"datapack/data/{namespace}/tags/{spec['kind']}/{name}.json"
        write_json(target, {"values": spec["values"]})
    return report


def _registered_ids(out: Path) -> set[str]:
    """从已经写出的 CE 配置里收集「已注册的 betternether 方块 + 物品」全集。

    必须读生成产物而不是再跑一遍 blocks.py：标签搬运要跟最终发布结果一致，
    否则过滤判据会和实际发布内容漂移。
    """
    import yaml  # noqa: PLC0415

    ce_root = out / f"craftengine/{NAMESPACE}/configuration"
    ids: set[str] = set()
    for path in sorted(ce_root.rglob("*.yml")):
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        if not isinstance(data, dict):
            continue
        for section in ("blocks", "items"):
            section_data = data.get(section)
            if isinstance(section_data, dict):
                ids.update(section_data.keys())
    return ids


def build_structure_pack(upstream: Path, out: Path) -> dict[str, object]:
    """从上游复制 Nether City 的 NBT 片段，并把里面的方块 id 前缀收敛到当前命名空间。

    ## 为什么必须改 NBT 里的字符串

    结构片段的调色板（palette）写的是 `betternether:cincinnasite_bricks` 这类方块名，由 CE 按
    当前命名空间注册。命名空间一旦与上游不同，不改就每一个方块都找不到定义 —— 城市会变成空壳。

    改法不是字节替换：TAG_String 带 2 字节长度前缀，前缀长度一变，直接替换就会让整个 NBT
    结构错位。这里真正解析、改字符串、重新序列化，见 bnlib/nbt.py。

    ## 只复制真正被引用的片段

    上游 `structure/city/` 里有 36 个片段，但其中一个（city_library_03）上游自己也没引用。这里全部
    复制，保持与上游文件集一致 —— 少复制反而会让「按上游文件名排查」失效。
    """
    src = upstream / "src/main/resources/data/betternether/structure/city"
    if not src.is_dir():
        raise SystemExit(f"找不到上游结构片段目录：{src}")

    dest = out / f"datapacks/betternether-structures/data/{NAMESPACE}/structure/nether_city"
    dest.mkdir(parents=True, exist_ok=True)

    copied = 0
    rewritten = 0
    seat_totals = structures.empty_stats()
    for path in sorted(src.glob("*.nbt")):
        root_name, root_tag, payload = nbt.read_file(path)
        # 节点在 bnlib.nbt 里是 (tag, value) 二元组；根名字是独立返回的，所以要重新组包。
        tree = (root_tag, payload)

        before = nbt.collect_strings(tree)
        upstream_refs = {s for s in before if is_foreign(s)}
        tree = nbt.map_strings(tree, rewrite_id)
        # 本包不发布的方块（座椅/书架/酿造台/门/箱子…）在调色板里必须显式映射成 air，
        # 否则 jigsaw 会静默跳过、verifyStructures 会判「未注册调色板方块」。
        # 判定统一走 publication.is_removed_block，删除类别变化时这里不必再改。
        tree, seat_stats = structures.strip_palette_seats(tree, structures.is_unpublished)
        structures.merge_stats(seat_totals, seat_stats)
        after = nbt.collect_strings(tree)
        leftover = {s for s in after if is_foreign(s)}
        if leftover:
            raise SystemExit(
                f"{path.name}: 改写后仍残留非当前命名空间 {sorted(leftover)[:3]}，拒绝写出一份半改的文件。")

        nbt.write_file(dest / path.name, root_name, tree[0], tree[1])
        copied += 1
        rewritten += len(upstream_refs)

    if copied == 0:
        raise SystemExit("没有复制到任何结构片段，结构包会是空的。")

    if seat_totals["templates"]:
        print(f"[结构] 有意移除 {seat_totals['placements']} 处已删方块放置"
              f"（{seat_totals['palette_entries']} 个调色板条目 → {structures.SEAT_REPLACEMENT_ID}）")

    write_json(out / "datapacks/betternether-structures/pack.mcmeta", {
        "pack": {
            "description": "BetterNether structures (26.3 port)",
            "min_format": PACK_MIN_FORMAT,
            "max_format": PACK_MIN_FORMAT,
        }
    })
    return {
        "structures": copied,
        "rewritten_refs": rewritten,
        # 有意移除的座椅：数量必须能被报告读出来，而不是「悄悄少了几把椅子」。
        "seat_palette_entries": seat_totals["palette_entries"],
        "seat_placements": seat_totals["placements"],
    }


def build_ce_pack(upstream: Path, out: Path) -> dict[str, object]:
    """生成 CraftEngine 资源包：pack.yml + 方块/物品 YAML + 语言 + 贴图与模型。"""
    ce_root = out / f"craftengine/{NAMESPACE}"
    write_text(ce_root / "pack.yml", (
        "author: linmumua\n"
        "description: BetterNether content for CraftEngine (26.3 port)\n"
        "version: '0.1'\n"
        f"namespace: {NAMESPACE}\n"
        "enable: true\n"
    ))

    # 方块/物品配置：由形状表驱动，不接受手工清单。
    # `env` 里固定 PYTHONHASHSEED，保证它写出的分类条目顺序在两次运行间一致（见
    # deterministic_env 的说明：分类顺序来自 set 迭代，默认每进程随机化）。
    subprocess.run(
        [sys.executable, str(Path(__file__).with_name("bnlib") / "blocks.py"),
         "--upstream", str(upstream), "--output", str(ce_root)],
        check=True,
        env=deterministic_env(),
    )

    manifest = json.loads((out / "reports/block-manifest.json").read_text(encoding="utf-8"))
    selected = [entry["id"] for entry in manifest["blocks"]]
    if not selected:
        raise SystemExit("方块清单为空，拒绝生成一个没有内容的 CE 包。")

    assets_src = upstream / "src/main/resources/assets/betternether"
    copy_assets(assets_src, ce_root / f"resourcepack/assets/{NAMESPACE}", selected)

    # 矿物掉落与烧制：物品贴图，以及矿物产物物品/配方（**不单独落盘**：items.py 会把
    # ore_drops 的行并进唯一的 `configuration/items/bn-items.yml`，一个文件夹只能有一个 yml）。
    #
    # 掉落规则本身不在这里 —— 它在每个矿物方块的 `loot:` 段（bnlib/ore_drops.py 生成、
    # blocks.py 拼进方块定义），用的是 CE 内置的 `default:loot_table/ore` 模板。
    # 这里补的是「掉出来的东西长什么样、能不能烧回矿物」。
    #
    # 必须排在 write_ce_manifest 之前：清单是运行期释放的依据，晚于它的文件不会被释放。
    ore_drops.configure(upstream)
    ore_drops.assert_references(selected)
    item_textures = ore_drops.copy_item_textures(
        assets_src, ce_root / f"resourcepack/assets/{NAMESPACE}")
    # 矿物的 `loot:` 段：blocks.py 目前对所有方块写死「掉自己」，这一步补成 ore 模板。
    # 一旦 blocks.py 自己按矿物生成掉落，这里自动变成空操作（判据见函数注释）。
    loot_report = ore_drops.patch_generated_blocks(ce_root)

    # 其他模块的生成器（物品、资产、实体……）统一从这里进入。
    #
    # 位置很关键：必须在 blocks/ore_drops 之后（它们可能要给已生成的方块补引用），
    # 又必须在 write_ce_manifest 之前（清单漏文件 = 运行期不释放 = 游戏里缺资源）。
    #
    # `selected` 是本次真正生成的方块 id 清单：资产/物品/实体这类按方块过滤的模块用它，
    # 免得各自再去解析一遍 block-manifest.json（解析口径一漂移就会生成出两套内容）。
    generators = run_generator_hooks(upstream, ce_root, out, selected)

    # 语言必须在**所有生成器跑完之后**写：条目集合要覆盖产物里每一个 `<lang:...>` 引用
    # （物品名就在 items.py 写出的 bn-items.yml 里，早于它的扫描什么都看不到）。
    # 同时仍要排在 write_ce_manifest 之前 —— 晚于清单的文件不会被释放。
    lang_report = build_langs(upstream, ce_root, lang_keys_from_manifest(manifest["blocks"]))

    # 必须在所有文件写完之后生成：清单是运行期释放的依据，漏一个文件就是漏一个资源。
    manifest_files = write_ce_manifest(ce_root, out)

    return {
        "blocks": len(selected),
        "langs": lang_report,
        "files": manifest_files,
        "item_textures": item_textures,
        "ore_drops": ore_drops.report(),
        "ore_loot": loot_report,
        "ores": [entry["id"] for entry in manifest["blocks"] if entry.get("ore")],
        "generators": generators,
    }


# 上游 `zh_cn.json` 缺条目时的补充中文名。
#
# 依据：上游 en_us 的命名与 BetterNether 既有译名体系（gloomwood 系列在 zh_cn 里已有
# 「幽暗林」词根，gloomsculk 系列沿用「幽匿」）。这不是机器音译，而是按现有译名的一致性补齐。
#
# ## 键可以写两种形态
#
#   * 完整语言键 —— `item.betternether.willow_boat`：同一个末段在方块与物品上含义不同时必须用它；
#   * 只写末段 —— `gloomwood_log`：上下游命名一致时最省事，方块表历史上就是这么写的。
#
# 查找顺序是「完整键 → 末段」，所以两种写法可以混在一张表里。**不要再开第二张表**：
# 两张表迟早漂移，漂移后的表现是「某些条目又变回英文」，而且没人能一眼看出该改哪张。
#
# ## 只补上游缺的，不改上游有的
#
# 上游 `zh_cn.json` 里已有的译文一律原样沿用（见 build_langs 的解析顺序：上游优先）。
# 因此本表条目只要上游哪天补了中文，就会自动让位 —— 不会把上游的正式译名覆盖掉。
#
# ## 词根对照（照上游/原版既有译名，不新造体系）
#
#   anchor_tree  锚木        crimson      绯红木      gloomwood      幽暗木
#   gloomwood_dark 深色幽暗木  mushroom_fir 下界冷杉菇木 nether_mushroom 蘑菇
#   nether_reed  下界芦苇    nether_sakura 下界樱花木   rubeus        宝红木
#   stalagnate   石结木      warped       诡异木      wart           下界疣
#   willow       下界柳木    cincinnasite 辛辛那金    nether_ruby    下界红宝石
#   flaming_ruby 烈焰红宝石  barrel 桶 / bookshelf 书架 / chiseled_bookshelf 雕纹书架 /
#   crafting_table 工作台 / trunk 树干 / boat 船 / chest_boat 运输船 / raft 木筏
SUPPLEMENTARY_ZH = {
    "bleached_gloomsculk": "漂白幽匿石",
    "gloomsculk_geode": "幽匿晶洞",
    "gloomsculk_lamp": "幽匿灯",
    "molten_gloomsculk": "熔融幽匿石",
    "veined_gloomsculk": "纹脉幽匿石",
    "gloomwood_log": "幽暗木原木",
    "gloomwood_bark": "幽暗木树皮",
    "gloomwood_planks": "幽暗木木板",
    "gloomwood_leaves": "幽暗木树叶",
    "gloomwood_bleached_leaves": "漂白幽暗木树叶",
    "gloomwood_stripped_log": "去皮幽暗木原木",
    "gloomwood_stripped_bark": "去皮幽暗木树皮",
    "gloomwood_dark_log": "深色幽暗木原木",
    "gloomwood_dark_bark": "深色幽暗木树皮",
    "gloomwood_dark_planks": "深色幽暗木木板",
    "gloomwood_dark_stripped_log": "去皮深色幽暗木原木",
    "gloomwood_dark_stripped_bark": "去皮深色幽暗木树皮",
    "gloomwood_dark_molten_log": "熔融深色幽暗木原木",
    "gloomwood_transition_log": "过渡幽暗木原木",
    "gloomwood_transition_planks": "过渡幽暗木木板",
    "gloomwood_transition_stripped_log": "去皮过渡幽暗木原木",
    "mushroom_fir_stripped_bark": "去皮蘑菇杉树皮",

    # --- 木板衍生方块（桶/书架/雕纹书架/工作台/树干）----------------------------------
    # 上游 zh_cn 里同一系列只译了「桶」和「工作台」，书架与雕纹书架整体缺条目。
    # 这里沿用同一系列已发布的中文词根，只有「雕纹」是原版 1.20+ 的「Chiseled Bookshelf」译法。
    "anchor_tree_bookshelf": "锚木书架",
    "anchor_tree_chiseled_bookshelf": "锚木雕纹书架",
    "crimson_bookshelf": "绯红木书架",
    "gloomwood_barrel": "幽暗木桶",
    "gloomwood_bookshelf": "幽暗木书架",
    "gloomwood_chiseled_bookshelf": "幽暗木雕纹书架",
    "gloomwood_crafting_table": "幽暗木工作台",
    "gloomwood_dark_barrel": "深色幽暗木桶",
    "gloomwood_dark_bookshelf": "深色幽暗木书架",
    "gloomwood_dark_chiseled_bookshelf": "深色幽暗木雕纹书架",
    "gloomwood_dark_crafting_table": "深色幽暗木工作台",
    "mushroom_fir_bookshelf": "下界冷杉菇木书架",
    "mushroom_fir_chiseled_bookshelf": "下界冷杉菇木雕纹书架",
    "mushroom_fir_trunk": "下界冷杉菇木树干",
    "nether_mushroom_bookshelf": "蘑菇书架",
    "nether_mushroom_chiseled_bookshelf": "蘑菇雕纹书架",
    "nether_reed_bookshelf": "下界芦苇书架",
    "nether_reed_chiseled_bookshelf": "下界芦苇雕纹书架",
    "nether_sakura_bookshelf": "下界樱花木书架",
    "nether_sakura_chiseled_bookshelf": "下界樱花木雕纹书架",
    "rubeus_bookshelf": "宝红木书架",
    "rubeus_chiseled_bookshelf": "宝红木雕纹书架",
    "stalagnate_bookshelf": "石结木书架",
    "stalagnate_chiseled_bookshelf": "石结木雕纹书架",
    "warped_bookshelf": "诡异木书架",
    "wart_bookshelf": "下界疣书架",
    "wart_chiseled_bookshelf": "下界疣雕纹书架",
    "willow_bookshelf": "下界柳木书架",
    "willow_chiseled_bookshelf": "下界柳木雕纹书架",

    # --- 幽暗系植被 / 幽匿晶石 -------------------------------------------------------
    "gloomgrass": "幽暗草",
    "pale_gloomgrass": "苍白幽暗草",
    "gloomsculk_crystal": "幽匿水晶",
    "gloomsculk_vine": "幽匿藤",
    "gloomwisp_vine": "幽光藤",

    # --- 物品：船 / 木筏（原版译法：`Boat` 船、`Boat with Chest` 运输船、`Raft` 木筏）----
    "item.betternether.anchor_tree_boat": "锚木船",
    "item.betternether.anchor_tree_chest_boat": "锚木运输船",
    "item.betternether.crimson_boat": "绯红木船",
    "item.betternether.crimson_chest_boat": "绯红木运输船",
    "item.betternether.gloomwood_boat": "幽暗木船",
    "item.betternether.gloomwood_chest_boat": "幽暗木运输船",
    "item.betternether.mushroom_fir_boat": "下界冷杉菇木船",
    "item.betternether.mushroom_fir_chest_boat": "下界冷杉菇木运输船",
    "item.betternether.nether_mushroom_boat": "蘑菇船",
    "item.betternether.nether_mushroom_chest_boat": "蘑菇运输船",
    "item.betternether.nether_reed_raft": "下界芦苇木筏",
    "item.betternether.nether_reed_chest_raft": "运输下界芦苇木筏",
    "item.betternether.nether_sakura_boat": "下界樱花木船",
    "item.betternether.nether_sakura_chest_boat": "下界樱花木运输船",
    "item.betternether.rubeus_boat": "宝红木船",
    "item.betternether.rubeus_chest_boat": "宝红木运输船",
    "item.betternether.stalagnate_boat": "石结木船",
    "item.betternether.stalagnate_chest_boat": "石结木运输船",
    "item.betternether.warped_boat": "诡异木船",
    "item.betternether.warped_chest_boat": "诡异木运输船",
    "item.betternether.wart_boat": "下界疣船",
    "item.betternether.wart_chest_boat": "下界疣运输船",
    "item.betternether.willow_boat": "下界柳木船",
    "item.betternether.willow_chest_boat": "下界柳木运输船",

    # --- 物品：长矛（`Spear` 长矛；钻石变体沿用上游 `Cincinnasite-Diamond` 的连字符形态）---
    "item.betternether.cincinnasite_spear": "辛辛那金长矛",
    "item.betternether.cincinnasite_spear_diamond": "辛辛那金-钻石长矛",
    "item.betternether.nether_ruby_spear": "下界红宝石长矛",

    # --- 物品：烈焰红宝石装备（上游 en 是 `Fireruby`，与已有「下界红宝石」并列）---------
    "item.betternether.flaming_ruby_axe": "烈焰红宝石斧",
    "item.betternether.flaming_ruby_boots": "烈焰红宝石靴子",
    "item.betternether.flaming_ruby_chestplate": "烈焰红宝石胸甲",
    "item.betternether.flaming_ruby_helmet": "烈焰红宝石头盔",
    "item.betternether.flaming_ruby_hoe": "烈焰红宝石锄",
    "item.betternether.flaming_ruby_leggings": "烈焰红宝石护腿",
    "item.betternether.flaming_ruby_pickaxe": "烈焰红宝石镐",
    "item.betternether.flaming_ruby_shovel": "烈焰红宝石锹",
    "item.betternether.flaming_ruby_spear": "烈焰红宝石长矛",
    "item.betternether.flaming_ruby_sword": "烈焰红宝石剑",

    # --- 物品：锻造模板（上游 en 三件都只写 `Smithing Template`，不对应具体升级）--------
    # 英文本身没有区分，中文也不擅自给它们编造不同的名字。
    "item.betternether.bowl_upgrade_smithing_template": "锻造模板",
    "item.betternether.cincinnasite_diamond_upgrade_smithing_template": "锻造模板",
    "item.betternether.flaming_ruby_upgrade_smithing_template": "锻造模板",

    # --- 座椅：5 个木种整体缺上游中文（2026-10-29 家具化后才第一次被引用）------------
    # 这 5 个木种的方块在**上游 zh_cn.json 里一条都没有**（连木板/栅栏都没有），所以
    # 不是「座椅漏译」而是「这批木种没进中文表」。词根照上表与上游既有座椅译名：
    #   bamboo 竹（原版「竹木板」）、cherry 樱花木（原版「樱花木板」）、
    #   mangrove 红树（原版「红树木板」）、gloomwood 幽暗木、gloomwood_dark 深色幽暗木；
    #   后缀照上游已译的座椅：椅子 / 凳子（taburet）/ 高脚凳（bar_stool）。
    "bamboo_chair": "竹椅子",
    "bamboo_taburet": "竹凳子",
    "bamboo_bar_stool": "竹高脚凳",
    "cherry_chair": "樱花木椅子",
    "cherry_taburet": "樱花木凳子",
    "cherry_bar_stool": "樱花木高脚凳",
    "mangrove_chair": "红树椅子",
    "mangrove_taburet": "红树凳子",
    "mangrove_bar_stool": "红树高脚凳",
    "gloomwood_chair": "幽暗木椅子",
    "gloomwood_taburet": "幽暗木凳子",
    "gloomwood_bar_stool": "幽暗木高脚凳",
    "gloomwood_dark_chair": "深色幽暗木椅子",
    "gloomwood_dark_taburet": "深色幽暗木凳子",
    "gloomwood_dark_bar_stool": "深色幽暗木高脚凳",
}


def supplementary_zh(lang_key: str) -> str | None:
    """查补充中文名：先按完整语言键，再按最后一段（两种写法共用一张表）。"""
    if lang_key in SUPPLEMENTARY_ZH:
        return SUPPLEMENTARY_ZH[lang_key]
    return SUPPLEMENTARY_ZH.get(lang_key.rsplit(".", 1)[-1])


def lang_keys_from_manifest(blocks: list[dict]) -> dict[str, str]:
    """从 `block-manifest.json` 取 id -> 上游语言键。

    ## 为什么语言键要由清单给出，而不是在这里按 id 拼

    上游有「方块 id 与语言键不同名」的情况：`stalagnate_trunk` 这个方块，上游的显示名写在
    `block.betternether.stalagnate_stem` 下（上游只有 `_stem` 这个键）。按 id 后缀硬拼
    `block.betternether.stalagnate_trunk` 会查不到，于是整条生成在「清单与上游不一致」处
    失败 —— 而清单本身是对的。

    这种别名必须只有一份定义。`blocks.py` 生成清单时已经把 `lang_key` 算进去了，这里读它，
    绝不再维护第二张别名表：两张表迟早漂移，漂移后的表现是「某些方块显示成 id」。

    缺省回退到 `block.<namespace>.<id 后缀>`：那正是没有别名时的正确形态。
    """
    mapping: dict[str, str] = {}
    for entry in blocks:
        block_id = entry.get("id")
        if not isinstance(block_id, str) or ":" not in block_id:
            continue
        name = block_id.split(":", 1)[1]
        declared = entry.get("lang_key")
        if isinstance(declared, str) and declared:
            mapping[block_id] = declared
        else:
            mapping[block_id] = f"block.{NAMESPACE}.{name}"
    return mapping


def published_lang_refs(ce_root: Path) -> list[str]:
    """扫描已生成的 CE 配置，返回本包需提供的 `<lang:...>` 语言键（已知原版标题除外）。

    ## 为什么要扫产物，而不是只按方块清单算条目

    `blocks.py` 用 `<lang:block.betternether.x>`，`items.py` 用
    `<lang:item.betternether.x>`（82 条）。如果语言文件只按方块清单生成，物品的名字就
    **一条都没有** —— 游戏里显示的不是英文，而是裸键 `item.betternether.agave_leaf`，
    比回退英文更糟，而且构建日志里只有「回退英文 34」这种与它无关的数字。

    所以判据要落在产物上：凡是配置里引用到的键，语言文件里必须有值。这样以后
    谁再往配置里加显示文本，都不会悄悄漏掉。
    """
    config = ce_root / "configuration"
    if not config.is_dir():
        return []
    refs: set[str] = set()
    for path in sorted(config.rglob("*.yml")):
        refs.update(LANG_REF_PATTERN.findall(path.read_text(encoding="utf-8")))
    # 这两个标题由 Minecraft 客户端自带，不能要求 BetterNether 上游重复提供译名。
    # 精确豁免，不按 container.* 放行，拼写错误和新增的未知键仍需被门禁拦住。
    refs.difference_update(("container.barrel", "container.chest"))
    return sorted(refs)


def build_langs(upstream: Path, ce_root: Path, lang_keys: dict[str, str]) -> list[str]:
    """给真正生成出来的方块写语言条目。

    显示名按 `lang_keys` 给出的上游键去查（`block.betternether.*`，少数只有 `item.*` 键，
    按同名回退）。写出去的键**就是** `lang_keys` 里的那个键 —— 因为 CE 是按方块配置里
    `<lang:...>` 的完整键去查表，两边必须是同一个字符串。

    **已知上游事实**：`en_us.json` 覆盖全部方块，但 `zh_cn.json` 只覆盖一部分。
    缺中文时不能静默把 id 当名字显示，也不能因此让整个迁移失败（那是上游的缺口，
    不是本工程的错误）。做法是回退到英文名，并把缺口数量与样例打进构建日志，
    让「哪些方块还是英文」这件事可见、可追踪。

    ## 方块之外，还要覆盖「已发布配置里引用到的键」

    条目集合 = 方块清单里的键 ∪ 矿物产物物品 ∪ **本包配置里所有 `<lang:...>` 引用**。
    第三项是后补的（见 `published_lang_refs`）：物品名、以后新增的任何显示文本都靠它兜住，
    否则键根本不存在于语言文件里，游戏里显示裸键。

    解析顺序（每一层只在上一层查不到时才用）：
    上游对应语言文件 → 补充表（仅中文）→ 英文（记入回退缺口）。
    上游已有的译文永远优先，所以补充表里就算写了同一个键，也不会顶掉上游的正式译名。
    """
    lang_src = upstream / "src/main/resources/assets/betternether/lang"
    written: list[str] = []
    gaps: dict[str, list[str]] = {}
    # 两个语言的条目先各自算完，最后一次性并进唯一的语言文件（见 merge_lang_sections）。
    sections: list[tuple[str, dict[str, str]]] = []

    # 先读英文，作为中文缺失时的回退源。
    en_source = lang_src / "en_us.json"
    if not en_source.is_file():
        raise SystemExit(f"找不到上游英文语言文件：{en_source}")
    en_lang = json.loads(en_source.read_text(encoding="utf-8"))

    # 必须在所有生成器跑完之后调用（见 build_ce_pack 的调用位置）：这一步要读产物。
    refs = published_lang_refs(ce_root)

    def item_fallback_key(lang_key: str) -> str:
        """`block.betternether.x` -> `item.betternether.x`（上游少数方块只有 item 键）。"""
        prefix = f"block.{NAMESPACE}."
        if lang_key.startswith(prefix):
            return f"item.{NAMESPACE}." + lang_key[len(prefix):]
        return lang_key

    def english_name(lang_key: str) -> str | None:
        for key in (lang_key, item_fallback_key(lang_key)):
            if key in en_lang:
                return en_lang[key]
        return None

    for code in ("en_us", "zh_cn"):
        source = lang_src / f"{code}.json"
        if not source.is_file():
            raise SystemExit(f"找不到上游语言文件：{source}")
        upstream_lang = json.loads(source.read_text(encoding="utf-8"))

        entries: dict[str, str] = {}
        missing: list[str] = []
        filled: list[str] = []
        for block_id, lang_key in lang_keys.items():
            # 补充表既能按完整键、也能按最后一段索引（与上游命名一致，例如 gloomwood_log）。
            lang_name = lang_key.rsplit(".", 1)[-1]
            translated = None
            for key in (lang_key, item_fallback_key(lang_key)):
                if key in upstream_lang:
                    translated = upstream_lang[key]
                    break
            if translated is None:
                # 上游 zh_cn 缺条目时，用补充表；仍然没有才回退英文。
                if code == "zh_cn":
                    translated = supplementary_zh(lang_key)
                if translated is None:
                    translated = english_name(lang_key)
                    if translated is not None:
                        missing.append(lang_name)
            if translated is None:
                # 连英文都没有：这是真错误，说明清单里的 lang_key 与上游对不上。
                raise SystemExit(
                    f"{block_id} 的语言键 {lang_key} 在上游 en_us.json 与 {code}.json 中"
                    "都没有译名，清单与上游不一致，拒绝生成。"
                )
            entries[lang_key] = translated

        # 矿物产物物品的名字一并写进同一个语言文件。
        #
        # 为什么不另建一个 langs 文件：`configuration/langs/` 只允许一个 yml。CE 的 langs
        # 有两条静默陷阱 —— 段名必须带 `lang#` 前缀、语言码那一层写错整个文件被丢弃且日志
        # 一字不差。这里复用已经被验证过的那份文件与写法，风险最小；键名本身是任意的。
        entries.update(ore_drops.lang_entries(upstream_lang))

        # 已发布配置里引用到、但上面两条路径都没覆盖的键（当前是 82 条物品名）。
        for key in refs:
            if key in entries:
                continue
            translated = upstream_lang.get(key)
            if translated is None and code == "zh_cn":
                translated = supplementary_zh(key)
            if translated is None:
                translated = en_lang.get(key)
                if translated is not None:
                    # 上游自己也没译：与方块同样处理 —— 回退英文并记入缺口，不静默蒸发。
                    missing.append(key)
            if translated is None:
                raise SystemExit(
                    f"配置引用了语言键 {key}，但上游 en_us.json 与 {code}.json 以及补充表"
                    "都没有对应文本。游戏里会显示裸键，拒绝生成。"
                )
            entries[key] = translated
            filled.append(key)

        sections.append((code, entries))
        written.append(
            f"{code}({len(entries)}"
            + (f"，回退英文 {len(missing)}" if missing else "")
            + (f"，补齐发布引用 {len(filled)}" if filled else "")
            + ")"
        )
        if missing:
            gaps[code] = missing

    merge_lang_sections(ce_root / f"configuration/langs/{block_rules.LANGS_OUTPUT_NAME}",
                        sections)

    for code, missing in gaps.items():
        preview = "、".join(missing[:8])
        suffix = " 等" if len(missing) > 8 else ""
        print(f"[警告] {code} 有 {len(missing)} 个显示条目缺上游译名，已回退英文：{preview}{suffix}")

    return written


def split_top_level_sections(text: str) -> tuple[list[str], list[tuple[str, list[str]]]]:
    """把生成物文本切成 (文件头注释行, [(顶层键, 该段全部行)])。

    顶层键 = 顶格、非注释、以 `:` 结尾的行；段内的一切都缩进，所以这个切法对
    CE 的 `langs:` / `items:` / `blocks:` 这类「一个顶层映射 + 两空格子项」的
    生成物都成立（本工程的产物一律是这种形状）。
    """
    header: list[str] = []
    sections: list[tuple[str, list[str]]] = []
    for line in text.splitlines(keepends=True):
        stripped = line.strip()
        if (stripped and not stripped.startswith("#")
                and not line[:1].isspace() and stripped.endswith(":")):
            sections.append((stripped[:-1], [line]))
        elif sections:
            sections[-1][1].append(line)
        else:
            header.append(line)
    return header, sections


def merge_lang_sections(path: Path, sections: list[tuple[str, dict[str, str]]]) -> None:
    """把自己拥有的方块/物品译名段并进**唯一的**语言文件，其余段原样保留。

    ## CE 的 langs 契约（结论来自 jar 的字节码，不是猜的）

    `configuration/langs/*.yml` 是真正的 YAML，结构是：

        <段类型>#<段名>:
          <语言码>:
            <键>: <值>

    `AbstractPackManager` 按 `#` **之前**的文本选解析器（`lang:` / `translations:` /
    `translations#...`），段名只要本包内唯一即可。而
    `TranslationManagerImpl$LangParser.parseSection(Pack, Path, ConfigSection)` 的 `Path`
    参数在方法体里根本没被使用：它遍历**段的内层键**（`en_us` / `zh_cn`）作为语言码，
    逐个调 `addClientTranslation(语种, 该语种下的键值)`，而那个方法是
    `clientLangData.computeIfAbsent(语种).addTranslations(键值)` —— **累加**语义。
    所以一个文件里可以同时放多个语种、多个段；语言码与文件名无关。

    ## 为什么是「替换自己那几段」而不是直接覆盖整个文件

    同一个文件里还有 blocks.py 写的分类译名段（`translations#categories_*`）。整体覆盖会
    把它们抹掉（分类名退化成裸键 `category.betternether.*`），而「读出来 + 只换掉自己的段」
    让这一步幂等：跑几遍结果一致，也不依赖 blocks.py 与 build_langs 的先后顺序。

    键值里可能有 `"`、`:`、`#`，所以一律用双引号包裹并转义，不依赖 YAML 的裸标量规则。
    """
    if path.is_file():
        header, existing = split_top_level_sections(path.read_text(encoding="utf-8"))
        # 自己写的是 `lang#blocks_<code>`；`lang#` 开头的其它段也一并清掉，
        # 免得旧版本留下的段名（例如每个语种一个文件时期的写法）叠成第二份译名。
        kept = [item for item in existing if not item[0].startswith("lang#")]
    else:
        header = [
            "# 由 scripts/extract_bn.py 与 scripts/bnlib/blocks.py 共同生成，请勿手工编辑。\n",
            "# 整包只有一个语言文件：语言码是每段的内层键（en_us / zh_cn），与文件名无关；\n",
            "# 段 id 只要在本包内唯一即可（CE 按 '#' 之前的文本选解析器）。\n",
        ]
        kept = []

    new: list[tuple[str, list[str]]] = []
    for code, entries in sections:
        lines = [f"lang#blocks_{code}:\n", f"  {code}:\n"]
        for key in sorted(entries):
            value = entries[key].replace("\\", "\\\\").replace('"', '\\"')
            lines.append(f'    {key}: "{value}"\n')
        new.append((f"lang#blocks_{code}", lines))

    write_text(path, "".join(header)
               + "".join(line for _, lines in kept + new for line in lines))


def write_ce_manifest(ce_root: Path, out: Path) -> int:
    """把 CE 包里每个文件的相对路径写成清单，供插件在运行期逐个释放。

    ## 为什么需要清单

    插件要把内置的 CE 包写到 `plugins/CraftEngine/resources/betternether/`。但它**不能**靠遍历
    jar 目录来发现文件：shadowJar 默认不写目录条目，`getResource("craftengine/betternether/configuration")`
    会返回 null，于是遍历结果为空 —— 那会释放出一个只有 pack.yml 的空包，CraftEngine 报"包已加载"，
    而所有方块都缺失。这类失败最恶劣：日志看起来是成功的。

    所以构建期就把文件列表写死进 jar（`craftengine/betternether.manifest.txt`），运行期照着读。
    清单在包目录**之外**（同级），这样它自己不会被当成包内容扫描。
    """
    files: list[str] = []
    for path in sorted(ce_root.rglob("*")):
        if path.is_file():
            files.append(path.relative_to(ce_root).as_posix())

    manifest_path = ce_root.parent / f"{ce_root.name}.manifest.txt"
    # 必须显式写 '\n'：清单会被 Java 侧按行切分后直接当 jar 内路径用。
    # Windows 上 write_text 默认把 '\n' 转成 '\r\n'，于是每一行路径都尾随一个 \r，
    # Java 读到的就是 "configuration/x.yml\r" —— getResource 返回 null，释放时全部计为缺失。
    # 这个 bug 在 Linux 构建时不出现，只在 Windows 上炸，且现象是「清单看起来有 1918 条，
    # 实际一条都读不到」。
    manifest_path.write_bytes(("\n".join(files) + "\n").encode("utf-8"))
    return len(files)


def copy_assets(assets_src: Path, dest_root: Path, selected: list[str]) -> None:
    """**全量**拷贝方块模型、贴图与 blockstates，并改写模型内的命名空间引用。

    ## 为什么不再按名字挑

    第一版按「模型名等于方块名或以方块名开头」来挑资产，结果线上刷出上百条
    `模型 "mumu:block/xxx" 缺少纹理 "assets/mumu/textures/block/xxx.png"`。原因是这个启发式
    必然漏：模型的贴图名和方块名经常不一致（`anchor_tree_log` 用 `anchor_tree_log_side`），
    父模型还可能在另一个文件里（`lumabus_seed` 继承 `lumabus_vine`），贴图又可能被多个模型共享。

    参考工程（更好的末地）的做法是不挑 —— 906 张贴图、690 个模型全量打包，3.4 MB。
    上游这里 1090 张贴图 + 627 个模型 + 187 个 blockstates，合计约 5.5 MB。用 5 MB 换掉
    「贴图缺失 / 父模型缺失 / 循环纹理」这一整类问题，是划算的：这些错误只在服务器启动时
    以 WARN 刷屏出现，而方块在游戏里表现为紫黑格子。

    ## 为什么 blockstates 也要带

    CE 的自定义方块默认按模型名推导外观，但 `config_factory` 与多状态方块（如
    `*_chiseled_bookshelf`、`quartz_glass_framed_*`）会引用 blockstates。少一份就少一个外观。
    """
    if not assets_src.is_dir():
        raise SystemExit(f"找不到上游资源目录：{assets_src}")

    copied_models = 0
    copied_textures = 0
    copied_animations = 0
    copied_states = 0
    cycle_fixed = 0
    skipped_broken = []
    skipped_body: list[str] = []

    # 上游有几个模型文件本身是坏的，且没有任何方块引用它们（死资源）：
    #   * stairs_with_top.json          —— textures 里 `#bottom -> #bottom` 自引用
    #   * wall_mushrooms_red_1/2.json   —— 引用原版 `blocks/mushroom_red_new`，而原版没有这个贴图
    #     （写的是 `blocks/` 复数，原版目录是 `textures/block`）
    # 它们不参与任何方块的渲染，但 CE 加载资源包时仍会逐个解析并报警。直接不释放，
    # 比在包里留一份坏文件再去改上游内容更诚实 —— 我们释放的是「上游能用的那部分」。
    BROKEN_UPSTREAM_MODELS = {"stairs_with_top", "wall_mushrooms_red_1", "wall_mushrooms_red_2"}

    # 还有一类「隐式冲突」：CE 会按 `texture: X` 生成 `models/block/X.json`，
    # 而上游恰好有一个同名的子部件模型（例：方块 cincinnasite_pillar 的贴图叫
    # cincinnasite_pillar_middle，上游也有 models/block/cincinnasite_pillar_middle.json）。
    # 这时 CE 报「无法生成模型，因为模型文件已存在」，然后用了那个子部件模型 —— 渲染未必对。
    #
    # 处理方式：这些方块本身没有模型（要靠 CE 生成），所以把与之撞名的子部件模型**不释放**，
    # 让 CE 顺利生成正确的 cube_all。撞名表由 blocks.py 从同一份数据算出，这里读回来。
    shadowed_models: set[str] = set()
    # dest_root = <out>/craftengine/<ns>/resourcepack/assets/<ns>，所以 reports/ 在 parents[4]。
    shadow_file = dest_root.parents[4] / "reports/shadowed-models.txt"
    if shadow_file.is_file():
        shadowed_models = {line.strip() for line in shadow_file.read_text(encoding="utf-8").splitlines()
                           if line.strip()}
    # 拆循环纹理引用时需要一个「有哪些贴图可用」的全集，先建一次。
    all_textures = {p.stem: p for p in (assets_src / "textures/block").glob("*.png")}

    # 模型：全量拷贝，但改写内部的命名空间引用。
    # 只释放模型，**不释放 blockstates**。
    #
    # 上游有 187 个 blockstates，其中不少引用我们并不释放的模型：
    #   * 我们刻意跳过的上游坏模型（wall_mushrooms_red_1/2、stairs_with_top）；
    #   * 属于我们没收录方块的模型（台阶/墙等异形，用户已要求不要）。
    # 它们会让 CE 在加载时报「方块状态 xxx 缺少模型文件」，而那个方块我们根本不注册，
    # 属于纯粹的噪声。
    #
    # 而且 blockstates 本来就不用我们提供：CE 会按每个方块的 `state.auto_state` 生成。
    # 释放它等于用上游的旧状态表去覆盖 CE 自己的分配，只会打架。
    # 恢复释放方块模型 —— 但这次修好两个之前没注意到的地方。
    #
    # ## 为什么要释放
    # CraftEngine 的 `items.<id>.model:` 是**硬引用**：物品栏图标必须有一个真实存在的模型文件，
    # CE 不会为物品生成模型。只释放贴图时，日志会刷满
    #   `物品"[betternether:xxx]"缺少模型文件"assets/betternether/models/block/xxx.json"`
    # 而且物品在背包里没有外观。参考工程（更好的末地）确实释放了 690 个模型。
    #
    # ## 之前为什么出错
    # 上一版释放模型后，界面里一大片方块长得一模一样。根因不是「模型不该释放」，而是模型里的
    # parent 写的是 `block/cube_all`（无命名空间）。CE 解析 parent 时按 `ResourceLocation` 规则
    # 需要命名空间，解析不到就把整个模型作废，回退成一个统一的占位外观 —— 所以看起来全都一样。
    # 参考工程的模型写的是 `minecraft:block/cross`（带前缀），从来没有这个问题。
    #
    # 所以这里在释放时把 parent 补上 `minecraft:` 前缀。这是**修正上游模型的一条不规范写法**，
    # 不是改外观。
    # 与参考工程（更好的末地）完全一致的释放策略：
    #
    #   实测它的 `models/block/` 里**没有**普通方块本体的模型 —— `flavolite`、`flavolite_polished`
    #   这些都不存在，只有 `flavolite_lantern`、`flavolite_flower_pot` 这类特殊形状的模型。
    #   也就是说：普通方块的外观由 CraftEngine 按 `state.texture` 生成，包里只放特殊形状的模型。
    #
    # 本移植照此办理：方块本体的模型不释放（交给 CE 生成），其余模型照常释放。
    # 这样 `items.model: <ns>:block/<方块名>` 引用的正是 CE 生成的那个模型，不会缺；
    # 也不会有「模型文件已存在」「贴图名撞子部件模型」这类冲突。
    block_names = {b.split(":", 1)[1] for b in selected}
    for sub in ("models/block",):
        src = assets_src / sub
        if not src.is_dir():
            continue
        for path in sorted(src.rglob("*.json")):
            # 上游有 3 个模型本身是坏的、且没有任何方块引用（死资源）：
            #   * stairs_with_top        —— `#bottom -> #bottom` 自引用
            #   * wall_mushrooms_red_1/2 —— 引用原版 `blocks/mushroom_red_new`（复数路径，原版没有）
            # 释放它们只会让 CE 在加载时逐个解析并报警。不释放。
            if path.stem in BROKEN_UPSTREAM_MODELS:
                skipped_broken.append(path.stem)
                continue
            # 方块本体模型**必须释放**。
            #
            # 实测纠正：CraftEngine 的 `items.<id>.model:` 是硬引用，**不会**为物品生成模型。
            # 只释放特殊形状模型时，日志会刷满
            #   `物品"[betternether:xxx]"缺少模型文件"assets/betternether/models/block/xxx.json"`
            # 而且物品在背包里没有图标。
            #
            # 参考工程（更好的末地）看起来「没有方块本体模型」，是因为它的方块名与贴图名不同、
            # 生成逻辑自成一套；对本移植最可靠的做法是把上游模型全部释放，
            # 让 `items.model` 与 `blocks.state.model` 都能直接解析到文件。
            target = dest_root / sub / path.relative_to(src)
            target.parent.mkdir(parents=True, exist_ok=True)

            text = path.read_text(encoding="utf-8")
            # 命名空间收敛（当前 NAMESPACE 等于上游，通常是恒等变换）。
            for known in ("betternether:", "mumu:"):
                if known != NAMESPACE + ":" and known in text:
                    text = text.replace(known, NAMESPACE + ":")
            if is_foreign(text):
                raise SystemExit(
                    f"{path.name}: 改写后仍残留非当前命名空间，拒绝写出一份贴图会丢的模型。")

            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                payload = None
            if isinstance(payload, dict):
                # ① parent 补齐命名空间：`block/cube_all` -> `minecraft:block/cube_all`。
                parent = payload.get("parent")
                if isinstance(parent, str) and ":" not in parent:
                    payload["parent"] = f"minecraft:{parent}"
                # ② 拆掉上游 Cubik 半成品的 `#particle -> #particle` 自引用环。
                if model_synth.break_texture_cycles(payload, path.stem, all_textures):
                    cycle_fixed += 1
                text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"

            target.write_text(text, encoding="utf-8")
            copied_models += 1

    # 贴图：全量拷贝，二进制原样。
    #
    # ## 为什么必须连 `.png.mcmeta` 一起拷
    #
    # Minecraft 靠 `<贴图名>.png.mcmeta` 里的 `animation` 段判断这是一张**竖排多帧**贴图；
    # 没有这个旁挂文件，客户端只会把整条竖排图当成一张静态图渲染 —— 游戏里看到的就是
    # 「贴图被拉得很长」。上游 79 个动画贴图（熔融幽匿石、幽匿灯、窑炉正面等）全部依赖它，
    # 只拷 `.png` 等于把动画退化成了一条长条。
    #
    # 判据用「png 旁边若存在同名 .png.mcmeta 就一起拷」，不写死清单：上游增删动画时自动跟随。
    textures_src = assets_src / "textures/block"
    if textures_src.is_dir():
        for path in sorted(textures_src.rglob("*.png")):
            target = dest_root / "textures/block" / path.relative_to(textures_src)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            copied_textures += 1

            # 动画元数据与贴图同目录同名（`x.png` + `x.png.mcmeta`），CE 资源包原样下发，
            # 客户端才认。`copyfile` 覆盖写，重复运行不会产生多余文件。
            meta = path.with_name(path.name + ".mcmeta")
            if meta.is_file():
                shutil.copyfile(meta, target.with_name(target.name + ".mcmeta"))
                copied_animations += 1

    if copied_textures == 0:
        raise SystemExit("没有拷贝到任何贴图，CE 的方块会全部渲染成紫黑格子。")
    if copied_models == 0:
        raise SystemExit("没有拷贝到任何方块模型：CE 的 items.model 是硬引用，物品会没有外观。")
    # 上游有动画贴图却一张元数据都没拷到，只可能是路径/判据写错了。这类失败在游戏里
    # 表现为「贴图变成拉长的静态图」，日志毫无痕迹，必须在构建期就拦住。
    expected_animations = len(list(textures_src.rglob("*.png.mcmeta"))) if textures_src.is_dir() else 0
    if expected_animations and copied_animations == 0:
        raise SystemExit(
            f"上游有 {expected_animations} 个 .png.mcmeta，却一个都没拷贝：动画贴图会退化成拉长的静态图。")

    print(f"[CE] 模型 {copied_models} 个（parent 已补 minecraft: 前缀），贴图 {copied_textures} 张，"
          f"动画贴图 {copied_animations} 个"
          + (f"，修复循环纹理 {cycle_fixed} 个" if cycle_fixed else ""))


def copy_licenses(upstream: Path, out: Path) -> None:
    """上游许可证随包走：MIT 正文 + 素材清单。"""
    for name in ("LICENSE", "LICENSE.ASSETS"):
        source = upstream / name
        if source.is_file():
            target = out / "licenses" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)


def deterministic_env() -> dict[str, str]:
    """给子进程用的环境：固定 `PYTHONHASHSEED`，让它的输出与运行次数无关。

    ## 为什么需要

    生成器里有「把一批 id 收进 set、再按 set 迭代顺序写出」的写法（分类条目顺序、
    分类 `list` 的顺序就是这样来的）。CPython 默认对字符串哈希做**每进程随机化**，
    于是同样的输入在两次运行里会产出不同顺序的 YAML —— 文件数一样、内容等价，
    但字节不同。实测：连续两次运行，`categories/betternether.yml` 的条目顺序会变，
    构建期「重复运行是否幂等」这条验收因此不成立。

    ## 为什么只对子进程设，不用 execv 重入自身

    `PYTHONHASHSEED` 只在解释器启动时读取，进程内改环境变量对当前解释器无效。曾经
    试过 `os.execv` 带固定种子重入自身，但 Windows 下重入后 **stdout 重定向会丢失**：
    日志只剩一行、退出码仍是 0，排障时看起来像"什么都没跑"，实际产物在写。这种
    不可观测的失败比非幂等本身更危险，所以放弃重入。

    进程内的 hook 若有同样的顺序依赖，必须自己 `sorted()` —— 这条约束写在
    `run_generator_hooks` 的说明里，并由「连跑两次逐字节比对」这条验收兜住。
    """
    env = dict(os.environ)
    env["PYTHONHASHSEED"] = "0"
    return env


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 BetterNether 插件资源")
    parser.add_argument("--upstream", required=True, help="上游 BetterNether 源码目录")
    parser.add_argument("--output", required=True, help="生成目录")
    args = parser.parse_args()

    upstream = Path(args.upstream).resolve()
    out = Path(args.output).resolve()
    if not upstream.is_dir():
        raise SystemExit(f"上游目录不存在：{upstream}")

    # 每次重新生成：旧产物留着会让"删掉一个方块"这类改动永远不生效。
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)

    biome_report = build_biome_pack(upstream, out)
    structure_report = build_structure_pack(upstream, out)
    # 植被放置数据：上游 worldgen JSON 里解释得动的那部分，连同树模板压成的紧凑 JSON。
    # 必须排在 build_ce_pack 之前 —— 方块清单（blocks.py）按这份产物判断要发布哪些植物。
    worldgen_report = worldgen_copy.write(upstream, out)
    # 地形（非 step 9 的 lakes / underground_decoration 等）单独一份清单 `terrain.json`，
    # 由地形侧 populator 用 WorldgenLoader.load("terrain") 读取；不改上面那份 manifest.json。
    #
    # 模块由地形侧维护。**文件存在**时它必须成功（失败要响）；不存在时只告警并跳过 ——
    # 并行开发期不能让「相邻模块还没落地」把别人的整条构建线卡死。文件一落地，这段就自动生效。
    terrain_module = Path(__file__).resolve().parent / "bnlib/terrain_worldgen.py"
    if terrain_module.is_file():
        from bnlib import terrain_worldgen  # noqa: PLC0415
        terrain_report = terrain_worldgen.write(upstream, out)
        print(f"[地形] 解释 {terrain_report.get('supported', '?')} 条 placed feature，"
              f"写出 {terrain_report.get('files', '?')} 个文件")
    else:
        terrain_report = None
        print(f"[地形][警告] 缺少 {terrain_module.name}：本次不生成 terrain.json，"
              "地形侧 populator 会因清单缺失而在启动时报错（模块落地后自动恢复）。")
    ce_report = build_ce_pack(upstream, out)
    # 标签必须排在 build_ce_pack 之后：过滤判据是「CE 实际注册了哪些 id」，
    # 提前跑会读到空目录，把所有 betternether 成员误删成空标签。
    tag_report = build_tag_pack(upstream, out)
    copy_licenses(upstream, out)
    surface_rule_copy.copy_rules(upstream, out)

    totals = biome_report["totals"]
    print(f"[群系] {totals['biomes']} 个，剥离实体生成项 {totals['entities']} 条，"
          f"摘除模组 feature 引用 {totals['features']} 处，替换/删除自定义音效 {totals['sounds']} 处")
    print(f"[结构] {structure_report['structures']} 个 NBT 片段，改写方块引用 "
          f"{structure_report['rewritten_refs']} 处")
    print(f"[植被] 解释 {worldgen_report['supported']} 条 placed feature（群系引用 "
          f"{worldgen_report['biomes']} 处），跳过 {worldgen_report['skipped']} 条，"
          f"树模板 {worldgen_report['templates']} 个")
    print(f"[CE] 方块 {ce_report['blocks']} 个，语言：{', '.join(ce_report['langs'])}")
    print(f"[CE] 矿物 {len(ce_report['ores'])} 种：{', '.join(ce_report['ores'])}")
    print(f"[CE] 矿物掉落：{ce_report['ore_drops']}")
    print(f"[CE] 矿物 loot 段：{ce_report['ore_loot']}")
    print(f"[CE] 矿物产物物品贴图 {ce_report['item_textures']} 张")
    print(f"[标签] 写出 block {tag_report['block'].get('written', 0)} 个、"
          f"item {tag_report['item'].get('written', 0)} 个"
          + (f"；跳过空标签 {len(tag_report['empty_skipped'])} 个"
             f"（{'、'.join(tag_report['empty_skipped'])}）" if tag_report['empty_skipped'] else "")
          + (f"；剔除已删成员 {len(tag_report['members_dropped'])} 处"
             if tag_report['members_dropped'] else ""))
    generators = ce_report["generators"]
    print(f"[生成器] 执行顺序：{' -> '.join(generators['order']) if generators['order'] else '（无）'}")
    for name, payload in generators["reports"].items():
        print(f"[生成器] {name}：{summarize_generator_report(payload)}")
    print("[完成] 产物目录：" + str(out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

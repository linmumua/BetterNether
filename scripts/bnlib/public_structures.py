#!/usr/bin/env python3
"""公开结构 id：`mumu:<建筑名>`，与 CE 内容命名空间分离。

## 为什么要拆出第二个命名空间

原版 `/locate structure` 解析的是**注册表 id**，而注册表 id 就是
`data/<ns>/worldgen/structure/<name>.json` 的路径。想让玩家敲
`/locate structure mumu:nether_city` 找到建筑，唯一可行的办法是**再写一份**
`data/mumu/worldgen/structure/nether_city.json` —— 不存在「别名」或「命令转发」这种
捷径：命令只认注册表，不认我们自己的映射表。

同时本包注册到 CraftEngine 的方块/物品、数据包里的群系、template_pool、processor_list
以及 NBT 里的方块调色板全部还是 `betternether:`，**一律不动**：那套 id 是 CE 包
`pack.yml` 的 namespace 决定的，改它要动 67 个结构 NBT 与全部 CE 资源，收益为零、
风险极大。所以这里只引入一个**专用**的公开结构命名空间，不碰 `namespace.NAMESPACE`
（它可以继续代表「内容命名空间」），也不进 `namespace.py` 的 `KNOWN_PREFIXES` ——
否则 `rewrite_id()` 会把 NBT 调色板里的方块 id 也往 `mumu:` 上收敛。

## 三份文件、两套 id、一处放置

每个结构最终是这样的：

  * `data/betternether/worldgen/structure/<name>.json` —— 作者原 id 的**定义**，
    照旧发布。已存档的区块把 `structures.starts` 按**生成时的 id 字符串**记在
    chunk 里；删掉这份定义，旧世界里的建筑会变成「Unknown structure id」并在
    继续建造时丢掉进度。
  * `data/mumu/worldgen/structure/<name>.json` —— 公开发布的**定义**，内容与上面
    逐字段相同（`start_pool` / `biomes` 仍指 `betternether:`，模板池与群系 tag 不迁移）。
  * `data/mumu/worldgen/structure_set/<name>.json` —— **唯一**的自然放置。

旧定义**不许**再拥有 `structure_set`：一套 placement 只该存在一份。留一份旧的
`betternether:` 放置集，世界会按两套 salt 在两套格子上各生成一座同形建筑 ——
既翻倍了密度，`/locate structure mumu:x` 找到的也未必是那座「旧 id」的。

`/locate structure` 的补全来自注册表，所以同时存在 `betternether:altars` 与
`mumu:altars` 时补全列表里会出现两条。这是有意的：前者只用于读旧存档的 starts，
对它执行 locate 会得到「找不到」（它不再被放置），后者才是能定位到的那个。

## 为什么 jungle_temples 没有自己的 structure_set

上游就是把它放在 `pyramid` 的放置集里（`structure_set/pyramid.json` 的
`structures` 同时列了 `pyramid` 与 `jungle_temples`，各 weight 1，共用
spacing 32 / separation 8 / salt 1460687864）。这就是「补一个 placement」的正确形态 ——
给它单开一套 `spacing/separation/salt` 等于凭空发明密度，会把丛林神庙刷成另一种稀有度。
所以本模块的放置不变量是「每个公开结构 id **恰好被引用一次**」，而不是「每个结构各有
一份 structure_set」。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Iterable

from bnlib import namespace

#: 公开结构 id 的命名空间：玩家在 `/locate structure` 后面敲的那个。
PUBLIC_STRUCTURE_NAMESPACE = "mumu"

#: 作者原 id 的命名空间（CE 内容命名空间，含群系、模板池、处理器、方块）。
LEGACY_STRUCTURE_NAMESPACE = namespace.NAMESPACE

#: 结构包在 jar 内的根路径（`<root>/datapacks/betternether-structures`）。
PACK_DIR = "datapacks/betternether-structures"


def public_id(name: str) -> str:
    """一个结构的公开 id，如 `mumu:nether_city`。"""
    return f"{PUBLIC_STRUCTURE_NAMESPACE}:{name}"


def legacy_id(name: str) -> str:
    """一个结构的作者原 id，如 `betternether:nether_city`。"""
    return f"{LEGACY_STRUCTURE_NAMESPACE}:{name}"


def public_structure_path(name: str) -> str:
    """相对结构包根：`data/mumu/worldgen/structure/<name>.json`。"""
    return f"data/{PUBLIC_STRUCTURE_NAMESPACE}/worldgen/structure/{name}.json"


def public_structure_set_path(name: str) -> str:
    """相对结构包根：`data/mumu/worldgen/structure_set/<name>.json`。"""
    return f"data/{PUBLIC_STRUCTURE_NAMESPACE}/worldgen/structure_set/{name}.json"


def legacy_structure_path(name: str) -> str:
    """相对结构包根：`data/betternether/worldgen/structure/<name>.json`。"""
    return f"data/{LEGACY_STRUCTURE_NAMESPACE}/worldgen/structure/{name}.json"


def legacy_structure_set_path(name: str) -> str:
    """相对结构包根：`data/betternether/worldgen/structure_set/<name>.json`（发布后必须为空）。"""
    return f"data/{LEGACY_STRUCTURE_NAMESPACE}/worldgen/structure_set/{name}.json"


# ---------------------------------------------------------------------------
# 发布
# ---------------------------------------------------------------------------
def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def public_structure_document(document: Any) -> Any:
    """公开定义 = 作者定义的**内容副本**，一个字段都不改。

    `start_pool`、`biomes`、`type`、`step`、`start_height`、`terrain_adaptation` 全部保持
    `betternether:` 原样：模板池、群系 tag、处理器列表都没有迁移，公开定义跨命名空间
    引用它们是合法的（原版只按 id 查注册表）。改这些字段等于把「只换 id」变成「顺手改
    结构参数」，与本次目标无关。
    """
    if not isinstance(document, dict):
        raise ValueError("结构定义顶层必须是 JSON 对象。")
    for key in ("type", "biomes"):
        if key not in document:
            raise ValueError(f"结构定义缺少 {key!r}，不是原版 structure 文档。")
    # 走一遍 JSON 往返：拿到独立对象，调用方改它不会污染原文档。
    return json.loads(json.dumps(document))


def public_structure_set_document(document: Any, names: Iterable[str]) -> tuple[Any, list[tuple[str, str]]]:
    """把一个旧放置集改写成公开放置集，返回 `(新文档, [(旧 id, 新 id)])`。

    只改 `structures[].structure` 的命名空间（且只对本包这些结构名生效），
    `placement` 的 `type`/`spacing`/`separation`/`salt` 逐字段保持原样 —— 密度、
    间距、盐值都属于「上游已定的平衡」，不是这次能顺手调的参数。

    引用了一个未发布的结构就**直接报错**：留着那条目录，地图/末影之眼会指向一座
    永不生成的结构，而 `/locate` 也永远找不到它。
    """
    known = set(names)
    if not isinstance(document, dict):
        raise ValueError("structure_set 顶层必须是 JSON 对象。")
    entries = document.get("structures")
    if not isinstance(entries, list) or not entries:
        raise ValueError("structure_set 的 structures 不是非空数组。")

    published = json.loads(json.dumps(document))
    rewrites: list[tuple[str, str]] = []
    for index, entry in enumerate(published["structures"]):
        target = entry.get("structure") if isinstance(entry, dict) else None
        if not isinstance(target, str) or ":" not in target:
            raise ValueError(f"structure_set 的 structures[{index}] 缺少带命名空间的 structure 字段。")
        source_ns, name = target.split(":", 1)
        if source_ns != LEGACY_STRUCTURE_NAMESPACE:
            raise ValueError(
                f"structure_set 的 structures[{index}] 引用了 {target}；"
                f"放置集只应引用 {LEGACY_STRUCTURE_NAMESPACE}: 下的定义（发布时改成公开 id）。")
        if name not in known:
            raise ValueError(
                f"structure_set 的 structures[{index}] 引用了 {target}，但它没有对应的结构定义："
                "旧 id 会指向一座永不生成的结构，公开 id 也不会存在。")
        new_id = public_id(name)
        entry["structure"] = new_id
        rewrites.append((target, new_id))
    return published, rewrites


def _assert_each_public_placed_once(names: list[str], set_documents: dict[str, Any]) -> None:
    """发布侧不变量：每个公开结构 id 恰好被一个放置集引用一次。

    这条同时覆盖两种真实故障：漏掉某个结构（jungle_temples 只在 pyramid 的集合里，
    很容易被当成「缺 structure_set」而错补一套密度）与重复放置（同一格各生成一座）。
    """
    counts: dict[str, int] = {}
    for document in set_documents.values():
        for entry in document.get("structures", []):
            target = entry.get("structure") if isinstance(entry, dict) else None
            if isinstance(target, str):
                counts[target] = counts.get(target, 0) + 1

    problems: list[str] = []
    for name in names:
        rid = public_id(name)
        count = counts.get(rid, 0)
        if count == 0:
            problems.append(f"{rid} 没有被任何 structure_set 放置：/locate 找不到、也不会自然生成。")
        elif count > 1:
            problems.append(f"{rid} 被放置 {count} 次：同一格会各生成一座，密度翻倍。")
    if problems:
        raise ValueError("结构放置集不闭合：\n  - " + "\n  - ".join(problems))


def publish(pack_root: Path) -> dict[str, object]:
    """把结构包发布成「定义两份、放置一份」的布局，返回统计。

    幂等：重复运行对同一棵树产生逐字节相同的输出。已经发布过的树（旧
    `structure_set` 目录不存在、`data/mumu/worldgen/structure_set` 已有文件）直接读回
    公开放置集做不变量校验，不再改写 —— 否则第二次运行会「找不到旧集合可用」而报错。
    """
    pack_root = Path(pack_root)
    legacy_dir = pack_root / "data" / LEGACY_STRUCTURE_NAMESPACE / "worldgen" / "structure"
    if not legacy_dir.is_dir():
        raise ValueError(f"找不到结构定义目录：{legacy_dir}")
    names = sorted(path.stem for path in legacy_dir.glob("*.json"))
    if not names:
        raise ValueError(f"{legacy_dir} 下没有任何结构定义，发布出来的包会是空的。")

    published = 0
    for name in names:
        document = public_structure_document(_read_json(legacy_dir / f"{name}.json"))
        _write_json(pack_root / public_structure_path(name), document)
        published += 1

    legacy_sets_dir = pack_root / "data" / LEGACY_STRUCTURE_NAMESPACE / "worldgen" / "structure_set"
    public_sets_dir = pack_root / "data" / PUBLIC_STRUCTURE_NAMESPACE / "worldgen" / "structure_set"

    rewrites: list[tuple[str, str]] = []
    set_documents: dict[str, Any] = {}
    if legacy_sets_dir.is_dir():
        legacy_sets = sorted(legacy_sets_dir.glob("*.json"))
        for path in legacy_sets:
            document, pairs = public_structure_set_document(_read_json(path), names)
            _write_json(pack_root / public_structure_set_path(path.stem), document)
            set_documents[path.stem] = document
            rewrites.extend(pairs)
            # 旧命名空间不再拥有放置：一份 placement 只能存在一份。
            path.unlink()
        if not any(legacy_sets_dir.iterdir()):
            legacy_sets_dir.rmdir()
    elif public_sets_dir.is_dir():
        for path in sorted(public_sets_dir.glob("*.json")):
            set_documents[path.stem] = _read_json(path)
    else:
        raise ValueError(
            f"既没有旧的 placement（{legacy_sets_dir}）也没有已发布的 "
            f"{public_sets_dir}：结构不会被放置。")

    _assert_each_public_placed_once(names, set_documents)
    return {
        "structures": published,
        "structure_sets": len(set_documents),
        "rewritten_refs": len(rewrites),
    }

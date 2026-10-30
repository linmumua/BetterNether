#!/usr/bin/env python3
"""贴图 alpha 检测 + 按模型纹理引用扫描，供生成器判断「这个方块该不该透明」。

## 为什么需要独立的一层

上游 `blocks.py:250` 附近的注释已经写明后果：带 alpha 的贴图如果被按 SOLID 渲染，
玻璃与树叶会变成实心色块。但这个判断一直没人真正做过 —— 之前是靠贴图名字里有没有
`glass` 之类的词去猜，猜错的代价是玩家看到实心玻璃或紫黑格子。

本模块只做两件事实判断，不做任何命名启发式：

  1. 一张 PNG 到底有没有 alpha < 255 的像素（`inspect_texture`）；
  2. 一个模型 JSON 最终引用了哪些真实贴图（`model_texture_refs`），以及这些贴图里
     有没有不透明的（`scan_model_textures`）。

## 依赖

图片解码用 **Pillow**（实测环境：Python 3.12.10 + Pillow 12.3.0，`import PIL` 可用）。
缺失时在导入期就抛 ImportError 并写明安装命令，而不是在跑了几万张图之后才崩。

## 两个容易搞错的点

  * **mode 带 `A` 不等于有透明度**：调色板图（`P`）可能完全没有 tRNS，此时它 100% 不透明。
    所以判定「是否携带 alpha 信息」看的是 mode 与 `info["transparency"]`，而不是 mode 里有没有 A。
  * **模型里的贴图值可能是槽位引用**：`"particle": "#all"` 里的 `#all` 不是贴图。
    必须先按槽位链解析到底，还可能遇到 `#a -> #b -> #a` 这种环，解析必须能停下来。
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, MutableMapping

try:  # pragma: no cover - 导入失败属于环境问题，不是逻辑分支
    from PIL import Image
except ImportError as exc:  # pragma: no cover
    raise ImportError(
        "bnlib.transparency 需要 Pillow 解码 PNG：python -m pip install pillow"
    ) from exc


# 与 blocks.NAMESPACE 一致；这里不 import blocks，避免把 160KB 的生成器拖进只查贴图的调用方。
NAMESPACE = "betternether"
# 模型里裸写 `block/xxx` 的贴图，MC 按**原版**命名空间解析（与 blocks.texture_ref 同规则）。
DEFAULT_TEXTURE_NAMESPACE = "minecraft"

# 两类判定结果。只有两态是刻意的：生成器只需要回答「能不能当不透明方块渲染」，
# 半透明与全透明在 CE 侧都是 transparent，细分不会改变写入的配置，只会增加分支。
OPAQUE = "opaque"
TRANSLUCENT = "translucent"


@dataclass(frozen=True)
class TextureAlpha:
    """一张贴图的 alpha 事实。"""

    path: Path
    width: int
    height: int
    mode: str
    #: 图片文件是否真的携带 alpha 信息（RGB 图 / 无 tRNS 的调色板图为 False）。
    has_alpha_channel: bool
    #: 全图最小 alpha 值，0-255；没有 alpha 通道时为 255。
    min_alpha: int

    @property
    def is_opaque(self) -> bool:
        """全图每个像素都 alpha == 255。"""
        return self.min_alpha == 255

    @property
    def kind(self) -> str:
        """`OPAQUE` 或 `TRANSLUCENT`（含半透明与全透明）。"""
        return OPAQUE if self.min_alpha == 255 else TRANSLUCENT


@dataclass(frozen=True)
class ModelTextures:
    """一个模型引用的贴图及其 alpha 事实。"""

    #: 去重后的具体贴图引用（`ns:block/x` 形态，不含 `#slot`）。
    refs: tuple[str, ...]
    #: 引用 -> 贴图 alpha；只含在磁盘上找到的图。
    resolved: Mapping[str, TextureAlpha]
    #: 解析到路径但文件不存在的引用。调用方要报出来，不能默默当成不透明。
    missing: tuple[str, ...]

    @property
    def translucent_refs(self) -> tuple[str, ...]:
        return tuple(ref for ref, alpha in self.resolved.items() if not alpha.is_opaque)

    @property
    def has_translucency(self) -> bool:
        """模型用到的贴图里至少有一张存在 alpha < 255 的像素。"""
        return any(not alpha.is_opaque for alpha in self.resolved.values())


class TextureError(OSError):
    """贴图无法给出可信的 alpha 结论（空图 / 通道异常）。

    继承 OSError，与 PIL 对损坏文件抛出的 `UnidentifiedImageError` 归在同一类里：
    调用方用 `except OSError` 就能覆盖全部「这张图不能用来做判定」的情形。
    """


def inspect_texture(path: Path) -> TextureAlpha:
    """读取一张 PNG，返回它的 alpha 事实。

    打不开、不是图片、或拿不到可信的 alpha 极值时一律抛 `OSError`
    （含 `PIL.UnidentifiedImageError` 与 {@link TextureError}）—— 这类错误必须让调用方
    看见，伪装成「不透明」会让一张裂图悄悄变成实心方块。
    """
    with Image.open(path) as image:
        width, height = image.size
        mode = image.mode
        # mode 带 A 不代表真有透明像素：调色板图的 alpha 信息在 info["transparency"] 里。
        carries_alpha = mode in ("RGBA", "LA", "PA", "RGBa", "La") or (
            mode == "P" and "transparency" in image.info
        )
        if not carries_alpha:
            return TextureAlpha(
                path=Path(path),
                width=width,
                height=height,
                mode=mode,
                has_alpha_channel=False,
                min_alpha=255,
            )
        rgba = image.convert("RGBA")
        low, _high = rgba.getchannel("A").getextrema()
    # getextrema 对空图返回 (None, None)。这里**不能**回退成某个默认值：退回 255 是悄悄
    # 判成不透明（裂图变实心方块），退回 0 是伪造一份「全透明」的事实。两种都是在没有
    # 证据的情况下编结论，所以直接报错，交给人看。
    if low is None:
        raise TextureError(f"{path}: 取不到 alpha 极值（空图或通道异常）")
    return TextureAlpha(
        path=Path(path),
        width=width,
        height=height,
        mode=mode,
        has_alpha_channel=True,
        min_alpha=int(low),
    )


def texture_kind(path: Path) -> str:
    """{@link inspect_texture} 的薄封装：只回答 `OPAQUE` / `TRANSLUCENT`。"""
    return inspect_texture(path).kind


def normalize_texture_ref(ref: str) -> str:
    """把贴图值归一化成**带命名空间的完整引用**（与 `blocks.texture_ref` 同规则、同幂等性）。

    三种上游写法都要正确处理：`betternether:block/foo`（原样）、`minecraft:block/foo`（原样）、
    `block/foo`（补成 `minecraft:block/foo` —— 上游裸路径全是原版贴图）。历史遗留的
    `minecraft:block/minecraft:block/foo` 会被折掉重复命名空间，保证反复调用不会再累积。
    """
    text = ref.strip()
    while "block/minecraft:block/" in text:
        text = text.replace("block/minecraft:block/", "block/")
    head, sep, rest = text.partition(":")
    if sep and rest.startswith("block/"):
        return text
    if text.startswith("block/"):
        return DEFAULT_TEXTURE_NAMESPACE + ":" + text
    return f"{NAMESPACE}:block/{text}"


def resolve_texture_ref(ref: str, assets_root: Path) -> Path | None:
    """把模型里的贴图值解析成磁盘路径。

    `assets_root` 是**资源命名空间的上层目录**（上游资源包里的 `assets/`），
    即 `betternether:block/foo` -> `<assets_root>/betternether/textures/block/foo.png`。
    命名空间规则见 {@link normalize_texture_ref}。槽位引用（`#slot`）与空值返回 None ——
    它们不是贴图。

    返回的路径**不保证存在**：是否存在由调用方用 `is_file()` 判断（见 {@link scan_model_textures}）。
    """
    text = (ref or "").strip()
    if not text or text.startswith("#"):
        return None
    namespace, _sep, tail = normalize_texture_ref(text).partition(":")
    return Path(assets_root) / namespace / "textures" / f"{tail}.png"


def resolve_slot_refs(textures: Mapping[str, object]) -> Mapping[str, str]:
    """把模型的 textures 段解析成「槽位 -> 具体贴图引用」。

    只保留能解析到底的槽位：`#a -> #b -> #a` 这种环、指向不存在槽位的引用、非字符串值，
    全部丢弃而不是硬塞一个别的贴图（塞错贴图比缺一条引用更难排查）。
    """
    resolved: dict[str, str] = {}
    for slot, value in textures.items():
        if not isinstance(value, str):
            continue
        current = value.strip()
        seen = {slot}
        while current.startswith("#"):
            target = current[1:]
            if target in seen:
                current = ""
                break
            seen.add(target)
            next_value = textures.get(target)
            if not isinstance(next_value, str):
                current = ""
                break
            current = next_value.strip()
        if current:
            resolved[slot] = current
    return resolved


def model_texture_refs(model: Mapping[str, object]) -> tuple[str, ...]:
    """模型实际用到的贴图引用（已归一化成 `ns:block/x`、去重、保序，剥掉了 `#slot`）。

    归一化是必要的：`block/stone` 与 `minecraft:block/stone` 是同一张图，不归一化会让
    同一个模型的引用清单里出现两条，也会让下游 miss/命中判断不一致。
    """
    declared = model.get("textures")
    if not isinstance(declared, Mapping):
        return ()
    refs: list[str] = []
    for value in resolve_slot_refs(declared).values():
        ref = normalize_texture_ref(value)
        if ref not in refs:
            refs.append(ref)
    return tuple(refs)


def scan_model_textures(
    model: Mapping[str, object],
    assets_root: Path,
    cache: MutableMapping[Path, TextureAlpha] | None = None,
) -> ModelTextures:
    """扫描一个模型的贴图引用并判定透明度。

    `cache` 可选：同一个贴图会被大量模型共用（`block/obsidian` 之类），生成器跑全量时
    传入一个 dict 就能避免反复解码同一张 PNG。

    贴图存在但解码失败时会抛出 `OSError`，**不会**被塞进 `missing` 或判成不透明：
    `missing` 只表示「引用解析到了路径但文件不存在」，两种情况调用方的处理方式不同。
    """
    refs = model_texture_refs(model)
    resolved: dict[str, TextureAlpha] = {}
    missing: list[str] = []
    for ref in refs:
        path = resolve_texture_ref(ref, assets_root)
        if path is None:
            continue
        if cache is not None and path in cache:
            resolved[ref] = cache[path]
            continue
        if not path.is_file():
            missing.append(ref)
            continue
        alpha = inspect_texture(path)
        if cache is not None:
            cache[path] = alpha
        resolved[ref] = alpha
    return ModelTextures(refs=refs, resolved=resolved, missing=tuple(missing))


def inspect_textures(paths: Iterable[Path]) -> dict[Path, TextureAlpha]:
    """批量检测（生成器预热缓存用）。"""
    return {Path(path): inspect_texture(Path(path)) for path in paths}


def load_model(path: Path) -> Mapping[str, object] | None:
    """读模型 JSON；解析失败返回 None，由调用方报告而不是抛异常中断整批扫描。"""
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return payload if isinstance(payload, Mapping) else None

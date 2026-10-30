#!/usr/bin/env python3
"""`bnlib.transparency` 的 alpha 检测与模型贴图引用扫描。

跑法：

    python -m pytest scripts/tests/test_texture_alpha.py -q

## 在验什么

生成器要用「贴图里有没有 alpha < 255」来决定这个方块能不能按不透明渲染。判错的两种后果
都是可见的：漏判 -> 玻璃/树叶变成实心色块；误判 -> 本来不透明的方块被迫走透明通道，
多出一份透明共享状态（还会挤占方块状态预算）。

所以这里锁的是**判定依据**本身，而不是某张贴图的结论：

  * RGBA 全 255 与 RGB（无 alpha 通道）都必须判成 `OPAQUE`；
  * 半透明与全透明都必须判成 `TRANSLUCENT`；
  * 调色板图（`P`）有没有 tRNS 决定它是否携带 alpha —— mode 里没有 `A` 不等于没有透明；
  * 模型里的 `#slot` 引用必须先解析到底，环与缺失槽位不能把扫描卡死或塞进假贴图；
  * 找不到的贴图要进 `missing`，不能被当成「不透明」静默通过。
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bnlib import transparency  # noqa: E402

UPSTREAM_ASSETS = (
    Path(__file__).resolve().parents[2]
    / "upstream"
    / "BetterNether"
    / "src/main/resources/assets"
)


def _png(path: Path, mode: str, fill, size=(4, 4), **save_kwargs) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new(mode, size, fill).save(path, **save_kwargs)
    return path


# --- 单张贴图判定 -----------------------------------------------------------------


def test_rgba_fully_opaque_is_opaque(tmp_path):
    path = _png(tmp_path / "opaque.png", "RGBA", (10, 20, 30, 255))
    alpha = transparency.inspect_texture(path)
    assert alpha.kind == transparency.OPAQUE
    assert alpha.is_opaque is True
    assert alpha.has_alpha_channel is True, "有 alpha 通道但全部 255，仍必须判不透明"
    assert alpha.min_alpha == 255
    assert (alpha.width, alpha.height, alpha.mode) == (4, 4, "RGBA")


def test_rgba_semi_transparent_is_translucent(tmp_path):
    path = _png(tmp_path / "semi.png", "RGBA", (10, 20, 30, 128))
    alpha = transparency.inspect_texture(path)
    assert alpha.kind == transparency.TRANSLUCENT
    assert alpha.is_opaque is False
    assert alpha.min_alpha == 128


def test_rgba_fully_transparent_is_translucent(tmp_path):
    # 全透明也是「存在 alpha < 255」，与半透明同归一类：CE 侧都是 transparent。
    path = _png(tmp_path / "clear.png", "RGBA", (10, 20, 30, 0))
    alpha = transparency.inspect_texture(path)
    assert alpha.kind == transparency.TRANSLUCENT
    assert alpha.min_alpha == 0


def test_rgb_without_alpha_is_opaque(tmp_path):
    path = _png(tmp_path / "rgb.png", "RGB", (10, 20, 30))
    alpha = transparency.inspect_texture(path)
    assert alpha.kind == transparency.OPAQUE
    assert alpha.has_alpha_channel is False
    assert alpha.min_alpha == 255
    assert transparency.texture_kind(path) == transparency.OPAQUE


def test_single_translucent_pixel_is_enough(tmp_path):
    # 只有 1 个像素 alpha < 255 也必须判透明：漏掉它就是把镂空贴图渲染成实心块。
    path = tmp_path / "one.png"
    image = Image.new("RGBA", (4, 4), (0, 0, 0, 255))
    image.putpixel((2, 3), (0, 0, 0, 254))
    image.save(path)
    assert transparency.inspect_texture(path).min_alpha == 254


def test_palette_png_with_transparency_is_translucent(tmp_path):
    # 上游大量贴图是 8 位调色板；mode 是 `P` 而不是 `RGBA`。
    path = tmp_path / "pal_trns.png"
    image = Image.new("P", (4, 4), 0)
    image.putpalette([0, 0, 0, 255, 255, 255] + [0, 0, 0] * 254)
    image.info["transparency"] = 0
    image.save(path, transparency=0)
    alpha = transparency.inspect_texture(path)
    assert alpha.mode == "P"
    assert alpha.has_alpha_channel is True
    assert alpha.kind == transparency.TRANSLUCENT


def test_palette_png_without_transparency_is_opaque(tmp_path):
    # mode 里没有 `A` 且没有 tRNS：不能因为「不是 RGB」就误判成透明。
    path = tmp_path / "pal_plain.png"
    image = Image.new("P", (4, 4), 0)
    image.putpalette([0, 0, 0, 255, 255, 255] + [0, 0, 0] * 254)
    image.save(path)
    alpha = transparency.inspect_texture(path)
    assert alpha.has_alpha_channel is False
    assert alpha.kind == transparency.OPAQUE


def test_missing_file_raises_instead_of_reporting_opaque(tmp_path):
    with pytest.raises(OSError):
        transparency.inspect_texture(tmp_path / "nope.png")


def _craft_png(width: int, height: int) -> bytes:
    """手搓一个 PNG 文件头：用来证明「退化图不会换来一个静默结论」。"""
    import struct
    import zlib

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(b""))
        + chunk(b"IEND", b"")
    )


def test_broken_png_raises_instead_of_opaque(tmp_path):
    # 裂图/非图片必须是「报错」，不能给一个 OPAQUE 的结论把方块渲成实心块。
    path = tmp_path / "garbage.png"
    path.write_bytes(b"not a png at all")
    with pytest.raises(OSError):
        transparency.inspect_texture(path)
    assert transparency.TextureError.__mro__[1] is OSError, "调用方应能只 catch OSError"


def test_truncated_png_raises_instead_of_opaque(tmp_path):
    real = _png(tmp_path / "ok.png", "RGBA", (1, 2, 3, 4))
    truncated = tmp_path / "truncated.png"
    truncated.write_bytes(real.read_bytes()[:40])
    with pytest.raises(OSError):
        transparency.inspect_texture(truncated)


def test_zero_size_png_raises_instead_of_opaque(tmp_path):
    # 空图（0x0）取不到 alpha 极值；无论拦在 PIL 还是拦在本模块，结论都必须是异常。
    path = tmp_path / "zero.png"
    path.write_bytes(_craft_png(0, 0))
    with pytest.raises(OSError):
        transparency.inspect_texture(path)


def test_none_extrema_raises_texture_error(tmp_path, monkeypatch):
    # getextrema 返回 (None, None) 的分支在真实 PNG 里碰不到（PIL 连 0x0 文件都打不开），
    # 所以这里直接替换它，把「不许静默给出 255 或 0」这条约定钉死。
    path = _png(tmp_path / "half.png", "RGBA", (1, 2, 3, 90))
    monkeypatch.setattr(
        "PIL.Image.Image.getextrema", lambda self: (None, None), raising=True
    )
    with pytest.raises(transparency.TextureError):
        transparency.inspect_texture(path)


# --- 贴图引用解析 -----------------------------------------------------------------


def test_bare_block_ref_resolves_to_vanilla_namespace(tmp_path):
    # 上游模型里裸写的 `block/xxx` 全部是原版贴图（与 blocks.texture_ref 同规则）。
    assert transparency.resolve_texture_ref("block/stone", tmp_path) == (
        tmp_path / "minecraft" / "textures" / "block" / "stone.png"
    )


def test_namespaced_ref_keeps_its_namespace(tmp_path):
    assert transparency.resolve_texture_ref("betternether:block/agave_leaf", tmp_path) == (
        tmp_path / "betternether" / "textures" / "block" / "agave_leaf.png"
    )


def test_duplicated_namespace_is_collapsed(tmp_path):
    # 历史数据里出现过 `minecraft:block/minecraft:block/x`，解析不能拼出三层目录。
    assert transparency.resolve_texture_ref(
        "minecraft:block/minecraft:block/stone", tmp_path
    ) == (tmp_path / "minecraft" / "textures" / "block" / "stone.png")


def test_slot_refs_and_empty_values_are_not_textures(tmp_path):
    assert transparency.resolve_texture_ref("#all", tmp_path) is None
    assert transparency.resolve_texture_ref("", tmp_path) is None


def test_model_texture_refs_follows_slot_chain_and_dedupes():
    model = {
        "parent": "block/cube_all",
        "textures": {
            "all": "betternether:block/x",
            "particle": "#all",
            "side": "betternether:block/x",  # 同一张图，必须去重
            "top": "#middle",
            "middle": "#all",
        },
    }
    assert transparency.model_texture_refs(model) == ("betternether:block/x",)


def test_model_texture_refs_survives_cycles_and_broken_slots():
    model = {
        "textures": {
            "a": "#b",
            "b": "#a",       # 环
            "c": "#missing",  # 指向不存在的槽位
            "d": "#d",       # 自引用
            "ok": "block/stone",
            "bad": 5,        # 非字符串，直接忽略
        }
    }
    assert transparency.model_texture_refs(model) == ("minecraft:block/stone",)


def test_model_without_textures_section_has_no_refs():
    assert transparency.model_texture_refs({"parent": "block/cube_all"}) == ()


# --- 端到端：模型 -> 磁盘贴图 ------------------------------------------------------


def _assets_root(tmp_path: Path) -> Path:
    return tmp_path / "assets"


def test_scan_model_textures_reports_alpha_and_missing(tmp_path):
    root = _assets_root(tmp_path)
    _png(root / "betternether/textures/block/glassish.png", "RGBA", (1, 2, 3, 40))
    _png(root / "betternether/textures/block/solid.png", "RGB", (9, 9, 9))
    model = {
        "textures": {
            "glass": "betternether:block/glassish",
            "solid": "betternether:block/solid",
            "lost": "betternether:block/not_shipped",  # 磁盘上没有
        }
    }
    result = transparency.scan_model_textures(model, root)
    assert set(result.refs) == {
        "betternether:block/glassish",
        "betternether:block/solid",
        "betternether:block/not_shipped",
    }
    assert result.missing == ("betternether:block/not_shipped",)
    assert result.translucent_refs == ("betternether:block/glassish",)
    assert result.has_translucency is True
    assert set(result.resolved) == {
        "betternether:block/glassish",
        "betternether:block/solid",
    }


def test_scan_model_textures_all_opaque_has_no_translucency(tmp_path):
    root = _assets_root(tmp_path)
    _png(root / "minecraft/textures/block/stone.png", "RGB", (9, 9, 9))
    result = transparency.scan_model_textures(
        {"textures": {"all": "block/stone"}}, root
    )
    assert result.refs == ("minecraft:block/stone",)
    assert result.has_translucency is False
    assert result.missing == ()


def test_scan_model_textures_honours_cache(tmp_path):
    # 生成器跑全量时会复用同一个 dict，避免重复解码同一张 PNG。
    root = _assets_root(tmp_path)
    path = _png(root / "betternether/textures/block/shared.png", "RGB", (1, 1, 1))
    cache: dict[Path, transparency.TextureAlpha] = {}
    model = {"textures": {"a": "betternether:block/shared", "b": "#a"}}
    first = transparency.scan_model_textures(model, root, cache)
    assert set(cache) == {path}
    # 第二遍即使把文件删掉也必须命中缓存（证明缓存真的被读取，而不是重扫磁盘）。
    path.unlink()
    second = transparency.scan_model_textures(model, root, cache)
    assert second.resolved == first.resolved
    assert second.missing == ()


@pytest.mark.skipif(
    not (UPSTREAM_ASSETS / "betternether/textures/block").is_dir(),
    reason="上游资源不在本机（该断言只在带 upstream 的工作区有意义）",
)
def test_real_upstream_texture_is_scannable():
    # 真实贴图走一遍：证明本模块对上游实际的 PNG 编码（调色板/索引）也能给出结论。
    root = UPSTREAM_ASSETS
    textures = sorted((root / "betternether/textures/block").glob("*.png"))[:5]
    assert textures, "上游 textures/block 不该是空的"
    alphas = transparency.inspect_textures(textures)
    assert set(alphas) == set(textures)
    assert all(alpha.kind in (transparency.OPAQUE, transparency.TRANSLUCENT) for alpha in alphas.values())

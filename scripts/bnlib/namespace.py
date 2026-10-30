#!/usr/bin/env python3
"""本移植对外使用的命名空间，以及 id 前缀改写规则。

## 为什么单独一个文件

id 前缀同时出现在：

  * CE 资源包（`pack.yml` 的 namespace、block/item/lang 的 id）
  * 数据包（群系、结构、template_pool、tag 的 namespaced id）
  * 36 个结构 NBT 里的方块调色板（二进制，必须重新序列化才改得动）
  * Java 代码里的 `CRITICAL_BLOCKS`、`NetherBiomes.NAMESPACE` 等常量

散落在四个脚本的字符串里就必然漏改，所以只有这里是「真值」，其余全部引用它；
Java 侧对应 `NetherBiomes.NAMESPACE`。

## 为什么改写是「多前缀 → 单目标」

命名空间改过两次：先是上游的 `betternether`，中途换成 `mumu`，最后又改回 `betternether`。
`KNOWN_PREFIXES` 记住所有用过的前缀，`rewrite_id` 把它们统一收敛到 `NAMESPACE`。
这样无论产物里残留的是哪一代前缀，重新生成一次都会收敛到当前值。

**特例**：当 `NAMESPACE == UPSTREAM_NAMESPACE`（当前就是这样）时，改写是恒等变换。
所有调用方都必须用 `is_foreign()` 判断残留，不能用「结果里还有没有 `betternether:`」来判断 ——
那样会把完全正确的文件误判成半改。
"""

from __future__ import annotations

# 本移植对外的命名空间。CE 包、数据包、NBT 里的方块 id 都用它。
NAMESPACE = "betternether"

# 上游模组的命名空间。读取上游文件时使用；与 NAMESPACE 相同时改写即恒等。
UPSTREAM_NAMESPACE = "betternether"

UPSTREAM_PREFIX = UPSTREAM_NAMESPACE + ":"
TARGET_PREFIX = NAMESPACE + ":"

# 历史上用过的所有前缀。产物里出现其中任何一个，都要收敛到 TARGET_PREFIX。
KNOWN_PREFIXES = ("betternether:", "mumu:")


def rewrite_id(text: str) -> str:
    """把任一代已知前缀换成当前命名空间；其余字符串原样返回。

    只改前缀，不动 path：`mumu:nether_city/start` -> `betternether:nether_city/start`。
    """
    for prefix in KNOWN_PREFIXES:
        if text.startswith(prefix):
            return TARGET_PREFIX + text[len(prefix):]
    return text


def is_foreign(text: str) -> bool:
    """这个字符串是否引用了「非当前」命名空间。

    这是残留检查的正确判据：不能只问「有没有 betternether:」——
    当它就是当前命名空间时，那个问题是错的。
    """
    for prefix in KNOWN_PREFIXES:
        if prefix != TARGET_PREFIX and text.startswith(prefix):
            return True
    return False


def is_ours(text: str) -> bool:
    """这个字符串是否引用了当前命名空间。"""
    return text.startswith(TARGET_PREFIX)

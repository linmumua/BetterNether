#!/usr/bin/env python3
"""最小但完整的 NBT 读写器，用于在不破坏结构的前提下重写字符串。

## 为什么不能用字节替换

NBT 的 TAG_String 是「2 字节无符号长度 + UTF-8 内容」。把 `betternether:`（12 字符）
替换成 `mumu:`（5 字符）会让内容短 7 字节，而长度前缀还写着 12 —— 结果不是一个「名字变了」
的文件，而是一个**损坏的文件**：后面的字节全部错位，服务器读结构时会直接解析失败。

所以必须真正解析成树、改字符串、再重新序列化。

## 保真度

为了「读进来再写出去」不改变任何东西，这里用 `(tag_id, value)` 二元组保留原始类型信息：
Python 的 int 分不出 TAG_Byte / TAG_Short / TAG_Int / TAG_Long，而列表的元素类型
（TAG_List 里存的 elem_type）必须原样写回，空列表尤其依赖它 —— 丢了这个信息，空列表
就会写成类型 0（TAG_End），那是非法的。

返回的树可以直接交给 `write_named` 写回，`roundtrip_ok` 会验证这一点。
"""

from __future__ import annotations

import gzip
import struct
from typing import Any

# 标签 id
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
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class NbtError(Exception):
    """结构损坏或不受支持时抛出。绝不静默返回半个结果。"""


class Reader:
    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0

    def take(self, count: int) -> bytes:
        end = self.pos + count
        if end > len(self.data):
            raise NbtError(f"读取越过末尾：需要 {count} 字节，剩余 {len(self.data) - self.pos}")
        chunk = self.data[self.pos:end]
        self.pos = end
        return chunk

    def u1(self) -> int:
        return self.take(1)[0]

    def i1(self) -> int:
        return struct.unpack(">b", self.take(1))[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def i4(self) -> int:
        return struct.unpack(">i", self.take(4))[0]

    def i8(self) -> int:
        return struct.unpack(">q", self.take(8))[0]

    def f4(self) -> float:
        return struct.unpack(">f", self.take(4))[0]

    def f8(self) -> float:
        return struct.unpack(">d", self.take(8))[0]

    def utf(self) -> str:
        length = self.u2()
        return self.take(length).decode("utf-8")

    def payload(self, tag: int) -> Any:
        if tag == TAG_BYTE:
            return self.i1()
        if tag == TAG_SHORT:
            return struct.unpack(">h", self.take(2))[0]
        if tag == TAG_INT:
            return self.i4()
        if tag == TAG_LONG:
            return self.i8()
        if tag == TAG_FLOAT:
            return self.f4()
        if tag == TAG_DOUBLE:
            return self.f8()
        if tag == TAG_BYTE_ARRAY:
            return self.take(self.i4())
        if tag == TAG_STRING:
            return self.utf()
        if tag == TAG_LIST:
            elem_type = self.u1()
            length = self.i4()
            if length < 0:
                raise NbtError(f"列表长度为负：{length}")
            return (elem_type, [(elem_type, self.payload(elem_type)) for _ in range(length)])
        if tag == TAG_COMPOUND:
            entries: dict[str, tuple[int, Any]] = {}
            while True:
                child = self.u1()
                if child == TAG_END:
                    break
                name = self.utf()
                entries[name] = (child, self.payload(child))
            return entries
        if tag == TAG_INT_ARRAY:
            count = self.i4()
            return [self.i4() for _ in range(count)]
        if tag == TAG_LONG_ARRAY:
            count = self.i4()
            return [self.i8() for _ in range(count)]
        raise NbtError(f"未知标签类型 {tag}")


class Writer:
    def __init__(self) -> None:
        self.buf = bytearray()

    def u1(self, value: int) -> None:
        self.buf.append(value & 0xFF)

    def i4(self, value: int) -> None:
        self.buf += struct.pack(">i", value)

    def u2(self, value: int) -> None:
        self.buf += struct.pack(">H", value)

    def utf(self, text: str) -> None:
        encoded = text.encode("utf-8")
        if len(encoded) > 0xFFFF:
            raise NbtError(f"字符串过长（{len(encoded)} 字节）：{text[:40]}…")
        self.u2(len(encoded))
        self.buf += encoded

    def payload(self, tag: int, value: Any) -> None:
        if tag == TAG_BYTE:
            self.buf += struct.pack(">b", value)
        elif tag == TAG_SHORT:
            self.buf += struct.pack(">h", value)
        elif tag == TAG_INT:
            self.i4(value)
        elif tag == TAG_LONG:
            self.buf += struct.pack(">q", value)
        elif tag == TAG_FLOAT:
            self.buf += struct.pack(">f", value)
        elif tag == TAG_DOUBLE:
            self.buf += struct.pack(">d", value)
        elif tag == TAG_BYTE_ARRAY:
            self.i4(len(value))
            self.buf += value
        elif tag == TAG_STRING:
            self.utf(value)
        elif tag == TAG_LIST:
            elem_type, items = value
            self.u1(elem_type)
            self.i4(len(items))
            for item_tag, item_value in items:
                if item_tag != elem_type:
                    raise NbtError(
                        f"列表元素类型不一致：声明 {elem_type}，实际 {item_tag}")
                self.payload(elem_type, item_value)
        elif tag == TAG_COMPOUND:
            for name, (child_tag, child_value) in value.items():
                self.u1(child_tag)
                self.utf(name)
                self.payload(child_tag, child_value)
            self.u1(TAG_END)
        elif tag == TAG_INT_ARRAY:
            self.i4(len(value))
            for number in value:
                self.i4(number)
        elif tag == TAG_LONG_ARRAY:
            self.i4(len(value))
            for number in value:
                self.buf += struct.pack(">q", number)
        else:
            raise NbtError(f"未知标签类型 {tag}")


def read_bytes(data: bytes) -> tuple[str, int, Any]:
    """解析未压缩的 NBT，返回 (根名字, 根标签类型, 根载荷)。"""
    reader = Reader(data)
    root_tag = reader.u1()
    if root_tag == TAG_END:
        raise NbtError("根标签是 TAG_End，不是有效 NBT")
    root_name = reader.utf()
    value = reader.payload(root_tag)
    if reader.pos != len(data):
        raise NbtError(f"解析结束后仍有 {len(data) - reader.pos} 字节未消费，文件结构不符合预期")
    return root_name, root_tag, value


def write_bytes(root_name: str, root_tag: int, value: Any) -> bytes:
    writer = Writer()
    writer.u1(root_tag)
    writer.utf(root_name)
    writer.payload(root_tag, value)
    return bytes(writer.buf)


def read_file(path) -> tuple[str, int, Any]:
    """解析 gzip 压缩的 .nbt 文件。"""
    with gzip.open(path, "rb") as handle:
        return read_bytes(handle.read())


def write_file(path, root_name: str, root_tag: int, value: Any) -> None:
    """以 gzip 写回；结构与 Minecraft 自己的 region 无关，只是让文件保持压缩形态。"""
    payload = write_bytes(root_name, root_tag, value)
    with gzip.GzipFile(filename="", mode="wb", fileobj=open(path, "wb"), mtime=0) as handle:
        handle.write(payload)


def map_strings(node: Any, transform) -> Any:
    """对树里每一个 TAG_String 应用 transform，返回新树（不修改入参）。"""
    tag, value = node
    if tag == TAG_STRING:
        return (tag, transform(value))
    if tag == TAG_COMPOUND:
        return (tag, {name: map_strings(child, transform) for name, child in value.items()})
    if tag == TAG_LIST:
        elem_type, items = value
        return (tag, (elem_type, [map_strings(item, transform) for item in items]))
    return node


def collect_strings(node: Any, out: set[str] | None = None) -> set[str]:
    """收集树里所有字符串，用于校验残留。"""
    if out is None:
        out = set()
    tag, value = node
    if tag == TAG_STRING:
        out.add(value)
    elif tag == TAG_COMPOUND:
        for child in value.values():
            collect_strings(child, out)
    elif tag == TAG_LIST:
        for item in value[1]:
            collect_strings(item, out)
    return out

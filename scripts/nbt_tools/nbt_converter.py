#!/usr/bin/env python3
"""
NBT 转换工具
================
实现 Minecraft .nbt 文件与 SNBT / JSON 之间的互相转化。

依赖:
    pip install nbtlib

用法示例:
    # .nbt -> SNBT
    python nbt_converter.py nbt2snbt input.nbt -o output.snbt

    # SNBT 字符串 -> .nbt
    python nbt_converter.py snbt2nbt -s '{key:"value",n:1b}' -o output.nbt
    # SNBT 文件 -> .nbt
    python nbt_converter.py snbt2nbt input.snbt -o output.nbt

    # .nbt -> JSON(默认带类型标记,可 round-trip)
    python nbt_converter.py nbt2json input.nbt -o output.json
    # .nbt -> 纯 JSON(仅查看,丢失类型细节)
    python nbt_converter.py nbt2json input.nbt -o output.json --plain

    # JSON -> .nbt
    python nbt_converter.py json2nbt input.json -o output.nbt

    # 强制未压缩的 NBT
    python nbt_converter.py snbt2nbt input.snbt -o output.nbt --no-gz
"""
import argparse
import json
import sys
from pathlib import Path

import nbtlib
from nbtlib import (
    Byte, Short, Int, Long, Float, Double, String,
    List, Compound, ByteArray, IntArray, LongArray,
    parse_nbt,
)


# 数值类型映射:类型标记字符串 -> nbtlib 类
_NUMERIC_TYPES = {
    "byte": Byte,
    "short": Short,
    "int": Int,
    "long": Long,
    "float": Float,
    "double": Double,
}
# 数组类型映射
_ARRAY_TYPES = {
    "byte_array": ByteArray,
    "int_array": IntArray,
    "long_array": LongArray,
}


# ============ NBT -> Python(typed / plain) ============
def nbt_to_typed(obj):
    """递归把 nbtlib tag 转成带 __nbt_type 标记的可 JSON 序列化对象

    该格式支持 round-trip:json2nbt 可据此还原完整 NBT 类型。
    """
    if isinstance(obj, ByteArray):
        return {"__nbt_type": "byte_array", "value": [int(x) for x in obj]}
    if isinstance(obj, IntArray):
        return {"__nbt_type": "int_array", "value": [int(x) for x in obj]}
    if isinstance(obj, LongArray):
        return {"__nbt_type": "long_array", "value": [int(x) for x in obj]}
    if isinstance(obj, List):
        items = [nbt_to_typed(x) for x in obj]
        # 优先用 List 自身的 subtype 属性,否则从首元素推断
        subtype = getattr(obj, "subtype", None)
        if subtype is None and len(obj) > 0:
            subtype = type(obj[0])
        subtype_name = subtype.__name__ if isinstance(subtype, type) else None
        return {"__nbt_type": "list", "subtype": subtype_name, "value": items}
    if isinstance(obj, Compound):
        return {k: nbt_to_typed(v) for k, v in obj.items()}
    if isinstance(obj, (Byte, Short, Int, Long)):
        return {"__nbt_type": type(obj).__name__.lower(), "value": int(obj)}
    if isinstance(obj, (Float, Double)):
        return {"__nbt_type": type(obj).__name__.lower(), "value": float(obj)}
    if isinstance(obj, String):
        return str(obj)
    raise TypeError(f"不支持的 NBT 类型: {type(obj).__name__}")


def nbt_to_plain(obj):
    """递归把 nbtlib tag 转成纯 JSON 对象(丢失 byte/short/int 等类型细节)

    仅用于人工查看,不可保证 round-trip。
    """
    if isinstance(obj, (ByteArray, IntArray, LongArray)):
        return [int(x) for x in obj]
    if isinstance(obj, List):
        return [nbt_to_plain(x) for x in obj]
    if isinstance(obj, Compound):
        return {k: nbt_to_plain(v) for k, v in obj.items()}
    if isinstance(obj, (Byte, Short, Int, Long)):
        return int(obj)
    if isinstance(obj, (Float, Double)):
        return float(obj)
    if isinstance(obj, String):
        return str(obj)
    raise TypeError(f"不支持的 NBT 类型: {type(obj).__name__}")


# ============ Python -> NBT ============
def python_to_nbt(obj):
    """把 Python 对象转成 nbtlib tag

    支持两种输入:
    1. typed JSON(带 __nbt_type 标记)——完整保留 NBT 类型
    2. 纯 JSON——按规则推断:dict->Compound, list->List, int->Int,
       float->Double, bool->Byte, str->String
    """
    if isinstance(obj, dict):
        if "__nbt_type" in obj:
            t = obj["__nbt_type"]
            v = obj["value"]
            if t in _ARRAY_TYPES:
                return _ARRAY_TYPES[t](v)
            if t == "list":
                items = [python_to_nbt(x) for x in v]
                subtype_name = obj.get("subtype")
                cls = _resolve_type(subtype_name)
                if cls is not None:
                    return List[cls](items)
                # subtype 缺失:用首元素类型,或默认 Int
                if items:
                    return List[type(items[0])](items)
                return List[Int]([])
            if t in _NUMERIC_TYPES:
                return _NUMERIC_TYPES[t](v)
            raise ValueError(f"未知的 __nbt_type: {t!r}")
        # 普通 dict -> Compound
        return Compound({k: python_to_nbt(v) for k, v in obj.items()})
    if isinstance(obj, list):
        items = [python_to_nbt(x) for x in obj]
        if not items:
            return List[Int]([])
        # 用第一个元素的类型作为 List 的 subtype
        return List[type(items[0])](items)
    # 注意:bool 必须在 int 之前判断(bool 是 int 的子类)
    if isinstance(obj, bool):
        return Byte(int(obj))
    if isinstance(obj, int):
        return Int(obj)
    if isinstance(obj, float):
        return Double(obj)
    if isinstance(obj, str):
        return String(obj)
    if obj is None:
        # NBT 没有 null,用空字符串代替
        return String("")
    raise TypeError(f"无法转换为 NBT: {type(obj).__name__}")


def _resolve_type(name):
    """根据类型名字符串解析 nbtlib 类,失败返回 None"""
    if not name or not isinstance(name, str):
        return None
    # 优先匹配 nbtlib 顶层类名(如 'Int'、'String')
    cls = getattr(nbtlib, name, None)
    if isinstance(cls, type):
        return cls
    # 兜底:匹配小写数值类型标记
    return _NUMERIC_TYPES.get(name.lower())


# ============ NBT 文件读写 ============
def load_nbt_file(path, gz=None):
    """加载 .nbt 文件,返回 (File 对象, 根 tag 名字)

    gz=None 时自动检测压缩格式(推荐)。
    """
    f = nbtlib.load(path, gzipped=gz)
    return f, getattr(f, "root_name", "")


def save_nbt_file(path, root_tag, root_name="", gz=True):
    """把 root_tag(Compound)保存为 .nbt 文件"""
    if not isinstance(root_tag, Compound):
        raise TypeError(f"根 tag 必须是 Compound,实际是 {type(root_tag).__name__}")
    f = nbtlib.File(root_tag, root_name=root_name, gzipped=gz)
    f.save(path, gzipped=gz)


# ============ 工具函数 ============
def _write_text(output, text):
    """输出文本到文件或 stdout"""
    if output:
        Path(output).write_text(text, encoding="utf-8")
        print(f"[完成] 已写入 {output}", file=sys.stderr)
    else:
        print(text)


def _default_output(input_path, ext):
    """根据输入文件名推导默认输出文件名"""
    return str(Path(input_path).with_suffix(ext))


# ============ 子命令实现 ============
def cmd_nbt2snbt(args):
    root, root_name = load_nbt_file(args.input, gz=None if not args.no_gz else False)
    if root_name:
        print(f"[提示] 根 tag 名字: {root_name!r}", file=sys.stderr)
    snbt = root.snbt()
    _write_text(args.output, snbt)


def cmd_snbt2nbt(args):
    # 输入来源:命令行字符串 或 文件
    if args.string is not None:
        snbt = args.string
    else:
        if not args.input:
            print("错误: snbt2nbt 需要提供 -s/--string 或输入文件", file=sys.stderr)
            sys.exit(1)
        snbt = Path(args.input).read_text(encoding="utf-8")
    tag = parse_nbt(snbt)
    if not isinstance(tag, Compound):
        print("错误: SNBT 顶层必须是 Compound {...}", file=sys.stderr)
        sys.exit(1)
    out = args.output
    if not out:
        out = "output.nbt" if args.string is not None else _default_output(args.input, ".nbt")
    save_nbt_file(out, tag, root_name=args.root_name, gz=not args.no_gz)
    print(f"[完成] 已写入 {out}", file=sys.stderr)


def cmd_nbt2json(args):
    root, root_name = load_nbt_file(args.input, gz=None if not args.no_gz else False)
    if root_name:
        print(
            f"[提示] 根 tag 名字: {root_name!r}(JSON 未保留,转回 nbt 时用 --root-name 指定)",
            file=sys.stderr,
        )
    data = nbt_to_plain(root) if args.plain else nbt_to_typed(root)
    text = json.dumps(data, ensure_ascii=False, indent=2)
    _write_text(args.output, text)


def cmd_json2nbt(args):
    data = json.loads(Path(args.input).read_text(encoding="utf-8"))
    tag = python_to_nbt(data)
    if not isinstance(tag, Compound):
        print("错误: JSON 顶层必须是对象 {...}", file=sys.stderr)
        sys.exit(1)
    out = args.output or _default_output(args.input, ".nbt")
    save_nbt_file(out, tag, root_name=args.root_name, gz=not args.no_gz)
    print(f"[完成] 已写入 {out}", file=sys.stderr)


# ============ CLI ============
def build_parser():
    parser = argparse.ArgumentParser(
        description="Minecraft NBT 文件与 SNBT / JSON 互相转化工具",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例:\n"
               "  nbt2snbt input.nbt -o out.snbt\n"
               "  snbt2nbt -s '{a:1b}' -o out.nbt\n"
               "  nbt2json input.nbt -o out.json --plain\n"
               "  json2nbt input.json -o out.nbt",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # nbt2snbt
    p = sub.add_parser("nbt2snbt", help=".nbt 文件 -> SNBT 字符串")
    p.add_argument("input", help="输入 .nbt 文件路径")
    p.add_argument("-o", "--output", help="输出文件路径(省略则打印到 stdout)")
    p.add_argument("--no-gz", action="store_true", help="输入文件未压缩")
    p.set_defaults(func=cmd_nbt2snbt)

    # snbt2nbt
    p = sub.add_parser("snbt2nbt", help="SNBT 字符串/文件 -> .nbt 文件")
    p.add_argument("input", nargs="?", help="输入 SNBT 文件路径(与 -s 二选一)")
    p.add_argument("-s", "--string", help="直接传入 SNBT 字符串")
    p.add_argument("-o", "--output", help="输出 .nbt 文件路径")
    p.add_argument("--root-name", default="", help="根 tag 名字(默认空字符串)")
    p.add_argument("--no-gz", action="store_true", help="输出未压缩的 NBT")
    p.set_defaults(func=cmd_snbt2nbt)

    # nbt2json
    p = sub.add_parser("nbt2json", help=".nbt 文件 -> JSON")
    p.add_argument("input", help="输入 .nbt 文件路径")
    p.add_argument("-o", "--output", help="输出文件路径(省略则打印到 stdout)")
    p.add_argument("--plain", action="store_true",
                   help="输出纯 JSON(丢失 byte/short 等类型细节,不可 round-trip)")
    p.add_argument("--no-gz", action="store_true", help="输入文件未压缩")
    p.set_defaults(func=cmd_nbt2json)

    # json2nbt
    p = sub.add_parser("json2nbt", help="JSON -> .nbt 文件")
    p.add_argument("input", help="输入 JSON 文件路径")
    p.add_argument("-o", "--output", help="输出 .nbt 文件路径")
    p.add_argument("--root-name", default="", help="根 tag 名字(默认空字符串)")
    p.add_argument("--no-gz", action="store_true", help="输出未压缩的 NBT")
    p.set_defaults(func=cmd_json2nbt)

    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()

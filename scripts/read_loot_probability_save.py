#!/usr/bin/env python3
"""读取 `unsuspiciousblock_loot_probability.dat`（战利品概率缓存 SavedData）并打印可诊断的摘要。

存在的理由：概率缓存"命中/未命中"的判定跨了三个因素——表内容哈希、输入键、存档格式版本——
只看日志里的"N 个待模拟"无法区分"内容确实变了"与"缓存键不稳定"。这个脚本直接读存档，
把每个表存的哈希、输入键与测量值条数摊开，是定位这类问题最快的一手证据。

用法（在仓库根目录，或任意位置传路径）：

    python scripts/read_loot_probability_save.py
        # 自动取 neoforge/run/saves/*/data/ 下最新的那份
    python scripts/read_loot_probability_save.py <路径.dat>
    python scripts/read_loot_probability_save.py --table minecraft:gameplay/fishing
        # 额外 dump 指定表的输入键与逐签名测量值

看点：
  * `format_version` 与当前代码里的 FORMAT_VERSION（LootProbabilityData）不一致 → 整份按未命中处理。
  * 某个表存了**多个输入键** → 它的输入键不稳定（每次运行都新增一条）。历史上这来自
    "场景键里嵌了条件指纹"，而 `entity_properties` / `location_check` 这类条件的指纹跨 JVM 运行不重复；
    v19 起场景键改为稳定身份（`scenario=baseline` / `scenario=scene-N`），这个数字应当归零。
  * 输入键里的 `luck=` 是**模拟输入的幸运**，与存档里的数值应当自洽（基准输入为 0.00）。
"""

import argparse
import gzip
import glob
import os
import struct
import sys
import zlib


class NbtReader:
    """极简 NBT 读取器——只支持本存档用到的 tag，不追求完备。"""

    def __init__(self, data: bytes):
        self.b = data
        self.i = 0

    def u1(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def i1(self):
        v = self.b[self.i]
        self.i += 1
        return v - 256 if v > 127 else v

    def i2(self):
        v = int.from_bytes(self.b[self.i:self.i + 2], "big", signed=True)
        self.i += 2
        return v

    def i4(self):
        v = int.from_bytes(self.b[self.i:self.i + 4], "big", signed=True)
        self.i += 4
        return v

    def i8(self):
        v = int.from_bytes(self.b[self.i:self.i + 8], "big", signed=True)
        self.i += 8
        return v

    def f4(self):
        v = struct.unpack(">f", self.b[self.i:self.i + 4])[0]
        self.i += 4
        return v

    def f8(self):
        v = struct.unpack(">d", self.b[self.i:self.i + 8])[0]
        self.i += 8
        return v

    def s(self):
        n = int.from_bytes(self.b[self.i:self.i + 2], "big")
        self.i += 2
        v = self.b[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return v

    def payload(self, tag):
        if tag == 1:
            return self.i1()
        if tag == 2:
            return self.i2()
        if tag == 3:
            return self.i4()
        if tag == 4:
            return self.i8()
        if tag == 5:
            return self.f4()
        if tag == 6:
            return self.f8()
        if tag == 7:
            n = self.i4()
            v = self.b[self.i:self.i + n]
            self.i += n
            return v
        if tag == 8:
            return self.s()
        if tag == 9:
            element = self.u1()
            n = self.i4()
            return [self.payload(element) for _ in range(n)]
        if tag == 10:
            out = {}
            while True:
                child = self.u1()
                if child == 0:
                    break
                # 必须先读名字再递归：写成 `out[self.s()] = self.payload(child)` 时 Python 会**先求右侧**，
                # 于是还没读名字就按错的偏移解析子节点，整棵树的解析从这里开始错位
                # （症状是几 KB 之后才冒出一个"不支持的 NBT tag"）。
                name = self.s()
                out[name] = self.payload(child)
            return out
        if tag == 11:
            n = self.i4()
            return [self.i4() for _ in range(n)]
        if tag == 12:
            n = self.i4()
            return [self.i8() for _ in range(n)]
        raise ValueError("不支持的 NBT tag: %d (offset %d)" % (tag, self.i))


def decompress(raw: bytes) -> bytes:
    """解压存档；两种格式都不成时明确失败，**不要**退回原始字节。

    退回原始字节会把"文件正被游戏写入（写到一半）"变成一段看不懂的解析错位报错——
    那样的错误信息会让人去翻 NBT 结构，而真正的原因只是读得太早。
    """
    for decoder in (gzip.decompress, zlib.decompress):
        try:
            return decoder(raw)
        except Exception:
            continue
    raise ValueError(
        "既不是 gzip 也不是 zlib 压缩流（%d 字节）。多半是游戏正在写入这份存档，"
        "稍后重试；若持续如此则文件已损坏。" % len(raw))


def load(path: str):
    """返回 (format_version, {tableId: entry})；解析失败时抛出。"""
    reader = NbtReader(decompress(open(path, "rb").read()))
    root_tag = reader.u1()
    reader.s()
    root = reader.payload(root_tag)
    inner = root.get("data", root)
    entries = {}
    for entry in inner.get("entries", []):
        inputs = {}
        for item in entry.get("inputs", []):
            values = {}
            for value in item.get("items", []):
                payload = value.get("value", {})
                values[value.get("key")] = (payload.get("state"), payload.get("lower"))
            inputs[item.get("input_key")] = {
                "sample_count": item.get("sample_count"),
                "items": values,
                "children": [c.get("table_id") for c in item.get("children", [])],
            }
        entries[entry.get("table_id")] = {
            "hash": entry.get("hash"),
            "inputs": inputs,
            "discovery": len(entry.get("discovery", {}).get("items", [])),
        }
    return inner.get("format_version"), entries


def newest_save() -> str | None:
    candidates = glob.glob("neoforge/run/saves/*/data/unsuspiciousblock_loot_probability.dat")
    if not candidates:
        candidates = glob.glob("**/unsuspiciousblock_loot_probability.dat", recursive=True)
    return max(candidates, key=os.path.getmtime) if candidates else None


def main() -> int:
    parser = argparse.ArgumentParser(description="诊断战利品概率缓存存档")
    parser.add_argument("path", nargs="?", help="存档路径；省略则自动找最新的一份")
    parser.add_argument("--table", help="额外 dump 这张表的输入与测量值")
    parser.add_argument("--show-items", action="store_true", help="配合 --table：列出逐签名测量值")
    args = parser.parse_args()

    path = args.path or newest_save()
    if not path or not os.path.exists(path):
        print("找不到概率缓存存档；请显式传入路径", file=sys.stderr)
        return 1

    version, entries = load(path)
    print("存档: %s" % path)
    print("format_version = %s（当前代码为 4；不一致时整份按缓存未命中处理）" % version)
    print("表数 = %d" % len(entries))

    multi = {t: d for t, d in entries.items() if len(d["inputs"]) > 1}
    print("存有多个输入键的表 = %d（>1 表示该表的输入键跨运行不稳定，每次都会新增一条）" % len(multi))
    for table, data in sorted(multi.items()):
        print("  - %s：%d 个输入，discovery %d 项" % (table, len(data["inputs"]), data["discovery"]))
        for key in data["inputs"]:
            print("      %s" % key)

    # 场景键是稳定身份：基准恒为 scenario=baseline，其余为 scenario=scene-N。
    # 这个计数是"键形如基准、即每个表的输入键都指向它自己的基准场景"的体检项。
    baseline_like = [t for t, d in entries.items()
                     if any(k.startswith("scenario=baseline|") for k in d["inputs"])]
    print("含 scenario=baseline 输入键的表 = %d（应等于可模拟表数；显著偏少说明键形态或版本不对）"
          % len(baseline_like))

    if args.table:
        data = entries.get(args.table)
        if not data:
            print("存档里没有这张表: %s" % args.table, file=sys.stderr)
            return 1
        print("\n=== %s ===" % args.table)
        print("hash = %s" % data["hash"])
        for key, value in data["inputs"].items():
            print("input: %s" % key)
            print("  抽样次数=%s，测量条目 %d，子表 %s"
                  % (value["sample_count"], len(value["items"]), value["children"]))
            if args.show_items:
                for signature, (state, lower) in sorted(value["items"].items()):
                    print("    %-70s %s %s" % (signature[:70], state, lower))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

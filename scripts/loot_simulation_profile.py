"""解析 journal reload 热运行日志，保留逐表观测数据并生成 CPU 对照表。"""

import argparse
import csv
import re
import statistics
from pathlib import Path


START = re.compile(r"已开始在后台重新模拟 (\d+) 个考古战利品表")
DONE = re.compile(
    r"已完成战利品表 (\S+) 的概率模拟，有效计算 (\d+)ms"
    r"（线程 CPU (\d+)ms），跨 tick 历时 (\d+)ms，剩余队列 (\d+)(.*)"
)
DETAIL = re.compile(r"\b([A-Z_]+(?:_ns)?)=(\d+)")
BASE_FIELDS = ["table", "active_ms", "cpu_ms", "elapsed_ms", "remaining", "profile", "line"]
PARENTS = (
    "PREPARE", "BEGIN_ROLL", "GENERATE", "INJECT", "MATCH", "RAW_MATCH", "DERIVE",
    "KEY_LOOKUP", "RECORD", "CHILD_RECORD", "FINISH", "RESULT",
)


# 只接受命令触发且完整结束的轮次，拒绝混入启动期、截断轮次或重复表。
def read_round(log_path, round_number):
    rounds = []
    current = None
    for number, line in enumerate(log_path.read_text(encoding="utf-8-sig").splitlines(), 1):
        start = START.search(line)
        if start:
            current = {"expected": int(start[1]), "rows": [], "evidence": [line]}
            rounds.append(current)
            continue
        match = DONE.search(line)
        if current is None or not match:
            continue
        table, active, cpu, elapsed, remaining, suffix = match.groups()
        row = dict(zip(BASE_FIELDS, [table, int(active), int(cpu), int(elapsed),
                                    int(remaining), "v1" if "profile=v1" in suffix else "off", number]))
        row.update({key: int(value) for key, value in DETAIL.findall(suffix)})
        if row["profile"] == "v1":
            if row.get("DROPS") != sum(row.get(key, 0) for key in ("MATCHED", "RAW_SKIPPED", "DERIVED")):
                raise ValueError(f"第 {number} 行掉落分支计数不守恒")
            parent_ms = sum(row.get(stage + "_ns", 0) for stage in PARENTS) / 1_000_000
            row["unattributed_ms"] = round(row["active_ms"] - parent_ms, 3)
        current["rows"].append(row)
        current["evidence"].append(line)
        if int(remaining) == 0:
            current = None
    if not rounds:
        raise ValueError("没有找到 journal reload 热运行标记；不使用启动期数字替代")
    index = len(rounds) - 1 if round_number == "last" else int(round_number) - 1
    if not 0 <= index < len(rounds):
        raise ValueError(f"轮次超出范围：日志中共有 {len(rounds)} 轮")
    chosen = rounds[index]
    rows = chosen["rows"]
    if (len(rows) != chosen["expected"] or not rows or rows[-1]["remaining"] != 0
            or len({row["table"] for row in rows}) != len(rows)):
        raise ValueError(f"第 {index + 1} 轮不完整或有重复表，拒绝生成对照")
    return rows, chosen["evidence"], index + 1


# CSV 保留原始纳秒与次数，Markdown 只显示便于阅读的毫秒。
def write_csv(path, rows):
    fields = BASE_FIELDS + sorted(set().union(*(row.keys() for row in rows)) - set(BASE_FIELDS))
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


# CPU 低分辨率下的 0ms 不表示零成本，不为它计算加速比例。
def render(rows, baseline, round_number):
    cpu = [row["cpu_ms"] for row in rows]
    total = sum(cpu)
    top_three = sum(sorted(cpu, reverse=True)[:3])
    share = f"{top_three / total:.1%}" if total else "不可计算"
    lines = [f"热运行第 {round_number} 轮：{len(rows)} 张表，CPU 合计 {total}ms，"
             f"中位数 {statistics.median(cpu):g}ms，最慢三张占 {share}。", "",
             "分段为墙钟时间；DETAIL 是嵌套明细，不能再次相加。CPU 0ms 不代表没有成本。",
             "profile=off 与 v1 的差异包含观测开销，不可直接当作修复收益。", ""]
    if baseline:
        if set(baseline) != {row["table"] for row in rows}:
            raise ValueError("两轮的表集合不同，拒绝生成逐表性能对照")
        lines += ["| 表 | 基线 CPU ms | 本轮 CPU ms | CPU 差值 ms | 基线/本轮观测 |",
                  "|---|---:|---:|---:|---|"]
        for row in sorted(rows, key=lambda entry: int(baseline[entry["table"]]["cpu_ms"]), reverse=True):
            before = baseline[row["table"]]
            lines.append(f"| {row['table']} | {before['cpu_ms']} | {row['cpu_ms']} | "
                         f"{row['cpu_ms'] - int(before['cpu_ms']):+d} | {before['profile']}/{row['profile']} |")
    else:
        lines += ["| 表 | CPU ms | 有效计算 ms |", "|---|---:|---:|"]
        lines += [f"| {row['table']} | {row['cpu_ms']} | {row['active_ms']} |"
                  for row in sorted(rows, key=lambda entry: entry["cpu_ms"], reverse=True)]
    measured = [row for row in rows if row["profile"] == "v1"]
    if measured:
        lines += ["", "| 表 | 场景/抽取 | 掉落栈 | 扫描 | 序列化 | 存储键调用 | 抽取 ms | 匹配 ms | "
                  "派生 ms | 序列化明细 ms | 预览明细 ms | 未分配 ms |",
                  "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for row in sorted(measured, key=lambda entry: entry["cpu_ms"], reverse=True):
            values = [row['table'], f"{row['SCENARIOS']}/{row['ROLLS']}", row['DROPS'], row['SCANS'],
                      row['EXACT_SERIALIZATIONS'], row['STORED_KEY_CALLS']]
            values += [f"{row.get(stage + '_ns', 0) / 1_000_000:.3f}"
                       for stage in ("GENERATE", "MATCH", "DERIVE", "SERIALIZE_DETAIL", "PREVIEW_DETAIL")]
            values.append(row['unattributed_ms'])
            lines.append("| " + " | ".join(map(str, values)) + " |")
    return "\n".join(lines) + "\n"


# 输出路径均由调用者指定，不覆盖游戏日志。
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, default=Path("neoforge/run/logs/latest.log"))
    parser.add_argument("--round", default="last", help="命令触发的热运行轮次，从 1 开始，默认 last")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, help="可选：保存该轮原始日志行")
    args = parser.parse_args()
    rows, evidence, round_number = read_round(args.log, args.round)
    baseline = None
    if args.baseline:
        with args.baseline.open(encoding="utf-8-sig", newline="") as source:
            baseline = {row["table"]: row for row in csv.DictReader(source)}
    report = render(rows, baseline, round_number)
    write_csv(args.csv, rows)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report, encoding="utf-8")
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text("\n".join(evidence) + "\n", encoding="utf-8")
    print(report.splitlines()[0])
    print(f"CSV: {args.csv}\n报告: {args.report}")


if __name__ == "__main__":
    main()

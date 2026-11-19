"""定向采集 NeoForge 性能基准并比较同口径原始样本。"""
import argparse
import csv
from datetime import datetime
import json
import math
from pathlib import Path
import platform
import statistics
import sys
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from toolslib.cli import configure_console
from toolslib.repo import repo_root
from probelib.autojs import ProbeError
from probelib.benchmarks import BENCHMARKS, collect
from probelib.runtime import Runtime, gradle, read_config, write_json


SCHEMA = 1
WORKLOAD_VERSION = 1
TAIL_MINIMUM = 20


def distribution(values):
    median = statistics.median(values)
    result = dict(median=median, mad=statistics.median(abs(value - median) for value in values),
                  min=min(values), max=max(values), count=len(values))
    if len(values) >= TAIL_MINIMUM:
        result['p95'] = sorted(values)[math.ceil(.95 * len(values)) - 1]
    return result


def metric_values(record):
    rounds = record['comparable']['sampling']['rounds']
    samples = record['samples']
    if record['schema'] != SCHEMA or record['status'] != 'sampled' or not samples or len(samples) != rounds:
        raise ProbeError('基准记录不完整或版本不匹配')
    if not isinstance(samples, list) or any(not isinstance(sample, dict) or not isinstance(sample.get('metrics'), dict)
                                            for sample in samples):
        raise ProbeError('基准采样需包含结构化指标')
    names = samples[0]['metrics'].keys()
    if record['comparable']['primary'] not in names or any(sample['metrics'].keys() != names for sample in samples):
        raise ProbeError('各轮采样的指标或单位不一致')
    if any(sample.get('workload') != samples[0].get('workload') for sample in samples):
        raise ProbeError('采样轮次的负载身份发生变化')
    metrics = {name: [sample['metrics'][name] for sample in samples] for name in names}
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0
           for values in metrics.values() for value in values):
        raise ProbeError('基准指标缺失或无效')
    return metrics


def compare(before, after, threshold):
    baseline, candidate = metric_values(before), metric_values(after)
    if before['comparable'] != after['comparable'] or baseline.keys() != candidate.keys():
        raise ProbeError('负载、执行环境、采样、观测模式或指标单位不同')
    if [sample.get('workload') for sample in before['samples']] != [sample.get('workload') for sample in after['samples']]:
        raise ProbeError('逐轮负载身份不同')
    metrics = {}
    for name, values in baseline.items():
        first, second = distribution(values), distribution(candidate[name])
        delta = second['median'] - first['median']
        metrics[name] = dict(baseline=first, candidate=second, delta=delta,
                             percent=100 * delta / first['median'] if first['median'] else None)
    primary = after['comparable']['primary']
    result = dict(status='compared', primary=primary, metrics=metrics)
    if threshold:
        absolute, percent, minimum = threshold
        selected = metrics[primary]
        if selected['baseline']['count'] < minimum or selected['percent'] is None:
            raise ProbeError('主指标样本不足或基线精度不足，无法判定阈值')
        result.update(threshold=dict(absolute=absolute, percent=percent, minSamples=minimum),
                      status='regression' if selected['delta'] > absolute and selected['percent'] > percent else 'pass')
    return result


def write_comparison(result, args):
    if args.output:
        write_json(args.output, result)
    if 'metrics' not in result:
        return
    table = [(name, row['baseline']['median'], row['candidate']['median'], row['delta'], row['percent'])
             for name, row in result['metrics'].items()]
    headings = ('指标', '基线中位数', '候选中位数', '差值', '变化百分比')
    if args.csv:
        args.csv.parent.mkdir(parents=True, exist_ok=True)
        with args.csv.open('w', encoding='utf-8', newline='') as output:
            writer = csv.writer(output)
            writer.writerow(headings)
            writer.writerows(table)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        rows = [headings, ('---',) * len(headings), *table]
        body = '\n'.join('| ' + ' | '.join('—' if value is None else str(value) for value in row) + ' |' for row in rows)
        args.report.write_text(f'# 性能基准对照\n\n`{result["status"]}` · `{result["primary"]}`\n\n{body}\n',
                               encoding='utf-8', newline='\n')


def run(args):
    spec = BENCHMARKS[args.only]
    root = repo_root()
    directory = (args.run_dir or root / 'neoforge/build/probe-runs' /
                 ('bench-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))).resolve()
    config = read_config(args.config or root / '.local/probes.json', spec.needs.sides)
    if args.background:
        config['background'] = True
    directory.mkdir(parents=True)
    runtime = Runtime(root, directory, config, spec.needs, loot_profile=args.profile)
    record = dict(schema=SCHEMA, status='error', cleanupErrors=[], samples=[], warmupSamples=[],
                  comparable=dict(benchmark=args.only, primary=spec.primary, inputs=spec.inputs, cache=spec.cache,
                                  workloadVersion=WORKLOAD_VERSION, profile=args.profile,
                                  hostMachine=platform.node(), os=platform.platform(),
                                  processor=platform.processor(), worldSeed=1,
                                  sampling=dict(warmup=args.warmup, rounds=args.rounds)))
    try:
        artifact = args.artifact
        if artifact is None:
            gradle(root, ['build', 'writeProbeArtifact'], directory / 'build.log')
            artifact = root / 'build/probes/artifact.json'
        runtime.prepare(artifact)
        if spec.stage:
            spec.stage(runtime)
        manifest = runtime.manifest
        record['artifact'] = dict(host=manifest['dependencies'][0], nested=manifest['nested'])
        record['comparable'].update(fixtures=manifest['fixtures'], minecraft=manifest['launch']['minecraft'],
                                    neoforge=manifest['launch']['neoforge'],
                                    dependencies=[entry['sha256'] for entry in manifest['dependencies'][1:]])
        runtime.start('server')
        collect(runtime, spec, args, record)
        record['status'] = 'sampled'
        metric_values(record)
    except (ProbeError, AssertionError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        record.update(status='error', error=str(error))
    finally:
        record['cleanupErrors'].extend(runtime.close())
        if record['cleanupErrors']:
            record['status'] = 'error'
        write_json(directory / 'benchmark.json', record)
    print(directory / 'benchmark.json')
    if record['status'] == 'error':
        print(record.get('error', record['cleanupErrors']), file=sys.stderr)
        return 2
    return 0


def main():
    configure_console()
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('list', help='列出可单独采集的负载')
    launch = commands.add_parser('run', help='用真实发布物采集指定负载')
    launch.add_argument('--only', choices=BENCHMARKS, required=True)
    launch.add_argument('--artifact', type=Path)
    launch.add_argument('--config', type=Path)
    launch.add_argument('--run-dir', type=Path)
    launch.add_argument('--background', action='store_true')
    launch.add_argument('--profile', choices=('true', 'false'), default='false')
    launch.add_argument('--warmup', type=int, default=2)
    launch.add_argument('--rounds', type=int, default=5)
    comparison = commands.add_parser('compare', help='离线比较原始 JSON')
    comparison.add_argument('baseline', type=Path)
    comparison.add_argument('candidate', type=Path)
    comparison.add_argument('--absolute', type=float, help='主指标绝对退化阈值，使用该指标的单位')
    comparison.add_argument('--percent', type=float, help='主指标相对退化阈值')
    comparison.add_argument('--min-samples', type=int, help='启用退化判定所需的最小样本数')
    comparison.add_argument('--output', type=Path, help='保存机器可读对照')
    comparison.add_argument('--report', type=Path, help='保存 Markdown 中位数对照')
    comparison.add_argument('--csv', type=Path, help='保存 CSV 中位数对照')
    args = parser.parse_args()
    if args.command == 'list':
        for name, spec in BENCHMARKS.items():
            print(f'{name}\t{spec.primary}\t{spec.purpose}')
        return 0
    if args.command == 'run':
        if args.warmup < 0 or args.rounds < 1:
            parser.error('预热轮次需为非负整数，采样至少一轮')
        try:
            return run(args)
        except (ProbeError, OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            print(error, file=sys.stderr)
            return 2
    threshold = (args.absolute, args.percent, args.min_samples)
    if any(value is not None for value in threshold):
        if (any(value is None for value in threshold) or
                any(not math.isfinite(value) or value < 0 for value in threshold[:2]) or args.min_samples < 2):
            parser.error('退化判定需要同时给出非负绝对阈值、百分比与至少两个样本')
    else:
        threshold = None
    raw = {args.baseline.resolve(), args.candidate.resolve()}
    reports = [path.resolve() for path in (args.output, args.report, args.csv) if path]
    if raw.intersection(reports) or len(set(reports)) != len(reports):
        parser.error('原始记录和各派生报告须使用不同路径')
    try:
        records = [json.loads(path.read_text(encoding='utf-8-sig')) for path in (args.baseline, args.candidate)]
        result = compare(*records, threshold)
        result['baseline'] = dict(record=str(args.baseline), artifact=records[0].get('artifact'))
        result['candidate'] = dict(record=str(args.candidate), artifact=records[1].get('artifact'))
    except (ProbeError, OSError, ValueError, KeyError, TypeError) as error:
        result = dict(status='incomparable', error=str(error))
    try:
        write_comparison(result, args)
    except OSError as error:
        print(error, file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return {'compared': 0, 'pass': 0, 'regression': 1, 'incomparable': 2}[result['status']]


if __name__ == '__main__':
    raise SystemExit(main())

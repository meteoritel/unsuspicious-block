"""真实 Java 负载与客户端里程碑的基准采集。"""
from collections.abc import Callable
from dataclasses import dataclass
import json
import re
import time

from diagnostics.loot_simulation_profile import START, read_round

from .autojs import ProbeError
from .cases import Case, wait_for, write_graph_fixtures
from .journal_cases import CATALOG_FIXTURE, SCAN_TABLE, prepare_table_selection, scan_and_take
from .runtime import LaunchNeeds, WITH_CLIENT, digest
from .selection import CATALOG_FIXTURES, PACK, SCAN


POLL_SECONDS = .15
ROUND_TIMEOUT_SECONDS = 180
GRAPH_ROOT = 'usb_probe:architecture_graph/12_a'
LEDGER_ENTRIES = 256
GC_START = re.compile(r'^\[.*?\]\[info\s*\]\[gc\s*\] Using (.+)$', re.MULTILINE)


def install(case, side, *filenames):
    directory = case.runtime.root / 'tools/probes/scripts'
    control = case.server if side == 'server' else case.client
    control.install('\n'.join((directory / name).read_text(encoding='utf-8') for name in filenames))


def stage_projection(runtime):
    pack = runtime.directory / 'server/world/datapacks/usb_probe'
    write_graph_fixtures(pack / 'data/usb_probe')
    runtime.manifest['fixtures'].update({
        path.relative_to(pack).as_posix(): digest(path)
        for path in (pack / 'data/usb_probe/loot_table/architecture_graph').glob('*.json')
    })


def projection(case):
    install(case, 'server', 'core_architecture.js')
    case.server.evaluate(f'(Probe.projection=Probe.graphProjection({json.dumps(GRAPH_ROOT)}), true)')

    def sample():
        result = case.server.evaluate('(function(){var sample=Probe.clockSample(Probe.projection);'
                                      'return {ns:sample.ns,facts:Probe.graphFacts(sample.result)};})()')
        expected = {'items': ['minecraft:stone'], 'paths': 2}
        if result['facts'] != expected:
            raise ProbeError(f'投影负载产物不符: {result["facts"]}')
        return dict(metrics={'projection_ns': result['ns']}, observed=result['facts'])

    return sample


def catalog_query(case):
    return lambda: case.server.evaluate(f'Probe.catalogProgress({json.dumps(SCAN_TABLE)})')


def catalog_codec(case):
    install(case, 'server', 'catalog_loading.js')
    wait_for(catalog_query(case), lambda value: not value['busy'], ROUND_TIMEOUT_SECONDS)

    def sample():
        result = case.server.evaluate('Probe.codecSample()')
        if SCAN_TABLE not in result['members'] or result['bytes'] <= 0:
            raise ProbeError('目录编码负载缺少固定表或数据')
        return dict(metrics={'codec_ns': result['ns'], 'packet_bytes': result['bytes']},
                    workload=dict(members=result['members'], hash=result['hash']))

    return sample


def ledger(case):
    def sample():
        result = case.action('server', f'Probe.ledgerSample({LEDGER_ENTRIES})')
        if result['retained'] != LEDGER_ENTRIES:
            raise ProbeError(f'账本负载保留条目异常: {result["retained"]}')
        return dict(metrics={'maintenance_ns': result['ns']}, observed=dict(retained=result['retained']))

    return sample


def rebuild(case):
    case.action('server', 'Probe.command("usb journal catalog rebuild")')


def journal_simulation(case):
    install(case, 'server', 'catalog_loading.js')
    query = catalog_query(case)
    log = case.runtime.directory / 'server/logs/latest.log'

    def sample():
        before = wait_for(query, lambda state: not state['busy'], ROUND_TIMEOUT_SECONDS)
        ordinal = sum(bool(START.search(line)) for line in log.read_text(encoding='utf-8-sig').splitlines()) + 1
        started = time.perf_counter_ns()
        rebuild(case)
        completed = wait_for(query, lambda state: state['generation'] > before['generation'] and not state['busy'],
                             ROUND_TIMEOUT_SECONDS)
        elapsed = time.perf_counter_ns() - started
        rows, evidence, _ = read_round(log, str(ordinal))
        tables = sorted(row['table'] for row in rows)
        if not completed['measured'] or SCAN_TABLE not in tables:
            raise ProbeError('完整轮次缺少固定表的基准结果')
        projection = case.server.evaluate('Probe.simulationWorkload()')
        workload = {table: projection[table] for table in tables}
        if any(samples <= 0 for _, samples in workload.values()):
            raise ProbeError('模拟轮次的已发布样本不完整')
        metrics = dict(round_observed_ns=elapsed, total_active_ms=sum(row['active_ms'] for row in rows),
                       max_table_elapsed_ms=max(row['elapsed_ms'] for row in rows))
        cpu_ms = sum(row['cpu_ms'] for row in rows)
        if cpu_ms > 0:
            metrics['total_cpu_ms'] = cpu_ms
        return dict(metrics=metrics, workload=workload,
                    observed=dict(logRound=ordinal, rows=rows, evidence=evidence, cpuTimeAvailable=cpu_ms > 0))

    return sample


def milestone(started, query, accept):
    lower = 0
    deadline = time.monotonic() + ROUND_TIMEOUT_SECONDS
    while True:
        queried = time.perf_counter_ns() - started
        value = query()
        observed = time.perf_counter_ns() - started
        if accept(value):
            return value, [lower, observed]
        if time.monotonic() >= deadline:
            raise ProbeError(f'未观察到客户端里程碑，最后状态: {value}')
        lower = max(0, queried)
        time.sleep(POLL_SECONDS)


def journal_visible(case):
    scan_and_take(case)
    install(case, 'server', 'catalog_loading.js')
    install(case, 'client', 'catalog_loading.js')
    query = catalog_query(case)
    client = lambda: case.client.evaluate(f'Probe.catalogView({json.dumps(SCAN_TABLE)})')
    click = (f'(function(){{var view=Probe.catalogView({json.dumps(SCAN_TABLE)});'
             f'Probe.click({json.dumps("table/" + SCAN_TABLE)}); return view.measured > 0;}})()')

    def sample():
        prepare_table_selection(case)
        before = wait_for(query, lambda state: not state['busy'], ROUND_TIMEOUT_SECONDS)
        started = time.perf_counter_ns()
        rebuild(case)
        same = lambda value: value and value['generation'] > before['generation'] and value['ready']
        cold, directory = milestone(started, client, same)
        generation = cold['generation']
        selection = time.perf_counter_ns()
        measured_before_selection = case.action('client', click)
        ready, table = milestone(selection, client, lambda value: same(value) and value['measured'] > 0)
        shared, sync = milestone(started, client, lambda value: same(value) and value['sharedSamples'] > 0)
        if cold['fixtures'] != CATALOG_FIXTURE or ready['generation'] != generation or shared['generation'] != generation:
            raise ProbeError('目录可见性采样的夹具或目录代次异常')
        return dict(metrics=dict(directory_observed_ns=directory[1], selected_table_observed_ns=table[1],
                                 shared_observed_ns=sync[1]),
                    workload=cold['fixtures'],
                    observed=dict(directory=directory, selectedTable=table, shared=sync, generation=generation,
                                  measuredBeforeSelection=measured_before_selection))

    return sample


@dataclass(frozen=True)
class Benchmark:
    prepare: Callable[[Case], Callable[[], dict]]
    needs: LaunchNeeds
    purpose: str
    cache: str
    primary: str
    inputs: dict
    stage: Callable | None = None


BENCHMARKS = {
    'loot-projection': Benchmark(projection, LaunchNeeds(fixtures=(PACK,)), '已编译引用图的根表投影',
                                 'fresh-projector', 'projection_ns', {'root': GRAPH_ROOT}, stage_projection),
    'catalog-codec': Benchmark(catalog_codec, LaunchNeeds(fixtures=(SCAN,)), '稳定目录负载的 Codec 编码',
                               'settled-catalog', 'codec_ns', {}),
    'shimmer-ledger': Benchmark(ledger, LaunchNeeds(), '写入未到期条目后的新 tick 维护',
                                'initialized-ledger', 'maintenance_ns', {'entries': LEDGER_ENTRIES, 'due': 0}),
    'journal-simulation': Benchmark(journal_simulation, LaunchNeeds(fixtures=(SCAN,)), '命令重建后的完整模拟队列',
                                    'warm-jvm-cold-cache', 'round_observed_ns', {'pollSeconds': POLL_SECONDS}),
    'journal-visible': Benchmark(journal_visible, LaunchNeeds(WITH_CLIENT, CATALOG_FIXTURES), '目录与单表的客户端可见性',
                                 'warm-jvm-cold-cache', 'directory_observed_ns',
                                 {'pollSeconds': POLL_SECONDS, 'table': SCAN_TABLE, 'selection': 'search before rebuild'}),
}


def collect(runtime, spec, options, record):
    case = Case(runtime, dict(name=options.only, assertions=[], screenshots=[]))
    try:
        install(case, 'server', 'benchmark.js')
        metadata = record['comparable']
        metadata['jvm'] = case.server.evaluate('Probe.benchmarkEnvironment()')
        output = runtime.directory / runtime.history[0]['log']
        gc = GC_START.search(output.read_text(encoding='utf-8'))
        if gc is None:
            raise ProbeError('进程日志缺少 JVM GC 启动记录')
        metadata['jvm']['gc'] = gc[1]
        if metadata['jvm']['tickRate'] != 20:
            raise ProbeError('性能基准需要正常 tick rate')
        sample = spec.prepare(case)
        if 'client' in spec.needs.sides:
            metadata['client'] = runtime.manifest['clientEnvironments'][0]
        for _ in range(options.warmup):
            record['warmupSamples'].append(sample())
        for _ in range(options.rounds):
            record['samples'].append(sample())
    finally:
        errors = case.cleanup()
        if errors:
            record['cleanupErrors'].extend(errors)

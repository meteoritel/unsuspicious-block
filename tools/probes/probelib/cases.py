"""普通函数表达核心链路，准备场景与断言保持同一层控制流。"""
from pathlib import Path
import json
import struct
import time
import zlib

from .autojs import ProbeError
from .runtime import digest, write_json


def wait_for(query, accept, timeout=45):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = query()
        if accept(last):
            return last
        time.sleep(.15)
    raise ProbeError(f'等待超时，最后状态: {last}')


class Case:
    def __init__(self, runtime, record):
        self.runtime, self.record = runtime, record

    @property
    def server(self):
        return self.runtime.controls['server']

    @property
    def client(self):
        if 'client' not in self.runtime.controls:
            self.runtime.start('client')
            server = wait_for(lambda: self.server.evaluate('Probe.ready()'), lambda value: value['player'])
            client = wait_for(lambda: self.runtime.controls['client'].evaluate('Probe.ready()'), lambda value: value['player'])
            self.equal('两端玩家身份', client['uuid'], server['uuid'])
            self.runtime.manifest.setdefault('clientEnvironments', []).append(self.runtime.controls['client'].evaluate('Probe.environment()'))
            write_json(self.runtime.directory / 'manifest.json', self.runtime.manifest)
        return self.runtime.controls['client']

    def equal(self, label, actual, expected):
        self.record['phase'] = 'assert'
        self.record['assertions'].append(dict(label=label, expected=expected, actual=actual, passed=actual == expected))
        if actual != expected:
            raise AssertionError(f'{label}: expected={expected!r}, actual={actual!r}')

    def action(self, side, expression):
        self.record['phase'] = side + '-action'
        actions = self.record.setdefault('actions', [])
        key = f'{self.record["name"]}/{len(actions)}'
        actions.append(dict(id=key, side=side, expression=expression))
        control = self.client if side == 'client' else self.server
        value = control.action(key, expression)
        actions[-1]['result'] = value
        return value

    def stable_layout(self):
        previous = None
        def stable(value):
            nonlocal previous
            same = value == previous
            previous = value
            return same
        return wait_for(lambda: self.client.evaluate('Probe.layout()'), stable)

    def click(self, key):
        self.stable_layout()
        return self.action('client', f'Probe.click({json.dumps(key)})')

    def screenshot(self, name):
        self.record['phase'] = 'capture'
        layout = self.stable_layout()
        path = self.runtime.directory / f'{self.record["name"]}-{name}.png'
        self.action('client', f'Probe.capture({json.dumps(str(path))})')
        result = wait_for(lambda: self.client.evaluate('Probe.captureResult()'), lambda value: value is not None)
        self.equal('截图目标 Screen', result['screen'], layout['screen'])
        self.equal('截图世界', result['dimension'], 'minecraft:overworld')
        self.equal('PNG 原始尺寸', png_dimensions(path), [result['width'], result['height']])
        result['relative'] = path.name
        self.record['screenshots'].append(result)
        self.record.setdefault('layouts', []).append(layout)
        return result

    def diagnostic_screenshot(self):
        if 'client' not in self.runtime.controls:
            return
        path = self.runtime.directory / f'{self.record["name"]}-failure.png'
        try:
            self.client.evaluate(f'Probe.capture({json.dumps(str(path))})')
            result = wait_for(lambda: self.client.evaluate('Probe.captureResult()'), lambda value: value is not None, timeout=10)
            png_dimensions(path)
            result.update(relative=path.name, diagnostic=True)
            self.record['screenshots'].append(result)
        except (ProbeError, OSError, ValueError) as error:
            self.record['diagnosticCaptureError'] = str(error)


def png_dimensions(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ProbeError(f'PNG 签名无效: {path}')
    offset, compressed, dimensions, channels, ended = 8, bytearray(), None, 0, False
    while offset < len(data):
        if len(data) - offset < 12:
            raise ProbeError(f'PNG 数据截断: {path}')
        length = struct.unpack_from('>I', data, offset)[0]
        if offset + length + 12 > len(data):
            raise ProbeError(f'PNG 块截断: {path}')
        kind = data[offset + 4:offset + 8]
        content = data[offset + 8:offset + 8 + length]
        crc = struct.unpack_from('>I', data, offset + 8 + length)[0]
        if zlib.crc32(kind + content) != crc:
            raise ProbeError(f'PNG CRC 错误: {path}')
        if kind == b'IHDR':
            if length != 13:
                raise ProbeError(f'PNG IHDR 无效: {path}')
            dimensions = list(struct.unpack_from('>II', content))
            depth, color, compression, filtering, interlace = content[8:]
            if depth != 8 or color not in (2, 6) or (compression, filtering, interlace) != (0, 0, 0):
                raise ProbeError(f'截图需为非交错 RGB/RGBA PNG: {path}')
            channels = 3 if color == 2 else 4
        if kind == b'IDAT':
            compressed.extend(content)
        offset += length + 12
        if kind == b'IEND':
            ended = True
            break
    if dimensions is None or min(dimensions) <= 0 or not ended:
        raise ProbeError(f'PNG 像素无效: {path}')
    try:
        pixels = zlib.decompress(compressed)
    except zlib.error as error:
        raise ProbeError(f'PNG 像素压缩无效: {path}') from error
    stride = dimensions[0] * channels + 1
    if len(pixels) != dimensions[1] * stride or any(pixels[row * stride] > 4 for row in range(dimensions[1])):
        raise ProbeError(f'PNG 像素行无效: {path}')
    return dimensions


def runtime_artifact(case):
    runtime = case.runtime
    artifact = case.server.evaluate('Probe.artifact()')
    path = Path(artifact['path']).resolve(strict=True)
    expected = runtime.manifest['dependencies'][0]
    case.equal('发布 JAR 来源', str(path.parent), str(runtime.directory / 'server/mods'))
    case.equal('发布 JAR SHA-256', digest(path), expected['sha256'])
    case.equal('Minecraft 版本', artifact['minecraft'], runtime.launch['minecraft'])


SCAN_TABLE = 'usb_probe:archaeology/scan'
COMPLETE_TABLE = 'usb_probe:archaeology/complete'


def scan_and_take(case, table=SCAN_TABLE, item='minecraft:iron_ingot'):
    client = case.client
    case.action('client', 'Probe.closeScreen()')
    wait_for(lambda: case.server.evaluate(f'Probe.catalogReady({json.dumps(table)})'),
             lambda value: value['exists'] and value['tracked'])
    before = case.action('server', f'Probe.prepareScan({json.dumps(table)})')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: value['screen'] is None)
    # 玩家位置与两手物品需由正常网络同步后再输入。
    wait_for(lambda: client.evaluate('({x:Probe.client().player.getX(),z:Probe.client().player.getZ(),'
                                    'off:String(Probe.client().player.getOffhandItem().getItem())})'),
             lambda value: abs(value['x'] - .5) < .01 and abs(value['z'] + 1.5) < .01
             and 'archaeological_shovel' in value['off'])
    case.action('client', 'Probe.use()')
    scanned = wait_for(lambda: case.server.evaluate(f'Probe.scanState({json.dumps(table)})'), lambda value: value['scanned'])
    case.equal('扫描解锁表', scanned['unlocked'], True)
    case.equal('扫描解锁物品', scanned['items'][item]['unlocked'], True)
    case.equal('扫描不累计实得', scanned['items'][item]['count'], before['items'].get(item, {}).get('count', 0))
    case.equal('扫描暂存待定日志', scanned['pending'], True)
    case.action('client', 'Probe.use()')
    taken = wait_for(lambda: case.server.evaluate(f'Probe.scanState({json.dumps(table)})'), lambda value: value['loot'] == 0 and not value['pending'])
    case.equal('取物只累计一次', taken['items'][item]['count'], scanned['items'][item]['count'] + 1)
    case.equal('取物新增一条日志', len(taken['logs']), len(before['logs']) + 1)
    expected_key = f'usb_sig|PLAIN|{item}|'
    case.equal('日志实得', taken['logs'][-1]['actual'], {expected_key: 1})
    case.equal('日志预期', taken['logs'][-1]['expected'], {expected_key: 1})
    tick = case.server.evaluate('Probe.ready().tick')
    dispatched = case.action('client', 'Probe.use()')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: value['tick'] >= dispatched['tick'] + 2)
    wait_for(lambda: case.server.evaluate('Probe.ready()'), lambda value: value['tick'] >= tick + 4)
    repeated = case.server.evaluate(f'Probe.scanState({json.dumps(table)})')
    for key in ('unlocked', 'claimed', 'items', 'logs', 'pending', 'loot', 'iron', 'emerald', 'coins'):
        case.equal('重复右键保持 ' + key, repeated[key], taken[key])
    mirrored = wait_for(lambda: client.evaluate(f'Probe.journal({json.dumps(table)})'),
                        lambda value: value['items'].get(item, {}).get('count') == taken['items'][item]['count']
                        and len(value['logs']) == len(taken['logs']))
    case.equal('客户端镜像日志', mirrored['logs'], taken['logs'])
    case.action('client', 'Probe.openJournal()')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: (value['screen'] or '').endswith('ArchaeologyJournalScreen'))
    return taken


def select_probe_table(case, table=SCAN_TABLE):
    layout = case.stable_layout()
    if not any(node['key'] == 'journal/search' and node['visible'] for node in layout['nodes']):
        case.click('journal/search-toggle')
    case.click('journal/search')
    case.action('client', 'Probe.replaceText("@usb_probe")')
    wait_for(lambda: case.client.evaluate('Probe.layout()'),
             lambda value: any(node['key'] == f'table/{table}' for node in value['nodes']))
    case.click(f'table/{table}')


def scan_take_journal(case):
    taken = scan_and_take(case)
    case.equal('保留未发现项不发完成奖励', taken['claimed'], False)
    select_probe_table(case)
    case.click('journal/tab/log')
    case.click('log/' + taken['logs'][-1]['id'])
    case.screenshot('log-detail')


def journal_viewport(case):
    taken = scan_and_take(case)
    select_probe_table(case)
    case.click('journal/tab/log')
    entry_key = 'log/' + taken['logs'][-1]['id']
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    case.click(entry_key)
    case.screenshot('natural')
    for name, expression in [('small', 'Probe.resize(640,480)'), ('restored', 'Probe.resize(1100,720)'),
                              ('gui-scale', 'Probe.guiScale(3)')]:
        case.action('client', expression)
        layout = case.stable_layout()
        case.equal('视口适配最小画布', layout['scale'], min(1, layout['width'] / 412, layout['height'] / 256))
        case.equal('resize 保留所选日志详情', any(node['key'] == detail_key for node in layout['nodes']), True)
        search = next(node for node in layout['nodes'] if node['key'] == 'journal/search')
        case.equal('resize 保留搜索草稿', search['text'], '@usb_probe')
        for node in layout['nodes']:
            clip = node['clip']
            if node['visible'] and clip['width'] > 0 and clip['height'] > 0:
                case.equal('可见节点位于视口 ' + (node['key'] or node['kind']),
                           0 <= clip['x'] and 0 <= clip['y'] and clip['x'] + clip['width'] <= layout['width']
                           and clip['y'] + clip['height'] <= layout['height'], True)
        case.click('journal/log-back')
        case.click('journal/tab/archaeology')
        case.click('journal/tab/log')
        case.click(entry_key)
        case.screenshot(name)
    case.action('client', 'Probe.guiScale(2)')
    case.click('journal/log-back')
    case.click('journal/tab/log')
    case.click('journal/tab/archaeology')
    case.click('journal/tab/log')
    case.click('journal/log-clear-table')
    modal = case.stable_layout()
    case.equal('确认框接管布局输入树', any(node['key'] == 'journal/tab/log' for node in modal['nodes']), False)
    case.action('client', 'Probe.resize(1000,700)')
    restored = case.stable_layout()
    case.equal('resize 关闭确认框并恢复根输入树', any(node['key'] == 'journal/tab/log' for node in restored['nodes']), True)
    case.equal('关闭确认框不删除日志', case.server.evaluate(f'Probe.journal({json.dumps(SCAN_TABLE)}).logs'), taken['logs'])
    case.click(entry_key)
    case.screenshot('modal-dismissed')


def cat_ledger(case):
    case.server.install((case.runtime.root / 'tools/probes/scripts/cat.js').read_text(encoding='utf-8'))
    actual = case.action('server', 'Probe.catLedger()')
    expected = {
        'unrelated': [0, 0, False, 'ACQUAINTED', False],
        'establish': [0, 0, True, 'ACQUAINTED', True],
        'feed': [5, 0, True, 'ACQUAINTED', True],
        'cooldown': [5, 0, True, 'ACQUAINTED', False],
        'boundary': [10, 0, True, 'ACQUAINTED', True],
        'stage': [29, 0, True, 'CLOSE', True],
        'initial-life': [100, 1, True, 'BEST_FRIEND', True],
        'life-cap': [100, 9, True, 'BEST_FRIEND', False],
        'hit': [95, 0, True, 'HONORED_GUEST', True],
        'fatal-hit': [50, 0, True, 'TRUSTED', True],
        'no-second-initial-life': [100, 0, True, 'BEST_FRIEND', False],
        'other-cat': [45, 0, True, 'TRUSTED', True],
        'other-tick': [45, 0, True, 'TRUSTED', True],
        'restored': [45, 0, True, True, False, True],
    }
    for name, value in expected.items():
        case.equal('猫族模型 ' + name, actual[name], value)


def cat_carried_binding(case):
    case.client
    case.action('client', 'Probe.closeScreen()')
    case.server.install((case.runtime.root / 'tools/probes/scripts/cat.js').read_text(encoding='utf-8'))
    before = case.action('server', 'Probe.prepareForeignToken()')
    foreign = wait_for(lambda: case.server.evaluate('Probe.bindingState()'), lambda value: value['tick'] >= before['tick'] + 4)
    case.equal('他人信物不成为本人信物', foreign['owned'], False)
    case.equal('他人信物不建立关系', foreign['relationship'], False)
    case.equal('他人绑定不覆盖', foreign['boxedOwner'], '00000000-0000-0000-0000-000000000003')
    case.action('server', 'Probe.prepareBlankTokens()')
    bound = wait_for(lambda: case.server.evaluate('Probe.bindingState()'), lambda value: value['owned'] and value['relationship'])
    case.equal('第一层标本箱内容写回本人 UUID', bound['boxedOwner'], case.server.evaluate('Probe.ready().uuid'))
    case.equal('已有本人信物时额外空白保持空白', bound['extraBound'], False)


def panning_settlement(case):
    case.client
    case.server.install((case.runtime.root / 'tools/probes/scripts/panning.js').read_text(encoding='utf-8'))
    case.action('server', 'Probe.preparePanning("water")')
    before = wait_for(lambda: case.server.evaluate('Probe.panningState()'), lambda value: value['targeted'])
    case.action('client', 'Probe.closeScreen()')
    case.action('client', 'Probe.holdUse()')
    begun = case.action('server', 'Probe.beginPanning()')
    case.equal('正式交互开始使用', begun['state']['using'], True)
    settled = wait_for(lambda: case.server.evaluate('Probe.panningState()'),
                       lambda value: value['remaining'] == before['remaining'] - 1)
    case.action('client', 'Probe.releaseInput()')
    settled = case.server.evaluate('Probe.panningState()')
    case.equal('完成一次扣一次数', settled['remaining'], before['remaining'] - 1)
    case.equal('完成一次扣一点耐久', settled['damage'], 1)
    case.equal('真实产出数量', settled['produced'], before['produced'] + 2)
    case.equal('即时日志追加', len(settled['logs']), len(before['logs']) + 1)
    case.equal('即时日志实得', settled['logs'][-1]['actual'], {'usb_sig|PLAIN|minecraft:gold_nugget|': 2})
    case.action('server', 'Probe.preparePanning("water")')
    before = wait_for(lambda: case.server.evaluate('Probe.panningState()'), lambda value: value['targeted'])
    case.action('client', 'Probe.closeScreen()')
    case.action('client', 'Probe.holdUse()')
    case.action('server', 'Probe.beginPanning()')
    case.action('server', 'Probe.command("tp UsbProbe 0.5 100 -1.5 180 0")')
    interrupted = wait_for(lambda: case.server.evaluate('Probe.panningState()'), lambda value: not value['using'] and not value['targeted'])
    case.action('client', 'Probe.releaseInput()')
    for key in ('remaining', 'damage', 'produced', 'logs'):
        case.equal('移开中断保持 ' + key, interrupted[key], before[key])
    case.action('server', 'Probe.preparePanning("glimmer")')
    before = wait_for(lambda: case.server.evaluate('Probe.panningState()'), lambda value: value['targeted'])
    rejected = case.action('server', 'Probe.beginPanning()')
    case.equal('铜盘不支持岩浆点', rejected['interaction'], 'PASS')
    for key in ('remaining', 'damage', 'produced', 'logs', 'using'):
        case.equal('不支持变体保持 ' + key, rejected['state'][key], before[key])
    case.action('server', 'Probe.cleanPanning()')


def journal_restart(case):
    taken = scan_and_take(case, COMPLETE_TABLE, 'minecraft:emerald')
    case.equal('完整表领取奖励', taken['claimed'], True)
    case.equal('完成奖励一枚', taken['coins'], 1)
    expected = case.server.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})')
    case.record['savedBeforeRestart'] = expected
    case.runtime.stop('client')
    case.runtime.stop('server')
    case.runtime.fresh_client()
    case.runtime.start('server')
    client = case.client
    restored = case.server.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})')
    case.equal('服务端重启恢复进度与日志', restored, expected)
    mirrored = wait_for(lambda: client.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})'), lambda value: value == expected)
    case.equal('空客户端缓存重新同步', mirrored, expected)
    case.equal('重启登录不重复奖励', case.server.evaluate('Probe.inventoryCount("unsuspiciousblock:ancient_coin")'), 1)
    case.runtime.stop('client')
    client = case.client
    case.equal('再次登录不重复奖励', case.server.evaluate('Probe.inventoryCount("unsuspiciousblock:ancient_coin")'), 1)
    case.action('client', 'Probe.openJournal()')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: (value['screen'] or '').endswith('ArchaeologyJournalScreen'))
    select_probe_table(case, COMPLETE_TABLE)
    case.click('journal/tab/log')
    case.click('log/' + expected['logs'][-1]['id'])
    case.screenshot('restored-log')


def simulation_reload(case):
    scan_and_take(case)
    select_probe_table(case)
    case.click('journal/tab/scenario')
    script = (case.runtime.root / 'tools/probes/scripts/simulation.js').read_text(encoding='utf-8')
    case.client.install(script)
    table = json.dumps(SCAN_TABLE)
    case.action('client', f'Probe.requestSimulation({table})')
    normal = wait_for(lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'cached')
    case.equal('真实模拟抽样完成', normal['measured'], 10000)
    snapshot = case.runtime.directory / 'simulation-before-reload.bin'
    case.action('client', f'Probe.saveSimulation({table},{json.dumps(str(snapshot))})')
    case.action('client', f'Probe.requestUnissuedTool({table})')
    rejected = wait_for(lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'failed')
    case.equal('未签发工具收到网络拒绝', rejected['failure'], 'rejected_input')
    case.screenshot('rejected-input')
    case.action('server', 'Probe.command("reload")')
    # reload 可重建 AutoJS 全局；代次查询不依赖 Probe。
    expression = 'Number(Java.loadClass("com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog").currentGenerationId())'
    wait_for(lambda: case.server.evaluate(expression), lambda value: value > normal['generation'], timeout=90)
    for side in ('server', 'client'):
        case.runtime.install(side)
    case.client.install(script)
    current = wait_for(lambda: case.client.evaluate(f'Probe.simulationState({table})'),
                       lambda value: value is not None and value['generation'] > normal['generation'])
    replay = case.action('client', f'Probe.receiveOldSimulation({json.dumps(str(snapshot))})')
    case.record['oldDtoReception'] = replay
    case.equal('接收规则丢弃旧代次 DTO', replay['after'], replay['before'])
    case.equal('旧 DTO 代次', replay['oldGeneration'], normal['generation'])
    case.equal('新目录代次', replay['currentGeneration'], current['generation'])
    case.action('client', f'Probe.requestSimulation({table})')
    result = wait_for(lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'cached')
    case.equal('重载后合法输入仍完成', result['measured'], 10000)
    select_probe_table(case)
    case.click('journal/tab/scenario')
    case.screenshot('after-reload')


CASES = {'runtime-artifact': ('server', runtime_artifact),
         'scan-take-journal': ('gui', scan_take_journal), 'journal-viewport': ('gui', journal_viewport),
         'cat-ledger': ('model', cat_ledger), 'cat-carried-binding': ('server', cat_carried_binding),
         'panning-settlement': ('server', panning_settlement), 'journal-restart': ('gui', journal_restart),
         'simulation-reload': ('gui', simulation_reload)}

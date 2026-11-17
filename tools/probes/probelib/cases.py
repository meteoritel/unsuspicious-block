"""普通函数表达核心链路，准备场景与断言保持同一层控制流。"""
from pathlib import Path
import hashlib
import json
import math
import re
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
        self.cleanups = []
        self.client_baseline = None
        self.tables = set()
        self.scene_owned = False
        self.original_op = None

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
        control = self.runtime.controls['client']
        if self.client_baseline is None:
            self.client_baseline = control.evaluate('Probe.presentation()')
            self.record['presentationBefore'] = self.client_baseline
            self.defer('client', f'Probe.restorePresentation({json.dumps(self.client_baseline)})')
        return control

    def defer(self, side, expression):
        self.cleanups.append((side, expression))

    def own_scene(self):
        if not self.scene_owned:
            was_op = self.server.evaluate('Probe.server().getPlayerList().isOp(Probe.player().getGameProfile())')
            self.original_op = was_op
            self.record['opBefore'] = was_op
            self.defer('server', 'Probe.command("' + ('op' if was_op else 'deop') + ' UsbProbe")')
            self.defer('server', 'Probe.cleanScene()')
            self.scene_owned = True

    def own_table(self, table):
        if table not in self.tables:
            self.own_scene()
            expression = f'Probe.command({json.dumps("execute as UsbProbe run usb journal reset " + table)})'
            self.defer('server', expression)
            self.action('server', expression)
            self.expect('清空本场景表并自然同步', lambda: self.client.evaluate(f'Probe.journal({json.dumps(table)})'),
                        lambda value: not value['unlocked'] and value['history'] is None,
                        expected='未解锁且无历史')
            self.tables.add(table)
            self.record['ownedTables'] = sorted(self.tables)

    def cleanup(self):
        errors = self.record.setdefault('cleanupErrors', [])
        # 清理只使用已启动且当前有效的连接；重启不会留下旧 control。
        if 'client' in self.runtime.controls:
            try:
                self.runtime.controls['client'].evaluate('(Probe.releaseInput(), Probe.closeScreen())')
            except Exception as error:
                errors.append(f'释放输入: {error}')
        for side, expression in reversed(self.cleanups):
            try:
                self.runtime.controls[side].evaluate(expression)
            except Exception as error:
                errors.append(f'{side} {expression}: {error}')
        if self.client_baseline is not None and 'client' in self.runtime.controls:
            try:
                self.record['presentationAfter'] = wait_for(
                    lambda: self.runtime.controls['client'].evaluate('Probe.presentation()'),
                    lambda value: value == self.client_baseline, timeout=10)
            except Exception as error:
                errors.append(f'恢复窗口: {error}')
        if self.original_op is not None:
            try:
                actual = self.server.evaluate('Probe.server().getPlayerList().isOp(Probe.player().getGameProfile())')
                self.record['opAfter'] = actual
                if actual != self.original_op:
                    errors.append(f'恢复权限: expected={self.original_op}, actual={actual}')
            except Exception as error:
                errors.append(f'恢复权限: {error}')
        return errors

    def equal(self, label, actual, expected):
        self.record['phase'] = 'assert'
        self.record['assertions'].append(dict(label=label, expected=expected, actual=actual, passed=actual == expected))
        if actual != expected:
            raise AssertionError(f'{label}: expected={expected!r}, actual={actual!r}')

    def expect(self, label, query, accept, *, expected, timeout=45):
        self.record['phase'] = 'assert'
        deadline = time.monotonic() + timeout
        actual = None
        while True:
            actual = query()
            passed = bool(accept(actual))
            if passed or time.monotonic() >= deadline:
                self.record['assertions'].append(dict(label=label, expected=expected, actual=actual, passed=passed))
                if not passed:
                    raise AssertionError(f'{label}: expected={expected!r}, actual={actual!r}')
                return actual
            time.sleep(.15)

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

    def hover(self, key):
        self.stable_layout()
        self.action('client', f'Probe.hover({json.dumps(key)})')
        wait_for(lambda: self.client.evaluate(f'Probe.hovered({json.dumps(key)})'), bool)

    def screenshot(self, name):
        self.record['phase'] = 'capture'
        self.action('client', 'Probe.dismissNotifications()')
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
FORM_TABLE = 'usb_probe:archaeology/ui_forms'


def journal_snapshot(value):
    return {key: value[key] for key in ('unlocked', 'claimed', 'items', 'logs', 'history')}


def new_logs(case, before, after):
    previous = {entry['id'] for entry in before['logs']}
    added = [entry for entry in after['logs'] if entry['id'] not in previous]
    case.equal('本次新增日志身份', len(added), 1)
    baseline = int(before['history']['lifetimeEntryCount']) if before['history'] is not None else 0
    case.equal('终身累计增加一次', int(after['history']['lifetimeEntryCount']), baseline + 1)
    return added


def scan_and_take(case, table=SCAN_TABLE, item='minecraft:iron_ingot'):
    client = case.client
    signature = f'usb_sig|PLAIN|{item}|'
    case.own_table(table)
    case.action('client', 'Probe.closeScreen()')
    wait_for(lambda: case.server.evaluate(f'Probe.catalogReady({json.dumps(table)})'),
             lambda value: value['exists'] and value['tracked'])
    before = case.action('server', f'Probe.prepareScan({json.dumps(table)})')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: value['screen'] is None)
    # 等待正常网络同步、加载层退出与实际准星命中，再向原版输入队列投递右键。
    wait_for(lambda: client.evaluate('({x:Probe.client().player.getX(),z:Probe.client().player.getZ(),'
                                    'off:String(Probe.client().player.getOffhandItem().getItem()),ready:Probe.scanInputReady()})'),
             lambda value: abs(value['x'] - .5) < .01 and abs(value['z'] + 1.5) < .01
             and 'archaeological_shovel' in value['off'] and value['ready'])
    dispatched = case.action('client', 'Probe.use()')
    scanned = case.expect('扫描完成', lambda: case.server.evaluate(f'Probe.scanState({json.dumps(table)})'), lambda value: value['scanned'], expected='scanned=true')
    case.equal('扫描解锁表', scanned['unlocked'], True)
    case.equal('扫描解锁物品', scanned['items'][signature]['unlocked'], True)
    case.equal('扫描不累计实得', scanned['items'][signature]['count'], before['items'].get(signature, {}).get('count', 0))
    case.equal('扫描暂存待定日志', scanned['pending'], True)
    for key in ('iron', 'emerald'):
        case.equal('扫描保持背包 ' + key, scanned[key], before[key])
    # 原版 startUseItem 的右键间隔为四个客户端 tick；观察 tick 后再投递下一次独立点击。
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: value['tick'] >= dispatched['tick'] + 4)
    case.action('client', 'Probe.use()')
    taken = case.expect('取物完成', lambda: case.server.evaluate(f'Probe.scanState({json.dumps(table)})'), lambda value: value['loot'] == 0 and not value['pending'], expected='loot=0, pending=false')
    case.equal('取物只累计一次', taken['items'][signature]['count'], scanned['items'][signature]['count'] + 1)
    entries = new_logs(case, before, taken)
    physical = {'minecraft:iron_ingot': 'iron', 'minecraft:emerald': 'emerald'}[item]
    case.equal('取物实物进入背包', taken[physical], before[physical] + 1)
    expected_key = f'usb_sig|PLAIN|{item}|'
    case.equal('日志实得', entries[0]['actual'], {expected_key: 1})
    case.equal('日志预期', entries[0]['expected'], {expected_key: 1})
    tick = case.server.evaluate('Probe.ready().tick')
    dispatched = case.action('client', 'Probe.use()')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: value['tick'] >= dispatched['tick'] + 2)
    wait_for(lambda: case.server.evaluate('Probe.ready()'), lambda value: value['tick'] >= tick + 4)
    repeated = case.server.evaluate(f'Probe.scanState({json.dumps(table)})')
    for key in ('unlocked', 'claimed', 'items', 'logs', 'history', 'pending', 'loot', 'iron', 'emerald', 'coins'):
        case.equal('重复右键保持 ' + key, repeated[key], taken[key])
    mirrored = case.expect('客户端自然同步', lambda: client.evaluate(f'Probe.journal({json.dumps(table)})'),
                        lambda value: value == journal_snapshot(taken), expected=journal_snapshot(taken))
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


def open_log_list(case):
    case.click('journal/tab/log')
    if any(node['key'] == 'journal/log-back' and node['visible'] for node in case.stable_layout()['nodes']):
        case.click('journal/log-back')


def scan_take_journal(case):
    taken = scan_and_take(case)
    case.equal('保留未发现项不发完成奖励', taken['claimed'], False)
    select_probe_table(case)
    open_log_list(case)
    case.click('log/' + taken['logs'][-1]['id'])
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    case.equal('点击日志条目打开实际详情',
               any(node['key'] == detail_key for node in case.stable_layout()['nodes']), True)
    case.screenshot('log-detail')
    case.action('client', 'Probe.resize(853,481)')
    wait_for(lambda: case.client.evaluate('Probe.layout().window'), lambda value: value == [853, 481])
    layout = case.stable_layout()
    case.equal('奇数窗口保留本次日志详情', any(node['key'] == detail_key for node in layout['nodes']), True)
    search = next(node for node in layout['nodes'] if node['key'] == 'journal/search')
    case.equal('奇数窗口保留搜索', search['text'], '@usb_probe')
    case.click('journal/log-back')
    case.click('log/' + taken['logs'][-1]['id'])
    case.equal('resize 后正常点击命中本次日志', any(node['key'] == detail_key for node in case.stable_layout()['nodes']), True)
    case.screenshot('odd-log-detail')
    case.click('journal/log-back')
    case.click('journal/log-clear-table')
    case.equal('清空确认接管输入', any(node['key'] == 'journal/tab/log' for node in case.stable_layout()['nodes']), False)
    case.action('client', 'Probe.key(256,0)')
    case.equal('取消清空恢复页面', any(node['key'] == 'journal/tab/log' for node in case.stable_layout()['nodes']), True)
    case.equal('取消清空保持权威日志', case.server.evaluate(f'Probe.journal({json.dumps(SCAN_TABLE)}).logs'), taken['logs'])


def journal_viewport(case):
    taken = scan_and_take(case)
    select_probe_table(case)
    open_log_list(case)
    entry_key = 'log/' + taken['logs'][-1]['id']
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    case.click(entry_key)
    case.screenshot('natural')
    case.action('client', 'Probe.guiScale(2)')
    for name, size in [('default', [854, 480]), ('odd', [853, 481]),
                       ('small', [640, 480]), ('restored', [1100, 720]), ('gui-scale', None)]:
        if size is None:
            case.action('client', 'Probe.guiScale(3)')
        else:
            case.action('client', f'Probe.resize({size[0]},{size[1]})')
            wait_for(lambda: case.client.evaluate('Probe.layout()'), lambda value: value['window'] == size)
        layout = case.stable_layout()
        width, height = layout['framebuffer']
        gui_scale = layout['guiScale']
        case.equal('原版 GUI 倍率', gui_scale, 3 if name == 'gui-scale' else 2)
        pixels = math.floor(min(gui_scale, width / 412, height / 256))
        case.equal('物理窗口容纳整数倍率', pixels >= 1, True)
        case.equal('视口整数物理倍率', layout['scale'], pixels / gui_scale)
        logical = ([layout['width'], layout['height']] if pixels == gui_scale
                   else [width // pixels, height // pixels])
        case.equal('逻辑画布按实际像素取整', layout['logical'], logical)
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
        if name in ('default', 'odd', 'gui-scale'):
            case.hover(detail_key)
            case.screenshot(name + '-text-tooltip')
            loot = next(node['key'] for node in case.stable_layout()['nodes'] if node['key'].startswith('log/loot/'))
            case.hover(loot)
            case.screenshot(name + '-item-tooltip')
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
    case.own_scene()
    case.action('server', 'Probe.resetCat()')
    case.defer('server', 'Probe.resetCat()')
    case.action('client', 'Probe.closeScreen()')
    case.server.install((case.runtime.root / 'tools/probes/scripts/cat.js').read_text(encoding='utf-8'))
    before = case.action('server', 'Probe.prepareForeignToken()')
    foreign = wait_for(lambda: case.server.evaluate('Probe.bindingState()'), lambda value: value['tick'] >= before['tick'] + 4)
    case.equal('他人信物不成为本人信物', foreign['owned'], False)
    case.equal('他人信物不建立关系', foreign['relationship'], False)
    case.equal('他人绑定不覆盖', foreign['boxedOwner'], '00000000-0000-0000-0000-000000000003')
    case.action('server', 'Probe.prepareBlankTokens()')
    bound = case.expect('信物绑定并建立关系', lambda: case.server.evaluate('Probe.bindingState()'), lambda value: value['owned'] and value['relationship'], expected='owned=true, relationship=true')
    case.equal('第一层标本箱内容写回本人 UUID', bound['boxedOwner'], case.server.evaluate('Probe.ready().uuid'))
    case.equal('已有本人信物时额外空白保持空白', bound['extraBound'], False)


def entity_input_ready(case, target, item):
    case.action('client', 'Probe.closeScreen()')
    return wait_for(lambda: case.client.evaluate('Probe.entityInput()'),
                    lambda value: value['ready'] and value['target'] == target and value['item'] == item)


def cat_player_journey(case):
    case.client
    case.action('client', 'Probe.closeScreen()')
    case.own_scene()
    case.server.install((case.runtime.root / 'tools/probes/scripts/cat.js').read_text(encoding='utf-8'))
    baseline = case.action('server', 'Probe.prepareCatJourney()')
    query = lambda: case.server.evaluate('Probe.catJourneyState()')
    case.equal('无信物重置基线', [baseline['bond'], baseline['lives'], baseline['relationship'], baseline['owned']], [0, 0, False, False])
    case.equal('猫归属于测试玩家', baseline['owner'], case.server.evaluate('Probe.ready().uuid'))
    case.expect('客户端重置同步', lambda: case.client.evaluate('Probe.catMirror()'),
                lambda value: value == dict(bond=0, lives=0, relationship=False), expected=dict(bond=0, lives=0, relationship=False))
    case.action('server', 'Probe.carryCatToken()')
    bound = case.expect('真实 tick 绑定并建立关系', query, lambda value: value['owned'] and value['relationship'],
                        expected='owned=true, relationship=true')
    case.equal('猫之手绑定本人', bound['tokenOwner'], bound['owner'])
    case.equal('绑定不增加羁绊', bound['bond'], 0)
    case.expect('关系自然同步', lambda: case.client.evaluate('Probe.catMirror()'),
                lambda value: value == dict(bond=0, lives=0, relationship=True), expected=dict(bond=0, lives=0, relationship=True))
    for name in ('首次喂食', '冷却内再次喂食'):
        before = case.action('server', 'Probe.injureFeedCat()')
        entity_input_ready(case, before['target'], 'minecraft:cod')
        dispatched = case.action('client', 'Probe.use()')
        fed = case.expect(name + '消耗食物并治疗', query,
                          lambda value: value['food'] == before['food'] - 1 and value['health'] > before['health'],
                          expected={'food': before['food'] - 1, 'healthAbove': before['health']})
        result = case.expect(name + '结算羁绊', query, lambda value: value['bond'] == 5, expected='bond=5')
        case.equal(name + '处于同一冷却周期', result['tick'] - bound['tick'] < 12000, True)
        case.expect(name + '客户端镜像', lambda: case.client.evaluate('Probe.catMirror()'),
                    lambda value: value == dict(bond=5, lives=0, relationship=True), expected=dict(bond=5, lives=0, relationship=True))
        wait_for(lambda: case.client.evaluate('Probe.ready().tick'), lambda tick: tick >= dispatched['tick'] + 4)
    # 第二次治疗之后再观察服务端 tick，覆盖延迟到 tick 末的结算。
    final = wait_for(query, lambda value: value['tick'] >= fed['tick'] + 4)
    case.equal('冷却内治疗保持羁绊', final['bond'], 5)


def panning_settlement(case):
    case.client
    case.own_scene()
    table = 'unsuspiciousblock:gameplay/panning/river'
    case.own_table(table)
    case.server.install((case.runtime.root / 'tools/probes/scripts/panning.js').read_text(encoding='utf-8'))
    case.defer('server', 'Probe.cleanPanning()')
    query = lambda: case.server.evaluate('Probe.panningState()')
    before = case.action('server', 'Probe.preparePanning("water")')
    entity_input_ready(case, before['target'], 'unsuspiciousblock:copper_pan')
    wait_for(query, lambda value: value['targeted'])
    case.action('client', 'Probe.holdUse()')
    case.expect('normal input starts panning', query, lambda value: value['using'], expected='using=true')
    case.expect('panning completes', query, lambda value: value['remaining'] == before['remaining'] - 1,
                expected={'remaining': before['remaining'] - 1})
    case.action('client', 'Probe.releaseInput()')
    settled = query()
    case.equal('one use consumed', settled['remaining'], before['remaining'] - 1)
    case.equal('one durability consumed', settled['damage'], before['damage'] + 1)
    case.equal('physical gold delivered', settled['produced'], before['produced'] + 2)
    entries = new_logs(case, before, settled)
    case.equal('panning actual loot', entries[0]['actual'], {'usb_sig|PLAIN|minecraft:gold_nugget|': 2})
    case.expect('panning mirror', lambda: case.client.evaluate(f'Probe.journal({json.dumps(table)})'),
                lambda value: value == journal_snapshot(settled), expected=journal_snapshot(settled))

    before = case.action('server', 'Probe.preparePanning("water")')
    entity_input_ready(case, before['target'], 'unsuspiciousblock:copper_pan')
    wait_for(query, lambda value: value['targeted'])
    case.action('client', 'Probe.holdUse()')
    case.expect('interruption starts from actual use', query, lambda value: value['using'], expected='using=true')
    case.action('server', 'Probe.command("tp UsbProbe 0.5 100 -1.5 180 0")')
    interrupted = case.expect('look away interrupts', query, lambda value: not value['using'] and not value['targeted'],
                              expected='using=false, targeted=false')
    case.action('client', 'Probe.releaseInput()')
    for key in ('remaining', 'damage', 'produced', 'logs', 'history'):
        case.equal('interrupted preserves ' + key, interrupted[key], before[key])

    before = case.action('server', 'Probe.preparePanning("glimmer")')
    entity_input_ready(case, before['target'], 'unsuspiciousblock:copper_pan')
    case.action('client', 'Probe.holdUse()')
    rejected = wait_for(query, lambda value: value['tick'] >= before['tick'] + before['duration'] + 4)
    case.action('client', 'Probe.releaseInput()')
    for key in ('remaining', 'damage', 'produced', 'logs', 'history', 'using'):
        case.equal('unsupported variant preserves ' + key, rejected[key], before[key])


def journal_restart(case):
    taken = scan_and_take(case, COMPLETE_TABLE, 'minecraft:emerald')
    case.equal('完整表领取奖励', taken['claimed'], True)
    case.equal('完成奖励一枚', taken['coins'], 1)
    expected = case.action('server', f'Probe.prepareRestartMetadata({json.dumps(COMPLETE_TABLE)},{json.dumps(taken["logs"][-1]["id"])})')
    entry = expected['logs'][-1]
    case.equal('恢复基线维度', entry['context']['dimension'], 'minecraft:overworld')
    case.equal('恢复基线坐标', entry['context']['pos'], [0, 100, 1])
    case.equal('恢复基线备注', entry['note'], 'Probe recovery note')
    case.equal('恢复基线保留设置', expected['history']['retentionLimit'], 7)
    case.equal('记录实际游戏时间', int(entry['created']['gameTime']) > 0, True)
    case.expect('重启前元数据自然同步', lambda: case.client.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})'),
                lambda value: value == expected, expected=expected)
    case.record['savedBeforeRestart'] = expected
    case.runtime.stop('client')
    case.runtime.stop('server')
    case.runtime.fresh_client()
    case.runtime.start('server')
    client = case.client
    restored = case.server.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})')
    case.equal('服务端重启恢复进度与日志', restored, expected)
    mirrored = case.expect('客户端自然同步', lambda: client.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})'), lambda value: value == expected, expected=expected)
    case.equal('空客户端缓存重新同步', mirrored, expected)
    case.equal('重启登录不重复奖励', case.server.evaluate('Probe.inventoryCount("unsuspiciousblock:ancient_coin")'), 1)
    case.runtime.stop('client')
    client = case.client
    case.equal('再次登录不重复奖励', case.server.evaluate('Probe.inventoryCount("unsuspiciousblock:ancient_coin")'), 1)
    case.action('client', 'Probe.openJournal()')
    wait_for(lambda: client.evaluate('Probe.ready()'), lambda value: (value['screen'] or '').endswith('ArchaeologyJournalScreen'))
    select_probe_table(case, COMPLETE_TABLE)
    open_log_list(case)
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
    normal = case.expect('模拟完成', lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'cached', expected='status=cached')
    case.equal('真实模拟抽样完成', normal['measured'], 10000)
    snapshot = case.runtime.directory / 'simulation-before-reload.bin'
    case.action('client', f'Probe.saveSimulation({table},{json.dumps(str(snapshot))})')
    case.action('client', f'Probe.requestUnissuedTool({table})')
    rejected = case.expect('未签发输入被拒绝', lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'failed', expected='status=failed')
    case.equal('未签发工具收到网络拒绝', rejected['failure'], 'rejected_input')
    case.screenshot('rejected-input')
    case.action('server', 'Probe.command("reload")')
    # reload 可重建 AutoJS 全局；代次查询不依赖 Probe。
    expression = 'Number(Java.loadClass("com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog").currentGenerationId())'
    wait_for(lambda: case.server.evaluate(expression), lambda value: value > normal['generation'], timeout=90)
    for side in ('server', 'client'):
        case.runtime.install(side)
    case.client.install(script)
    current = case.expect('重载目录同步', lambda: case.client.evaluate(f'Probe.simulationState({table})'),
                       lambda value: value is not None and value['generation'] > normal['generation'], expected={'generationGreaterThan': normal['generation']})
    replay = case.action('client', f'Probe.receiveOldSimulation({json.dumps(str(snapshot))})')
    case.record['oldDtoReception'] = replay
    case.equal('接收规则丢弃旧代次 DTO', replay['after'], replay['before'])
    case.equal('旧 DTO 代次', replay['oldGeneration'], normal['generation'])
    case.equal('新目录代次', replay['currentGeneration'], current['generation'])
    case.action('client', f'Probe.requestSimulation({table})')
    result = case.expect('重载后模拟完成', lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'cached', expected='status=cached')
    case.equal('重载后合法输入仍完成', result['measured'], 10000)
    select_probe_table(case)
    case.click('journal/tab/scenario')
    case.screenshot('after-reload')


def ui_member_tree(case):
    from .ui_cases import member_tree
    taken = scan_and_take(case)
    select_probe_table(case)
    member_tree(case, taken, SCAN_TABLE)


def ui_form_bodies(case):
    from .ui_cases import form_bodies
    scan_and_take(case, FORM_TABLE)
    select_probe_table(case, FORM_TABLE)
    form_bodies(case, FORM_TABLE)


def recipe_card_filename(entry, variant):
    """为探针证据生成稳定文件名，具名配方不依赖清单当前顺序。"""
    position = int(entry['sourceIndex'] if entry['recipe'] is None else entry.get('occurrence', 0))
    identity = json.dumps([entry['category'], entry['recipe'], position, variant], separators=(',', ':'))
    suffix = hashlib.sha256(identity.encode()).hexdigest()[:12]
    stem = entry['recipe'] or f"{entry['category']}-anonymous-{int(entry['sourceIndex'])}"
    stem = re.sub(r'[^a-zA-Z0-9._-]', '_', stem)[:90].rstrip('. ')
    return f'card-{stem}-v{variant}-{suffix}.png'


def recipe_card_export(case):
    """通过已装载的 JEI bridge 导出本分支筛选到的全部配方。"""
    client = case.client
    options = case.runtime.recipe_cards
    output = case.runtime.directory / 'recipe-cards'
    output.mkdir(parents=True, exist_ok=False)
    filters = {key: options[key] for key in ('namespaces', 'categories', 'recipes')}
    token = f'{case.record["name"]}/{case.runtime.directory.name}'
    encoded_token = json.dumps(token)
    encoded_filters = json.dumps(filters, ensure_ascii=False)
    started = case.action('client', f'RecipeCards.begin({json.dumps(token)},{encoded_filters})')
    case.equal('配方卡片扫描启动', started['state'], 'scanning')
    state = wait_for(lambda: client.evaluate(f'RecipeCards.step({encoded_token})'),
                     lambda value: value['state'] != 'scanning', timeout=90)
    if state['state'] != 'ready':
        raise ProbeError(f'配方卡片清单失败: {state}')
    entries = []
    for offset in range(0, int(state['total']), 64):
        entries.extend(client.evaluate(f'RecipeCards.page({encoded_token},{offset})'))
    case.equal('配方卡片清单分页完整', len(entries), state['total'])
    if not entries:
        raise AssertionError(f'配方卡片筛选结果为空: {filters}')

    cards = []
    manifest = dict(schemaVersion=1, filters=filters, scale=options['scale'],
                    variant=options['variant'], cards=cards)
    write_json(output / 'recipe-cards.json', manifest)
    case.record['recipeCardManifest'] = str((output / 'recipe-cards.json').relative_to(case.runtime.directory))
    for entry in entries:
        index = int(entry['index'])
        request_id = f'{token}/{index}'
        path = output / recipe_card_filename(entry, options['variant'])
        submit_options = dict(id=request_id, path=str(path), scale=options['scale'],
                              variant=options['variant'], overwrite=False)
        case.action('client', f'RecipeCards.submit({encoded_token},{index},{json.dumps(submit_options, ensure_ascii=False)})')
        result = wait_for(lambda: client.evaluate(f'RecipeCards.status({json.dumps(request_id)})'),
                          lambda value: value['state'] in ('done', 'failed'), timeout=60)
        card = dict(entry, requestId=request_id, file=path.name, result=result, state=result['state'])
        if result['state'] == 'done':
            if not path.is_file():
                raise ProbeError(f'配方卡片 PNG 缺失: {path}')
            dimensions = png_dimensions(path)
            case.equal(f'PNG 尺寸 {path.name}', dimensions, [result['width'], result['height']])
            card['sha256'] = digest(path)
            case.record['screenshots'].append(dict(relative=str(path.relative_to(case.runtime.directory)),
                                                   width=dimensions[0], height=dimensions[1], recipeCard=True))
        cards.append(card)
        case.record.setdefault('recipeCards', []).append(card)
        write_json(output / 'recipe-cards.json', manifest)
        if result['state'] == 'failed':
            raise ProbeError(f'配方卡片导出失败: {result}')

    write_json(output / 'recipe-cards.json', manifest)
    case.equal('配方卡片全部完成', all(card['state'] == 'done' for card in cards), True)


CASES = {'runtime-artifact': ('server', runtime_artifact),
         'scan-take-journal': ('gui', scan_take_journal), 'journal-viewport': ('gui', journal_viewport),
         'cat-ledger': ('model', cat_ledger), 'cat-carried-binding': ('server', cat_carried_binding),
         'cat-player-journey': ('server', cat_player_journey),
         'panning-settlement': ('server', panning_settlement), 'journal-restart': ('gui', journal_restart),
         'simulation-reload': ('gui', simulation_reload),
         'ui-member-tree': ('gui', ui_member_tree), 'ui-form-bodies': ('gui', ui_form_bodies),
         'recipe-card-export': ('gui', recipe_card_export)}

CORE_CASES = ('runtime-artifact', 'scan-take-journal', 'journal-restart', 'panning-settlement', 'cat-player-journey')

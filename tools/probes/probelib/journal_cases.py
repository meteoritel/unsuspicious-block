"""考古笔记的进度、恢复、目录与客户端场景。"""
import json
import math

from .cases import wait_for


SCAN_TABLE = 'usb_probe:archaeology/scan'
COMPLETE_TABLE = 'usb_probe:archaeology/complete'
FORM_TABLE = 'usb_probe:archaeology/ui_forms'
CATALOG_PARENT = 'usb_probe:archaeology/loading_parent'
CATALOG_CHILD = 'usb_probe:loading_child'
CATALOG_FIXTURE = {
    'members': {SCAN_TABLE: [], CATALOG_PARENT: [CATALOG_CHILD], CATALOG_CHILD: []},
    'roots': {SCAN_TABLE: 'unsuspiciousblock:brushing', CATALOG_PARENT: 'unsuspiciousblock:brushing'},
}


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


def prepare_table_selection(case, table=SCAN_TABLE):
    layout = case.stable_layout()
    if not any(node['key'] == 'journal/search' and node['visible'] for node in layout['nodes']):
        case.click('journal/search-toggle')
    case.click('journal/search')
    case.action('client', 'Probe.replaceText("@usb_probe")')
    wait_for(lambda: case.client.evaluate('Probe.layout()'),
             lambda value: any(node['key'] == f'table/{table}' for node in value['nodes']))


def select_probe_table(case, table=SCAN_TABLE):
    prepare_table_selection(case, table)
    case.click(f'table/{table}')


def open_log_list(case):
    case.click('journal/tab/log')
    if any(node['key'] == 'journal/log-back' and node['visible'] for node in case.stable_layout()['nodes']):
        case.click('journal/log-back')


def active_log_detail(case):
    nodes = case.stable_layout()['nodes']
    keys = [node['key'] for node in nodes if node['visible'] and node['key'].startswith('log/coordinates/')]
    return keys[0] if len(keys) == 1 else keys


def scan_take_journal(case):
    taken = scan_and_take(case)
    case.check('scan-loot', taken['logs'][-1]['actual'], {'usb_sig|PLAIN|minecraft:iron_ingot|': 1})
    case.equal('保留未发现项不发完成奖励', taken['claimed'], False)
    select_probe_table(case)
    open_log_list(case)
    case.click('log/' + taken['logs'][-1]['id'])
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    case.check('scan-active-detail', active_log_detail(case), detail_key)
    case.screenshot('log-detail')
    case.resize(853, 481)
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
            case.resize(*size)
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
    case.resize(1000, 700)
    restored = case.stable_layout()
    case.check('viewport-modal-resize',
               dict(modal=any(node['kind'] == 'MODAL' for node in restored['nodes']),
                    root=any(node['key'] == 'journal/tab/log' for node in restored['nodes'])),
               dict(modal=True, root=False))
    case.action('client', 'Probe.key(256,0)')
    case.equal('取消确认后恢复根输入树', any(node['key'] == 'journal/tab/log' for node in case.stable_layout()['nodes']), True)
    case.equal('关闭确认框不删除日志', case.server.evaluate(f'Probe.journal({json.dumps(SCAN_TABLE)}).logs'), taken['logs'])
    case.click(entry_key)
    case.check('viewport-active-detail', active_log_detail(case), detail_key)
    case.screenshot('modal-dismissed')


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
    case.check('restored-server', restored, expected)
    case.await_check('restored-client', lambda: client.evaluate(f'Probe.journal({json.dumps(COMPLETE_TABLE)})'), expected)
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
    case.action('client', f'Probe.requestUnissuedTool({table})')
    rejected = case.expect('未签发输入被拒绝', lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'failed', expected='status=failed')
    case.check('simulation-rejection', rejected['failure'], 'rejected_input')
    case.screenshot('rejected-input')
    case.action('server', 'Probe.command("reload")')
    # reload 可重建 AutoJS 全局；代次查询不依赖 Probe。
    expression = 'Number(Java.loadClass("com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog").currentGenerationId())'
    wait_for(lambda: case.server.evaluate(expression), lambda value: value > normal['generation'], timeout=90)
    for side in ('server', 'client'):
        case.runtime.install(side)
    case.client.install(script)
    current = case.expect('重载目录同步', lambda: case.client.evaluate(f'Probe.simulationState({table})'),
                          lambda value: value is not None and value['generation'] > normal['generation'],
                          expected={'generationGreaterThan': normal['generation']})
    case.action('client', f'Probe.requestSimulation({table})')
    result = case.expect('重载后模拟完成', lambda: case.client.evaluate(f'Probe.simulationState({table})'), lambda value: value['status'] == 'cached', expected='status=cached')
    case.equal('重载后合法输入仍完成', result['measured'], 10000)
    offset = normal['generation'] - current['generation']
    replay_samples = 10001
    observe_samples = lambda: case.client.evaluate(f'Probe.simulationState({table}).measured')
    case.action('client', f'Probe.replaySimulation({table},{offset},{replay_samples})')
    case.check('stale-samples', observe_samples(), 10000)
    case.action('client', f'Probe.replaySimulation({table},0,{replay_samples})')
    case.check('current-samples', observe_samples(), replay_samples)
    select_probe_table(case)
    case.click('journal/tab/scenario')
    case.screenshot('after-reload')


def journal_catalog_loading(case):
    taken = scan_and_take(case)
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    script = (case.runtime.root / 'tools/probes/scripts/catalog_loading.js').read_text(encoding='utf-8')
    case.server.install(script)
    case.client.install(script)
    table = json.dumps(SCAN_TABLE)
    initial = case.client.evaluate(f'Probe.catalogView({table})')
    case.defer('server', 'Probe.command("tick rate 20")')
    case.action('server', 'Probe.command("tick rate 2")')
    cold = case.action('server', f'(Probe.rebuildColdCatalog(),Probe.catalogSnapshot({table}))')
    generation = cold['generation']
    case.record['coldSnapshot'] = cold
    case.check('static-server', cold['fixtures'], CATALOG_FIXTURE)
    case.equal('重建完成后基准尚未模拟', (cold['generation'], cold['busy'], cold['measured']), (generation, True, 0))

    query = lambda: case.client.evaluate(f'Probe.catalogView({table})')
    pending = case.expect('客户端自然收到新代完整静态目录', query,
                          lambda value: value and value['generation'] == generation and value['ready'],
                          expected={'generation': generation, 'ready': True})
    case.check('static-client', pending['fixtures'], CATALOG_FIXTURE)
    case.equal('历史日志独立保留', pending['logs'], initial['logs'])
    case.equal('显示静态候选与待计算状态', {item['id']: item['state'] for item in pending['items']},
               {'minecraft:iron_ingot': 'NOT_SIMULATED', 'minecraft:diamond': 'NOT_SIMULATED'})
    welcome = case.stable_layout()
    case.equal('欢迎页和分类同时可浏览',
               any(node['key'] == 'journal/welcome' for node in welcome['nodes'])
               and any(node['key'].startswith('category/') for node in welcome['nodes']), True)
    case.screenshot('catalog-before-measurement')
    select_probe_table(case)
    open_log_list(case)
    case.click('log/' + taken['logs'][-1]['id'])

    ready = case.expect('抽样后当前表概率单独回填', query,
                        lambda value: value and value['measured'] > 0,
                        expected='simulationCount > 0')
    progress = case.server.evaluate(f'Probe.catalogProgress({table})')
    case.check('individual-delivery', (ready['generation'], progress['generation'], progress['measured'],
                                      progress['busy'], ready['sharedSamples']), (generation, generation, True, True, 0))
    case.equal('下一次更新保留目录成员', ready['fixtures'], CATALOG_FIXTURE)
    case.equal('冷缓存重新抽样所得物品概率',
               {item['id']: item['state'] for item in ready['items']}.get('minecraft:iron_ingot'), 'MEASURED')
    case.check('individual-reading', active_log_detail(case), detail_key)
    case.screenshot('catalog-after-measurement')

    case.action('server', 'Probe.command("tick rate 20")')
    committed = case.expect('队列排空后客户端收到共享基准', query,
                            lambda value: value and value['generation'] == generation
                            and value['sharedSamples'] == ready['measured'],
                            expected={'sharedSamples': ready['measured']})
    case.equal('共享更新保留已显示的概率状态', committed['items'], ready['items'])
    case.check('shared-reading', active_log_detail(case), detail_key)
    case.record['readySnapshot'] = case.server.evaluate(f'Probe.catalogSnapshot({table})')


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

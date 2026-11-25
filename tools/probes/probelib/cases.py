"""普通函数表达核心链路，准备场景与断言保持同一层控制流。"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import struct
import time
import zlib

from .autojs import ProbeError
from .runtime import digest, write_json
from .verdicts import Verdict


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
    def __init__(self, runtime, record, required=()):
        self.runtime, self.record = runtime, record
        self.verdict = Verdict(required)
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

    def check(self, check_id, actual, expected):
        self.record['phase'] = 'assert'
        self.verdict.check(check_id, actual, expected)

    def await_check(self, check_id, observe, expected, *, timeout=45):
        self.record['phase'] = 'assert'
        self.verdict.wait(check_id, observe, expected, timeout)

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

    def resize(self, width, height):
        self.action('client', f'Probe.resize({width},{height})')
        return wait_for(lambda: self.client.evaluate('Probe.layout()'),
                        lambda layout: layout['window'] == [width, height])

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


def creative_tab(case):
    case.client
    case.action('server', 'Probe.command("gamemode creative UsbProbe")')
    case.defer('server', 'Probe.command("gamemode survival UsbProbe")')
    case.expect('玩家进入创造模式', lambda: case.client.evaluate(
        'Probe.client().gameMode.hasInfiniteItems()'), bool, expected=True)
    expression = """(function() {
        var client = Probe.client();
        var screen = new (Probe.type('net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen'))(
            client.player, client.level.enabledFeatures(), false);
        client.setScreen(screen);
        var tabs = Probe.type('net.minecraft.core.registries.BuiltInRegistries').CREATIVE_MODE_TAB;
        var id = Probe.id('unsuspiciousblock:main');
        var tab = tabs.get(id);
        if (tab === null) return {registered: false, items: []};
        tab.buildContents(new (Probe.type('net.minecraft.world.item.CreativeModeTab$ItemDisplayParameters'))(
            client.level.enabledFeatures(), client.player.canUseGameMasterBlocks(), client.level.registryAccess()));
        var items = [], iterator = tab.getDisplayItems().iterator();
        while (iterator.hasNext()) items.push(String(Probe.type('net.minecraft.core.registries.BuiltInRegistries')
            .ITEM.getKey(iterator.next().getItem())));
        var registry = Probe.type('net.neoforged.neoforge.common.CreativeModeTabRegistry');
        var sortedTabs = registry.getSortedCreativeModeTabs(), defaults = registry.getDefaultTabs();
        var pageTabs = [], currentGroup = [], tabPage = null, tabIndex = 0;
        var sortedIterator = sortedTabs.iterator();
        while (sortedIterator.hasNext()) {
            var sortedTab = sortedIterator.next();
            if (!sortedTab.hasAnyItems()) continue;
            if (sortedTab === tab) tabPage = Math.floor(tabIndex / 10) + 1;
            currentGroup.push(sortedTab);
            tabIndex++;
            if (currentGroup.length === 10 || !sortedIterator.hasNext()) {
                var pageTabIds = [];
                for (var i = 0; i < currentGroup.length; i++) {
                    if (currentGroup[i].shouldDisplay()) pageTabIds.push(String(tabs.getKey(currentGroup[i])));
                }
                var defaultIterator = defaults.iterator();
                while (defaultIterator.hasNext()) {
                    var defaultTab = defaultIterator.next();
                    if (defaultTab.shouldDisplay()) pageTabIds.push(String(tabs.getKey(defaultTab)));
                }
                pageTabs.push(pageTabIds);
                currentGroup = [];
            }
        }
        var currentPageTabs = [], currentIterator = screen.getCurrentPage().getVisibleTabs().iterator();
        while (currentIterator.hasNext()) currentPageTabs.push(String(tabs.getKey(currentIterator.next())));
        var selectedPage = null;
        for (var pageIndex = 0; pageIndex < pageTabs.length; pageIndex++) {
            if (String(pageTabs[pageIndex]) === String(currentPageTabs)) selectedPage = pageIndex + 1;
        }
        return {registered: true, visible: tab.shouldDisplay(), title: String(tab.getDisplayName().getString()),
            icon: String(Probe.type('net.minecraft.core.registries.BuiltInRegistries').ITEM.getKey(tab.getIconItem().getItem())),
            items: items, pages: pageTabs,
            selectedPage: selectedPage, tabPage: tabPage};
    })()"""
    snapshot = case.client.evaluate(expression)
    case.record['creativeTabPages'] = dict(pages=snapshot.pop('pages'), selectedPage=snapshot.pop('selectedPage'),
                                            tabPage=snapshot.pop('tabPage'))
    case.check('creative-tab-items', snapshot, dict(
        registered=True, visible=True, title='不可疑方块', icon='unsuspiciousblock:pottery_wheel',
        items=['unsuspiciousblock:archaeology_journal', 'unsuspiciousblock:unsuspicious_sand',
               'unsuspiciousblock:unsuspicious_gravel', 'unsuspiciousblock:pottery_wheel',
               'unsuspiciousblock:unfired_decorated_pot', 'unsuspiciousblock:unfired_decorated_sherd',
               'unsuspiciousblock:suspicious_reader', 'unsuspiciousblock:archaeological_shovel',
               'unsuspiciousblock:copper_pan', 'unsuspiciousblock:gold_pan', 'unsuspiciousblock:obsidian_pan',
               'unsuspiciousblock:ancient_coin', 'unsuspiciousblock:lost_page', 'unsuspiciousblock:base_page',
               'unsuspiciousblock:spark_dust', 'unsuspiciousblock:rewind_dust', 'unsuspiciousblock:eye_of_cat',
               'unsuspiciousblock:specimen_box', 'unsuspiciousblock:hand_of_cat',
               'unsuspiciousblock:purrmint', 'unsuspiciousblock:purrmint_seeds']))
    case.action('client', '(function() { var screen = Probe.client().screen; '
                'screen.mouseClicked(362, 72, 0); screen.mouseReleased(362, 72, 0); return true; })()')
    visible_tabs = case.client.evaluate("""(function() {
        var tabs = Probe.type('net.minecraft.core.registries.BuiltInRegistries').CREATIVE_MODE_TAB;
        var result = [], iterator = Probe.client().screen.getCurrentPage().getVisibleTabs().iterator();
        while (iterator.hasNext()) result.push(String(tabs.getKey(iterator.next())));
        return result;
    })()""")
    case.equal('第二页可见本模组标签', 'unsuspiciousblock:main' in visible_tabs, True)
    case.record['creativeTabPageTwo'] = visible_tabs
    case.action('client', 'Probe.dismissNotifications()')
    path = case.runtime.directory / 'creative-tab.png'
    case.action('client', f'Probe.capture({json.dumps(str(path))})')
    capture = wait_for(lambda: case.client.evaluate('Probe.captureResult()'), lambda value: value is not None)
    case.equal('创造栏截图 Screen', capture['screen'], 'net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen')
    case.equal('截图世界', capture['dimension'], 'minecraft:overworld')
    case.equal('PNG 原始尺寸', png_dimensions(path), [capture['width'], capture['height']])
    capture['relative'] = path.name
    case.record['screenshots'].append(capture)


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
    case.check('artifact-digest', digest(path), expected['sha256'])
    case.equal('Minecraft 版本', artifact['minecraft'], runtime.launch['minecraft'])


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
    case.check('exported-files', sorted(path.name for path in output.glob('*.png')),
               sorted(recipe_card_filename(entry, options['variant']) for entry in entries))


def core_architecture_inventory(case):
    _ = case.client
    case.own_scene()
    case.server.install((case.runtime.root / 'tools/probes/scripts/core_architecture.js').read_text(encoding='utf-8'))
    case.action('server', 'Probe.command("gamemode survival UsbProbe")')
    case.action('server', 'Probe.command("fill -4 99 -4 4 99 4 minecraft:stone")')
    case.action('server', 'Probe.command("tp UsbProbe 0.5 100 -1.5")')
    case.defer('server', '(Probe.player().closeContainer(), true)')
    wait_for(lambda: case.server.evaluate('Number(Probe.player().tickCount)'), lambda ticks: ticks > 60)

    for hand in ('false', 'true'):
        case.equal('打开指定手的标本箱', case.action('server', f'Probe.archOpenBox({hand})'), True)
        case.equal('图腾消费后快速移动和同型载体替换', case.action('server', 'Probe.archConsumeBoxTotem()'),
                   dict(alive=True, consumed=True, moved=True, invalid=True))

    for mode in ('QUICK_MOVE', 'PICKUP', 'SWAP'):
        case.equal('锻造配方生成预览', case.action('server', 'Probe.archOpenSmithing(false)'), True)
        case.equal(f'{mode} 交付已鉴定的书', case.action('server', f'Probe.archTakeSmithing("{mode}")'),
                   dict(enchanted=True, emptyInputs=True, pending=False))
    case.equal('满背包仍可预览配方', case.action('server', 'Probe.archOpenSmithing(true)'), True)
    case.check('full-inventory-smithing', case.action('server', 'Probe.archTakeSmithing("QUICK_MOVE")'),
               dict(enchanted=False, emptyInputs=False, pending=True))
    case.action('server', '(Probe.player().closeContainer(), true)')

    for enchanted in ('false', 'true'):
        expected = dict(broken=True, cleared=True, emptyTool=enchanted == 'true', bonus=enchanted == 'true')
        case.equal('成功采掘清除自然标记', case.action('server', f'Probe.archBreakBone({enchanted})'), expected)

    sherd = 'unsuspiciousblock:unfired_decorated_sherd'
    case.equal('一份黏土时的陶轮预览', case.action('server', 'Probe.archPottery()'), sherd)
    case.expect('实际产物和预览一致，材料扣除且返还空瓶',
                lambda: case.server.evaluate('Probe.archPotteryResult()'),
                lambda result: result == dict(output=sherd, water='minecraft:glass_bottle', clayEmpty=True),
                expected={'output': sherd, 'water': 'minecraft:glass_bottle', 'clayEmpty': True})

    case.equal('未到期卸载商人仍占位', case.action('server', 'Probe.archPrepareMerchant()'),
               dict(spawned=True, unloaded=True, occupied=True))
    case.expect('无人加载旧商人时到期释放占位',
                lambda: case.server.evaluate('Probe.archMerchantOccupied()'), lambda value: not value, expected=False)

    case.equal('登记了有寿命的淘洗点', case.action('server', 'Probe.archLedgerDeadline()'), True)
    case.expect('到期点释放配额并留下标记',
                lambda: case.server.evaluate('Probe.archLedgerExpired()'), bool, expected=True)


def write_graph_fixtures(pack):
    def graph(path, entries):
        write_json(pack / ('loot_table/architecture_graph/' + path + '.json'),
                   {'type': 'minecraft:generic', 'pools': [{'rolls': 1, 'entries': entries}]})

    def reference(path, **fields):
        return {'type': 'minecraft:loot_table', 'value': 'usb_probe:architecture_graph/' + path, **fields}

    graph('leaf', [{'type': 'minecraft:item', 'name': 'minecraft:stone'}])
    for layer in range(1, 13):
        below = [reference(f'{layer-1}_{side}') for side in ('a', 'b')] if layer > 1 else [reference('leaf')]
        for side in ('a', 'b'):
            graph(f'{layer}_{side}', below)
    graph('different', [
        reference('leaf', conditions=[{'condition': 'minecraft:random_chance', 'chance': 0}]),
        reference('leaf', functions=[{'function': 'minecraft:set_count', 'count': 2}]),
    ])
    graph('different_root', [reference('different')])


def _write_architecture_fixtures(case):
    pack = case.runtime.directory / 'server/world/datapacks/usb_probe/data/usb_probe'
    def loot(path, entries, kind='minecraft:generic'):
        write_json(pack / ('loot_table/' + path + '.json'),
                   {'type': kind, 'pools': [{'rolls': 1, 'entries': entries}]})

    def item(name, **fields):
        return {'type': 'minecraft:item', 'name': f'minecraft:{name}', **fields}

    def reference(value):
        return {'type': 'minecraft:loot_table', 'value': value}

    def composite(kind, *entries):
        return {'type': f'minecraft:{kind}', 'children': entries}

    miss = [{'condition': 'minecraft:random_chance', 'chance': 0}]
    leaf = 'usb_probe:archaeology/architecture_leaf'
    loot('archaeology/architecture_leaf', [item('diamond')], 'minecraft:archaeology')
    ordered = [
        composite('alternatives', item('iron_ingot', conditions=miss), item('apple')),
        composite('sequence', item('book'), item('emerald', conditions=miss), item('gold_ingot')),
        composite('group', item('feather', weight=0), item('coal')),
        reference({'pools': [{'rolls': 1, 'entries': [reference(leaf)]}]}),
    ]
    write_json(pack / 'loot_table/archaeology/architecture_ordered.json',
               {'type': 'minecraft:archaeology', 'pools': [{'rolls': 1, 'entries': [entry]} for entry in ordered]})

    write_graph_fixtures(pack)

def core_architecture_catalog(case):
    _write_architecture_fixtures(case)
    expr = 'Number(Java.loadClass("com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog").currentGenerationId())'
    before = case.server.evaluate(expr)
    case.action('server', 'Probe.command("reload")')
    case.expect('数据包实际发布新目录', lambda: case.server.evaluate(expr), lambda value: value > before,
                expected={'generationGreaterThan': before}, timeout=90)
    case.runtime.install('server')
    case.server.install((case.runtime.root / 'tools/probes/scripts/core_architecture.js').read_text(encoding='utf-8'))
    result = case.action('server', 'Probe.archCatalogTable("usb_probe:archaeology/architecture_ordered")')
    case.check('ordered-loot', result['drops'],
               ['minecraft:apple', 'minecraft:book', 'minecraft:coal', 'minecraft:diamond'])
    case.equal('内联表中的具名引用成为目录子表', result['child'], True)
    case.equal('零权重组内物品不可产出', result['staticItems']['minecraft:feather']['blockedByWeight'], True)
    case.equal('回退分支继承前项失败条件',
               'NOT' in result['staticItems']['minecraft:apple']['gating'], True)
    case.equal('序列后项继承前项成功条件',
               'LEAF' in result['staticItems']['minecraft:gold_ingot']['gating'], True)

    for name in ('leaf', '4_a', '9_a', '12_a'):
        result = case.server.evaluate(f'Probe.archProjectGraph("usb_probe:architecture_graph/{name}")')
        case.equal(f'共享后缀 {name} 的物品集合', result['items'], ['minecraft:stone'])
        case.equal(f'共享后缀 {name} 的获取路径', result['paths'], 1 if name == 'leaf' else 2)
    different = case.server.evaluate('Probe.archProjectGraph("usb_probe:architecture_graph/different_root")')
    case.equal('继承条件或函数不同的路径各自保留', different['paths'], 2)

    _verify_catalog_rebuild_failure(case, expr)
    before = case.server.evaluate(expr)
    case.action('server', 'Probe.command("usb journal catalog rebuild")')
    case.expect('强制重建发布新目录', lambda: case.server.evaluate(expr), lambda value: value > before,
                expected={'generationGreaterThan': before})

def merchant_trade_pool(case):
    script = case.runtime.root / 'tools/probes/scripts/core_architecture.js'
    case.server.install(script.read_text(encoding='utf-8'))
    offers = set(case.server.evaluate('Probe.archTradeOffers()'))
    fixture_items = {'minecraft:end_stone', 'minecraft:amethyst_shard', 'minecraft:air', 'minecraft:barrier'}
    case.check('merchant-valid-offers', sorted(offers.intersection(fixture_items)),
               ['minecraft:amethyst_shard', 'minecraft:end_stone'])


def _verify_catalog_rebuild_failure(case, generation_expression):
    query = 'Probe.archCatalogState()'
    healthy = case.expect('基准测量已缓存', lambda: case.server.evaluate(query), lambda state: state['cached'],
                          expected={'cached': True})
    failure = case.runtime.directory / 'server/world/datapacks/usb_probe/data/usb_probe/journal_categories/too_long.json'
    # 分类仍能读入，但目录 wire codec 会拒绝超过 writeUtf 长度的文本。
    write_json(failure, {'fallback': 'x' * 32768})
    failed = 'Java.loadClass("com.meteorite.unsuspiciousblock.core.journal.catalog.ArchaeologyJournalServerCatalog").hasRebuildFailed()'
    try:
        case.action('server', 'Probe.command("reload")')
        case.expect('候选目录构建确实失败', lambda: case.server.evaluate(failed), bool, expected=True)
        case.runtime.install('server')
        case.server.install((case.runtime.root / 'tools/probes/scripts/core_architecture.js').read_text(encoding='utf-8'))
        expected = dict(healthy, canExecute=False, failed=True)
        case.equal('资源已切换，保留快照和概率缓存', case.server.evaluate(query), expected)
        case.action('server', 'Probe.command("usb journal catalog rebuild")')
        case.equal('强制重建失败保留相同快照和缓存', case.server.evaluate(query), expected)
    finally:
        failure.unlink()
    case.action('server', 'Probe.command("reload")')
    case.expect('移除坏资源后恢复目录', lambda: case.server.evaluate(generation_expression),
                lambda value: value > healthy['generation'], expected={'generationGreaterThan': healthy['generation']})
    case.runtime.install('server')


def core_architecture_storage(case):
    script = (case.runtime.root / 'tools/probes/scripts/core_architecture.js').read_text(encoding='utf-8')
    case.server.install(script)
    fixtures = case.runtime.directory / 'architecture_legacy'
    fixtures.mkdir()
    case.action('server', f'Probe.archWriteLegacyState({json.dumps(str(fixtures))})')
    if 'client' in case.runtime.controls:
        case.runtime.stop('client')
    case.runtime.stop('server')
    world_data = case.runtime.directory / 'server/world/data'
    for fixture in fixtures.iterdir():
        shutil.copyfile(fixture, world_data / fixture.name)
    case.runtime.start('server')
    case.server.install(script)
    migrated = case.action('server', 'Probe.archReadLegacyState()')
    case.equal('旧档商人和淘洗点恢复有效截止时间',
               migrated['occupied'] and min(migrated['merchantDeadline'], migrated['shimmerDeadline']) > migrated['time'], True)
    case.runtime.stop('server')
    case.runtime.start('server')
    case.server.install(script)
    restored = case.action('server', 'Probe.archReadLegacyState()')
    deadlines = ('merchantDeadline', 'shimmerDeadline')
    case.check('restored-deadlines', {key: restored[key] for key in deadlines}, {key: migrated[key] for key in deadlines})

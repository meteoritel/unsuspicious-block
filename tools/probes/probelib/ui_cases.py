"""UI 回归场景只描述目标、操作和结果；输入、几何及证据复用既有 Case。"""
import json

from .autojs import ProbeError


def _nodes(case, **selector):
    return [node for node in case.stable_layout()['nodes']
            if all(node[key] == value for key, value in selector.items())]


def _node(case, selector):
    values = _nodes(case, **selector)
    if len(values) != 1:
        raise ProbeError(f'选择器需要唯一目标: {selector}; 匹配={values}')
    return values[0]


def _label(case, key):
    return case.client.evaluate(f'Probe.label({json.dumps(key)})')


def _control(case, key):
    return dict(kind='CONTROL', name=_label(case, key))


def _key(case, code, modifiers=0):
    case.action('client', f'Probe.key({code},{modifiers})')


def _tab_to(case, selector):
    # 通过普通 Tab 遍历；不直接设置焦点或调用 reveal，因而可验证正文自动滚入。
    limit = len(case.stable_layout()['nodes']) + 1
    for _ in range(limit):
        node = _node(case, selector)
        if node['focused']:
            return node
        _key(case, 258)
    raise AssertionError(f'Tab 无法到达: {selector}')


def _fully_visible(node):
    clip = node['clip']
    return clip['width'] > 0 and clip['height'] == node['height'] and clip['y'] == node['y']


def member_tree(case, taken, table):
    from .cases import open_log_list, wait_for
    open_log_list(case)
    entry_key = 'log/' + taken['logs'][-1]['id']
    detail_key = 'log/coordinates/' + taken['logs'][-1]['id']
    case.click(entry_key)
    case.equal('日志条目真实输入进入详情', bool(_nodes(case, key=detail_key)), True)
    case.screenshot('detail-member')
    case.click('journal/log-back')
    case.equal('详情退出后活动布局恢复列表', bool(_nodes(case, key=entry_key)), True)

    case.click('journal/search')
    _key(case, 258)
    _key(case, 258, 1)
    case.equal('Tab 与 Shift+Tab 返回搜索字段', _node(case, {'key':'journal/search'})['focused'], True)
    case.click('journal/tab/log')
    before = _node(case, {'key':'journal/search'})['value']
    case.action('client', 'Probe.text("z")')
    case.equal('离焦搜索不接收普通字符', _node(case, {'key':'journal/search'})['value'], before)

    pressed = case.action('client', 'Probe.press("journal/tab/archaeology")')
    case.equal('书签按下等待配对释放', bool(_nodes(case, key=entry_key)), True)
    width = 1100 if case.stable_layout()['window'][0] == 1000 else 1000
    case.action('client', f'Probe.resize({width},700)')
    wait_for(lambda: case.client.evaluate('Probe.layout().window'), lambda size: size == [width, 700])
    case.stable_layout()
    case.action('client', f'Probe.pointer("release",{json.dumps(pressed)})')
    case.equal('resize 后旧按压释放不切页', bool(_nodes(case, key=entry_key)), True)

    case.click('journal/log-clear-table')
    case.equal('模态接管实际成员布局', bool(_nodes(case, key='journal/tab/log')), False)
    case.action('client', 'Probe.text("z")')
    _key(case, 256)
    case.equal('关闭模态恢复根成员', bool(_nodes(case, key='journal/tab/log')), True)
    case.equal('取消模态不改变日志', case.server.evaluate(f'Probe.journal({json.dumps(table)}).logs'), taken['logs'])
    case.equal('模态输入不修改底层搜索', _node(case, {'key':'journal/search'})['value'], before)

    case.click('journal/tab/scenario')
    case.screenshot('scenario-member')
    case.click('journal/tab/archaeology')
    keys = [node['key'] for node in case.stable_layout()['nodes'] if node['key']]
    case.equal('当前成员布局业务 key 唯一', len(keys) == len(set(keys)), True)
    case.screenshot('archaeology-member')
    case.click('journal/tab/log')


def form_bodies(case, table):
    retention = 'screen.unsuspiciousblock.archaeology_journal.log_retention.'
    from .cases import open_log_list, wait_for
    case.action('client', 'Probe.guiScale(2)')
    case.action('client', 'Probe.resize(1100,720)')
    open_log_list(case)
    case.click(_control(case, retention+'open_tooltip'))
    fields = _nodes(case, kind='TEXT_INPUT')
    case.equal('保留页装配真实数字字段', len(fields), 2)
    first = dict(kind='TEXT_INPUT', name=_label(case, retention+'limit_label'))
    case.click(first)
    case.action('client', 'Probe.replaceText("42x")')
    case.equal('数字过滤拒绝非数字字符', bool(_nodes(case, kind='TEXT_INPUT', value='42')), True)
    case.action('client', 'Probe.resize(640,480)')
    second = dict(kind='TEXT_INPUT', name=_label(case, retention+'keep_recent_label'))
    prune = _control(case, retention+'prune_now')
    wait_for(lambda: case.client.evaluate('Probe.layout().window'), lambda size: size == [640, 480])
    case.equal('窄窗动作需要正文揭示', _fully_visible(_node(case, prune)), False)
    current = _tab_to(case, prune)
    case.equal('Tab 自动揭示正文外动作', _fully_visible(current), True)
    case.screenshot('retention-narrow')
    case.action('client', 'Probe.resize(1100,720)')
    case.click(first)
    case.action('client', 'Probe.replaceText("2")')
    case.click(_control(case, retention+'apply_limit'))
    limit_query = ('Probe.type("com.meteorite.unsuspiciousblock.core.world.JournalLogStorage")'
                   '.getPlayerState(Probe.server(),Probe.player().getUUID())'
                   f'.getTable(Probe.id({json.dumps(table)})).getRetentionLimit()')
    limit = case.expect('保留策略提交', lambda: case.server.evaluate(limit_query), lambda value: value == 2, expected=2)
    case.equal('自动保留提交到服务端', limit, 2)
    case.click(_control(case, retention+'open_tooltip'))
    case.click(first)
    case.action('client', 'Probe.replaceText("0")')
    case.click(_control(case, retention+'apply_limit'))
    case.equal('自动保留下限拒绝零', case.stable_layout()['screen'].endswith('JournalLogRetentionScreen'), True)
    case.equal('非法提交保持服务端保留值', case.server.evaluate(limit_query), 2)
    # 一次性裁剪零合法，测试只清理隔离玩家的本表日志。
    case.click(second)
    case.action('client', 'Probe.replaceText("0")')
    case.click(_control(case, retention+'prune_now'))
    logs = case.expect('裁剪日志提交', lambda: case.server.evaluate(f'Probe.journal({json.dumps(table)}).logs'), lambda value: value == [], expected=[])
    case.equal('一次性裁剪允许零并执行对应请求', logs, [])

    case.action('server', 'Probe.command("op UsbProbe")')
    prefix = 'screen.unsuspiciousblock.loot_table_management.'
    case.click(_control(case, prefix+'open'))
    search = dict(kind='TEXT_INPUT', name=_label(case, prefix+'search'))
    case.click(search)
    case.action('client', 'Probe.replaceText("usb_probe")')
    wait_for(lambda: case.client.evaluate('Probe.layout()'), lambda value: any(node['key'] == table for node in value['nodes']))
    case.click(table)
    case.equal('管理列表直接输出 LIST', _node(case, {'key':'management/tables'})['kind'], 'LIST')
    name = dict(kind='TEXT_INPUT', name=_label(case, prefix+'localized_name'))
    language = dict(kind='TEXT_INPUT', name=_label(case, prefix+'language'))
    case.click(name)
    case.action('client', 'Probe.replaceText("Probe draft EN")')
    case.click(language)
    case.action('client', 'Probe.replaceText("zh_cn")')
    case.click(name)
    case.action('client', 'Probe.replaceText("Probe draft ZH")')
    case.click(language)
    case.action('client', 'Probe.replaceText("en_us")')
    case.equal('语言切换保留复合名称草稿', _node(case, name)['value'], 'Probe draft EN')
    case.action('client', 'Probe.resize(640,480)')
    field = _tab_to(case, name)
    case.equal('管理详情 Tab reveal', _fully_visible(field), True)
    case.equal('管理详情 resize 保留草稿', field['value'], 'Probe draft EN')
    case.screenshot('management-narrow')
    choose = _control(case, prefix+'choose_language')
    _tab_to(case, choose)
    _key(case, 257)
    case.equal('语言模态贡献真实列表', bool(_nodes(case, key='management/languages')), True)
    case.equal('语言模态遮蔽管理列表', bool(_nodes(case, key='management/tables')), False)
    layout = case.stable_layout()
    case.equal('窄窗模态与按钮保持屏内可达', all(
        node['x'] >= 0 and node['y'] >= 0
        and node['x'] + node['width'] <= layout['width']
        and node['y'] + node['height'] <= layout['height']
        for node in layout['nodes'] if node['kind'] in ('MODAL', 'CONTROL')), True)
    case.screenshot('language-modal')
    _key(case, 256)
    case.equal('退出语言模态恢复管理列表', bool(_nodes(case, key='management/tables')), True)
    case.action('client', 'Probe.resize(1100,720)')
    case.click(_control(case, 'gui.back'))

    case.client.install((case.runtime.root / 'tools/probes/scripts/ui_scenarios.js').read_text(encoding='utf-8'))
    original = case.action('client', 'Probe.openConfiguration()')
    numeric = [node for node in _nodes(case, kind='TEXT_INPUT') if node['text'].isdigit()][-1]
    selector = dict(kind='TEXT_INPUT', name=numeric['name'])
    _tab_to(case, selector)
    case.action('client', 'Probe.replaceText("invalid")')
    case.click(_control(case, 'unsuspiciousblock.config.save'))
    case.equal('配置保存校验失败保留页面与草稿', _node(case, selector)['value'], 'invalid')
    case.equal('配置校验失败保持实际配置', case.client.evaluate('Probe.configurationValues()'), original)
    case.action('client', 'Probe.resize(640,480)')
    field = _tab_to(case, selector)
    case.equal('窄窗配置 Tab reveal', _fully_visible(field), True)
    case.equal('配置 resize 保留无效草稿', field['value'], 'invalid')
    case.screenshot('configuration-validation')
    case.click(_control(case, 'unsuspiciousblock.config.reset'))
    case.equal('重置恢复字段默认草稿', _node(case, selector)['value'], numeric['value'])
    case.equal('重置只修改草稿', case.client.evaluate('Probe.configurationValues()'), original)
    case.click(_control(case, 'gui.cancel'))
    case.equal('取消不保存配置', case.client.evaluate('Probe.configurationValues()'), original)
    case.equal('配置取消返回原父页面', case.stable_layout()['screen'].endswith('ArchaeologyJournalScreen'), True)
    case.action('client', 'Probe.resize(1100,720)')

"""猫族关系与淘洗的真实交互场景。"""
import json

from .cases import wait_for
from .journal_cases import journal_snapshot, new_logs


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
    case.check('cat-ledger-states', actual, expected)


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
    case.check('bound-carrier-owner', bound['boxedOwner'], case.server.evaluate('Probe.ready().uuid'))
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
    case.check('feed-cooldown', final['bond'], 5)


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
    case.check('panning-loot', entries[0]['actual'], {'usb_sig|PLAIN|minecraft:gold_nugget|': 2})
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

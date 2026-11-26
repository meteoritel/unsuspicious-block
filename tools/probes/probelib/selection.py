"""选择用例并按启动需求组织隔离会话。"""
from collections.abc import Callable
from dataclasses import dataclass
from itertools import groupby

from . import cases, journal_cases, world_cases
from .runtime import LaunchNeeds, WITH_CLIENT
from .verdicts import Check


PACK = 'pack.mcmeta'
SCAN = 'data/usb_probe/loot_table/archaeology/scan.json'
COMPLETE = 'data/usb_probe/loot_table/archaeology/complete.json'
FORMS = 'data/usb_probe/loot_table/archaeology/ui_forms.json'
RIVER = 'data/unsuspiciousblock/loot_table/gameplay/panning/river.json'
TRADES = 'data/usb_probe/merchant_cat_trades/architecture.json'
CATALOG_FIXTURES = (
    SCAN,
    'data/usb_probe/loot_table/archaeology/loading_parent.json',
    'data/usb_probe/loot_table/loading_child.json',
)


@dataclass(frozen=True)
class ProbeSpec:
    execute: Callable[[cases.Case], None]
    surface: str
    purpose: str
    required: tuple[Check, ...]
    needs: LaunchNeeds = LaunchNeeds()


CASES = {
    'runtime-artifact': ProbeSpec(cases.runtime_artifact, 'server', '发布物与 Loader 身份',
                                  (Check('artifact-digest', '指定宿主发布物摘要', '服务端 Loader 加载路径的文件'),)),
    'scan-take-journal': ProbeSpec(journal_cases.scan_take_journal, 'gui', '扫描、取物、日志与镜像',
                                   (Check('scan-loot', '扫描夹具的固定种子与掉落', '服务端结算日志'),
                                    Check('scan-active-detail', '本次取物新增日志身份', '客户端活动详情')),
                                   LaunchNeeds(WITH_CLIENT, (SCAN,))),
    'journal-viewport': ProbeSpec(journal_cases.journal_viewport, 'gui', '窗口倍率、详情保持与输入命中',
                                  (Check('viewport-modal-resize', '缩放重新摆放当前确认框', '客户端活动输入布局'),
                                   Check('viewport-active-detail', '调整窗口前选定的日志身份', '客户端活动详情')),
                                  LaunchNeeds(WITH_CLIENT, (SCAN,))),
    'cat-ledger': ProbeSpec(world_cases.cat_ledger, 'model', '猫族关系、冷却与命数',
                            (Check('cat-ledger-states', '固定输入对应的猫族规则', 'Java 账本模型'),)),
    'cat-carried-binding': ProbeSpec(world_cases.cat_carried_binding, 'server', '携带扫描的绑定与写回',
                                      (Check('bound-carrier-owner', '已登录玩家的 UUID', '标本箱内信物绑定所有者'),),
                                      LaunchNeeds(WITH_CLIENT)),
    'cat-player-journey': ProbeSpec(world_cases.cat_player_journey, 'server', '真实喂食、羁绊及同步',
                                     (Check('feed-cooldown', '冷却内只累计首次喂食', '真实交互后的服务端羁绊'),),
                                     LaunchNeeds(WITH_CLIENT)),
    'panning-settlement': ProbeSpec(world_cases.panning_settlement, 'server', '长按淘洗及中断结算',
                                     (Check('panning-loot', '固定河流夹具掉落两个金粒', '服务端结算日志'),
                                      Check('panning-clock', '服务端接受的手、介质和时钟', '客户端淘洗快照'),
                                      Check('panning-target-lock', '切换命中目标并保持两点和工具状态', '服务端会话与结算'),
                                      Check('panning-tap', '短促右键触发起手后结束，不扣次数或耐久', '服务端会话与结算')),
                                     LaunchNeeds(WITH_CLIENT, (RIVER,), own_world=True)),
    'panning-generation': ProbeSpec(world_cases.panning_generation, 'server', '跨区块采样和特殊淘洗点奖励',
                                     (Check('panning-chunk-seam', '接缝夹具的有效河流水面 X=15', '真实落点采样'),
                                      Check('panning-special-reward', '冷却中只在最近未占用水面生成一次特殊点',
                                            '真实生成与维度账本'),
                                      Check('panning-ledger-expiry', '卸载登记项到期释放锚点并保留标记', '维度账本')),
                                     LaunchNeeds(own_world=True)),
    'journal-restart': ProbeSpec(journal_cases.journal_restart, 'gui', '重启与空客户端缓存恢复',
                                  (Check('restored-server', '重启前核对过的权威快照', '重启后的服务端存档'),
                                   Check('restored-client', '重启前核对过的权威快照', '空缓存客户端的同步状态')),
                                  LaunchNeeds(WITH_CLIENT, (COMPLETE,), own_world=True)),
    'simulation-reload': ProbeSpec(journal_cases.simulation_reload, 'gui', '模拟回执与代次过滤',
                                    (Check('simulation-rejection', '客户端使用未签发工具', '服务端拒绝回执'),
                                     Check('stale-samples', '旧代负载保持真实模拟样本数', '当前表输入结果'),
                                     Check('current-samples', '同代负载可更新样本数', '当前表输入结果')),
                                    LaunchNeeds(WITH_CLIENT, (SCAN,))),
    'journal-catalog-loading': ProbeSpec(journal_cases.journal_catalog_loading, 'gui', '静态目录、概率与阅读位置',
                                         (Check('static-server', '固定根表、子表及分类夹具', '服务端发布目录'),
                                          Check('static-client', '固定根表、子表及分类夹具', '客户端同步目录'),
                                          Check('individual-delivery', '单表结果早于共享回填', '同代客户端结果与服务端队列'),
                                          Check('individual-reading', '选中的具体日志详情', '单表回填后的客户端活动详情'),
                                          Check('shared-reading', '选中的具体日志详情', '共享回填后的客户端活动详情')),
                                         LaunchNeeds(WITH_CLIENT, CATALOG_FIXTURES)),
    'core-architecture-inventory': ProbeSpec(cases.core_architecture_inventory, 'server', '交付、采掘及到期规则',
                                              (Check('full-inventory-smithing', '满背包时保留锻造输入', '锻造菜单结算后的库存'),),
                                              LaunchNeeds(WITH_CLIENT, own_world=True)),
    'core-architecture-catalog': ProbeSpec(cases.core_architecture_catalog, 'server', '目录、组合条目与引用投影',
                                            (Check('ordered-loot', '组合条目、零权重、内联引用夹具', '实际掉落与目录投影'),),
                                            LaunchNeeds(fixtures=(PACK,), own_world=True)),
    'merchant-trade-pool': ProbeSpec(cases.merchant_trade_pool, 'server', '商人交易权重与无效条目',
                                     (Check('merchant-valid-offers', '固定种子和交易夹具', '真实商人报价'),),
                                     LaunchNeeds(fixtures=(TRADES,))),
    'core-architecture-storage': ProbeSpec(cases.core_architecture_storage, 'server', '旧档迁移与重启持久化',
                                            (Check('restored-deadlines', '第一次迁移核对的截止时间', '第二次重启读取的存档'),),
                                            LaunchNeeds(own_world=True)),
    'ui-member-tree': ProbeSpec(journal_cases.ui_member_tree, 'gui', '焦点、模态和活动输入树',
                                (Check('member-log-detail', '本次真实点击的日志身份', '客户端活动详情'),),
                                LaunchNeeds(WITH_CLIENT, (SCAN,))),
    'ui-form-bodies': ProbeSpec(journal_cases.ui_form_bodies, 'gui', '正文输入与保存',
                                (Check('retention-limit', '提交到界面的固定保留上限', '服务端保留配置'),),
                                LaunchNeeds(WITH_CLIENT, (FORMS,))),
    'creative-tab': ProbeSpec(cases.creative_tab, 'gui', '创造模式物品栏标签与物品',
                              (Check('creative-tab-items', '模组标签名称、图标与物品清单', 'NeoForge 创造模式注册表'),),
                              LaunchNeeds(WITH_CLIENT)),
    'recipe-card-export': ProbeSpec(cases.recipe_card_export, 'gui', 'JEI 配方和 PNG 导出',
                                    (Check('exported-files', '选中配方的预期输出路径', '写入磁盘的 PNG 文件'),),
                                    LaunchNeeds(WITH_CLIENT, recipe_cards=True)),
}

SUITES = {
    'core': ('runtime-artifact', 'scan-take-journal', 'journal-restart', 'panning-settlement', 'cat-player-journey'),
    'journal-catalog': ('journal-catalog-loading', 'simulation-reload'),
    'journal-ui': ('scan-take-journal', 'journal-viewport', 'ui-member-tree', 'ui-form-bodies'),
    'all': tuple(CASES),
}


def sessions(selected):
    def session_key(name):
        needs = CASES[name].needs
        return name if needs.own_world else needs

    plan = []
    for _, group in groupby(selected, session_key):
        names = list(group)
        plan.append((CASES[names[0]].needs, names))
    return plan


def describe_cases():
    return [
        dict(id=name, purpose=spec.purpose, surface=spec.surface,
             suites=[suite for suite, members in SUITES.items() if name in members],
             checks=[dict(id=check.id, basis=check.basis, source=check.source) for check in spec.required],
             **spec.needs.describe())
        for name, spec in CASES.items()
    ]

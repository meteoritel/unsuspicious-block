"""UI 图集分源子模块注册入口，允许整体合成与按来源单独预览打磨。"""
from __future__ import annotations

from . import book_chrome, catalog, log, scenario, screens_hud

ATLAS_SOURCES = (
    ('catalog', '目录工具栏、排序/筛选与目录树条目 (CatalogToolbar / CatalogPanel)', catalog.apply),
    ('log', '日志工具栏、条目徽章与通用基础图标 (LogToolbar / LogPanel / LogDetailPanel)', log.apply),
    ('scenario', '场景推导、状态角标与灯箱控件 (Scenario* / ItemGridPanel / ScenarioUi)', scenario.apply),
    ('book_chrome', '书本翻页箭头、侧签底板、按钮皮肤与外链 (ArchaeologyJournalScreen / JournalControls)', book_chrome.apply),
    ('screens_hud', '暗底独立界面与猫之恩惠 HUD (LootTableManagementScreen / PotteryWheelScreen / CatFavorHud)', screens_hud.apply),
)

__all__ = [
    'ATLAS_SOURCES',
    'book_chrome',
    'catalog',
    'log',
    'scenario',
    'screens_hud',
]

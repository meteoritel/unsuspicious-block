package com.meteorite.unsuspiciousblock.client.ui.layout;

import com.meteorite.meteosuikit.api.style.UiTextScheme;
import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalPalette;
import com.meteorite.unsuspiciousblock.client.ui.theme.JournalTextColors;
import net.minecraft.resources.ResourceLocation;

/** 考古笔记 GUI 所有布局参数集中定义，便于手动调整测试 */
public final class JournalLayout {
    public static final ResourceLocation BOOKMARK_TEXTURE = ResourceLocation.fromNamespaceAndPath(
            Constants.MOD_ID, "textures/gui/bookmark_tab.png");

    // —— 书页纹理 ——
    public static final int BOOK_X_SHIFT = -8; // 整体左移像素（给右侧书签留空间）
    public static final int TEXTURE_WIDTH = 384;
    public static final int TEXTURE_HEIGHT = 256;
    public static final int PAGE_WIDTH = 160;
    public static final int PAGE_HEIGHT = 224;
    public static final int LEFT_PAGE_X_OFFSET = 16;
    public static final int RIGHT_PAGE_X_OFFSET = 208;
    public static final int PAGE_Y_OFFSET = 16;

    // —— 搜索栏（收起态为图标按钮，展开态为输入框） ——
    public static final int SEARCH_QUICK_BAR_HEIGHT = 14; // 工具栏内容布局基准高度
    public static final int SEARCH_ICON_SIZE = 16;      // 放大镜图标按钮尺寸 (正方形)
    public static final int SORT_ICON_SIZE = 16;       // 排序图标按钮尺寸 (正方形)
    public static final int SEARCH_FIELD_WIDTH = 88;   // 展开态文本框宽度（不含图标按钮；缩短以为 hideLocked 按钮腾出空间）
    public static final int SEARCH_BAR_HEIGHT = SEARCH_ICON_SIZE; // 搜索框背景与搜索按钮等高
    public static final int TOOLBAR_GAP = 2;           // 图标按钮之间间距
    public static final int TOOLBAR_Y = 6;             // 工具栏距左页顶部偏移

    // —— 左侧目录 ——
    public static final int CATALOG_ROW_HEIGHT = 20;
    public static final int CATALOG_ROW_GAP = 1;
    // 目录列表从工具栏下方开始
    public static final int CATALOG_LIST_TOP = TOOLBAR_Y + SEARCH_QUICK_BAR_HEIGHT + 4;
    // 左页取消分页后，解锁进度条下移到页底，并将腾出的空间交给滚动列表。
    public static final int CATALOG_SUMMARY_Y = 207;
    public static final int CATALOG_LIST_BOTTOM = CATALOG_SUMMARY_Y - 4;
    public static final int CATALOG_LEFT_PAD = 4;
    public static final int CATALOG_X_OFFSET = 4;
    public static final int CATALOG_TEXTURE_WIDTH = 152;
    public static final int CATALOG_TEXTURE_HEIGHT = 76;

    // —— 右侧网格 ——
    public static final int GRID_CELLS_PER_ROW = 2;
    public static final int GRID_ROWS_PER_PAGE = 3;
    public static final int GRID_ITEMS_PER_PAGE = GRID_CELLS_PER_ROW * GRID_ROWS_PER_PAGE;
    public static final int GRID_CELL_WIDTH = 74;
    public static final int GRID_CELL_HEIGHT = 48;
    public static final int GRID_COLUMN_GAP = 2;
    // 网格页内容顶部：比工具栏行低 34px，为场景页头部（摘要行 + 按钮行）留位。
    // 只有网格页与场景页该用这个值；**没有头部的页面必须各用自己的上界**，否则会被头部一起推下去。
    public static final int GRID_TOP = TOOLBAR_Y + 34;
    /** 介绍页内容上界：该页没有场景页头部，因此与左页工具栏行同高。 */
    public static final int INTRO_TOP = TOOLBAR_Y;
    public static final int GRID_LEFT_PAD = 4;
    public static final int GRID_PAGE_INDICATOR_Y = 208;
    public static final int INTRO_PAGE_INDICATOR_Y = 208;
    public static final int LOG_PAGE_INDICATOR_Y = 208;
    public static final int SCENARIO_PAGE_INDICATOR_Y = 208;
    public static final int GRID_SEPARATOR_COLOR = JournalPalette.GRID_SEPARATOR;

    // —— 右侧日志页 ——
    // 与右页网格内容同起点，对齐左页工具栏行
    public static final int LOG_TOP = TOOLBAR_Y;
    // "最近发掘记录"标签
    public static final int LOG_LIST_LABEL_Y = LOG_TOP;
    // 日志工具栏为单行，列表起点与左侧目录内容对齐
    public static final int LOG_TOOLBAR_ICON_SIZE = 14;
    public static final int LOG_LIST_TOP = LOG_TOP + LOG_TOOLBAR_ICON_SIZE + 4;
    public static final int LOG_LIST_BOTTOM = LOG_PAGE_INDICATOR_Y - 8;
    public static final int LOG_ROW_HEIGHT = 28;

    // 日志条目精灵图集
    public static final int LOG_ENTRY_TEXTURE_WIDTH = 148;
    public static final int LOG_ENTRY_TEXTURE_HEIGHT = 78; // 3态 × 26px
    public static final int LOG_ENTRY_STATE_HEIGHT = 26;

    // 日志条目内容偏移
    public static final int LOG_ENTRY_ICON_SIZE = 16;
    public static final int LOG_ENTRY_ICON_GAP = 4;
    public static final int LOG_ENTRY_TEXT_WIDTH = 124;

    // 日志条目复制坐标按钮（使用统一轻量 9-slice 皮肤，置于第一行右侧）
    public static final int LOG_ENTRY_COPY_BTN_WIDTH = 14;
    public static final int LOG_ENTRY_COPY_BTN_HEIGHT = 10;
    public static final int LOG_ENTRY_COPY_BTN_RIGHT_PAD = 4;   // 按钮与条目背景右边缘的距离
    public static final int LOG_ENTRY_COPY_BTN_TOP_OFFSET = 2;  // 按钮距条目顶部偏移

    // 日志条目文字配色（统一引自语义色表与书页专属色）
    public static final int LOG_ENTRY_TIME_COLOR = JournalTextColors.NAME;
    public static final int LOG_ENTRY_DIM_POS_COLOR = UiTextScheme.PARCHMENT.label();
    public static final int LOG_ENTRY_NOTE_BADGE_BG_HOVER = JournalPalette.NOTE_BADGE_HOVER_BG;

    // 日志分组头
    public static final int LOG_GROUP_HEADER_HEIGHT = 14;   // 组头行高度
    public static final int LOG_GROUP_ICON_SIZE = LOG_TOOLBAR_ICON_SIZE; // 分组按钮图标尺寸
    public static final int LOG_GROUP_HEADER_COLOR = UiTextScheme.PARCHMENT.body();
    public static final int LOG_GROUP_HEADER_BG = JournalPalette.CARD_SUBTLE_LIGHT;
    public static final int LOG_GROUP_HEADER_BORDER = JournalPalette.BORDER_SOFT;

    // 日志列表选中态色条（强化选中识别）
    public static final int LOG_LIST_SELECTED_BAR_COLOR = JournalPalette.SELECTED_BAR;
    public static final int LOG_LIST_SELECTED_BAR_WIDTH = 2;          // 色条宽度

    // 日志排序工具栏
    public static final int LOG_SORT_ICON_SIZE = LOG_TOOLBAR_ICON_SIZE;
    public static final int LOG_TOOLBAR_GAP = 2;

    // —— 日志详情页 ——
    public static final int LOG_DETAIL_CARD_BG = JournalPalette.CARD_SUBTLE;
    public static final int LOG_DETAIL_CARD_BORDER = JournalPalette.BORDER_SOFT;
    public static final int LOG_DETAIL_BACK_BTN_SIZE = 14;        // 返回按钮（kit 控件）尺寸
    public static final int LOG_DETAIL_CARD_PAD = 4;              // 卡片内边距
    public static final int LOG_DETAIL_CARD_GAP = 4;              // 卡片之间间距
    public static final int LOG_DETAIL_COMPLETION_COLOR = JournalTextColors.COMPLETED;

    // Spacetime 卡片文字布局（无图标，标签+值两列）
    public static final int LOG_DETAIL_META_LABEL_GAP = 4;        // 标签与值间距
    public static final int LOG_DETAIL_META_LINE_GAP = 3;         // 元信息行间距
    public static final int LOG_DETAIL_META_ROWS = 6;             // 元信息行数（创建时间/更新时间/结构/群系/维度/坐标）

    // 文字配色：区分信息权重
    public static final int LOG_DETAIL_LABEL_COLOR = UiTextScheme.PARCHMENT.label();
    public static final int LOG_DETAIL_VALUE_COLOR = UiTextScheme.PARCHMENT.body();
    public static final int LOG_DETAIL_UNKNOWN_COLOR = UiTextScheme.PARCHMENT.hint();

    // 战利品角标颜色
    public static final int LOG_DETAIL_LOOT_BADGE_FULL_COLOR = JournalPalette.LOOT_BADGE_FULL;
    public static final int LOG_DETAIL_LOOT_BADGE_PARTIAL_COLOR = JournalPalette.LOOT_BADGE_PARTIAL;

    // 战利品卡片标题与图标网格的间距
    public static final int LOG_DETAIL_TITLE_TO_GRID_GAP = 6;

    // 战利品卡底部总进度条（统一尺寸与羊皮纸色板）
    public static final int LOG_DETAIL_PROGRESS_BAR_HEIGHT = 12;  // 进度条厚度（容纳文字）
    public static final int LOG_DETAIL_PROGRESS_BAR_GAP = 4;      // 进度条与图标区间距

    // —— 翻页按钮 ——
    public static final int PAGE_BUTTON_WIDTH = 23;
    public static final int PAGE_BUTTON_HEIGHT = 13;
    public static final int PAGE_BUTTON_CENTER_GAP = 32;

    // —— 书本左侧入口标签 ——
    public static final int SIDE_TAB_WIDTH = 24;
    public static final int SIDE_TAB_HEIGHT = 20;
    public static final int SIDE_TAB_OVERLAP = 4;        // 与书本封皮重叠，形成夹入书页的视觉效果
    public static final int SIDE_TAB_Y_OFFSET = 28;
    public static final int SIDE_TAB_GAP = 3;

    private JournalLayout() {}
}

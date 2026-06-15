package com.meteorite.unsuspiciousblock.client.ui.layout;

/** 考古手册 GUI 所有布局参数集中定义，便于手动调整测试 */
public final class JournalLayout {

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
    public static final int SEARCH_QUICK_BAR_HEIGHT = 14; // 收起态/展开态工具栏行高
    public static final int SEARCH_ICON_SIZE = 14;      // 放大镜图标按钮尺寸 (正方形)
    public static final int SORT_ICON_SIZE = 14;       // 排序图标按钮尺寸 (正方形)
    public static final int SEARCH_FIELD_WIDTH = 94;   // 展开态文本框宽度（不含图标按钮）
    public static final int SEARCH_BAR_HEIGHT = 12;    // 文本框自身高度
    public static final int TOOLBAR_GAP = 2;           // 图标按钮之间间距
    public static final int TOOLBAR_Y = 6;             // 工具栏距左页顶部偏移

    // —— 左侧目录 ——
    public static final int CATALOG_ROW_HEIGHT = 20;
    public static final int CATALOG_ROW_GAP = 3;
    // 目录列表从工具栏下方开始
    public static final int CATALOG_LIST_TOP = TOOLBAR_Y + SEARCH_QUICK_BAR_HEIGHT + 2;
    public static final int CATALOG_SUMMARY_Y = 190;
    public static final int CATALOG_PAGE_INDICATOR_Y = 208;
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
    public static final int GRID_CELL_HEIGHT = 56;
    public static final int GRID_COLUMN_GAP = 2;
    public static final int GRID_TOP = 28;
    public static final int GRID_LEFT_PAD = 4;
    public static final int GRID_PAGE_INDICATOR_Y = 208;
    public static final int GRID_SEPARATOR_COLOR = 0x40A0A0A0;

    // —— 右侧日志页 ——
    public static final int LOG_TOP = 28;
    // "最近发掘记录"标签
    public static final int LOG_LIST_LABEL_Y = LOG_TOP;
    // 日志条目列表区域
    public static final int LOG_LIST_TOP = LOG_TOP + 16;
    public static final int LOG_LIST_BOTTOM = GRID_PAGE_INDICATOR_Y - 8;
    public static final int LOG_ROW_HEIGHT = 28;
    public static final int LOG_SEPARATOR_COLOR = 0x40C8B090;

    // 日志条目精灵图集
    public static final int LOG_ENTRY_TEXTURE_WIDTH = 148;
    public static final int LOG_ENTRY_TEXTURE_HEIGHT = 78; // 3态 × 26px
    public static final int LOG_ENTRY_STATE_HEIGHT = 26;

    // 日志条目内容偏移
    public static final int LOG_ENTRY_ICON_SIZE = 16;
    public static final int LOG_ENTRY_ICON_GAP = 4;
    public static final int LOG_ENTRY_TEXT_WIDTH = 124;

    // 日志分组头
    public static final int LOG_GROUP_HEADER_HEIGHT = 14;   // 组头行高度
    public static final int LOG_GROUP_ICON_SIZE = 14;        // 分组按钮图标尺寸
    public static final int LOG_GROUP_HEADER_COLOR = 0x5A422C;  // 组头行文字颜色
    public static final int LOG_GROUP_HEADER_BG = 0x18A08060;   // 组头行背景色（复用详情卡片背景）
    public static final int LOG_GROUP_HEADER_BORDER = 0x30C8B090; // 组头行边框色（复用详情卡片边框）

    // 日志列表选中态色条（强化选中识别）
    public static final int LOG_LIST_SELECTED_BAR_COLOR = 0xC07B3E18; // 选中态左侧色条（红棕）
    public static final int LOG_LIST_SELECTED_BAR_WIDTH = 2;          // 色条宽度

    // 日志搜索/排序工具栏
    public static final int LOG_SEARCH_ICON_SIZE = 14;
    public static final int LOG_SORT_ICON_SIZE = 14;
    public static final int LOG_SEARCH_FIELD_WIDTH = 80;
    public static final int LOG_SEARCH_BAR_HEIGHT = 12;
    public static final int LOG_TOOLBAR_GAP = 2;

    // —— 日志详情页 ——
    public static final int LOG_DETAIL_CARD_BG = 0x18A08060;      // 卡片暖色半透明背景
    public static final int LOG_DETAIL_CARD_BORDER = 0x30C8B090;  // 卡片顶部边框色
    public static final int LOG_DETAIL_GHOST_OVERLAY = 0xB06B5B45; // 未获得战利品遮罩色
    public static final int LOG_DETAIL_BACK_BTN_PAD_X = 6;        // 返回按钮水平内边距
    public static final int LOG_DETAIL_BACK_BTN_PAD_Y = 2;        // 返回按钮垂直内边距
    public static final int LOG_DETAIL_CARD_PAD = 4;              // 卡片内边距
    public static final int LOG_DETAIL_CARD_GAP = 4;              // 卡片之间间距
    public static final int LOG_DETAIL_COMPLETION_COLOR = 0x3A8C3A; // 完成标志绿色

    // Spacetime 卡片图标键值对布局
    public static final int LOG_DETAIL_META_ICON_SIZE = 16;       // 元信息引导图标尺寸
    public static final int LOG_DETAIL_META_ICON_GAP = 4;         // 图标与文字间距
    public static final int LOG_DETAIL_META_LINE_GAP = 2;         // 元信息行间距
    public static final int LOG_DETAIL_META_ROWS = 4;             // 元信息行数（时间/结构/群系/维度·坐标）

    // 战利品角标颜色
    public static final int LOG_DETAIL_LOOT_BADGE_PARTIAL_COLOR = 0xFFB08C00; // 部分获得角标（黄褐）

    // 战利品卡底部总进度条
    public static final int LOG_DETAIL_PROGRESS_BAR_HEIGHT = 4;   // 进度条厚度
    public static final int LOG_DETAIL_PROGRESS_BAR_BG = 0x405A422C; // 进度条底色
    public static final int LOG_DETAIL_PROGRESS_BAR_FILL = 0xC0A08060; // 进度条前景（未完成暖棕）
    public static final int LOG_DETAIL_PROGRESS_BAR_GAP = 4;      // 进度条与图标区/文字间距

    // —— 翻页按钮 ——
    public static final int PAGE_BUTTON_WIDTH = 23;
    public static final int PAGE_BUTTON_HEIGHT = 13;
    public static final int PAGE_BUTTON_CENTER_GAP = 32;

    private JournalLayout() {}
}

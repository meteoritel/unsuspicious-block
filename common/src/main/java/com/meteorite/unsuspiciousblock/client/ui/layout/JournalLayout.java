package com.meteorite.unsuspiciousblock.client.ui.layout;

/** 考古手册 GUI 所有布局参数集中定义，便于手动调整测试 */
public final class JournalLayout {

    // —— 书页纹理 ——
    public static final int TEXTURE_WIDTH = 384;
    public static final int TEXTURE_HEIGHT = 256;
    public static final int PAGE_WIDTH = 160;
    public static final int PAGE_HEIGHT = 224;
    public static final int LEFT_PAGE_X_OFFSET = 16;
    public static final int RIGHT_PAGE_X_OFFSET = 208;
    public static final int PAGE_Y_OFFSET = 16;

    // —— 左侧目录 ——
    public static final int CATALOG_ROW_HEIGHT = 20;
    public static final int CATALOG_ROW_GAP = 3;
    public static final int CATALOG_TITLE_Y = 6;
    public static final int CATALOG_LIST_TOP = 28;
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
    public static final int LOG_FIRST_UNLOCK_VALUE_Y = LOG_TOP + 12;
    public static final int LOG_FIRST_UNLOCK_TRIGGER_Y = LOG_TOP + 24;
    public static final int LOG_LIST_LABEL_Y = LOG_TOP + 40;
    public static final int LOG_LIST_TOP = LOG_TOP + 54;
    public static final int LOG_LIST_BOTTOM = GRID_PAGE_INDICATOR_Y - 8;
    public static final int LOG_ROW_HEIGHT = 38;

    // —— 翻页按钮 ——
    public static final int PAGE_BUTTON_WIDTH = 23;
    public static final int PAGE_BUTTON_HEIGHT = 13;
    public static final int PAGE_BUTTON_CENTER_GAP = 32;

    private JournalLayout() {}
}

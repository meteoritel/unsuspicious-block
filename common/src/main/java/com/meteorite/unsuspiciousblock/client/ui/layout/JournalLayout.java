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
    public static final int CATALOG_ROW_HEIGHT = 22;
    public static final int CATALOG_TITLE_Y = 6;
    public static final int CATALOG_LIST_TOP = 28;
    public static final int CATALOG_LIST_BOTTOM_PAD = 6;
    public static final int CATALOG_LEFT_PAD = 4;
    public static final int CATALOG_SCROLLBAR_WIDTH = 4;
    public static final int CATALOG_TEXTURE_WIDTH = 160;
    public static final int CATALOG_TEXTURE_HEIGHT = 66;
    public static final int CATALOG_SCROLLBAR_MIN_HANDLE = 16;

    // —— 右侧网格 ——
    public static final int GRID_CELLS_PER_ROW = 3;
    public static final int GRID_ROWS_PER_PAGE = 3;
    public static final int GRID_ITEMS_PER_PAGE = GRID_CELLS_PER_ROW * GRID_ROWS_PER_PAGE;
    public static final int GRID_CELL_WIDTH = 50;
    public static final int GRID_CELL_HEIGHT = 64;
    public static final int GRID_TOP = 32;
    public static final int GRID_TITLE_Y = 6;
    public static final int GRID_PROGRESS_Y = 18;
    public static final int GRID_PAGE_INDICATOR_Y = 216;
    public static final int GRID_SEPARATOR_COLOR = 0x40A0A0A0;

    // —— 翻页按钮 ——
    public static final int PAGE_BUTTON_SIZE = 18;
    public static final int PAGE_BUTTON_BOTTOM_PAD = 4;

    private JournalLayout() {}
}

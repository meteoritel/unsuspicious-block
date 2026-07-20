package com.meteorite.unsuspiciousblock.client.state;

/**
 * 标本箱快速交互的客户端滚动选择状态。
 * 存储全局选中的内部槽位索引，供 tooltip 高亮与服务端取出时引用。
 */
public final class SpecimenBoxScrollState {
    private static int selectedInnerSlot = -1;

    private SpecimenBoxScrollState() {
    }

    // -1 表示"取最后一个非空槽位"
    public static int getSelectedInnerSlot() {
        return selectedInnerSlot;
    }

    public static void setSelectedInnerSlot(int slot) {
        selectedInnerSlot = slot;
    }

    // 客户端断开连接时重置
    public static void reset() {
        selectedInnerSlot = -1;
    }
}
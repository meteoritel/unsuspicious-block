package com.meteorite.unsuspiciousblock.client.ui.journal;

import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 阅读目标及内容身份；完整快照可携带批选，持久投影明确去除临时交互。 */
public record JournalReadingState(@Nullable ResourceLocation table, RightPageContainer.ReadingState rightPage) {
    /** 初次阅读的默认状态。 */
    public static JournalReadingState empty() { return new JournalReadingState(null, RightPageContainer.ReadingState.empty()); }
    /** 跨普通重开保留阅读，批选只属于完整内存快照。 */
    public JournalReadingState persistent() {
        var right = rightPage;
        return new JournalReadingState(table, new RightPageContainer.ReadingState(right.tab(), right.pages(),
                right.activeItemTag(), right.logDetail(), right.logEntryId(), false, List.of(), right.conditionOffsets()));
    }
    /** 页号具名表达，恢复时由实际内容钳制上界。 */
    public record PagePositions(int intro, int grid, int log, int logDetail) {
        /** 外部负页号按首页恢复。 */
        public PagePositions { intro = Math.max(0, intro); grid = Math.max(0, grid); log = Math.max(0, log); logDetail = Math.max(0, logDetail); }
        /** 尚未阅读时各页均在首页。 */
        public static PagePositions empty() { return new PagePositions(0, 0, 0, 0); }
    }
}

package com.meteorite.unsuspiciousblock.client.ui.journal;

import com.meteorite.unsuspiciousblock.client.ui.panel.RightPageContainer;
import com.meteorite.unsuspiciousblock.client.ui.journal.JournalReadingState.PagePositions;
import com.meteorite.unsuspiciousblock.core.persistence.migration.MigrationVersions;
import com.meteorite.unsuspiciousblock.core.persistence.migration.NbtMigration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** 客户端偏好的阅读投影与格式演进；保留原场景偏移键及根表/tab 编码位置。 */
public final class JournalReadingStateCodec {
    private static final String VERSION = "uiPreferencesVersion";
    private static final int CURRENT_VERSION = 1;
    private static final int MAX_OFFSETS = 512;
    private static final NbtMigration MIGRATION = new NbtMigration(
            new MigrationVersions("笔记 UI 偏好", 0, CURRENT_VERSION), VERSION,
            Map.of(0, tag -> tag.put("reading", new CompoundTag())));
    private JournalReadingStateCodec() {}
    /** 在输入副本中执行连续迁移，失败时保持原输入。 */
    public static CompoundTag migrate(CompoundTag source) { var next = source.copy(); MIGRATION.migrate(next); return next; }
    /** 当前格式读取；ID 在本地文件边界校验，页号上界由面板决定。 */
    public static JournalReadingState read(CompoundTag root) {
        var tag = root.getCompound("reading");
        var pages = new PagePositions(tag.getInt("introPage"), tag.getInt("gridPage"), tag.getInt("logPage"), tag.getInt("logDetailPage"));
        RightPageContainer.Tab tab;
        try { tab = RightPageContainer.Tab.valueOf(root.getString("lastRightPageTab")); }
        catch (IllegalArgumentException exception) { tab = RightPageContainer.Tab.INTRO; }
        UUID entry = entryId(tag);
        Map<String, Integer> offsets = new LinkedHashMap<>();
        var saved = root.getCompound("panels").getCompound("scenario").getCompound("conditionOffsets");
        for (String key : saved.getAllKeys()) {
            if (offsets.size() >= MAX_OFFSETS) break;
            if (ResourceLocation.tryParse(key) != null && saved.contains(key, Tag.TAG_INT)) offsets.put(key, Math.max(0, saved.getInt(key)));
        }
        return new JournalReadingState(id(root, "lastSelectedTable"), new RightPageContainer.ReadingState(tab, pages,
                id(tag, "activeItemTag"), tag.getBoolean("logDetail"), entry, false, List.of(), offsets));
    }
    private static @Nullable UUID entryId(CompoundTag tag) {
        if (!tag.contains("logEntryId", Tag.TAG_STRING)) return null;
        try { return UUID.fromString(tag.getString("logEntryId")); }
        catch (IllegalArgumentException exception) { return null; }
    }
    private static @Nullable ResourceLocation id(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString(key)) : null;
    }
    /** 写入同一次捕获的表、tab 和分页；场景偏移保留原键，批选不进入磁盘。 */
    public static void write(CompoundTag root, JournalReadingState state) {
        root.putInt(VERSION, CURRENT_VERSION);
        if (state.table() != null) root.putString("lastSelectedTable", state.table().toString());
        else root.remove("lastSelectedTable");
        var right = state.rightPage();
        root.putString("lastRightPageTab", right.tab().name());
        var tag = new CompoundTag();
        tag.putInt("introPage", right.pages().intro()); tag.putInt("gridPage", right.pages().grid());
        tag.putInt("logPage", right.pages().log()); tag.putInt("logDetailPage", right.pages().logDetail());
        if (right.activeItemTag() != null) tag.putString("activeItemTag", right.activeItemTag().toString());
        if (right.logEntryId() != null) tag.putString("logEntryId", right.logEntryId().toString());
        tag.putBoolean("logDetail", right.logDetail());
        root.put("reading", tag);
        var offsets = new CompoundTag(); right.conditionOffsets().forEach(offsets::putInt);
        var panels = root.getCompound("panels").copy();
        var scenario = panels.getCompound("scenario").copy();
        scenario.put("conditionOffsets", offsets); panels.put("scenario", scenario); root.put("panels", panels);
    }
}

package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * 考古笔记解锁通知 Toast —— 解锁新表、新物品或单表 100% 完成时在右上角弹窗提示。
 * <p>
 * 同一类型的多个解锁条目会在同一个 Toast 框内轮换展示；
 * 展示时间到期后即使还有未轮换完的内容也会正常消失。
 */
public class JournalUnlockToast implements Toast {

    private static final int MIN_WIDTH = 176;
    private static final int MAX_WIDTH = 218;
    private static final int TOAST_HEIGHT = 38;
    private static final int SOCKET_Y = 7;
    private static final int TITLE_Y = 8;
    private static final int BADGE_Y = 7;
    private static final int DESCRIPTION_Y = 20;

    // 轮换间隔自适应区间（毫秒）：队列少时从容阅读，集中解锁时适度加快
    private static final long BASE_ROTATION_INTERVAL = 1100L;
    private static final long MIN_ROTATION_INTERVAL = 700L;
    // 基础展示时长与合并延展上限（毫秒）
    private static final long BASE_DISPLAY_TIME = 5000L;
    private static final long MERGE_EXTENSION_TIME = 1000L;
    private static final long MAX_DISPLAY_TIME = 8000L;

    private static final String TABLE_TOKEN = "journal_table_unlocks";
    private static final String ITEM_TOKEN = "journal_item_unlocks";
    private static final String COMPLETION_TOKEN = "journal_table_completion";

    private static final Component TABLE_UNLOCK_TITLE =
            Component.translatable("toast.unsuspiciousblock.journal_unlock.title");
    private static final Component COMPLETION_TITLE =
            Component.translatable("toast.unsuspiciousblock.journal_completion.title");
    // 物品解锁有自己的标题：原来复用表解锁标题，会把"新物品"说成"表更新"（I-13）
    private static final Component ITEM_UNLOCK_TITLE =
            Component.translatable("toast.unsuspiciousblock.journal_unlock.item_title");

    private final Queue<Entry> entries = new ArrayDeque<>();
    private final Object token;
    private final Component title;
    private final ToastFrame.Tone tone;
    private Entry displayedEntry;
    private UiIcon displayedIcon;
    private String displayedDescription = "";
    private Font measuredFont;
    private Language language;
    private int currentWidth = MIN_WIDTH;
    private int lastMeasuredRemaining = -1;
    private long displayDurationMs = BASE_DISPLAY_TIME;
    private long startTime = -1L;
    private long lastRotationTime = -1L;

    /**
     * 单条解锁展示记录。
     */
    public record Entry(Component description, ItemStack icon) {
    }

    private JournalUnlockToast(Object token, Component title, ToastFrame.Tone tone) {
        this.token = token;
        this.title = title;
        this.tone = tone;
    }

    /**
     * 向此 Toast 追加一条条目；若 Toast 已在展示则按节奏轮换展示。
     */
    public void addEntry(Entry entry) {
        this.entries.add(entry);
    }

    @Override
    public int width() {
        return currentWidth;
    }

    @Override
    public int height() {
        return TOAST_HEIGHT;
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics guiGraphics,
                                       @NotNull ToastComponent toastComponent,
                                       long timeSinceLastVisible) {
        if (this.startTime < 0L) {
            this.startTime = timeSinceLastVisible;
            this.lastRotationTime = timeSinceLastVisible;
        }

        double multiplier = toastComponent.getNotificationDisplayTimeMultiplier();
        double totalDuration = Math.max(1.0D, this.displayDurationMs * multiplier);
        long totalElapsed = Math.max(0L, timeSinceLastVisible - this.startTime);

        if (this.entries.size() > 1) {
            long interval = computeRotationInterval(totalDuration, totalElapsed, this.entries.size());
            if (timeSinceLastVisible - this.lastRotationTime >= interval) {
                this.entries.poll();
                this.lastRotationTime = timeSinceLastVisible;
            }
        }

        Entry current = this.entries.peek();
        if (current == null) {
            return Visibility.HIDE;
        }

        Font font = toastComponent.getMinecraft().font;
        int remaining = this.entries.size() - 1;
        refreshLayoutMetrics(font, current, remaining);

        int width = width();
        int height = height();
        ToastFrame.paintBackground(guiGraphics, width, height, this.tone, totalElapsed);
        ToastFrame.paintIconSocket(guiGraphics, ToastFrame.PADDING_X, SOCKET_Y, this.tone, this.displayedIcon);

        int badgeReserved = ToastFrame.measureCountBadgeReservedWidth(font, remaining);
        int titleMaxWidth = Math.max(1, width - ToastFrame.TEXT_START_X - ToastFrame.TEXT_RIGHT_MARGIN - badgeReserved);
        String trimmedTitle = TextScroll.trimToWidth(font, this.title.getString(), titleMaxWidth);
        guiGraphics.drawString(font, trimmedTitle, ToastFrame.TEXT_START_X, TITLE_Y, this.tone.titleColor(), true);

        if (remaining > 0) {
            ToastFrame.paintCountBadge(
                    guiGraphics, font, width - ToastFrame.TEXT_RIGHT_MARGIN, BADGE_Y, remaining, this.tone);
        }

        int descMaxWidth = Math.max(1, width - ToastFrame.TEXT_START_X - ToastFrame.TEXT_RIGHT_MARGIN);
        String trimmedDescription = TextScroll.trimToWidth(font, this.displayedDescription, descMaxWidth);
        guiGraphics.drawString(
                font, trimmedDescription, ToastFrame.TEXT_START_X, DESCRIPTION_Y, this.tone.subtitleColor(), true);

        double remainingRatio = 1.0D - totalElapsed / totalDuration;
        ToastFrame.paintProgressBar(guiGraphics, width, height, remainingRatio, this.tone);

        return totalElapsed < totalDuration ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public @NotNull Object getToken() {
        return this.token;
    }

    // 宽度只做单调递增扩展，避免轮换变短时造成 ToastComponent 右对齐边缘抖动
    private void refreshLayoutMetrics(Font font, Entry current, int remaining) {
        Language currentLanguage = Language.getInstance();
        boolean contentChanged = this.displayedEntry != current || this.language != currentLanguage;
        if (contentChanged) {
            this.displayedEntry = current;
            this.language = currentLanguage;
            this.displayedIcon = new UiIcon.Item(resolveIconStack(current.icon()));
            this.displayedDescription = current.description().getString();
        }
        if (contentChanged || this.measuredFont != font || this.lastMeasuredRemaining != remaining) {
            this.measuredFont = font;
            this.lastMeasuredRemaining = remaining;
            int badgeReserved = ToastFrame.measureCountBadgeReservedWidth(font, remaining);
            int titleNeeded = ToastFrame.TEXT_START_X
                    + font.width(this.title.getString())
                    + badgeReserved
                    + ToastFrame.TEXT_RIGHT_MARGIN;
            int descNeeded = ToastFrame.TEXT_START_X
                    + font.width(this.displayedDescription)
                    + ToastFrame.TEXT_RIGHT_MARGIN;
            int desired = Math.clamp(Math.max(titleNeeded, descNeeded), MIN_WIDTH, MAX_WIDTH);
            this.currentWidth = Math.max(this.currentWidth, desired);
        }
    }

    private static ItemStack resolveIconStack(ItemStack entryIcon) {
        if (!entryIcon.isEmpty()) {
            return entryIcon;
        }
        if (ModItems.ARCHAEOLOGY_JOURNAL != null) {
            return new ItemStack(ModItems.ARCHAEOLOGY_JOURNAL);
        }
        return new ItemStack(Items.BOOK);
    }

    private static long computeRotationInterval(double totalDuration, long totalElapsed, int queueSize) {
        long remainingTime = Math.max(0L, Math.round(totalDuration) - totalElapsed);
        long adaptive = remainingTime / Math.max(1, queueSize);
        return Math.clamp(adaptive, MIN_ROTATION_INTERVAL, BASE_ROTATION_INTERVAL);
    }

    // 添加或合并 Toast：相同 token 的 Toast 会合并条目而非替换
    private static void addOrMerge(ToastComponent toastComponent, JournalUnlockToast newToast) {
        JournalUnlockToast existing = toastComponent.getToast(JournalUnlockToast.class, newToast.token);
        if (existing != null) {
            for (Entry entry : newToast.entries) {
                existing.addEntry(entry);
            }
            existing.displayDurationMs = Math.min(
                    MAX_DISPLAY_TIME, existing.displayDurationMs + MERGE_EXTENSION_TIME);
            return;
        }
        toastComponent.addToast(newToast);
    }

    /**
     * 批量添加多条战利品表解锁 Toast。
     */
    public static void addTableUnlocks(List<Component> tableNames) {
        if (tableNames.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            JournalUnlockToast toast = new JournalUnlockToast(TABLE_TOKEN, TABLE_UNLOCK_TITLE, ToastFrame.Tone.BRONZE);
            for (Component name : tableNames) {
                toast.addEntry(new Entry(name, ItemStack.EMPTY));
            }
            addOrMerge(mc.getToasts(), toast);
        }
    }

    /**
     * 批量添加多条物品解锁 Toast。
     */
    public static void addItemUnlocks(List<Entry> itemEntries) {
        if (itemEntries.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            JournalUnlockToast toast = new JournalUnlockToast(ITEM_TOKEN, ITEM_UNLOCK_TITLE, ToastFrame.Tone.BRONZE);
            for (Entry entry : itemEntries) {
                toast.addEntry(entry);
            }
            addOrMerge(mc.getToasts(), toast);
        }
    }

    /**
     * 添加战利品表 100% 完成 Toast，使用金框礼赞风格与古代金币图标。
     */
    public static void addTableCompletion(Component tableName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        JournalUnlockToast toast = new JournalUnlockToast(COMPLETION_TOKEN, COMPLETION_TITLE, ToastFrame.Tone.GOLD);
        ItemStack icon = ModItems.ANCIENT_COIN != null
                ? new ItemStack(ModItems.ANCIENT_COIN)
                : ItemStack.EMPTY;
        toast.addEntry(new Entry(tableName, icon));
        addOrMerge(mc.getToasts(), toast);
    }
}

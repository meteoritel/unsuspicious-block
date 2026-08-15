package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * 考古笔记解锁通知 Toast —— 解锁新表或新物品时在右上角弹窗提示。
 * 同一类型的多个解锁条目会在同一个 Toast 框内轮换展示；
 * 展示时间到期后即使还有未轮换完的内容也会正常消失。
 */
public class JournalUnlockToast implements Toast {

    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/advancement");
    // 单条内容轮换间隔（毫秒）
    private static final long ROTATION_INTERVAL = 500L;
    // Toast 总展示时间（毫秒），到期后即使还有条目也会消失
    private static final long DISPLAY_TIME = 5000L;
    private static final Component TABLE_UNLOCK_TITLE =
            Component.translatable("toast.unsuspiciousblock.journal_unlock.title");
    private static final Component COMPLETION_TITLE =
            Component.translatable("toast.unsuspiciousblock.journal_completion.title");

    private final Queue<Entry> entries = new ArrayDeque<>();
    private final Object token;
    private final Component title;
    private long startTime = -1L;
    private long lastRotationTime = -1L;

    // 解锁条目
    public record Entry(Component description, ItemStack icon) {
    }

    private JournalUnlockToast(Object token, Component title) {
        this.token = token;
        this.title = title;
    }

    /**
     * 向此 Toast 追加一条条目；若 Toast 已在展示则自动轮换到新条目。
     */
    public void addEntry(Entry entry) {
        this.entries.add(entry);
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics guiGraphics,
                                       @NotNull ToastComponent toastComponent,
                                       long timeSinceLastVisible) {
        if (this.startTime < 0L) {
            this.startTime = timeSinceLastVisible;
            this.lastRotationTime = timeSinceLastVisible;
        }

        // 检查是否需要轮换到下一条
        long elapsedSinceRotation = timeSinceLastVisible - this.lastRotationTime;
        if (elapsedSinceRotation >= ROTATION_INTERVAL && this.entries.size() > 1) {
            this.entries.poll();
            this.lastRotationTime = timeSinceLastVisible;
        }

        Entry current = this.entries.peek();
        if (current == null) {
            return Visibility.HIDE;
        }

        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());

        Font font = toastComponent.getMinecraft().font;

        // 标题
        guiGraphics.drawString(font, this.title, 30, 7, 0xFFFFFF, true);
        // 描述
        guiGraphics.drawString(font, current.description(), 30, 18, 0xFFFFD0, true);

        // 图标
        if (!current.icon().isEmpty()) {
            guiGraphics.renderItem(current.icon(), 8, 8);
        } else {
            guiGraphics.renderItem(new ItemStack(Items.BOOK), 8, 8);
        }

        // 轮换指示器：有多条待显示时，在右下角显示 "x/n"
        if (this.entries.size() > 1) {
            String indicator = "x" + this.entries.size();
            guiGraphics.drawString(font, indicator, this.width() - font.width(indicator) - 4, this.height() - 10, 0xAAAAAA, false);
        }

        // 总展示时间结束则隐藏
        double multiplier = toastComponent.getNotificationDisplayTimeMultiplier();
        long totalElapsed = timeSinceLastVisible - this.startTime;
        return totalElapsed < DISPLAY_TIME * multiplier ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public @NotNull Object getToken() {
        return this.token;
    }

    // 添加或合并 Toast：相同 token 的 Toast 会合并条目而非替换
    private static void addOrMerge(ToastComponent toastComponent, JournalUnlockToast newToast) {
        JournalUnlockToast existing = toastComponent.getToast(JournalUnlockToast.class, newToast.token);
        if (existing != null) {
            // 合并条目到已有 Toast
            for (Entry entry : newToast.entries) {
                existing.addEntry(entry);
            }
            return;
        }
        toastComponent.addToast(newToast);
    }

    // 便捷方法：批量添加多条表解锁 Toast
    public static void addTableUnlocks(List<Component> tableNames) {
        if (tableNames.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            JournalUnlockToast toast = new JournalUnlockToast(TABLE_TOKEN, TABLE_UNLOCK_TITLE);
            for (Component name : tableNames) {
                toast.addEntry(new Entry(name, ItemStack.EMPTY));
            }
            addOrMerge(mc.getToasts(), toast);
        }
    }

    // 便捷方法：批量添加多条物品解锁 Toast
    public static void addItemUnlocks(List<Entry> itemEntries) {
        if (itemEntries.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            JournalUnlockToast toast = new JournalUnlockToast(ITEM_TOKEN, TABLE_UNLOCK_TITLE);
            for (Entry entry : itemEntries) {
                toast.addEntry(entry);
            }
            addOrMerge(mc.getToasts(), toast);
        }
    }

    // 便捷方法：添加战利品表 100% 完成 Toast，图标为古代金币
    public static void addTableCompletion(Component tableName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        JournalUnlockToast toast = new JournalUnlockToast(COMPLETION_TOKEN, COMPLETION_TITLE);
        ItemStack icon = ModItems.ANCIENT_COIN != null
                ? new ItemStack(ModItems.ANCIENT_COIN)
                : ItemStack.EMPTY;
        toast.addEntry(new Entry(tableName, icon));
        addOrMerge(mc.getToasts(), toast);
    }

    // Token 常量：同一类型只有一个 Toast 实例
    private static final String TABLE_TOKEN = "journal_table_unlocks";
    private static final String ITEM_TOKEN = "journal_item_unlocks";
    private static final String COMPLETION_TOKEN = "journal_table_completion";
}
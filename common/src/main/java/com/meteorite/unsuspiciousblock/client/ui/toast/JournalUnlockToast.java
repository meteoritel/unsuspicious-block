package com.meteorite.unsuspiciousblock.client.ui.toast;

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

/**
 * 考古手册解锁通知 Toast —— 解锁新表或新物品时在右上角弹窗提示。
 * 参考原版 RecipeToast 模式实现。
 */
public class JournalUnlockToast implements Toast {

    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_TIME = 4000L;
    private static final Component TITLE_TEXT =
            Component.translatable("toast.unsuspiciousblock.journal_unlock.title");

    private final Component description;
    private final ItemStack icon;
    private final Object token;
    private long lastChanged;
    private boolean changed;

    // 解锁表
    public JournalUnlockToast(Component tableName, Object token) {
        this.description = tableName;
        this.icon = ItemStack.EMPTY;
        this.token = token;
    }

    // 解锁物品
    public JournalUnlockToast(Component itemName, ItemStack icon, Object token) {
        this.description = itemName;
        this.icon = icon;
        this.token = token;
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics guiGraphics, @NotNull ToastComponent toastComponent, long timeSinceLastVisible) {
        if (this.changed) {
            this.lastChanged = timeSinceLastVisible;
            this.changed = false;
        }

        guiGraphics.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());

        Font font = toastComponent.getMinecraft().font;

        // 标题
        guiGraphics.drawString(font, TITLE_TEXT, 30, 7, 0xFF4A3320, false);
        // 描述
        guiGraphics.drawString(font, this.description, 30, 18, 0xFF5A422C, false);

        // 图标
        if (!this.icon.isEmpty()) {
            guiGraphics.renderItem(this.icon, 8, 8);
        } else {
            // 表解锁用书本图标代替
            guiGraphics.renderItem(new ItemStack(Items.BOOK), 8, 8);
        }

        double multiplier = toastComponent.getNotificationDisplayTimeMultiplier();
        long elapsed = timeSinceLastVisible - this.lastChanged;
        return elapsed < DISPLAY_TIME * multiplier ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public @NotNull Object getToken() {
        return this.token;
    }

    // 添加或更新 Toast：如果相同token的Toast已存在则刷新，否则新建
    public static void addOrUpdate(ToastComponent toastComponent, JournalUnlockToast newToast) {
        JournalUnlockToast existing = toastComponent.getToast(JournalUnlockToast.class, newToast.token);
        if (existing != null) {
            existing.changed = true;
            return;
        }
        toastComponent.addToast(newToast);
    }

    // 便捷方法：添加表解锁 Toast
    public static void addTableUnlock(Component tableName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Object token = "journal_table";
            addOrUpdate(mc.getToasts(), new JournalUnlockToast(tableName, token));
        }
    }

    // 便捷方法：添加物品解锁 Toast
    public static void addItemUnlock(Component itemName, ItemStack icon) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Object token = "journal_item_" + System.identityHashCode(icon.getItem());
            addOrUpdate(mc.getToasts(), new JournalUnlockToast(itemName, icon, token));
        }
    }
}
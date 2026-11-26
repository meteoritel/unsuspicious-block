package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.meteosuikit.api.text.TextWrap;
import com.meteorite.unsuspiciousblock.core.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.core.cat.CatFavorAbility;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 猫族关系 Toast —— 提示首次建立关系以及阶段升降时获得或失去的恩惠。
 */
public class CatBondToast implements Toast {

    private static final int TOAST_WIDTH = 224;
    private static final int TOAST_HEIGHT = 56;
    private static final int SOCKET_Y = 14;
    private static final int TITLE_Y = 8;
    private static final int DETAIL_START_Y = 20;
    private static final int DETAIL_LINE_STEP = 10;
    private static final long DISPLAY_TIME = 6000L;
    private static final Object TOKEN = new Object();
    // 固定 Toast 高度对应的详情行数上限。
    private static final int MAX_DETAIL_LINES = 3;

    private final Component title;
    private final Component detail;
    private final ToastFrame.Tone tone;
    private final UiIcon icon;
    private Font measuredFont;
    private Language language;
    private List<FormattedCharSequence> lines = List.of();
    private long startTime = -1L;

    private CatBondToast(Component title, Component detail, ToastFrame.Tone tone) {
        this.title = title;
        this.detail = detail;
        this.tone = tone;
        this.icon = new UiIcon.Item(new ItemStack(
                ModItems.HAND_OF_CAT == null ? Items.CAT_SPAWN_EGG : ModItems.HAND_OF_CAT));
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics graphics,
                                      @NotNull ToastComponent toastComponent,
                                      long timeSinceLastVisible) {
        if (this.startTime < 0L) {
            this.startTime = timeSinceLastVisible;
        }
        long elapsed = Math.max(0L, timeSinceLastVisible - this.startTime);
        double multiplier = toastComponent.getNotificationDisplayTimeMultiplier();
        double totalDuration = Math.max(1.0D, DISPLAY_TIME * multiplier);

        int width = width();
        int height = height();
        ToastFrame.paintBackground(graphics, width, height, this.tone, elapsed);
        ToastFrame.paintIconSocket(graphics, ToastFrame.PADDING_X, SOCKET_Y, this.tone, this.icon);

        Font font = toastComponent.getMinecraft().font;
        int textMaxWidth = Math.max(1, width - ToastFrame.TEXT_START_X - ToastFrame.TEXT_RIGHT_MARGIN);
        String trimmedTitle = TextScroll.trimToWidth(font, this.title.getString(), textMaxWidth);
        graphics.drawString(font, trimmedTitle, ToastFrame.TEXT_START_X, TITLE_Y, this.tone.titleColor(), true);

        if (this.measuredFont != font || this.language != Language.getInstance()) {
            this.measuredFont = font;
            this.language = Language.getInstance();
            this.lines = TextWrap.fit(font, this.detail, textMaxWidth, MAX_DETAIL_LINES);
        }
        for (int index = 0; index < this.lines.size(); index++) {
            graphics.drawString(
                    font,
                    this.lines.get(index),
                    ToastFrame.TEXT_START_X,
                    DETAIL_START_Y + index * DETAIL_LINE_STEP,
                    this.tone.detailColor(),
                    true);
        }

        double remainingRatio = 1.0D - elapsed / totalDuration;
        ToastFrame.paintProgressBar(graphics, width, height, remainingRatio, this.tone);

        return elapsed < totalDuration ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public int width() {
        return TOAST_WIDTH;
    }

    @Override
    public int height() {
        return TOAST_HEIGHT;
    }

    @Override
    public @NotNull Object getToken() {
        return TOKEN;
    }

    /**
     * 弹出首次建立猫族关系的提示。
     */
    public static void showRelationshipEstablished() {
        Component title = Component.translatable("toast.unsuspiciousblock.cat_bond.established");
        Component detail = Component.translatable(
                "toast.unsuspiciousblock.cat_bond.gained", Component.translatable(CatFavorAbility.CAT_EYE.nameKey()));
        show(title, detail, ToastFrame.Tone.GOLD);
    }

    /**
     * 弹出猫族羁绊阶段升降的提示。
     */
    public static void showStageChange(CatBondStage oldStage, CatBondStage newStage, boolean livesCleared) {
        boolean increased = newStage.ordinal() > oldStage.ordinal();
        Component title = Component.translatable(
                increased ? "toast.unsuspiciousblock.cat_bond.stage_up"
                        : "toast.unsuspiciousblock.cat_bond.stage_down",
                Component.translatable(newStage.translationKey()));
        Component abilities = joinedAbilities(oldStage, newStage, increased);
        MutableComponent detail = Component.translatable(
                increased ? "toast.unsuspiciousblock.cat_bond.gained"
                        : "toast.unsuspiciousblock.cat_bond.lost",
                abilities);
        if (livesCleared) {
            detail.append(Component.literal(" "))
                    .append(Component.translatable("toast.unsuspiciousblock.cat_bond.lives_cleared"));
        }
        show(title, detail, increased ? ToastFrame.Tone.GOLD : ToastFrame.Tone.CRIMSON);
    }

    private static Component joinedAbilities(CatBondStage oldStage, CatBondStage newStage, boolean increased) {
        List<Component> names = new ArrayList<>();
        if (increased) {
            for (int i = oldStage.ordinal() + 1; i <= newStage.ordinal(); i++) {
                for (CatFavorAbility ability : CatFavorAbility.values()) {
                    if (ability.unlocksAt() == CatBondStage.values()[i]) {
                        names.add(Component.translatable(ability.nameKey()));
                    }
                }
            }
        } else {
            for (int i = oldStage.ordinal(); i > newStage.ordinal(); i--) {
                for (CatFavorAbility ability : CatFavorAbility.values()) {
                    if (ability.unlocksAt() == CatBondStage.values()[i]) {
                        names.add(Component.translatable(ability.nameKey()));
                    }
                }
            }
        }
        MutableComponent joined = Component.empty();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                joined.append(Component.translatable("toast.unsuspiciousblock.cat_bond.separator"));
            }
            joined.append(names.get(i));
        }
        return joined;
    }

    private static void show(Component title, Component detail, ToastFrame.Tone tone) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ToastComponent toasts = minecraft.getToasts();
        toasts.addToast(new CatBondToast(title, detail, tone));
    }
}

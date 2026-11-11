package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.unsuspiciousblock.core.cat.CatBondStage;
import com.meteorite.meteosuikit.api.text.TextScroll;
import com.meteorite.meteosuikit.api.text.TextWrap;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import com.meteorite.meteosuikit.api.geometry.UiRect;
import com.meteorite.meteosuikit.api.render.UiIcon;
import com.meteorite.meteosuikit.api.style.UiSurfaceSkin;
import com.meteorite.meteosuikit.api.style.UiControlStyle;
import net.minecraft.locale.Language;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 猫族关系 Toast——提示首次建立关系以及阶段升降时获得或失去的恩惠。
 */
public class CatBondToast implements Toast {
    private static final UiSurfaceSkin BACKGROUND = UiSurfaceSkin.panel(0xFF24232A, 0xFF796342, 0xFF494551);
    private static final long DISPLAY_TIME = 6000L;
    private static final Object TOKEN = new Object();
    // 固定 Toast 高度对应的详情行数上限。
    private static final int MAX_DETAIL_LINES = 3;

    private final Component title;
    private final Component detail;
    private final UiIcon icon;
    private Font measuredFont;
    private Language language;
    private List<FormattedCharSequence> lines = List.of();

    private CatBondToast(Component title, Component detail) {
        this.title = title;
        this.detail = detail;
        icon = new UiIcon.Item(new ItemStack(ModItems.HAND_OF_CAT == null ? Items.CAT_SPAWN_EGG : ModItems.HAND_OF_CAT));
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics graphics,
                                      @NotNull ToastComponent toastComponent,
                                      long timeSinceLastVisible) {
        BACKGROUND.paint(graphics, new UiRect(0, 0, width(), height()), UiControlStyle.State.NORMAL);
        Font font = toastComponent.getMinecraft().font;
        TextScroll.draw(graphics, font, title.getString(), 30, 7, width() - 36, 0xFFFFFF, false, 0, false);
        if (measuredFont != font || language != Language.getInstance()) {
            measuredFont = font; language = Language.getInstance();
            lines = TextWrap.fit(font, detail, width() - 36, MAX_DETAIL_LINES);
        }
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(font, lines.get(index), 30, 19 + index * 10, 0xFFFFD0, false);
        }
        icon.render(graphics, 8, 8);
        double multiplier = toastComponent.getNotificationDisplayTimeMultiplier();
        return timeSinceLastVisible < DISPLAY_TIME * multiplier ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public int width() {
        return 220;
    }

    @Override
    public int height() {
        return 52;
    }

    @Override
    public @NotNull Object getToken() {
        return TOKEN;
    }

    public static void showRelationshipEstablished() {
        Component title = Component.translatable("toast.unsuspiciousblock.cat_bond.established");
        Component detail = Component.translatable(
                "toast.unsuspiciousblock.cat_bond.gained", abilityName(CatBondStage.ACQUAINTED));
        show(title, detail);
    }

    public static void showStageChange(CatBondStage oldStage, CatBondStage newStage,
                                       boolean livesCleared) {
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
        show(title, detail);
    }

    private static Component joinedAbilities(CatBondStage oldStage, CatBondStage newStage,
                                             boolean increased) {
        List<Component> names = new ArrayList<>();
        if (increased) {
            for (int i = oldStage.ordinal() + 1; i <= newStage.ordinal(); i++) {
                names.add(abilityName(CatBondStage.values()[i]));
            }
        } else {
            for (int i = oldStage.ordinal(); i > newStage.ordinal(); i--) {
                names.add(abilityName(CatBondStage.values()[i]));
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

    private static Component abilityName(CatBondStage stage) {
        return switch (stage) {
            case ACQUAINTED -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.cat_eye");
            case CLOSE -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.deterrence");
            case TRUSTED -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.light_step");
            case FAVORED -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.soft_paws");
            case HONORED_GUEST -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.ancient_gift");
            case BEST_FRIEND -> Component.translatable("item.unsuspiciousblock.hand_of_cat.ability.best_friend_access");
        };
    }

    private static void show(Component title, Component detail) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ToastComponent toasts = minecraft.getToasts();
        toasts.addToast(new CatBondToast(title, detail));
    }
}

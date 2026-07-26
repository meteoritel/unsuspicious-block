package com.meteorite.unsuspiciousblock.client.ui.toast;

import com.meteorite.unsuspiciousblock.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 猫族关系 Toast——提示首次建立关系以及阶段升降时获得或失去的恩惠。
 */
public class CatBondToast implements Toast {
    private static final ResourceLocation BACKGROUND_SPRITE =
            ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final long DISPLAY_TIME = 6000L;
    private static final Object TOKEN = new Object();

    private final Component title;
    private final Component detail;

    private CatBondToast(Component title, Component detail) {
        this.title = title;
        this.detail = detail;
    }

    @Override
    public @NotNull Visibility render(@NotNull GuiGraphics graphics,
                                      @NotNull ToastComponent toastComponent,
                                      long timeSinceLastVisible) {
        graphics.blitSprite(BACKGROUND_SPRITE, 0, 0, this.width(), this.height());
        Font font = toastComponent.getMinecraft().font;
        graphics.drawString(font, this.title, 30, 7, 0xFFFFFF, true);
        List<net.minecraft.util.FormattedCharSequence> lines = font.split(this.detail, this.width() - 36);
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            graphics.drawString(font, lines.get(i), 30, 19 + i * 10, 0xFFFFD0, false);
        }
        ItemStack icon = ModItems.HAND_OF_CAT == null
                ? new ItemStack(Items.CAT_SPAWN_EGG)
                : new ItemStack(ModItems.HAND_OF_CAT);
        graphics.renderItem(icon, 8, 8);
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

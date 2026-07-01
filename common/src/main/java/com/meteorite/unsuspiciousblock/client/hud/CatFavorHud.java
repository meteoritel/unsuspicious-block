package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 猫之恩惠快捷栏 HUD——在快捷栏左侧渲染猫爪图标与恩惠值/九命命数，
 * 供玩家不打开物品栏即可快捷查看当前状态。仅当玩家背包含「猫之手」时显示。
 * 渲染入口由各平台客户端（Fabric HudRenderCallback / NeoForge RenderGuiEvent）调用。
 */
public final class CatFavorHud {

    private static final ResourceLocation CAT_FAVOR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/cat_favor.png");

    // 图标尺寸（16×16 原图）
    private static final int ICON_SIZE = 16;
    // HUD 相对快捷栏左边缘的偏移
    private static final int OFFSET_FROM_HOTBAR = 26;
    // 文本相对图标的偏移
    private static final int TEXT_X_OFFSET = ICON_SIZE + 2;

    private CatFavorHud() {
    }

    /**
     * 渲染 HUD。由平台客户端在帧渲染时调用。
     *
     * @param gui 平台提供的 GuiGraphics
     */
    public static void render(GuiGraphics gui) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.font == null) {
            return;
        }
        // 仅当背包存在猫之手时显示
        if (!hasHandOfCat(player)) {
            return;
        }
        int favor = HandOfCatClientState.getCachedFavor();
        int lives = HandOfCatClientState.getCachedNineLivesCount();

        // 快捷栏左边缘 = width/2 - 91；本 HUD 置于其左侧
        int hotbarLeft = minecraft.getWindow().getGuiScaledWidth() / 2 - 91;
        int hotbarTop = minecraft.getWindow().getGuiScaledHeight() - 22;
        int iconX = hotbarLeft - OFFSET_FROM_HOTBAR;
        int iconY = hotbarTop + 3;

        // 猫爪图标
        gui.blit(CAT_FAVOR_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

        Font font = minecraft.font;
        // 恩惠值（金色，显示在图标右侧）
        String favorText = String.valueOf(favor);
        int favorColor = 0xFFFFD700;
        gui.drawString(font, favorText, iconX + TEXT_X_OFFSET, iconY + 1, favorColor, true);

        // 九命命数（若 >0，显示在恩惠值下方，红色 "×N"）
        if (lives > 0) {
            String livesText = "×" + lives;
            gui.drawString(font, livesText, iconX + TEXT_X_OFFSET, iconY + 10, 0xFFFF5555, true);
        }
    }

    // 客户端侧检查背包是否存在猫之手
    private static boolean hasHandOfCat(LocalPlayer player) {
        if (ModItems.HAND_OF_CAT == null) {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModItems.HAND_OF_CAT) {
                return true;
            }
        }
        return false;
    }
}

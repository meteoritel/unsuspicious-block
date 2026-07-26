package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.CatBondStage;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.inventory.InventoryPresenceRegistry;
import com.meteorite.unsuspiciousblock.item.HandOfCatItem;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * 猫之恩惠快捷栏 HUD——在快捷栏左侧渲染猫爪图标与恩惠值/九命命数，
 * 供玩家不打开物品栏即可快捷查看当前状态。仅当玩家背包含「猫之手」时显示。
 * 渲染入口由各平台客户端（Fabric HudRenderCallback / NeoForge RenderGuiEvent）调用。
 * 恩惠值显示在图标左侧以避开快捷栏遮挡；封顶时图标叠加紫色呼吸 tint；
 * 九命命数以半尺寸数字角标显示在图标右下角。
 */
public final class CatFavorHud {

    private static final ResourceLocation CAT_FAVOR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/cat_favor.png");

    // 图标尺寸（16×16 原图）
    private static final int ICON_SIZE = 16;
    // HUD 相对快捷栏左边缘的偏移
    private static final int OFFSET_FROM_HOTBAR = 26;
    // 恩惠值文字相对图标的水平间距（文字在图标左侧）
    private static final int TEXT_GAP = 2;
    // 数字角标缩放比例（与物品堆叠数量角标一致）
    private static final float BADGE_SCALE = 1.0F;

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
        if (player == null) {
            return;
        }
        // 仅当背包存在猫之手时显示
        if (!HandOfCatClientState.isRelationshipEstablished() || !hasOwnedHandOfCat(player)) {
            return;
        }
        int favor = HandOfCatClientState.getCachedFavor();
        int lives = HandOfCatClientState.getCachedNineLivesCount();

        // 快捷栏左边缘 = width/2 - 91；本 HUD 置于其左侧
        int hotbarLeft = minecraft.getWindow().getGuiScaledWidth() / 2 - 91;
        int hotbarTop = minecraft.getWindow().getGuiScaledHeight() - 22;
        int iconX = hotbarLeft - OFFSET_FROM_HOTBAR;
        int iconY = hotbarTop + 3;

        CatBondStage stage = CatBondStage.fromBond(favor);
        int stageColor = stage.hudColor();
        float red = ((stageColor >> 16) & 0xFF) / 255.0F;
        float green = ((stageColor >> 8) & 0xFF) / 255.0F;
        float blue = (stageColor & 0xFF) / 255.0F;

        // 猫爪图标与数值使用当前关系阶段配色。
        RenderSystem.setShaderColor(red, green, blue, 1.0F);
        gui.blit(CAT_FAVOR_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

        // 封顶时叠加紫色呼吸 tint——纹理自身 alpha 作蒙版，无需操作 framebuffer
        if (favor >= CatFavorState.MAX_FAVOR) {
            long millis = Util.getMillis();
            // alpha 在 0.25~0.75 之间呼吸
            float breath = 0.5F + 0.25F * (float) Math.sin(millis / 350.0);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(red, green, blue, breath);
            gui.blit(CAT_FAVOR_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
            // 复位颜色与混合状态，避免污染后续渲染
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }

        Font font = minecraft.font;
        // 羁绊值显示在图标左侧，颜色与当前阶段一致。
        String favorText = String.valueOf(favor);
        int favorWidth = font.width(favorText);
        gui.drawString(font, favorText, iconX - TEXT_GAP - favorWidth, iconY + 1, stageColor, true);

        // 九命命数角标（青色，显示在图标右下角，半尺寸缩放）
        if (lives > 0) {
            drawCornerBadge(gui, font, iconX, iconY, String.valueOf(lives));
        }
    }

    // 在图标右下角绘制半尺寸数字角标——仿物品堆叠数量角标范式（pose scale + drawString）
    private static void drawCornerBadge(GuiGraphics gui, Font font, int iconX, int iconY,
                                        String text) {
        float textWidth = font.width(text);
        float textHeight = font.lineHeight;
        // 角标右下角对齐到图标右下角，留 1px 内边距
        float x = iconX + ICON_SIZE - textWidth * BADGE_SCALE - 1.0F;
        float y = iconY + ICON_SIZE - textHeight * BADGE_SCALE - 1.0F;

        gui.pose().pushPose();
        gui.pose().scale(BADGE_SCALE, BADGE_SCALE, 1.0F);
        // 缩放后坐标需除以缩放比例还原到字体坐标系
        gui.drawString(font, text, Math.round(x / BADGE_SCALE), Math.round(y / BADGE_SCALE), -12525360, true);
        gui.pose().popPose();
    }

    // 客户端侧检查背包（含标本箱等便携容器与饰品栏）是否存在猫之手
    private static boolean hasOwnedHandOfCat(LocalPlayer player) {
        if (ModItems.HAND_OF_CAT == null) {
            return false;
        }
        return InventoryPresenceRegistry.containsMatching(player,
                stack -> stack.getItem() == ModItems.HAND_OF_CAT
                        && HandOfCatItem.isBoundTo(stack, player.getUUID()));
    }
}

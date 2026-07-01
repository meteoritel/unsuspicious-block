package com.meteorite.unsuspiciousblock.client.hud;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.cat.state.CatFavorState;
import com.meteorite.unsuspiciousblock.client.state.HandOfCatClientState;
import com.meteorite.unsuspiciousblock.item.ModItems;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
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
 * 恩惠值显示在图标左侧以避开快捷栏遮挡；封顶时图标叠加附魔光效；
 * 九命命数以半尺寸数字角标显示在图标右下角。
 */
public final class CatFavorHud {

    private static final ResourceLocation CAT_FAVOR_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "textures/cat_favor.png");
    // 原版附魔光效纹理（64×64 重复纹理）
    private static final ResourceLocation ENCHANTED_GLINT_TEXTURE =
            ResourceLocation.parse("minecraft:textures/misc/enchanted_glint_item.png");

    // 图标尺寸（16×16 原图）
    private static final int ICON_SIZE = 16;
    // HUD 相对快捷栏左边缘的偏移
    private static final int OFFSET_FROM_HOTBAR = 26;
    // 恩惠值文字相对图标的水平间距（文字在图标左侧）
    private static final int TEXT_GAP = 2;
    // 数字角标缩放比例（与物品堆叠数量角标一致）
    private static final float BADGE_SCALE = 0.5F;
    // glint 纹理尺寸
    private static final int GLINT_TEXTURE_SIZE = 64;

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

        // 猫爪图标本体
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        gui.blit(CAT_FAVOR_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

        // 封顶时叠加附魔光效：紫色着色 + UV 流动偏移 + 呼吸 alpha
        if (favor >= CatFavorState.MAX_FAVOR) {
            renderEnchantmentGlint(gui, iconX, iconY);
        }

        Font font = minecraft.font;
        // 恩惠值（金色，显示在图标左侧，右对齐到 iconX - TEXT_GAP）
        String favorText = String.valueOf(favor);
        int favorWidth = font.width(favorText);
        gui.drawString(font, favorText, iconX - TEXT_GAP - favorWidth, iconY + 1, 0xFFFFD700, true);

        // 九命命数角标（青色，显示在图标右下角，半尺寸缩放）
        if (lives > 0) {
            drawCornerBadge(gui, font, iconX, iconY, String.valueOf(lives));
        }
    }

    // 在猫爪图标上叠加附魔光效——使用原版 glint 纹理，紫色着色，UV 随时间流动，亮度周期呼吸。
    // 仅覆盖原图非透明像素：先用猫爪纹理覆写 framebuffer 的 alpha 通道作遮罩，
    // 再以 DST_ALPHA 为源混合因子叠加 glint，使透明区域不受影响。
    private static void renderEnchantmentGlint(GuiGraphics gui, int iconX, int iconY) {
        long millis = System.currentTimeMillis();
        // UV 偏移：2 秒一圈，0~16 像素范围
        float offset = (millis % 2000L) / 2000.0F * 16.0F;
        // 亮度呼吸：0.40~0.78 之间（乘入 RGB 以驱动混合强度）
        float breath = 0.59F + 0.19F * (float) Math.sin(millis / 350.0);

        // === 第一步：用猫爪纹理覆写 framebuffer 的 alpha 通道 ===
        // 关闭混合、仅写 alpha 通道，使非透明处 alpha=1、透明处 alpha=0，不受背景干扰
        RenderSystem.disableBlend();
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        gui.blit(CAT_FAVOR_TEXTURE, iconX, iconY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        RenderSystem.colorMask(true, true, true, true);

        // === 第二步：渲染 glint，源因子取 DST_ALPHA——仅非透明像素叠加 ===
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(0.75F * breath, 0.55F * breath, breath, 1.0F);
        gui.blit(ENCHANTED_GLINT_TEXTURE, iconX, iconY, offset, offset,
                ICON_SIZE, ICON_SIZE, GLINT_TEXTURE_SIZE, GLINT_TEXTURE_SIZE);

        // 复位颜色与混合状态，避免污染后续渲染
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
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

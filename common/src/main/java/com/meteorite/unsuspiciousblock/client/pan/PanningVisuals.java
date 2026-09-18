package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.item.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

/*** 淘洗演出共用时钟：以每次使用进度驱动起手、摇洗与液面帧，避免多人共享贴图时钟。 */
public final class PanningVisuals {
    private static final float CYCLE_TICKS = 20.0F;
    private static final ResourceLocation PANNING_FRAME =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panning_frame");

    private PanningVisuals() {
    }

    // 两个平台都在客户端初始化时注册；停止使用后自动回到空盘。
    // 遍历全部淘盘，新增淘盘无需改动这里。
    public static void register() {
        for (Supplier<Item> pan : ModItems.PAN_ITEMS) {
            ItemProperties.register(pan.get(), PANNING_FRAME, (stack, level, entity, seed) -> {
                if (entity == null || !entity.isUsingItem() || entity.getUseItem() != stack) {
                    return 0.0F;
                }
                float elapsed = entity.getTicksUsingItem();
                if (elapsed < fillTicks(entity) * 0.5F) {
                    return 0.0F;
                }
                // 相位从中间液面起步；原图从上到下依次为左、中、右、中。
                int frame = Mth.floor(phase(entity, elapsed) / Mth.TWO_PI * 4.0F + 0.5F) % 4;
                HumanoidArm arm = entity.getUsedItemHand() == InteractionHand.MAIN_HAND
                        ? entity.getMainArm() : entity.getMainArm().getOpposite();
                if (arm == HumanoidArm.LEFT) {
                    frame = (4 - frame) % 4;
                }
                frame = (frame + 1) % 4;
                return (frame + 1) / 4.0F;
            });
        }
    }

    // 极短配置按比例缩短装水阶段，保持原有总使用时长。
    public static float fillTicks(LivingEntity entity) {
        return Math.min(8.0F, entity.getUseItem().getUseDuration(entity) * 0.25F);
    }

    // 平滑起手与抬盘，端点速度归零。
    public static float smooth(float progress) {
        float value = Mth.clamp(progress, 0.0F, 1.0F);
        return value * value * (3.0F - 2.0F * value);
    }

    // 水面选帧和两种视角的手部运动共用相位。
    public static float phase(LivingEntity entity, float elapsed) {
        return Math.max(0.0F, elapsed - fillTicks(entity)) * Mth.TWO_PI / CYCLE_TICKS;
    }

    // 装水结束后逐渐进入完整摇洗幅度。
    public static float washBlend(LivingEntity entity, float elapsed) {
        return smooth((elapsed - fillTicks(entity)) / 5.0F);
    }
}

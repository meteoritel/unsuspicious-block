package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.Constants;
import com.meteorite.unsuspiciousblock.core.item.ModItems;
import com.meteorite.unsuspiciousblock.core.network.payload.s2c.PanningStatePayload;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/*** 淘洗演出共用时钟：以每次使用进度驱动起手、摇洗与液面帧，避免多人共享贴图时钟。 */
public final class PanningVisuals {
    private static final float CYCLE_TICKS = 20.0F;
    private static final ResourceLocation PANNING_FRAME =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panning_frame");
    // 正在淘洗的介质：0 = 水域帧组，1 = 岩浆帧组；用于在自定义模型里按介质二选一
    private static final ResourceLocation PANNING_MEDIUM =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "panning_medium");

    private PanningVisuals() {
    }

    // 两个平台都在客户端初始化时注册；停止使用后自动回到空盘。
    // 遍历全部淘盘，新增淘盘无需改动这里。
    public static void register() {
        for (Supplier<Item> pan : ModItems.PAN_ITEMS) {
            ItemProperties.register(pan.get(), PANNING_FRAME, (stack, level, entity, seed) -> {
                if (entity == null) {
                    return 0.0F;
                }
                PanningStatePayload state = stateFor(entity, stack);
                if (state == null) {
                    return 0.0F;
                }
                float elapsed = elapsed(entity, state);
                float fillTicks = fillTicks(state);
                if (elapsed < fillTicks * 0.5F) {
                    return 0.0F;
                }
                // 相位从中间液面起步；原图从上到下依次为左、中、右、中。
                int frame = Mth.floor(phase(fillTicks, elapsed) / Mth.TWO_PI * 4.0F + 0.5F) % 4;
                HumanoidArm arm = state.hand() == InteractionHand.MAIN_HAND
                        ? entity.getMainArm() : entity.getMainArm().getOpposite();
                if (arm == HumanoidArm.LEFT) {
                    frame = (4 - frame) % 4;
                }
                frame = (frame + 1) % 4;
                return (frame + 1) / 4.0F;
            });
            ItemProperties.register(pan.get(), PANNING_MEDIUM, (stack, level, entity, seed) -> {
                if (entity == null) {
                    return 0.0F;
                }
                PanningStatePayload state = stateFor(entity, stack);
                return state == null ? 0.0F : state.medium().ordinal();
            });
        }
    }

    private static @Nullable PanningStatePayload stateFor(LivingEntity entity, ItemStack stack) {
        PanningStatePayload state = ClientPanningState.of(entity);
        return state != null && entity.getItemInHand(state.hand()) == stack ? state : null;
    }

    static float elapsed(LivingEntity entity, PanningStatePayload state) {
        return Mth.clamp(entity.level().getGameTime() - state.startedAt(), 0, state.duration());
    }

    static float fillTicks(PanningStatePayload state) {
        return Math.min(8.0F, state.duration() * 0.25F);
    }

    // 平滑起手与抬盘，端点速度归零。
    public static float smooth(float progress) {
        float value = Mth.clamp(progress, 0.0F, 1.0F);
        return value * value * (3.0F - 2.0F * value);
    }

    // 水面选帧和两种视角的手部运动共用相位。
    static float phase(float fillTicks, float elapsed) {
        return Math.max(0.0F, elapsed - fillTicks) * Mth.TWO_PI / CYCLE_TICKS;
    }

    // 装水结束后逐渐进入完整摇洗幅度。
    static float washBlend(float fillTicks, float elapsed) {
        return smooth((elapsed - fillTicks) / 5.0F);
    }
}

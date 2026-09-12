package com.meteorite.unsuspiciousblock.client.pan;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/*** 淘洗循环水声：复用原版流水素材，以音调和音量起伏模拟摇洗，工作结束立即停止。 */
public final class PanningSound extends AbstractTickableSoundInstance {
    private final ShimmerEntity shimmer;

    public PanningSound(ShimmerEntity shimmer) {
        super(SoundEvents.WATER_AMBIENT, SoundSource.BLOCKS, shimmer.level().getRandom());
        this.shimmer = shimmer;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.22F;
        this.pitch = 1.3F;
        this.x = shimmer.getX();
        this.y = shimmer.getY() + 0.875D;
        this.z = shimmer.getZ();
    }

    @Override
    public void tick() {
        Minecraft client = Minecraft.getInstance();
        if (this.shimmer.isRemoved() || !this.shimmer.isPanning()
                || client.level != this.shimmer.level() || client.player == null
                || client.player.distanceToSqr(this.shimmer) > 24.0D * 24.0D) {
            this.stop();
            return;
        }
        float pulse = 0.5F + 0.5F * Mth.cos(this.shimmer.getWorkTicks() * Mth.TWO_PI / 20.0F);
        this.volume = 0.16F + pulse * 0.12F;
        this.pitch = 1.15F + pulse * 0.25F;
    }

    // 断开服务器或切换维度时由客户端生命周期主动释放。
    public void finish() {
        this.stop();
    }
}

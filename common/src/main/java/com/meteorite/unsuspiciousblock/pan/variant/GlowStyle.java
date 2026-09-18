package com.meteorite.unsuspiciousblock.pan.variant;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;

/***
 * 变体表现参数——波光配色、粒子、音效与摇洗帧组，纯数据、不含逻辑。
 * <p>
 * 客户端渲染、粒子发射与声音播放全部从这里取值，因此玩家看到与听到的介质差异完全由变体决定，
 * 变体本身不需要任何额外的网络同步字段。
 */
public record GlowStyle(
        Rgb glint,                        // 波光外层色
        Rgb glintCore,                    // 波光亮芯色
        ParticleOptions idleParticle,     // 世界生成点采空后保留的低频闪光
        ParticleOptions splashParticle,   // 工作中的旋转水花与生成入水特效
        ParticleOptions rippleParticle,   // 工作中的向外扩散涟漪
        SoundEvent startSound,            // 起手
        SoundEvent consumeSound,          // 每次消耗一次淘洗次数
        SoundEvent loopSound,             // 摇洗循环
        PanningMedium medium              // 摇洗帧组，决定物品属性 panning_medium 的取值
) {
    /*** 波光顶点色，按分量写入，避免在逐帧渲染中做位运算解包。 */
    public record Rgb(int red, int green, int blue) {}
}

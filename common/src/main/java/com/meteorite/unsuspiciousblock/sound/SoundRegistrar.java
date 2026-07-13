package com.meteorite.unsuspiciousblock.sound;

import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;

import java.util.function.Consumer;

/** 声音注册回调接口，供各平台实现具体的注册逻辑 */
@FunctionalInterface
public interface SoundRegistrar {
    void register(String name, Consumer<Holder<SoundEvent>> setter);
}

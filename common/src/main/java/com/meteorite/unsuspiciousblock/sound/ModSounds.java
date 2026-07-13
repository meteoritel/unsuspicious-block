package com.meteorite.unsuspiciousblock.sound;

import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;

import java.util.List;
import java.util.function.Consumer;

/** 模组声音--声音事件由各平台分别创建注册，Holder 回写至静态字段供 common 代码引用 */
public final class ModSounds {

    // 可疑解析仪扫描音效：范围扫描或单方块扫描成功时播放
    public static Holder<SoundEvent> SUSPICIOUS_READER_SCAN;

    // 声音注册清单条目，供各平台遍历注册
    public record SoundEntry(String name, Consumer<Holder<SoundEvent>> setter) {}

    // 声音注册清单--新增声音只需在此添加一行
    public static final List<SoundEntry> REGISTRY_MANIFEST = List.of(
            new SoundEntry("suspicious_reader_scan", holder -> SUSPICIOUS_READER_SCAN = holder)
    );

    private ModSounds() {
    }

    // 遍历清单，调用平台回调完成注册
    public static void forEach(SoundRegistrar registrar) {
        for (SoundEntry entry : REGISTRY_MANIFEST) {
            registrar.register(entry.name(), entry.setter());
        }
    }
}

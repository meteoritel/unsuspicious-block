package com.meteorite.unsuspiciousblock.loottable.injection;

/**
 * 考古战利品注入器注册器——平台在初始化时注册各自的实现。
 * <p>
 * 未注册时使用空实现（不注入），确保 common 包在无平台注入器时仍可编译运行，
 * 同时让 NeoForge 端（已通过 GlobalLootModifier 实现注入）无需注册即可正常工作。
 */
public final class ArchaeologyLootInjectors {

    private ArchaeologyLootInjectors() {
    }

    // 空实现——未注册时不执行任何注入
    private static volatile ArchaeologyLootInjector instance = (tableId, drops, random) -> {};

    // 平台在初始化时调用，注册各自的注入器实现
    public static void register(ArchaeologyLootInjector injector) {
        instance = injector;
    }

    // 调用方（mixin / 模拟器）通过此方法获取当前注入器
    public static ArchaeologyLootInjector get() {
        return instance;
    }
}

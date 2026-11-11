package com.meteorite.unsuspiciousblock.core.bootstrap;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 注册值 T，并按平台相位回交 H。
 * <p>Fabric 同步创建和回交；NeoForge 登记延迟工厂，Supplier/Holder 可立即回交，
 * Item 实例必须在 common setup 的 enqueueWork 中回交。回交 Supplier 不代表其已可求值。
 */
@FunctionalInterface
public interface Registrar<T, H> {
    /** 工厂求值与回交时点由平台注册相位决定，调用方只保存回交引用。 */
    void register(String name, Supplier<T> factory, Consumer<H> setter);
}

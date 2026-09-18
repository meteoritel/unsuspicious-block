package com.meteorite.unsuspiciousblock.loottable.graph;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 战利品表引用图中的一条有向边——由来源表的邻接表隐含起点，边自身只描述目标与语义来源。
 * <p>
 * 边必须区分语义来源：{@link Kind#JSON_REFERENCE} 来自有效 JSON 中真实存在的
 * {@code loot_table} entry，参与静态语义链接；{@link Kind#RUNTIME_INJECTION} 只表达
 * 平台运行时注入关系，参与收录闭包、目录层级与哈希，但不会把子表条目提前编译进父表。
 */
public record LootTableEdge(ResourceLocation target, Kind kind) {
    public LootTableEdge {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(kind, "kind");
    }

    // 有效 JSON 中声明的子表引用
    public static LootTableEdge jsonReference(ResourceLocation target) {
        return new LootTableEdge(target, Kind.JSON_REFERENCE);
    }

    // 平台运行时注入产生的合成边（JSON 中不存在）
    public static LootTableEdge runtimeInjection(ResourceLocation target) {
        return new LootTableEdge(target, Kind.RUNTIME_INJECTION);
    }

    /**
     * 边的语义来源类型——决定这条边参与哪些下游视图。
     * <p>
     * 新增运行时联动时必须显式选型，避免"注入关系"被误当"静态引用"：
     *
     * <table>
     *   <tr><th>类型</th><th>闭包</th><th>目录层级</th><th>哈希</th><th>静态语义链接</th></tr>
     *   <tr><td>{@link #JSON_REFERENCE}</td><td>是</td><td>是</td><td>是</td><td>是</td></tr>
     *   <tr><td>{@link #RUNTIME_INJECTION}</td><td>是</td><td>是</td><td>是</td><td>否</td></tr>
     * </table>
     * <p>
     * 当前两类边在结构维度（闭包 / 目录 / 哈希）上一致，因此结构遍历不区分类型；
     * 唯一的差异维度是静态语义链接——只有 {@code JSON_REFERENCE} 会把子表条目
     * 编译进父表的展平路径（由投影层按类型筛选）。
     */
    public enum Kind {
        // 真实 JSON 引用：既决定结构，也决定静态语义
        JSON_REFERENCE,
        // 运行时注入：只决定结构，静态语义由模拟期动态发现
        RUNTIME_INJECTION
    }
}

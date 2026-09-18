package com.meteorite.unsuspiciousblock.pan.variant;

import com.meteorite.unsuspiciousblock.entity.ShimmerEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.function.Supplier;

/***
 * 淘洗点变体——一个变体的全部差异数据。
 * <p>
 * 变体之间的差异只有依附介质、生成域、表现参数、产出表与实体类型五项，全部是数据或可替换的算法片段，
 * 没有一条是新的机制。变体由注册表按 id 索引，实体、地物与指令都通过 id 引用它。
 * <p>
 * 实体类型以 {@link Supplier} 持有：注册表的静态初始化早于平台回写实体类型字段，必须延迟取值。
 */
public record ShimmerVariant(
        ResourceLocation id,
        AnchorRule anchor,                                   // 依附判定与演出高度
        SpawnDomain spawnDomain,                             // 落点基准与群系预筛
        GlowStyle glow,                                      // 波光、粒子与音效
        ResourceKey<LootTable> lootTable,                    // 产出表由变体决定，不由工具决定
        Supplier<EntityType<? extends ShimmerEntity>> entityType
) {}

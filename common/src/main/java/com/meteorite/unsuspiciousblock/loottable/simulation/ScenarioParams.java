package com.meteorite.unsuspiciousblock.loottable.simulation;

import com.meteorite.unsuspiciousblock.loottable.analysis.LuckGateAnalysis;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link SimulationInput} 的参数旋钮部分——玩家真实能选、且直接改变掉落结果的那几个值。
 * <p>
 * 白名单经第四轮收窄为四项（决策 49）：幸运、工具基座、工具附魔等级、抽样次数。
 * **不做爆炸与击杀/抢夺**——它们分别只服务于方块破坏表与实体掉落表，而两类都不在追踪范围内
 * （见规划 §2.3）。同样的理由使 {@code enchanted_count_increase} /
 * {@code random_chance_with_enchanted_bonus} 的附魔不参与等级旋钮：它们读的是
 * {@code ATTACKING_ENTITY} 的装备槽，只出现在实体掉落表里。
 * <p>
 * 三条不变量（都在构造点强制，非法值不会流到缓存键）：
 * <ul>
 *   <li>{@code luck} 有限、落在 {@link LuckGateAnalysis#MIN_LUCK}..{@link LuckGateAnalysis#MAX_LUCK}，
 *       并按 {@link LuckGateAnalysis#GRID} **量化**——门槛值本身就是对齐到 0.01 的（决策 34），
 *       量化让"填进去的门槛"与"算出来的门槛"是同一个键；</li>
 *   <li>{@code toolEnchantments} 只保留非零项并按附魔 id 排序（0 与"没写"必须是同一个输入）；</li>
 *   <li>{@code sampleCount} 只能取服务端签发的离散档位（决策 39）——自由输入会让每表的 LRU
 *       被同一参数的不同精度稀释。</li>
 * </ul>
 *
 * @param luck             幸运，-5.00 ~ 10.00，精度 0.01
 * @param toolId           工具基座物品 id；必须由目录签发
 * @param toolEnchantments 写在工具栈 {@code ENCHANTMENTS} 组件上的附魔等级（只放非零项）
 * @param sampleCount      单次模拟的抽样次数，只能取 {@link #SAMPLE_COUNT_TIERS} 中的值
 */
public record ScenarioParams(float luck, ResourceLocation toolId,
                             Map<ResourceLocation, Integer> toolEnchantments, int sampleCount) {
    /**
     * 可行的抽样次数档位（决策 39）——同时也是**硬上限 10 万**的执行处：档位是白名单，
     * 越界值在构造点被拒绝，因此不存在"自由输入把服务端压垮"的路径。档位本身是可调实现参数。
     */
    public static final List<Integer> SAMPLE_COUNT_TIERS = List.of(10_000, 50_000, 100_000);
    /** 基准档位：启动时每表只跑这一个（决策 17）。 */
    public static final int DEFAULT_SAMPLE_COUNT = 10_000;
    /** 附魔等级的数值边界；vanilla 组件 codec 与构造器都用 0..255（见规划 §3.2）。 */
    public static final int MAX_ENCHANTMENT_LEVEL = 255;
    /** 量化用的网格分母：幸运精度 0.01。 */
    private static final float LUCK_GRID = 100.0F;

    public ScenarioParams {
        if (Float.isNaN(luck) || Float.isInfinite(luck)) {
            throw new IllegalArgumentException("幸运必须是有限数值: " + luck);
        }
        if (luck < LuckGateAnalysis.MIN_LUCK || luck > LuckGateAnalysis.MAX_LUCK) {
            throw new IllegalArgumentException("幸运必须落在 " + LuckGateAnalysis.MIN_LUCK
                    + ".." + LuckGateAnalysis.MAX_LUCK + ": " + luck);
        }
        if (toolId == null) {
            throw new IllegalArgumentException("工具基座不可为空");
        }
        if (!SAMPLE_COUNT_TIERS.contains(sampleCount)) {
            throw new IllegalArgumentException("抽样次数必须是签发的档位之一 " + SAMPLE_COUNT_TIERS
                    + ": " + sampleCount);
        }
        luck = Math.round(luck * LUCK_GRID) / LUCK_GRID;
        toolEnchantments = canonicalizeEnchantments(toolEnchantments);
    }

    /** 基准参数（决策 17）：无附魔、幸运 0、基准档位。工具基座由调用方按表类型给出。 */
    public static ScenarioParams baseline(ResourceLocation toolId) {
        return new ScenarioParams(0.0F, toolId, Map.of(), DEFAULT_SAMPLE_COUNT);
    }

    private static Map<ResourceLocation, Integer> canonicalizeEnchantments(
            Map<ResourceLocation, Integer> levels) {
        Map<ResourceLocation, Integer> sorted = new TreeMap<>(
                Comparator.comparing(ResourceLocation::toString));
        for (Map.Entry<ResourceLocation, Integer> entry : levels.entrySet()) {
            Integer level = entry.getValue();
            if (entry.getKey() == null || level == null || level <= 0) {
                continue;
            }
            if (level > MAX_ENCHANTMENT_LEVEL) {
                throw new IllegalArgumentException("附魔等级必须落在 0.." + MAX_ENCHANTMENT_LEVEL
                        + ": " + entry.getKey() + "=" + level);
            }
            sorted.put(entry.getKey(), level);
        }
        return Map.copyOf(sorted);
    }

    /**
     * 把参数旋钮套到基座场景 profile 上。
     * <p>
     * 幸运总是生效；工具是否生效由 {@code keepBaseTool} 决定，因为**注入场景**（原版 fishing 的
     * 泥地打捞场景）把"满级钓竿"写进了场景定义本身（见
     * {@code SimulationScenarioPlanner.appendMudDredgingScenarios}）：那条场景的全部意义就是带着
     * 能通过 {@code tool_enchantment} 的门槛去抽注入池。若用输入的默认工具覆盖它，注入池永远抽空，
     * 注入条目就再也发现不了——那是信息丢失，不是"参数生效"。
     *
     * @param keepBaseTool 是否保留场景自带的工具（注入场景为 {@code true}）
     */
    public SimulationProfile applyTo(SimulationProfile base, @Nullable HolderLookup.Provider registries,
                                     boolean keepBaseTool) {
        SimulationProfile withLuck = base.withLuck(this.luck);
        return keepBaseTool ? withLuck : withLuck.withTool(createToolStack(registries));
    }

    /**
     * 构造工具栈：基座物品 + 写入 {@code ENCHANTMENTS} 组件。
     * <p>
     * 走真实组件而不是自造一个"等级"字段，是因为读取链路就是
     * {@code ApplyBonusCount} → {@code EnchantmentHelper.getItemEnchantmentLevel(TOOL)} →
     * {@code ItemEnchantments.getLevel}（纯 map 查表，vanilla 全链路不裁剪到 {@code maxLevel}）。
     * 项目里已有的先例是 {@code SimulationScenarioPlanner} 构造满级钓竿。
     */
    public ItemStack createToolStack(@Nullable HolderLookup.Provider registries) {
        ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(this.toolId));
        if (this.toolEnchantments.isEmpty()) {
            return tool;
        }
        if (registries == null) {
            throw new IllegalStateException("写入附魔等级需要注册表访问");
        }
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        HolderLookup.RegistryLookup<Enchantment> lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        for (Map.Entry<ResourceLocation, Integer> entry : this.toolEnchantments.entrySet()) {
            Holder<Enchantment> holder = lookup
                    .get(ResourceKey.create(Registries.ENCHANTMENT, entry.getKey()))
                    .orElse(null);
            if (holder == null) {
                // 附魔不存在就不写：凭空造一个 Holder 会让条件按一个游戏里不存在的等级求值
                continue;
            }
            enchantments.set(holder, entry.getValue());
        }
        tool.set(DataComponents.ENCHANTMENTS, enchantments.toImmutable());
        return tool;
    }

    /** 便于日志与调试的紧凑描述。 */
    public String describe() {
        if (this.toolEnchantments.isEmpty()) {
            return "luck=" + this.luck + ",tool=" + this.toolId + ",n=" + this.sampleCount;
        }
        Map<String, Integer> levels = new LinkedHashMap<>();
        this.toolEnchantments.forEach((id, level) -> levels.put(id.toString(), level));
        return "luck=" + this.luck + ",tool=" + this.toolId + ",ench=" + levels + ",n=" + this.sampleCount;
    }
}
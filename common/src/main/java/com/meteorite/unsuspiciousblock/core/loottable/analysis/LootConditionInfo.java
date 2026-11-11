package com.meteorite.unsuspiciousblock.core.loottable.analysis;

import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** 条件静态分析节点；逻辑形态与说明行分别保存，原始谓词只在服务端本代使用。 */
public record LootConditionInfo(
        ResourceLocation conditionType,
        Component description,
        @Nullable Float probability,
        ConditionTree.Kind kind,
        List<LootConditionInfo> logicChildren,
        List<ConditionDisplayRow> displayRows,
        Map<String, String> metadata,
        @Nullable LootItemCondition source,
        ConditionIdentity identity
) implements ConditionPresentation {
    /** 构造可信的逻辑树；非法形态由分析入口转为未解析诊断。 */
    public LootConditionInfo {
        if (conditionType == null || description == null || kind == null) {
            throw new IllegalArgumentException("条件类型、描述与形态必须存在");
        }
        logicChildren = List.copyOf(logicChildren);
        displayRows = List.copyOf(displayRows);
        metadata = Map.copyOf(metadata);
        if (kind == ConditionTree.Kind.LEAF && !logicChildren.isEmpty()
                || kind == ConditionTree.Kind.NOT && logicChildren.size() != 1) {
            throw new IllegalArgumentException("非法条件形态: " + kind);
        }
    }

    public LootConditionInfo(ResourceLocation type, Component description, @Nullable Float probability,
                             ConditionTree.Kind kind, List<LootConditionInfo> children,
                             List<ConditionDisplayRow> rows, Map<String, String> metadata,
                             @Nullable LootItemCondition source) {
        this(type, description, probability, kind, children, rows, metadata, source, ConditionIdentity.occurrence());
    }

    /** 分析入口绑定已编码的身份；后续投影与复制复用它。 */
    public LootConditionInfo withIdentity(ConditionIdentity value) {
        return new LootConditionInfo(conditionType, description, probability, kind,
                logicChildren, displayRows, metadata, source, value);
    }

    /** 创建逻辑叶子；展示子行从分析描述转换为独立值。 */
    public LootConditionInfo(ResourceLocation type, Component description, @Nullable Float probability,
                             List<LootConditionInfo> rows, Map<String, String> metadata) {
        this(type, description, probability, ConditionTree.Kind.LEAF, List.of(),
                rows.stream().map(LootConditionInfo::asDisplayRow).toList(), metadata, null);
    }

    public LootConditionInfo(ResourceLocation type, Component description, @Nullable Float probability) {
        this(type, description, probability, List.of(), Map.of());
    }

    public LootConditionInfo(ResourceLocation type, Component description, @Nullable Float probability,
                             List<LootConditionInfo> rows) {
        this(type, description, probability, rows, Map.of());
    }

    /** 组合条件显式提供形态，类型 ID 只用于类型能力与诊断。 */
    public static LootConditionInfo branch(ConditionTree.Kind kind, ResourceLocation type,
                                          Component description, List<LootConditionInfo> children) {
        return new LootConditionInfo(type, description, null, kind, children, List.of(), Map.of(), null);
    }

    /** 只替换本代服务端谓词，保持形态与展示快照。 */
    public LootConditionInfo withSource(LootItemCondition value) {
        return new LootConditionInfo(conditionType, description, probability, kind,
                logicChildren, displayRows, metadata, value, identity);
    }

    /** 追加机器可读信息，保持源谓词与逻辑形态。 */
    public LootConditionInfo withMetadata(String key, String value) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new LootConditionInfo(conditionType, description, probability, kind,
                logicChildren, displayRows, merged, source, identity);
    }

    @Override
    public List<? extends ConditionPresentation> displayChildren() {
        List<ConditionPresentation> result = new ArrayList<>(logicChildren);
        result.addAll(displayRows);
        return List.copyOf(result);
    }

    private ConditionDisplayRow asDisplayRow() {
        List<ConditionDisplayRow> children = new ArrayList<>();
        logicChildren.forEach(child -> children.add(child.asDisplayRow()));
        children.addAll(displayRows);
        return new ConditionDisplayRow(conditionType, description, probability, children, metadata);
    }
}

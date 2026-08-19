# Mixin 总览

本文档描述模组的 Mixin 使用情况：三套 mixin 配置、注入点分类、典型模式与兼容性策略。

## 1. 职责概述

模组遵循项目准则（见 [`CLAUDE.md`](../../CLAUDE.md)）：**优先使用已有 Event API，尽可能少用 Mixin**。Fabric 平台原生事件不足时才使用 Mixin，且 **Mixin 仅作入口，不写大量业务逻辑**。

Mixin 主要用于四类需求：
1. **状态附加**：为玩家/方块实体附加持久化状态（考古笔记进度、猫族关系状态）。
2. **行为修改**：改变原版行为（九命无敌、轻步不踩耕地、苦力怕回避等）。
3. **事件补齐**：Fabric 平台原生事件缺失时，用 mixin 捕获原版流程（铁砧修复、刷拭、羊剪毛、骨块追踪）。
4. **兼容支持**：Lootr 兼容（保留每位玩家独立的发掘与日志状态）。

## 2. 三套 mixin 配置

| 配置文件 | 位置 | mixin 数 | 说明 |
|---|---|---|---|
| `unsuspiciousblock.mixins.json` | `common/src/main/resources/` | 48（含 4 客户端） | 跨平台通用 mixin，两端共用 |
| `unsuspiciousblock.fabric.mixins.json` | `fabric/src/main/resources/` | 6 | Fabric 独有，补齐原生事件缺失 |
| `unsuspiciousblock.lootr.mixins.json` | `common/src/main/resources/` | 5 | Lootr 兼容，`requiredMods = ["lootr"]` |

> Lootr mixin 是**可选兼容**，构建时可通过 `lootr_compat_*` 属性排除（见 [架构总览](architecture-overview.md) 第 5.3 节）。

## 3. common mixin 分类

`common/src/main/java/com/meteorite/unsuspiciousblock/mixin/` 下按子系统分包：

### 3.1 journal/ - 考古笔记状态附加

| Mixin | 目标 | 职责 |
|---|---|---|
| `PlayerJournalStateMixin` | `Player` | 附加 `ArchaeologyJournalState`，实现 `ArchaeologyJournalStateHolder`，持久化到玩家 NBT |
| `ServerPlayerJournalStateMixin` | `ServerPlayer` | 服务端专属的状态处理（tick 驱动、同步） |
| `PlayerJournalLogStateMixin` | `Player` | 附加日志状态 |

### 3.2 catfavor/ - 猫族关系行为

| Mixin | 目标 | 职责 |
|---|---|---|
| `CatFeedMixin` | 猫喂食 | 喂食奖励（延迟到 tick 末结算） |
| `CatRelaxOnOwnerGoalMixin` | 猫 AI | 共眠奖励 |
| `CatSitOnBlockGoalMixin` | 猫 AI | 坐方块奖励 |
| `TamableAnimalTameMixin` | `TamableAnimal` | 驯服奖励 + 取消待结算喂食 |
| `CreeperAvoidCatFavorMixin` | 苦力怕 | 威慑：回避持有恩惠的玩家 |
| `PhantomSpawnerMixin` / `PhantomTargetGoalMixin` | 幻翼 | 威慑：不生成/不索敌 |
| `FarmBlockTrampleMixin` | 耕地 | 轻步：不踩坏耕地 |
| `EntityBlockTriggerMixin` | 实体触发 | 轻步：不触发压力板/绊线 |
| `LivingEntityFallDamageMixin` | `LivingEntity` | 柔软肉垫：摔落减伤 |
| `LivingEntityNineLivesMixin` | `LivingEntity` | 九命：致命伤害时触发 |
| `PlayerHurtInvulnMixin` | 玩家受伤 | 九命无敌窗口的伤害豁免 |
| `PlayerCatFavorStateMixin` / `ServerPlayerCatFavorStateMixin` | 玩家 | 附加 `CatFavorState`，持久化 |

### 3.3 container/ - 容器追踪

| Mixin | 目标 | 职责 |
|---|---|---|
| `AbstractContainerMenuMixin` | `AbstractContainerMenu` | 菜单快照（追踪开箱前后差异） |
| `RandomizableContainerBlockEntityMixin` / `RandomizableContainerMixin` | 战利品容器 | 容器开箱追踪 |
| `AbstractMinecartContainerMixin` | `AbstractMinecartContainer` | 箱子矿车追踪状态、NBT 持久化与销毁结算 |
| `ContainerEntityMixin` | `ContainerEntity` | 箱子矿车等实体容器的战利品生成入口，沿用 `LOOT_CONTAINER` |
| `BaseContainerBlockEntityMixin` / `CompoundContainerMixin` | 基础容器 | 双联箱子追踪 |
| `DecoratedPotLootStateMixin` | 陶罐 | 陶罐追踪 |
| `SmithingMenuMixin` | 锻造台 | 失落书页锻造触发 |

### 3.4 block/ - 方块行为

| Mixin | 目标 | 职责 |
|---|---|---|
| `BrushableBlockEntityMixin` | 可疑方块实体 | 扫描状态标记 + 战利品解析（`BrushableBlockEntityScanState`） |
| `BlockBehaviourMixin` / `BlockMixin` | 方块 | 方块行为钩子 |
| `DecoratedPotBlockMixin` | 陶罐方块 | 陶罐行为 |

### 3.5 interaction/ - 交互

| Mixin | 目标 | 职责 |
|---|---|---|
| `FishingHookMixin` | 钓鱼钩 | 钓鱼追踪（泥底打捞 + 笔记记录） |
| `NestedLootTableMixin` | 嵌套战利品表 | 自动捕获嵌套子表物品，派生追踪上下文 |

### 3.6 loottable/ - 模拟条件作用域

| Mixin | 目标 | 职责 |
|---|---|---|
| `Simulation*ConditionMixin`（8 个单目标入口） | 八类原版场景条件 | 模拟作用域存在时把 `test` 转交 `LootSimulationScope`；单目标保证 Fabric remap 正确 |
| `SimulationCompositeConditionMixin` | `CompositeLootItemCondition` | 仅暴露只读 terms 供静态条件分析；运行时不覆盖组合结果 |

这些 Mixin 都只作入口；profile、场景规划、条件指纹和线程作用域全部位于 `loottable/simulation/`
普通 Java 类。作用域只覆盖有精确指纹或类型默认值的叶条件；组合与取反由原版求值，作用域外完整
执行原版逻辑，不影响实际战利品生成。

### 3.7 其他

| Mixin | 目标 | 职责 |
|---|---|---|
| `enchantment/EnchantmentMenuMixin` | `EnchantmentMenu` | 附魔揭示：`slotsChanged` 时检查是否应揭示完整候选 |
| `inventory/PlayerPresenceStateMixin` | 玩家 | 背包存在检测的 diff 状态 |
| `StructureTemplateMixin` | 结构模板 | 结构生成时扫描骨块（`scanBoundingBox`） |
| `client/AbstractContainerScreenAccessor` | 容器屏幕 | 访问内部字段（tooltip 渲染） |
| `client/EditBoxMixin` | 输入框 | 搜索框行为调整 |
| `client/EnchantmentScreenMixin` | 附魔台屏幕 | 渲染完整候选列表 |
| `client/ClientLanguageMixin` | `ClientLanguage` | 动态查询当前服务端补充名称，并在真实资源重载时失效资源来源索引 |

## 4. Fabric 独有 mixin

Fabric 平台因原生事件缺失，用 mixin 补齐 NeoForge 用事件实现的能力：

| Mixin | 目标 | NeoForge 对应 | 职责 |
|---|---|---|---|
| `chunk/ChunkAccessMixin` | `ChunkAccess` | `DataAttachment` | 骨块追踪持久化 |
| `chunk/ChunkSerializerMixin` | `ChunkSerializer` | `DataAttachment` | 骨块追踪序列化 |
| `chunk/LevelChunkMixin` | `LevelChunk` | `DataAttachment` | 骨块追踪运行时 |
| `container/AnvilMenuMixin` | `AnvilMenu` | `AnvilUpdateEvent` | 古代金币铁砧修复 |
| `interaction/BrushableBlockEntityMixin` | 可疑方块实体 | 事件 | 刷拭触发（精准发掘翻倍 + 追踪） |
| `interaction/SheepMixin` | 羊 | 事件 | 剪羊毛触发（织物采集） |

> 这是项目"允许两端使用不同方案实现相同效果"原则的体现（见 [`CLAUDE.md`](../../CLAUDE.md)）。Fabric 端的骨块追踪用 mixin + chunk 事件，NeoForge 端用 `DataAttachment`；铁砧修复 Fabric 用 mixin，NeoForge 用 `AnvilUpdateEvent`。

## 5. Lootr 兼容 mixin

`mixin/compat/lootr/` 包，`requiredMods = ["lootr"]`，仅在安装 Lootr 时生效（NeoForge 发布版带，Fabric 不带）：

| Mixin | 职责 |
|---|---|
| `DefaultLootFillerMixin` | 普通 Lootr 容器及奖励箱矿车的每玩家战利品填充钩子 |
| `DefaultBrushableLootFillerMixin` | 刷拭战利品填充钩子 |
| `LootrInventoryMixin` | Lootr 容器集成 |
| `LootrSavedDataMixin` | Lootr 存档数据 |
| `LootrBrushableBlockEntityMixin` | Lootr 可疑方块实体 |

目标是保留每位玩家独立的发掘与日志状态（Lootr 的每人独立战利品机制与模组的追踪系统协同）。Lootr 奖励箱矿车覆盖原版 `unpackChestVehicleLootTable` 为空实现，实际生成仍进入 `DefaultLootFiller`，所以不会与通用 `ContainerEntityMixin` 重复追踪。

## 6. 典型 mixin 模式

### 6.1 状态附加型

以 [`PlayerJournalStateMixin`](../../common/src/main/java/com/meteorite/unsuspiciousblock/mixin/journal/PlayerJournalStateMixin.java) 为例：

```java
@Mixin(Player.class)
public abstract class PlayerJournalStateMixin implements ArchaeologyJournalStateHolder {
    @Unique private final ArchaeologyJournalState state = new ArchaeologyJournalState();

    @Override
    public ArchaeologyJournalState unsuspiciousblock$getArchaeologyJournalState() {
        return this.state;  // 业务逻辑在 ArchaeologyJournalState 内
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(CompoundTag tag, CallbackInfo ci) { tag.put(KEY, this.state.toTag()); }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void load(CompoundTag tag, CallbackInfo ci) { this.state.readFrom(tag.getCompound(KEY)); }
}
```

特点：mixin 只负责"附加字段 + 持久化读写"，业务逻辑全部在 `ArchaeologyJournalState` 内。猫族关系的 `PlayerCatFavorStateMixin` 同此模式。

### 6.2 行为修改型

以 [`AnvilMenuMixin`](../../fabric/src/main/java/com/meteorite/unsuspiciousblock/mixin/container/AnvilMenuMixin.java) 为例（Fabric 独有）：

```java
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow private int repairItemCountCost;
    @Final @Shadow private DataSlot cost;

    @Inject(method = "createResult", at = @At("RETURN"))
    private void ancientCoinRepair(CallbackInfo ci) {
        // 仅当原版未产生结果、右侧为古代金币时介入
        if (!result.isEmpty()) return;  // 不覆盖其他 mod 输出
        // ... 修复逻辑
    }
}
```

特点：`@At("RETURN")` 覆盖全部 return 路径，仅在结果为空时介入，不修改原版分支与局部变量，对其他 mixin 完全透明。

### 6.3 接口注入型

`BrushableBlockEntityScanState` 等接口通过 mixin 注入到原版类（如 `BrushableBlockEntity`），暴露模组需要的方法（`isScanner` / `markScanned` / `resolveAndGetLoot`），供 `SuspiciousReaderItem` 等调用。

## 7. 兼容性策略

项目 mixin 普遍遵循以下兼容性策略：

1. **注入点选择 `@At("RETURN")` 或 `TAIL`**：覆盖全部 return 路径，不依赖具体 INVOKE 字节码，对原版更新与其他 mod 的 mixin 更鲁棒。
2. **仅在原版未处理时介入**：如铁砧 mixin 仅在结果槽为空时介入，避免覆盖其他 mod 的输出。
3. **不修改原版分支与局部变量**：对其他 mixin 完全透明。
4. **业务逻辑外置**：mixin 只做入口，复杂逻辑在专门类中（如 `CatFavorManager`、`ArchaeologyJournalState`），便于维护与测试。
5. **能力位掩码缓存**：高频查询的能力（威慑、轻步）通过 `CatPassiveAbilities` 每 tick 计算位掩码缓存到状态，mixin 查询时无需库存扫描（见 [猫族关系系统](cat-favor.md) 第 6.1 节）。

## 8. AccessTransformer / AccessWidener

部分 mixin 需要访问原版私有字段，通过平台访问扩展机制：

- common: `META-INF/accesstransformer.cfg`（AccessTransformer，两端共用）
- AccessWidener：`common/src/main/resources/unsuspiciousblock.accesswidener`（文件位于 common，由 Fabric 构建应用到 Fabric 端）

详见 [架构总览](architecture-overview.md) 第 5.2 节。

## 9. 相关文档

- [架构总览](architecture-overview.md) - 三套 mixin 配置与构建
- [考古笔记系统](journal.md) - journal/ 包 mixin
- [猫族关系系统](cat-favor.md) - catfavor/ 包 mixin
- [战利品表系统](loottable.md) - `NestedLootTableMixin` 与模拟条件作用域
- [实体与世界生成](entities-world.md) - Fabric 骨块追踪 mixin
- [配置与第三方联动](config-integrations.md) - Lootr 兼容

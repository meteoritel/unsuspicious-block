# Mixin

> 模组的 Mixin 使用情况：三套 mixin 配置、全部注入点清单、典型模式与兼容性策略。
> 本文件是 **Mixin 注入点清单的唯一权威**。各子系统文档只写"本子系统涉及哪几个 mixin"，不在此复述清单。

## 1. 机制概览

模组遵循项目准则：**优先使用已有 Event API，尽可能少用 Mixin**。Fabric 平台原生事件不足时才使用 Mixin，且 **Mixin 仅作入口，不写大量业务逻辑**。

Mixin 用于四类需求：

1. **状态附加**：为玩家/方块实体附加持久化状态（考古笔记进度、猫族关系状态）。
2. **行为修改**：改变原版行为（九命无敌、轻步不踩耕地、苦力怕回避等）。
3. **事件补齐**：Fabric 平台原生事件缺失时，用 mixin 捕获原版流程（铁砧修复、刷拭、羊剪毛、骨块追踪）。
4. **兼容支持**：Lootr 兼容（保留每位玩家独立的发掘与日志状态）。

## 2. 三套 mixin 配置

| 配置文件 | 位置 | 条目数 | 说明 |
|---|---|---|---|
| `unsuspiciousblock.mixins.json` | `common/src/main/resources/` | 45 + 5 client = 50 | 跨平台通用 mixin，两端共用 |
| `unsuspiciousblock.fabric.mixins.json` | `fabric/src/main/resources/` | 6 + 1 client = 7 | Fabric 独有，补齐原生事件缺失 |
| `unsuspiciousblock.lootr.mixins.json` | `common/src/main/resources/` | 5 | Lootr 兼容 |

**Lootr 兼容的启用方式**：`discoverable` 与 `requiredMods = ["lootr"]` 不在 mixin 配置文件里，而由构建注入 —— `buildSrc/src/main/groovy/multiloader-common.gradle` 按 `lootrCompatNeoForge` 把

```toml
[[mixins]]
config = "unsuspiciousblock.lootr.mixins.json"
requiredMods = ["lootr"]
```

写进 `neoforge.mods.toml`，并用同一个属性把 Lootr 依赖项与 Lootr 相关代码、service 文件一起排除。**Fabric 端不声明该 config**（`fabric.mod.json` 的 `mixins` 只列 `mixins.json` 与 `fabric.mixins.json`），发布配置为 `lootr_compat_fabric=false` / `lootr_compat_neoforge=true`。详见 [架构总览](architecture.md)。

## 3. common mixin 全清单

`common/src/main/java/com/meteorite/unsuspiciousblock/mixin/` 下按子系统分包。

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
| `SmithingMenuMixin` | 锻造台 | 失落书页锻造触发；产出附魔书时授予 `ancient_scholarship` |

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
| `Simulation*ConditionMixin`（7 个单目标入口） | 七类原版场景条件 | 模拟作用域存在时把 `test` 转交 `LootSimulationScope`；单目标保证 Fabric remap 正确 |
| `SimulationCompositeConditionMixin` | `CompositeLootItemCondition` | 仅暴露只读 terms 供静态条件分析；运行时不覆盖组合结果 |
| `LootItemConditionalFunctionMixin` | `LootItemConditionalFunction` | 函数执行捕获入口：`@WrapOperation` 包装 `apply` 内对 `run` 的调用，语义＝外层条件通过进入执行体；不重掷条件、不额外消费随机数，作用域外直接透传 |
| `LootTableSplitterMixin` | `LootTable` | `@ModifyVariable` 包装拆栈器的输出 `Consumer`、`@ModifyReturnValue` 包装拆栈器本身，让拆栈副本按对象身份继承来源观测链；拆栈算法仍由原版执行 |

这些 Mixin 都只作入口：profile、场景规划、条件指纹和线程作用域全部位于 `loottable/simulation/` 的普通 Java 类。作用域只覆盖有精确指纹或类型默认值的叶条件；组合与取反由原版求值，作用域外完整执行原版逻辑，不影响实际战利品生成。

两个函数捕获 Mixin 同样只转交参数与返回值，捕获逻辑全在 `FunctionTraceSession`（见 [战利品表系统](../subsystems/loottable.md) 的「函数规则描述与运行时观测」）。**拆栈为什么包装 `Consumer` 而不是 lambda 窄注入**：`copyWithCount` 位于编译器生成的合成方法内——vanilla / neoforge merged jar 里是 `lambda$createStackSplitter$5`，但 Fabric loom named jar 中同一合成方法名是 `method_331`，该 lambda 名在 Fabric 命名空间中根本不存在，只能依赖 refmap 对合成 lambda 名重映射，而 lambda 序号不是稳定 API；且两个平台的 `createStackSplitter` 调用点并不一致（vanilla 在 `public getRandomItems(LootContext, Consumer)` 内，neoforge 在返回 `ObjectArrayList` 的私有 `getRandomItems(LootContext)` 内），单一 common Mixin 无法窄注入两处。因此改为在两平台一致的非 lambda 静态方法 `createStackSplitter` 上包装它的输入/输出 `Consumer`：两种方案只选一种、不叠加，也不对 `ItemStack.copy` / `copyWithCount` 做全局注入。

> **`tool` 不是场景布尔控制类型**（场景布尔维度共 7 类，无 `match_tool`）。工具匹配由 `ItemPredicate` 对 profile 填充的真实 `TOOL` 栈求值，附魔等级由参数旋钮写进工具栈——若把它做成场景布尔，会出现"条件说匹配成功、而读真实 `TOOL` 的 `apply_bonus` 拿到的是一把无附魔镐"的分裂。新增场景维度前先确认它是否只在 `test` 层可观察。

### 3.7 其他

| Mixin | 目标 | 职责 |
|---|---|---|
| `enchantment/EnchantmentMenuMixin` | `EnchantmentMenu` | 附魔揭示：`slotsChanged` 时检查是否应揭示完整候选 |
| `inventory/PlayerPresenceStateMixin` | 玩家 | 背包存在检测的 diff 状态 |
| `StructureTemplateMixin` | 结构模板 | 结构生成时扫描骨块（`scanBoundingBox`） |
| `client/AbstractContainerScreenAccessor` | 容器屏幕 | 访问内部字段（tooltip 渲染） |
| `client/EditBoxMixin` | 输入框 | 搜索框行为调整 |
| `client/EnchantmentScreenMixin` | 附魔台屏幕 | 渲染完整候选列表 |
| `client/ClientLanguageMixin` | `ClientLanguage` | 动态查询当前服务端补充名称，并在真实资源重载时失效资源来源索引（见 [战利品表系统](../subsystems/loottable.md)） |
| `client/HumanoidPanningMixin` | `HumanoidModel` | 淘盘第三人称动画入口（`setupAnim` 尾部转交 `PanningAnimation`，见 [淘洗系统](../subsystems/panning.md)） |

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
| `client/ItemInHandPanningMixin` | `ItemInHandRenderer` | `RenderHandEvent` | 淘盘第一人称动画入口（转交 `PanningAnimation`，见 [淘洗系统](../subsystems/panning.md)） |

> 这是"允许两端使用不同方案实现相同效果"原则的体现：骨块追踪 Fabric 用 mixin + chunk 事件、NeoForge 用 `DataAttachment`；铁砧修复 Fabric 用 mixin、NeoForge 用 `AnvilUpdateEvent`。

## 5. Lootr 兼容 mixin

`mixin/compat/lootr/` 包，仅在安装 Lootr 且对应端启用了兼容时生效：

| Mixin | 职责 |
|---|---|
| `DefaultLootFillerMixin` | 普通 Lootr 容器及奖励箱矿车的每玩家战利品填充钩子 |
| `DefaultBrushableLootFillerMixin` | 刷拭战利品填充钩子 |
| `LootrInventoryMixin` | Lootr 容器集成 |
| `LootrSavedDataMixin` | Lootr 存档数据 |
| `LootrBrushableBlockEntityMixin` | Lootr 可疑方块实体 |

目标是保留每位玩家独立的发掘与日志状态（Lootr 的每人独立战利品机制与模组的追踪系统协同）。Lootr 奖励箱矿车覆盖原版 `unpackChestVehicleLootTable` 为空实现，实际生成仍进入 `DefaultLootFiller`，所以不会与通用 `ContainerEntityMixin` 重复追踪。

## 6. 典型模式

### 6.1 状态附加型

以 [`PlayerJournalStateMixin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/mixin/journal/PlayerJournalStateMixin.java) 为例：

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

特点：mixin 只负责"附加字段 + 持久化读写"，业务逻辑全部在 `ArchaeologyJournalState` 内。`PlayerCatFavorStateMixin` 同此模式。

### 6.2 行为修改型

以 [`AnvilMenuMixin`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/mixin/container/AnvilMenuMixin.java) 为例（Fabric 独有）：

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

## 7. 扩展点：新增 Mixin

**先自问是否真的需要 mixin**：能由 Event API 表达的一律不用 mixin；只有 Fabric 原生事件缺失时才补 mixin，且优先只加在 Fabric 端（NeoForge 用事件）。

1. **确定归属**：common 通用 → `common/.../mixin/<子系统>/`；仅 Fabric 需要 → `fabric/.../mixin/<子系统>/`；Lootr 相关 → `mixin/compat/lootr/`。
2. **写入口**：只做"捕获时机 + 转交"，把参数整理后交给子系统里的普通类处理（如 `CatFavorManager`、`PanningAnimation`、`LootSimulationScope`）。不要在此写业务分支。
3. **注册进对应配置**：common mixin 加到 `unsuspiciousblock.mixins.json`（客户端类放 `client` 数组），Fabric 独有加到 `unsuspiciousblock.fabric.mixins.json`。
4. **本文件补一行**：在对应分节的表里加 `Mixin | 目标 | 职责`，这是清单的唯一位置。
5. 若需要访问原版私有成员，检查 `accesstransformer.cfg` / `unsuspiciousblock.accesswidener` 是否已放开（见第 9 节）。

## 8. 约束与陷阱

1. **注入点优先 `@At("RETURN")` 或 `TAIL`**：覆盖全部 return 路径，不依赖具体 INVOKE 字节码，对原版更新与其他 mod 的 mixin 更鲁棒。
2. **仅在原版未处理时介入**：如铁砧 mixin 仅在结果槽为空时介入，避免覆盖其他 mod 的输出。
3. **不修改原版分支与局部变量**：对其他 mixin 完全透明。
4. **业务逻辑外置**：mixin 只做入口，复杂逻辑在专门类中。
5. **高频查询走缓存**：高频的能力查询（威慑、轻步）通过 `CatPassiveAbilities` 每 tick 计算位掩码缓存到状态，mixin 查询时不做库存扫描（见 [猫族关系系统](../subsystems/cat-favor.md)）。
6. **`Simulation*ConditionMixin` 保持单目标**：一对多的 mixin 在 Fabric remap 下会出错。
7. **不要在标题或正文里写死 mixin 总数**（本文第 2 节的数字以配置文件为准，增减时同步）。

## 9. AccessTransformer / AccessWidener

部分 mixin 需要访问原版私有字段，通过平台访问扩展机制：

- common：`META-INF/accesstransformer.cfg`（AccessTransformer，两端共用）
- AccessWidener：`common/src/main/resources/unsuspiciousblock.accesswidener`（文件位于 common，由 Fabric 构建应用到 Fabric 端）

详见 [架构总览](architecture.md)。

## 10. 相关文档

- [架构总览](architecture.md) —— 三套 mixin 配置与构建占位符
- [考古笔记系统](../subsystems/journal.md) —— journal/ 包 mixin
- [猫族关系系统](../subsystems/cat-favor.md) —— catfavor/ 包 mixin
- [战利品表系统](../subsystems/loottable.md) —— `NestedLootTableMixin` 与模拟条件作用域
- [实体与 AI](../subsystems/entities-world.md) —— Fabric 骨块追踪 mixin
- [配置与第三方联动](config-and-integrations.md) —— Lootr 兼容
- [淘洗系统](../subsystems/panning.md) —— 淘盘动画的两个 mixin 入口

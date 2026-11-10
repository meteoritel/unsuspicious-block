# Mixin 边界与接线

Mixin 负责原版状态附加、事件入口补齐与必要的访问扩展，业务逻辑转交普通 Java 类。优先使用已有 Event API，平台事件可表达的行为由对应 Loader 接入。

## 配置事实源

| 配置 | 作用 |
|---|---|
| [common 公共配置](../../../common/src/main/resources/unsuspiciousblock.mixins.json) | 两端共用的状态、追踪与模拟注入 |
| [Fabric 配置](../../../fabric/src/main/resources/unsuspiciousblock.fabric.mixins.json) | Fabric 事件补齐与平台接入 |
| [Lootr 配置](../../../common/src/main/resources/unsuspiciousblock.lootr.mixins.json) | 按构建和可选依赖条件启用容器追踪 |
| [common 客户端配置](../../../common/src/main/resources/unsuspiciousblock.client.mixins.json) | `client.mixin` 下的客户端注入 |
| [Fabric 客户端配置](../../../fabric/src/main/resources/unsuspiciousblock.fabric.client.mixins.json) | `client.mixin.fabric` 下的原生客户端接入 |

完整类名从配置中的 package、mixins 和 client 组合，目标与注入点由对应类声明。源码接线由[仓库工具](../../../tools/README.md)检查。Fabric 的 Loom 构建生成共享的 `unsuspiciousblock.refmap.json`，覆盖公共、平台与客户端注入；NeoForge 使用现有 ModDev 构建接线。

Lootr 的 `requiredMods` 与 NeoForge 元数据接线由 `multiloader-common.gradle` 展开，构建属性同时控制相关代码、服务声明和资源排除。Fabric 当前不声明 Lootr 配置；作用域见[配置与联动](config-and-integrations.md)。

## 机制入口

| 机制 | Mixin 职责 | 业务落点 |
|---|---|---|
| 玩家与容器状态 | 附加持久化或瞬态状态，桥接 NBT 与宿主回调 | [考古笔记](../subsystems/journal.md)、[猫族关系](../subsystems/cat-favor.md)、[方块与物品](../subsystems/blocks-items.md) |
| 战利品追踪 | 捕获刷拭、容器和钓鱼会话的原版调用边界 | [追踪链路](../internals/journal-tracking.md) |
| 条件与函数模拟 | 将实际条件与函数执行事件交给线程作用域和观测会话 | [战利品表机制](../internals/loottable-mechanics.md) |
| 客户端表现 | 读取客户端字段或转交动画、语言与界面事件 | [客户端与 GUI](../subsystems/client-ui.md) |
| Fabric 专用接入 | 补齐铁砧、刷拭、剪羊毛、骨块与第一人称淘洗事件 | [实体与 AI](../subsystems/entities-world.md)、[附魔](../subsystems/enchantment.md)、[淘洗](../subsystems/panning.md) |

函数捕获通过 `LootItemConditionalFunctionMixin` 和 `LootTableSplitterMixin` 转交 `FunctionTraceSession`。拆栈器包装稳定的 `createStackSplitter` 输入/输出 Consumer，使拆栈副本继承观测链，保持原版拆栈算法；合成 lambda 名及其调用点存在映射和 Loader 差异，注入选择该公共边界。

工具匹配使用真实 `TOOL` 栈与 `ItemPredicate`，附魔等级由参数写入工具栈。场景布尔维度按条件求值职责定义，普通游戏调用在作用域外执行原版逻辑。

## 典型模式

### 状态附加型

以 [`PlayerJournalStateMixin`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/core/mixin/journal/PlayerJournalStateMixin.java) 为例：

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

### 行为修改型

以 [`AnvilMenuMixin`](../../../fabric/src/main/java/com/meteorite/unsuspiciousblock/platform/mixin/container/AnvilMenuMixin.java) 为例（Fabric 独有）：

```java
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Shadow private int repairItemCountCost;
    @Final @Shadow private DataSlot cost;

    @Inject(method = "createResult", at = @At("RETURN"))
    private void ancientCoinRepair(CallbackInfo ci) {
        // 仅当原版未产生结果、右侧为古代金币时介入
        if (!result.isEmpty()) return;  // 不覆盖其他 mod 输出
        // AncientCoinRepair.calculate 返回输出、经验与金币消耗，宿主负责写回
    }
}
```

特点：`@At("RETURN")` 覆盖全部 return 路径，仅在结果为空时介入。计算归 common 的 `AncientCoinRepair`，Mixin 只写回结果槽、经验与 `repairItemCountCost`；NeoForge 事件消费同一结果。

### 接口注入型

`BrushableBlockEntityScanState` 等接口通过 mixin 注入到原版类（如 `BrushableBlockEntity`），暴露模组需要的方法（`isScanner` / `markScanned` / `resolveAndGetLoot`），供 `SuspiciousReaderItem` 等调用。支持待定日志的宿主另实现 `PendingJournalHost`；持久状态与 codec 归 `BrushableTracking` / `ContainerLootTracking`，Mixin 保留原注入、物品访问和更新通知。

## 扩展点：新增 Mixin

**先自问是否真的需要 mixin**：能由 Event API 表达的一律不用 mixin；只有 Fabric 原生事件缺失时才补 mixin，且优先只加在 Fabric 端（NeoForge 用事件）。

1. **确定归属**：common 公共注入 → `core/mixin/<子系统>/`；common 客户端 → `client/mixin/`；Fabric 公共注入 → `platform/mixin/`，客户端 → `client/mixin/fabric/`；Lootr → `core/mixin/compat/lootr/`。
2. **写入口**：只做"捕获时机 + 转交"，把参数整理后交给子系统里的普通类处理（如 `CatFavorManager`、`PanningAnimation`、`LootSimulationScope`）。不要在此写业务分支。
3. **注册进对应配置**：公共注入使用 common 或 Fabric 公共配置；客户端分别使用 `unsuspiciousblock.client.mixins.json` 或 `unsuspiciousblock.fabric.client.mixins.json` 的 `client` 数组。配置 package 限于专属 Mixin 包，保持目标与 refmap 配套。
4. 在对应子系统篇说明新增入口和业务所有者；跨子系统边界变化时同步本页。
5. 若需要访问原版私有成员，检查 `accesstransformer.cfg` / `unsuspiciousblock.accesswidener` 是否已放开（见「AccessTransformer / AccessWidener」）。

## 约束与陷阱

1. 按实际触发语义选择稳定且作用域小的注入点；返回阶段覆盖全部 return 路径，调用点注入需核对映射与 Loader 差异。
2. **仅在原版未处理时介入**：如铁砧 mixin 仅在结果槽为空时介入，避免覆盖其他 mod 的输出。
3. 原版分支与局部变量的拦截范围以各 Mixin 声明为准；业务语义由普通类持有。
4. **业务逻辑外置**：mixin 只做入口，复杂逻辑在专门类中。
5. **高频查询走缓存**：高频的能力查询（威慑、轻步）通过 `CatPassiveAbilities` 每 tick 计算位掩码缓存到状态，mixin 查询时不做库存扫描（见 [猫族关系系统](../subsystems/cat-favor.md)）。
6. **`Simulation*ConditionMixin` 保持单目标**：一对多的 mixin 在 Fabric remap 下会出错。
7. 完整类清单以对应配置为准，文档保留机制与接线入口。

## AccessTransformer / AccessWidener

部分 mixin 需要访问原版私有字段，通过平台访问扩展机制：

- common：`META-INF/accesstransformer.cfg`（AccessTransformer，两端共用）
- AccessWidener：`common/src/main/resources/unsuspiciousblock.accesswidener`（文件位于 common，由 Fabric 构建应用到 Fabric 端）

详见 [架构总览](architecture.md)。

## 相关文档

- [架构总览](architecture.md) —— 公共、客户端与 Lootr Mixin 配置与构建占位符
- [考古笔记系统](../subsystems/journal.md) —— journal/ 包 mixin
- [猫族关系系统](../subsystems/cat-favor.md) —— catfavor/ 包 mixin
- [战利品表系统](../subsystems/loottable.md) —— `NestedLootTableMixin` 与模拟条件作用域
- [实体与 AI](../subsystems/entities-world.md) —— Fabric 骨块追踪 mixin
- [配置与第三方联动](config-and-integrations.md) —— Lootr 兼容
- [淘洗系统](../subsystems/panning.md) —— 淘盘动画的两个 mixin 入口

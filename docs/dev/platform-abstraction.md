# 平台抽象层

本文档描述 `common` 如何通过 ServiceLoader SPI 机制访问 Fabric / NeoForge 平台能力，以及在依赖可选模组（Trinkets / Curios 等）时如何安全跨越类加载边界。

## 1. 设计动机

`common` 模块承载绝大部分玩法代码，但不得 import 任何平台类。当玩法逻辑需要"发包"、"查询饰品栏"、"读取配置"等平台相关能力时，采用经典的 **SPI（Service Provider Interface）模式**：

- `common` 定义接口（契约）
- `fabric` / `neoforge` 各自提供实现
- 运行时由 `ServiceLoader` 发现实现并注入

这与 Architectury 的 `@ExpectPlatform` 注解方案目标一致，但本项目选择显式接口 + `ServiceLoader`，优势在于：
- 无需注解处理器，构建更简单
- 一个实现类可实现多个接口（如配置类同时实现 `ILootTableConfig` 与 `ISpiritCatConfig`）
- 接口可携带常量与默认方法，集中表达领域约束

## 2. Services 加载器

入口是 [`platform/Services.java`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/Services.java)：

```java
public class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetworkHelper NETWORK = load(INetworkHelper.class);
    public static final IAccessoryHelper ACCESSORY = load(IAccessoryHelper.class);
    public static final ILootTableConfig LOOT_TABLE_CONFIG = load(ILootTableConfig.class);
    public static final ISpiritCatConfig SPIRIT_CAT_CONFIG = load(ISpiritCatConfig.class);
    public static final IEnchantmentEventAdapter ENCHANTMENT = load(IEnchantmentEventAdapter.class);
    public static final ICatEventAdapter CAT = load(ICatEventAdapter.class);
    public static final IBoneBlockTracker BONE_BLOCK_TRACKER = load(IBoneBlockTracker.class);

    public static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz).findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
    }
}
```

- 每个接口在类加载时通过 `ServiceLoader.load(clazz).findFirst()` 取第一个实现。
- 实现的注册靠 `META-INF/services/<接口全限定名>` 文件，文件内容为实现类全限定名。
- 由于 `fabric` 与 `neoforge` 是互斥的运行环境，每个接口在运行时只有一个实现，`findFirst()` 足够。

### 2.1 服务注册文件

每个平台在自己的 `src/main/resources/META-INF/services/` 下为每个 SPI 接口放一个文件。例如 Fabric 端：

```
fabric/src/main/resources/META-INF/services/
├── com.meteorite.unsuspiciousblock.platform.services.IPlatformHelper    -> FabricPlatformHelper
├── com.meteorite.unsuspiciousblock.platform.services.INetworkHelper     -> FabricNetworkHelper
├── com.meteorite.unsuspiciousblock.platform.services.IAccessoryHelper   -> FabricAccessoryHelper
├── com.meteorite.unsuspiciousblock.platform.services.ILootTableConfig   -> FabricLootTableConfig
├── com.meteorite.unsuspiciousblock.platform.services.ISpiritCatConfig   -> FabricLootTableConfig
├── com.meteorite.unsuspiciousblock.cat.adapter.ICatEventAdapter         -> FabricCatEventAdapter
├── com.meteorite.unsuspiciousblock.enchantment.framework.adapter.IEnchantmentEventAdapter -> FabricEnchantmentEventAdapter
└── com.meteorite.unsuspiciousblock.world.IBoneBlockTracker              -> FabricBoneBlockTracker
```

NeoForge 端结构对称，实现类替换为 `NeoForge*`。

> 注意：`ICatEventAdapter` 与 `IEnchantmentEventAdapter`、`IBoneBlockTracker` 虽然放在各自子系统的包下，但同样走 `Services` 的 `ServiceLoader` 机制，是 SPI 的一部分。

## 3. 八个 SPI 接口职责

| 接口 | 所在包 | 职责 | Fabric 实现 | NeoForge 实现 |
|---|---|---|---|---|
| `IPlatformHelper` | `platform.services` | 平台名、mod 加载检测、开发环境判断、游戏目录、mod 版本 | `FabricPlatformHelper`（`FabricLoader`） | `NeoForgePlatformHelper`（`FMLLoader`/`ModList`） |
| `INetworkHelper` | `platform.services` | 发送 C2S / S2C payload | `FabricNetworkHelper`（`ServerPlayNetworking`/`ClientPlayNetworking`） | `NeoForgeNetworkHelper`（`Connection.send`） |
| `IAccessoryHelper` | `platform.services` | 查询饰品栏装备（考古笔记/猫之瞳/标本箱是否装备） | `FabricAccessoryHelper`（Trinkets，缺失时返回空） | `NeoForgeAccessoryHelper`（Curios，缺失时返回空） |
| `ILootTableConfig` | `platform.services` | 战利品追踪前缀、单表日志上限、追踪超时 | `FabricLootTableConfig` | `NeoForgeLootTableConfig` |
| `ISpiritCatConfig` | `platform.services` | 猫国灵体生命周期与效果时长（信使/剑士/商人在场时间、无敌/抗性/火抗时长） | `FabricLootTableConfig` | `NeoForgeLootTableConfig` |
| `IEnchantmentEventAdapter` | `enchantment.framework.adapter` | 附魔事件钩子（注册附魔、监听附魔相关事件） | `FabricEnchantmentEventAdapter` | `NeoForgeEnchantmentEventAdapter` |
| `ICatEventAdapter` | `cat.adapter` | 猫族事件钩子（驯服、喂食、晨礼等猫相关事件） | `FabricCatEventAdapter` | `NeoForgeCatEventAdapter` |
| `IBoneBlockTracker` | `world` | 自然骨块追踪（记录世界生成的骨块，供化石猎手附魔判定） | `FabricBoneBlockTracker`（mixin + chunk 事件） | `NeoForgeBoneBlockTracker`（DataAttachment） |

> **例外**：`platform.services` 包内还有第 9 个接口 [`IAchievementHelper`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services/IAchievementHelper.java)（授予/查询/撤销成就）。它**不走 ServiceLoader**，由 common 的 [`VanillaAchievementHelper`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/VanillaAchievementHelper.java) 用原版 API 直接实现，在 `UnsuspiciousBlockCommon.init()` 中以构造参数注入 `AchievementManager`，双平台共用同一实现。

### 3.1 配置类双接口模式

注意 `ISpiritCatConfig` 在两个平台都由 `*LootTableConfig` 类实现——**同一个配置类同时实现两个接口**。这是因为项目把"战利品配置"和"灵体配置"放在同一份配置文件里管理，但 common 代码通过两个独立接口读取，保持职责分离。

接口内还集中定义了**领域约束常量**（范围与默认值），例如 `ILootTableConfig`：

```java
int MIN_MAX_LOG_ENTRIES_PER_TABLE = 64;
int MAX_MAX_LOG_ENTRIES_PER_TABLE = 4096;
int DEFAULT_MAX_LOG_ENTRIES_PER_TABLE = 512;

List<String> DEFAULT_ARCHAEOLOGY_PATH_PREFIXES = List.of(
        "archaeology/", "archeology/", "pots/",
        "minecraft:gameplay/fishing", ...);
```

两端共享这些常量，确保校验与重置行为一致。这是 SPI 模式相对于 `@ExpectPlatform` 的一个实际收益：契约与约束可以集中表达。

## 4. 可选依赖的安全加载

Trinkets、Curios、Artifacts 等是**可选联动**，`common` 编译期引用其 API，但运行时可能不存在。直接 `import` 会导致主入口在依赖缺失时类加载失败。

解决方案是 [`OptionalModIntegration`](../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/OptionalModIntegration.java)，用反射跨越边界：

```java
// 仅在平台确认 mod 已加载后调用
if (Services.PLATFORM.isModLoaded("trinkets")) {
    OptionalModIntegration.instantiate(
            "com.meteorite.unsuspiciousblock.plugin.trinket.FabricTrinketsIntegration",
            Runnable.class).run();
}
```

- `instantiate(className, contract)`：反射加载无参构造，校验为指定契约类型后返回实例。
- `invokeFactory(className, methodName, contract)`：调用无参静态工厂方法。

集成类（如 `FabricTrinketsIntegration`）在 `run()` 中才真正引用 Trinkets API，因此只有调用时才会触发对第三方类的解析。配合 `Services.PLATFORM.isModLoaded(...)` 的前置判断，保证依赖缺失时不会类加载失败。

> `IAccessoryHelper` 的实现内部同样遵循此原则：Trinkets/Curios 未安装时，所有查询返回 `false` / 空流，玩法降级为"未装备饰品"。

## 5. 新增 SPI 接口的步骤

当需要新增一个平台相关能力时：

1. **定义接口**：在 `common` 的合适包下定义接口（核心平台能力放 `platform/services/`，子系统专属能力放子系统包，如 `cat/adapter/ICatEventAdapter`）。
2. **Fabric 实现**：在 `fabric/` 写实现类，并在 `fabric/src/main/resources/META-INF/services/<接口全限定名>` 注册。
3. **NeoForge 实现**：在 `neoforge/` 写实现类，同样注册 service 文件。
4. **加载**：在 `Services.java` 加一行 `public static final IXxx XXX = load(IXxx.class);`，或由子系统 Manager 自行 `load`（如 `CatFavorManager.init(Services.CAT)`）。

> 如果新接口只是某个子系统的内部需求，不一定要放进 `Services` 全局字段，可以让该子系统的 Manager 在 `init` 时自行加载，减少全局耦合。

## 6. 相关文档

- [架构总览](architecture-overview.md)
- [注册架构](registration.md)
- [配置与第三方联动](config-integrations.md) - 各配置项的具体可调范围
- [猫族关系系统](cat-favor.md) - `ICatEventAdapter` 的使用
- [附魔系统](enchantment.md) - `IEnchantmentEventAdapter` 的使用

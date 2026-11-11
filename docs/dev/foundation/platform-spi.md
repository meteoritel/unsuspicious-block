# 平台抽象层

`common` 模块的跨平台玩法逻辑通过 `platform.services` 定义服务接口（SPI）访问 Loader 能力，由 `platform.Services` 在运行期经 `ServiceLoader` 装配；内容注册与客户端网络发送则分别通过独立适配器接口接入。

## 1. SPI 服务装配

[`Services`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/Services.java) 使用 `ServiceLoader.load(...).findFirst()` 加载各接口实现并缓存在静态字段中；若当前环境缺少提供者，立即 fail-fast 抛出加载异常。

各平台在 `src/main/resources/META-INF/services/<接口全限定名>` 文件中声明实现类全限定名。由于 Fabric 与 NeoForge 互斥运行，各自发布包仅打包本平台的服务声明与实现。

全部公共 SPI 接口位于 [`platform/services`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services)，平台实现位于对应源集的 `platform` 包下：

| SPI 接口 | 职责范围 |
|---|---|
| `IPlatformHelper` | 平台身份判断、可选模组检测、游戏根目录与战利品注入描述 |
| `INetworkHelper` | 服务端向指定玩家发送 S2C 网络包（C2S 发送见[网络与同步](network.md)） |
| `IAccessoryHelper` | 查询 Trinkets / Curios 饰品栏物品（未安装对应模组时返回未装备或空流） |
| `ILootTableConfig`、`ISpiritCatConfig` | 读取战利品追踪与灵体猫配置；双端由各自的同一配置类实现两项契约 |
| `IPanningConfig` | 读取独立的淘洗配置 |
| `ICatEventAdapter`、`IEnchantmentEventAdapter` | 将 Loader 原生事件转换为统一上下文并转交 `core` 业务入口 |
| `IBoneBlockTracker` | 查询、标记、消费与持久化自然骨块来源 |

配置 SPI 接口同时持有共享的默认值与范围约束常量，供双端实现复用；配置文件路径与生命周期见[配置与第三方联动](config-and-integrations.md)。

## 2. 客户端能力与注册适配器

- **客户端 SPI**：`IClientSimulationPreference` 等客户端专属服务位于 `client.platform`，其实现与服务声明仅面向客户端归属，不由公共 `Services` 加载（当前运行期模拟偏好由 `JournalUiPreferencesStore` 管理）。
- **客户端网络发送**：C2S 发送函数由平台客户端入口通过 `ModClientLifecycle.install` 安装到 `ClientNetwork`，使公共服务加载器完全独立于客户端类。
- **注册适配器**：`core.bootstrap.ModContent` 显式接收两端传入的注册适配器并按依赖顺序分发 `REGISTRY_MANIFEST` 清单，详见[注册架构](registration.md)。

## 3. 可选依赖的惰性安全加载

[`OptionalModIntegration`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/OptionalModIntegration.java) 提供带契约校验的无参反射工厂调用：

1. 调用方先通过 `Services.PLATFORM.isModLoaded("<modid>")` 确认目标可选模组已安装；
2. 确认存在后，再触发对应集成类的类加载与实例化。

Trinkets、Curios 与 Artifacts 的原生 API 调用隔离在各自平台的 `platform/plugin/` 包内；Jade 的中立发现桥 `JadePlugin` / `JadeClientPlugin` 位于 `common`，在构造期避免触发客户端实现类加载，具体装配见[配置与第三方联动](config-and-integrations.md)。

## 4. 扩展点：新增 SPI 接口

1. 在 `common` 的 `platform.services` 定义最小必要的平台能力接口，参数与返回值全部使用原版或 `common` 公共类型。
2. 在 `fabric` 与 `neoforge` 的 `platform` 包下分别编写实现，并在各自 `META-INF/services/` 注册提供者。
3. 在合适的装配入口加载服务（全局共享能力通过 `Services` 暴露；客户端专属能力置于 `client` 并由客户端入口装配）。
4. 运行 `python tools/checks/check-isolation.py` 与 `python tools/checks/check-wiring.py`，分别在可选模组存在与缺失的环境下核对双端行为。

## 5. 相关文档

- [架构总览](architecture.md)
- [注册架构](registration.md)
- [网络与同步](network.md)
- [配置与第三方联动](config-and-integrations.md)

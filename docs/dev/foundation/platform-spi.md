# 平台抽象层

common 的 core 通过 `platform.services` 定义的平台能力访问 Loader 实现；`platform.Services` 使用 ServiceLoader 装配。客户端发送由客户端入口安装，注册清单由独立的 registrar Adapter 接入。

## 包与装配

[`Services`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/Services.java) 在静态字段中保存平台能力。`load` 使用 `ServiceLoader.load(...).findFirst()`，缺少提供者时立即报告加载失败。

每个平台的 `src/main/resources/META-INF/services/<接口全限定名>` 声明提供者，内容为实现类全限定名。Fabric 与 NeoForge 互斥运行；发布包保留本平台提供者和 common 契约。

接口统一位于 [`platform/services`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/services)。平台原生实现位于各自源集的 platform；事件 Adapter 再按 cat、enchantment、world 等职责组织。

| 能力 | 职责与装配 |
|---|---|
| `IPlatformHelper` | 平台身份、可选模组检测、游戏目录与战利品注入描述 |
| `INetworkHelper` | 服务端向指定玩家发送 S2C payload；C2S 发送见[网络与同步](network.md) |
| `IAccessoryHelper` | 访问饰品栏；第三方模组缺失时返回未装备或空结果 |
| `ILootTableConfig`、`ISpiritCatConfig` | 读取战利品与灵体配置；两端同一配置类分别实现对应契约 |
| `IPanningConfig` | 读取独立的淘洗配置 |
| `ICatEventAdapter`、`IEnchantmentEventAdapter` | 绑定 Loader 事件并交给 core 业务入口 |
| `IBoneBlockTracker` | 骨块自然生成来源的查询、标记和平台持久化 |

配置 Interface 承载共享默认值与合法范围；两端读取和校验复用这些约束。文件位置与生命周期见[配置与联动](config-and-integrations.md)。


`IClientSimulationPreference` 与文件实现位于 `client.platform`，两端实现及服务声明按客户端职责归属。公共 `Services` 不加载它们；当前运行中的模拟偏好由 `JournalUiPreferencesStore` 保存。

## 注册 Adapter

`core.bootstrap.ModContent` 显式接收两端 registrar，声明注册内容及依赖顺序。平台持有即时或延迟注册、Item 回写队列与原生事件相位；注册 Interface 与服务装配分别管理，详见[注册架构](registration.md)。

## 可选依赖的安全加载

[`OptionalModIntegration`](../../../common/src/main/java/com/meteorite/unsuspiciousblock/platform/OptionalModIntegration.java) 提供受契约校验的惰性实例化与无参工厂调用。调用方先使用 `Services.PLATFORM.isModLoaded` 确认可选依赖，再触发实现类的加载。

Trinkets、Curios 与 Artifacts 的原生引用留在对应 Loader 的 platform/plugin，公共入口只通过共享契约使用。Jade 的中立发现桥位于 platform/plugin/jade，服务端 provider 位于 core，客户端 provider 由 client 安装回调；装配相位见[配置与联动](config-and-integrations.md)。

客户端发送能力由 `ModClientLifecycle.install` 接收并安装到 `ClientNetwork`。公共服务加载器和公共发送 Adapter 保持独立于客户端 Implementation。

## 扩展点：新增 SPI 接口

1. 在 common 的 `platform.services` 定义实际需要的平台能力，参数和返回值使用公共类型。
2. 在两端 platform 实现，分别登记 SPI 文件名及提供者。
3. 在实际装配点加载能力；已有全局能力由 `Services` 暴露，业务保持清晰的状态所有权。
4. 涉及客户端专属能力时，由客户端入口装配并保持实现位于 client。
5. 核对源码隔离、SPI 接线、可选依赖缺失环境和两端运行行为。

## 相关文档

- [架构总览](architecture.md)
- [注册架构](registration.md)
- [网络与同步](network.md)
- [配置与联动](config-and-integrations.md)

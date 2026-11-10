# 代码风格与架构约束规范

本文档是 Unsuspicious Block 模组 Java 源码的唯一代码风格与架构设计权威。所有新增或修改的 Java 代码均必须严格遵守本文档约定的规范。

---

## 1. 基础语法与环境规范

- **Java 版本**：统一使用 Java 21。
  - 数据传输载体优先使用 `record`；
  - 类型判断与分支优先使用 Pattern Matching for `instanceof` 和 `switch`；
  - 避免无意义的封箱与临时对象频繁分配。
- **编码与换行**：统一使用 UTF-8 编码，行尾换行符统一使用 LF（禁止 CRLF）。
- **缩进与排版**：标准 4 空格缩进，禁止使用 Tab 制表符；文件末尾保留单空行；移除行末多余空白字符。

---

## 2. 引用与导入规范（硬性禁止全限定名）

1. **强制显式导入**：
   - 所有类、接口、注解及枚举必须在文件头部显式 `import`，**严禁**在方法签名、变量声明或代码逻辑中使用全限定类名（如 `net.minecraft.world.level.block.Block`）。
   - 静态导入（`import static`）仅推荐用于断言、常量（如 `Constants.*`）或流畅构建器（如 `TooltipBuilder` 语义常量），禁止大面积滥用通配符静态导入。
2. **同名类型冲突处理**：
   - 当遇到同名类（如自定义类与 Minecraft/平台类重名）时，将主要业务实体正常 `import`，辅助类型可提取局部别名封装或通过内部接口隔离，避免在方法签名中直接裸写全限定路径。

---

## 3. 命名与包结构规范

1. **包结构划分**：
   - 根包保留 `Constants` 与 Loader 公共入口；common 的生产代码按 `core / client / platform` 划分，core 内继续按玩法子系统组织；
   - 客户端专有逻辑必须置于 `client/` 或其子包下；
   - 跨平台抽象服务接口置于 `platform/services/`。
2. **标识符命名**：
   - **类与接口**：大驼峰（PascalCase）。
     - 注册持有类统一使用 `ModBlocks`、`ModItems`、`ModBlockEntities`、`ModEntities`、`ModPayloads` 等；
     - 无状态业务逻辑使用 `XxxService`；
     - 带状态或持久化管理使用 `XxxManager`；
     - 自定义方块实体使用 `XxxBlockEntity`。
   - **方法与变量**：小驼峰（camelCase）。
   - **常量与枚举**：大写下划线（UPPER_SNAKE_CASE）。
3. **禁止裸魔法值**：
   - 业务逻辑中的数字、固定字符串标识符、网络通道 ID 必须收拢为命名常量或领域枚举，优先复用项目中已有的常量池。

---

## 4. 多模块架构与 Loader 隔离（铁律）

本项目采用 Architectury 风格同源多模块设计：

```
unsuspiciousBlock-1.21.1-multi/
├── common/      跨平台核心（玩法、注册清单、数据资源、网络协议、客户端 UI）
├── fabric/      Fabric 适配（平台入口、即时注册、Fabric SPI 实现、专用 Mixin）
└── neoforge/    NeoForge 适配（平台入口、延迟注册、NeoForge SPI 实现、GLM 与事件）
```

1. **`common` 模块隔离禁令**：
   - **绝对禁止**在 `common` 模块中 import 任何 `net.fabricmc.*` 或 `net.neoforged.*` 包。
   - 自动化门禁 `python tools/check-isolation.py` 将强制检测该项，违规将导致构建失败。
2. **平台能力抽象 (SPI)**：
   - 当 `common` 需要访问平台特有能力（如判断是否安装某模组、访问平台配置路径、获取饰品栏物品等）时，必须在 `common/.../platform/services/` 中定义服务接口（如 `IPlatformHelper`）。
   - 由 `fabric` 与 `neoforge` 分别实现该接口，并通过 `META-INF/services/` 文件注册。
3. **允许平台异构对齐**：
   - 两个平台不必追求代码完全同构。例如 NeoForge 使用 `IGlobalLootModifier`（GLM）注入战利品，Fabric 使用 `LootTableEvents` 或 Mixin 注入；只要玩家可见语义一致即可。

---

## 5. 端边界与客户端物理隔离 (Client/Server Separation)

1. **客户端代码边界**：
   - 任何涉及渲染（`GuiGraphics`、`RenderSystem`）、界面（`Screen`）、声音实例、模型及客户端状态（`Minecraft.getInstance()`）的代码必须严格限制在 `client/` 包或平台客户端入口。
2. **服务端与公共执行面防污染**：
   - 服务端逻辑与公用数据类型严禁直接静态引用客户端类，防止在专用服务端（Dedicated Server）启动时触发 `NoClassDefFoundError`。
3. **网络包跨端解耦**：
   - 网络 Payload（`CustomPacketPayload`）仅承载序列化数据，其实例属于跨端共用。
   - `core.network.ModPayloads` 通过公共 `ClientPayloadReceiver` 契约声明 S2C 分发；`client.network.ClientPayloadHandlers` 实现状态处理，客户端入口显式绑定。C2S 发送由客户端入口安装到 `ClientNetwork`。

---

## 6. Javadoc 与代码注释规范

1. **Javadoc 覆盖标准**：
   - 所有公开类（`public class`、`interface`、`enum`、`record`）必须包含类级 Javadoc，简明阐述职责；
   - 核心公开方法、SPI 接口方法、非自解释算法必须附带方法级 Javadoc，说明参数边界、返回值含义及抛出条件。
2. **语言与内容标准**：
   - 注释语言统一使用**简体中文**，原版与第三方库的专有名词保留英文；
   - **重点解释“为什么这样做 (Why)”**：说明设计原因、业务假设、线程限制与避坑点；**禁止单纯复述代码（What）**。

---

## 7. Minecraft 业务契约一致性

1. **文本与 Tooltip 呈现**：
   - 必须严格遵守 [`text-format.md`](../dev/foundation/text-format.md) 约定的五段式行序结构；
   - 颜色输出必须统一从 `TooltipBuilder` 语义色表常量（如 `TITLE`、`POSITIVE`、`LABEL`、`HINT` 等）提取，**严禁**直接选用 `ChatFormatting` 或手写十六进制颜色；
   - HUD 与界面文本必须使用本地化键，并在 `en_us.json` 与 `zh_cn.json` 中保持同步。
2. **日志与诊断**：
   - 日志统一使用 SLF4J 的 `Constants.LOG`；
   - 日志输出内容必须拼接 `Constants.LOG_TAG` 前缀（`"[" + MOD_ID + "] "`），确保崩溃报告与整合包日志可追溯；
   - 严禁使用 `System.out.println`、`e.printStackTrace()` 或吞掉异常。
3. **注册回写机制**：
   - 遵循 Fabric 端即时回写与 NeoForge 端延迟回写的平台既有契约，遵循 [`registration.md`](../dev/foundation/registration.md) 模式。

---

## 8. 相关文档

- [文档撰写风格指南](style-documentation.md)
- [开发流程](development.md)
- [架构总览](../dev/foundation/architecture.md)
- [文本格式规范](../dev/foundation/text-format.md)

# 代码风格与架构约束规范

本文档是 Unsuspicious Block 模组 Java 源码的唯一代码风格权威。所有新增或修改的 Java 代码均必须严格遵守以下规范；模块划分、注册流程与网络协议等架构细则见对应的基础设施文档。

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
   - 当遇到同名类（如自定义类与 Minecraft/平台类重名）时，将主要业务实体正常 `import`，辅助类型通过局部包装或内部接口隔离，避免在方法签名中直接裸写全限定路径。

---

## 3. 命名与包结构规范

1. **包结构划分**：
   - 根包保留 `Constants` 与各 Loader 公共入口；`common` 的生产代码按 `core / client / platform` 划分，`core` 内继续按玩法子系统组织。
   - 客户端专有逻辑必须置于 `client/` 或其子包下。
   - 跨平台抽象服务接口（SPI）置于 `platform/services/`。
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

## 4. 模块、Loader 与端隔离红线

项目三模块（`common` / `fabric` / `neoforge`）的职责与初始化架构见[架构总览](../dev/foundation/architecture.md)与[平台抽象](../dev/foundation/platform-spi.md)，编码时遵守以下三条代码级红线：

1. **`common` 零 Loader 依赖**：`common/` 源码**绝对禁止** import 任何 `net.fabricmc.*` 或 `net.neoforged.*` 包，平台能力一律经 `platform/services/` SPI 接口调用。该规则由 `python tools/checks/check-isolation.py` 强制检验。
2. **服务端执行面与客户端物理隔离**：
   - `core` 与公共 `platform` 严禁静态引用渲染（`GuiGraphics`、`RenderSystem`）、界面（`Screen`）、模型或 `Minecraft.getInstance()` 等客户端类，避免专用服务端出现 `NoClassDefFoundError`。
   - 需要客户端能力的公共交互通过桥接接口（如 `ClientTooltipBridge`）在客户端初始化时安装。
   - 网络通道声明与端绑定边界见[网络与同步](../dev/foundation/network.md)。
3. **允许平台异构实现**：Fabric 与 NeoForge 可采用各自最合适的 Loader 机制（例如 NeoForge GLM vs Fabric 注入/事件），只要玩家可见语义一致即可。

---

## 5. Javadoc 与代码注释规范

1. **Javadoc 覆盖标准**：
   - 所有公开类（`public class`、`interface`、`enum`、`record`）必须包含类级 Javadoc，简明阐述职责；
   - 核心公开方法、SPI 接口方法、非自解释算法必须附带方法级 Javadoc，说明参数边界、返回值含义及抛出条件。
2. **语言与内容标准**：
   - 注释语言统一使用**简体中文**，原版与第三方库的专有名词保留英文；
   - **重点解释“为什么这样做 (Why)”**：说明设计原因、业务假设、线程限制与避坑点；**禁止单纯复述代码（What）**。

---

## 6. Minecraft 业务契约与日志

1. **文本与 Tooltip**：
   - 行序结构、颜色语义常量与本地化键命名严格遵循[文本格式规范](../dev/foundation/text-format.md)。
   - 颜色统一从 `TooltipBuilder`、UI Kit `UiTextScheme` 或 `JournalTextColors` 取值；HUD 与界面文本使用本地化键并在 `en_us.json` 与 `zh_cn.json` 保持同步。
2. **注册流程**：新增可注册内容统一遵循[注册架构](../dev/foundation/registration.md)的「清单 + 回调」模式。
3. **日志与诊断**：
   - 日志统一使用 SLF4J 的 `Constants.LOG`，并拼接 `Constants.LOG_TAG` 前缀（`"[" + MOD_ID + "] "`），确保崩溃报告与整合包日志可追溯。
   - 严禁使用 `System.out.println`、`e.printStackTrace()` 或吞掉异常。

---

## 7. 相关文档

- [文档撰写风格指南](style-documentation.md)
- [开发流程](development.md)
- [架构总览](../dev/foundation/architecture.md)
- [平台抽象](../dev/foundation/platform-spi.md)
- [网络与同步](../dev/foundation/network.md)
- [注册架构](../dev/foundation/registration.md)
- [文本格式规范](../dev/foundation/text-format.md)

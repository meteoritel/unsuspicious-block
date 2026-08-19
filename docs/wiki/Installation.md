# 安装与运行环境

> 适用于 Minecraft 1.21.1 和 Unsuspicious Block 1.5.1。

## 必要条件

- Minecraft 1.21.1
- Java 21
- Fabric Loader 0.17.0 及以上，或 NeoForge 21.1.195 及以上
- Fabric 版本需要安装 Fabric API；NeoForge 版本没有额外的必需依赖

## 单人游戏

1. 安装对应的 Fabric 或 NeoForge 启动器配置。
2. 将与加载器匹配的 Unsuspicious Block `.jar` 放入 `mods` 文件夹。
3. Fabric 用户同时放入 Fabric API。
4. 启动 Minecraft 1.21.1，并在主菜单或模组列表中确认模组已加载。

## 多人游戏

客户端和服务端都必须安装本模组，并使用相同的 Minecraft、加载器和模组版本。只有客户端安装时，考古笔记的同步数据、扫描和发掘状态无法正常工作。

## 可选联动

以下 Mod 不是运行本模组的必要条件：

- **JEI**：查看配方，并显示纹饰陶轮台的专用配方分类。
- **REI / EMI**：可用于查看普通配方；本模组不强制依赖它们。
- **Jade**：查看已经扫描出的可疑方块战利品。
- **Trinkets**（Fabric）或 **Curios**（NeoForge）：为考古笔记、猫之瞳和标本箱提供饰品栏位置。
- **Artifacts**：让标本箱代理兼容饰品的效果。
- **Lootr**：NeoForge 构建提供可选兼容。

## 更新或更换加载器

更换 Fabric 与 NeoForge 时，请保留世界备份。两端的配置文件位置和可选联动方式不同，不要把一种加载器的配置文件直接复制到另一种加载器中。

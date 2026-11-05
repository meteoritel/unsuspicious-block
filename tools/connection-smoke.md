# 本地双端连接测试

`connection_smoke.py` 编译当前源码，启动真实独立服务端和真实模组客户端，通过 `127.0.0.1` 自动连接。默认依次测试 NeoForge、Fabric；每种 Loader 连接两次，每次进入世界后观察 30 秒。

## 使用

需要 Java 21、Python 3 和可显示 Minecraft 窗口的 Windows 桌面。首次准备环境需要下载 Gradle 依赖及游戏资源。

在项目根目录执行：

```powershell
python tools/connection_smoke.py --loader all --accept-eula
```

`--accept-eula` 表示接受 Minecraft EULA，仅写入本次测试目录的 `eula.txt`。服务端使用离线身份，并且只监听本机地址。

常用参数：

```powershell
# 只测试 NeoForge，使用已经缓存的依赖
python tools/connection_smoke.py --loader neoforge --offline --accept-eula

# 只测试 Fabric，每次保持在线 60 秒
python tools/connection_smoke.py --loader fabric --seconds 60 --accept-eula

# 更换本地端口
python tools/connection_smoke.py --loader all --port 25576 --accept-eula
```

测试期间客户端窗口会自动打开、连接和关闭。保持窗口开启，等待命令结束。

## 判定与结果

每次连接必须满足：

1. 独立服务端完成启动。
2. 服务端记录 `ConnectionTest joined the game`。
3. 客户端生成本次 Quick Play 世界进入记录。
4. 观察期间双端持续运行，玩家保持连接，日志中没有匹配到握手或 packet 编解码错误。
5. 客户端关闭后，服务端记录断开连接；下一轮重新启动客户端并连接同一个服务端。

所有 Loader 通过时进程退出码为 `0`，任意一个失败时为 `1`。

每次测试使用独立目录：

```text
build/connection-smoke/<loader>/<时间戳>/
  result.json                # PASS/FAIL、每次进服记录、失败原因
  prepare.log                # Gradle 编译和启动配置准备日志
  server-console.log         # 独立服务端日志
  client-1-console.log       # 第一次连接的客户端日志
  client-2-console.log       # 第二次连接的客户端日志
  server/                    # 测试世界和服务端配置
  client/                    # 客户端配置、Quick Play 记录和游戏日志
```

`connection-smoke.init.gradle` 复用项目现有的 Loader 依赖与启动参数，导出启动命令；Python 直接管理游戏进程。

## 覆盖范围

测试对象是当前源码的开发运行环境，以及项目 Gradle 中配置的运行时依赖。连接成功覆盖独立服务端启动、Loader 握手、进入世界、登录期间的数据同步和客户端重连。发布 JAR、正版登录认证、公网端口转发及额外整合包依赖需要使用对应环境另外验证。

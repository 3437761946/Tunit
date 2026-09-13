# Tunit

> 面向触屏优化的 2D 瓦片地图编辑器（基于 [NotTiled](https://github.com/wandsmire/NotTiled) 1.8.6 二次开发）。
> 内置多人实时协作与独立无头服务端。

Tunit 是一个针对触屏设备优化的 2D 地图编辑器，可用于为支持自定义地图的游戏（如 Rusted Warfare、Remixed Dungeon、Stardew Valley 等）制作地图。
它在 NotTiled 1.8.6 的基础上增加了**实时多人协作**、**独立开服端**等功能。

## 功能特性

- 地图编辑：创建/编辑 `tmx`、`json` 地图，导入导出 `png`
- 内置 PNG 编辑器、随机地图生成器、图块集生成器
- 多人实时协作：房间制，含房主审批、成员禁编、地图同步自检等
- 独立无头服务端：纯中继 + 多房间管理，不存储地图，地图由房主客户端推送
- 客户端可选自动更新（走联机端口或独立 HTTP 端口）

## 项目结构

```
app/                            Android 客户端模块
server/                         无头开服模块（开服包源码）
NotTiled_src/NotTiled-1.8.6/    底座上游源码（编译期直接引用）
gradle/                         Gradle Wrapper
LICENSE                         GNU GPLv3 全文
NOTICE                          来源 / 署名 / 许可沿革
```

> 说明：`release_server/`（开服包部署目录）为构建产物，未纳入仓库；执行下面的服务端构建任务即可生成。

## 环境要求

- JDK 11+（推荐 17 / 21）
- Android SDK（`compileSdk 37`，`minSdk 19`，`targetSdk 33`）

## 构建客户端

```bash
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

### 服务器地址（构建期注入）

真实服务器地址**不写死在源码**中。构建时从 `app/server.properties` 读取并注入 `BuildConfig`；
该文件已被 `.gitignore` 排除，缺失时回退为占位符 `0.0.0.0`（此时「连接服务器」需手动填写地址）。

```
serverHost=your.server.host   # 可填域名或 IP
serverPort=40686
```

### 签名

编译需要签名密钥，请在 `app/keystore/` 下二选一：

- **正式签名**：提供 `app/keystore/keystore.properties`，内容示例：

  ```
  storeFile=keystore/release.jks
  storePassword=你的口令
  keyAlias=你的别名
  keyPassword=你的口令
  ```

- **调试签名**：放置一个标准 Android 调试密钥到 `app/keystore/debug.jks`。

> `app/keystore/` 下的所有密钥与口令配置均已被 `.gitignore` 排除，不会随源码公开。

## 构建与运行服务端

```bash
gradlew.bat :server:deployServer
```

产物：`release_server/Re-NotTiled-Server.jar`（附带示例插件 `plugins/sampleplugin.jar`）。

运行：

```bash
java -jar Re-NotTiled-Server.jar             # 读取同目录 server.properties
java -jar Re-NotTiled-Server.jar --help      # 查看全部参数
java -jar Re-NotTiled-Server.jar 40686 0     # 位置参数：TCP / UDP 端口（0 = 关闭 UDP）
```

`server.properties` 常用键（缺省时使用内置默认值）：

| 键 | 说明 |
|----|------|
| `tcp` / `udp` | TCP / UDP 端口（`udp=0` 为纯 TCP，推荐） |
| `maxRooms` / `maxPlayersPerRoom` / `maxConnections` | 房间数 / 每房人数 / 总连接数 |
| `maxMapPushMB` | 单次整图推送上限（MB） |
| `roomIdleTimeoutMin` | 空闲房间回收时间（分钟） |
| `hostGraceSec` | 房主断线宽限保留（秒） |
| `httpPort` / `updateDir` / `updateOverGame` | 客户端更新服务相关 |
| `pluginsDir` / `enablePlugins` | 插件目录与开关（插件以服务器权限运行，注意安全） |
| `registerTimeoutSec` / `newConnLimitPer10s` | 僵尸连接清理 / 同 IP 建连频率限制 |

> 服务端不存储地图，仅按房间转发；地图始终由房主客户端推送。

## 许可与致谢

- 本项目整体按 **GNU GPLv3（或更新）** 发布，许可全文见 [LICENSE](LICENSE)。
- 来源、署名与许可沿革见 [NOTICE](NOTICE)：
  - 底座为 **NotTiled**（作者 Reza Mirwanda，现行为 GPLv3+）；其是独立实现，**受** Tiled Map Editor（Thorbjørn Lindeijer）**启发**，并非基于 Tiled 源码。
  - 上游 1.8.6 版本当时按 CC BY-SA 4.0 发布，与 GPLv3 兼容。
- 对外分发时请遵守 GPLv3：保留署名，并提供完整对应源码。

# 1.10.11 跨版本功能同步记录

日期：2026-10-15

## 根因和修改范围

项目有 Yarn（Fabric 1.21.x）与 Mojmap（Fabric 26.x、NeoForge 各版本）两套实现。
之前逐版本启动检查没有核对功能清单，导致 Mojmap 实现缺少部分榜单、指令、配置和语言说明。
名字颜色三种模式本来两套实现都有，但 Mojmap 的配置注释和帮助没有准确说明它影响聊天、TAB 和头顶名牌。

本次同步配置、榜单、指令、帮助、语言及相关运行时逻辑，保留各平台已有的游戏 API 兼容层和私有侧边栏包实现。
此同步内容作为 1.10.11 发布版本的一部分。

| 补齐内容 | 同步后的行为 |
| --- | --- |
| 繁殖榜 | 读取 animals_bred 统计，支持名称、颜色及测试数据指令 |
| 摸鱼榜 | 静止达到三分钟后累计时间，保存长整型计数，侧边栏和网页按时间显示 |
| 破基岩榜 | 默认关闭，可由 OP 开启，记录玩家附近消失的基岩，位于榜单列表末尾 |
| 个人进服菜单 | `/leaderboard joinmenu true/false/status`，按 UUID 保存玩家选择 |
| 分组帮助与榜单介绍 | 补齐 `/leaderboard help config` 及子分组、`/leaderboard intro` |
| 网页共享端口 | 后启动的实例使用内部中继，公开网址端口相同，通过 server query 切服 |
| 网页配置 | 补齐共享端口中继、OP 切服名称提醒两个开关 |
| 名字颜色说明 | 全部位置着色、全部关闭、仅排行榜着色三种模式说明一致 |

使用名字颜色模组时，建议由 OP 执行：

```text
/leaderboard namecolor scoreboard-only
```

主配置对应 `config/rankboard/rankboard.properties` 中的
`scoreboard-name-color-enabled=scoreboard-only`。该模式保留排行榜内着色，聊天、TAB 与头顶名牌采用原本的名字显示。

## 构建及归档

四个直接构建的家族 JAR 已成功生成，无内嵌子版本 JAR。

| 加载器 | 游戏系列 | JAR | 本地归档 | SHA-256 |
| --- | --- | --- | --- | --- |
| Fabric | 1.21.x | `rankboard-1.10.11+fabric+mc1.21.x.jar` | `mod-builds/20261015-094602` | `83175FFE6F3675842857DAB7ECDF88F7EC770C6B1AA1D2C32C696CF7000B22D5` |
| Fabric | 26.x | `rankboard-1.10.11+fabric+mc26.x.jar` | `mod-builds/20261015-094509` | `5CB77A960727E3865F60D0B4CDC9DDAC7C421E806FAA02A3BF847306EB694A71` |
| NeoForge | 1.21.x | `rankboard-1.10.11+neoforge+mc1.21.x.jar` | `mod-builds/20261015-094712` | `42E20A48B8758D2D2CAD625EC737376452F51842E46163952029D502F2B6D8C7` |
| NeoForge | 26.x | `rankboard-1.10.11+neoforge+mc26.x.jar` | `mod-builds/20261015-094643` | `6C2045055158AAE915093CADA5C3DA4698441A2237260EE5F19909C5EAD970A2` |

Fabric 构建需要 JDK 25 来运行当前 Loom，1.21.x 的输出仍使用 Java 21 字节码。CI 的 Fabric 1.21.x 运行 JDK 已据此修正。

## 功能回归

以下四组实际协议客户端进服回归在 1.10.10 功能同步构建中通过；1.10.11 仅更新版本元数据，本轮另外用 1.10.11 JAR 重跑了下方完整启动矩阵：

| 加载器 | 游戏版本 | 结果文件 |
| --- | --- | --- |
| Fabric | 1.21.4 | `.tmp/fabric-direct-smoke-20261013-230755/test-results.json` |
| Fabric | 26.1.2 | `.tmp/fabric-direct-smoke-20261013-230608/test-results.json` |
| NeoForge | 1.21.4 | `.tmp/neoforge-direct-smoke-20261013-230752/test-results.json` |
| NeoForge | 26.1.2 | `.tmp/neoforge-direct-smoke-20261013-231026/test-results.json` |

测试覆盖简洁进服提示、个人菜单开关、配置和帮助指令、手动切榜与轮播、外部队伍成员及聊天前缀保留、繁殖榜覆盖值、实际 AFK 累计、实际破基岩累计和网页榜单接口。
外部名字管理通过原版队伍、绿色名字及 EXTERNAL 聊天前缀模拟；没有使用用户未提供的名字颜色模组 JAR。

Fabric 26.3 双实例共享网页端口测试通过：两个实例均通过端口 19876 访问，server query 正确路由各自的站点和榜单。
结果：`.tmp/shared-web-port-20261013-230914/test-results.json`。
现有协议客户端缺少 26.3 协议数据，因此该版本的自动进服测试没有完成；服务端启动、网页、控制台指令和双实例切服已验证。

飞行距离数学回归和简洁进服配置/中英文按钮回归通过。Gradle 的 test 任务仍显示 NO-SOURCE，相关测试单独运行。

## 防止再次漏同步

`scripts/check-feature-parity.py` 检查两套实现的 97 个配置默认值、71 个指令名称、20 个榜单及顺序、255 个语言/帮助条目，并检查 Fabric/NeoForge 的运行时统计生命周期接线。
该检查已接入构建及两个发布工作流，发现清单不同会使工作流失败。清单检查不能替代实际功能回归。

复测示例：

```powershell
python scripts/check-feature-parity.py
./scripts/smoke-fabric-direct.ps1 -ModJar <Fabric家族JAR> -Versions 26.1.2 -FeatureParityRegression
./scripts/smoke-neoforge-direct.ps1 -ModJar <NeoForge家族JAR> -Versions 26.1.2 -FeatureParityRegression
./scripts/test-shared-web-port.ps1 -ModJar <Fabric26.x家族JAR> -Version 26.3
```

## 完整启动矩阵

本轮使用 1.10.11 JAR 重新启动的 34 个小版本全部通过服务端启动及网页 HTTP 200 检查；此矩阵不包含玩家登录。

| 加载器 | 游戏版本 | 结果文件 |
| --- | --- | --- |
| Fabric | 1.21 至 1.21.11（12 个） | `.tmp/fabric-direct-smoke-20261015-094727/test-results.json` |
| Fabric | 26.1、26.1.1 | `.tmp/fabric-direct-smoke-20261015-095323/test-results.json` |
| Fabric | 26.1.2、26.2、26.3 | `.tmp/fabric-direct-smoke-20261015-095425/test-results.json` |
| NeoForge | 1.21 至 1.21.11（12 个） | `.tmp/neoforge-direct-smoke-20261015-095600/test-results.json` |
| NeoForge | 26.1、26.1.1、26.1.2、26.2、26.3 | `.tmp/neoforge-direct-smoke-20261015-100345/test-results.json` |

这些矩阵检查不包含每个小版本的玩家登录。实际登录功能测试是上方列出的四组，另有 Fabric 26.1.2 长距离飞行榜和增量侧边栏回归通过，结果为 `.tmp/fabric-direct-smoke-20261013-232647/test-results.json`。

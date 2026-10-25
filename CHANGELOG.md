# Changelog

## 2.1.0

- 合并上游 2.0.0 的多加载器能力与本分支 1.10.3 的菜单、历史分片、日期范围、生物受害榜等特性。
- 新增 Minecraft 26.3 构建目标（Fabric 与 NeoForge），并保留 1.21–1.21.11、26.1、26.2 支持。
- 引入上游的榜单缓存差异比较、数据过期检查、单玩家/全部应用新结果。
- 引入上游的摸鱼榜、破基岩榜、飞行距离记录、同端口多服务器网页切换、聊天语言切换与玩家目录兼容层。
- 保留本分支的分层菜单、抬头蹲起入口、按自然周期与滚动日期查询、按月历史分片和 `HistorySnapshotStore` 测试。
- 修正网页统计周期选项，恢复最近一季度与最近一年。
- 统一配置项、榜单分类与默认值，新增榜单已纳入“建造/常用”分类。

## 2.0.0

- Fabric 与 NeoForge 合并为一个直接构建的 JAR，支持 Minecraft 1.21、1.21.1–1.21.11，以及 26.1、26.1.1、26.1.2、26.2、26.3；文件名移除加载器组合后缀。
- 新增启动时加载器与游戏版本识别，隔离各平台实现与 Mixin，旧版 Java 21 加载器可安全扫描统一包。
- 增加榜单缓存重读和差异比较，支持查看玩家差异、确认后应用单玩家或全部新结果，并检查数据是否已过期。
- 优化统计读取与榜单刷新，复用同一刷新周期的统计结果，减少重复计算和反射；缓存异步写入，停服前保存在线玩家最终数据。
- 修复 NeoForge 生命周期初始化与停服保存，并补充假人反复加入/退出、榜单发包和多版本启动回归。
- 更新单 JAR 自动构建流程，推送和拉取请求只构建统一产物。

## 1.10.11

- 补齐 Fabric 26.x 与 NeoForge 各版本的功能：繁殖榜、摸鱼榜、破基岩榜及配置、个人进服菜单开关、同端口网页服务器中继、OP 切服名称提醒，以及分组帮助和语言说明。
- 明确名字颜色模式：`true` 同步排行榜、聊天、TAB 和头顶名牌；`false` 全部关闭；`scoreboard-only` 仅在排行榜内着色，方便与名字颜色模组共存。
- 对齐 Fabric 1.21.x、Fabric 26.x、NeoForge 1.21.x 和 NeoForge 26.x 的配置、指令、榜单顺序、语言及运行时统计功能。
- 自动构建新增跨版本功能清单检查，并增加多版本服务端启动及网页连通性回归。

## 1.10.10

- Added a simple join experience by default: regular players see one clickable leaderboard entry; operators retain their administration controls, and help documents the mode switch.
- Fixed scoreboard refresh flicker by updating only changed rows instead of recreating the whole display.
- Extended flight-distance tracking beyond the integer limit, standardized kilometer display, and added an operator command for editing leaderboard values during testing.
- Consolidated Fabric and NeoForge builds into one direct JAR per supported family (`1.21.x` and `26.x`) and added non-publishing CI builds for pushes and pull requests.

## 1.10.6

- Fixed cross-patch Minecraft API linkage for the single 1.21.x Fabric and NeoForge JARs, including player profile/avatar data, persistent state, NBT, component lookup, command permissions, registry identifiers, and statistics.
- Simplified the join leaderboard prompt for regular players to one clickable “Expand leaderboard info” button. Operators keep the detailed join menu and its whitelist, scoreboard cleanup, language, and administration controls.
- Updated the all-version compatibility audit and added isolated server/player-join smoke checks for supported version families.

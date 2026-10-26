# Changelog

## 2.3.2

- 修复“添加机器人后不显示”：`botgroup add` 现在从在线玩家、usercache、白名单、OP 名单解析 UUID，即使统计名仍是 `unknown_` 也能按 UUID 归入机器人组。
- 网页“包含机器人”改为按请求显式生效：关闭时普通榜不再被服务端混合设置漏出机器人；开启时包含手动与 `bot_` 机器人。
- 机器人类型在普通榜、混合榜、机器人榜都显示，不再只在机器人视图显示。
- 网页榜单分类收纳：默认只显示 9 个常用榜单，战斗/建造/生存/探索/生物受害用标签折叠，点击展开；平铺/紧凑/普通视图共用，表格与紧凑视图的列也只显示常用 + 当前分类。
- 修复分类侧栏常用榜单与分类组重叠、移动端两列错位的问题。

## 2.3.1

- 网页普通榜单新增“包含机器人”开关与 `includeBots=true` 参数，混合显示时机器人带 `BOT` 标记。
- 游戏内混合榜改为“真人区 + `──────── BOT 机器人 ────────` 分隔线 + 机器人区”，不再只在行尾加标记。
- 机器人添加建议过滤掉 `unknown_` 条目，并补充 usercache/白名单/OP 名单中的名称。
- 新增 `/leaderboard lookup unknown`：批量向 Mojang 补全 `unknown_` UUID 的名称。
- 缓存名称自动刷新：启动时及运行中每 30 秒从 usercache/白名单/OP 名单回填，修复机器人重启后长期显示 `unknown_` 的问题。

## 2.3.0

- 机器人行为类型：按主要统计自动标记攻击型、挂机型、点击型、农民型、探索型或混合型，游戏内机器人榜/名单与网页徽标显示。
- 机器人效率榜：`/leaderboard bots rate <周期> <榜单>` 按在线时长归一化，输出每小时击杀、放置、挖掘等；网页机器人视图增加“每小时”开关。
- 机器人混合显示：`/leaderboard botgroup mixed <true|false>` 可让机器人同时进入普通聊天榜、侧边栏与网页，并保留 `[BOT]` 标记；默认关闭。
- 网页机器人视图增加 `BOT·类型` 徽标、`rate=true` 接口与效率模式（自动切换为单列列表）。
- 清理：移除 `botgroup status`（名单已含类型与数量）、无用的网页底部 CSS、`range` 多余参数与重复的机器人汇总行。

## 2.2.0

- 新增机器人分组：`/leaderboard botgroup add|remove|list|clear|status` 可把任意玩家（如 Carpet 假人）移入机器人分组；普通榜单隐藏，`/leaderboard bots <周期> <榜单>` 单独显示并标记 `[BOT]`。
- 机器人分组不受服务器白名单与 RankBoard 独立白名单限制；扫描、缓存、游戏内与网页都会保留机器人统计。
- 网页新增“机器人分组”开关和 `BOT` 徽标；机器人榜接口支持 `bots=true`，响应增加 `bot` 标记。
- 网页移除底部模组下载/源码区块，在顶栏加入 `Misaka24/rankboard` GitHub 仓库入口。
- 历史/时段查询优化：缺失或非零点快照不再直接报错，自动回退到最近可用边界并显示“实际范围”和警告；结束日期为今天时使用实时统计。
- 修复空的历史快照导致当日/自定义日期始终无数据的问题；查询范围前会补齐当日快照并合并新增玩家。
- 1.21.x 旅行家榜未知统计项按 0 处理，避免空指针（延续 2.1.2 修复）。

## 2.1.2

- 修复 1.21.x 旅行家榜/旅行统计在读取 26.x 新增统计项（`happy_ghast_one_cm`、`nautilus_one_cm`）时抛空指针的问题。
- 未知或当前版本不存在的自定义统计现在按 0 处理，不再中断单榜读取、侧边栏刷新或网页周期查询。
- 单指标实时读取增加异常保护，避免单个统计异常影响整张榜单。

## 2.1.1

- 新增手动玩家过滤：`/leaderboard playerfilter` 支持添加隐藏玩家、允许 `bot_`/`unknown_` 玩家显示、移除、查看、清空。
- 隐藏名单对所有排行榜、侧边栏与网页生效；允许显示名单只跳过自动前缀过滤，不影响白名单和仅在线筛选。
- 玩家过滤名单随排行榜数据持久化，支持离线玩家名与 UUID，并在修改后立即刷新榜单。
- 主菜单 OP 行新增“玩家过滤”入口；帮助与 README 同步更新。

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

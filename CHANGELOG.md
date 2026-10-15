# Changelog

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

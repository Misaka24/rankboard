# RankBoard

RankBoard 是一个面向 Minecraft 服务端的排行榜模组，同时提供 Fabric 与 NeoForge 版本。它直接读取原版玩家统计，提供聊天栏排行榜、个人侧边栏、全服侧边栏、自动轮播、榜单缓存对比和网页排行榜。玩家无需安装客户端模组，也不需要重新开始存档。

当前版本：`2.3.5`

- 项目仓库：[Misaka24/rankboard](https://github.com/Misaka24/rankboard)
- 上游仓库：[halfkite/rankboard](https://github.com/halfkite/rankboard)
- 下载：[GitHub Releases](https://github.com/Misaka24/rankboard/releases)
- 许可证：[MIT](LICENSE)

## 适用场景

RankBoard 适合生存服、活动服、好友服和长期周目服务器：

- 使用原版累计统计制作 27 个标准排行榜。
- 按本日、本周、本月、本年或总计查看排名。
- 网页支持最近一天、一周、一月、一季度、一年以及自定义日期范围。
- 玩家可以独立选择个人侧边栏，OP 可以设置全服共享侧边栏。
- 离线玩家仍可参与排名。
- 可按服务器白名单、RankBoard 独立白名单、在线状态与假人规则筛选。
- 手动隐藏指定玩家，或允许指定的 `bot_`/`unknown_` 玩家重新显示。
- 独立“生物受害”分类提供 9 个综合榜和 53 个单种生物受害者榜。
- 新增摸鱼榜、破基岩榜，以及缓存差异对比、聊天语言切换和同端口多服务器网页切换。

RankBoard 是服务端模组。正常使用聊天菜单、原版侧边栏和网页时，客户端不需要安装 RankBoard。

## 支持版本与下载选择

主发布物是一个同时支持 Fabric 与 NeoForge 的统一 JAR：

| 文件 | 加载器 | Minecraft | Java |
| --- | --- | --- | --- |
| `rankboard-2.3.5+mc1.21-26.3+<时间戳>.jar` | Fabric 与 NeoForge | 1.21–1.21.11、26.1–26.3 | 1.21 系 Java 21+；26.x 系 Java 25+ |

也可以按版本家族分别构建单加载器 JAR（见“从源码构建”）：

| 文件 | 加载器 | Minecraft | Java |
| --- | --- | --- | --- |
| `rankboard-2.3.5+fabric+mc1.21.x.jar` | Fabric | 1.21–1.21.11 | Java 21+ |
| `rankboard-2.3.5+fabric+mc26.1.x.jar` | Fabric | 26.1.x | Java 25+ |
| `rankboard-2.3.5+fabric+mc26.2.x.jar` | Fabric | 26.2.x | Java 25+ |
| `rankboard-2.3.5+fabric+mc26.3.x.jar` | Fabric | 26.3.x | Java 25+ |
| `rankboard-2.3.5+neoforge+mc1.21.x.jar` | NeoForge | 1.21–1.21.11 | Java 21+ |
| `rankboard-2.3.5+neoforge+mc26.x.jar` | NeoForge | 26.1–26.3 | Java 25+ |

Fabric 还需要与 Minecraft 版本匹配的 Fabric API；NeoForge 不需要额外 API 模组。请勿同时安装多个 RankBoard JAR，也不要在同一服务器同时安装 Fabric 与 NeoForge 两个版本。

## 安装

Fabric：

1. 安装对应版本的 Fabric Loader 与 Fabric API。
2. 下载匹配服务端 Minecraft 版本的 Fabric JAR。
3. 将 JAR 放入服务端 `mods/`。

NeoForge：

1. 安装对应 Minecraft 版本的 NeoForge。
2. 将 `rankboard-2.3.5+neoforge+mc*.jar` 放入服务端 `mods/`。

启动服务器，首次运行会生成 `config/rankboard/`。按需修改后重启，或由 OP 执行 `/leaderboard config reload`。输入 `/leaderboard` 打开可点击菜单。

## 游戏内菜单

输入 `/leaderboard` 后，一级菜单包含：

1. **查询排行榜**：时间 → 分类 → 榜单；在聊天栏显示前 10 名，不改变侧边栏。
2. **分类浏览榜单**：先寻找榜单，再选时间和操作；可设为个人侧边栏。OP 还可设置全服侧边栏或管理指定玩家侧边栏。
3. **查询我的统计**：显示执行者自己的标准统计，不与其他玩家比较。
4. **个人侧边栏**：设置榜单、恢复关闭前的显示方式或直接关闭。
5. **全服侧边栏**：仅 OP 可见，可设置或清除共享侧边栏。
6. **轮播**：开启、关闭或查询自己的榜单轮播。
7. **抬头蹲起菜单**：开启、关闭或查询快捷入口。
8. **网站**：打开管理员设置的网页排行榜。
9. **Help**：打开分组指令帮助。

需要参数的常用操作均可沿聊天栏按钮逐级完成。指令按钮通过客户端可验证的菜单路由直接执行，在新版客户端上不会再弹出执行确认界面。完整命令树同时支持 Tab 自动补全。

### 抬头蹲起快捷入口

默认允许玩家抬头并按住 Shift 打开菜单。玩家可用：

```text
/leaderboard lookmenu <true|false|status>
```

OP 可控制全服入口：

```text
/leaderboard lookmenu global <true|false|status>
```

## 时间统计原理

原版统计是持续增加的累计值。总榜直接读取当前累计值；周期榜使用两个日期边界的累计快照相减：

```text
一段时间内的成绩 = 结束边界累计值 - 开始边界累计值
```

例如周一零点累计挖掘 10,000，下一周周一零点累计 12,500，该周成绩就是 2,500。

### 游戏内周期

| 参数 | 含义 |
| --- | --- |
| `daily` | 当前自然日 |
| `weekly` | 当前自然周 |
| `monthly` | 当前自然月 |
| `yearly` | 当前自然年 |
| `all` | 原版累计总计 |

部分菜单和 `mine` 指令也接受 `day/week/month/year` 短参数。

### 网页周期

网页提供总计、最近一天、最近一周、最近一月、最近一季度、最近一年和自定义起止日期。网页快捷周期是相对当前日期的滚动范围，与“当前自然周/自然月”不同。自定义日期包含用户选择的开始日和结束日。

### 准确性说明

- RankBoard 每日保存累计快照，通过差值计算周期成绩。
- 原版不保存过去每天的累计边界，因此无法准确反推出安装 RankBoard 前任意日期的增量。
- 缺少可靠边界或累计值发生回退的玩家不会被当作 0 分伪造排名。
- 只能安全计算部分范围时，网页会显示实际采用的日期和不完整提示。
- 首次扫描期间，总榜可能显示带提示的缓存预览；周期查询会等待可靠扫描完成。
- 受伤榜与近战伤害输出榜显示原版累计整数，不除以 10。

## 榜单目录

分类参数为 `core/combat/build/life/explore/all/fun`。

| 分类 | 榜单（参数） |
| --- | --- |
| 常用 `core` | 在线 `playtime`、挖掘 `mined`、放置 `placed`、击杀 `kills`、死亡 `deaths`、旅行家 `travel`、矿业大亨 `ores`、交易 `trades` |
| 战斗 `combat` | 击杀 `kills`、PvP `pvp`、死亡 `deaths`、受伤 `damage`、近战伤害输出 `dealt`、坚盾 `shield`、死里逃生 `totem`、神射手 `target` |
| 建造 `build` | 挖掘 `mined`、放置 `placed`、矿业大亨 `ores`、合成 `crafted`、红石大蛇 `redstone`、工具毁灭者 `broken` |
| 生存 `life` | 大胃王 `food`、钓鱼 `fishing`、动物繁育 `bred`、睡神 `slept`、交易 `trades`、附魔大师 `enchanted` |
| 探索 `explore` | 旅行家 `travel`、飞行 `elytra`、跳跃 `jumps`、拾荒 `picked`、丢垃圾 `dropped`、音乐家 `music` |

`all` 是全部标准榜单去重后的集合，不包含“生物受害”。

### 标准榜单统计口径

- 挖掘榜只汇总当前注册的方块，避免已删除内容留下无效统计。
- 放置榜汇总方块物品的原版使用次数。
- 击杀榜为生物击杀与玩家击杀之和；PvP 榜只统计玩家击杀。
- 旅行家榜汇总主要移动方式距离；飞行榜只统计鞘翅飞行距离。
- 矿业大亨榜汇总原版矿石与远古残骸。
- 红石大蛇榜汇总红石电源、传输和机械组件的放置次数。
- 工具毁灭者榜汇总物品耐久耗尽次数。
- 坚盾榜使用原版护盾格挡伤害累计值。
- 死里逃生榜统计不死图腾触发次数。
- 音乐家榜统计唱片播放次数，神射手榜统计标靶命中次数。

## 生物受害分类

该分类只读取原版 `minecraft:killed_by`，不会建立私有死亡计数，也不会虚构原版不提供的淹死、烧死、摔死等死因数据。

### 综合受害榜

| 名称 | 参数 | 范围 |
| --- | --- | --- |
| 怪物大餐榜 | `mob_victims` | 除玩家外的所有生物 |
| 僵尸加餐榜 | `zombie_group` | 僵尸系 |
| 骷髅箭靶榜 | `skeleton_group` | 骷髅系 |
| 苦力怕烟花榜 | `creeper_group` | 苦力怕 |
| 虫群口粮榜 | `arthropod_group` | 蜘蛛、蠹虫等节肢生物 |
| 灾厄靶子榜 | `raider_group` | 灾厄与劫掠生物 |
| 下界燃料榜 | `nether_group` | 下界生物 |
| 末地祭品榜 | `end_group` | 末地生物 |
| 监守者玩具榜 | `warden_group` | 监守者 |

### 单种生物受害者榜

参数统一为 `victim_<实体 ID>`，显示为“XXX受害者榜”。包含：

烈焰人、沼骸、旋风人、骆驼尸壳、洞穴蜘蛛、嘎枝、苦力怕、溺尸、远古守卫者、末影龙、末影人、末影螨、唤魔者、恶魂、守卫者、疣猪兽、尸壳、幻术师、岩浆怪、干尸（Parched）、幻翼、猪灵、猪灵蛮兵、掠夺者、劫掠兽、潜影贝、蠹虫、骷髅、史莱姆、蜘蛛、流浪者、恼鬼、卫道士、监守者、女巫、凋灵、凋灵骷髅、僵尸疣猪兽、僵尸、僵尸鹦鹉螺、僵尸村民、僵尸猪灵、蜜蜂、海豚、山羊、铁傀儡、羊驼、熊猫、北极熊、河豚、杀手兔、行商羊驼和狼。

示例：

```text
/leaderboard weekly fun victim_zombie
/leaderboard display show monthly fun victim_warden
```

“生物受害”下还有“综合受害榜”和“单种生物受害榜”两级入口，并且：

- 不进入 `all`。
- 不进入“查询我的统计”。
- 不参与自动轮播。
- 不进入网页“全部”。
- 只有进入“生物受害”分类才能查看与设置。

当前 Minecraft 版本不存在某实体，或原版统计没有对应记录时，该榜单自然为空。

## 完整指令

尖括号是必填参数，方括号是可选参数。

### 玩家指令

```text
/leaderboard
/leaderboard help [player|scoreboard|web]
/leaderboard menu <core|combat|build|life|explore|fun|all>
/leaderboard menu ranking [周期] [分类] [榜单]
/leaderboard menu personal [周期] [分类] [榜单]
/leaderboard <daily|weekly|monthly|yearly|all> <分类> <榜单> [limit]
/leaderboard mine [all|day|week|month|year]
/leaderboard display show <周期> <分类> <榜单>
/leaderboard display on
/leaderboard display off
/leaderboard carousel <true|false|status>
/leaderboard lookmenu <true|false|status>
```

排行榜查询的 `limit` 范围为 1–50，默认 10。“查询排行榜”只在聊天栏输出，不改变任何侧边栏。

### OP 指令

```text
/leaderboard menu server [周期] [分类] [榜单]
/leaderboard scoreboard show <周期> <分类> <榜单>
/leaderboard scoreboard clear
/leaderboard display show <周期> <分类> <榜单> <玩家>
/leaderboard display off <玩家>
/leaderboard displayfilter <榜单> <true|false|status>
/leaderboard scoreboard cleanup
/leaderboard scoreboard blocking <true|false|status>
/leaderboard carousel color <true|false|status>
/leaderboard whitelist <true|false|status>
/leaderboard botfilter <true|false|status>
/leaderboard customfilter <true|false|status>
/leaderboard onlinefilter <true|false|status>
/leaderboard playerfilter
/leaderboard playerfilter add|allow|remove <玩家名|UUID>
/leaderboard playerfilter list|clear|status
/leaderboard botgroup
/leaderboard botgroup add|remove|list|clear|mixed <玩家名|UUID>
/leaderboard bots [rate] <周期> <榜单> [数量]
/leaderboard modwhitelist add|remove <name|UUID>
/leaderboard modwhitelist list|reload
/leaderboard recipients <fake-only|false|whitelist|blacklist|status>
/leaderboard cache <status|reload>
/leaderboard cache threads <0-256|status>
/leaderboard lookup <UUID|whitelist|unknown>
/leaderboard ratelimit clear
/leaderboard config <list|reload>
/leaderboard config get <配置项>
/leaderboard config set <配置项> <值>
/leaderboard lookmenu global <true|false|status>
/leaderboard namecolor <true|false|scoreboard-only|status>
/leaderboard color list
/leaderboard color <榜单> [英文颜色名|#RRGGBB]
/leaderboard color reset <榜单|all>
/leaderboard label <榜单> [显示名称]
/leaderboard label list
/leaderboard label reset <榜单|all>
/leaderboard webtheme <icon|blue|rgb #RRGGBB|status>
```

布尔参数推荐使用 `true/false`。部分管理指令仍接受 `on/off` 或 `enable/disable` 兼容写法。

## 三种侧边栏行为

- **个人侧边栏**只发送给对应玩家，每位玩家可以选择不同榜单。
- **全服侧边栏**是 OP 控制的共享原版计分板。
- **轮播**按配置间隔切换标准榜单；生物受害榜不会加入。
- `/leaderboard display on` 会恢复关闭前的个人总览、单榜或轮播。
- 榜单变化可实时刷新；高频行为会自动降频，避免频繁重写。
- 名字颜色可同步排行榜、聊天、TAB 和头顶名牌；已有其他队伍的玩家不会被强行接管头顶名牌效果。

## 网页排行榜

默认地址：

```text
http://服务器地址:8765/
```

网页支持：

- 浅色玻璃风格界面，支持榜单分类、二级分类、搜索和响应式布局。
- 分类浏览、榜单搜索、在线玩家筛选。
- 机器人分组开关、`BOT·类型` 徽标与“每小时”效率切换。
- 榜单分类：默认显示 12 个常用榜单（含 PvP、受伤、伤害输出），战斗/建造/生存/探索/生物受害等分类用标签收纳，点击展开；三种视图共用。
- “包含机器人”开关：普通榜单可选择把机器人一起显示，并带 `BOT` 标记。
- 总计、滚动快捷周期和自定义日期；缺失零点边界时显示实际范围与提示。
- 顶栏 GitHub 仓库入口（`Misaka24/rankboard`）。
- 头像、UUID、在线状态和最后在线时间。
- 服务器图标自动取色或自定义主题。
- 电脑与手机响应式布局。

公网或反向代理环境应设置玩家真正能访问的地址：

```text
/leaderboard config set web-public-address https://rank.example.com
```

该项只改变菜单中的链接；实际监听地址与端口由网页配置控制。

### API

```text
GET /api/site
GET /api/rankings?metric=playtime&period=all
GET /api/rankings?metric=kills&period=week
GET /api/rankings?metric=playtime&from=2026-07-01&to=2026-07-21
GET /api/rankings?metric=playtime&period=all&bots=true
GET /api/rankings?metric=playtime&period=all&includeBots=true
```

`bots=true` 只返回机器人分组（手动分组 + `bot_` 前缀），不受白名单限制；普通请求会隐藏机器人分组。`includeBots=true` 让普通榜单额外包含机器人（带 `bot` 标记），对应网页“包含机器人”开关。响应中的每位玩家带 `bot` 标记，并返回 `actualStart`、`actualEnd`、`warnings` 等实际范围信息。

网页接口按 IP 渐进限流。超限返回 HTTP `429` 与 `Retry-After`；OP 可执行 `/leaderboard ratelimit clear`。公网部署建议在前方使用 Caddy、Nginx 等反向代理提供 HTTPS。

## 配置

```text
config/rankboard/
├─ rankboard.properties
├─ rankboard-web.properties
└─ rankboard-whitelist.json
```

配置首次启动自动生成，并包含每一项的中文说明。也可使用：

```text
/leaderboard config list
/leaderboard config get <配置项>
/leaderboard config set <配置项> <值>
```

### 常用主配置

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `join-menu-enabled` | `true` | 进服显示菜单 |
| `look-up-sneak-menu-enabled` | `true` | 抬头并按 Shift 打开菜单 |
| `restore-scoreboard-on-join` | `true` | 进服恢复个人侧边栏 |
| `carousel-enabled` | `true` | 允许轮播 |
| `carousel-interval-seconds` | `30` | 轮播间隔 |
| `scoreboard-live-update-enabled` | `true` | 统计变化时刷新 |
| `scoreboard-name-color-enabled` | `true` | 名字颜色模式 |
| `history-files-per-second` | `50` | 每线程每秒检查文件数 |
| `history-scan-threads` | `0` | 自动使用不超过 50% 逻辑处理器 |
| `mod-whitelist-enabled` | `false` | RankBoard 独立白名单 |
| `website-button-enabled` | `true` | 显示网站按钮 |
| `web-public-address` | 空 | 展示给玩家的网页地址 |
| `help-visibility` | `all` | Help 对 all/op/hidden 可见 |

每个标准榜单支持 `metric-label-<参数>` 和 `metric-color-<参数>`，也可用 `/leaderboard label`、`/leaderboard color` 修改。

### 常用网页配置

| 配置项 | 默认值 | 用途 |
| --- | --- | --- |
| `host` | `0.0.0.0` | 监听地址 |
| `port` | `8765` | 监听端口 |
| `server-name` | `auto` | 网页服务器名称 |
| `website-icon` | `server-icon.png` | 网页图标 |
| `web-ranking-refresh-interval-seconds` | `30` | 排行缓存刷新间隔 |
| `web-theme-follow-icon` | `true` | 从图标取色 |
| `web-theme-primary` | `auto` | 主题主色或 `#RRGGBB` |

`website-icon` 优先读取 `config/rankboard/`；默认图标不存在时尝试服务端根目录的 `server-icon.png`。绝对路径、目录穿越和逃出允许目录的符号链接会被拒绝。

### 独立白名单

启用：

```text
/leaderboard config set mod-whitelist-enabled true
```

`rankboard-whitelist.json` 示例：

```json
[
  {"uuid": "00000000-0000-0000-0000-000000000000"},
  {"name": "PlayerName"}
]
```

启用后，扫描、缓存、游戏内与网页排行榜只接受名单玩家。若服务器白名单筛选也开启，则取交集。机器人分组中的玩家（手动分组与 `bot_` 前缀）不受白名单限制，仍会被扫描并显示在机器人榜中。

### 手动玩家过滤

除了自动过滤 `bot_` 与 `unknown_` 前缀玩家，OP 还可以手动维护两个名单：

- **隐藏名单**：名单内玩家不会出现在任何排行榜、侧边栏或网页中。
- **允许显示名单**：名单内玩家即使带有 `bot_` 或 `unknown_` 前缀也会正常显示，适合需要保留的假人或历史玩家。

```text
/leaderboard playerfilter                 打开可点击面板
/leaderboard playerfilter add <玩家>      加入隐藏名单
/leaderboard playerfilter allow <玩家>    加入允许显示名单
/leaderboard playerfilter remove <玩家>   从两个名单移除
/leaderboard playerfilter list            查看名单
/leaderboard playerfilter clear           清空两个名单
/leaderboard playerfilter status          查看过滤状态与人数
```

玩家名支持在线或离线玩家；也接受 UUID。修改后立即刷新榜单。自动 `bot_`/`unknown_` 过滤仍由 `/leaderboard botfilter` 与 `/leaderboard customfilter` 控制，手动名单优先。

### 机器人分组

除了 `bot_` 前缀自动识别，OP 还可以把任意玩家（例如 Carpet 假人 Steve、Alex）手动移入机器人分组：

```text
/leaderboard botgroup                 打开可点击面板
/leaderboard botgroup add <玩家>      移入机器人分组
/leaderboard botgroup remove <玩家>   移出机器人分组
/leaderboard botgroup list|clear      查看/清空分组
/leaderboard botgroup mixed <true|false|status>  与普通榜单混合显示
/leaderboard bots [rate] <周期> <榜单> [数量]  查看机器人分组榜单
/leaderboard bots rate <周期> <榜单> [数量]  按每小时产出查看效率榜
```

- 分组内玩家会从普通聊天榜、侧边栏和网页默认列表中隐藏，避免假人挤占真人排名。
- 机器人榜单独显示手动分组与 `bot_` 前缀玩家，行尾标记 `[BOT·类型]`；网页顶栏的“机器人分组”开关提供同样视图和 `BOT` 徽标。
- **自动行为类型**：按主要统计把机器人标记为攻击型、挂机型、点击型、农民型、探索型或混合型，游戏内名单与网页徽标都会显示。
- **每小时效率榜**：`/leaderboard bots rate <周期> <榜单>` 用该周期在线时长归一化，避免“挂得久就第一”；网页机器人视图勾选“每小时”即可切换。
- **混合显示**：`/leaderboard botgroup mixed true` 后机器人也会进入普通聊天榜、侧边栏和网页；真人区与 BOT 区分别显示总和，聊天栏用 `──────── BOT 机器人 ────────` 分隔，侧边栏 BOT 区在真人区下方并以 `BOT 总和` 开头；默认关闭。
- **机器人榜不受服务器白名单或 RankBoard 独立白名单限制**：未加入白名单的假人也能被扫描、统计和显示。
- 机器人分组成员随排行榜数据持久化，支持离线玩家名与 UUID。
- 若离线假人仍显示 `unknown_xxxxxxxx`，可运行 `/leaderboard lookup unknown` 从 Mojang 补全真实账户名；`usercache.json` 更新后 RankBoard 也会在 30 秒内自动刷新缓存名称。

### 历史边界与日期范围

历史快照通常只在服务器零点运行时建立。选择今天或缺失零点的日期时，RankBoard 不再直接报错，而是：

- 使用范围内最近可用快照，并在网页/命令结果显示“实际范围”和警告；
- 缺少次日零点边界时回退到最近的后续快照；
- 结束日期为今天时使用实时统计；
- 开始日期只有部分快照时按当日首次可信快照计算，并明确标注不完整。

因此即使当天刚启动服务器、尚无完整零点快照，也可以查询当日、本周等范围；数据会随服务器继续运行逐步完整。

## 数据与快照位置

### 原版源数据

```text
world/stats/<玩家 UUID>.json
```

RankBoard 不会为了榜单改写原版统计。

### 每日历史快照

```text
world/data/rankboard/history/YYYY-MM.dat
```

每天的累计快照按月份集中到一个压缩文件，例如 `2026-07.dat`。这样每月只有少量文件，也不会把多年历史塞进一个持续膨胀的总文件。

### 主状态

- Minecraft 1.21.x：`world/data/rankboard/rankboard_leaderboard.dat`
- Minecraft 26.x：主世界维度数据目录中的 `data/minecraft/rankboard/rankboard_leaderboard.dat`

这里保存周期边界、玩家显示偏好和筛选状态等持久化数据。

备份世界时应同时保留 `world/stats/`、`world/data/rankboard/`、26.x 的主状态文件以及 `config/rankboard/`。只备份 JAR 不能保留周期快照与配置。

## 性能、隐私与常见问题

- 首次启动会后台扫描所有原版统计文件；扫描受速率与线程上限控制。
- 后续只检查发生变化的文件，玩家退出时会先捕获内存中的最新统计。
- 头像缓存可关闭并设置保留天数。
- 网页会公开排行榜需要的玩家名、UUID、统计与在线信息；公网部署前请确认符合服务器规则。
- 总榜有数据而周期榜为空，通常是因为总榜只需当前累计值，而周期榜还需要可靠日期边界。
- 周期榜少人可能是边界缺失、累计回退、筛选规则或首次扫描未完成。
- 生物受害不在“全部”是刻意设计，用于避免 62 个低频榜单挤占常用菜单。
- 网页按钮打开本机地址时，请设置 `web-public-address`。
- 玩家客户端不需要安装 RankBoard。

## 从源码构建

Gradle 需要 JDK 25 运行；1.21.x 目标使用 Java 21 字节码，26.x 目标使用 Java 25 字节码。

```text
gradlew.bat build
```

指定目标版本（Fabric）：

```text
gradlew.bat build -Pminecraft_version=26.3 -Pmapping_type=none -Ploader_version=0.19.5 -Pfabric_version=0.160.5+26.3 -Pminecraft_dependency=26.3
```

NeoForge：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 26.3
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 1.21.x
```

普通产物位于 `build/libs/` 或 `neoforge/build/libs/`。多个版本可用 `scripts/build-direct-fabric.ps1` / `scripts/build-direct-neoforge.ps1` 分别构建。

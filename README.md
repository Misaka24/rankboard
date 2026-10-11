# RankBoard

[![License](https://img.shields.io/github/license/halfkite/rankboard)](https://choosealicense.com/licenses/mit/)
[![Modrinth](https://img.shields.io/modrinth/dt/rankboard?color=00AF5C&label=Modrinth%20downloads&logo=modrinth)](https://modrinth.com/project/rankboard)
[![CurseForge](https://img.shields.io/curseforge/dt/1663124?logo=curseforge&label=CurseForge%20downloads&color=f16436)](https://www.curseforge.com/minecraft/mc-mods/rankboard)
[![MC Versions](https://cf.way2muchnoise.eu/versions/For%20MC_1663124_all.svg)](https://www.curseforge.com/minecraft/mc-mods/rankboard)
[![GitHub](https://img.shields.io/github/downloads/halfkite/rankboard/total?color=161616&label=GitHub%20downloads&logo=github)](https://github.com/halfkite/rankboard/releases)

**中文** | [English](#english)

## 依赖

| 名称 | 类型 | 链接 | 备注 |
| --- | --- | --- | --- |
| Fabric Loader | Fabric 必须 | [官方](https://fabricmc.net/use/installer/) | 1.21.x 使用 0.15.11+ |
| Fabric API | Fabric 必须 | [官方](https://fabricmc.net/) | 与目标 Minecraft 版本匹配 |
| NeoForge | NeoForge 必须 | [官方](https://neoforged.net/) | 安装对应游戏版本 |

## 版本支持

历史说明：已发布的 1.10.5 `mc1.21.x` 单 JAR 曾在部分 1.21 小版本存在二进制兼容问题，1.10.6 修复了该问题。当前 1.10.10 按 `1.21.x` 与 `26.x` 家族分别直接构建，逐版本验证结果见 [1.10.10 兼容性报告](docs/compatibility-audit-1.10.10-families.md)。

| 加载器 | 游戏版本 | 状态 | 最新 RankBoard |
| --- | --- | --- | --- |
| Fabric | 1.21–1.21.11 | 家族 JAR；逐版启动与网页测试 | 1.10.10 |
| Fabric | 26.1、26.1.1、26.1.2、26.2、26.3 | 家族 JAR；逐版启动与网页测试 | 1.10.10 |
| NeoForge | 1.21–1.21.11 | 家族 JAR；逐版启动与网页测试 | 1.10.10 |
| NeoForge | 26.1、26.1.1、26.1.2、26.2、26.3 | 家族 JAR；逐版启动与网页测试 | 1.10.10 |

## 文档

- [中文完整文档](docs/rankboard.md)
- [English documentation](docs/rankboard_en.md)

## 下载

- [Modrinth](https://modrinth.com/project/rankboard)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/rankboard)
- [GitHub Releases](https://github.com/halfkite/rankboard/releases/latest)

介绍页面的布局参考了 [Carpet-Igny-Addition](https://github.com/liuyuexiaoyu1/Carpet-Igny-Addition)。

---

## 中文

RankBoard 是一个服务端排行榜模组，支持 Fabric 和 NeoForge。玩家不需要客户端模组即可使用原版计分板和网页查看排行榜。

当前开发版本：`1.10.10`（Fabric / NeoForge；`1.21.x`、`26.x` 家族构建，尚未发布）　|　[构建包与测试范围](docs/compatibility-audit-1.10.10-families.md)　|　[更新日志](CHANGELOG.md)　|　[完整中文文档](docs/rankboard.md)　|　[English documentation](docs/rankboard_en.md)

### 功能简介

- 17 种统计榜单，支持 daily / weekly / monthly / yearly / all 五种时间周期
- 游戏内原版侧边栏：个人榜、全服榜、轮播榜，进服自动恢复
- 网页排行榜：日期范围查询、在线筛选、主题配色、多服务器切换
- 离线数据读取、后台缓存更新、IP 请求限流、白名单筛选

### 安装

**Fabric：**
1. 安装对应 Minecraft 版本的 Fabric Loader 和 Fabric API。
2. 将 JAR 放入服务器 `mods/` 目录。

**NeoForge：**
1. 安装对应 Minecraft 版本的 NeoForge。
2. 1.21.x 使用 `rankboard-1.10.10+neoforge+mc1.21.x.jar`；26.x 使用 `rankboard-1.10.10+neoforge+mc26.x.jar`。
3. 将 JAR 放入服务器 `mods/` 目录。

启动服务器一次生成 `config/rankboard/` 配置目录，修改配置后重启或使用 `/leaderboard config reload`。

1.21 系列需要 Java 21+；26.x 系列需要 Java 25+。

### 快速上手

```text
/leaderboard                          打开可点击的排行榜菜单
/leaderboard mine                     查询自己的统计分数
/leaderboard display show all playtime 显示在线榜侧边栏
/leaderboard help                     查看帮助
```

更多命令、配置和游戏内操作说明请参阅[完整文档](docs/rankboard.md)。

### 构建

需要 JDK 21；构建 26.x 需要 JDK 25。

```text
gradlew.bat build
```

构建产物位于 `build/libs/`。

需要手动构建某个平台/版本家族的单次直接 JAR 时，在 PowerShell 执行（不会嵌套多个小版本 JAR）：

```text
powershell -ExecutionPolicy Bypass -File scripts/build-direct-fabric.ps1 -Target 1.21.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 1.21.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-fabric.ps1 -Target 26.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 26.x -OutputDirectory release -ModVersion 1.10.10
```

### GitHub Actions 发布

推送到 `main` 或创建面向 `main` 的 Pull Request 时，`.github/workflows/build.yml` 会构建四个家族 JAR 并保存为 Actions 构建产物，不会发布到 Modrinth 或 CurseForge。正式发布时，创建并发布 GitHub Release（例如标签 `1.10.10`）后，两个发布工作流才会从该标签构建并发布：

- `.github/workflows/release.yml`：构建并发布 Fabric（1.21.x、26.x）。
- `.github/workflows/release-neoforge.yml`：构建并发布 NeoForge（1.21.x、26.x）。

两个发布工作流都从 `CHANGELOG.md` 读取更新说明，只将自己的 JAR 上传到现有 GitHub Release，并分别发布到 Modrinth、CurseForge；校验文件只在本地构建目录生成。需要重建某个已有 Release 时，可在对应工作流选择 **Run workflow**，填写 Release 标签、发布目标和版本筛选。

发布前需要在仓库 **Settings → Secrets and variables → Actions** 添加以下 Secrets：

- `MODRINTH_TOKEN`：Modrinth 项目令牌
- `CURSEFORGE_TOKEN`：CurseForge 作者 API 令牌

可选的 Repository Variables：`MODRINTH_PROJECT_ID`、`MODRINTH_NEOFORGE_PROJECT_ID`、`CURSEFORGE_PROJECT_ID`、`CURSEFORGE_NEOFORGE_PROJECT_ID`。未设置时使用 RankBoard 的默认项目 ID。

GitHub Release 使用工作流自带的 `GITHUB_TOKEN`。令牌不会写入源码；Release 的预发布状态会自动映射为 Modrinth/CurseForge 的 Beta，否则按正式版发布。

---

## English

Historical note: the published 1.10.5 `mc1.21.x` single JAR had binary-compatibility issues on some 1.21 releases; 1.10.6 fixed those issues. Version 1.10.10 is built directly for the `1.21.x` and `26.x` families. See the [1.10.10 compatibility report](docs/compatibility-audit-1.10.10-families.md) for per-version verification results.

RankBoard is a server-side leaderboard mod supporting Fabric and NeoForge. Players do not need a client-side mod to use the vanilla sidebar or the web dashboard.

Development version: `1.10.10` (Fabric / NeoForge, `1.21.x` and `26.x` families; not yet released)　|　[Builds and test coverage](docs/compatibility-audit-1.10.10-families.md)　|　[Changelog](CHANGELOG.md)　|　[中文文档](docs/rankboard.md)　|　[English documentation](docs/rankboard_en.md)

### Highlights

- 17 ranking metrics with daily / weekly / monthly / yearly / all periods
- Vanilla sidebar: personal, server-wide, and carousel modes with join restoration
- Web dashboard: date range queries, online filtering, themes, multi-server switcher
- Offline data, background caching, IP rate limiting, whitelist filtering

### Installation

**Fabric:**
1. Install Fabric Loader and Fabric API for the target Minecraft version.
2. Put the JAR in the server `mods/` directory.

**NeoForge:**
1. Install NeoForge for the target Minecraft version.
2. For 1.21.x use `rankboard-1.10.10+neoforge+mc1.21.x.jar`; for 26.x use `rankboard-1.10.10+neoforge+mc26.x.jar`.
3. Put the JAR in the server `mods/` directory.

Start the server once to create `config/rankboard/`. Restart after editing configuration, or run `/leaderboard config reload`.

Minecraft 1.21 requires Java 21+. Minecraft 26.x requires Java 25+.

### Quick Start

```text
/leaderboard                          Open the clickable ranking menu
/leaderboard mine                     Show your personal scores
/leaderboard display show all playtime Show the playtime sidebar
/leaderboard help                     Show command help
```

See the [full documentation](docs/rankboard_en.md) for all commands, configuration, and in-game operations.

### Building

JDK 21 is required; JDK 25 is required for 26.x builds.

```text
gradlew.bat build
```

Artifacts are written to `build/libs/`.

To build a single direct JAR for a platform/version family in PowerShell (no nested per-minor JARs):

```text
powershell -ExecutionPolicy Bypass -File scripts/build-direct-fabric.ps1 -Target 1.21.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 1.21.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-fabric.ps1 -Target 26.x -OutputDirectory release -ModVersion 1.10.10
powershell -ExecutionPolicy Bypass -File scripts/build-direct-neoforge.ps1 -Target 26.x -OutputDirectory release -ModVersion 1.10.10
```

### GitHub Actions publishing

Pushes to `main` and pull requests targeting `main` run `.github/workflows/build.yml`, which builds the four family JARs as CI artifacts without publishing them externally. For an actual release, create and publish a GitHub Release (for example, tag `1.10.10`); the two publication workflows then build from that tag:

- `.github/workflows/release.yml` builds and publishes Fabric (1.21.x and 26.x).
- `.github/workflows/release-neoforge.yml` builds and publishes NeoForge (1.21.x and 26.x).

Both publication workflows read release notes from `CHANGELOG.md` and upload only their own JARs to the existing GitHub Release, Modrinth, and CurseForge; checksums are local sidecar files, not extra release assets. To repair an existing Release, use **Actions → Run workflow** and enter the Release tag, destinations, and optional version filter.

Before publishing, add these repository Actions secrets under **Settings → Secrets and variables → Actions**:

- `MODRINTH_TOKEN`: the Modrinth project token
- `CURSEFORGE_TOKEN`: the CurseForge author API token

Optional repository variables are `MODRINTH_PROJECT_ID`, `MODRINTH_NEOFORGE_PROJECT_ID`, `CURSEFORGE_PROJECT_ID`, and `CURSEFORGE_NEOFORGE_PROJECT_ID`. The RankBoard project IDs are used when these variables are omitted.

GitHub Releases use the workflow-provided `GITHUB_TOKEN`. Tokens are never stored in source code. A Release's prerelease flag is mapped to the Beta channel; otherwise the upload is treated as a stable release.

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


# Yarn and Mojmap metric definitions.
replace_once(
    "src/main/java/cn/bamgdam/rankboard/RankBoardMod.java",
    '        KILLS("kills", "击杀榜", Formatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),\n',
    '        KILLS("kills", "击杀榜", Formatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),\n'
    '        PVP_KILLS("pvp", "PvP榜", Formatting.DARK_RED, p -> custom(p, Stats.PLAYER_KILLS)),\n',
)
replace_once(
    "src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java",
    '        KILLS("kills", "击杀榜", ChatFormatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),\n',
    '        KILLS("kills", "击杀榜", ChatFormatting.RED, p -> custom(p, Stats.MOB_KILLS) + custom(p, Stats.PLAYER_KILLS)),\n'
    '        PVP_KILLS("pvp", "PvP榜", ChatFormatting.DARK_RED, p -> custom(p, Stats.PLAYER_KILLS)),\n',
)

# Offline JSON reader and cache schema. Schema bump forces existing player files to be re-read.
for path in [
    "src/main/java/cn/bamgdam/rankboard/StatReader.java",
    "src/mojmap/java/cn/bamgdam/rankboard/StatReader.java",
]:
    replace_once(path, "    private static final int PERSISTENT_CACHE_SCHEMA = 5;\n",
                 "    private static final int PERSISTENT_CACHE_SCHEMA = 6;\n")
    replace_once(
        path,
        '            case KILLS -> stat(stats, "minecraft:custom", "minecraft:mob_kills") + stat(stats, "minecraft:custom", "minecraft:player_kills");\n',
        '            case KILLS -> stat(stats, "minecraft:custom", "minecraft:mob_kills") + stat(stats, "minecraft:custom", "minecraft:player_kills");\n'
        '            case PVP_KILLS -> stat(stats, "minecraft:custom", "minecraft:player_kills");\n',
    )

# Configurable label and color in both source mappings.
for path in [
    "src/main/java/cn/bamgdam/rankboard/RankBoardConfig.java",
    "src/mojmap/java/cn/bamgdam/rankboard/RankBoardConfig.java",
]:
    replace_once(
        path,
        '            option("metric-label-kills", "击杀榜", FileKind.MAIN, "榜单名称", "击杀榜在游戏和网页结果中显示的名称。"),\n',
        '            option("metric-label-kills", "击杀榜", FileKind.MAIN, "榜单名称", "击杀榜在游戏和网页结果中显示的名称。"),\n'
        '            option("metric-label-pvp", "PvP榜", FileKind.MAIN, "榜单名称", "击杀其他玩家数量榜在游戏和网页结果中显示的名称。"),\n',
    )
    replace_once(
        path,
        '            option("metric-color-kills", "#FF5555", FileKind.MAIN, "榜单颜色", "击杀榜颜色；格式 #RRGGBB。"),\n',
        '            option("metric-color-kills", "#FF5555", FileKind.MAIN, "榜单颜色", "击杀榜颜色；格式 #RRGGBB。"),\n'
        '            option("metric-color-pvp", "#AA0000", FileKind.MAIN, "榜单颜色", "PvP榜颜色；默认深红色，格式 #RRGGBB。"),\n',
    )

# Web selector.
replace_once(
    "web/src/App.tsx",
    '  { id: "kills", label: "击杀榜", detail: "战斗" },\n',
    '  { id: "kills", label: "击杀榜", detail: "战斗" },\n'
    '  { id: "pvp", label: "PvP榜", detail: "玩家对战" },\n',
)

# Version and documentation.
replace_once("gradle.properties", "mod_version=1.5.7\n", "mod_version=1.5.8\n")
replace_once("README.md", "当前版本：`1.5.7`\n", "当前版本：`1.5.8`\n")
replace_once(
    "README.md",
    "### 1.5.7 周期可用性修复\n",
    "### 1.5.8 PvP 排行榜\n\n"
    "- 新增 `pvp` 指标与 PvP 榜，统计原版 `minecraft:player_kills`，即玩家击杀其他玩家的次数。\n"
    "- PvP 榜支持总榜、每日、每周、每月、每年、自定义日期、个人计分板、全服计分板和网页排行榜。\n"
    "- 总榜可直接读取已有原版历史数据；旧快照不会被反向补值，升级后的时间榜从首次可信 PvP 基线开始并标记为部分统计。\n"
    "- 原有击杀榜保持不变，继续统计生物击杀与玩家击杀之和。\n\n"
    "### 1.5.7 周期可用性修复\n",
)

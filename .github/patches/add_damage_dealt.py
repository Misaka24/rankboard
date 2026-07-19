from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_many(path: str, replacements: list[tuple[str, str]]) -> None:
    for old, new in replacements:
        replace_once(path, old, new)


COMMON = "src/main/java/cn/bamgdam/rankboard"
MOJMAP = "src/mojmap/java/cn/bamgdam/rankboard"

replace_once("gradle.properties", "mod_version=1.5.1", "mod_version=1.5.2")

replace_many(
    f"{COMMON}/RankBoardMod.java",
    [
        (
            'if (metric == Metric.DAMAGE_TAKEN) return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);',
            'if (metric == Metric.DAMAGE_TAKEN || metric == Metric.DAMAGE_DEALT) return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);',
        ),
        (
            '        DAMAGE_TAKEN("damage", "受伤榜", Formatting.RED, p -> custom(p, Stats.DAMAGE_TAKEN)),\n',
            '        DAMAGE_TAKEN("damage", "受伤榜", Formatting.RED, p -> custom(p, Stats.DAMAGE_TAKEN)),\n'
            '        DAMAGE_DEALT("dealt", "伤害输出榜", Formatting.GOLD, p -> custom(p, Stats.DAMAGE_DEALT)),\n',
        ),
    ],
)

replace_many(
    f"{MOJMAP}/RankBoardMod.java",
    [
        (
            'if (metric == Metric.DAMAGE_TAKEN) return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);',
            'if (metric == Metric.DAMAGE_TAKEN || metric == Metric.DAMAGE_DEALT) return String.format(java.util.Locale.ROOT, "%.1f", value / 10.0);',
        ),
        (
            '        DAMAGE_TAKEN("damage", "受伤榜", ChatFormatting.RED, p -> custom(p, Stats.DAMAGE_TAKEN)),\n',
            '        DAMAGE_TAKEN("damage", "受伤榜", ChatFormatting.RED, p -> custom(p, Stats.DAMAGE_TAKEN)),\n'
            '        DAMAGE_DEALT("dealt", "伤害输出榜", ChatFormatting.GOLD, p -> custom(p, Stats.DAMAGE_DEALT)),\n',
        ),
    ],
)

for base in (COMMON, MOJMAP):
    replace_many(
        f"{base}/StatReader.java",
        [
            ("private static final int PERSISTENT_CACHE_SCHEMA = 3;", "private static final int PERSISTENT_CACHE_SCHEMA = 4;"),
            (
                '            case DAMAGE_TAKEN -> stat(stats, "minecraft:custom", "minecraft:damage_taken");\n',
                '            case DAMAGE_TAKEN -> stat(stats, "minecraft:custom", "minecraft:damage_taken");\n'
                '            case DAMAGE_DEALT -> stat(stats, "minecraft:custom", "minecraft:damage_dealt");\n',
            ),
        ],
    )
    replace_many(
        f"{base}/RankBoardConfig.java",
        [
            (
                '            option("metric-label-damage", "受伤榜", FileKind.MAIN, "榜单名称", "受伤榜在游戏和网页结果中显示的名称。"),\n',
                '            option("metric-label-damage", "受伤榜", FileKind.MAIN, "榜单名称", "受伤榜在游戏和网页结果中显示的名称。"),\n'
                '            option("metric-label-dealt", "伤害输出榜", FileKind.MAIN, "榜单名称", "伤害输出榜在游戏和网页结果中显示的名称。"),\n',
            ),
            (
                '            option("metric-color-damage", "#FF5555", FileKind.MAIN, "榜单颜色", "受伤榜颜色；默认红色，格式 #RRGGBB。"),\n',
                '            option("metric-color-damage", "#FF5555", FileKind.MAIN, "榜单颜色", "受伤榜颜色；默认红色，格式 #RRGGBB。"),\n'
                '            option("metric-color-dealt", "#FFAA00", FileKind.MAIN, "榜单颜色", "伤害输出榜颜色；默认金色，格式 #RRGGBB。"),\n',
            ),
        ],
    )
    replace_once(
        f"{base}/WebDashboard.java",
        "            case DAMAGE_TAKEN -> String.format(java.util.Locale.ROOT, \"%,.1f\", value / 10.0);",
        "            case DAMAGE_TAKEN, DAMAGE_DEALT -> String.format(java.util.Locale.ROOT, \"%,.1f\", value / 10.0);",
    )

common_roll_old = '''    public void rollPeriods(MinecraftServer server) {
        if (!StatReader.isReady()) return;
        LocalDate now = LocalDate.now();
        boolean changed = false;
        for (RankBoardMod.Period period : RankBoardMod.Period.values()) {
            if (period == RankBoardMod.Period.ALL) continue;
            PeriodData old = periods.get(period);
            if (old == null || !old.key.equals(period.key(now))) {
                PeriodData replacement = new PeriodData(period, period.key(now));
                StatReader.readAll(server).forEach(replacement::capture);
                periods.put(period, replacement);
                changed = true;
            }
        }
        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            StatReader.readAll(server).forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            changed = true;
        }
        if (changed) markDirty();
    }
'''

common_roll_new = '''    public void rollPeriods(MinecraftServer server) {
        if (!StatReader.isReady()) return;
        LocalDate now = LocalDate.now();
        boolean changed = false;
        List<StatSnapshot> snapshots = StatReader.readAll(server);
        for (RankBoardMod.Period period : RankBoardMod.Period.values()) {
            if (period == RankBoardMod.Period.ALL) continue;
            PeriodData old = periods.get(period);
            if (old == null || !old.key.equals(period.key(now))) {
                PeriodData replacement = new PeriodData(period, period.key(now));
                snapshots.forEach(replacement::capture);
                periods.put(period, replacement);
                changed = true;
            }
        }
        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            changed = true;
        }
        if (backfillMissingMetrics(snapshots)) changed = true;
        if (changed) markDirty();
    }

    private boolean backfillMissingMetrics(List<StatSnapshot> snapshots) {
        boolean changed = false;
        for (PeriodData data : periods.values()) {
            for (StatSnapshot snapshot : snapshots) {
                Map<RankBoardMod.Metric, Long> values = data.players.computeIfAbsent(
                        snapshot.uuid(), ignored -> new EnumMap<>(RankBoardMod.Metric.class));
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (!values.containsKey(metric)) {
                        values.put(metric, snapshot.value(metric));
                        changed = true;
                    }
                }
            }
        }
        for (Map<UUID, Map<RankBoardMod.Metric, Long>> players : dailySnapshots.values()) {
            for (StatSnapshot snapshot : snapshots) {
                Map<RankBoardMod.Metric, Long> values = players.computeIfAbsent(
                        snapshot.uuid(), ignored -> new EnumMap<>(RankBoardMod.Metric.class));
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (!values.containsKey(metric)) {
                        values.put(metric, snapshot.value(metric));
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }
'''

moj_roll_old = common_roll_old.replace("markDirty()", "setDirty()")
moj_roll_new = common_roll_new.replace("markDirty()", "setDirty()")

replace_once(f"{COMMON}/LeaderboardState.java", common_roll_old, common_roll_new)
replace_once(f"{MOJMAP}/LeaderboardState.java", moj_roll_old, moj_roll_new)

for base in (COMMON, MOJMAP):
    replace_once(
        f"{base}/LeaderboardState.java",
        '            for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) values.put(metric, NbtCompat.getLong(entry, metric.command));',
        '            for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {\n'
        '                if (entry.contains(metric.command)) values.put(metric, NbtCompat.getLong(entry, metric.command));\n'
        '            }',
    )

replace_once(
    "web/src/App.tsx",
    '  { id: "damage", label: "受伤害榜", detail: "生存" }\n',
    '  { id: "damage", label: "受伤害榜", detail: "生存" },\n'
    '  { id: "dealt", label: "伤害输出榜", detail: "战斗" }\n',
)

replace_many(
    "README.md",
    [
        ("当前版本：`1.5.1`", "当前版本：`1.5.2`"),
        ("Current version: `1.5.1`", "Current version: `1.5.2`"),
        (
            "钓鱼、受伤害、丢弃、拾取、合成和红石元件放置",
            "钓鱼、受伤害、伤害输出、丢弃、拾取、合成和红石元件放置",
        ),
        (
            "fishing, damage, dropped and picked-up items",
            "fishing, damage taken and damage dealt, dropped and picked-up items",
        ),
        (
            "metric-color-damage=#FF5555                 # 受伤榜：红色\n",
            "metric-color-damage=#FF5555                 # 受伤榜：红色\n"
            "metric-color-dealt=#FFAA00                  # 伤害输出榜：金色\n",
        ),
        (
            "metric-label-damage=受伤榜\n",
            "metric-label-damage=受伤榜\n"
            "metric-label-dealt=伤害输出榜\n",
        ),
        (
            "metric-color-damage=#FF5555                 # Damage: red\n",
            "metric-color-damage=#FF5555                 # Damage taken: red\n"
            "metric-color-dealt=#FFAA00                  # Damage dealt: gold\n",
        ),
    ],
)

print("Damage dealt leaderboard patch applied successfully.")

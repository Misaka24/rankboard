from pathlib import Path
import re


def replace_method(path: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    pattern = re.compile(
        r"    public RangeData range\(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod\.Metric metric\) \{.*?"
        r"    public String earliestSnapshotDate\(\) \{.*?\n    \}\n",
        re.S,
    )
    replacement = '''    public RangeData range(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
        return range(server, from, to, metric, false);
    }

    public RangeData range(MinecraftServer server, LocalDate from, LocalDate to,
                           RankBoardMod.Metric metric, boolean allowPartialStart) {
        if (!StatReader.isReady()) throw new IllegalStateException("历史统计仍在权威扫描（" + StatReader.progress() + "）");
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        LocalDate today = LocalDate.now();
        if (to.isAfter(today)) throw new IllegalArgumentException("结束日期不能晚于今天：" + today);

        Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> startEntry;
        if (allowPartialStart) {
            startEntry = dailySnapshots.ceilingEntry(from);
            if (startEntry == null || startEntry.getKey().isAfter(to)) {
                throw new IllegalArgumentException("所选范围内还没有可用历史快照；最早快照为 " + earliestSnapshotDate());
            }
        } else {
            Map<UUID, Map<RankBoardMod.Metric, Long>> exact = dailySnapshots.get(from);
            if (exact == null) {
                throw new IllegalArgumentException("开始日期没有真实边界快照；最早快照为 " + earliestSnapshotDate());
            }
            if (partialSnapshotDates.contains(from)) {
                throw new IllegalArgumentException("开始日期 " + from + " 不是零点建立的完整快照");
            }
            startEntry = new java.util.AbstractMap.SimpleImmutableEntry<>(from, exact);
        }

        LocalDate actualStart = startEntry.getKey();
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = startEntry.getValue();
        List<String> warnings = new java.util.ArrayList<>();
        if (!actualStart.equals(from) || partialSnapshotDates.contains(actualStart)) {
            warnings.add("请求周期缺少完整零点起点，实际从 " + actualStart + " 当日首次可信快照开始");
        }

        Map<UUID, Long> endValues = new HashMap<>();
        if (!to.isBefore(today)) {
            StatReader.readAll(server, metric).forEach(snapshot -> endValues.put(snapshot.uuid(), snapshot.value(metric)));
        } else {
            LocalDate requiredEnd = to.plusDays(1);
            Map<UUID, Map<RankBoardMod.Metric, Long>> end = dailySnapshots.get(requiredEnd);
            if (end == null) throw new IllegalArgumentException("结束日期缺少次日零点快照：" + requiredEnd);
            if (partialSnapshotDates.contains(requiredEnd)) throw new IllegalArgumentException("结束边界 " + requiredEnd + " 不是完整零点快照");
            end.forEach((uuid, values) -> endValues.put(uuid, values.getOrDefault(metric, 0L)));
        }

        Map<UUID, Long> result = new HashMap<>();
        int missing = 0;
        int missingEnd = 0;
        int reset = 0;
        for (UUID uuid : start.keySet()) {
            if (!endValues.containsKey(uuid)) missingEnd++;
        }
        for (Map.Entry<UUID, Long> entry : endValues.entrySet()) {
            Map<RankBoardMod.Metric, Long> baseValues = start.get(entry.getKey());
            if (baseValues == null || !baseValues.containsKey(metric)) { missing++; continue; }
            long base = baseValues.get(metric);
            if (entry.getValue() < base) { reset++; continue; }
            result.put(entry.getKey(), entry.getValue() - base);
        }
        if (missing > 0) warnings.add(missing + " 名玩家缺少开始边界，已排除");
        if (missingEnd > 0) warnings.add(missingEnd + " 名玩家缺少结束边界，已排除");
        if (reset > 0) warnings.add(reset + " 名玩家累计统计发生回退，已排除");
        return new RangeData(actualStart, to, result, warnings.isEmpty(), List.copyOf(warnings));
    }

    public String earliestSnapshotDate() {
        if (dailySnapshots.isEmpty()) return "暂无历史快照";
        LocalDate first = dailySnapshots.firstKey();
        return first + (partialSnapshotDates.contains(first) ? "（部分）" : "");
    }
'''
    updated, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"Could not replace range methods in {path}: {count}")
    file.write_text(updated, encoding="utf-8")


for source in (
    "src/main/java/cn/bamgdam/rankboard/LeaderboardState.java",
    "src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java",
):
    replace_method(source)

for source in (
    "src/main/java/cn/bamgdam/rankboard/WebDashboard.java",
    "src/mojmap/java/cn/bamgdam/rankboard/WebDashboard.java",
):
    file = Path(source)
    text = file.read_text(encoding="utf-8")
    old = "LeaderboardState.RangeData range = state.range(server, from, to, metric);"
    new = "LeaderboardState.RangeData range = state.range(server, from, to, metric, true);"
    if text.count(old) != 1:
        raise SystemExit(f"Unexpected WebDashboard range call count in {source}: {text.count(old)}")
    file.write_text(text.replace(old, new), encoding="utf-8")

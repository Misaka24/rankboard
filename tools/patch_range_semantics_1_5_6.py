from pathlib import Path


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def patch(path):
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    text = replace_once(
        text,
        '''        if (!StatReader.isReady()) throw new IllegalStateException("历史统计仍在权威扫描（" + StatReader.progress() + "）");
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = dailySnapshots.get(from);''',
        '''        if (!StatReader.isReady()) throw new IllegalStateException("历史统计仍在权威扫描（" + StatReader.progress() + "）");
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        LocalDate today = LocalDate.now();
        if (to.isAfter(today)) throw new IllegalArgumentException("结束日期不能晚于今天：" + today);
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = dailySnapshots.get(from);''',
        path + " future date",
    )
    text = replace_once(
        text,
        '''        Map<UUID, Long> endValues = new HashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate endBoundary;
        if (!to.isBefore(today)) {
            endBoundary = today;''',
        '''        Map<UUID, Long> endValues = new HashMap<>();
        if (!to.isBefore(today)) {''',
        path + " boundary variable",
    )
    text = replace_once(
        text,
        '''            endBoundary = requiredEnd;
            end.forEach((uuid, values) -> endValues.put(uuid, values.getOrDefault(metric, 0L)));''',
        '''            end.forEach((uuid, values) -> endValues.put(uuid, values.getOrDefault(metric, 0L)));''',
        path + " internal end boundary",
    )
    text = replace_once(
        text,
        '''        int missing = 0;
        int reset = 0;
        for (Map.Entry<UUID, Long> entry : endValues.entrySet()) {''',
        '''        int missing = 0;
        int missingEnd = 0;
        int reset = 0;
        for (UUID uuid : start.keySet()) {
            if (!endValues.containsKey(uuid)) missingEnd++;
        }
        for (Map.Entry<UUID, Long> entry : endValues.entrySet()) {''',
        path + " missing end",
    )
    text = replace_once(
        text,
        '''        if (missing > 0) warnings.add(missing + " 名玩家缺少开始边界，已排除");
        if (reset > 0) warnings.add(reset + " 名玩家累计统计发生回退，已排除");
        return new RangeData(from, endBoundary, result, warnings.isEmpty(), List.copyOf(warnings));''',
        '''        if (missing > 0) warnings.add(missing + " 名玩家缺少开始边界，已排除");
        if (missingEnd > 0) warnings.add(missingEnd + " 名玩家缺少结束边界，已排除");
        if (reset > 0) warnings.add(reset + " 名玩家累计统计发生回退，已排除");
        return new RangeData(from, to, result, warnings.isEmpty(), List.copyOf(warnings));''',
        path + " inclusive actual end",
    )
    if "endBoundary" in text:
        raise RuntimeError(path + ": stale endBoundary remains")
    file.write_text(text, encoding="utf-8")


patch("src/main/java/cn/bamgdam/rankboard/LeaderboardState.java")
patch("src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java")

readme_path = Path("README.md")
readme = readme_path.read_text(encoding="utf-8")
old = "- 缺少开始基线或累计统计发生回退的玩家会被排除并返回警告，不再按 0 基线制造巨额数据。\n"
new = (
    "- 缺少开始或结束边界、或累计统计发生回退的玩家会被排除并返回警告，不再按 0 基线制造巨额数据。\n"
    "- 自定义范围拒绝未来日期，API 的 `actualEnd` 表示用户选择的包含式结束日期。\n"
)
readme = replace_once(readme, old, new, "README range note")
readme_path.write_text(readme, encoding="utf-8")

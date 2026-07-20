from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


for path in [
    "src/main/java/cn/bamgdam/rankboard/RankBoardMod.java",
    "src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java",
]:
    replace_once(
        path,
        "                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period)) {\n",
        "                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period, metric)) {\n",
    )

for path in [
    "src/main/java/cn/bamgdam/rankboard/BoardService.java",
    "src/mojmap/java/cn/bamgdam/rankboard/BoardService.java",
]:
    replace_once(
        path,
        "        boolean partialPeriod = period != RankBoardMod.Period.ALL\n"
        "                && !LeaderboardState.get(server).isPeriodComplete(period);\n"
        "        ",
        "        boolean partialPeriod = period != RankBoardMod.Period.ALL\n"
        "                && !LeaderboardState.get(server).isPeriodComplete(period, metric);\n"
        "        ",
    )

for path in [
    "src/main/java/cn/bamgdam/rankboard/WebDashboard.java",
    "src/mojmap/java/cn/bamgdam/rankboard/WebDashboard.java",
]:
    replace_once(
        path,
        '        root.addProperty("earliest", state.earliestSnapshotDate());\n',
        '        root.addProperty("earliest", state.earliestSnapshotDate(metric));\n',
    )

from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Unexpected match count in {path}: {count}\n{old[:120]}")
    file.write_text(text.replace(old, new), encoding="utf-8")


yarn_mod = "src/main/java/cn/bamgdam/rankboard/RankBoardMod.java"
moj_mod = "src/mojmap/java/cn/bamgdam/rankboard/RankBoardMod.java"

replace_once(
    yarn_mod,
    '''            }
            List<Entry> entries = entries(source.getServer(), period, metric);''',
    '''            }
            if (StatReader.isReady() && period != Period.ALL
                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period)) {
                source.sendFeedback(() -> Text.literal(period.label
                        + "统计为部分周期：从服务器本周期内首次建立可信基线时开始。")
                        .formatted(Formatting.YELLOW), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);''',
)
replace_once(
    yarn_mod,
    '''        if (period != Period.ALL && !state.isPeriodComplete(period)) {
            throw new IllegalStateException(period.label + "统计没有完整周期边界；服务器需在周期开始时在线并完成统计扫描");
        }
''',
    '',
)

replace_once(
    moj_mod,
    '''            }
            List<Entry> entries = entries(source.getServer(), period, metric);''',
    '''            }
            if (StatReader.isReady() && period != Period.ALL
                    && !LeaderboardState.get(source.getServer()).isPeriodComplete(period)) {
                source.sendSuccess(() -> Component.literal(period.label
                        + "统计为部分周期：从服务器本周期内首次建立可信基线时开始。")
                        .withStyle(ChatFormatting.YELLOW), false);
            }
            List<Entry> entries = entries(source.getServer(), period, metric);''',
)
replace_once(
    moj_mod,
    '''        if (period != Period.ALL && !state.isPeriodComplete(period)) {
            throw new IllegalStateException(period.label + "统计没有完整周期边界；服务器需在周期开始时在线并完成统计扫描");
        }
''',
    '',
)

for source, text_type, literal in (
    ("src/main/java/cn/bamgdam/rankboard/BoardService.java", "Text", "Text.literal"),
    ("src/mojmap/java/cn/bamgdam/rankboard/BoardService.java", "Component", "Component.literal"),
):
    replace_once(
        source,
        '''        if (preference.period() != RankBoardMod.Period.ALL && !state.isPeriodComplete(preference.period())) {
            globalSelection = null;
            RankBoardMod.LOGGER.warn("Skipped restoring incomplete global {} period scoreboard", preference.period().command);
            return;
        }
''',
        '',
    )
    replace_once(
        source,
        '''        if (period != RankBoardMod.Period.ALL && !state.isPeriodComplete(period)) {
            throw new IllegalStateException(period.label + "统计没有完整周期边界");
        }
''',
        '',
    )
    old_overview = f'''        {text_type} title = {literal}(period.label + " 我的总览");'''
    new_overview = f'''        boolean partialPeriod = period != RankBoardMod.Period.ALL && !state.isPeriodComplete(period);
        {text_type} title = {literal}(period.label + (partialPeriod ? "（部分）" : "") + " 我的总览");'''
    replace_once(source, old_overview, new_overview)
    old_title = f'''        {text_type} title = {literal}(period.label + " " + metric.label() + unit);'''
    new_title = f'''        boolean partialPeriod = period != RankBoardMod.Period.ALL
                && !LeaderboardState.get(server).isPeriodComplete(period);
        {text_type} title = {literal}(period.label + (partialPeriod ? "（部分）" : "")
                + " " + metric.label() + unit);'''
    replace_once(source, old_title, new_title)

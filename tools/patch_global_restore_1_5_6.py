from pathlib import Path


def patch(path, objective_type, slot_method):
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    old = f'''    static void restoreGlobal(MinecraftServer server) {{
        LeaderboardState state = LeaderboardState.get(server);
        LeaderboardState.BoardPreference preference = state.globalBoardPreference();
        if (preference == null || !preference.enabled() || !state.isMetricDisplayEnabled(preference.metric())) return;
        globalSelection = new Selection(preference.period(), preference.metric());
        {objective_type} objective = syncObjective(server, globalSelection.period, globalSelection.metric, false);
        server.getScoreboard().{slot_method}('''
    new = f'''    static void restoreGlobal(MinecraftServer server) {{
        LeaderboardState state = LeaderboardState.get(server);
        LeaderboardState.BoardPreference preference = state.globalBoardPreference();
        if (preference == null || !preference.enabled() || !state.isMetricDisplayEnabled(preference.metric())) return;
        if (preference.period() != RankBoardMod.Period.ALL && !state.isPeriodComplete(preference.period())) {{
            globalSelection = null;
            RankBoardMod.LOGGER.warn("Skipped restoring incomplete global {{}} period scoreboard", preference.period().command);
            return;
        }}
        Selection restored = new Selection(preference.period(), preference.metric());
        {objective_type} objective = syncObjective(server, restored.period, restored.metric, false);
        globalSelection = restored;
        server.getScoreboard().{slot_method}('''
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one restoreGlobal anchor, found {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


patch("src/main/java/cn/bamgdam/rankboard/BoardService.java", "ScoreboardObjective", "setObjectiveSlot")
patch("src/mojmap/java/cn/bamgdam/rankboard/BoardService.java", "Objective", "setDisplayObjective")

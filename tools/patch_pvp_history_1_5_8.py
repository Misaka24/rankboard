from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def patch_state(path: str, mojmap: bool) -> None:
    dirty = "setDirty()" if mojmap else "markDirty()"
    compound = "CompoundTag" if mojmap else "NbtCompound"
    list_tag = "ListTag" if mojmap else "NbtList"
    string_tag = "StringTag.valueOf" if mojmap else "NbtString.of"
    tag_type = "Tag" if mojmap else "NbtElement"
    string_type = "Tag.TAG_STRING" if mojmap else "NbtElement.STRING_TYPE"

    # Existing current-period baselines gain only the newly introduced metric at upgrade time.
    old_period_loop = '''            if (old == null || !old.key.equals(period.key(now))) {
                PeriodData replacement = new PeriodData(period, period.key(now),
                        completePeriodBoundary(period, now, nearMidnight));
                snapshots.forEach(replacement::capture);
                periods.put(period, replacement);
                changed = true;
            }
'''
    new_period_loop = '''            if (old == null || !old.key.equals(period.key(now))) {
                PeriodData replacement = new PeriodData(period, period.key(now),
                        completePeriodBoundary(period, now, nearMidnight));
                snapshots.forEach(replacement::capture);
                periods.put(period, replacement);
                changed = true;
            } else if (old.initializeMissingMetrics(snapshots)) {
                changed = true;
            }
'''
    replace_once(path, old_period_loop, new_period_loop)

    # The current date can receive a metric activation baseline, but older snapshots stay immutable.
    old_daily = '''        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            if (LocalTime.now().isAfter(COMPLETE_BOUNDARY_LIMIT)) partialSnapshotDates.add(now);
            else partialSnapshotDates.remove(now);
            changed = true;
        }
        if (changed) ''' + dirty + ''';
'''
    new_daily = '''        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            if (LocalTime.now().isAfter(COMPLETE_BOUNDARY_LIMIT)) partialSnapshotDates.add(now);
            else partialSnapshotDates.remove(now);
            changed = true;
        } else {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = dailySnapshots.get(now);
            boolean activatedMetric = false;
            for (StatSnapshot snapshot : snapshots) {
                Map<RankBoardMod.Metric, Long> playerValues = values.get(snapshot.uuid());
                if (playerValues == null) continue;
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (!playerValues.containsKey(metric)) {
                        playerValues.put(metric, snapshot.value(metric));
                        activatedMetric = true;
                        changed = true;
                    }
                }
            }
            if (activatedMetric) partialSnapshotDates.add(now);
        }
        if (changed) ''' + dirty + ''';
'''
    replace_once(path, old_daily, new_daily)

    old_complete = '''    public boolean isPeriodComplete(RankBoardMod.Period period) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete;
    }
'''
    new_complete = '''    public boolean isPeriodComplete(RankBoardMod.Period period) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete && data.partialMetrics.isEmpty();
    }
    public boolean isPeriodComplete(RankBoardMod.Period period, RankBoardMod.Metric metric) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete && !data.partialMetrics.contains(metric);
    }
'''
    replace_once(path, old_complete, new_complete)

    replace_once(
        path,
        '''        if (allowPartialStart) {
            startEntry = dailySnapshots.ceilingEntry(from);
            if (startEntry == null || startEntry.getKey().isAfter(to)) {
                throw new IllegalArgumentException("所选范围内还没有可用历史快照；最早快照为 " + earliestSnapshotDate());
            }
''',
        '''        if (allowPartialStart) {
            startEntry = firstSnapshotWithMetric(from, to, metric);
            if (startEntry == null) {
                throw new IllegalArgumentException("所选范围内还没有可用的 " + metric.label()
                        + " 快照；最早快照为 " + earliestSnapshotDate(metric));
            }
''',
    )
    replace_once(
        path,
        '''            if (exact == null) {
                throw new IllegalArgumentException("开始日期没有真实边界快照；最早快照为 " + earliestSnapshotDate());
            }
            if (partialSnapshotDates.contains(from)) {
''',
        '''            if (exact == null) {
                throw new IllegalArgumentException("开始日期没有真实边界快照；最早快照为 " + earliestSnapshotDate(metric));
            }
            if (!snapshotHasMetric(exact, metric)) {
                throw new IllegalArgumentException("开始日期 " + from + " 尚未记录 " + metric.label()
                        + "；最早快照为 " + earliestSnapshotDate(metric));
            }
            if (partialSnapshotDates.contains(from)) {
''',
    )
    replace_once(
        path,
        '''            if (end == null) throw new IllegalArgumentException("结束日期缺少次日零点快照：" + requiredEnd);
            if (partialSnapshotDates.contains(requiredEnd)) throw new IllegalArgumentException("结束边界 " + requiredEnd + " 不是完整零点快照");
            end.forEach((uuid, values) -> endValues.put(uuid, values.getOrDefault(metric, 0L)));
''',
        '''            if (end == null) throw new IllegalArgumentException("结束日期缺少次日零点快照：" + requiredEnd);
            if (!snapshotHasMetric(end, metric)) {
                throw new IllegalArgumentException("结束边界 " + requiredEnd + " 尚未记录 " + metric.label());
            }
            if (partialSnapshotDates.contains(requiredEnd)) throw new IllegalArgumentException("结束边界 " + requiredEnd + " 不是完整零点快照");
            end.forEach((uuid, values) -> {
                Long value = values.get(metric);
                if (value != null) endValues.put(uuid, value);
            });
''',
    )

    old_earliest = '''    public String earliestSnapshotDate() {
        if (dailySnapshots.isEmpty()) return "暂无历史快照";
        LocalDate first = dailySnapshots.firstKey();
        return first + (partialSnapshotDates.contains(first) ? "（部分）" : "");
    }
'''
    new_earliest = '''    public String earliestSnapshotDate() {
        if (dailySnapshots.isEmpty()) return "暂无历史快照";
        LocalDate first = dailySnapshots.firstKey();
        return first + (partialSnapshotDates.contains(first) ? "（部分）" : "");
    }

    public String earliestSnapshotDate(RankBoardMod.Metric metric) {
        for (Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> entry : dailySnapshots.entrySet()) {
            if (snapshotHasMetric(entry.getValue(), metric)) {
                return entry.getKey() + (partialSnapshotDates.contains(entry.getKey()) ? "（部分）" : "");
            }
        }
        return "暂无 " + metric.label() + " 快照";
    }

    private Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> firstSnapshotWithMetric(
            LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
        Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> entry = dailySnapshots.ceilingEntry(from);
        while (entry != null && !entry.getKey().isAfter(to)) {
            if (snapshotHasMetric(entry.getValue(), metric)) return entry;
            entry = dailySnapshots.higherEntry(entry.getKey());
        }
        return null;
    }

    private static boolean snapshotHasMetric(Map<UUID, Map<RankBoardMod.Metric, Long>> snapshot,
                                             RankBoardMod.Metric metric) {
        return snapshot.values().stream().anyMatch(values -> values.containsKey(metric));
    }
'''
    replace_once(path, old_earliest, new_earliest)

    old_period_data = '''    private static final class PeriodData {
        final RankBoardMod.Period period; final String key; final boolean complete;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        PeriodData(RankBoardMod.Period period, String key, boolean complete) {
            this.period = period; this.key = key; this.complete = complete;
        }
        void capture(StatSnapshot snapshot) { players.put(snapshot.uuid(), snapshot.values()); }
        ''' + compound + ''' toNbt() {
            ''' + compound + ''' nbt = new ''' + compound + '''(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players)); return nbt;
        }
        static PeriodData fromNbt(''' + compound + ''' nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            return data;
        }
    }
'''
    new_period_data = '''    private static final class PeriodData {
        final RankBoardMod.Period period; final String key; final boolean complete;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        final Set<RankBoardMod.Metric> partialMetrics = new HashSet<>();
        PeriodData(RankBoardMod.Period period, String key, boolean complete) {
            this.period = period; this.key = key; this.complete = complete;
        }
        void capture(StatSnapshot snapshot) { players.put(snapshot.uuid(), new EnumMap<>(snapshot.values())); }
        boolean initializeMissingMetrics(List<StatSnapshot> snapshots) {
            boolean changed = false;
            for (StatSnapshot snapshot : snapshots) {
                Map<RankBoardMod.Metric, Long> values = players.get(snapshot.uuid());
                if (values == null) continue;
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (!values.containsKey(metric)) {
                        values.put(metric, snapshot.value(metric));
                        partialMetrics.add(metric);
                        changed = true;
                    }
                }
            }
            return changed;
        }
        ''' + compound + ''' toNbt() {
            ''' + compound + ''' nbt = new ''' + compound + '''(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players));
            ''' + list_tag + ''' partial = new ''' + list_tag + '''();
            partialMetrics.forEach(metric -> partial.add(''' + string_tag + '''(metric.name())));
            nbt.put("partialMetrics", partial);
            return nbt;
        }
        static PeriodData fromNbt(''' + compound + ''' nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            for (''' + tag_type + ''' element : NbtCompat.getList(nbt, "partialMetrics", ''' + string_type + ''')) {
                try { data.partialMetrics.add(RankBoardMod.Metric.valueOf(NbtCompat.asString(element))); }
                catch (IllegalArgumentException ignored) { }
            }
            return data;
        }
    }
'''
    replace_once(path, old_period_data, new_period_data)


patch_state("src/main/java/cn/bamgdam/rankboard/LeaderboardState.java", False)
patch_state("src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java", True)

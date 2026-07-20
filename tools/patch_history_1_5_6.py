from pathlib import Path
import re


def load(path): return Path(path).read_text(encoding='utf-8')
def save(path, text): Path(path).write_text(text, encoding='utf-8')

def one(text, old, new, label):
    count = text.count(old); print(label, count)
    if count != 1: raise RuntimeError(f'{label}: {count}')
    return text.replace(old, new, 1)

def regex(text, pattern, new, label):
    text, count = re.subn(pattern, new, text, count=1, flags=re.S); print(label, count)
    if count != 1: raise RuntimeError(f'{label}: {count}')
    return text


def patch(path, mojmap):
    text = load(path)
    text = one(text, 'import java.time.LocalDate;\n', 'import java.time.LocalDate;\nimport java.time.LocalTime;\n', path+' time')
    text = one(text, '    private static final String STATE_ID = "rankboard_leaderboard";\n',
        '    private static final String STATE_ID = "rankboard_leaderboard";\n'
        '    private static final int HISTORY_SCHEMA = 2;\n'
        '    private static final LocalTime COMPLETE_BOUNDARY_LIMIT = LocalTime.of(0, 5);\n', path+' constants')
    text = one(text,
        '    private final NavigableMap<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> dailySnapshots = new TreeMap<>();\n',
        '    private final NavigableMap<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> dailySnapshots = new TreeMap<>();\n'
        '    private final Set<LocalDate> partialSnapshotDates = new HashSet<>();\n', path+' partial field')

    tag = 'Tag' if mojmap else 'NbtElement'
    compound = 'CompoundTag' if mojmap else 'NbtCompound'
    compound_type = 'Tag.TAG_COMPOUND' if mojmap else 'NbtElement.COMPOUND_TYPE'
    string_type = 'Tag.TAG_STRING' if mojmap else 'NbtElement.STRING_TYPE'
    old = f'''        for ({tag} element : NbtCompat.getList(nbt, "periods", {compound_type})) {{
            PeriodData data = PeriodData.fromNbt(({compound}) element);
            state.periods.put(data.period, data);
        }}
        for ({tag} element : NbtCompat.getList(nbt, "dailySnapshots", {compound_type})) {{
            {compound} snapshot = ({compound}) element;
            state.dailySnapshots.put(LocalDate.parse(NbtCompat.getString(snapshot, "date")), readPlayers(snapshot));
        }}'''
    new = f'''        if (Integer.toString(HISTORY_SCHEMA).equals(NbtCompat.getString(nbt, "historySchema"))) {{
            for ({tag} element : NbtCompat.getList(nbt, "periods", {compound_type})) {{
                PeriodData data = PeriodData.fromNbt(({compound}) element);
                state.periods.put(data.period, data);
            }}
            for ({tag} element : NbtCompat.getList(nbt, "dailySnapshots", {compound_type})) {{
                {compound} snapshot = ({compound}) element;
                state.dailySnapshots.put(LocalDate.parse(NbtCompat.getString(snapshot, "date")), readPlayers(snapshot));
            }}
            for ({tag} element : NbtCompat.getList(nbt, "partialSnapshotDates", {string_type})) {{
                try {{ state.partialSnapshotDates.add(LocalDate.parse(NbtCompat.asString(element))); }}
                catch (RuntimeException ignored) {{ }}
            }}
        }} else {{
            RankBoardMod.LOGGER.warn("Discarding legacy RankBoard history because its baselines may be polluted");
        }}'''
    text = one(text, old, new, path+' schema load')

    list_line = '        ListTag list = new ListTag();\n' if mojmap else '        NbtList list = new NbtList();\n'
    text = one(text, list_line, '        nbt.putString("historySchema", Integer.toString(HISTORY_SCHEMA));\n'+list_line, path+' schema save')
    after = '        nbt.put("dailySnapshots", snapshots);\n'
    partial = ('        ListTag partialDates = new ListTag();\n'
               '        partialSnapshotDates.forEach(date -> partialDates.add(StringTag.valueOf(date.toString())));\n') if mojmap else (
               '        NbtList partialDates = new NbtList();\n'
               '        partialSnapshotDates.forEach(date -> partialDates.add(NbtString.of(date.toString())));\n')
    partial += '        nbt.put("partialSnapshotDates", partialDates);\n'
    text = one(text, after, after+partial, path+' partial save')

    old_daily = '''        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            changed = true;
        }
        if (backfillMissingMetrics(snapshots)) changed = true;'''
    new_daily = '''        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            if (LocalTime.now().isAfter(COMPLETE_BOUNDARY_LIMIT)) partialSnapshotDates.add(now);
            else partialSnapshotDates.remove(now);
            changed = true;
        }'''
    text = one(text, old_daily, new_daily, path+' immutable snapshot')
    text = regex(text, r'\n    private boolean backfillMissingMetrics\(List<StatSnapshot> snapshots\) \{.*?\n    \}\n    public void ensurePlayer',
                 '\n    public void ensurePlayer', path+' remove backfill')

    range_code = '''    public RangeData range(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
        if (!StatReader.isReady()) throw new IllegalStateException("历史统计仍在权威扫描（" + StatReader.progress() + "）");
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = dailySnapshots.get(from);
        if (start == null) throw new IllegalArgumentException("开始日期没有真实边界快照；最早可完整查询日期为 " + earliestSnapshotDate());
        if (partialSnapshotDates.contains(from)) throw new IllegalArgumentException("开始日期 " + from + " 不是零点建立的完整快照");
        Map<UUID, Long> endValues = new HashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate endBoundary;
        if (!to.isBefore(today)) {
            endBoundary = today;
            StatReader.readAll(server, metric).forEach(snapshot -> endValues.put(snapshot.uuid(), snapshot.value(metric)));
        } else {
            LocalDate requiredEnd = to.plusDays(1);
            Map<UUID, Map<RankBoardMod.Metric, Long>> end = dailySnapshots.get(requiredEnd);
            if (end == null) throw new IllegalArgumentException("结束日期缺少次日零点快照：" + requiredEnd);
            if (partialSnapshotDates.contains(requiredEnd)) throw new IllegalArgumentException("结束边界 " + requiredEnd + " 不是完整零点快照");
            endBoundary = requiredEnd;
            end.forEach((uuid, values) -> endValues.put(uuid, values.getOrDefault(metric, 0L)));
        }
        Map<UUID, Long> result = new HashMap<>();
        int missing = 0;
        int reset = 0;
        for (Map.Entry<UUID, Long> entry : endValues.entrySet()) {
            Map<RankBoardMod.Metric, Long> baseValues = start.get(entry.getKey());
            if (baseValues == null || !baseValues.containsKey(metric)) { missing++; continue; }
            long base = baseValues.get(metric);
            if (entry.getValue() < base) { reset++; continue; }
            result.put(entry.getKey(), entry.getValue() - base);
        }
        List<String> warnings = new java.util.ArrayList<>();
        if (missing > 0) warnings.add(missing + " 名玩家缺少开始边界，已排除");
        if (reset > 0) warnings.add(reset + " 名玩家累计统计发生回退，已排除");
        return new RangeData(from, endBoundary, result, warnings.isEmpty(), List.copyOf(warnings));
    }

'''
    text = regex(text, r'    public RangeData range\(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod.Metric metric\) \{.*?\n    \}\n\n    public String earliestSnapshotDate',
                 range_code+'    public String earliestSnapshotDate', path+' strict range')
    text = regex(text, r'    public String earliestSnapshotDate\(\) \{.*?\n    \}',
        '''    public String earliestSnapshotDate() {
        for (LocalDate date : dailySnapshots.navigableKeySet()) {
            if (!partialSnapshotDates.contains(date)) return date.toString();
        }
        return "暂无完整零点快照";
    }''', path+' earliest')
    text = one(text, '    public record RangeData(LocalDate actualStart, LocalDate actualEnd, Map<UUID, Long> values) { }',
        '    public record RangeData(LocalDate actualStart, LocalDate actualEnd, Map<UUID, Long> values,\n'
        '                            boolean complete, List<String> warnings) { }', path+' record')
    save(path, text)

patch('src/main/java/cn/bamgdam/rankboard/LeaderboardState.java', False)
patch('src/mojmap/java/cn/bamgdam/rankboard/LeaderboardState.java', True)

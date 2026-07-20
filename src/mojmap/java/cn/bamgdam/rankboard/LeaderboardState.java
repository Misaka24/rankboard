package cn.bamgdam.rankboard;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

/** Stores raw-stat baselines, allowing period ranks without modifying vanilla statistics. */
public final class LeaderboardState extends SavedData {
    private static final String STATE_ID = "rankboard_leaderboard";
    private static final int HISTORY_SCHEMA = 3;
    private static final LocalTime COMPLETE_BOUNDARY_LIMIT = LocalTime.of(0, 5);
    private final Map<RankBoardMod.Period, PeriodData> periods = new EnumMap<>(RankBoardMod.Period.class);
    private boolean whitelistOnly = true;
    private boolean botFilterEnabled = true;
    private boolean customPlayerFilterEnabled = true;
    private boolean onlineOnly;
    private final Set<RankBoardMod.Metric> disabledDisplayMetrics = new HashSet<>();
    private final Set<UUID> nameColorDisabledPlayers = new HashSet<>();
    private final Set<UUID> lookMenuDisabledPlayers = new HashSet<>();
    private final Map<UUID, BoardPreference> boardPreferences = new HashMap<>();
    private BoardPreference globalBoardPreference;
    private final NavigableMap<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> dailySnapshots = new TreeMap<>();
    private final Set<LocalDate> partialSnapshotDates = new HashSet<>();
    LeaderboardState() { }

    public static LeaderboardState get(MinecraftServer server) {
        return PersistentStateCompat.get(server, STATE_ID);
    }
    static LeaderboardState fromNbt(CompoundTag nbt, HolderLookup.Provider lookup) {
        LeaderboardState state = new LeaderboardState();
        if (nbt.contains("whitelistOnly")) state.whitelistOnly = NbtCompat.getBoolean(nbt, "whitelistOnly");
        if (nbt.contains("botFilterEnabled")) state.botFilterEnabled = NbtCompat.getBoolean(nbt, "botFilterEnabled");
        if (nbt.contains("customPlayerFilterEnabled")) state.customPlayerFilterEnabled = NbtCompat.getBoolean(nbt, "customPlayerFilterEnabled");
        if (nbt.contains("onlineOnly")) state.onlineOnly = NbtCompat.getBoolean(nbt, "onlineOnly");
        for (Tag element : NbtCompat.getList(nbt, "disabledDisplayMetrics", Tag.TAG_STRING)) {
            try { state.disabledDisplayMetrics.add(RankBoardMod.Metric.valueOf(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        for (Tag element : NbtCompat.getList(nbt, "nameColorDisabledPlayers", Tag.TAG_STRING)) {
            try { state.nameColorDisabledPlayers.add(UUID.fromString(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        for (Tag element : NbtCompat.getList(nbt, "lookMenuDisabledPlayers", Tag.TAG_STRING)) {
            try { state.lookMenuDisabledPlayers.add(UUID.fromString(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (Integer.toString(HISTORY_SCHEMA).equals(NbtCompat.getString(nbt, "historySchema"))) {
            for (Tag element : NbtCompat.getList(nbt, "periods", Tag.TAG_COMPOUND)) {
                PeriodData data = PeriodData.fromNbt((CompoundTag) element);
                state.periods.put(data.period, data);
            }
            for (Tag element : NbtCompat.getList(nbt, "dailySnapshots", Tag.TAG_COMPOUND)) {
                CompoundTag snapshot = (CompoundTag) element;
                state.dailySnapshots.put(LocalDate.parse(NbtCompat.getString(snapshot, "date")), readPlayers(snapshot));
            }
            for (Tag element : NbtCompat.getList(nbt, "partialSnapshotDates", Tag.TAG_STRING)) {
                try { state.partialSnapshotDates.add(LocalDate.parse(NbtCompat.asString(element))); }
                catch (RuntimeException ignored) { }
            }
        } else {
            RankBoardMod.LOGGER.warn("Discarding legacy RankBoard history because its baselines may be polluted");
        }
        for (Tag element : NbtCompat.getList(nbt, "boardPreferences", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag entry = (CompoundTag) element;
                UUID uuid = NbtCompat.getUuid(entry, "uuid");
                RankBoardMod.Period period = RankBoardMod.Period.valueOf(NbtCompat.getString(entry, "period"));
                RankBoardMod.Metric metric = RankBoardMod.Metric.valueOf(NbtCompat.getString(entry, "metric"));
                state.boardPreferences.put(uuid, new BoardPreference(period, metric,
                        NbtCompat.getBoolean(entry, "enabled"), NbtCompat.getBoolean(entry, "carousel"),
                        NbtCompat.getBoolean(entry, "overview")));
            } catch (IllegalArgumentException ignored) { }
        }
        if (nbt.contains("globalBoardPreference")) {
            try {
                CompoundTag entry = NbtCompat.getCompound(nbt, "globalBoardPreference");
                RankBoardMod.Period period = RankBoardMod.Period.valueOf(NbtCompat.getString(entry, "period"));
                RankBoardMod.Metric metric = RankBoardMod.Metric.valueOf(NbtCompat.getString(entry, "metric"));
                state.globalBoardPreference = new BoardPreference(period, metric, true, false, false);
            } catch (IllegalArgumentException ignored) { }
        }
        return state;
    }
    public CompoundTag writeNbt(CompoundTag nbt, HolderLookup.Provider lookup) {
        nbt.putString("historySchema", Integer.toString(HISTORY_SCHEMA));
        ListTag list = new ListTag();
        periods.values().forEach(data -> list.add(data.toNbt()));
        nbt.put("periods", list);
        nbt.putBoolean("whitelistOnly", whitelistOnly);
        nbt.putBoolean("botFilterEnabled", botFilterEnabled);
        nbt.putBoolean("customPlayerFilterEnabled", customPlayerFilterEnabled);
        nbt.putBoolean("onlineOnly", onlineOnly);
        ListTag disabledMetrics = new ListTag();
        disabledDisplayMetrics.forEach(metric -> disabledMetrics.add(StringTag.valueOf(metric.name())));
        nbt.put("disabledDisplayMetrics", disabledMetrics);
        ListTag disabledColors = new ListTag();
        nameColorDisabledPlayers.forEach(uuid -> disabledColors.add(StringTag.valueOf(uuid.toString())));
        nbt.put("nameColorDisabledPlayers", disabledColors);
        ListTag disabledLookMenus = new ListTag();
        lookMenuDisabledPlayers.forEach(uuid -> disabledLookMenus.add(StringTag.valueOf(uuid.toString())));
        nbt.put("lookMenuDisabledPlayers", disabledLookMenus);
        ListTag snapshots = new ListTag();
        dailySnapshots.forEach((date, players) -> {
            CompoundTag snapshot = new CompoundTag();
            snapshot.putString("date", date.toString());
            snapshot.put("players", writePlayers(players));
            snapshots.add(snapshot);
        });
        nbt.put("dailySnapshots", snapshots);
        ListTag partialDates = new ListTag();
        partialSnapshotDates.forEach(date -> partialDates.add(StringTag.valueOf(date.toString())));
        nbt.put("partialSnapshotDates", partialDates);
        ListTag preferences = new ListTag();
        boardPreferences.forEach((uuid, preference) -> {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUuid(entry, "uuid", uuid);
            entry.putString("period", preference.period().name());
            entry.putString("metric", preference.metric().name());
            entry.putBoolean("enabled", preference.enabled());
            entry.putBoolean("carousel", preference.carousel());
            entry.putBoolean("overview", preference.overview());
            preferences.add(entry);
        });
        nbt.put("boardPreferences", preferences);
        if (globalBoardPreference != null && globalBoardPreference.enabled()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("period", globalBoardPreference.period().name());
            entry.putString("metric", globalBoardPreference.metric().name());
            nbt.put("globalBoardPreference", entry);
        }
        return nbt;
    }
    public void rollPeriods(MinecraftServer server) {
        if (!StatReader.isReady()) return;
        LocalDate now = LocalDate.now();
        LocalTime boundaryTime = LocalTime.now();
        boolean nearMidnight = !boundaryTime.isAfter(COMPLETE_BOUNDARY_LIMIT);
        boolean changed = false;
        List<StatSnapshot> snapshots = StatReader.readAll(server);
        for (RankBoardMod.Period period : RankBoardMod.Period.values()) {
            if (period == RankBoardMod.Period.ALL) continue;
            PeriodData old = periods.get(period);
            if (old == null || !old.key.equals(period.key(now))) {
                PeriodData replacement = new PeriodData(period, period.key(now),
                        completePeriodBoundary(period, now, nearMidnight));
                snapshots.forEach(replacement::capture);
                periods.put(period, replacement);
                changed = true;
            }
        }
        if (!dailySnapshots.containsKey(now)) {
            Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
            snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
            dailySnapshots.put(now, values);
            if (LocalTime.now().isAfter(COMPLETE_BOUNDARY_LIMIT)) partialSnapshotDates.add(now);
            else partialSnapshotDates.remove(now);
            changed = true;
        }
        if (changed) setDirty();
    }

    public void ensurePlayer(ServerPlayer player) {
        rollPeriods(PlayerCompat.server(player));
        StatSnapshot snapshot = StatSnapshot.fromPlayer(player);
        boolean changed = false;
        for (PeriodData data : periods.values()) if (data.players.putIfAbsent(snapshot.uuid(), snapshot.values()) == null) changed = true;
        if (changed) setDirty();
    }
    public long getBaseline(RankBoardMod.Period period, UUID uuid, RankBoardMod.Metric metric) {
        PeriodData data = periods.get(period);
        return data == null ? 0 : data.players.getOrDefault(uuid, Map.of()).getOrDefault(metric, 0L);
    }
    public boolean isPeriodComplete(RankBoardMod.Period period) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete;
    }
    public boolean isWhitelistOnly() { return whitelistOnly; }
    public void setWhitelistOnly(boolean whitelistOnly) {
        if (this.whitelistOnly != whitelistOnly) {
            this.whitelistOnly = whitelistOnly;
            setDirty();
        }
    }
    public boolean isBotFilterEnabled() { return botFilterEnabled; }
    public void setBotFilterEnabled(boolean enabled) {
        if (botFilterEnabled != enabled) {
            botFilterEnabled = enabled;
            setDirty();
        }
    }
    public boolean isCustomPlayerFilterEnabled() { return customPlayerFilterEnabled; }
    public void setCustomPlayerFilterEnabled(boolean enabled) {
        if (customPlayerFilterEnabled != enabled) {
            customPlayerFilterEnabled = enabled;
            setDirty();
        }
    }
    public boolean isOnlineOnly() { return onlineOnly; }
    public void setOnlineOnly(boolean enabled) {
        if (onlineOnly != enabled) {
            onlineOnly = enabled;
            setDirty();
        }
    }
    public boolean isMetricDisplayEnabled(RankBoardMod.Metric metric) { return !disabledDisplayMetrics.contains(metric); }
    public void setMetricDisplayEnabled(RankBoardMod.Metric metric, boolean enabled) {
        boolean changed = enabled ? disabledDisplayMetrics.remove(metric) : disabledDisplayMetrics.add(metric);
        if (changed) setDirty();
    }
    public boolean isNameColorEnabled(UUID uuid) { return !nameColorDisabledPlayers.contains(uuid); }
    public void setNameColorEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? nameColorDisabledPlayers.remove(uuid) : nameColorDisabledPlayers.add(uuid);
        if (changed) setDirty();
    }

    public boolean isLookMenuEnabled(UUID uuid) { return !lookMenuDisabledPlayers.contains(uuid); }
    public void setLookMenuEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? lookMenuDisabledPlayers.remove(uuid) : lookMenuDisabledPlayers.add(uuid);
        if (changed) setDirty();
    }

    public BoardPreference boardPreference(UUID uuid) { return boardPreferences.get(uuid); }

    public void setBoardPreference(UUID uuid, RankBoardMod.Period period, RankBoardMod.Metric metric,
                                   boolean enabled, boolean carousel) {
        BoardPreference replacement = new BoardPreference(period, metric, enabled, carousel, false);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) setDirty();
    }

    public void setOverviewPreference(UUID uuid, RankBoardMod.Period period, boolean enabled) {
        BoardPreference replacement = new BoardPreference(period, RankBoardMod.Metric.PLAY_TIME, enabled, false, enabled);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) setDirty();
    }

    public void disableBoard(UUID uuid) {
        BoardPreference current = boardPreferences.get(uuid);
        if (current != null && current.enabled()) {
            boardPreferences.put(uuid, new BoardPreference(current.period(), current.metric(), false, false, false));
            setDirty();
        }
    }

    public BoardPreference globalBoardPreference() { return globalBoardPreference; }

    public void setGlobalBoardPreference(RankBoardMod.Period period, RankBoardMod.Metric metric) {
        BoardPreference replacement = new BoardPreference(period, metric, true, false, false);
        if (!replacement.equals(globalBoardPreference)) {
            globalBoardPreference = replacement;
            setDirty();
        }
    }

    public void clearGlobalBoardPreference() {
        if (globalBoardPreference != null) {
            globalBoardPreference = null;
            setDirty();
        }
    }

    public RangeData range(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
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

    public record RangeData(LocalDate actualStart, LocalDate actualEnd, Map<UUID, Long> values,
                            boolean complete, List<String> warnings) { }
    public record BoardPreference(RankBoardMod.Period period, RankBoardMod.Metric metric,
                                  boolean enabled, boolean carousel, boolean overview) { }

    private static boolean completePeriodBoundary(RankBoardMod.Period period, LocalDate date, boolean nearMidnight) {
        if (!nearMidnight) return false;
        return switch (period) {
            case DAILY -> true;
            case WEEKLY -> date.getDayOfWeek() == java.time.DayOfWeek.MONDAY;
            case MONTHLY -> date.getDayOfMonth() == 1;
            case YEARLY -> date.getDayOfYear() == 1;
            case ALL -> true;
        };
    }

    private static ListTag writePlayers(Map<UUID, Map<RankBoardMod.Metric, Long>> players) {
        ListTag list = new ListTag();
        players.forEach((uuid, values) -> {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUuid(entry, "uuid", uuid);
            values.forEach((metric, value) -> entry.putLong(metric.command, value));
            list.add(entry);
        });
        return list;
    }

    private static Map<UUID, Map<RankBoardMod.Metric, Long>> readPlayers(CompoundTag owner) {
        Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        for (Tag element : NbtCompat.getList(owner, "players", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) element;
            Map<RankBoardMod.Metric, Long> values = new EnumMap<>(RankBoardMod.Metric.class);
            for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                if (entry.contains(metric.command)) values.put(metric, NbtCompat.getLong(entry, metric.command));
            }
            players.put(NbtCompat.getUuid(entry, "uuid"), values);
        }
        return players;
    }
    private static final class PeriodData {
        final RankBoardMod.Period period; final String key; final boolean complete;
        final Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        PeriodData(RankBoardMod.Period period, String key, boolean complete) {
            this.period = period; this.key = key; this.complete = complete;
        }
        void capture(StatSnapshot snapshot) { players.put(snapshot.uuid(), snapshot.values()); }
        CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players)); return nbt;
        }
        static PeriodData fromNbt(CompoundTag nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            return data;
        }
    }
}

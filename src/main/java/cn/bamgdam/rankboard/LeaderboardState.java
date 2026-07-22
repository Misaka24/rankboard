package cn.bamgdam.rankboard;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.OptionalLong;
import java.util.UUID;

/** Stores raw-stat baselines, allowing period ranks without modifying vanilla statistics. */
public final class LeaderboardState extends PersistentState {
    private static final String LEGACY_STATE_ID = "rankboard_leaderboard";
    private static final String STATE_ID = "rankboard/" + LEGACY_STATE_ID;
    private static final int HISTORY_SCHEMA = 5;
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
    private HistorySnapshotStore historyStore;
    LeaderboardState() { }

    public static LeaderboardState get(MinecraftServer server) {
        prepareStorage(server);
        LeaderboardState state = PersistentStateCompat.get(server, STATE_ID);
        state.attachHistoryStore(server.getSavePath(WorldSavePath.ROOT).resolve("data/rankboard/history"));
        return state;
    }

    private synchronized void attachHistoryStore(Path directory) {
        if (historyStore != null) return;
        historyStore = new HistorySnapshotStore(directory);
        if (dailySnapshots.isEmpty()) return;
        try {
            historyStore.importLegacy(dailySnapshots, partialSnapshotDates);
            int migrated = dailySnapshots.size();
            dailySnapshots.clear();
            partialSnapshotDates.clear();
            markDirty();
            RankBoardMod.LOGGER.info("Migrated {} RankBoard daily snapshots to monthly files in {}", migrated, directory);
        } catch (IOException exception) {
            RankBoardMod.LOGGER.error("Could not migrate RankBoard history to {}; legacy snapshots remain in the main data file",
                    directory, exception);
        }
    }
    private static synchronized void prepareStorage(MinecraftServer server) {
        Path dataDirectory = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        Path rankBoardDirectory = dataDirectory.resolve("rankboard");
        Path legacy = dataDirectory.resolve(LEGACY_STATE_ID + ".dat");
        Path target = rankBoardDirectory.resolve(LEGACY_STATE_ID + ".dat");
        try {
            Files.createDirectories(rankBoardDirectory);
            if (Files.isRegularFile(target) || !Files.isRegularFile(legacy)) return;
            Path temporary = target.resolveSibling(target.getFileName() + ".migrating");
            Files.copy(legacy, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.delete(legacy);
            RankBoardMod.LOGGER.info("Migrated RankBoard world data from {} to {}", legacy, target);
        } catch (IOException exception) {
            RankBoardMod.LOGGER.warn("Could not migrate RankBoard world data from {} to {}; the legacy file was kept",
                    legacy, target, exception);
        }
    }
    static LeaderboardState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        LeaderboardState state = new LeaderboardState();
        if (nbt.contains("whitelistOnly")) state.whitelistOnly = NbtCompat.getBoolean(nbt, "whitelistOnly");
        if (nbt.contains("botFilterEnabled")) state.botFilterEnabled = NbtCompat.getBoolean(nbt, "botFilterEnabled");
        if (nbt.contains("customPlayerFilterEnabled")) state.customPlayerFilterEnabled = NbtCompat.getBoolean(nbt, "customPlayerFilterEnabled");
        if (nbt.contains("onlineOnly")) state.onlineOnly = NbtCompat.getBoolean(nbt, "onlineOnly");
        for (NbtElement element : NbtCompat.getList(nbt, "disabledDisplayMetrics", NbtElement.STRING_TYPE)) {
            try { state.disabledDisplayMetrics.add(RankBoardMod.Metric.valueOf(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        for (NbtElement element : NbtCompat.getList(nbt, "nameColorDisabledPlayers", NbtElement.STRING_TYPE)) {
            try { state.nameColorDisabledPlayers.add(UUID.fromString(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        for (NbtElement element : NbtCompat.getList(nbt, "lookMenuDisabledPlayers", NbtElement.STRING_TYPE)) {
            try { state.lookMenuDisabledPlayers.add(UUID.fromString(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        String historySchema = NbtCompat.getString(nbt, "historySchema");
        if ("3".equals(historySchema) || "4".equals(historySchema)
                || Integer.toString(HISTORY_SCHEMA).equals(historySchema)) {
            for (NbtElement element : NbtCompat.getList(nbt, "periods", NbtElement.COMPOUND_TYPE)) {
                PeriodData data = PeriodData.fromNbt((NbtCompound) element);
                state.periods.put(data.period, data);
            }
            for (NbtElement element : NbtCompat.getList(nbt, "dailySnapshots", NbtElement.COMPOUND_TYPE)) {
                NbtCompound snapshot = (NbtCompound) element;
                state.dailySnapshots.put(LocalDate.parse(NbtCompat.getString(snapshot, "date")), readPlayers(snapshot));
            }
            for (NbtElement element : NbtCompat.getList(nbt, "partialSnapshotDates", NbtElement.STRING_TYPE)) {
                try { state.partialSnapshotDates.add(LocalDate.parse(NbtCompat.asString(element))); }
                catch (RuntimeException ignored) { }
            }
        } else {
            RankBoardMod.LOGGER.warn("Discarding legacy RankBoard history because its baselines may be polluted");
        }
        for (NbtElement element : NbtCompat.getList(nbt, "boardPreferences", NbtElement.COMPOUND_TYPE)) {
            try {
                NbtCompound entry = (NbtCompound) element;
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
                NbtCompound entry = NbtCompat.getCompound(nbt, "globalBoardPreference");
                RankBoardMod.Period period = RankBoardMod.Period.valueOf(NbtCompat.getString(entry, "period"));
                RankBoardMod.Metric metric = RankBoardMod.Metric.valueOf(NbtCompat.getString(entry, "metric"));
                state.globalBoardPreference = new BoardPreference(period, metric, true, false, false);
            } catch (IllegalArgumentException ignored) { }
        }
        return state;
    }
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        nbt.putString("historySchema", Integer.toString(HISTORY_SCHEMA));
        NbtList list = new NbtList();
        periods.values().forEach(data -> list.add(data.toNbt()));
        nbt.put("periods", list);
        nbt.putBoolean("whitelistOnly", whitelistOnly);
        nbt.putBoolean("botFilterEnabled", botFilterEnabled);
        nbt.putBoolean("customPlayerFilterEnabled", customPlayerFilterEnabled);
        nbt.putBoolean("onlineOnly", onlineOnly);
        NbtList disabledMetrics = new NbtList();
        disabledDisplayMetrics.forEach(metric -> disabledMetrics.add(NbtString.of(metric.name())));
        nbt.put("disabledDisplayMetrics", disabledMetrics);
        NbtList disabledColors = new NbtList();
        nameColorDisabledPlayers.forEach(uuid -> disabledColors.add(NbtString.of(uuid.toString())));
        nbt.put("nameColorDisabledPlayers", disabledColors);
        NbtList disabledLookMenus = new NbtList();
        lookMenuDisabledPlayers.forEach(uuid -> disabledLookMenus.add(NbtString.of(uuid.toString())));
        nbt.put("lookMenuDisabledPlayers", disabledLookMenus);
        NbtList snapshots = new NbtList();
        dailySnapshots.forEach((date, players) -> {
            NbtCompound snapshot = new NbtCompound();
            snapshot.putString("date", date.toString());
            snapshot.put("players", writePlayers(players));
            snapshots.add(snapshot);
        });
        nbt.put("dailySnapshots", snapshots);
        NbtList partialDates = new NbtList();
        partialSnapshotDates.forEach(date -> partialDates.add(NbtString.of(date.toString())));
        nbt.put("partialSnapshotDates", partialDates);
        NbtList preferences = new NbtList();
        boardPreferences.forEach((uuid, preference) -> {
            NbtCompound entry = new NbtCompound();
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
            NbtCompound entry = new NbtCompound();
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
            } else if (old.initializeMissingMetrics(snapshots)) {
                changed = true;
            }
        }
        try {
            HistorySnapshotStore.Snapshot daily = historyStore.get(now).orElse(null);
            if (daily == null) {
                Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
                snapshots.forEach(snapshot -> values.put(snapshot.uuid(), new EnumMap<>(snapshot.values())));
                historyStore.put(now, values, boundaryTime.isAfter(COMPLETE_BOUNDARY_LIMIT));
            } else {
                Map<UUID, Map<RankBoardMod.Metric, Long>> values = new HashMap<>();
                daily.players().forEach((uuid, playerValues) -> values.put(uuid, new EnumMap<>(playerValues)));
                boolean activatedMetric = false;
                for (StatSnapshot snapshot : snapshots) {
                    Map<RankBoardMod.Metric, Long> playerValues = values.get(snapshot.uuid());
                    if (playerValues == null) continue;
                    for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                        if (!playerValues.containsKey(metric)) {
                            playerValues.put(metric, snapshot.value(metric));
                            activatedMetric = true;
                        }
                    }
                }
                if (activatedMetric) historyStore.put(now, values, true);
            }
        } catch (IOException exception) {
            RankBoardMod.LOGGER.error("Could not update RankBoard history snapshot in {}", historyStore.directory(), exception);
        }
        if (changed) markDirty();
    }

    public void ensurePlayer(ServerPlayerEntity player) {
        rollPeriods(PlayerCompat.server(player));
        StatSnapshot snapshot = StatSnapshot.fromPlayer(player);
        boolean changed = false;
        for (PeriodData data : periods.values()) if (data.players.putIfAbsent(snapshot.uuid(), snapshot.values()) == null) changed = true;
        if (changed) markDirty();
    }
    public OptionalLong periodDelta(RankBoardMod.Period period, UUID uuid,
                                    RankBoardMod.Metric metric, long currentValue) {
        if (period == RankBoardMod.Period.ALL) return OptionalLong.of(Math.max(0L, currentValue));
        PeriodData data = periods.get(period);
        if (data == null) return OptionalLong.empty();
        Map<RankBoardMod.Metric, Long> values = data.players.get(uuid);
        if (values == null || !values.containsKey(metric)) return OptionalLong.empty();
        long baseline = values.get(metric);
        if (currentValue < baseline) return OptionalLong.empty();
        return OptionalLong.of(currentValue - baseline);
    }
    public boolean isPeriodComplete(RankBoardMod.Period period) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete && data.partialMetrics.isEmpty();
    }
    public boolean isPeriodComplete(RankBoardMod.Period period, RankBoardMod.Metric metric) {
        if (period == RankBoardMod.Period.ALL) return true;
        PeriodData data = periods.get(period);
        return data != null && data.complete && !data.partialMetrics.contains(metric);
    }
    public boolean isWhitelistOnly() { return whitelistOnly; }
    public void setWhitelistOnly(boolean whitelistOnly) {
        if (this.whitelistOnly != whitelistOnly) {
            this.whitelistOnly = whitelistOnly;
            markDirty();
        }
    }
    public boolean isBotFilterEnabled() { return botFilterEnabled; }
    public void setBotFilterEnabled(boolean enabled) {
        if (botFilterEnabled != enabled) {
            botFilterEnabled = enabled;
            markDirty();
        }
    }
    public boolean isCustomPlayerFilterEnabled() { return customPlayerFilterEnabled; }
    public void setCustomPlayerFilterEnabled(boolean enabled) {
        if (customPlayerFilterEnabled != enabled) {
            customPlayerFilterEnabled = enabled;
            markDirty();
        }
    }
    public boolean isOnlineOnly() { return onlineOnly; }
    public void setOnlineOnly(boolean enabled) {
        if (onlineOnly != enabled) {
            onlineOnly = enabled;
            markDirty();
        }
    }
    public boolean isMetricDisplayEnabled(RankBoardMod.Metric metric) { return !disabledDisplayMetrics.contains(metric); }
    public void setMetricDisplayEnabled(RankBoardMod.Metric metric, boolean enabled) {
        boolean changed = enabled ? disabledDisplayMetrics.remove(metric) : disabledDisplayMetrics.add(metric);
        if (changed) markDirty();
    }
    public boolean isNameColorEnabled(UUID uuid) { return !nameColorDisabledPlayers.contains(uuid); }
    public void setNameColorEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? nameColorDisabledPlayers.remove(uuid) : nameColorDisabledPlayers.add(uuid);
        if (changed) markDirty();
    }

    public boolean isLookMenuEnabled(UUID uuid) { return !lookMenuDisabledPlayers.contains(uuid); }
    public void setLookMenuEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? lookMenuDisabledPlayers.remove(uuid) : lookMenuDisabledPlayers.add(uuid);
        if (changed) markDirty();
    }

    public BoardPreference boardPreference(UUID uuid) { return boardPreferences.get(uuid); }

    public void setBoardPreference(UUID uuid, RankBoardMod.Period period, RankBoardMod.Metric metric,
                                   boolean enabled, boolean carousel) {
        BoardPreference replacement = new BoardPreference(period, metric, enabled, carousel, false);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) markDirty();
    }

    public void setOverviewPreference(UUID uuid, RankBoardMod.Period period, boolean enabled) {
        BoardPreference replacement = new BoardPreference(
                period, RankBoardMod.Metric.PLAY_TIME, enabled, false, enabled);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) markDirty();
    }

    public void disableBoard(UUID uuid) {
        BoardPreference current = boardPreferences.get(uuid);
        if (current != null && current.enabled()) {
            boardPreferences.put(uuid, new BoardPreference(current.period(), current.metric(), false, false, false));
            markDirty();
        }
    }

    public BoardPreference globalBoardPreference() { return globalBoardPreference; }

    public void setGlobalBoardPreference(RankBoardMod.Period period, RankBoardMod.Metric metric) {
        BoardPreference replacement = new BoardPreference(period, metric, true, false, false);
        if (!replacement.equals(globalBoardPreference)) {
            globalBoardPreference = replacement;
            markDirty();
        }
    }

    public void clearGlobalBoardPreference() {
        if (globalBoardPreference != null) {
            globalBoardPreference = null;
            markDirty();
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
            startEntry = firstSnapshotWithMetric(from, to, metric);
            if (startEntry == null) {
                throw new IllegalArgumentException("所选范围内还没有可用的 " + metric.label()
                        + " 快照；最早快照为 " + earliestSnapshotDate(metric));
            }
        } else {
            HistorySnapshotStore.Snapshot exactSnapshot = historySnapshot(from);
            Map<UUID, Map<RankBoardMod.Metric, Long>> exact = exactSnapshot == null ? null : exactSnapshot.players();
            if (exact == null) {
                throw new IllegalArgumentException("开始日期没有真实边界快照；最早快照为 " + earliestSnapshotDate(metric));
            }
            if (!snapshotHasMetric(exact, metric)) {
                throw new IllegalArgumentException("开始日期 " + from + " 尚未记录 " + metric.label()
                        + "；最早快照为 " + earliestSnapshotDate(metric));
            }
            if (exactSnapshot.partial()) {
                throw new IllegalArgumentException("开始日期 " + from + " 不是零点建立的完整快照");
            }
            startEntry = new java.util.AbstractMap.SimpleImmutableEntry<>(from, exact);
        }

        LocalDate actualStart = startEntry.getKey();
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = startEntry.getValue();
        List<String> warnings = new java.util.ArrayList<>();
        if (!actualStart.equals(from) || isPartialSnapshot(actualStart)) {
            warnings.add("请求周期缺少完整零点起点，实际从 " + actualStart + " 当日首次可信快照开始");
        }

        Map<UUID, Long> endValues = new HashMap<>();
        if (!to.isBefore(today)) {
            StatReader.readAll(server, metric).forEach(snapshot -> endValues.put(snapshot.uuid(), snapshot.value(metric)));
        } else {
            LocalDate requiredEnd = to.plusDays(1);
            HistorySnapshotStore.Snapshot endSnapshot = historySnapshot(requiredEnd);
            Map<UUID, Map<RankBoardMod.Metric, Long>> end = endSnapshot == null ? null : endSnapshot.players();
            if (end == null) throw new IllegalArgumentException("结束日期缺少次日零点快照：" + requiredEnd);
            if (!snapshotHasMetric(end, metric)) {
                throw new IllegalArgumentException("结束边界 " + requiredEnd + " 尚未记录 " + metric.label());
            }
            if (endSnapshot.partial()) throw new IllegalArgumentException("结束边界 " + requiredEnd + " 不是完整零点快照");
            end.forEach((uuid, values) -> {
                Long value = values.get(metric);
                if (value != null) endValues.put(uuid, value);
            });
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
        Map.Entry<LocalDate, HistorySnapshotStore.Snapshot> entry = earliestHistorySnapshot(null);
        if (entry == null) return "暂无历史快照";
        return entry.getKey() + (entry.getValue().partial() ? "（部分）" : "");
    }

    public String earliestSnapshotDate(RankBoardMod.Metric metric) {
        Map.Entry<LocalDate, HistorySnapshotStore.Snapshot> entry = earliestHistorySnapshot(metric);
        if (entry == null) return "暂无 " + metric.label() + " 快照";
        return entry.getKey() + (entry.getValue().partial() ? "（部分）" : "");
    }

    private Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> firstSnapshotWithMetric(
            LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
        try {
            Map.Entry<LocalDate, HistorySnapshotStore.Snapshot> entry = historyStore.firstWithMetric(from, to, metric);
            return entry == null ? null : new java.util.AbstractMap.SimpleImmutableEntry<>(
                    entry.getKey(), entry.getValue().players());
        } catch (IOException exception) {
            throw historyReadFailure(exception);
        }
    }

    private HistorySnapshotStore.Snapshot historySnapshot(LocalDate date) {
        try { return historyStore.get(date).orElse(null); }
        catch (IOException exception) { throw historyReadFailure(exception); }
    }

    private boolean isPartialSnapshot(LocalDate date) {
        HistorySnapshotStore.Snapshot snapshot = historySnapshot(date);
        return snapshot != null && snapshot.partial();
    }

    private Map.Entry<LocalDate, HistorySnapshotStore.Snapshot> earliestHistorySnapshot(RankBoardMod.Metric metric) {
        try { return historyStore.earliest(metric); }
        catch (IOException exception) { throw historyReadFailure(exception); }
    }

    private IllegalStateException historyReadFailure(IOException exception) {
        RankBoardMod.LOGGER.error("Could not read RankBoard history snapshots from {}", historyStore.directory(), exception);
        return new IllegalStateException("历史快照文件读取失败，请检查服务器日志", exception);
    }
    private static boolean snapshotHasMetric(Map<UUID, Map<RankBoardMod.Metric, Long>> snapshot,
                                             RankBoardMod.Metric metric) {
        return snapshot.values().stream().anyMatch(values -> values.containsKey(metric));
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

    private static NbtList writePlayers(Map<UUID, Map<RankBoardMod.Metric, Long>> players) {
        NbtList list = new NbtList();
        players.forEach((uuid, values) -> {
            NbtCompound entry = new NbtCompound();
            NbtCompat.putUuid(entry, "uuid", uuid);
            values.forEach((metric, value) -> entry.putLong(metric.command, value));
            list.add(entry);
        });
        return list;
    }

    private static Map<UUID, Map<RankBoardMod.Metric, Long>> readPlayers(NbtCompound owner) {
        Map<UUID, Map<RankBoardMod.Metric, Long>> players = new HashMap<>();
        for (NbtElement element : NbtCompat.getList(owner, "players", NbtElement.COMPOUND_TYPE)) {
            NbtCompound entry = (NbtCompound) element;
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
        NbtCompound toNbt() {
            NbtCompound nbt = new NbtCompound(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players));
            NbtList partial = new NbtList();
            partialMetrics.forEach(metric -> partial.add(NbtString.of(metric.name())));
            nbt.put("partialMetrics", partial);
            return nbt;
        }
        static PeriodData fromNbt(NbtCompound nbt) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            for (NbtElement element : NbtCompat.getList(nbt, "partialMetrics", NbtElement.STRING_TYPE)) {
                try { data.partialMetrics.add(RankBoardMod.Metric.valueOf(NbtCompat.asString(element))); }
                catch (IllegalArgumentException ignored) { }
            }
            return data;
        }
    }
}

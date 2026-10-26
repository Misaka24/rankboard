package cn.bamgdam.rankboard;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Stores raw-stat baselines, allowing period ranks without modifying vanilla statistics. */
public final class LeaderboardState extends SavedData {
    private static final String LEGACY_STATE_ID = "rankboard_leaderboard";
    private static final String STATE_ID = "rankboard/" + LEGACY_STATE_ID;
    private static final int HISTORY_SCHEMA = 5;
    private static final LocalTime COMPLETE_BOUNDARY_LIMIT = LocalTime.of(0, 5);
    private static final Set<MinecraftServer> STORAGE_PREPARED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Map<RankBoardMod.Period, PeriodData> periods = new EnumMap<>(RankBoardMod.Period.class);
    private boolean whitelistOnly;
    // New worlds choose a whitelist mode from the RankBoard menu. Worlds saved
    // before this flag existed are treated as already configured on upgrade.
    private boolean whitelistModeConfigured;
    private boolean botFilterEnabled = true;
    private boolean customPlayerFilterEnabled = true;
    private boolean onlineOnly;
    /** Manually hidden players, keyed by lower-case name. A null UUID means the name is not resolved yet. */
    private final Map<String, UUID> filteredPlayers = new LinkedHashMap<>();
    /** Players explicitly allowed to bypass the automatic bot_/unknown_ prefix filters. */
    private final Map<String, UUID> allowedPlayers = new LinkedHashMap<>();
    /** Players an operator explicitly classified as bots (shown only in the bot group). */
    private final Map<String, UUID> botPlayers = new LinkedHashMap<>();
    /** When true the bot group is also listed together with normal players. */
    private boolean botGroupMixed;
    private final Set<RankBoardMod.Metric> disabledDisplayMetrics = new HashSet<>();
    private final Set<UUID> nameColorDisabledPlayers = new HashSet<>();
    private final Set<UUID> lookMenuDisabledPlayers = new HashSet<>();
    private final Set<UUID> joinMenuDisabledPlayers = new HashSet<>();
    private final Map<UUID, String> playerLanguages = new HashMap<>();
    /** Runtime metrics which are not present in vanilla statistics JSON files. */
    private final Map<UUID, EnumMap<RankBoardMod.Metric, Long>> customMetricValues = new HashMap<>();
    private final Map<UUID, EnumMap<RankBoardMod.Metric, Long>> metricOverrides = new HashMap<>();
    private final Map<UUID, BoardPreference> boardPreferences = new HashMap<>();
    private BoardPreference globalBoardPreference;
    private final NavigableMap<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> dailySnapshots = new TreeMap<>();
    private final Set<LocalDate> partialSnapshotDates = new HashSet<>();
    private HistorySnapshotStore historyStore;
    private LocalDate lastPeriodReadDate;
    private long lastPeriodReadRevision = -1L;
    LeaderboardState() { }

    public static LeaderboardState get(MinecraftServer server) {
        prepareStorage(server);
        LeaderboardState state = PersistentStateCompat.get(server, STATE_ID);
        state.attachHistoryStore(server.getWorldPath(LevelResource.ROOT).resolve("data/rankboard/history"));
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
            setDirty();
            RankBoardMod.LOGGER.info("Migrated {} RankBoard daily snapshots to monthly files in {}", migrated, directory);
        } catch (IOException exception) {
            RankBoardMod.LOGGER.error("Could not migrate RankBoard history to {}; legacy snapshots remain in the main data file",
                    directory, exception);
        }
    }
    private static synchronized void prepareStorage(MinecraftServer server) {
        if (STORAGE_PREPARED.contains(server)) return;
        Path dataDirectory = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Path rankBoardDirectory = dataDirectory.resolve("rankboard");
        Path legacy = dataDirectory.resolve(LEGACY_STATE_ID + ".dat");
        Path target = rankBoardDirectory.resolve(LEGACY_STATE_ID + ".dat");
        try {
            Files.createDirectories(rankBoardDirectory);
            if (Files.isRegularFile(target) || !Files.isRegularFile(legacy)) {
                STORAGE_PREPARED.add(server);
                return;
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".migrating");
            Files.copy(legacy, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.delete(legacy);
            STORAGE_PREPARED.add(server);
            RankBoardMod.LOGGER.info("Migrated RankBoard world data from {} to {}", legacy, target);
        } catch (IOException exception) {
            RankBoardMod.LOGGER.warn("Could not migrate RankBoard world data from {} to {}; the legacy file was kept",
                    legacy, target, exception);
        }
    }
    private static void readPlayerFilters(CompoundTag nbt, String key, Map<String, UUID> target) {
        for (Tag element : NbtCompat.getList(nbt, key, Tag.TAG_COMPOUND)) {
            try {
                CompoundTag entry = (CompoundTag) element;
                String name = NbtCompat.getString(entry, "name");
                if (name == null || name.isBlank()) continue;
                UUID uuid = entry.contains("uuid") ? NbtCompat.getUuid(entry, "uuid") : null;
                target.put(name.toLowerCase(Locale.ROOT), uuid);
            } catch (RuntimeException ignored) { }
        }
    }

    private static ListTag writePlayerFilters(Map<String, UUID> entries) {
        ListTag list = new ListTag();
        entries.forEach((name, uuid) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", name);
            if (uuid != null) NbtCompat.putUuid(entry, "uuid", uuid);
            list.add(entry);
        });
        return list;
    }

    static LeaderboardState fromNbt(CompoundTag nbt, HolderLookup.Provider lookup) {
        LeaderboardState state = new LeaderboardState();
        if (nbt.contains("whitelistOnly")) state.whitelistOnly = NbtCompat.getBoolean(nbt, "whitelistOnly");
        state.whitelistModeConfigured = nbt.contains("whitelistModeConfigured")
                ? NbtCompat.getBoolean(nbt, "whitelistModeConfigured") : true;
        if (nbt.contains("botFilterEnabled")) state.botFilterEnabled = NbtCompat.getBoolean(nbt, "botFilterEnabled");
        if (nbt.contains("customPlayerFilterEnabled")) state.customPlayerFilterEnabled = NbtCompat.getBoolean(nbt, "customPlayerFilterEnabled");
        if (nbt.contains("onlineOnly")) state.onlineOnly = NbtCompat.getBoolean(nbt, "onlineOnly");
        readPlayerFilters(nbt, "filteredPlayers", state.filteredPlayers);
        readPlayerFilters(nbt, "allowedPlayers", state.allowedPlayers);
        readPlayerFilters(nbt, "botPlayers", state.botPlayers);
        if (nbt.contains("botGroupMixed")) state.botGroupMixed = NbtCompat.getBoolean(nbt, "botGroupMixed");
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
        for (Tag element : NbtCompat.getList(nbt, "joinMenuDisabledPlayers", Tag.TAG_STRING)) {
            try { state.joinMenuDisabledPlayers.add(UUID.fromString(NbtCompat.asString(element))); }
            catch (IllegalArgumentException ignored) { }
        }
        for (Tag element : NbtCompat.getList(nbt, "playerLanguages", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag entry = (CompoundTag) element;
                String language = NbtCompat.getString(entry, "language");
                if (language.matches("[a-z0-9_-]{2,32}")) {
                    state.playerLanguages.put(NbtCompat.getUuid(entry, "uuid"), language);
                }
            } catch (RuntimeException ignored) { }
        }
        for (Tag element : NbtCompat.getList(nbt, "customMetricValues", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag entry = (CompoundTag) element;
                UUID uuid = NbtCompat.getUuid(entry, "uuid");
                CompoundTag values = NbtCompat.getCompound(entry, "values");
                EnumMap<RankBoardMod.Metric, Long> custom = new EnumMap<>(RankBoardMod.Metric.class);
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (metric.isCustomMetric() && values.contains(metric.command)) {
                        custom.put(metric, NbtCompat.getLong(values, metric.command));
                    }
                }
                if (!custom.isEmpty()) state.customMetricValues.put(uuid, custom);
            } catch (RuntimeException ignored) { }
        }
        for (Tag element : NbtCompat.getList(nbt, "metricOverrides", Tag.TAG_COMPOUND)) {
            try {
                CompoundTag entry = (CompoundTag) element;
                UUID uuid = NbtCompat.getUuid(entry, "uuid");
                CompoundTag values = NbtCompat.getCompound(entry, "values");
                EnumMap<RankBoardMod.Metric, Long> overrides = new EnumMap<>(RankBoardMod.Metric.class);
                for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                    if (values.contains(metric.command)) overrides.put(metric, Math.max(0L, NbtCompat.getLong(values, metric.command)));
                }
                if (!overrides.isEmpty()) state.metricOverrides.put(uuid, overrides);
            } catch (RuntimeException ignored) { }
        }
        String historySchema = NbtCompat.getString(nbt, "historySchema");
        boolean legacyHistory = historySchema.isEmpty();
        if (legacyHistory || "3".equals(historySchema) || "4".equals(historySchema)
                || Integer.toString(HISTORY_SCHEMA).equals(historySchema)) {
            for (Tag element : NbtCompat.getList(nbt, "periods", Tag.TAG_COMPOUND)) {
                PeriodData data = PeriodData.fromNbt((CompoundTag) element, legacyHistory);
                state.periods.put(data.period, data);
            }
            for (Tag element : NbtCompat.getList(nbt, "dailySnapshots", Tag.TAG_COMPOUND)) {
                CompoundTag snapshot = (CompoundTag) element;
                LocalDate date = LocalDate.parse(NbtCompat.getString(snapshot, "date"));
                state.dailySnapshots.put(date, readPlayers(snapshot));
                if (legacyHistory) state.partialSnapshotDates.add(date);
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
        nbt.putBoolean("whitelistModeConfigured", whitelistModeConfigured);
        nbt.putBoolean("botFilterEnabled", botFilterEnabled);
        nbt.putBoolean("customPlayerFilterEnabled", customPlayerFilterEnabled);
        nbt.putBoolean("onlineOnly", onlineOnly);
        nbt.put("filteredPlayers", writePlayerFilters(filteredPlayers));
        nbt.put("allowedPlayers", writePlayerFilters(allowedPlayers));
        nbt.put("botPlayers", writePlayerFilters(botPlayers));
        nbt.putBoolean("botGroupMixed", botGroupMixed);
        ListTag disabledMetrics = new ListTag();
        disabledDisplayMetrics.forEach(metric -> disabledMetrics.add(StringTag.valueOf(metric.name())));
        nbt.put("disabledDisplayMetrics", disabledMetrics);
        ListTag disabledColors = new ListTag();
        nameColorDisabledPlayers.forEach(uuid -> disabledColors.add(StringTag.valueOf(uuid.toString())));
        nbt.put("nameColorDisabledPlayers", disabledColors);
        ListTag disabledLookMenus = new ListTag();
        lookMenuDisabledPlayers.forEach(uuid -> disabledLookMenus.add(StringTag.valueOf(uuid.toString())));
        nbt.put("lookMenuDisabledPlayers", disabledLookMenus);
        ListTag disabledJoinMenus = new ListTag();
        joinMenuDisabledPlayers.forEach(uuid -> disabledJoinMenus.add(StringTag.valueOf(uuid.toString())));
        nbt.put("joinMenuDisabledPlayers", disabledJoinMenus);
        ListTag languages = new ListTag();
        playerLanguages.forEach((uuid, language) -> {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUuid(entry, "uuid", uuid);
            entry.putString("language", language);
            languages.add(entry);
        });
        nbt.put("playerLanguages", languages);
        ListTag customMetrics = new ListTag();
        customMetricValues.forEach((uuid, values) -> {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUuid(entry, "uuid", uuid);
            CompoundTag valueCompound = new CompoundTag();
            values.forEach((metric, value) -> {
                if (metric.isCustomMetric()) valueCompound.putLong(metric.command, value);
            });
            entry.put("values", valueCompound);
            customMetrics.add(entry);
        });
        nbt.put("customMetricValues", customMetrics);
        ListTag metricOverrideList = new ListTag();
        metricOverrides.forEach((uuid, values) -> {
            CompoundTag entry = new CompoundTag();
            NbtCompat.putUuid(entry, "uuid", uuid);
            CompoundTag valueCompound = new CompoundTag();
            values.forEach((metric, value) -> valueCompound.putLong(metric.command, value));
            entry.put("values", valueCompound);
            metricOverrideList.add(entry);
        });
        nbt.put("metricOverrides", metricOverrideList);
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
        long revision = StatReader.periodDataRevision();
        if (now.equals(lastPeriodReadDate) && revision == lastPeriodReadRevision) return;
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
                    if (playerValues == null) {
                        values.put(snapshot.uuid(), new EnumMap<>(snapshot.values()));
                        activatedMetric = true;
                        continue;
                    }
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
        lastPeriodReadDate = now;
        lastPeriodReadRevision = revision;
        if (changed) setDirty();
    }
    public void ensurePlayer(ServerPlayer player) {
        rollPeriods(PlayerCompat.server(player));
        if (periods.isEmpty() || periods.values().stream().allMatch(data -> {
            Map<RankBoardMod.Metric, Long> values = data.players.get(player.getUUID());
            return values != null && values.size() == RankBoardMod.Metric.values().length;
        })) return;
        StatSnapshot snapshot = StatSnapshot.fromPlayer(player);
        boolean changed = false;
        for (PeriodData data : periods.values()) if (data.players.putIfAbsent(snapshot.uuid(), snapshot.values()) == null) changed = true;
        if (changed) setDirty();
    }
    public boolean needsLanguageChoice(UUID uuid) { return !playerLanguages.containsKey(uuid); }
    public String language(UUID uuid) {
        return playerLanguages.getOrDefault(uuid, RankBoardConfig.get().defaultLanguage);
    }
    public void setLanguage(UUID uuid, String language) {
        if (!language.matches("[a-z0-9_-]{2,32}")) throw new IllegalArgumentException("unsupported language");
        if (!language.equals(playerLanguages.put(uuid, language))) setDirty();
    }
    /** Applies a server-wide language choice to all known and currently online players. */
    public int setLanguageForAll(MinecraftServer server, String language) {
        Set<UUID> players = new HashSet<>(playerLanguages.keySet());
        server.getPlayerList().getPlayers().forEach(player -> players.add(player.getUUID()));
        boolean changed = false;
        for (UUID uuid : players) {
            if (!language.equals(playerLanguages.put(uuid, language))) changed = true;
        }
        if (changed) setDirty();
        return players.size();
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
    /** Raw baseline captured for the given period, or 0 when the period never observed the player. */
    public long getBaseline(RankBoardMod.Period period, UUID uuid, RankBoardMod.Metric metric) {
        PeriodData data = periods.get(period);
        if (data == null) return 0L;
        Map<RankBoardMod.Metric, Long> values = data.players.get(uuid);
        return values == null ? 0L : values.getOrDefault(metric, 0L);
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
            setDirty();
        }
    }
    public boolean needsWhitelistModeSetup() { return !whitelistModeConfigured; }
    public void setWhitelistModeConfigured() {
        if (!whitelistModeConfigured) {
            whitelistModeConfigured = true;
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
    public boolean isManuallyFiltered(UUID uuid, String name) {
        return matchesFilter(filteredPlayers, uuid, name);
    }
    public boolean isManuallyAllowed(UUID uuid, String name) {
        return matchesFilter(allowedPlayers, uuid, name);
    }
    /** Adds a player to the manual hidden list. Returns false when already hidden. */
    public boolean addFilteredPlayer(String name, UUID uuid) {
        String key = normalizeFilterName(name);
        if (key.isEmpty()) throw new IllegalArgumentException("玩家名称不能为空");
        allowedPlayers.remove(key);
        if (filteredPlayers.containsKey(key)) return false;
        filteredPlayers.put(key, uuid);
        setDirty();
        return true;
    }
    /** Allows a player to bypass the automatic bot_/unknown_ prefix filters. */
    public boolean addAllowedPlayer(String name, UUID uuid) {
        String key = normalizeFilterName(name);
        if (key.isEmpty()) throw new IllegalArgumentException("玩家名称不能为空");
        filteredPlayers.remove(key);
        if (allowedPlayers.containsKey(key)) return false;
        allowedPlayers.put(key, uuid);
        setDirty();
        return true;
    }
    /** Removes a player from both manual lists. Accepts a name or a UUID string. */
    public boolean removePlayerFilter(String value) {
        if (value == null) return false;
        String key = normalizeFilterName(value);
        boolean changed = filteredPlayers.remove(key) != null;
        changed |= allowedPlayers.remove(key) != null;
        try {
            UUID uuid = UUID.fromString(value.trim());
            changed |= filteredPlayers.values().removeIf(uuid::equals);
            changed |= allowedPlayers.values().removeIf(uuid::equals);
        } catch (IllegalArgumentException ignored) { }
        if (changed) setDirty();
        return changed;
    }
    public List<Map.Entry<String, UUID>> filteredPlayerEntries() {
        return List.copyOf(filteredPlayers.entrySet());
    }
    public List<Map.Entry<String, UUID>> allowedPlayerEntries() {
        return List.copyOf(allowedPlayers.entrySet());
    }
    public int clearPlayerFilters() {
        int removed = filteredPlayers.size() + allowedPlayers.size();
        if (removed > 0) {
            filteredPlayers.clear();
            allowedPlayers.clear();
            setDirty();
        }
        return removed;
    }
    private static boolean matchesFilter(Map<String, UUID> entries, UUID uuid, String name) {
        if (entries.isEmpty()) return false;
        if (name != null && entries.containsKey(normalizeFilterName(name))) return true;
        return uuid != null && entries.containsValue(uuid);
    }
    private static String normalizeFilterName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
    /** True when the operator explicitly classified this player as a bot. */
    public boolean isManualBot(UUID uuid, String name) {
        return matchesFilter(botPlayers, uuid, name);
    }
    /** True for manually classified bots and for the automatic bot_ name prefix. */
    public boolean isBotPlayer(UUID uuid, String name) {
        return isManualBot(uuid, name) || (name != null && normalizeFilterName(name).startsWith("bot_"));
    }
    /** Adds a player to the manual bot group. Returns false when already classified. */
    public boolean addBotPlayer(String name, UUID uuid) {
        String key = normalizeFilterName(name);
        if (key.isEmpty()) throw new IllegalArgumentException("玩家名称不能为空");
        allowedPlayers.remove(key);
        filteredPlayers.remove(key);
        if (botPlayers.containsKey(key)) return false;
        botPlayers.put(key, uuid);
        setDirty();
        return true;
    }
    /** Removes a player from the manual bot group. Accepts a name or a UUID string. */
    public boolean removeBotPlayer(String value) {
        if (value == null) return false;
        String key = normalizeFilterName(value);
        boolean changed = botPlayers.remove(key) != null;
        try {
            UUID uuid = UUID.fromString(value.trim());
            changed |= botPlayers.values().removeIf(uuid::equals);
        } catch (IllegalArgumentException ignored) { }
        if (changed) setDirty();
        return changed;
    }
    public List<Map.Entry<String, UUID>> botPlayerEntries() {
        return List.copyOf(botPlayers.entrySet());
    }
    public Set<UUID> botPlayerUuids() {
        return Set.copyOf(new HashSet<>(botPlayers.values()));
    }
    public int clearBotPlayers() {
        int removed = botPlayers.size();
        if (removed > 0) {
            botPlayers.clear();
            setDirty();
        }
        return removed;
    }
    public boolean isBotGroupMixed() { return botGroupMixed; }
    public void setBotGroupMixed(boolean mixed) {
        if (botGroupMixed != mixed) {
            botGroupMixed = mixed;
            setDirty();
        }
    }
    public boolean isMetricDisplayEnabled(RankBoardMod.Metric metric) {
        if (metric == RankBoardMod.Metric.BEDROCK_BROKEN && !RankBoardConfig.get().bedrockBreakLeaderboardEnabled) return false;
        return !disabledDisplayMetrics.contains(metric);
    }
    public void setMetricDisplayEnabled(RankBoardMod.Metric metric, boolean enabled) {
        boolean changed = enabled ? disabledDisplayMetrics.remove(metric) : disabledDisplayMetrics.add(metric);
        if (changed) setDirty();
    }

    public long customMetric(UUID uuid, RankBoardMod.Metric metric) {
        return customMetricValues.getOrDefault(uuid, new EnumMap<>(RankBoardMod.Metric.class))
                .getOrDefault(metric, 0L);
    }

    public boolean hasCustomMetric(UUID uuid, RankBoardMod.Metric metric) {
        EnumMap<RankBoardMod.Metric, Long> values = customMetricValues.get(uuid);
        return values != null && values.containsKey(metric);
    }

    public void ensureCustomMetric(UUID uuid, RankBoardMod.Metric metric, long initialValue) {
        if (!metric.isCustomMetric() || hasCustomMetric(uuid, metric)) return;
        long value = metric == RankBoardMod.Metric.ELYTRA_DISTANCE && initialValue < 0
                ? Integer.MAX_VALUE : Math.max(0L, initialValue);
        customMetricValues.computeIfAbsent(uuid, ignored -> new EnumMap<>(RankBoardMod.Metric.class))
                .put(metric, value);
        setDirty();
    }

    public void setMetricOverride(UUID uuid, RankBoardMod.Metric metric, long value) {
        if (value < 0) throw new IllegalArgumentException("Metric override cannot be negative");
        EnumMap<RankBoardMod.Metric, Long> overrides = metricOverrides.computeIfAbsent(
                uuid, ignored -> new EnumMap<>(RankBoardMod.Metric.class));
        if (!java.util.Objects.equals(overrides.put(metric, value), value)) setDirty();
    }

    public boolean clearMetricOverride(UUID uuid, RankBoardMod.Metric metric) {
        EnumMap<RankBoardMod.Metric, Long> overrides = metricOverrides.get(uuid);
        if (overrides == null || overrides.remove(metric) == null) return false;
        if (overrides.isEmpty()) metricOverrides.remove(uuid);
        setDirty();
        return true;
    }

    public int clearMetricOverrides(UUID uuid) {
        EnumMap<RankBoardMod.Metric, Long> removed = metricOverrides.remove(uuid);
        if (removed == null || removed.isEmpty()) return 0;
        setDirty();
        return removed.size();
    }

    public void addCustomMetric(UUID uuid, RankBoardMod.Metric metric, long amount) {
        if (!metric.isCustomMetric() || amount <= 0) return;
        EnumMap<RankBoardMod.Metric, Long> values = customMetricValues.computeIfAbsent(
                uuid, ignored -> new EnumMap<>(RankBoardMod.Metric.class));
        long previous = values.getOrDefault(metric, 0L);
        long updated;
        try { updated = Math.addExact(previous, amount); }
        catch (ArithmeticException ignored) { updated = Long.MAX_VALUE; }
        if (updated != previous) {
            values.put(metric, updated);
            setDirty();
        }
    }

    public StatSnapshot applyCustomMetrics(StatSnapshot snapshot) {
        ensureCustomMetric(snapshot.uuid(), RankBoardMod.Metric.ELYTRA_DISTANCE,
                snapshot.value(RankBoardMod.Metric.ELYTRA_DISTANCE));
        EnumMap<RankBoardMod.Metric, Long> values = new EnumMap<>(snapshot.values());
        EnumMap<RankBoardMod.Metric, Long> custom = customMetricValues.get(snapshot.uuid());
        if (custom != null) custom.forEach((metric, value) -> values.put(metric, value));
        EnumMap<RankBoardMod.Metric, Long> overrides = metricOverrides.get(snapshot.uuid());
        if (overrides != null) overrides.forEach(values::put);
        return new StatSnapshot(snapshot.uuid(), snapshot.name(), values);
    }

    StatSnapshot applyStoredCustomMetrics(StatSnapshot snapshot) {
        EnumMap<RankBoardMod.Metric, Long> values = new EnumMap<>(snapshot.values());
        EnumMap<RankBoardMod.Metric, Long> custom = customMetricValues.get(snapshot.uuid());
        if (custom != null) custom.forEach((metric, value) -> {
            if (metric.isCustomMetric()) values.put(metric, value);
        });
        return new StatSnapshot(snapshot.uuid(), snapshot.name(), values);
    }

    public StatSnapshot applyCustomMetric(StatSnapshot snapshot, RankBoardMod.Metric metric) {
        if (metric == RankBoardMod.Metric.ELYTRA_DISTANCE) {
            ensureCustomMetric(snapshot.uuid(), metric, snapshot.value(metric));
        }
        if (!snapshot.values().containsKey(metric)) return snapshot;
        long value = snapshot.value(metric);
        EnumMap<RankBoardMod.Metric, Long> custom = customMetricValues.get(snapshot.uuid());
        if (custom != null && custom.containsKey(metric)) value = custom.get(metric);
        EnumMap<RankBoardMod.Metric, Long> overrides = metricOverrides.get(snapshot.uuid());
        if (overrides != null && overrides.containsKey(metric)) value = overrides.get(metric);
        EnumMap<RankBoardMod.Metric, Long> values = new EnumMap<>(snapshot.values());
        values.put(metric, value);
        return new StatSnapshot(snapshot.uuid(), snapshot.name(), values);
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

    public boolean isJoinMenuEnabled(UUID uuid) { return !joinMenuDisabledPlayers.contains(uuid); }
    public void setJoinMenuEnabled(UUID uuid, boolean enabled) {
        boolean changed = enabled ? joinMenuDisabledPlayers.remove(uuid) : joinMenuDisabledPlayers.add(uuid);
        if (changed) setDirty();
    }

    public BoardPreference boardPreference(UUID uuid) { return boardPreferences.get(uuid); }

    public void setBoardPreference(UUID uuid, RankBoardMod.Period period, RankBoardMod.Metric metric,
                                   boolean enabled, boolean carousel) {
        BoardPreference replacement = new BoardPreference(period, metric, enabled, carousel, false);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) setDirty();
    }

    public void setOverviewPreference(UUID uuid, RankBoardMod.Period period, boolean enabled) {
        BoardPreference replacement = new BoardPreference(
                period, RankBoardMod.Metric.PLAY_TIME, enabled, false, enabled);
        if (!replacement.equals(boardPreferences.put(uuid, replacement))) setDirty();
    }

    public void disableBoard(UUID uuid) {
        BoardPreference current = boardPreferences.get(uuid);
        if (current != null && current.enabled()) {
            boardPreferences.put(uuid, new BoardPreference(
                    current.period(), current.metric(), false, current.carousel(), current.overview()));
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

    /**
     * Computes a range delta from the best available snapshots. Missing or partial
     * midnight boundaries are reported as warnings and replaced by the nearest
     * usable snapshot instead of failing the whole query.
     */
    public RangeData range(MinecraftServer server, LocalDate from, LocalDate to, RankBoardMod.Metric metric) {
        if (!StatReader.isReady()) throw new IllegalStateException("历史统计仍在权威扫描（" + StatReader.progress() + "）");
        if (to.isBefore(from)) throw new IllegalArgumentException("结束日期不能早于开始日期");
        LocalDate today = LocalDate.now();
        if (to.isAfter(today)) throw new IllegalArgumentException("结束日期不能晚于今天：" + today);

        // Make sure today's snapshot exists before evaluating ranges.
        rollPeriods(server);

        List<String> warnings = new java.util.ArrayList<>();
        Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> startEntry =
                firstSnapshotWithMetric(from, to, metric);
        if (startEntry == null && to.isBefore(today)) {
            startEntry = firstSnapshotWithMetric(from, today, metric);
        }
        if (startEntry == null) {
            throw new IllegalArgumentException("所选范围内还没有可用的 " + metric.label()
                    + " 快照；最早快照为 " + earliestSnapshotDate(metric));
        }

        LocalDate actualStart = startEntry.getKey();
        Map<UUID, Map<RankBoardMod.Metric, Long>> start = startEntry.getValue();
        if (!actualStart.equals(from)) {
            warnings.add("开始日期缺少完整零点快照，实际从 " + actualStart + " 的首个可用快照开始");
        } else if (isPartialSnapshot(actualStart)) {
            warnings.add("开始日期不是零点建立的完整快照，按当日首次可信快照计算");
        }

        Map<UUID, Long> endValues = new HashMap<>();
        LocalDate actualEnd = to;
        if (!to.isBefore(today)) {
            StatReader.readAll(server, metric).forEach(snapshot -> endValues.put(snapshot.uuid(), snapshot.value(metric)));
        } else {
            LocalDate boundary = to.plusDays(1);
            HistorySnapshotStore.Snapshot endSnapshot = historySnapshot(boundary);
            Map<UUID, Map<RankBoardMod.Metric, Long>> end =
                    endSnapshot == null ? null : endSnapshot.players();
            if (end == null || !snapshotHasMetric(end, metric)) {
                Map.Entry<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> fallback =
                        firstSnapshotWithMetric(boundary, today, metric);
                if (fallback == null) {
                    throw new IllegalArgumentException("结束日期缺少可用边界快照："
                            + to + "；最早快照为 " + earliestSnapshotDate(metric));
                }
                boundary = fallback.getKey();
                end = fallback.getValue();
                actualEnd = boundary.minusDays(1);
                warnings.add("结束日期缺少次日零点快照，实际统计到 " + actualEnd);
            } else if (endSnapshot.partial()) {
                warnings.add("结束边界不是完整零点快照，结果可能不完整");
            }
            Map<UUID, Map<RankBoardMod.Metric, Long>> endValuesMap = end;
            endValuesMap.forEach((uuid, values) -> {
                Long value = values.get(metric);
                if (value != null) endValues.put(uuid, value);
            });
        }

        Map<UUID, Long> result = new HashMap<>();
        int missingStart = 0;
        int missingEnd = 0;
        int reset = 0;
        for (UUID uuid : start.keySet()) if (!endValues.containsKey(uuid)) missingEnd++;
        for (Map.Entry<UUID, Long> entry : endValues.entrySet()) {
            Map<RankBoardMod.Metric, Long> baseValues = start.get(entry.getKey());
            if (baseValues == null || !baseValues.containsKey(metric)) { missingStart++; continue; }
            long base = baseValues.get(metric);
            if (entry.getValue() < base) { reset++; continue; }
            result.put(entry.getKey(), entry.getValue() - base);
        }
        if (missingStart > 0) warnings.add(missingStart + " 名玩家缺少开始边界，已排除");
        if (missingEnd > 0) warnings.add(missingEnd + " 名玩家缺少结束边界，已排除");
        if (reset > 0) warnings.add(reset + " 名玩家累计统计发生回退，已排除");
        return new RangeData(actualStart, actualEnd, result, warnings.isEmpty(), List.copyOf(warnings));
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
        CompoundTag toNbt() {
            CompoundTag nbt = new CompoundTag(); nbt.putString("period", period.name()); nbt.putString("key", key);
            nbt.putBoolean("complete", complete); nbt.put("players", writePlayers(players));
            ListTag partial = new ListTag();
            partialMetrics.forEach(metric -> partial.add(StringTag.valueOf(metric.name())));
            nbt.put("partialMetrics", partial);
            return nbt;
        }
        static PeriodData fromNbt(CompoundTag nbt, boolean legacyHistory) {
            PeriodData data = new PeriodData(RankBoardMod.Period.valueOf(NbtCompat.getString(nbt, "period")),
                    NbtCompat.getString(nbt, "key"), !legacyHistory && NbtCompat.getBoolean(nbt, "complete"));
            data.players.putAll(readPlayers(nbt));
            for (Tag element : NbtCompat.getList(nbt, "partialMetrics", Tag.TAG_STRING)) {
                try { data.partialMetrics.add(RankBoardMod.Metric.valueOf(NbtCompat.asString(element))); }
                catch (IllegalArgumentException ignored) { }
            }
            return data;
        }
    }

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

    private static boolean initializeMissingSnapshotMetrics(
            Map<UUID, Map<RankBoardMod.Metric, Long>> values, List<StatSnapshot> snapshots) {
        boolean changed = false;
        for (StatSnapshot snapshot : snapshots) {
            Map<RankBoardMod.Metric, Long> playerValues = values.get(snapshot.uuid());
            if (playerValues == null) continue;
            for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
                if (!playerValues.containsKey(metric)) {
                    playerValues.put(metric, snapshot.value(metric));
                    changed = true;
                }
            }
        }
        return changed;
    }
}

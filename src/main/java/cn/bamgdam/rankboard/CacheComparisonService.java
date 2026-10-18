package cn.bamgdam.rankboard;

import net.minecraft.util.Formatting;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.text.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;

/** Read-only preview and explicitly confirmed application of refreshed stat snapshots. */
final class CacheComparisonService {
    private static final long SESSION_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int PAGE_SIZE = 10;
    private static final Set<MinecraftServer> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final Map<MinecraftServer, Comparison> COMPARISONS = new ConcurrentHashMap<>();

    private CacheComparisonService() { }

    static int compare(ServerCommandSource source, String requestedPlayer) {
        MinecraftServer server = source.getServer();
        if (!StatReader.isReady() || StatReader.isChecking()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.cache_busy")));
            return 0;
        }
        if (!ACTIVE.add(server)) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.already_running")));
            return 0;
        }

        COMPARISONS.remove(server);
        Map<UUID, StatSnapshot> baselineCache = StatReader.cacheSnapshot();
        Map<UUID, StatSnapshot> current = new HashMap<>(baselineCache);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            current.put(player.getUuid(), StatReader.fromPlayer(player));
        }
        LeaderboardState state = LeaderboardState.get(server);
        Map<UUID, StatSnapshot> currentView = new HashMap<>();
        current.forEach((uuid, snapshot) -> currentView.put(uuid, state.applyStoredCustomMetrics(snapshot)));

        Run run = new Run(server, source, UUID.randomUUID().toString(), requestedPlayer,
                baselineCache, Map.copyOf(currentView));
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source,
                requestedPlayer == null ? "cache_compare.started_all" : "cache_compare.started_player", requestedPlayer == null ? "" : requestedPlayer))
                .formatted(Formatting.GRAY), false);
        try {
            StatReader.submitComparison(() -> scan(run));
            return 1;
        } catch (RejectedExecutionException exception) {
            ACTIVE.remove(server);
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.queue_failed")));
            return 0;
        }
    }

    private static void scan(Run run) {
        try {
            ScanResult scan = readFiles(run);
            run.server.execute(() -> finish(run, scan));
        } catch (RuntimeException exception) {
            RankBoardMod.LOGGER.error("Could not compare RankBoard history statistics", exception);
            run.server.execute(() -> {
                ACTIVE.remove(run.server);
                run.source.sendError(Text.literal(RankBoardMod.localized(run.source,
                        "cache_compare.scan_failed")));
            });
        }
    }

    private static ScanResult readFiles(Run run) {
        MinecraftServer server = run.server;
        Map<UUID, String> names = StatReader.knownNamesForComparison(server);
        Map<UUID, StatSnapshot> known = new HashMap<>(run.baselineCache);
        known.putAll(run.currentView);
        known.forEach((uuid, snapshot) -> names.put(uuid, snapshot.name()));

        UUID requestedUuid = null;
        if (run.requestedPlayer != null) {
            requestedUuid = resolvePlayer(run.requestedPlayer, names);
            if (requestedUuid == null) return ScanResult.notFound();
        }

        Path directory = server.getSavePath(WorldSavePath.STATS);
        List<Path> files = new ArrayList<>();
        int failures = 0;
        Set<UUID> failedPlayers = new HashSet<>();
        if (requestedUuid != null) {
            Path path = directory.resolve(requestedUuid + ".json");
            if (Files.isRegularFile(path)) files.add(path);
        } else {
            try (Stream<Path> paths = Files.isDirectory(directory) ? Files.list(directory) : Stream.empty()) {
                paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> StatReader.uuidFromStatPathOrNull(path) != null)
                        .forEach(files::add);
            } catch (IOException exception) {
                failures++;
            }
        }
        files.sort(Comparator.comparing(Path::toString));

        Map<UUID, StatSnapshot> latest = new HashMap<>();
        Map<UUID, StatFileVersion> versions = new HashMap<>();
        for (Path path : files) {
            UUID uuid = StatReader.uuidFromStatPathOrNull(path);
            if (uuid == null) continue;
            StatFileVersion before = StatReader.statFileVersion(path);
            if (before.modifiedMillis() < 0) {
                failures++;
                failedPlayers.add(uuid);
                continue;
            }
            var parsed = StatReader.readComparisonSnapshot(path, names);
            StatFileVersion after = StatReader.statFileVersion(path);
            if (parsed.isEmpty() || !before.equals(after)) {
                failures++;
                failedPlayers.add(uuid);
                continue;
            }
            latest.put(uuid, parsed.get());
            versions.put(uuid, after);
        }

        return new ScanResult(requestedUuid, latest, versions, failures, failedPlayers, false);
    }

    private static UUID resolvePlayer(String requested, Map<UUID, String> names) {
        try { return UUID.fromString(requested); }
        catch (IllegalArgumentException ignored) { }
        return names.entrySet().stream()
                .filter(entry -> entry.getValue().equalsIgnoreCase(requested))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private static void finish(Run run, ScanResult scan) {
        MinecraftServer server = run.server;
        if (!ACTIVE.remove(server)) return;
        if (scan.targetNotFound) {
            run.source.sendError(Text.literal(RankBoardMod.localized(run.source, "cache_compare.player_not_found",
                    run.requestedPlayer)));
            return;
        }

        Map<UUID, StatSnapshot> latest = new HashMap<>(scan.latest);
        Map<UUID, StatFileVersion> versions = new HashMap<>(scan.versions);
        Set<UUID> failedPlayers = new HashSet<>(scan.failedPlayers);
        int failures = scan.failures;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            if (scan.requestedUuid != null && !scan.requestedUuid.equals(uuid)) continue;
            if (failedPlayers.remove(uuid)) failures--;
            latest.put(uuid, StatReader.fromPlayer(player));
            versions.putIfAbsent(uuid, StatReader.statFileVersion(
                    server.getSavePath(WorldSavePath.STATS).resolve(uuid + ".json")));
        }

        Map<UUID, StatSnapshot> latestView = new HashMap<>();
        LeaderboardState state = LeaderboardState.get(server);
        latest.forEach((uuid, snapshot) -> latestView.put(uuid, state.applyStoredCustomMetrics(snapshot)));

        Map<UUID, StatSnapshot> baselineCache = run.baselineCache;
        Map<UUID, StatSnapshot> currentView = run.currentView;
        if (scan.requestedUuid != null) {
            UUID target = scan.requestedUuid;
            baselineCache = scoped(baselineCache, target);
            currentView = scoped(currentView, target);
        }

        Set<UUID> missing = new HashSet<>();
        for (UUID uuid : currentView.keySet()) {
            if (!latestView.containsKey(uuid) && !failedPlayers.contains(uuid)) missing.add(uuid);
        }
        if (scan.requestedUuid != null && !latestView.containsKey(scan.requestedUuid)
                && !failedPlayers.contains(scan.requestedUuid)) missing.add(scan.requestedUuid);
        Comparison comparison = new Comparison(run.id, run.requestedPlayer, System.currentTimeMillis(),
                baselineCache, currentView, Map.copyOf(latest), Map.copyOf(latestView),
                Map.copyOf(versions), Set.copyOf(failedPlayers), Set.copyOf(missing), failures,
                scan.targetNotFound, 0L, Map.of());
        COMPARISONS.put(server, comparison);
        showSummary(run.source, comparison);
    }

    private static void showSummary(ServerCommandSource source, Comparison comparison) {
        if (comparison.targetNotFound) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.player_not_found",
                    comparison.requestedPlayer)));
            return;
        }
        int changed = changedPlayers(comparison).size();
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.summary",
                        comparison.latest.size(), changed, comparison.missing.size(), comparison.failures))
                .formatted(Formatting.AQUA), false);
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.scope"))
                .formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal(" ")
                .append(action(source, "cache_compare.view_differences", Formatting.YELLOW,
                        command(comparison.id, "list", "1"), "cache_compare.view_differences_hint"))
                .append(Text.literal("  "))
                .append(action(source, "cache_compare.use_latest", Formatting.GREEN,
                        command(comparison.id, "apply-all"), "cache_compare.use_latest_hint")), false);
        if (comparison.failures > 0 || comparison.missing.size() > 0) {
            source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.incomplete"))
                    .formatted(Formatting.RED), false);
        }
    }

    static int list(ServerCommandSource source, String id, int page) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        List<UUID> players = new ArrayList<>(changedPlayers(comparison));
        Set<UUID> listed = new HashSet<>(players);
        comparison.missing.stream().filter(listed::add)
                .sorted(Comparator.comparing(uuid -> name(comparison, uuid), String.CASE_INSENSITIVE_ORDER))
                .forEach(players::add);
        comparison.failedPlayers.stream().filter(listed::add)
                .sorted(Comparator.comparing(uuid -> name(comparison, uuid), String.CASE_INSENSITIVE_ORDER))
                .forEach(players::add);
        int pageCount = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int safePage = Math.max(1, Math.min(page, pageCount));
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.list_title",
                players.size(), safePage, pageCount)).formatted(Formatting.AQUA), false);
        if (players.isEmpty()) {
            source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.no_differences"))
                    .formatted(Formatting.GRAY), false);
        } else {
            int start = (safePage - 1) * PAGE_SIZE;
            for (UUID uuid : players.subList(start, Math.min(start + PAGE_SIZE, players.size()))) {
                boolean failed = comparison.failedPlayers.contains(uuid);
                boolean missing = comparison.missing.contains(uuid);
                String statusKey = failed ? "cache_compare.read_error"
                        : missing ? "cache_compare.source_missing" : null;
                String label = statusKey == null ? name(comparison, uuid)
                        : name(comparison, uuid) + " · " + RankBoardMod.localized(source, statusKey);
                String hover = failed ? RankBoardMod.localized(source, "cache_compare.read_error_hint")
                        : missing ? RankBoardMod.localized(source, "cache_compare.source_missing_hint")
                        : RankBoardMod.localized(source, "cache_compare.open_player_hint");
                source.sendFeedback(() -> Text.literal("• ").append(interactive(label,
                        failed || missing ? Formatting.RED : Formatting.YELLOW,
                        command(comparison.id, "player", uuid.toString()), hover)), false);
            }
        }
        if (safePage > 1 || safePage < pageCount) {
            var navigation = Text.literal(" ");
            if (safePage > 1) navigation = navigation.append(action(source, "cache_compare.previous_page",
                    Formatting.AQUA, command(comparison.id, "list", Integer.toString(safePage - 1)),
                    "cache_compare.page_hint"));
            if (safePage < pageCount) navigation = navigation.append(Text.literal("  "))
                    .append(action(source, "cache_compare.next_page", Formatting.AQUA,
                            command(comparison.id, "list", Integer.toString(safePage + 1)), "cache_compare.page_hint"));
            Text navigationMessage = navigation;
            source.sendFeedback(() -> navigationMessage, false);
        }
        return 1;
    }

    static int player(ServerCommandSource source, String id, String uuidText) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        UUID uuid;
        try { uuid = UUID.fromString(uuidText); }
        catch (IllegalArgumentException exception) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.invalid_player")));
            return 0;
        }
        String playerName = name(comparison, uuid);
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.player_title", playerName))
                .formatted(Formatting.AQUA), false);
        if (comparison.missing.contains(uuid) || comparison.failedPlayers.contains(uuid)) {
            source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.no_fresh_data"))
                    .formatted(Formatting.RED), false);
            return 1;
        }
        StatSnapshot old = comparison.currentView.get(uuid);
        StatSnapshot fresh = comparison.latestView.get(uuid);
        if (fresh == null) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.no_fresh_data")));
            return 0;
        }
        if (old == null) {
            source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.no_current_data"))
                    .formatted(Formatting.GRAY), false);
        }
        int differences = 0;
        for (RankBoardMod.Metric metric : RankBoardMod.Metric.values()) {
            if (metric.isCustomMetric()) continue;
            long current = old == null ? 0L : old.value(metric);
            long latest = fresh.value(metric);
            if (current == latest) continue;
            differences++;
            String line = RankBoardMod.localizedMetric(source, metric) + "："
                    + format(metric, current) + " → " + format(metric, latest);
            source.sendFeedback(() -> Text.literal("  " + line).formatted(Formatting.GRAY), false);
        }
        if (differences == 0) {
            source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.player_matches"))
                    .formatted(Formatting.GRAY), false);
        }
        source.sendFeedback(() -> Text.literal(" ").append(action(source, "cache_compare.sync_player",
                Formatting.GREEN, command(comparison.id, "apply-player", uuid.toString()),
                "cache_compare.sync_player_hint")), false);
        return 1;
    }

    static int prepareApplyAll(ServerCommandSource source, String id) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        if (comparison.failures > 0 || !comparison.missing.isEmpty()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.incomplete")));
            return 0;
        }
        if (comparison.latest.isEmpty()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.nothing_to_apply")));
            return 0;
        }
        Comparison confirmed = comparison.withAllConfirmation(System.currentTimeMillis() + 30_000L);
        COMPARISONS.put(source.getServer(), confirmed);
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.confirm_all_prompt",
                        confirmed.latest.size()))
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" "))
                .append(action(source, "cache_compare.confirm", Formatting.GREEN,
                        command(confirmed.id, "confirm-all"), "cache_compare.confirm_hint"))
                .append(Text.literal("  "))
                .append(action(source, "cache_compare.cancel", Formatting.GRAY,
                        command(confirmed.id, "cancel"), "cache_compare.cancel_hint")), false);
        return 1;
    }

    static int prepareApplyPlayer(ServerCommandSource source, String id, String uuidText) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        UUID uuid = parseUuid(source, uuidText);
        if (uuid == null) return 0;
        if (comparison.failedPlayers.contains(uuid) || comparison.missing.contains(uuid)
                || !comparison.latest.containsKey(uuid)) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.no_fresh_data")));
            return 0;
        }
        Comparison confirmed = comparison.withPlayerConfirmation(uuid, System.currentTimeMillis() + 30_000L);
        COMPARISONS.put(source.getServer(), confirmed);
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.confirm_player_prompt",
                        name(confirmed, uuid)))
                .formatted(Formatting.YELLOW)
                .append(Text.literal(" "))
                .append(action(source, "cache_compare.confirm", Formatting.GREEN,
                        command(confirmed.id, "confirm-player", uuid.toString()), "cache_compare.confirm_hint"))
                .append(Text.literal("  "))
                .append(action(source, "cache_compare.cancel", Formatting.GRAY,
                        command(confirmed.id, "cancel"), "cache_compare.cancel_hint")), false);
        return 1;
    }

    static int confirmAll(ServerCommandSource source, String id) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        if (comparison.allConfirmUntil < System.currentTimeMillis()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.confirm_required")));
            return 0;
        }
        if (comparison.failures > 0 || !comparison.missing.isEmpty()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.incomplete")));
            return 0;
        }
        boolean applied = StatReader.applyComparison(source.getServer(), comparison.baselineCache,
                comparison.latest, comparison.versions);
        if (!applied) return stale(source, comparison);
        COMPARISONS.remove(source.getServer(), comparison);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.applied_all",
                comparison.latest.size())).formatted(Formatting.GREEN), true);
        return 1;
    }

    static int confirmPlayer(ServerCommandSource source, String id, String uuidText) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        UUID uuid = parseUuid(source, uuidText);
        if (uuid == null) return 0;
        if (comparison.playerConfirmUntil.getOrDefault(uuid, 0L) < System.currentTimeMillis()) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.confirm_required")));
            return 0;
        }
        StatSnapshot latest = comparison.latest.get(uuid);
        if (latest == null || comparison.failedPlayers.contains(uuid)) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.no_fresh_data")));
            return 0;
        }
        Map<UUID, StatSnapshot> one = Map.of(uuid, latest);
        boolean applied = StatReader.applyComparison(source.getServer(), comparison.baselineCache,
                one, comparison.versions);
        if (!applied) return stale(source, comparison);
        COMPARISONS.remove(source.getServer(), comparison);
        BoardService.refreshAll(source.getServer());
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.applied_player",
                name(comparison, uuid))).formatted(Formatting.GREEN), true);
        return 1;
    }

    static int cancel(ServerCommandSource source, String id) {
        Comparison comparison = get(source, id);
        if (comparison == null) return 0;
        COMPARISONS.remove(source.getServer(), comparison);
        source.sendFeedback(() -> Text.literal(RankBoardMod.localized(source, "cache_compare.cancelled"))
                .formatted(Formatting.GRAY), false);
        return 1;
    }

    static void clear(MinecraftServer server) {
        ACTIVE.remove(server);
        COMPARISONS.remove(server);
    }

    private static int stale(ServerCommandSource source, Comparison comparison) {
        COMPARISONS.remove(source.getServer(), comparison);
        source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.stale")));
        return 0;
    }

    private static Comparison get(ServerCommandSource source, String id) {
        Comparison comparison = COMPARISONS.get(source.getServer());
        if (comparison == null || !comparison.id.equals(id)) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.expired")));
            return null;
        }
        if (System.currentTimeMillis() - comparison.createdAt > SESSION_TTL_MILLIS) {
            COMPARISONS.remove(source.getServer(), comparison);
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.expired")));
            return null;
        }
        return comparison;
    }

    private static UUID parseUuid(ServerCommandSource source, String text) {
        try { return UUID.fromString(text); }
        catch (IllegalArgumentException exception) {
            source.sendError(Text.literal(RankBoardMod.localized(source, "cache_compare.invalid_player")));
            return null;
        }
    }

    private static List<UUID> changedPlayers(Comparison comparison) {
        return comparison.latestView.entrySet().stream()
                .filter(entry -> {
                    StatSnapshot old = comparison.currentView.get(entry.getKey());
                    return java.util.Arrays.stream(RankBoardMod.Metric.values())
                            .filter(metric -> !metric.isCustomMetric())
                            .anyMatch(metric -> (old == null ? 0L : old.value(metric))
                                    != entry.getValue().value(metric));
                })
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(uuid -> name(comparison, uuid), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String name(Comparison comparison, UUID uuid) {
        StatSnapshot latest = comparison.latestView.get(uuid);
        if (latest != null) return latest.name();
        StatSnapshot current = comparison.currentView.get(uuid);
        if (current != null) return current.name();
        if (comparison.requestedPlayer != null) {
            try { UUID.fromString(comparison.requestedPlayer); }
            catch (IllegalArgumentException ignored) { return comparison.requestedPlayer; }
        }
        return uuid.toString();
    }

    private static Map<UUID, StatSnapshot> scoped(Map<UUID, StatSnapshot> snapshots, UUID uuid) {
        StatSnapshot snapshot = snapshots.get(uuid);
        return snapshot == null ? Map.of() : Map.of(uuid, snapshot);
    }

    private static String format(RankBoardMod.Metric metric, long value) {
        if (metric == RankBoardMod.Metric.PLAY_TIME || metric == RankBoardMod.Metric.DAMAGE_TAKEN
                || metric == RankBoardMod.Metric.DAMAGE_DEALT) {
            return RankBoardMod.format(metric, value);
        }
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(value);
    }

    private static Text action(ServerCommandSource source, String key, Formatting color,
                                    String command, String hintKey) {
        return Text.literal("[" + RankBoardMod.localized(source, key) + "]")
                .setStyle(TextCompat.interactive(Style.EMPTY.withColor(color), command,
                        Text.literal(RankBoardMod.localized(source, hintKey))));
    }

    private static Text interactive(String label, Formatting color, String command, String hover) {
        return Text.literal(label).setStyle(TextCompat.interactive(Style.EMPTY.withColor(color), command,
                Text.literal(hover)));
    }

    private static String command(String id, String action, String... arguments) {
        StringBuilder result = new StringBuilder("/leaderboard cache compare ").append(action).append(' ').append(id);
        for (String argument : arguments) result.append(' ').append(argument);
        return result.toString();
    }

    private record Run(MinecraftServer server, ServerCommandSource source, String id, String requestedPlayer,
                       Map<UUID, StatSnapshot> baselineCache, Map<UUID, StatSnapshot> currentView) { }

    private record ScanResult(UUID requestedUuid, Map<UUID, StatSnapshot> latest,
                              Map<UUID, StatFileVersion> versions, int failures, Set<UUID> failedPlayers,
                              boolean targetNotFound) {
        private static ScanResult notFound() {
            return new ScanResult(null, Map.of(), Map.of(), 0, Set.of(), true);
        }
    }

    private record Comparison(String id, String requestedPlayer, long createdAt,
                              Map<UUID, StatSnapshot> baselineCache,
                              Map<UUID, StatSnapshot> currentView,
                              Map<UUID, StatSnapshot> latest,
                              Map<UUID, StatSnapshot> latestView,
                              Map<UUID, StatFileVersion> versions,
                              Set<UUID> failedPlayers, Set<UUID> missing,
                              int failures, boolean targetNotFound,
                              long allConfirmUntil, Map<UUID, Long> playerConfirmUntil) {
        private Comparison withAllConfirmation(long until) {
            return new Comparison(id, requestedPlayer, createdAt, baselineCache, currentView, latest, latestView,
                    versions, failedPlayers, missing, failures, targetNotFound, until, playerConfirmUntil);
        }

        private Comparison withPlayerConfirmation(UUID uuid, long until) {
            Map<UUID, Long> confirmations = new HashMap<>(playerConfirmUntil);
            confirmations.put(uuid, until);
            return new Comparison(id, requestedPlayer, createdAt, baselineCache, currentView, latest, latestView,
                    versions, failedPlayers, missing, failures, targetNotFound, allConfirmUntil,
                    Map.copyOf(confirmations));
        }
    }
}

package cn.bamgdam.rankboard;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public final class HistorySnapshotStoreTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rankboard-history-test-");
        try {
            UUID player = UUID.fromString("12345678-1234-5678-1234-567812345678");
            HistorySnapshotStore store = new HistorySnapshotStore(root);
            store.put(LocalDate.of(2026, 7, 31), players(player, RankBoardMod.Metric.PLAY_TIME, 100), false);
            store.put(LocalDate.of(2026, 8, 1), players(player, RankBoardMod.Metric.JUMPS, 20), true);
            store.put(LocalDate.of(2026, 8, 2), players(player, RankBoardMod.Metric.JUMPS, 30), false);

            check(Files.isRegularFile(root.resolve("2026-07.dat")), "July shard missing");
            check(Files.isRegularFile(root.resolve("2026-08.dat")), "August shard missing");
            check(Files.list(root).filter(path -> path.toString().endsWith(".dat")).count() == 2,
                    "Expected exactly two month shards");

            HistorySnapshotStore reloaded = new HistorySnapshotStore(root);
            check(reloaded.get(LocalDate.of(2026, 7, 31)).orElseThrow().players()
                    .get(player).get(RankBoardMod.Metric.PLAY_TIME) == 100L, "Round-trip value mismatch");
            check(reloaded.get(LocalDate.of(2026, 8, 1)).orElseThrow().partial(), "Partial flag lost");
            check(reloaded.firstWithMetric(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31),
                    RankBoardMod.Metric.JUMPS).getKey().equals(LocalDate.of(2026, 8, 1)),
                    "Cross-month metric search failed");
            check(reloaded.earliest(RankBoardMod.Metric.PLAY_TIME).getKey().equals(LocalDate.of(2026, 7, 31)),
                    "Earliest snapshot lookup failed");

            Map<LocalDate, Map<UUID, Map<RankBoardMod.Metric, Long>>> legacy = new TreeMap<>();
            legacy.put(LocalDate.of(2026, 9, 1), players(player, RankBoardMod.Metric.PLAY_TIME, 200));
            legacy.put(LocalDate.of(2026, 9, 2), players(player, RankBoardMod.Metric.PLAY_TIME, 220));
            reloaded.importLegacy(legacy, Set.of(LocalDate.of(2026, 9, 2)));
            check(Files.isRegularFile(root.resolve("2026-09.dat")), "Legacy migration shard missing");
            check(reloaded.get(LocalDate.of(2026, 9, 2)).orElseThrow().partial(),
                    "Legacy partial flag lost");

            Files.write(root.resolve("2026-10.dat"), new byte[] {1, 2, 3});
            boolean corruptRejected = false;
            try { new HistorySnapshotStore(root).get(LocalDate.of(2026, 10, 1)); }
            catch (IOException expected) { corruptRejected = true; }
            check(corruptRejected, "Corrupt shard was not rejected");
            System.out.println("HistorySnapshotStoreTest passed");
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            }
        }
    }

    private static Map<UUID, Map<RankBoardMod.Metric, Long>> players(
            UUID uuid, RankBoardMod.Metric metric, long value) {
        Map<RankBoardMod.Metric, Long> values = new EnumMap<>(RankBoardMod.Metric.class);
        values.put(metric, value);
        return Map.of(uuid, values);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
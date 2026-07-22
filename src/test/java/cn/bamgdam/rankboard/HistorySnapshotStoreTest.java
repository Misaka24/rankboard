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
            check("123".equals(RankBoardMod.format(RankBoardMod.Metric.DAMAGE_TAKEN, 123L)),
                    "Damage taken was scaled instead of keeping the vanilla value");
            check("456".equals(RankBoardMod.format(RankBoardMod.Metric.DAMAGE_DEALT, 456L)),
                    "Damage dealt was scaled instead of keeping the vanilla value");
            checkRanges();
            checkNewMetrics();
            checkMenuCoverage();
            checkCommandTree();
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

            net.minecraft.nbt.NbtCompound upstreamNbt = new net.minecraft.nbt.NbtCompound();
            upstreamNbt.putString("historySchema", "4");
            net.minecraft.nbt.NbtCompound upstreamDay = new net.minecraft.nbt.NbtCompound();
            upstreamDay.putString("date", "2026-10-01");
            net.minecraft.nbt.NbtCompound upstreamPlayer = new net.minecraft.nbt.NbtCompound();
            NbtCompat.putUuid(upstreamPlayer, "uuid", player);
            upstreamPlayer.putLong("playtime", 300L);
            net.minecraft.nbt.NbtList upstreamPlayers = new net.minecraft.nbt.NbtList();
            upstreamPlayers.add(upstreamPlayer);
            upstreamDay.put("players", upstreamPlayers);
            net.minecraft.nbt.NbtList upstreamDays = new net.minecraft.nbt.NbtList();
            upstreamDays.add(upstreamDay);
            upstreamNbt.put("dailySnapshots", upstreamDays);
            net.minecraft.nbt.NbtList partialDates = new net.minecraft.nbt.NbtList();
            partialDates.add(net.minecraft.nbt.NbtString.of("2026-10-01"));
            upstreamNbt.put("partialSnapshotDates", partialDates);
            LeaderboardState migratedState = LeaderboardState.fromNbt(upstreamNbt, null);
            var attach = LeaderboardState.class.getDeclaredMethod("attachHistoryStore", Path.class);
            attach.setAccessible(true);
            attach.invoke(migratedState, root);
            HistorySnapshotStore migratedStore = new HistorySnapshotStore(root);
            check(migratedStore.get(LocalDate.of(2026, 10, 1)).orElseThrow().partial(),
                    "Upstream schema 4 partial flag was not migrated");
            check(migratedStore.get(LocalDate.of(2026, 10, 1)).orElseThrow().players()
                    .get(player).get(RankBoardMod.Metric.PLAY_TIME) == 300L,
                    "Upstream schema 4 value was not migrated");
            net.minecraft.nbt.NbtCompound migratedNbt = migratedState.writeNbt(
                    new net.minecraft.nbt.NbtCompound(), null);
            check("5".equals(NbtCompat.getString(migratedNbt, "historySchema")),
                    "Migrated state did not advance to schema 5");
            check(NbtCompat.getList(migratedNbt, "dailySnapshots",
                    net.minecraft.nbt.NbtElement.COMPOUND_TYPE).isEmpty(),
                    "Migrated embedded snapshots were not cleared");
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

    private static void checkRanges() {
        LocalDate today = LocalDate.of(2026, 7, 22);
        checkRange("day", today, LocalDate.of(2026, 7, 22), today);
        checkRange("week", today, LocalDate.of(2026, 7, 16), today);
        checkRange("month", today, LocalDate.of(2026, 6, 23), today);
        checkRange("quarter", today, LocalDate.of(2026, 4, 24), today);
        checkRange("year", today, LocalDate.of(2025, 7, 23), today);
        RankingDateRanges.DateRange custom = RankingDateRanges.resolve(
                "custom", today, "2026-01-02", "2026-03-04");
        check(custom.from().equals(LocalDate.of(2026, 1, 2))
                        && custom.to().equals(LocalDate.of(2026, 3, 4)),
                "Custom date range was not preserved");
    }

    @SuppressWarnings("unchecked")
    private static void checkMenuCoverage() throws Exception {
        var method = RankBoardMod.class.getDeclaredMethod("menuMetrics", String.class);
        method.setAccessible(true);
        Set<RankBoardMod.Metric> covered = EnumSet.noneOf(RankBoardMod.Metric.class);
        for (String group : List.of("core", "combat", "build", "life", "explore")) {
            covered.addAll((List<RankBoardMod.Metric>) method.invoke(null, group));
        }
        check(covered.equals(EnumSet.allOf(RankBoardMod.Metric.class)),
                "Categorized menu does not cover every metric: " + covered);
    }

    private static void checkCommandTree() throws Exception {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.server.command.ServerCommandSource>();
        var register = RankBoardMod.class.getDeclaredMethod("registerCommands",
                com.mojang.brigadier.CommandDispatcher.class,
                net.minecraft.command.CommandRegistryAccess.class,
                net.minecraft.server.command.CommandManager.RegistrationEnvironment.class);
        register.setAccessible(true);
        register.invoke(new RankBoardMod(), dispatcher, null, null);
        var root = dispatcher.getRoot().getChild("leaderboard");
        check(root != null, "leaderboard command root missing");
        checkCommandPath(root, "menu", "core", "placed");
        checkCommandPath(root, "menu", "core", "placed", "week");
        checkCommandPath(root, "menu", "core", "placed", "week", "players", "player");
        checkCommandPath(root, "menu", "home");
        checkCommandPath(root, "menu", "quick");
        checkCommandPath(root, "menu", "ranking", "week");
        var quickRanking = checkCommandPath(root, "menu", "ranking", "week", "all", "placed");
        check(quickRanking.getChild("players") == null, "Quick ranking unexpectedly exposes sidebar actions");
        checkCommandPath(root, "menu", "personal", "month", "all", "placed");
        checkCommandPath(root, "menu", "server", "yearly", "all", "placed");
        checkCommandPath(root, "display", "show", "weekly", "placed");
        checkCommandPath(root, "display", "show", "weekly", "placed", "player");
        checkCommandPath(root, "display", "off", "player");
        checkCommandPath(root, "scoreboard", "show", "monthly", "placed");
        checkCommandPath(root, "scoreboard", "clear");
    }

    private static com.mojang.brigadier.tree.CommandNode<?> checkCommandPath(
            com.mojang.brigadier.tree.CommandNode<?> root, String... path) {
        com.mojang.brigadier.tree.CommandNode<?> node = root;
        for (String segment : path) {
            node = node.getChild(segment);
            check(node != null, "Command completion path missing: " + String.join(" ", path));
        }
        return node;
    }
    @SuppressWarnings("unchecked")
    private static void checkNewMetrics() throws Exception {
        com.google.gson.JsonObject stats = new com.google.gson.JsonObject();
        com.google.gson.JsonObject custom = new com.google.gson.JsonObject();
        custom.addProperty("minecraft:animals_bred", 2);
        custom.addProperty("minecraft:damage_blocked_by_shield", 30);
        custom.addProperty("minecraft:enchant_item", 4);
        custom.addProperty("minecraft:sleep_in_bed", 5);
        custom.addProperty("minecraft:walk_one_cm", 100_000);
        custom.addProperty("minecraft:boat_one_cm", 200_000);
        custom.addProperty("minecraft:play_record", 6);
        custom.addProperty("minecraft:target_hit", 7);
        stats.add("minecraft:custom", custom);
        com.google.gson.JsonObject broken = new com.google.gson.JsonObject();
        broken.addProperty("minecraft:diamond_pickaxe", 2);
        broken.addProperty("minecraft:elytra", 1);
        stats.add("minecraft:broken", broken);
        com.google.gson.JsonObject mined = new com.google.gson.JsonObject();
        mined.addProperty("minecraft:diamond_ore", 9);
        mined.addProperty("minecraft:deepslate_diamond_ore", 10);
        mined.addProperty("minecraft:stone", 999);
        stats.add("minecraft:mined", mined);
        com.google.gson.JsonObject used = new com.google.gson.JsonObject();
        used.addProperty("minecraft:totem_of_undying", 11);
        stats.add("minecraft:used", used);

        var method = StatReader.class.getDeclaredMethod("readValues", com.google.gson.JsonObject.class);
        method.setAccessible(true);
        Map<RankBoardMod.Metric, Long> values =
                (Map<RankBoardMod.Metric, Long>) method.invoke(null, stats);
        check(values.get(RankBoardMod.Metric.ANIMALS_BRED) == 2L, "Animals bred metric mismatch");
        check(values.get(RankBoardMod.Metric.SHIELD_BLOCKED) == 30L, "Shield metric mismatch");
        check(values.get(RankBoardMod.Metric.ENCHANTED) == 4L, "Enchanted metric mismatch");
        check(values.get(RankBoardMod.Metric.SLEPT) == 5L, "Sleep metric mismatch");
        check(values.get(RankBoardMod.Metric.TOOLS_BROKEN) == 3L, "Broken tools aggregate mismatch");
        check(values.get(RankBoardMod.Metric.TRAVEL_DISTANCE) == 300_000L, "Travel aggregate mismatch");
        check(values.get(RankBoardMod.Metric.ORES_MINED) == 19L, "Ore aggregate mismatch");
        check(values.get(RankBoardMod.Metric.TOTEM_USED) == 11L, "Totem metric mismatch");
        check(values.get(RankBoardMod.Metric.MUSIC_PLAYED) == 6L, "Music metric mismatch");
        check(values.get(RankBoardMod.Metric.TARGET_HITS) == 7L, "Target metric mismatch");
        check("3.0 km".equals(RankBoardMod.format(RankBoardMod.Metric.TRAVEL_DISTANCE, 300_000L)),
                "Travel formatting mismatch");
    }

    private static void checkRange(String period, LocalDate today, LocalDate from, LocalDate to) {
        RankingDateRanges.DateRange range = RankingDateRanges.resolve(period, today, null, null);
        check(range.from().equals(from) && range.to().equals(to),
                "Unexpected " + period + " date range: " + range);
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
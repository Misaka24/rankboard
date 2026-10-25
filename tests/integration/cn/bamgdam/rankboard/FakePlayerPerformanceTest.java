package cn.bamgdam.rankboard;

import carpet.patches.EntityPlayerMPFake;
import carpet.patches.FakeClientConnection;
import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs inside a disposable 26.3 server against both the previous and fixed JAR. */
public final class FakePlayerPerformanceTest implements ModInitializer {
    private static FakePlayerPerformanceTest instance;
    private final List<EntityPlayerMPFake> players = new ArrayList<>();
    private final List<Double> tickMillis = new ArrayList<>();
    private final List<Double> joinMillis = new ArrayList<>();
    private final Map<String, Object> results = new LinkedHashMap<>();
    private int ticks, stage, cycles;
    private EntityPlayerMPFake churnPlayer;
    private final int viewers = Integer.getInteger("rankboard.test.viewers", 3);
    private final boolean mixedMetrics = Boolean.getBoolean("rankboard.test.mixed");

    @Override public void onInitialize() {
        instance = this;
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (stage == 2) for (ServerPlayer player : players) {
                player.getStats().setValue(player, Stats.CUSTOM.get(Stats.JUMP), 1000 + ticks);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try { step(server); }
            catch (Throwable failure) {
                System.out.println("PERF_FAIL " + failure);
                failure.printStackTrace();
                server.halt(false);
            }
        });
    }

    private void step(MinecraftServer server) throws Exception {
        ++ticks;
        if (!StatReader.isReady() || ticks < 80) return;
        if (stage == 0) {
            for (int i = 0; i < 20; ++i) players.add(add(server, "Perf" + String.format("%02d", i)));
            for (ServerPlayer player : players.subList(0, viewers)) BoardService.enable(player.createCommandSourceStack(),
                    RankBoardMod.Period.ALL, RankBoardMod.Metric.PLAY_TIME);
            checkCounters(players.getFirst());
            stage = 1;
            ticks = 0;
        } else if (stage == 1 && ticks >= 30) {
            results.put("players", players.size());
            results.put("viewers", viewers);
            results.put("mixedMetrics", mixedMetrics);
            results.put("refreshAllMs", benchmark(() -> BoardService.refreshAll(server), 3, 8));
            results.put("existingJoinBaselineMs", benchmark(() -> LeaderboardState.get(server)
                    .ensurePlayer(players.getFirst()), 3, 12));
            System.out.println("PERF_BENCHMARK " + new Gson().toJson(results));
            List<RankBoardMod.Metric> enabled = Arrays.stream(RankBoardMod.Metric.values())
                    .filter(metric -> LeaderboardState.get(server).isMetricDisplayEnabled(metric)).toList();
            for (int i = 0; i < viewers; ++i) BoardService.enable(players.get(i).createCommandSourceStack(),
                    RankBoardMod.Period.ALL, mixedMetrics ? enabled.get(i % enabled.size()) : RankBoardMod.Metric.JUMPS);
            command(server, "leaderboard config set scoreboard-live-update-enabled true");
            stage = 2;
            ticks = 0;
        } else if (stage == 2) {
            if (tickMillis.size() < 120) return;
            results.put("activeTicksMs", distribution(tickMillis));
            command(server, "leaderboard config set scoreboard-live-update-enabled false");
            stage = 3;
        } else if (stage == 3) {
            if (churnPlayer == null) {
                long started = System.nanoTime();
                churnPlayer = add(server, "111111");
                joinMillis.add((System.nanoTime() - started) / 1_000_000.0);
                long previous = RankBoardMod.Metric.JUMPS.read(churnPlayer);
                if (cycles > 0) expect(previous == 320 + cycles, "same-UUID fake lost statistics: " + previous);
                churnPlayer.getStats().setValue(churnPlayer, Stats.CUSTOM.get(Stats.JUMP), 321 + cycles);
            } else if (server.getPlayerList().getPlayer(churnPlayer.getUUID()) == null) {
                churnPlayer = null;
                if (++cycles == 20) stage = 4;
            } else {
                churnPlayer.kill(Component.literal("performance-regression"));
            }
        } else if (stage == 4) {
            checkReadBoundaries(server);
            checkPeriodRollover(server);
            results.put("sameNameChurnCycles", cycles);
            results.put("churnUuid", UUID.nameUUIDFromBytes("OfflinePlayer:111111".getBytes(StandardCharsets.UTF_8)).toString());
            results.put("sameNameJoinMs", distribution(joinMillis));
            System.out.println("PERF_RESULT " + new Gson().toJson(results));
            System.out.println("PERF_PASS");
            stage = 5;
            server.halt(false);
        }
    }

    public static boolean measuringTicks() { return instance != null && instance.stage == 2; }
    public static void recordCompletedTick(long nanos) {
        if (instance != null && instance.tickMillis.size() < 120) instance.tickMillis.add(nanos / 1_000_000.0);
    }

    private static EntityPlayerMPFake add(MinecraftServer server, String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);
        EntityPlayerMPFake player = EntityPlayerMPFake.respawnFake(server, server.overworld(), profile,
                ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new FakeClientConnection(PacketFlow.SERVERBOUND), player,
                new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
        player.setGameMode(GameType.SPECTATOR);
        return player;
    }

    private static void checkCounters(ServerPlayer player) {
        player.getStats().setValue(player, Stats.ITEM_USED.get(Items.APPLE), 7);
        player.getStats().setValue(player, Stats.ITEM_USED.get(Items.REDSTONE_BLOCK), 13);
        player.getStats().setValue(player, Stats.BLOCK_MINED.get(Blocks.STONE), 17);
        player.getStats().setValue(player, Stats.ITEM_DROPPED.get(Items.APPLE), 19);
        player.getStats().setValue(player, Stats.ITEM_PICKED_UP.get(Items.APPLE), 23);
        player.getStats().setValue(player, Stats.ITEM_CRAFTED.get(Items.APPLE), 29);
        expect(RankBoardMod.Metric.FOOD.read(player) == 7, "food classification changed");
        expect(RankBoardMod.Metric.REDSTONE_PLACED.read(player) == 13, "redstone classification changed");
        expect(RankBoardMod.Metric.PLACED.read(player) == 13, "block classification changed");
        expect(RankBoardMod.Metric.MINED.read(player) == 17, "mined count changed");
        expect(RankBoardMod.Metric.DROPPED.read(player) == 19, "dropped count changed");
        expect(RankBoardMod.Metric.PICKED_UP.read(player) == 23, "picked count changed");
        expect(RankBoardMod.Metric.CRAFTED.read(player) == 29, "crafted count changed");
    }

    private void checkReadBoundaries(MinecraftServer server) throws Exception {
        ServerPlayer player = players.getFirst();
        player.getStats().setValue(player, Stats.CUSTOM.get(Stats.JUMP), 9001);
        expect(value(server, player) == 9001, "first query is stale");
        try {
            Class<?> scope = Class.forName("cn.bamgdam.rankboard.LeaderboardReadScope");
            var open = scope.getDeclaredMethod("open", MinecraftServer.class);
            open.setAccessible(true);
            try (AutoCloseable ignored = (AutoCloseable) open.invoke(null, server)) {
                var first = RankBoardMod.entries(server, RankBoardMod.Period.ALL, RankBoardMod.Metric.JUMPS);
                var second = RankBoardMod.entries(server, RankBoardMod.Period.ALL, RankBoardMod.Metric.JUMPS);
                expect(first == second, "rankings were recomputed inside one refresh");
            }
            results.put("scopedReuse", true);
        } catch (ClassNotFoundException baselineWithoutScope) {
            results.put("scopedReuse", "not present in baseline");
        }
        player.getStats().setValue(player, Stats.CUSTOM.get(Stats.JUMP), 9002);
        expect(value(server, player) == 9002, "new query reused an expired refresh snapshot");
    }

    @SuppressWarnings("unchecked")
    private void checkPeriodRollover(MinecraftServer server) throws Exception {
        LeaderboardState state = LeaderboardState.get(server);
        var periodsField = LeaderboardState.class.getDeclaredField("periods");
        periodsField.setAccessible(true);
        ((Map<?, ?>) periodsField.get(state)).remove(RankBoardMod.Period.DAILY);
        try {
            var dateField = LeaderboardState.class.getDeclaredField("lastPeriodReadDate");
            dateField.setAccessible(true);
            dateField.set(state, LocalDate.now().minusDays(1));
        } catch (NoSuchFieldException baselineWithoutGate) { }
        state.rollPeriods(server);
        expect(state.getBaseline(RankBoardMod.Period.DAILY, players.getFirst().getUUID(),
                RankBoardMod.Metric.JUMPS) == 9002, "period rollover did not capture current statistics");
        results.put("periodRollover", true);
    }

    private static long value(MinecraftServer server, ServerPlayer player) {
        return RankBoardMod.entries(server, RankBoardMod.Period.ALL, RankBoardMod.Metric.JUMPS).stream()
                .filter(row -> row.uuid().equals(player.getUUID())).findFirst().orElseThrow().value();
    }

    private static Map<String, Double> benchmark(Runnable action, int warmup, int count) {
        for (int i = 0; i < warmup; ++i) action.run();
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < count; ++i) {
            long start = System.nanoTime();
            action.run();
            samples.add((System.nanoTime() - start) / 1_000_000.0);
        }
        return distribution(samples);
    }

    private static Map<String, Double> distribution(List<Double> samples) {
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return Map.of("mean", Arrays.stream(sorted).average().orElseThrow(),
                "p95", sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * .95) - 1)],
                "max", sorted[sorted.length - 1]);
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

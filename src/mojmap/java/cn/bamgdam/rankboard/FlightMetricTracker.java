package cn.bamgdam.rankboard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Accumulates long Elytra distance values without relying on vanilla's bounded stat counter. */
final class FlightMetricTracker {
    private static final Map<UUID, FlightActivity> ACTIVITIES = new HashMap<>();
    private static final Map<UUID, Long> PENDING = new HashMap<>();
    private static MinecraftServer server;
    private static long tick;

    private FlightMetricTracker() { }

    static void start(MinecraftServer value) {
        server = value;
        tick = 0;
        ACTIVITIES.clear();
        PENDING.clear();
    }

    static void stop(MinecraftServer value) {
        if (server != value) return;
        flush(value);
        ACTIVITIES.clear();
        PENDING.clear();
        server = null;
    }

    static void join(MinecraftServer value, ServerPlayer player) {
        if (server != value) start(value);
        LeaderboardState.get(value).ensureCustomMetric(player.getUUID(), RankBoardMod.Metric.ELYTRA_DISTANCE,
                RankBoardMod.Metric.ELYTRA_DISTANCE.read(player));
        ACTIVITIES.put(player.getUUID(), new FlightActivity(player));
    }

    static void disconnect(MinecraftServer value, ServerPlayer player) {
        if (server != value) return;
        FlightActivity activity = ACTIVITIES.get(player.getUUID());
        if (activity != null) process(player, activity);
        flush(value);
        ACTIVITIES.remove(player.getUUID());
    }

    static void tick(MinecraftServer value) {
        if (server != value) start(value);
        tick++;
        for (ServerPlayer player : value.getPlayerList().getPlayers()) {
            FlightActivity activity = ACTIVITIES.computeIfAbsent(player.getUUID(), ignored -> new FlightActivity(player));
            process(player, activity);
        }
        if (tick % 20L == 0L) flush(value);
    }

    private static void process(ServerPlayer player, FlightActivity activity) {
        boolean flying = player.getPose() == Pose.FALL_FLYING;
        long centimeters = FlightDistanceMath.deltaCentimeters(activity.fallFlying, flying,
                activity.level == player.level(),
                player.getX() - activity.x, player.getY() - activity.y, player.getZ() - activity.z);
        if (centimeters > 0) addPending(player.getUUID(), centimeters);
        activity.x = player.getX();
        activity.y = player.getY();
        activity.z = player.getZ();
        activity.level = player.level();
        activity.fallFlying = flying;
    }

    private static void addPending(UUID uuid, long amount) {
        PENDING.merge(uuid, amount, (oldValue, newValue) -> {
            try { return Math.addExact(oldValue, newValue); }
            catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
        });
    }

    private static void flush(MinecraftServer value) {
        if (PENDING.isEmpty()) return;
        LeaderboardState state = LeaderboardState.get(value);
        PENDING.forEach((uuid, amount) -> state.addCustomMetric(uuid, RankBoardMod.Metric.ELYTRA_DISTANCE, amount));
        PENDING.clear();
    }

    private static final class FlightActivity {
        double x;
        double y;
        double z;
        Object level;
        boolean fallFlying;

        FlightActivity(ServerPlayer player) {
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            level = player.level();
            fallFlying = player.getPose() == Pose.FALL_FLYING;
        }
    }
}

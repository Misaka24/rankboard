package cn.bamgdam.rankboard;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.Pose;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks runtime metrics, including counters that exceed vanilla's bounded statistics. */
final class RuntimeMetricTracker {
    private static final long AFK_THRESHOLD_TICKS = 3L * 60L * 20L;
    private static final Map<UUID, Activity> ACTIVITIES = new HashMap<>();
    private static final Map<UUID, FlightActivity> FLIGHT_ACTIVITIES = new HashMap<>();
    private static final Map<UUID, Long> PENDING_AFK = new HashMap<>();
    private static final Map<UUID, Long> PENDING_BEDROCK = new HashMap<>();
    private static final Map<UUID, Long> PENDING_ELYTRA = new HashMap<>();
    private static final Map<BlockPos, UUID> BEDROCK_WATCHERS = new HashMap<>();
    private static MinecraftServer server;
    private static long tick;

    private RuntimeMetricTracker() { }

    static void start(MinecraftServer value) {
        server = value;
        tick = 0;
        ACTIVITIES.clear();
        FLIGHT_ACTIVITIES.clear();
        PENDING_AFK.clear();
        PENDING_BEDROCK.clear();
        PENDING_ELYTRA.clear();
        BEDROCK_WATCHERS.clear();
    }

    static void stop(MinecraftServer value) {
        if (server != value) return;
        flush(value);
        ACTIVITIES.clear();
        FLIGHT_ACTIVITIES.clear();
        PENDING_AFK.clear();
        PENDING_BEDROCK.clear();
        PENDING_ELYTRA.clear();
        BEDROCK_WATCHERS.clear();
        server = null;
    }

    static void join(MinecraftServer value, ServerPlayer player) {
        if (server != value) start(value);
        ACTIVITIES.put(player.getUUID(), new Activity(player.getX(), player.getY(), player.getZ(), tick, tick));
        FLIGHT_ACTIVITIES.put(player.getUUID(), new FlightActivity(player));
        LeaderboardState.get(value).ensureCustomMetric(player.getUUID(), RankBoardMod.Metric.ELYTRA_DISTANCE,
                RankBoardMod.Metric.ELYTRA_DISTANCE.read(player));
    }

    static void disconnect(MinecraftServer value, ServerPlayer player) {
        if (server != value) return;
        Activity activity = ACTIVITIES.get(player.getUUID());
        if (activity != null) process(value, player, activity, tick);
        FlightActivity flightActivity = FLIGHT_ACTIVITIES.get(player.getUUID());
        if (flightActivity != null) processFlight(player, flightActivity);
        flush(value);
        ACTIVITIES.remove(player.getUUID());
        FLIGHT_ACTIVITIES.remove(player.getUUID());
    }

    static void tick(MinecraftServer value) {
        if (server != value) start(value);
        tick++;
        for (ServerPlayer player : value.getPlayerList().getPlayers()) {
            Activity activity = ACTIVITIES.computeIfAbsent(player.getUUID(), ignored ->
                    new Activity(player.getX(), player.getY(), player.getZ(), tick, tick));
            process(value, player, activity, tick);
            FlightActivity flightActivity = FLIGHT_ACTIVITIES.computeIfAbsent(player.getUUID(), ignored ->
                    new FlightActivity(player));
            processFlight(player, flightActivity);
        }
        if (RankBoardConfig.get().bedrockBreakLeaderboardEnabled && tick % 20L == 0L) scanBedrock(value);
        else if (!RankBoardConfig.get().bedrockBreakLeaderboardEnabled && !BEDROCK_WATCHERS.isEmpty()) BEDROCK_WATCHERS.clear();
        if (tick % 20L == 0L) flush(value);
    }

    private static void processFlight(ServerPlayer player, FlightActivity activity) {
        boolean flying = player.getPose() == Pose.FALL_FLYING;
        Object world = player.level();
        long centimeters = FlightDistanceMath.deltaCentimeters(activity.fallFlying, flying,
                activity.world == world,
                player.getX() - activity.x, player.getY() - activity.y, player.getZ() - activity.z);
        if (centimeters > 0) addPending(PENDING_ELYTRA, player.getUUID(), centimeters);
        activity.x = player.getX();
        activity.y = player.getY();
        activity.z = player.getZ();
        activity.world = world;
        activity.fallFlying = flying;
    }

    private static void process(MinecraftServer value, ServerPlayer player, Activity activity, long now) {
        boolean moved = player.getX() != activity.x || player.getY() != activity.y || player.getZ() != activity.z;
        if (moved) {
            if (activity.afk) addPending(PENDING_AFK, player.getUUID(), Math.max(0L, now - activity.countedTick));
            activity.afk = false;
            activity.lastMovementTick = now;
            activity.countedTick = now;
            activity.x = player.getX();
            activity.y = player.getY();
            activity.z = player.getZ();
            return;
        }
        if (now - activity.lastMovementTick >= AFK_THRESHOLD_TICKS) {
            if (!activity.afk) {
                activity.afk = true;
                // Count the complete three-minute threshold, not only time after it.
                activity.countedTick = activity.lastMovementTick;
            }
            addPending(PENDING_AFK, player.getUUID(), Math.max(0L, now - activity.countedTick));
            activity.countedTick = now;
        }
    }

    private static void scanBedrock(MinecraftServer value) {
        Map<BlockPos, UUID> seenThisPass = new HashMap<>();
        for (ServerPlayer player : value.getPlayerList().getPlayers()) {
            BlockPos center = player.blockPosition();
            for (int dx = -5; dx <= 5; dx++) for (int dy = -5; dy <= 5; dy++) for (int dz = -5; dz <= 5; dz++) {
                if (dx * dx + dy * dy + dz * dz > 25) continue;
                BlockPos pos = center.offset(dx, dy, dz);
                if (player.level().getBlockState(pos).is(Blocks.BEDROCK)) {
                    UUID previous = seenThisPass.get(pos);
                    if (previous == null || distanceSquared(value, previous, pos) > distanceSquared(value, player.getUUID(), pos)) {
                        seenThisPass.put(pos, player.getUUID());
                    }
                }
            }
        }
        // A watched bedrock block which is no longer bedrock was removed while it was near a player.
        for (Map.Entry<BlockPos, UUID> entry : new HashMap<>(BEDROCK_WATCHERS).entrySet()) {
            BlockPos pos = entry.getKey();
            if (seenThisPass.containsKey(pos)) continue;
            ServerPlayer watcher = value.getPlayerList().getPlayer(entry.getValue());
            if (watcher != null && !watcher.level().getBlockState(pos).is(Blocks.BEDROCK)
                    && watcher.distanceToSqr(Vec3.atCenterOf(pos)) <= 36.0) {
                addPending(PENDING_BEDROCK, watcher.getUUID(), 1L);
            }
            BEDROCK_WATCHERS.remove(pos);
        }
        BEDROCK_WATCHERS.putAll(seenThisPass);
    }

    private static double distanceSquared(MinecraftServer value, UUID uuid, BlockPos pos) {
        ServerPlayer player = value.getPlayerList().getPlayer(uuid);
        return player == null ? Double.MAX_VALUE : player.distanceToSqr(Vec3.atCenterOf(pos));
    }

    private static void addPending(Map<UUID, Long> pending, UUID uuid, long amount) {
        if (amount <= 0) return;
        pending.merge(uuid, amount, (oldValue, newValue) -> {
            try { return Math.addExact(oldValue, newValue); }
            catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
        });
    }

    private static void flush(MinecraftServer value) {
        LeaderboardState state = LeaderboardState.get(value);
        PENDING_AFK.forEach((uuid, amount) -> state.addCustomMetric(uuid, RankBoardMod.Metric.AFK_TIME, amount));
        PENDING_BEDROCK.forEach((uuid, amount) -> state.addCustomMetric(uuid, RankBoardMod.Metric.BEDROCK_BROKEN, amount));
        PENDING_ELYTRA.forEach((uuid, amount) -> state.addCustomMetric(uuid, RankBoardMod.Metric.ELYTRA_DISTANCE, amount));
        PENDING_AFK.clear();
        PENDING_BEDROCK.clear();
        PENDING_ELYTRA.clear();
    }

    private static final class Activity {
        double x;
        double y;
        double z;
        long lastMovementTick;
        long countedTick;
        boolean afk;

        Activity(double x, double y, double z, long lastMovementTick, long countedTick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastMovementTick = lastMovementTick;
            this.countedTick = countedTick;
        }
    }

    private static final class FlightActivity {
        double x;
        double y;
        double z;
        Object world;
        boolean fallFlying;

        FlightActivity(ServerPlayer player) {
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            world = player.level();
            fallFlying = player.getPose() == Pose.FALL_FLYING;
        }
    }
}

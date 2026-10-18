package cn.bamgdam.rankboard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Shares reads only within one synchronous refresh, never across ticks or commands. */
final class LeaderboardReadScope implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private final Context previous;
    private final boolean owner;

    private LeaderboardReadScope(MinecraftServer server) {
        previous = CURRENT.get();
        owner = previous == null || previous.server != server;
        if (owner) CURRENT.set(new Context(server));
    }

    static LeaderboardReadScope open(MinecraftServer server) {
        return new LeaderboardReadScope(server);
    }

    static long read(ServerPlayerEntity player, RankBoardMod.Metric metric) {
        Context context = CURRENT.get();
        if (context == null || context.server != PlayerCompat.server(player)) return metric.read(player);
        return context.values.computeIfAbsent(player.getUuid(), ignored -> new EnumMap<>(RankBoardMod.Metric.class))
                .computeIfAbsent(metric, ignored -> metric.read(player));
    }

    static List<RankBoardMod.Entry> entries(MinecraftServer server, RankBoardMod.Period period,
                                          RankBoardMod.Metric metric, Supplier<List<RankBoardMod.Entry>> reader) {
        Context context = CURRENT.get();
        if (context == null || context.server != server) return reader.get();
        return context.rankings.computeIfAbsent(new Selection(period, metric), ignored -> reader.get());
    }

    @Override public void close() {
        if (!owner) return;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    private static final class Context {
        final MinecraftServer server;
        final Map<UUID, EnumMap<RankBoardMod.Metric, Long>> values = new HashMap<>();
        final Map<Selection, List<RankBoardMod.Entry>> rankings = new HashMap<>();
        Context(MinecraftServer server) { this.server = server; }
    }

    private record Selection(RankBoardMod.Period period, RankBoardMod.Metric metric) { }
}

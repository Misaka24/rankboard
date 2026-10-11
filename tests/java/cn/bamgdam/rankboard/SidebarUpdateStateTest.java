package cn.bamgdam.rankboard;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.scores.*;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SidebarUpdateStateTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Scoreboard scoreboard = new Scoreboard();
        Objective board = scoreboard.addObjective("rbp_test", ObjectiveCriteria.DUMMY, Component.literal("Flight"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        SidebarUpdateState state = new SidebarUpdateState();
        List<Packet<ClientGamePacketListener>> packets = new ArrayList<>();
        var a = row(board, "a", 1, "0.6 km");
        var b = row(board, "b", 2, "1.0 km");
        state.refresh(board, List.of(a, b), packets::add);
        expect(packets.size() == 4, "initial objective + two rows + display");
        packets.clear();
        state.refresh(board, List.of(row(board, "a", 1, "0.6 km"), row(board, "b", 2, "1.0 km")), packets::add);
        expect(packets.isEmpty(), "unchanged refresh must send no packets");
        var updated = row(board, "a", 3, "2.0 km");
        state.refresh(board, List.of(updated, b), packets::add);
        expect(packets.equals(List.of(updated)), "value update sends only changed row");
        packets.clear();
        state.refresh(board, List.of(b, updated), packets::add);
        expect(packets.isEmpty(), "list reordering must not rebuild objective");
        var formatted = row(board, "a", 3, "90000000.0 km");
        state.refresh(board, List.of(b, formatted), packets::add);
        expect(packets.equals(List.of(formatted)), "format changes update even when integer score is unchanged");
        packets.clear();
        state.refresh(board, List.of(b), packets::add);
        expect(packets.size() == 1 && packets.getFirst() instanceof ClientboundResetScorePacket reset
                && reset.owner().equals("a"), "only stale row removed");
        packets.clear();
        board.setDisplayName(Component.literal("Renamed"));
        state.refresh(board, List.of(b), packets::add);
        expect(packets.size() == 1 && packets.getFirst() instanceof ClientboundSetObjectivePacket title
                && title.getMethod() == ClientboundSetObjectivePacket.METHOD_CHANGE, "title update must not remove objective");
        packets.clear();
        state.refresh(board, List.of(), packets::add);
        expect(packets.size() == 1 && packets.getFirst() instanceof ClientboundResetScorePacket, "empty board clears stale row only");
        packets.clear();
        Objective next = scoreboard.addObjective("rbp_next", ObjectiveCriteria.DUMMY, Component.literal("Next"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        state.refresh(next, List.of(row(next, "b", 5, "3.0 km")), packets::add);
        expect(packets.size() == 4 && packets.getFirst() instanceof ClientboundSetObjectivePacket old
                && old.getMethod() == ClientboundSetObjectivePacket.METHOD_REMOVE, "switch must clean up old objective");
        testGlobalScoreUpdates();
        System.out.println("PASS: actual sidebar packets for initial display, unchanged refresh, value/rank/title changes, stale/empty rows and switching");
    }

    private static void testGlobalScoreUpdates() throws Exception {
        CountingScoreboard scoreboard = new CountingScoreboard();
        Objective board = scoreboard.addObjective("rbg_test", ObjectiveCriteria.DUMMY, Component.literal("Global"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
        var update = BoardService.class.getDeclaredMethod("updateScore", Scoreboard.class, Objective.class,
                String.class, int.class, NumberFormat.class);
        update.setAccessible(true);
        update.invoke(null, scoreboard, board, "player", 1, new FixedFormat(Component.literal("0.6 km")));
        scoreboard.changed = 0;
        update.invoke(null, scoreboard, board, "player", 1, new FixedFormat(Component.literal("0.6 km")));
        expect(scoreboard.changed == 0, "unchanged global refresh must not send score changes");
        update.invoke(null, scoreboard, board, "player", 2, new FixedFormat(Component.literal("0.6 km")));
        expect(scoreboard.changed == 1, "changed global score updates in place");
        update.invoke(null, scoreboard, board, "player", 2, new FixedFormat(Component.literal("90000000.0 km")));
        expect(scoreboard.changed == 2, "changed global format updates in place");
        expect(scoreboard.removed == 0, "global refresh never clears the updated score");
    }

    private static final class CountingScoreboard extends Scoreboard {
        int changed;
        int removed;
        @Override public void onScoreChanged(ScoreHolder holder, Objective objective, Score score) { changed++; }
        @Override public void onPlayerScoreRemoved(ScoreHolder holder, Objective objective) { removed++; }
    }

    private static ClientboundSetScorePacket row(Objective board, String owner, int score, String value) {
        return new ClientboundSetScorePacket(owner, board.getName(), score, Optional.empty(),
                Optional.of(new FixedFormat(Component.literal(value))));
    }
    private static void expect(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

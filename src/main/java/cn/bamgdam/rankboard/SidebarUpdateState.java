package cn.bamgdam.rankboard;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Keeps ordinary score refreshes from removing and recreating the sidebar. */
final class SidebarUpdateState {
    private ScoreboardObjective objective;
    private Text title;
    private Map<String, ScoreboardScoreUpdateS2CPacket> rows = Map.of();

    void refresh(ScoreboardObjective next, List<ScoreboardScoreUpdateS2CPacket> updates,
                 Consumer<Packet<ClientPlayPacketListener>> send) {
        boolean changed = objective == null || !objective.getName().equals(next.getName());
        if (changed) {
            if (objective != null) send.accept(new ScoreboardObjectiveUpdateS2CPacket(objective, ScoreboardObjectiveUpdateS2CPacket.REMOVE_MODE));
            send.accept(new ScoreboardObjectiveUpdateS2CPacket(next, ScoreboardObjectiveUpdateS2CPacket.ADD_MODE));
            rows = Map.of();
        } else if (!next.getDisplayName().equals(title)) {
            send.accept(new ScoreboardObjectiveUpdateS2CPacket(next, ScoreboardObjectiveUpdateS2CPacket.UPDATE_MODE));
        }
        Map<String, ScoreboardScoreUpdateS2CPacket> nextRows = new LinkedHashMap<>();
        for (var packet : updates) {
            nextRows.put(packet.scoreHolderName(), packet);
            if (!sameRow(packet, rows.get(packet.scoreHolderName()))) send.accept(packet);
        }
        for (String owner : rows.keySet()) {
            if (!nextRows.containsKey(owner)) send.accept(new ScoreboardScoreResetS2CPacket(owner, next.getName()));
        }
        if (changed) send.accept(new ScoreboardDisplayS2CPacket(ScoreboardDisplaySlot.SIDEBAR, next));
        objective = next;
        title = next.getDisplayName().copy();
        rows = nextRows;
    }

    private static boolean sameRow(ScoreboardScoreUpdateS2CPacket next, ScoreboardScoreUpdateS2CPacket previous) {
        if (previous == null || next.score() != previous.score() || !next.display().equals(previous.display())
                || next.numberFormat().isPresent() != previous.numberFormat().isPresent()) return false;
        return next.numberFormat().isEmpty() || next.numberFormat().get().format(next.score())
                .equals(previous.numberFormat().get().format(previous.score()));
    }
}

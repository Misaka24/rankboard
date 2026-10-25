package cn.bamgdam.rankboard;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Client-local snapshot: normal refreshes never remove or recreate an objective. */
final class SidebarUpdateState {
    private Objective objective;
    private Component title;
    private Map<String, ClientboundSetScorePacket> rows = Map.of();

    void refresh(Objective next, List<ClientboundSetScorePacket> updates,
                 Consumer<Packet<ClientGamePacketListener>> send) {
        boolean changed = objective == null || !objective.getName().equals(next.getName());
        if (changed) {
            if (objective != null) send.accept(new ClientboundSetObjectivePacket(
                    objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
            send.accept(new ClientboundSetObjectivePacket(next, ClientboundSetObjectivePacket.METHOD_ADD));
            rows = Map.of();
        } else if (!next.getDisplayName().equals(title)) {
            send.accept(new ClientboundSetObjectivePacket(next, ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        Map<String, ClientboundSetScorePacket> nextRows = new LinkedHashMap<>();
        for (ClientboundSetScorePacket packet : updates) {
            nextRows.put(packet.owner(), packet);
            if (!sameRow(packet, rows.get(packet.owner()))) send.accept(packet);
        }
        for (String owner : rows.keySet()) {
            if (!nextRows.containsKey(owner)) send.accept(new ClientboundResetScorePacket(owner, next.getName()));
        }
        if (changed) send.accept(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, next));
        objective = next;
        title = next.getDisplayName().copy();
        rows = nextRows;
    }

    private static boolean sameRow(ClientboundSetScorePacket next, ClientboundSetScorePacket previous) {
        if (previous == null || next.score() != previous.score()
                || !next.display().equals(previous.display())
                || next.numberFormat().isPresent() != previous.numberFormat().isPresent()) return false;
        return next.numberFormat().isEmpty() || next.numberFormat().get().format(next.score())
                .equals(previous.numberFormat().get().format(previous.score()));
    }
}

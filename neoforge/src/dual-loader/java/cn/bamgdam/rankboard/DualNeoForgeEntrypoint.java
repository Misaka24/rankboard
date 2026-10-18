package cn.bamgdam.rankboard;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Native NeoForge adapter; it does not require Fabric Loader or Fabric API. */
@Mod("rankboard")
public final class DualNeoForgeEntrypoint {
    private final RankBoardMod mod = new RankBoardMod();

    public DualNeoForgeEntrypoint() {
        NeoForge.EVENT_BUS.addListener(this::commands);
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        NeoForge.EVENT_BUS.addListener(this::joined);
        NeoForge.EVENT_BUS.addListener(this::disconnected);
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void commands(RegisterCommandsEvent event) {
        mod.registerCommands(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }
    private void started(ServerStartedEvent event) { mod.start(event.getServer()); }
    private void stopping(ServerStoppingEvent event) { mod.stop(event.getServer()); }
    private void tick(ServerTickEvent.Post event) { mod.tick(event.getServer()); }
    private void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) mod.join(player);
    }
    private void disconnected(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) mod.disconnect(player);
    }
}

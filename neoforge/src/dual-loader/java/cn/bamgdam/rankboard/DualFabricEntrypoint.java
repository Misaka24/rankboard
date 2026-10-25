package cn.bamgdam.rankboard;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Fabric loads only this adapter; the shared implementation has no loader imports. */
public final class DualFabricEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        RankBoardMod mod = new RankBoardMod();
        CommandRegistrationCallback.EVENT.register(mod::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(mod::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(mod::stop);
        ServerTickEvents.END_SERVER_TICK.register(mod::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> mod.join(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> mod.disconnect(handler.getPlayer()));
    }
}

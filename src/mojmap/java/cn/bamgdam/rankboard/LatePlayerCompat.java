package cn.bamgdam.rankboard;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class PlayerCompat {
    private PlayerCompat() { }
    static MinecraftServer server(ServerPlayer player) { return player.level().getServer(); }
    static boolean isFake(ServerPlayer player) {
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getSimpleName().equals("EntityPlayerMPFake")) return true;
        }
        return false;
    }
}

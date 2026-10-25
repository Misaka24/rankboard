package cn.bamgdam.rankboardtest;

import cn.bamgdam.rankboardtest.mixin.ProbeConnectionAccessor;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.GameType;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/** Server-internal actor: explicitly NOT a real TCP client or a Carpet dependency. */
public final class SyntheticPlayerProbe {
    public static Consumer<Connection> configureConnection = ignored -> { };
    private static ServerPlayer player;
    private static ProbeConnection connection;
    private static int joins;
    private static final UUID UUID_VALUE = UUID.nameUUIDFromBytes("OfflinePlayer:RankBoardProbe".getBytes(StandardCharsets.UTF_8));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rankboard_test_player")
                .then(Commands.literal("join").executes(context -> join(context.getSource())))
                .then(Commands.literal("check").executes(context -> check(context.getSource())))
                .then(Commands.literal("leave").executes(context -> leave(context.getSource()))));
    }

    private static int join(CommandSourceStack source) {
        if (player != null) throw new IllegalStateException("Previous probe player was not removed");
        MinecraftServer server = source.getServer();
        GameProfile profile = new GameProfile(UUID_VALUE, "RankBoardProbe");
        player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault()) {
            @Override public void tick() { }
        };
        connection = new ProbeConnection();
        configureConnection.accept(connection);
        server.getPlayerList().placeNewPlayer(connection, player,
                new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
        player.setGameMode(GameType.SPECTATOR);
        player.getStats().setValue(player, Stats.CUSTOM.get(Stats.JUMP), 1000 + ++joins);
        if (!player.getDisplayName().getString().contains("RankBoardProbe")) throw new AssertionError("Name mixin lost the player name");
        source.sendSuccess(() -> Component.literal("SYNTHETIC_JOIN_OK " + UUID_VALUE), false);
        return 1;
    }

    private static int check(CommandSourceStack source) {
        if (player == null || source.getServer().getPlayerList().getPlayer(UUID_VALUE) != player) {
            throw new AssertionError("Probe actor did not enter the actual player list");
        }
        if (connection.objectives == 0 || connection.scores == 0) throw new AssertionError("No sidebar packets reached the actor");
        source.sendSuccess(() -> Component.literal("SYNTHETIC_SIDEBAR_OK objectives=" + connection.objectives + " scores=" + connection.scores), false);
        return 1;
    }

    private static int leave(CommandSourceStack source) {
        if (player == null) throw new IllegalStateException("No probe actor to remove");
        source.getServer().getPlayerList().remove(player);
        connection.channel.close();
        player = null;
        if (source.getServer().getPlayerList().getPlayer(UUID_VALUE) != null) throw new AssertionError("Actor remained in the player list");
        source.sendSuccess(() -> Component.literal("SYNTHETIC_LEAVE_OK"), false);
        return 1;
    }

    private static final class ProbeConnection extends Connection {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private int objectives, scores;
        private ProbeConnection() {
            super(PacketFlow.SERVERBOUND);
            ((ProbeConnectionAccessor) (Object) this).rankboardTestChannel(channel);
        }
        @Override public void setReadOnly() { }
        @Override public void send(Packet<?> packet, ChannelFutureListener callback, boolean flush) {
            if (packet.getClass().getSimpleName().equals("ClientboundSetObjectivePacket")) objectives++;
            if (packet.getClass().getSimpleName().equals("ClientboundSetScorePacket")) scores++;
        }
        @Override public void handleDisconnection() { }
        @Override public void setListenerForServerboundHandshake(PacketListener listener) {
            ((ProbeConnectionAccessor) (Object) this).rankboardTestListener(listener);
        }
        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {
            ((ProbeConnectionAccessor) (Object) this).rankboardTestListener(listener);
        }
    }
}

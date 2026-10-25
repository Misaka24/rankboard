package cn.bamgdam.rankboardtest;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;

@Mod("rankboard_test_probe")
public final class NeoForgeProbeEntrypoint {
    public NeoForgeProbeEntrypoint() { NeoForge.EVENT_BUS.addListener(this::commands); }
    private void commands(RegisterCommandsEvent event) {
        SyntheticPlayerProbe.configureConnection = connection -> {
            ChannelAttributes.setConnectionType(connection, ConnectionType.OTHER);
            ChannelAttributes.setPayloadSetup(connection, NetworkPayloadSetup.empty());
        };
        ComparisonReportProbe.register(event.getDispatcher());
        SyntheticPlayerProbe.register(event.getDispatcher());
    }
}

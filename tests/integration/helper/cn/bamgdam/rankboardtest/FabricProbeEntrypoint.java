package cn.bamgdam.rankboardtest;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class FabricProbeEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> {
            ComparisonReportProbe.register(dispatcher);
            SyntheticPlayerProbe.register(dispatcher);
        });
    }
}

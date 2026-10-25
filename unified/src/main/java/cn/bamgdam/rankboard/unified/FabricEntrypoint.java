package cn.bamgdam.rankboard.unified;
import net.fabricmc.api.ModInitializer;

public final class FabricEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        boolean modern = UnifiedRuntime.implementationPackage().endsWith("mojang26");
        UnifiedRuntime.start(modern ? "DualFabricEntrypoint" : "RankBoardMod", "onInitialize");
    }
}

package cn.bamgdam.rankboard.unified;
import net.neoforged.fml.common.Mod;

@Mod("rankboard")
public final class NeoForgeEntrypoint {
    public NeoForgeEntrypoint() {
        boolean modern = UnifiedRuntime.implementationPackage().endsWith("mojang26");
        UnifiedRuntime.start(modern ? "DualNeoForgeEntrypoint" : "RankBoardMod", null);
    }
}

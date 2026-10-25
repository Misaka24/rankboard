package cn.bamgdam.rankboardtest;

import cn.bamgdam.rankboard.unified.UnifiedRuntime;

public final class UnifiedRuntimeSelectionTest {
    public static void main(String[] args) {
        for (String loader : new String[]{"fabric", "neoforge"}) {
            for (int patch = 0; patch <= 11; ++patch) {
                String version = patch == 0 ? "1.21" : "1.21." + patch;
                String family = loader.equals("fabric") ? "fabric121" : "neo121";
                if (!UnifiedRuntime.select(loader, version).endsWith(family)) throw new AssertionError(version);
            }
            for (String version : new String[]{"26.1", "26.1.1", "26.1.2", "26.2", "26.3"}) {
                if (!UnifiedRuntime.select(loader, version).endsWith("mojang26")) throw new AssertionError(version);
            }
            for (String unsupported : new String[]{"", "1.20.6", "1.21.12", "26.3-pre1", "26.4", "27.1"}) {
                try { UnifiedRuntime.select(loader, unsupported); throw new AssertionError("Accepted " + unsupported); }
                catch (IllegalStateException expected) { }
            }
        }
        try { UnifiedRuntime.select("forge", "1.21"); throw new AssertionError("Accepted unknown loader"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("UNIFIED_RUNTIME_SELECTION_OK (34 supported combinations)");
    }
}

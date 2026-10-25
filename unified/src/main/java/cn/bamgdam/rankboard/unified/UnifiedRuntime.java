package cn.bamgdam.rankboard.unified;

import java.util.Optional;
import java.util.Set;

/** Select once at startup without loading game-specific classes. */
public final class UnifiedRuntime {
    private static final Set<String> SUPPORTED = Set.of("1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4",
            "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2", "26.3");
    private static volatile String selected;
    private UnifiedRuntime() { }

    public static String select(String loader, String version) {
        if (!SUPPORTED.contains(version)) throw new IllegalStateException("Unsupported RankBoard game version: " + version);
        if (!loader.equals("fabric") && !loader.equals("neoforge")) throw new IllegalArgumentException("Unknown loader " + loader);
        return "cn.bamgdam.rankboard.impl." + (version.startsWith("26.") ? "mojang26"
                : loader.equals("fabric") ? "fabric121" : "neo121");
    }

    public static String implementationPackage() {
        String cached = selected;
        if (cached != null) return cached;
        synchronized (UnifiedRuntime.class) {
            if (selected != null) return selected;
            try {
                ClassLoader loader = UnifiedRuntime.class.getClassLoader();
                Class<?> fml;
                try { fml = Class.forName("net.neoforged.fml.loading.FMLLoader", false, loader); }
                catch (ClassNotFoundException fabricRuntime) { fml = null; }
                String platform = fml == null ? "fabric" : "neoforge";
                String version = fml == null ? fabricVersion(loader) : neoVersion(fml);
                selected = select(platform, version);
                System.out.println("RankBoard unified selected " + selected + " for " + platform + " " + version);
                return selected;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not select RankBoard runtime before loading game classes", exception);
            }
        }
    }

    private static String fabricVersion(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> api = Class.forName("net.fabricmc.loader.api.FabricLoader", true, loader);
        Object instance = api.getMethod("getInstance").invoke(null);
        Optional<?> minecraft = (Optional<?>) api.getMethod("getModContainer", String.class).invoke(instance, "minecraft");
        Object metadata = Class.forName("net.fabricmc.loader.api.ModContainer", false, loader)
                .getMethod("getMetadata").invoke(minecraft.orElseThrow());
        Object version = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, loader)
                .getMethod("getVersion").invoke(metadata);
        return (String) Class.forName("net.fabricmc.loader.api.Version", false, loader)
                .getMethod("getFriendlyString").invoke(version);
    }

    private static String neoVersion(Class<?> fml) throws ReflectiveOperationException {
        Object info;
        try { info = fml.getMethod("versionInfo").invoke(null); }
        catch (NoSuchMethodException instanceApi) {
            Object instance = fml.getMethod("getCurrent").invoke(null);
            info = fml.getMethod("getVersionInfo").invoke(instance);
        }
        return (String) info.getClass().getMethod("mcVersion").invoke(info);
    }

    public static void start(String entrypoint, String method) {
        try {
            Class<?> type = Class.forName(implementationPackage() + '.' + entrypoint, true, UnifiedRuntime.class.getClassLoader());
            Object instance = type.getConstructor().newInstance();
            if (method != null) type.getMethod(method).invoke(instance);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not initialize the selected RankBoard implementation", exception);
        }
    }
}

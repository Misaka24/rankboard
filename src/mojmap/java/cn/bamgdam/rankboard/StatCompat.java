package cn.bamgdam.rankboard;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.stats.Stat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Reads custom statistics whose identifier type changed from ResourceLocation to Identifier. */
final class StatCompat {
    private static final Map<String, Stat<?>> CUSTOM_STATS = new ConcurrentHashMap<>();
    private StatCompat() {}

    static long custom(ServerPlayer player, String statName) {
        return player.getStats().getValue(CUSTOM_STATS.computeIfAbsent(statName, StatCompat::resolve));
    }

    private static Stat<?> resolve(String statName) {
        try {
            Field statField = Stats.class.getField(statName);
            Object identifier = statField.get(null);
            Object customType = Stats.class.getField("CUSTOM").get(null);
            Object stat = null;
            for (Method method : customType.getClass().getMethods()) {
                if (!method.getName().equals("get") || method.getParameterCount() != 1
                        || !method.getParameterTypes()[0].isInstance(identifier)) continue;
                stat = method.invoke(customType, identifier);
                break;
            }
            if (stat == null) throw new NoSuchMethodException("Stats.CUSTOM.get(identifier)");

            return (Stat<?>) stat;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read custom statistic " + statName, exception);
        }
    }
}

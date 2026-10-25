package cn.bamgdam.rankboard;

import java.lang.reflect.Method;
import net.minecraft.core.Registry;

/** Resolves registry identifiers without linking to a version-specific return type. */
final class RegistryKeyCompat {
    private static final Method KEY_GETTER = keyGetter();
    private RegistryKeyCompat() {}

    static String id(Object registry, Object value) {
        Object key = invokeRegistryKey(registry, value);
        return key.toString();
    }

    static String path(Object registry, Object value) {
        String id = id(registry, value);
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private static Method keyGetter() {
        try {
            return Registry.class.getMethod("getKey", Object.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object invokeRegistryKey(Object registry, Object value) {
        try {
            Object key = KEY_GETTER.invoke(registry, value);
            if (key != null) return key;
            throw new IllegalArgumentException("Unregistered value: " + value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve registry key for " + value.getClass().getName(), exception);
        }
    }
}

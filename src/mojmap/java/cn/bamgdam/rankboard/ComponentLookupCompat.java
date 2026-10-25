package cn.bamgdam.rankboard;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reads an item component across Minecraft 1.21 ComponentMap API revisions. */
final class ComponentLookupCompat {
    private static final Map<LookupKey, Method> GETTERS = new ConcurrentHashMap<>();
    private ComponentLookupCompat() { }

    static boolean has(Object components, Object componentType) {
        Method getter = GETTERS.computeIfAbsent(new LookupKey(components.getClass(), componentType.getClass()),
                ignored -> resolve(components, componentType));
        try {
            return getter.invoke(components, componentType) != null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read item component " + componentType, exception);
        }
    }

    private static Method resolve(Object components, Object componentType) {
        Method fallback = null;
        for (Method method : components.getClass().getMethods()) {
            if (method.getName().equals("get") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(componentType)
                    && !method.getReturnType().isPrimitive()
                    && method.getReturnType() != void.class) {
                if (method.getDeclaringClass().isInterface()
                        && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                    fallback = method;
                    break;
                }
                if (fallback == null) fallback = method;
            }
        }
        if (fallback == null) throw new IllegalStateException("Could not find a component getter on "
                + components.getClass().getName());
        if (!fallback.canAccess(components)) fallback.setAccessible(true);
        return fallback;
    }
    private record LookupKey(Class<?> container, Class<?> componentType) { }
}

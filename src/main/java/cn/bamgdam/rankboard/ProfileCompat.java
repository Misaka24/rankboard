package cn.bamgdam.rankboard;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.lang.reflect.Method;
import java.util.Collection;

final class ProfileCompat {
    private ProfileCompat() { }

    @SuppressWarnings("unchecked")
    static Collection<Property> textures(GameProfile profile) {
        Object properties = invoke(profile, "properties", "getProperties");
        try {
            return (Collection<Property>) properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not read player profile textures", exception);
        }
    }

    static String name(GameProfile profile) {
        return (String) invoke(profile, "name", "getName");
    }

    private static Object invoke(GameProfile profile, String modernName, String legacyName) {
        for (String name : new String[]{modernName, legacyName}) {
            try {
                Method method = profile.getClass().getMethod(name);
                return method.invoke(profile);
            } catch (NoSuchMethodException ignored) {
                // Try the other Authlib generation.
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not read player profile " + name, exception);
            }
        }
        throw new IllegalStateException("Unsupported Authlib GameProfile API");
    }
}

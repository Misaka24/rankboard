package cn.bamgdam.rankboardtest.fixture;

/** Deliberately outside the compatibility helper's package, as Minecraft is. */
public final class PermissionSourceFixture {
    public enum Level {
        ZERO, ONE, TWO, THREE, FOUR;
        public static Level byId(int id) { return values()[id]; }
    }
    public interface Permission { record HasCommandLevel(Level level) implements Permission { } }
    public interface PermissionSet { boolean hasPermission(Permission permission); }
    public static final class Source {
        private final PermissionSet permissions;
        public Source(int level) {
            permissions = permission -> ((Permission.HasCommandLevel) permission).level().ordinal() <= level;
        }
        public PermissionSet permissions() { return permissions; }
    }
}

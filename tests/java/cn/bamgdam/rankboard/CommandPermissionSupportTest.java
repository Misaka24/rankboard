package cn.bamgdam.rankboard;

import cn.bamgdam.rankboardtest.fixture.PermissionSourceFixture.Source;

/** No Minecraft dependency: emulate its public API with private lambda implementations. */
public final class CommandPermissionSupportTest {
    public static final class LegacySource {
        public boolean hasPermission(int level) { return level <= 2; }
    }
    public static void main(String[] args) {
        for (int granted = 0; granted <= 4; ++granted) {
            Source source = new Source(granted);
            for (int requested = 0; requested <= 4; ++requested) {
                if (CommandPermissionSupport.has(source, requested) != (requested <= granted)) {
                    throw new AssertionError("Wrong lambda-backed permission result");
                }
            }
        }
        if (!CommandPermissionSupport.has(new LegacySource(), 2)
                || CommandPermissionSupport.has(new LegacySource(), 3)) throw new AssertionError("Legacy permission regression");
        System.out.println("COMMAND_PERMISSION_SUPPORT_OK");
    }
}

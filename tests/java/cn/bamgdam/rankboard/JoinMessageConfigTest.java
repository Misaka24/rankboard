package cn.bamgdam.rankboard;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Properties;

public final class JoinMessageConfigTest {
    public static void main(String[] args) throws Exception {
        Class<?> config = Class.forName("cn.bamgdam.rankboard.RankBoardConfig");
        var constructor = config.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        var simple = config.getDeclaredField("simpleJoinMessages");
        simple.setAccessible(true);
        expect(simple.getBoolean(constructor.newInstance(new Properties())), "old/missing config defaults to simple");
        Properties properties = new Properties();
        properties.setProperty("join-message-mode", "DETAILED");
        expect(!simple.getBoolean(constructor.newInstance(properties)), "detailed config restores old join flow");
        properties.setProperty("join-message-mode", "simple");
        properties.setProperty("welcome-enabled", "true");
        properties.setProperty("join-web-hint-enabled", "true");
        expect(simple.getBoolean(constructor.newInstance(properties)), "simple mode takes priority over verbose toggles");
        var find = config.getDeclaredMethod("findOption", String.class);
        find.setAccessible(true);
        Object option = find.invoke(null, "join-message-mode");
        var normalize = config.getDeclaredMethod("normalize", option.getClass(), String.class);
        normalize.setAccessible(true);
        expect("simple".equals(normalize.invoke(null, option, "SIMPLE")), "command normalizes mode");
        try {
            normalize.invoke(null, option, "invalid");
            throw new AssertionError("invalid mode accepted");
        } catch (InvocationTargetException exception) {
            if (!(exception.getCause() instanceof IllegalArgumentException)) throw exception;
        }
        Class<?> language = Class.forName("cn.bamgdam.rankboard.RankBoardLanguage");
        var zh = language.getDeclaredField("ZH_CN");
        var en = language.getDeclaredField("EN_US");
        zh.setAccessible(true); en.setAccessible(true);
        expect("查看排行榜单相关内容".equals(((Map<?, ?>) zh.get(null)).get("menu.join_compact")), "exact Chinese label");
        expect("View leaderboard information".equals(((Map<?, ?>) en.get(null)).get("menu.join_compact")), "English label");
        System.out.println("PASS: compact join config defaults, detailed mode, validation and Chinese/English button labels");
    }
    private static void expect(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}

package cn.bamgdam.rankboard;

import java.lang.reflect.Method;
import java.util.Optional;

/** Exercises the packaged mod's sidebar formatter against real Minecraft classes. */
public final class SidebarFlightFormatTest {
    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        Class<?> metricClass = Class.forName("cn.bamgdam.rankboard.RankBoardMod$Metric");
        Class<?> boardClass = Class.forName("cn.bamgdam.rankboard.BoardService");
        Method formatter = boardClass.getDeclaredMethod("scoreboardFormat", metricClass, long.class);
        formatter.setAccessible(true);
        Object flight = metricClass.getField("ELYTRA_DISTANCE").get(null);
        expect(formatter, flight, 0L, "0.0 km");
        expect(formatter, flight, 63077L, "0.6 km");
        expect(formatter, flight, 100000L, "1.0 km");
        expect(formatter, flight, 2147483648L, "21474.8 km");
        expect(formatter, flight, 9000000000000L, "90000000.0 km");
        expect(formatter, metricClass.getField("PLAY_TIME").get(null), 144000L, "2h");
        Object ordinary = formatter.invoke(null, metricClass.getField("JUMPS").get(null), 63077L);
        if (((Optional<?>) ordinary).isPresent()) throw new AssertionError("Non-distance metric changed");
        System.out.println("PASS: packaged sidebar km format, zero, centimeter conversion, long values and hours");
    }

    private static void expect(Method formatter, Object metric, long raw, String expected) throws Exception {
        Object format = ((Optional<?>) formatter.invoke(null, metric, raw)).orElseThrow();
        Object text = format.getClass().getMethod("format", int.class).invoke(format, Integer.MAX_VALUE);
        String actual = (String) Class.forName("net.minecraft.network.chat.Component")
                .getMethod("getString").invoke(text);
        if (!expected.equals(actual)) throw new AssertionError(raw + ": expected " + expected + ", got " + actual);
    }
}

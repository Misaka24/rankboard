package cn.bamgdam.rankboard;

public final class FlightDistanceMathTest {
    public static void main(String[] args) {
        expect(500L, FlightDistanceMath.deltaCentimeters(true, true, true, 3.0, 4.0, 0.0),
                "3-4-5 block movement converts to centimeters");
        expect(300L, FlightDistanceMath.deltaCentimeters(true, true, true, 1.0, 2.0, 2.0),
                "three-dimensional movement is counted");
        expect(0L, FlightDistanceMath.deltaCentimeters(false, true, true, 3.0, 4.0, 0.0),
                "takeoff interval is not counted");
        expect(0L, FlightDistanceMath.deltaCentimeters(true, false, true, 3.0, 4.0, 0.0),
                "landing interval is not counted");
        expect(0L, FlightDistanceMath.deltaCentimeters(true, true, false, 3.0, 4.0, 0.0),
                "dimension changes reset the sample");
        expect(0L, FlightDistanceMath.deltaCentimeters(true, true, true, 129.0, 0.0, 0.0),
                "teleport-sized jumps are ignored");
        expect(0L, FlightDistanceMath.deltaCentimeters(true, true, true, Double.NaN, 0.0, 0.0),
                "invalid coordinates are ignored");
        System.out.println("PASS: flight distance interval conversion and guards");
    }

    private static void expect(long expected, long actual, String description) {
        if (expected != actual) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }
}

package cn.bamgdam.rankboard;

/** Converts one observed Elytra movement interval to vanilla-compatible centimeters. */
final class FlightDistanceMath {
    private static final double MAX_TICK_DISTANCE_BLOCKS = 128.0;

    private FlightDistanceMath() { }

    static long deltaCentimeters(boolean wasFlying, boolean isFlying, boolean sameWorld,
                                 double dx, double dy, double dz) {
        if (!wasFlying || !isFlying || !sameWorld
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) return 0L;
        double distance = Math.hypot(Math.hypot(dx, dy), dz);
        if (!(distance > 0.0) || distance > MAX_TICK_DISTANCE_BLOCKS) return 0L;
        return Math.round(distance * 100.0);
    }
}

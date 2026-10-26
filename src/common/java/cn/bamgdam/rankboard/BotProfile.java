package cn.bamgdam.rankboard;

import java.util.Map;

/**
 * Heuristic classification of automated players. Bots usually repeat a single
 * action, so the dominant statistic group is a useful label for operators.
 */
final class BotProfile {
    private BotProfile() { }

    enum Type {
        COMBAT("combat", "攻击型", "Combat"),
        AFK("afk", "挂机型", "AFK"),
        CLICK("click", "点击型", "Clicker"),
        FARM("farm", "农民型", "Farmer"),
        EXPLORE("explore", "探索型", "Explorer"),
        MIXED("mixed", "混合型", "Mixed");

        final String id;
        final String zh;
        final String en;

        Type(String id, String zh, String en) {
            this.id = id;
            this.zh = zh;
            this.en = en;
        }
    }

    static Type classify(Map<RankBoardMod.Metric, Long> values) {
        long playTime = value(values, RankBoardMod.Metric.PLAY_TIME);
        long afkTime = value(values, RankBoardMod.Metric.AFK_TIME);
        if (playTime > 0 && afkTime > 0 && afkTime * 2 >= playTime) return Type.AFK;

        long combat = value(values, RankBoardMod.Metric.KILLS) * 20L
                + value(values, RankBoardMod.Metric.PVP_KILLS) * 30L
                + value(values, RankBoardMod.Metric.DAMAGE_DEALT) / 10L
                + value(values, RankBoardMod.Metric.DAMAGE_TAKEN) / 10L
                + value(values, RankBoardMod.Metric.SHIELD_BLOCKED) / 10L
                + value(values, RankBoardMod.Metric.TARGET_HITS) * 5L
                + value(values, RankBoardMod.Metric.TOTEM_USED) * 10L;
        long click = value(values, RankBoardMod.Metric.PLACED)
                + value(values, RankBoardMod.Metric.CRAFTED)
                + value(values, RankBoardMod.Metric.DROPPED)
                + value(values, RankBoardMod.Metric.PICKED_UP)
                + value(values, RankBoardMod.Metric.REDSTONE_PLACED);
        long farm = value(values, RankBoardMod.Metric.MINED)
                + value(values, RankBoardMod.Metric.ORES_MINED) * 2L
                + value(values, RankBoardMod.Metric.ANIMALS_BRED) * 5L
                + value(values, RankBoardMod.Metric.FISHING) * 3L
                + value(values, RankBoardMod.Metric.TRADES) * 2L
                + value(values, RankBoardMod.Metric.FOOD) / 10L;
        long explore = value(values, RankBoardMod.Metric.TRAVEL_DISTANCE) / 100_000L
                + value(values, RankBoardMod.Metric.ELYTRA_DISTANCE) / 100_000L
                + value(values, RankBoardMod.Metric.JUMPS) / 10L;

        long max = Math.max(Math.max(combat, click), Math.max(farm, explore));
        if (max <= 0L) return Type.MIXED;
        if (combat == max) return Type.COMBAT;
        if (click == max) return Type.CLICK;
        if (farm == max) return Type.FARM;
        return Type.EXPLORE;
    }

    private static long value(Map<RankBoardMod.Metric, Long> values, RankBoardMod.Metric metric) {
        Long value = values.get(metric);
        return value == null ? 0L : value;
    }
}

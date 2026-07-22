package cn.bamgdam.rankboard;

import java.time.LocalDate;

/** Shared calendar-date ranges used by both mapped server implementations and the web UI. */
final class RankingDateRanges {
    private RankingDateRanges() { }

    static DateRange resolve(String period, LocalDate today, String customFrom, String customTo) {
        return switch (period) {
            case "day" -> endingToday(today, 1);
            case "week" -> endingToday(today, 7);
            case "month" -> endingToday(today, 30);
            case "quarter" -> endingToday(today, 90);
            case "year" -> endingToday(today, 365);
            case "all" -> new DateRange(today, today);
            case "custom" -> {
                LocalDate from = LocalDate.parse(customFrom == null || customFrom.isBlank()
                        ? today.toString() : customFrom);
                LocalDate to = LocalDate.parse(customTo == null || customTo.isBlank()
                        ? from.toString() : customTo);
                yield new DateRange(from, to);
            }
            default -> throw new IllegalArgumentException("未知时间范围：" + period);
        };
    }

    private static DateRange endingToday(LocalDate today, int inclusiveDays) {
        return new DateRange(today.minusDays(inclusiveDays - 1L), today);
    }

    record DateRange(LocalDate from, LocalDate to) { }
}

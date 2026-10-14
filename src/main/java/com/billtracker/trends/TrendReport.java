package com.billtracker.trends;

import com.billtracker.bill.UtilityType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Monthly cost trend for one user. {@code months} is contiguous and oldest-first;
 * every series has one value per month (zero when there was no bill).
 */
public record TrendReport(
        List<String> months,
        List<Series> series,
        List<BigDecimal> totals,
        Summary summary,
        List<TypeBreakdown> byType) {

    public boolean isEmpty() {
        return months.isEmpty();
    }

    public record Series(UtilityType type, String label, String color, List<BigDecimal> values) {
    }

    /** Percentages are null when there is nothing to compare against. */
    public record Summary(
            String latestMonth,
            BigDecimal latestMonthTotal,
            BigDecimal previousMonthTotal,
            BigDecimal monthOverMonthPct,
            BigDecimal sameMonthLastYearTotal,
            BigDecimal yearOverYearPct,
            BigDecimal windowTotal,
            BigDecimal monthlyAverage,
            int billCount,
            int occupants,
            BigDecimal costPerDay,
            BigDecimal perPersonMonthly) {

        public LocalDate latestMonthStart() {
            return YearMonth.parse(latestMonth).atDay(1);
        }
    }

    public record TypeBreakdown(
            UtilityType type,
            String label,
            String color,
            BigDecimal total,
            BigDecimal monthlyAverage,
            BigDecimal totalUsage,
            String usageUnit,
            BigDecimal averageUnitCost,
            BigDecimal usagePerPersonPerDay) {
    }
}

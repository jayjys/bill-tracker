package com.billtracker.trends;

import com.billtracker.bill.Bill;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.UtilityType;
import com.billtracker.household.Household;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TrendService {

    private final BillRepository bills;

    public TrendService(BillRepository bills) {
        this.bills = bills;
    }

    @Transactional(readOnly = true)
    public TrendReport monthly(Household household, int months) {
        return compute(bills.findByHouseholdIdOrderByCreatedAtDesc(household.getId()), months,
                household.getOccupants());
    }

    /**
     * Builds the report in memory. A household has a few bills a month, so even
     * years of history is a few hundred rows — simpler and DB-agnostic vs. SQL date bucketing.
     *
     * <p>The window ends at the latest month with a bill rather than today, so
     * a backfilled history still shows up.
     */
    static TrendReport compute(List<Bill> all, int windowMonths, int occupants) {
        List<Bill> priced = all.stream().filter(b -> b.getAmount() != null).toList();
        if (priced.isEmpty() || windowMonths < 1) {
            return new TrendReport(List.of(), List.of(), List.of(), null, List.of());
        }

        TreeMap<YearMonth, List<Bill>> byMonth = priced.stream()
                .collect(Collectors.groupingBy(b -> YearMonth.from(b.getStatementDate()), TreeMap::new,
                        Collectors.toList()));
        YearMonth latest = byMonth.lastKey();
        YearMonth first = latest.minusMonths(windowMonths - 1L);

        List<YearMonth> window = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(latest); m = m.plusMonths(1)) {
            window.add(m);
        }
        List<Bill> inWindow = priced.stream()
                .filter(b -> !YearMonth.from(b.getStatementDate()).isBefore(first))
                .toList();

        Map<UtilityType, Map<YearMonth, BigDecimal>> costByTypeMonth = new EnumMap<>(UtilityType.class);
        for (Bill b : inWindow) {
            costByTypeMonth.computeIfAbsent(b.getUtilityType(), t -> new TreeMap<>())
                    .merge(YearMonth.from(b.getStatementDate()), b.getAmount(), BigDecimal::add);
        }

        List<TrendReport.Series> series = costByTypeMonth.entrySet().stream()
                .map(e -> new TrendReport.Series(e.getKey(), e.getKey().label(), e.getKey().color(),
                        window.stream().map(m -> e.getValue().getOrDefault(m, BigDecimal.ZERO)).toList()))
                .toList();

        List<BigDecimal> totals = window.stream().map(m -> monthTotal(byMonth, m)).toList();
        BigDecimal windowTotal = sum(totals);
        long activeMonths = window.stream().filter(byMonth::containsKey).count();

        BigDecimal latestTotal = monthTotal(byMonth, latest);
        BigDecimal prevTotal = byMonth.containsKey(latest.minusMonths(1)) ? monthTotal(byMonth, latest.minusMonths(1)) : null;
        BigDecimal lastYearTotal = byMonth.containsKey(latest.minusYears(1)) ? monthTotal(byMonth, latest.minusYears(1)) : null;

        int people = Math.max(1, occupants);
        BigDecimal monthlyAverage = divide(windowTotal, BigDecimal.valueOf(activeMonths), 2);
        TrendReport.Summary summary = new TrendReport.Summary(
                latest.toString(),
                latestTotal,
                prevTotal,
                pctChange(prevTotal, latestTotal),
                lastYearTotal,
                pctChange(lastYearTotal, latestTotal),
                windowTotal,
                monthlyAverage,
                inWindow.size(),
                people,
                // Average month → per day: × 12 months / 365.25 days.
                divide(monthlyAverage.multiply(BigDecimal.valueOf(12)), new BigDecimal("365.25"), 2),
                divide(monthlyAverage, BigDecimal.valueOf(people), 2));

        return new TrendReport(window.stream().map(YearMonth::toString).toList(), series, totals, summary,
                breakdown(inWindow, activeMonths, people));
    }

    private static List<TrendReport.TypeBreakdown> breakdown(List<Bill> inWindow, long activeMonths, int people) {
        Map<UtilityType, List<Bill>> byType = inWindow.stream()
                .collect(Collectors.groupingBy(Bill::getUtilityType, () -> new EnumMap<>(UtilityType.class),
                        Collectors.toList()));
        return byType.entrySet().stream()
                .map(e -> {
                    List<Bill> list = e.getValue();
                    BigDecimal total = sum(list.stream().map(Bill::getAmount).toList());
                    // Unit cost only makes sense within one unit, so use the type's most common unit.
                    String unit = list.stream().map(Bill::getUsageUnit).filter(Objects::nonNull)
                            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                            .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                            .orElse(null);
                    List<Bill> metered = list.stream()
                            .filter(b -> unit != null && unit.equals(b.getUsageUnit()) && b.getUnitCost() != null)
                            .toList();
                    BigDecimal usage = metered.isEmpty() ? null
                            : sum(metered.stream().map(Bill::getUsageQuantity).toList());
                    BigDecimal unitCost = usage == null ? null
                            : divide(sum(metered.stream().map(Bill::getAmount).toList()), usage, 4);
                    int meteredDays = metered.stream().mapToInt(Bill::getEffectiveDays).sum();
                    BigDecimal perPersonPerDay = usage == null || meteredDays == 0 ? null
                            : divide(usage, BigDecimal.valueOf((long) meteredDays * people), 1);
                    return new TrendReport.TypeBreakdown(e.getKey(), e.getKey().label(), e.getKey().color(), total,
                            divide(total, BigDecimal.valueOf(activeMonths), 2), usage, unit, unitCost,
                            perPersonPerDay);
                })
                .sorted(Comparator.comparing(TrendReport.TypeBreakdown::total).reversed())
                .toList();
    }

    private static BigDecimal monthTotal(Map<YearMonth, List<Bill>> byMonth, YearMonth month) {
        return sum(byMonth.getOrDefault(month, List.of()).stream().map(Bill::getAmount).toList());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal divide(BigDecimal a, BigDecimal b, int scale) {
        return b.signum() == 0 ? null : a.divide(b, scale, RoundingMode.HALF_UP);
    }

    /** Percent change from {@code before} to {@code after}, one decimal place. */
    static BigDecimal pctChange(BigDecimal before, BigDecimal after) {
        if (before == null || after == null || before.signum() == 0) {
            return null;
        }
        return after.subtract(before).multiply(BigDecimal.valueOf(100)).divide(before, 1, RoundingMode.HALF_UP);
    }
}

package com.billtracker.trends;

import com.billtracker.bill.Bill;
import com.billtracker.TestBills;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendServiceTest {

    @Test
    void emptyHistoryGivesEmptyReport() {
        TrendReport report = TrendService.compute(List.of(), 12, 1);
        assertThat(report.isEmpty()).isTrue();
        assertThat(report.summary()).isNull();
    }

    @Test
    void bucketsBillsByMonthAndFillsGapsWithZero() {
        List<Bill> bills = List.of(
                bill(UtilityType.ELECTRIC, "100.00", LocalDate.of(2026, 7, 28), "400", "kWh"),
                bill(UtilityType.GAS, "40.00", LocalDate.of(2026, 7, 10), null, null),
                // August has no bills.
                bill(UtilityType.ELECTRIC, "120.00", LocalDate.of(2026, 9, 27), "400", "kWh"));

        TrendReport report = TrendService.compute(bills, 3, 1);

        assertThat(report.months()).containsExactly("2026-07", "2026-08", "2026-09");
        assertThat(report.totals()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("140"), BigDecimal.ZERO, new BigDecimal("120"));
        assertThat(report.series()).extracting(TrendReport.Series::type)
                .containsExactly(UtilityType.ELECTRIC, UtilityType.GAS);
        assertThat(report.series().get(1).values()).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("40"), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void summaryComparesLatestMonthToPreviousMonthAndLastYear() {
        List<Bill> bills = List.of(
                bill(UtilityType.ELECTRIC, "80.00", LocalDate.of(2025, 9, 30), null, null),
                bill(UtilityType.ELECTRIC, "100.00", LocalDate.of(2026, 8, 30), null, null),
                bill(UtilityType.ELECTRIC, "110.00", LocalDate.of(2026, 9, 30), null, null));

        TrendReport.Summary s = TrendService.compute(bills, 12, 1).summary();

        assertThat(s.latestMonth()).isEqualTo("2026-09");
        assertThat(s.latestMonthTotal()).isEqualByComparingTo("110");
        assertThat(s.monthOverMonthPct()).isEqualByComparingTo("10.0");
        assertThat(s.sameMonthLastYearTotal()).isEqualByComparingTo("80");
        assertThat(s.yearOverYearPct()).isEqualByComparingTo("37.5");
        // The 2025-09 bill is 12 months back, outside a 12-month window ending 2026-09.
        assertThat(s.windowTotal()).isEqualByComparingTo("210");
        assertThat(s.monthlyAverage()).isEqualByComparingTo("105");
        assertThat(s.billCount()).isEqualTo(2);
    }

    @Test
    void unitCostUsesTheDominantUnitOnly() {
        List<Bill> bills = List.of(
                bill(UtilityType.WATER, "60.00", LocalDate.of(2026, 8, 1), "3000", "gallons"),
                bill(UtilityType.WATER, "30.00", LocalDate.of(2026, 9, 1), "1000", "gallons"),
                bill(UtilityType.WATER, "50.00", LocalDate.of(2026, 9, 2), "5", "HCF"));

        TrendReport.TypeBreakdown water = TrendService.compute(bills, 12, 1).byType().getFirst();

        assertThat(water.usageUnit()).isEqualTo("gallons");
        assertThat(water.totalUsage()).isEqualByComparingTo("4000");
        assertThat(water.averageUnitCost()).isEqualByComparingTo("0.0225");
        assertThat(water.total()).isEqualByComparingTo("140");
    }

    @Test
    void billsWithoutAnAmountAreIgnored() {
        TrendReport report = TrendService.compute(
                List.of(bill(UtilityType.ELECTRIC, null, LocalDate.of(2026, 9, 1), null, null)), 12, 1);
        assertThat(report.isEmpty()).isTrue();
    }

    @Test
    void percentChangeHandlesZeroAndNull() {
        assertThat(TrendService.pctChange(BigDecimal.ZERO, BigDecimal.TEN)).isNull();
        assertThat(TrendService.pctChange(null, BigDecimal.TEN)).isNull();
        assertThat(TrendService.pctChange(new BigDecimal("200"), new BigDecimal("150"))).isEqualByComparingTo("-25.0");
    }

    private final TestBills fixtures = new TestBills();
    private final java.util.Map<UtilityType, UtilityAccount> accounts = new java.util.EnumMap<>(UtilityType.class);

    private Bill bill(UtilityType type, String amount, LocalDate periodEnd, String usage, String unit) {
        UtilityAccount account = accounts.computeIfAbsent(type, t -> fixtures.account("Test", t));
        return TestBills.bill(account, amount, null, periodEnd, usage == null ? null : Double.valueOf(usage), unit);
    }

    @Test
    void normalisesPerDayAndPerPerson() {
        List<Bill> bills = List.of(
                bill(UtilityType.ELECTRIC, "120.00", LocalDate.of(2026, 8, 30), "600", "kWh"),
                bill(UtilityType.ELECTRIC, "90.00", LocalDate.of(2026, 9, 30), "450", "kWh"));

        TrendReport report = TrendService.compute(bills, 12, 3);

        assertThat(report.summary().monthlyAverage()).isEqualByComparingTo("105");
        assertThat(report.summary().perPersonMonthly()).isEqualByComparingTo("35");
        // 105 × 12 / 365.25
        assertThat(report.summary().costPerDay()).isEqualByComparingTo("3.45");
        // 1050 kWh over 2 bills × 30 days (period unknown) × 3 people
        assertThat(report.byType().getFirst().usagePerPersonPerDay()).isEqualByComparingTo("5.8");
    }
}

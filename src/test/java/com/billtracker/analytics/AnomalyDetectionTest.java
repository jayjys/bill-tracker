package com.billtracker.analytics;

import com.billtracker.TestBills;
import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertKind;
import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectionTest {

    private final SyntheticHousehold home = new SyntheticHousehold();

    @Test
    void flagsAWaterSpikeAsAPossibleLeak() {
        UtilityAccount water = home.account("City Water", UtilityType.WATER);
        List<Bill> bills = home.meteredBills(water, 14, 0.4, "gallons", i -> i == 13 ? 1.8 : 1.0);

        List<AnomalyService.Finding> findings = AnomalyService.detect(bills, home.temps);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(AlertKind.POSSIBLE_LEAK);
            assertThat(f.bill()).isSameAs(bills.getLast());
            assertThat(f.score()).isGreaterThan(3.5);
            assertThat(f.detail()).contains("leak");
        });
    }

    @Test
    void seasonalSwingsExplainedByWeatherAreNotFlagged() {
        // Summer AC makes some bills 2-3x others; the weather model expects that.
        UtilityAccount electric = home.account("ComEd", UtilityType.ELECTRIC);
        List<Bill> bills = home.meteredBills(electric, 24, 0.3, "kWh");

        assertThat(AnomalyService.detect(bills, home.temps)).isEmpty();
    }

    @Test
    void withoutWeatherSeasonalSwingsAreComparedToLastYearNotLastMonth() {
        UtilityAccount electric = home.account("ComEd", UtilityType.ELECTRIC);
        UtilityAccount gas = home.account("Peoples Gas", UtilityType.GAS);
        List<Bill> bills = new ArrayList<>(home.meteredBills(electric, 24, 0.3, "kWh"));
        bills.addAll(home.meteredBills(gas, 8, 0.3, "therms")); // < 1 year: no seasonal reference

        assertThat(AnomalyService.detect(bills, Map.of())).isEmpty();
    }

    @Test
    void withoutWeatherALeakIsStillCaughtAgainstLastYear() {
        UtilityAccount water = home.account("City Water", UtilityType.WATER);
        // Without weather and with only one year of overlap the detector assumes ±20% is normal,
        // so it needs a bigger jump than the weather-adjusted model does (1.8× above).
        List<Bill> bills = home.meteredBills(water, 14, 0.4, "gallons", i -> i == 13 ? 2.2 : 1.0);

        assertThat(AnomalyService.detect(bills, Map.of())).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(AlertKind.POSSIBLE_LEAK);
            assertThat(f.detail()).contains("same period last year");
        });
    }

    @Test
    void electricSpikeIsAUsageSpikeWithSeverityByScore() {
        UtilityAccount electric = home.account("ComEd", UtilityType.ELECTRIC);
        List<Bill> bills = home.meteredBills(electric, 18, 0.3, "kWh", i -> i == 17 ? 2.5 : 1.0);

        assertThat(AnomalyService.detect(bills, home.temps)).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(AlertKind.USAGE_SPIKE);
            assertThat(f.severity()).isEqualTo(Alert.Severity.CRITICAL);
        });
    }

    @Test
    void detectsARateIncreaseWithNormalUsage() {
        UtilityAccount gas = home.account("Peoples Gas", UtilityType.GAS);
        List<Bill> bills = new ArrayList<>();
        LocalDate end = LocalDate.of(2026, 9, 20);
        for (int i = 0; i < 8; i++) {
            LocalDate e = end.minusMonths(7 - i);
            String amount = i == 7 ? "78.00" : "60.00"; // +30% for the same 60 therms
            bills.add(TestBills.bill(gas, amount, e.minusMonths(1).plusDays(1), e, 60.0, "therms"));
        }

        assertThat(AnomalyService.detect(bills, Map.of())).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(AlertKind.RATE_INCREASE);
            assertThat(f.title()).contains("30%");
        });
    }

    @Test
    void dueSoonRemindersOnlyForTheNextFewDays() {
        UtilityAccount gas = home.account("Peoples Gas", UtilityType.GAS);
        LocalDate today = LocalDate.of(2026, 10, 1);
        Bill soon = TestBills.bill(gas, "50.00", null, LocalDate.of(2026, 9, 20), null, null);
        soon.setDueDate(today.plusDays(3));
        Bill later = TestBills.bill(gas, "50.00", null, LocalDate.of(2026, 9, 20), null, null);
        later.setDueDate(today.plusDays(20));

        assertThat(AnomalyService.dueSoon(List.of(soon, later), today)).singleElement()
                .extracting(AnomalyService.Finding::bill).isSameAs(soon);
    }
}

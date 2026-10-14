package com.billtracker.analytics;

import com.billtracker.analytics.forecast.AccountForecast;
import com.billtracker.analytics.forecast.BillModel;
import com.billtracker.analytics.forecast.ForecastService;
import com.billtracker.analytics.forecast.HouseholdForecast;
import com.billtracker.analytics.forecast.LinearModel;
import com.billtracker.analytics.forecast.Observation;
import com.billtracker.bill.Bill;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ForecastTest {

    private final SyntheticHousehold home = new SyntheticHousehold();
    private final UtilityAccount electric = home.account("ComEd", UtilityType.ELECTRIC);

    @Test
    void weatherRegressionRecoversTheTrueUsageModel() {
        List<Bill> bills = home.meteredBills(electric, 24, 0.3, "kWh");
        List<Observation> obs = bills.stream().map(b -> Observation.of(b, home.temps)).toList();

        BillModel model = BillModel.fit(obs).orElseThrow();

        assertThat(model.method()).isEqualTo(BillModel.Method.WEATHER_REGRESSION);
        LinearModel usage = model.usageRegression().orElseThrow();
        assertThat(usage.intercept()).isCloseTo(SyntheticHousehold.BASE, within(1.0));
        assertThat(usage.coefficient(0)).isCloseTo(SyntheticHousehold.PER_HDD, within(0.08));
        assertThat(usage.coefficient(1)).isCloseTo(SyntheticHousehold.PER_CDD, within(0.3));
        assertThat(usage.r2()).isGreaterThan(0.95);
        // Cost model separates the fixed charge from the per-kWh rate.
        LinearModel cost = model.costRegression().orElseThrow();
        assertThat(cost.intercept()).isCloseTo(SyntheticHousehold.FIXED, within(1.0));
        assertThat(cost.coefficient(0)).isCloseTo(SyntheticHousehold.RATE, within(0.002));
    }

    @Test
    void forecastIsCalibratedAndBeatsTheNaiveBaselineOnSeasonalData() {
        List<Bill> bills = new ArrayList<>(home.meteredBills(electric, 24, 0.3, "kWh"));

        HouseholdForecast forecast = ForecastService.forecast(bills, home.temps, 2);

        AccountForecast f = forecast.accounts().getFirst();
        assertThat(f.method()).isEqualTo(BillModel.Method.WEATHER_REGRESSION.label());
        assertThat(f.low()).isLessThan(f.predictedAmount());
        assertThat(f.high()).isGreaterThan(f.predictedAmount());
        assertThat(f.periodStart()).isEqualTo(home.lastEnd.plusDays(1));
        assertThat(f.backtestMape()).isNotNull().isLessThan(f.naiveMape());
        assertThat(f.backtestMape()).isLessThan(5.0);
        assertThat(forecast.perPerson()).isEqualByComparingTo(forecast.total().divide(java.math.BigDecimal.valueOf(2)));
    }

    @Test
    void fallsBackToRecentMedianWithoutWeather() {
        List<Bill> bills = home.meteredBills(electric, 8, 0.3, "kWh");

        AccountForecast f = ForecastService.forecast(bills, Map.of(), 1).accounts().getFirst();

        assertThat(f.method()).isEqualTo(BillModel.Method.RECENT_MEDIAN.label());
        assertThat(f.predictedAmount().doubleValue()).isPositive();
    }

    @Test
    void withAYearOfHistoryButNoWeatherUsesTheSamePeriodLastYear() {
        List<Bill> bills = home.meteredBills(electric, 24, 0.3, "kWh");

        AccountForecast f = ForecastService.forecast(bills, Map.of(), 1).accounts().getFirst();

        assertThat(f.method()).isEqualTo(BillModel.Method.SEASONAL_NAIVE.label());
        // Strong seasonality: last year's same month beats "same as last month".
        assertThat(f.backtestMape()).isLessThan(f.naiveMape());
    }

    @Test
    void billsFlaggedAsAnomaliesDontInflateTheForecast() {
        UtilityAccount water = home.account("City Water", UtilityType.WATER);
        List<Bill> withLeak = home.meteredBills(water, 24, 0.3, "gallons", i -> i == 23 ? 2.0 : 1.0);
        List<Bill> clean = new SyntheticHousehold().meteredBills(water, 24, 0.3, "gallons");
        Bill leak = withLeak.getLast();

        double truth = ForecastService.forecast(clean, home.temps, 1).accounts().getFirst()
                .predictedAmount().doubleValue();
        double contaminated = ForecastService.forecast(withLeak, home.temps, 1).accounts().getFirst()
                .predictedAmount().doubleValue();
        AccountForecast excluded = ForecastService.forecast(withLeak, home.temps, 1, java.util.Set.of(leak.getId()))
                .accounts().getFirst();

        assertThat(Math.abs(excluded.predictedAmount().doubleValue() - truth))
                .isLessThan(Math.abs(contaminated - truth));
        // The next period still starts after the excluded bill.
        assertThat(excluded.periodStart()).isEqualTo(leak.getPeriodEnd().plusDays(1));
    }

    @Test
    void unmeteredServicesUseTheRecentCharge() {
        UtilityAccount internet = home.account("Xfinity", UtilityType.INTERNET);
        List<Bill> bills = home.flatBills(internet, 6, 65);

        AccountForecast f = ForecastService.forecast(bills, home.temps, 1).accounts().getFirst();

        assertThat(f.method()).isEqualTo(BillModel.Method.FLAT.label());
        assertThat(f.predictedAmount()).isEqualByComparingTo("65.00");
        assertThat(f.predictedUsage()).isNull();
    }

    @Test
    void skipsAccountsWithTooLittleHistory() {
        HouseholdForecast forecast = ForecastService.forecast(
                home.meteredBills(electric, 2, 0.3, "kWh"), home.temps, 1);
        assertThat(forecast.isEmpty()).isTrue();
        assertThat(forecast.skipped()).singleElement().asString().contains("needs 3+ bills");
    }
}

package com.billtracker.analytics.forecast;

import com.billtracker.analytics.weather.DegreeDays;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.Bill;

import java.time.LocalDate;
import java.util.Map;

/**
 * One bill as model input. {@code usage} is NaN for unmetered services (internet);
 * {@code weather} is null when the period or the weather data is unknown.
 */
public record Observation(long billId, LocalDate start, LocalDate end, int days, double usage, String unit,
                          double amount, DegreeDays weather) {

    public static Observation of(Bill b, Map<LocalDate, Double> weather) {
        DegreeDays dd = WeatherService.observed(weather, b.getPeriodStart(), b.getPeriodEnd()).orElse(null);
        return new Observation(
                b.getId() == null ? -1 : b.getId(),
                b.getPeriodStart(),
                b.getStatementDate(),
                b.getEffectiveDays(),
                b.getUsageQuantity() == null ? Double.NaN : b.getUsageQuantity().doubleValue(),
                b.getUsageUnit(),
                b.getAmount().doubleValue(),
                dd);
    }

    public boolean metered() {
        return !Double.isNaN(usage) && usage > 0;
    }

    public double usagePerDay() {
        return usage / days;
    }
}

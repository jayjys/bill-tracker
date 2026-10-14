package com.billtracker.analytics;

import com.billtracker.analytics.weather.DegreeDays;
import com.billtracker.analytics.weather.WeatherService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeatherMathTest {

    @Test
    void degreeDaysAgainst65F() {
        DegreeDays dd = DegreeDays.of(List.of(8.3, 28.3, 18.3), 3);
        assertThat(dd.hddPerDay()).isCloseTo(10 / 3.0, within(1e-9));
        assertThat(dd.cddPerDay()).isCloseTo(10 / 3.0, within(1e-9));
        assertThat(dd.coverage()).isEqualTo(1.0);
    }

    @Test
    void observedNeedsMostDaysOfThePeriod() {
        Map<LocalDate, Double> temps = new HashMap<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 20; i++) { // 20 of 31 days
            temps.put(start.plusDays(i), 0.0);
        }
        assertThat(WeatherService.observed(temps, start, LocalDate.of(2026, 1, 31))).isEmpty();
        assertThat(WeatherService.observed(temps, start, LocalDate.of(2026, 1, 20)))
                .hasValueSatisfying(dd -> assertThat(dd.hddPerDay()).isCloseTo(18.3, within(1e-9)));
    }

    @Test
    void seasonalNormalAveragesTheSameDatesInPreviousYears() {
        Map<LocalDate, Double> temps = new HashMap<>();
        // Same January 1–10 in 2024 at 0°C and in 2025 at 10°C.
        for (int i = 0; i < 10; i++) {
            temps.put(LocalDate.of(2024, 1, 1).plusDays(i), 0.0);
            temps.put(LocalDate.of(2025, 1, 1).plusDays(i), 10.0);
        }
        DegreeDays normal = WeatherService.normal(temps, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10), 3)
                .orElseThrow();
        assertThat(normal.hddPerDay()).isCloseTo((18.3 + 8.3) / 2, within(1e-9));
    }
}

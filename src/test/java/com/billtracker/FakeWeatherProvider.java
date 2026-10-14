package com.billtracker;

import com.billtracker.analytics.weather.WeatherProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Replaces Open-Meteo in Spring tests: deterministic, offline. "Nowhere" isn't found. */
@Component
@Primary
@Profile("test")
public class FakeWeatherProvider implements WeatherProvider {

    @Override
    public Optional<Place> geocode(String query) {
        if (query.toLowerCase().contains("nowhere")) {
            return Optional.empty();
        }
        return Optional.of(new Place(query + ", Testland", new BigDecimal("41.9"), new BigDecimal("-87.6")));
    }

    @Override
    public List<DailyTemp> dailyMeans(BigDecimal latitude, BigDecimal longitude, LocalDate from, LocalDate to) {
        List<DailyTemp> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(new DailyTemp(d, 11 - 13 * Math.cos(2 * Math.PI * (d.getDayOfYear() - 20) / 365.0)));
        }
        return out;
    }
}

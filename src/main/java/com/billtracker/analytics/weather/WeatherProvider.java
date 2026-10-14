package com.billtracker.analytics.weather;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Source of historical weather; the production implementation is {@link OpenMeteoClient}. */
public interface WeatherProvider {

    record Place(String name, BigDecimal latitude, BigDecimal longitude) {
    }

    record DailyTemp(LocalDate date, double meanC) {
    }

    Optional<Place> geocode(String query);

    List<DailyTemp> dailyMeans(BigDecimal latitude, BigDecimal longitude, LocalDate from, LocalDate to);

    class WeatherUnavailableException extends RuntimeException {
        public WeatherUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

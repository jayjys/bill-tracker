package com.billtracker.analytics.weather;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Daily mean temperature for a ~10 km grid cell. */
@Entity
@Table(name = "weather_day")
public class WeatherDay {

    @Embeddable
    public record Key(
            @Column(name = "location_key") String locationKey,
            @Column(name = "obs_date") LocalDate date) implements Serializable {
    }

    @EmbeddedId
    private Key key;

    @Column(name = "temp_mean_c", nullable = false)
    private BigDecimal tempMeanC;

    protected WeatherDay() {
    }

    public WeatherDay(String locationKey, LocalDate date, BigDecimal tempMeanC) {
        this.key = new Key(locationKey, date);
        this.tempMeanC = tempMeanC;
    }

    public LocalDate getDate() {
        return key.date();
    }

    public BigDecimal getTempMeanC() {
        return tempMeanC;
    }
}

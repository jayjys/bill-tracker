package com.billtracker.analytics.weather;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface WeatherDayRepository extends JpaRepository<WeatherDay, WeatherDay.Key> {

    @Query("select w from WeatherDay w where w.key.locationKey = :location and w.key.date between :from and :to")
    List<WeatherDay> findRange(String location, LocalDate from, LocalDate to);

    @Query("select max(w.key.date) from WeatherDay w where w.key.locationKey = :location")
    LocalDate latestDate(String location);

    @Query("select min(w.key.date) from WeatherDay w where w.key.locationKey = :location")
    LocalDate earliestDate(String location);
}

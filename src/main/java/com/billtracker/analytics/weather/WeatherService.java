package com.billtracker.analytics.weather;

import com.billtracker.household.Household;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Keeps a local copy of daily temperatures per location and turns them into degree days. */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    /** Reanalysis data lags real time by a few days. */
    private static final int ARCHIVE_LAG_DAYS = 6;
    private static final double MIN_COVERAGE = 0.8;

    private final WeatherProvider provider;
    private final WeatherDayRepository days;
    private final WeatherProperties props;

    public WeatherService(WeatherProvider provider, WeatherDayRepository days, WeatherProperties props) {
        this.provider = provider;
        this.days = days;
        this.props = props;
    }

    public Optional<WeatherProvider.Place> geocode(String query) {
        return provider.geocode(query);
    }

    /**
     * Fetches whatever is missing for the household's location: a few years of history
     * the first time (for seasonal normals), then just the new days. Returns days saved.
     */
    @Transactional
    public int sync(Household household) {
        if (!household.hasLocation()) {
            return 0;
        }
        String key = household.locationKey();
        LocalDate wantFrom = LocalDate.now().minusYears(props.historyYears()).withDayOfYear(1);
        LocalDate wantTo = LocalDate.now().minusDays(ARCHIVE_LAG_DAYS);
        LocalDate earliest = days.earliestDate(key);
        LocalDate latest = days.latestDate(key);

        int saved = 0;
        if (earliest == null) {
            saved += fetch(household, key, wantFrom, wantTo);
        } else {
            if (earliest.isAfter(wantFrom)) {
                saved += fetch(household, key, wantFrom, earliest.minusDays(1));
            }
            if (latest.isBefore(wantTo)) {
                saved += fetch(household, key, latest.plusDays(1), wantTo);
            }
        }
        if (saved > 0) {
            log.info("Saved {} weather days for {}", saved, key);
        }
        return saved;
    }

    private int fetch(Household h, String key, LocalDate from, LocalDate to) {
        int saved = 0;
        // One request per year keeps responses small and lets a partial sync make progress.
        for (LocalDate start = from; !start.isAfter(to); start = start.plusYears(1)) {
            LocalDate end = start.plusYears(1).minusDays(1).isAfter(to) ? to : start.plusYears(1).minusDays(1);
            List<WeatherDay> batch = new ArrayList<>();
            for (WeatherProvider.DailyTemp t : provider.dailyMeans(h.getLatitude(), h.getLongitude(), start, end)) {
                batch.add(new WeatherDay(key, t.date(), BigDecimal.valueOf(t.meanC()).setScale(2, RoundingMode.HALF_UP)));
            }
            days.saveAll(batch);
            saved += batch.size();
        }
        return saved;
    }

    /** Observed degree days over a period, or empty if too little data is stored. */
    @Transactional(readOnly = true)
    public Optional<DegreeDays> observed(String locationKey, LocalDate start, LocalDate end) {
        if (locationKey == null || start == null || end == null) {
            return Optional.empty();
        }
        return observed(load(locationKey, start, end), start, end);
    }

    /** A loaded slice of weather, so analytics over many bills hit the DB once. */
    public Map<LocalDate, Double> load(String locationKey, LocalDate from, LocalDate to) {
        if (locationKey == null) {
            return Map.of();
        }
        return days.findRange(locationKey, from, to).stream()
                .collect(Collectors.toMap(WeatherDay::getDate, d -> d.getTempMeanC().doubleValue()));
    }

    public static Optional<DegreeDays> observed(Map<LocalDate, Double> weather, LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            return Optional.empty();
        }
        List<Double> temps = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            Double t = weather.get(d);
            if (t != null) {
                temps.add(t);
            }
        }
        DegreeDays dd = DegreeDays.of(temps, ChronoUnit.DAYS.between(start, end) + 1);
        return dd.coverage() >= MIN_COVERAGE ? Optional.of(dd) : Optional.empty();
    }

    /**
     * Seasonal normal for a future period: the same calendar dates averaged over
     * previous years. Used to forecast next month's bill before its weather happens.
     */
    public static Optional<DegreeDays> normal(Map<LocalDate, Double> weather, LocalDate start, LocalDate end,
                                              int years) {
        double hdd = 0;
        double cdd = 0;
        int found = 0;
        for (int y = 1; y <= years; y++) {
            Optional<DegreeDays> dd = observed(weather, start.minusYears(y), end.minusYears(y));
            if (dd.isPresent()) {
                hdd += dd.get().hddPerDay();
                cdd += dd.get().cddPerDay();
                found++;
            }
        }
        return found == 0 ? Optional.empty() : Optional.of(new DegreeDays(hdd / found, cdd / found, 1.0));
    }
}

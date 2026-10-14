package com.billtracker.analytics.forecast;

import com.billtracker.analytics.weather.DegreeDays;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Predicts a bill in two stages, mirroring how utilities actually charge:
 * <ol>
 *   <li><b>Usage per day</b>, by the best method the history supports:
 *     <ul>
 *       <li>{@link Method#WEATHER_REGRESSION}: {@code usage/day = a + b·HDD/day + c·CDD/day}
 *           (ridge regression), with at least {@value #MIN_WEATHER_BILLS} weather-matched bills;</li>
 *       <li>{@link Method#SEASONAL_NAIVE}: the same period a year earlier, when there's no weather but
 *           there is a year of history — heating and cooling make "last month" a poor guess;</li>
 *       <li>{@link Method#RECENT_MEDIAN}: the median of recent bills, as a last resort.</li>
 *     </ul></li>
 *   <li><b>Cost</b> from usage: {@code $ = fixed + rate·usage} (OLS on recent bills), which
 *       captures fixed monthly charges that a plain $/unit would miss.</li>
 * </ol>
 * Unmetered services (internet, phone) are {@link Method#FLAT}: the median recent amount.
 */
public final class BillModel {

    static final int MIN_WEATHER_BILLS = 6;
    static final int MIN_BILLS = 3;
    private static final double RIDGE = 1.0;
    private static final int RECENT = 12;
    /** How far from "exactly a year earlier" a bill may end and still count as the same season. */
    private static final int SEASON_TOLERANCE_DAYS = 20;

    public enum Method {
        WEATHER_REGRESSION("Weather-adjusted regression"),
        SEASONAL_NAIVE("Same period last year"),
        RECENT_MEDIAN("Median of recent bills"),
        FLAT("Flat recurring charge");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final List<Observation> metered;  // same unit, time order
    private final LinearModel usageModel;     // null unless weather regression is possible
    private final double regressionScale;     // robust spread of regression residuals (usage/day)
    private final double medianUsagePerDay;
    private final double medianScale;         // robust spread around the recent median
    private final double seasonalScale;       // robust spread of year-over-year residuals
    private final LinearModel costModel;      // null → unit cost
    private final double unitCost;
    private final boolean flat;
    private final double flatAmount;
    private final double flatSpread;

    private BillModel(List<Observation> metered, LinearModel usageModel, double regressionScale,
                      double medianUsagePerDay, double medianScale, double seasonalScale, LinearModel costModel,
                      double unitCost, boolean flat, double flatAmount, double flatSpread) {
        this.metered = metered;
        this.usageModel = usageModel;
        this.regressionScale = regressionScale;
        this.medianUsagePerDay = medianUsagePerDay;
        this.medianScale = medianScale;
        this.seasonalScale = seasonalScale;
        this.costModel = costModel;
        this.unitCost = unitCost;
        this.flat = flat;
        this.flatAmount = flatAmount;
        this.flatSpread = flatSpread;
    }

    /** Fits on observations in time order. Empty when there's too little history. */
    public static Optional<BillModel> fit(List<Observation> history) {
        if (history.size() < MIN_BILLS) {
            return Optional.empty();
        }
        List<Observation> metered = history.stream().filter(Observation::metered).toList();
        if (metered.size() < MIN_BILLS) {
            double[] recent = tail(history, 3).stream().mapToDouble(Observation::amount).toArray();
            double[] six = tail(history, 6).stream().mapToDouble(Observation::amount).toArray();
            double spread = (Arrays.stream(six).max().orElse(0) - Arrays.stream(six).min().orElse(0)) / 2;
            return Optional.of(new BillModel(List.of(), null, 0, 0, 0, 0, null, 0, true, median(recent), spread));
        }

        // Mixing units (gallons vs HCF) would corrupt the fit; use the latest unit only.
        String unit = metered.getLast().unit();
        metered = metered.stream().filter(o -> Objects.equals(o.unit(), unit)).toList();
        if (metered.size() < MIN_BILLS) {
            return Optional.empty();
        }

        double medianPerDay = median(tail(metered, 6).stream().mapToDouble(Observation::usagePerDay).toArray());
        double medianScale = robustScale(
                metered.stream().mapToDouble(o -> o.usagePerDay() - medianPerDay).toArray(), medianPerDay);

        LinearModel usage = null;
        double regressionScale = 0;
        List<Observation> withWeather = metered.stream().filter(o -> o.weather() != null).toList();
        if (withWeather.size() >= MIN_WEATHER_BILLS) {
            double[][] x = withWeather.stream().map(o -> o.weather().features()).toArray(double[][]::new);
            double[] y = withWeather.stream().mapToDouble(Observation::usagePerDay).toArray();
            try {
                LinearModel m = LinearModel.fit(x, y, RIDGE);
                usage = m;
                regressionScale = robustScale(withWeather.stream()
                        .mapToDouble(o -> o.usagePerDay() - m.predict(o.weather().features())).toArray(), medianPerDay);
            } catch (IllegalArgumentException e) {
                usage = null;
            }
        }

        // Year-over-year residuals: how much usage normally differs from the same season last year.
        List<Observation> m = metered;
        double[] yoy = metered.stream()
                .flatMap(o -> sameSeason(m, o.end()).stream().map(prev -> o.usagePerDay() - prev.usagePerDay()))
                .mapToDouble(Double::doubleValue).toArray();
        // With few year-over-year pairs, be conservative: assume ±20% is normal variation.
        double seasonalScale = yoy.length >= 4 ? robustScale(yoy, medianPerDay) : 0.20 * medianPerDay;

        List<Observation> recent = tail(metered, RECENT);
        LinearModel cost = null;
        double[] u = recent.stream().mapToDouble(Observation::usage).toArray();
        double[] amt = recent.stream().mapToDouble(Observation::amount).toArray();
        if (recent.size() >= 4 && variance(u) > 0) {
            LinearModel c = LinearModel.fit(Arrays.stream(u).mapToObj(v -> new double[] {v}).toArray(double[][]::new),
                    amt, 0);
            // A negative rate or large negative fixed charge means noise dominated; don't trust it.
            if (c.coefficient(0) > 0 && c.intercept() > -0.25 * median(amt)) {
                cost = c;
            }
        }
        double unitCost = median(tail(metered, 3).stream().mapToDouble(o -> o.amount() / o.usage()).toArray());
        return Optional.of(new BillModel(metered, usage, regressionScale, medianPerDay, medianScale, seasonalScale,
                cost, unitCost, false, 0, 0));
    }

    /** The method used for a bill ending on {@code end} with the given weather (null if unknown). */
    public Method methodFor(DegreeDays weather, LocalDate end) {
        if (flat) {
            return Method.FLAT;
        }
        if (usageModel != null && weather != null) {
            return Method.WEATHER_REGRESSION;
        }
        if (end != null && sameSeason(metered, end).isPresent()) {
            return Method.SEASONAL_NAIVE;
        }
        return Method.RECENT_MEDIAN;
    }

    /** The best method this history supports, given weather. */
    public Method method() {
        if (flat) {
            return Method.FLAT;
        }
        return usageModel != null ? Method.WEATHER_REGRESSION
                : methodFor(null, metered.getLast().end().plusMonths(1));
    }

    public boolean usesWeather() {
        return usageModel != null;
    }

    public double expectedUsagePerDay(DegreeDays weather, LocalDate end) {
        return switch (methodFor(weather, end)) {
            case WEATHER_REGRESSION -> Math.max(0, usageModel.predict(weather.features()));
            case SEASONAL_NAIVE -> sameSeason(metered, end).orElseThrow().usagePerDay();
            case RECENT_MEDIAN -> medianUsagePerDay;
            case FLAT -> Double.NaN;
        };
    }

    /** Robust standard deviation (1.4826 × MAD) of usage/day around the given method's prediction. */
    public double scalePerDay(Method method) {
        return switch (method) {
            case WEATHER_REGRESSION -> regressionScale;
            case SEASONAL_NAIVE -> seasonalScale;
            case RECENT_MEDIAN, FLAT -> medianScale;
        };
    }

    public double predictUsage(DegreeDays weather, int days, LocalDate end) {
        return flat ? Double.NaN : expectedUsagePerDay(weather, end) * days;
    }

    public double predictAmount(DegreeDays weather, int days, LocalDate end) {
        return flat ? flatAmount : costFor(predictUsage(weather, days, end));
    }

    public double costFor(double usage) {
        return costModel != null ? costModel.predict(new double[] {usage}) : unitCost * usage;
    }

    /** Half-width of an ~80% prediction interval for the amount. */
    public double amountHalfWidth(int days, Method method) {
        if (flat) {
            return flatSpread;
        }
        double rate = costModel != null ? costModel.coefficient(0) : unitCost;
        // A household's deviation from normal persists through the period, so per-day error scales with days.
        double usageSd = scalePerDay(method) * days;
        double costSd = costModel != null ? costModel.residualStd() : 0;
        return 1.28 * Math.sqrt(Math.pow(rate * usageSd, 2) + costSd * costSd);
    }

    public Optional<LinearModel> usageRegression() {
        return Optional.ofNullable(usageModel);
    }

    public Optional<LinearModel> costRegression() {
        return Optional.ofNullable(costModel);
    }

    // ---------------------------------------------------------------- helpers

    /** The earlier bill ending closest to one year before {@code end}, within the tolerance. */
    static Optional<Observation> sameSeason(List<Observation> history, LocalDate end) {
        LocalDate target = end.minusYears(1);
        return history.stream()
                .filter(o -> Math.abs(ChronoUnit.DAYS.between(o.end(), target)) <= SEASON_TOLERANCE_DAYS)
                .min(Comparator.comparingLong(o -> Math.abs(ChronoUnit.DAYS.between(o.end(), target))));
    }

    static double robustScale(double[] residuals, double typical) {
        if (residuals.length == 0) {
            return 0.25 * Math.abs(typical);
        }
        double med = median(residuals);
        double[] dev = Arrays.stream(residuals).map(r -> Math.abs(r - med)).toArray();
        double mad = 1.4826 * median(dev);
        // Floor at 5% of typical usage: with very regular history MAD can be ~0,
        // which would make every small wobble look like an anomaly.
        return Math.max(mad, 0.05 * Math.abs(typical));
    }

    public static double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] s = values.clone();
        Arrays.sort(s);
        int mid = s.length / 2;
        return s.length % 2 == 1 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
    }

    private static double variance(double[] v) {
        double mean = Arrays.stream(v).average().orElse(0);
        return Arrays.stream(v).map(x -> (x - mean) * (x - mean)).sum() / Math.max(1, v.length - 1);
    }

    static <T> List<T> tail(List<T> list, int n) {
        return list.subList(Math.max(0, list.size() - n), list.size());
    }

    static List<Observation> sorted(List<Observation> obs) {
        return obs.stream().sorted(Comparator.comparing(Observation::end)).toList();
    }
}

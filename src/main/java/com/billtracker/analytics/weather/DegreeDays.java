package com.billtracker.analytics.weather;

import java.util.Collection;

/**
 * Heating and cooling degree days per day over a period. A day at 5°C has 13.3 HDD
 * against the standard 18.3°C (65°F) base: roughly how hard the heating works.
 * {@code coverage} is the share of the period's days that had data.
 */
public record DegreeDays(double hddPerDay, double cddPerDay, double coverage) {

    public static final double BASE_C = 18.3;

    public static DegreeDays of(Collection<Double> dailyMeansC, long periodDays) {
        if (dailyMeansC.isEmpty() || periodDays <= 0) {
            return new DegreeDays(0, 0, 0);
        }
        double hdd = 0;
        double cdd = 0;
        for (double t : dailyMeansC) {
            hdd += Math.max(0, BASE_C - t);
            cdd += Math.max(0, t - BASE_C);
        }
        int n = dailyMeansC.size();
        return new DegreeDays(hdd / n, cdd / n, Math.min(1.0, n / (double) periodDays));
    }

    public double[] features() {
        return new double[] {hddPerDay, cddPerDay};
    }
}

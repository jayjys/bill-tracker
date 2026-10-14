package com.billtracker.analytics;

import com.billtracker.TestBills;
import com.billtracker.bill.Bill;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * Synthetic weather and bills where the true usage–weather relationship is known,
 * so tests can check the models recover it.
 */
public final class SyntheticHousehold {

    public static final double BASE = 10;
    public static final double PER_HDD = 0.5;
    public static final double PER_CDD = 2.0;
    public static final double FIXED = 12;
    public static final double RATE = 0.15;

    private final Random random = new Random(7);
    private final TestBills fixtures = new TestBills();
    public final Map<LocalDate, Double> temps = new HashMap<>();
    public final LocalDate lastEnd = LocalDate.now().minusDays(10);

    public SyntheticHousehold() {
        for (LocalDate d = LocalDate.now().minusYears(5); !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            double seasonal = 11 - 13 * Math.cos(2 * Math.PI * (d.getDayOfYear() - 20) / 365.0);
            temps.put(d, seasonal + random.nextGaussian() * 3);
        }
    }

    public UtilityAccount account(String provider, UtilityType type) {
        return fixtures.account(provider, type);
    }

    /** Monthly metered bills following the true model, with small noise in usage/day. */
    public List<Bill> meteredBills(UtilityAccount account, int months, double noisePerDay, String unit) {
        return meteredBills(account, months, noisePerDay, unit, i -> 1.0);
    }

    /** As above, with a usage multiplier per bill index (0 = oldest) to inject anomalies. */
    public List<Bill> meteredBills(UtilityAccount account, int months, double noisePerDay, String unit,
                                   DoubleUnaryOperator usageFactor) {
        List<Bill> bills = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            LocalDate end = lastEnd.minusMonths(months - 1L - i);
            LocalDate start = end.minusMonths(1).plusDays(1);
            int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
            double hdd = 0;
            double cdd = 0;
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                double t = temps.get(d);
                hdd += Math.max(0, 18.3 - t);
                cdd += Math.max(0, t - 18.3);
            }
            double perDay = BASE + PER_HDD * hdd / days + PER_CDD * cdd / days + random.nextGaussian() * noisePerDay;
            double usage = Math.round(perDay * days * usageFactor.applyAsDouble(i));
            double amount = FIXED + RATE * usage;
            bills.add(TestBills.bill(account, String.format("%.2f", amount), start, end, usage, unit));
        }
        return bills;
    }

    public List<Bill> flatBills(UtilityAccount account, int months, double amount) {
        List<Bill> bills = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            LocalDate end = lastEnd.minusMonths(months - 1L - i);
            bills.add(TestBills.bill(account, String.format("%.2f", amount), end.minusMonths(1).plusDays(1), end,
                    null, null));
        }
        return bills;
    }
}

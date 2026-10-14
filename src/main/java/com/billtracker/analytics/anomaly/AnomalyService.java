package com.billtracker.analytics.anomaly;

import com.billtracker.analytics.forecast.BillModel;
import com.billtracker.analytics.forecast.Observation;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import com.billtracker.household.Household;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Flags bills that don't fit the household's own history.
 *
 * <p>For each bill, a {@link BillModel} is fitted on <em>earlier</em> bills only, so a
 * spike can't hide itself by pulling the model up. Usage per day is compared with the
 * model's expectation (weather-adjusted when possible) using a robust z-score:
 * {@code z = (actual − expected) / (1.4826 · MAD of past residuals)}. Median/MAD rather
 * than mean/std so one earlier spike doesn't mask the next.
 */
@Service
public class AnomalyService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyService.class);

    static final double Z_THRESHOLD = 3.5;
    /** Also require a practically meaningful jump, not just a statistically odd one. */
    static final double MIN_RATIO = 1.25;
    /** Against a plain recent median (no weather, no year-ago bill), only big jumps count. */
    static final double WEAK_BASELINE_MIN_RATIO = 1.5;
    static final double RATE_JUMP = 1.20;
    private static final int LOOKBACK_MONTHS = 12;
    private static final int DUE_SOON_DAYS = 5;
    private static final DateTimeFormatter DUE = DateTimeFormatter.ofPattern("MMM d");

    private final BillRepository bills;
    private final AlertRepository alerts;
    private final WeatherService weather;

    public AnomalyService(BillRepository bills, AlertRepository alerts, WeatherService weather) {
        this.bills = bills;
        this.alerts = alerts;
        this.weather = weather;
    }

    public record Finding(Bill bill, AlertKind kind, Alert.Severity severity, String title, String detail,
                          double observed, double expected, double score) {
    }

    /** Scans the household's recent bills and stores new alerts. Safe to re-run. Returns alerts created. */
    @Transactional
    public int scan(Household household) {
        List<Bill> all = bills.findByHouseholdIdOrderByCreatedAtDesc(household.getId()).stream()
                .filter(b -> b.getAmount() != null).toList();
        Map<LocalDate, Double> temps = weather.load(household.locationKey(),
                LocalDate.now().minusYears(6), LocalDate.now());
        List<Finding> findings = new ArrayList<>(detect(all, temps));
        findings.addAll(dueSoon(all, LocalDate.now()));

        int created = 0;
        for (Finding f : findings) {
            if (!alerts.existsByBill_IdAndKind(f.bill().getId(), f.kind())) {
                alerts.save(new Alert(f.bill(), f.kind(), f.severity(), f.title(), f.detail())
                        .withStats(bd(f.observed()), bd(f.expected()), bd(f.score())));
                created++;
            }
        }
        if (created > 0) {
            log.info("Created {} alert(s) for household {}", created, household.getId());
        }
        return created;
    }

    /** Pure detection over bills, for testing and for the scan above. */
    public static List<Finding> detect(List<Bill> all, Map<LocalDate, Double> temps) {
        Map<UtilityAccount, List<Bill>> byAccount = all.stream().collect(Collectors.groupingBy(Bill::getAccount));
        List<Finding> findings = new ArrayList<>();
        for (List<Bill> accountBills : byAccount.values()) {
            List<Bill> sorted = accountBills.stream().sorted(Comparator.comparing(Bill::getStatementDate)).toList();
            List<Observation> obs = sorted.stream().map(b -> Observation.of(b, temps)).toList();
            LocalDate cutoff = sorted.getLast().getStatementDate().minusMonths(LOOKBACK_MONTHS);
            for (int i = 0; i < obs.size(); i++) {
                if (sorted.get(i).getStatementDate().isBefore(cutoff)) {
                    continue;
                }
                Optional<BillModel> model = BillModel.fit(obs.subList(0, i));
                if (model.isEmpty() || model.get().method() == BillModel.Method.FLAT) {
                    continue;
                }
                usageSpike(sorted.get(i), obs.get(i), model.get()).ifPresent(findings::add);
                rateJump(sorted.get(i), obs.subList(0, i + 1)).ifPresent(findings::add);
            }
        }
        return findings;
    }

    static Optional<Finding> usageSpike(Bill bill, Observation o, BillModel model) {
        if (!o.metered()) {
            return Optional.empty();
        }
        BillModel.Method method = model.methodFor(o.weather(), o.end());
        boolean weatherSensitive = bill.getUtilityType() == UtilityType.ELECTRIC
                || bill.getUtilityType() == UtilityType.GAS;
        // Without weather or a year-ago bill, heating and cooling swings look like spikes.
        if (method == BillModel.Method.RECENT_MEDIAN && weatherSensitive) {
            return Optional.empty();
        }
        double minRatio = method == BillModel.Method.RECENT_MEDIAN ? WEAK_BASELINE_MIN_RATIO : MIN_RATIO;
        double expected = model.expectedUsagePerDay(o.weather(), o.end());
        double actual = o.usagePerDay();
        double z = (actual - expected) / model.scalePerDay(method);
        if (z < Z_THRESHOLD || expected <= 0 || actual < minRatio * expected) {
            return Optional.empty();
        }
        boolean water = bill.getUtilityType() == UtilityType.WATER;
        String unit = bill.getUsageUnit() == null ? "units" : bill.getUsageUnit();
        int pct = (int) Math.round(100 * (actual / expected - 1));
        String context = switch (method) {
            case WEATHER_REGRESSION -> "given the weather that period";
            case SEASONAL_NAIVE -> "based on the same period last year";
            default -> "based on your recent bills";
        };
        String detail = String.format(
                "Used %.1f %s/day vs. about %.1f expected %s (+%d%%, robust z = %.1f).",
                actual, unit, expected, context, pct, z)
                + (water ? " A sudden water jump is often a leak — check toilets, faucets and irrigation."
                         : " Worth checking for a new appliance, a heater left on, or a meter read error.");
        return Optional.of(new Finding(bill,
                water ? AlertKind.POSSIBLE_LEAK : AlertKind.USAGE_SPIKE,
                z >= 6 ? Alert.Severity.CRITICAL : Alert.Severity.WARNING,
                (water ? "Possible leak: " : "Usage spike: ") + bill.getProvider() + " " + pct + "% above normal",
                detail, actual, expected, z));
    }

    /** Price per unit up sharply while usage was ordinary — usually a rate change. */
    static Optional<Finding> rateJump(Bill bill, List<Observation> upToIncluding) {
        List<Observation> metered = upToIncluding.stream().filter(Observation::metered).toList();
        if (metered.size() < 4 || !upToIncluding.getLast().metered()) {
            return Optional.empty();
        }
        Observation cur = metered.getLast();
        List<Observation> prev = metered.subList(Math.max(0, metered.size() - 7), metered.size() - 1);
        double prevRate = BillModel.median(prev.stream().mapToDouble(o -> o.amount() / o.usage()).toArray());
        double prevUsage = BillModel.median(prev.stream().mapToDouble(Observation::usagePerDay).toArray());
        double rate = cur.amount() / cur.usage();
        boolean ordinaryUsage = Math.abs(cur.usagePerDay() / prevUsage - 1) < 0.25;
        if (!ordinaryUsage || rate < RATE_JUMP * prevRate) {
            return Optional.empty();
        }
        int pct = (int) Math.round(100 * (rate / prevRate - 1));
        String unit = bill.getUsageUnit() == null ? "unit" : bill.getUsageUnit();
        return Optional.of(new Finding(bill, AlertKind.RATE_INCREASE, Alert.Severity.INFO,
                "Rate increase: " + bill.getProvider() + " up " + pct + "% per " + unit,
                String.format("Paid $%.4f per %s vs. a recent median of $%.4f, with normal usage.", rate, unit,
                        prevRate),
                rate, prevRate, 0));
    }

    public static List<Finding> dueSoon(List<Bill> all, LocalDate today) {
        return all.stream()
                .filter(b -> b.getDueDate() != null && !b.getDueDate().isBefore(today)
                        && !b.getDueDate().isAfter(today.plusDays(DUE_SOON_DAYS)))
                .map(b -> new Finding(b, AlertKind.DUE_SOON, Alert.Severity.INFO,
                        b.getProvider() + " bill due " + DUE.format(b.getDueDate()),
                        String.format("$%.2f due on %s.", b.getAmount(), DUE.format(b.getDueDate())),
                        b.getAmount().doubleValue(), 0, 0))
                .toList();
    }

    private static BigDecimal bd(double v) {
        return Double.isFinite(v) ? BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP) : null;
    }
}

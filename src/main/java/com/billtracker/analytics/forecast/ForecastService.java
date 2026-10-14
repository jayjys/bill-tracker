package com.billtracker.analytics.forecast;

import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertKind;
import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.analytics.weather.DegreeDays;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.household.Household;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ForecastService {

    private static final int NORMAL_YEARS = 3;
    private static final int BACKTEST_BILLS = 6;

    private final BillRepository bills;
    private final WeatherService weather;
    private final AlertRepository alerts;

    public ForecastService(BillRepository bills, WeatherService weather, AlertRepository alerts) {
        this.bills = bills;
        this.weather = weather;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public HouseholdForecast forecast(Household household) {
        List<Bill> all = bills.findByHouseholdIdOrderByCreatedAtDesc(household.getId());
        Map<LocalDate, Double> temps = weather.load(household.locationKey(),
                LocalDate.now().minusYears(NORMAL_YEARS + 3), LocalDate.now());
        // A leak or spike shouldn't teach the model that next month will be high too. Dismissing
        // the alert ("this was normal") puts the bill back into training.
        Set<Long> anomalous = alerts.openAlerts(household.getId()).stream()
                .filter(a -> a.getKind() == AlertKind.USAGE_SPIKE || a.getKind() == AlertKind.POSSIBLE_LEAK)
                .map(Alert::getBillId)
                .collect(Collectors.toSet());
        return forecast(all, temps, household.getOccupants(), anomalous);
    }

    public static HouseholdForecast forecast(List<Bill> all, Map<LocalDate, Double> temps, int occupants) {
        return forecast(all, temps, occupants, Set.of());
    }

    /** DB-free core, also used directly by tests. Bills in {@code excluded} aren't used for fitting. */
    public static HouseholdForecast forecast(List<Bill> all, Map<LocalDate, Double> temps, int occupants,
                                             Set<Long> excluded) {
        Map<UtilityAccount, List<Bill>> byAccount = all.stream()
                .filter(b -> b.getAmount() != null)
                .collect(Collectors.groupingBy(Bill::getAccount, LinkedHashMap::new, Collectors.toList()));

        List<AccountForecast> forecasts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<UtilityAccount, List<Bill>> e : byAccount.entrySet()) {
            List<Observation> all0 = BillModel.sorted(e.getValue().stream().map(b -> Observation.of(b, temps)).toList());
            // Accounts with no bill in the last ~4 months are closed or moved away from.
            if (all0.isEmpty() || all0.getLast().end().isBefore(LocalDate.now().minusMonths(4))) {
                continue;
            }
            List<Observation> obs = all0.stream().filter(o -> !excluded.contains(o.billId())).toList();
            Optional<AccountForecast> f = obs.isEmpty() ? Optional.empty()
                    : forecastAccount(e.getKey(), obs, all0.getLast().end(), temps);
            if (f.isPresent()) {
                forecasts.add(f.get());
            } else {
                skipped.add(e.getKey().getLabel() + " (needs " + BillModel.MIN_BILLS + "+ bills)");
            }
        }
        forecasts.sort(Comparator.comparing(AccountForecast::predictedAmount).reversed());

        BigDecimal total = forecasts.stream().map(AccountForecast::predictedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Errors across utilities are roughly independent, so half-widths add in quadrature.
        double halfWidth = Math.sqrt(forecasts.stream()
                .mapToDouble(f -> Math.pow(f.high().subtract(f.predictedAmount()).doubleValue(), 2)).sum());
        boolean weatherUsed = forecasts.stream().anyMatch(f -> f.method().equals(BillModel.Method.WEATHER_REGRESSION.label()));
        return new HouseholdForecast(forecasts, skipped, total,
                money(Math.max(0, total.doubleValue() - halfWidth)), money(total.doubleValue() + halfWidth),
                total.divide(BigDecimal.valueOf(Math.max(1, occupants)), 2, RoundingMode.HALF_UP),
                occupants, weatherUsed);
    }

    static Optional<AccountForecast> forecastAccount(UtilityAccount account, List<Observation> obs,
                                                     LocalDate lastBillEnd, Map<LocalDate, Double> temps) {
        Optional<BillModel> fitted = BillModel.fit(obs);
        if (fitted.isEmpty()) {
            return Optional.empty();
        }
        BillModel model = fitted.get();
        int days = (int) Math.round(BillModel.median(
                BillModel.tail(obs, 6).stream().mapToDouble(Observation::days).toArray()));
        LocalDate start = lastBillEnd.plusDays(1);
        LocalDate end = start.plusDays(days - 1L);

        DegreeDays normal = WeatherService.normal(temps, start, end, NORMAL_YEARS).orElse(null);
        BillModel.Method method = model.methodFor(normal, end);
        double amount = model.predictAmount(normal, days, end);
        double usage = model.predictUsage(normal, days, end);
        double half = model.amountHalfWidth(days, method);
        Backtest bt = backtest(obs);

        String unit = obs.stream().filter(Observation::metered).map(Observation::unit)
                .reduce((first, second) -> second).orElse(null);
        return Optional.of(new AccountForecast(
                account.getId(),
                account.getLabel(),
                account.getUtilityType(),
                method.label(),
                start,
                end,
                Double.isNaN(usage) ? null : BigDecimal.valueOf(usage).setScale(0, RoundingMode.HALF_UP),
                unit,
                money(amount),
                money(Math.max(0, amount - half)),
                money(amount + half),
                explain(model, method, unit),
                model.usageRegression().map(LinearModel::r2).orElse(null),
                bt.modelMape(),
                bt.naiveMape(),
                bt.n(),
                obs.size()));
    }

    record Backtest(Double modelMape, Double naiveMape, int n) {
    }

    /**
     * Rolling-origin evaluation: for each of the last few bills, fit only on earlier
     * bills, predict it using the weather that actually occurred, and compare to
     * "same cost per day as the previous bill".
     */
    static Backtest backtest(List<Observation> obs) {
        double modelErr = 0;
        double naiveErr = 0;
        int n = 0;
        for (int i = Math.max(BillModel.MIN_BILLS + 1, obs.size() - BACKTEST_BILLS); i < obs.size(); i++) {
            Observation target = obs.get(i);
            Optional<BillModel> m = BillModel.fit(obs.subList(0, i));
            if (m.isEmpty() || target.amount() <= 0) {
                continue;
            }
            double pred = m.get().predictAmount(target.weather(), target.days(), target.end());
            Observation prev = obs.get(i - 1);
            double naive = prev.amount() / prev.days() * target.days();
            modelErr += Math.abs(pred - target.amount()) / target.amount();
            naiveErr += Math.abs(naive - target.amount()) / target.amount();
            n++;
        }
        return n == 0 ? new Backtest(null, null, 0) : new Backtest(100 * modelErr / n, 100 * naiveErr / n, n);
    }

    private static String explain(BillModel model, BillModel.Method method, String unit) {
        StringBuilder s = new StringBuilder();
        if (method == BillModel.Method.SEASONAL_NAIVE) {
            s.append("Usage from the same period last year (add a city for weather-adjusted forecasts). ");
        }
        model.usageRegression().filter(r -> method == BillModel.Method.WEATHER_REGRESSION).ifPresent(r -> {
            s.append(String.format("Base %.1f %s/day", r.intercept(), unit));
            if (Math.abs(r.coefficient(0)) >= 0.05) {
                s.append(String.format(", %+.2f per heating degree day", r.coefficient(0)));
            }
            if (Math.abs(r.coefficient(1)) >= 0.05) {
                s.append(String.format(", %+.2f per cooling degree day", r.coefficient(1)));
            }
            s.append(". ");
        });
        model.costRegression().ifPresent(c -> s.append(String.format(
                "Cost ≈ $%.2f fixed + $%.4f per %s.", c.intercept(), c.coefficient(0), unit)));
        if (method == BillModel.Method.FLAT) {
            s.append("Recurring charge; uses the median of the last three bills.");
        }
        return s.toString().trim();
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}

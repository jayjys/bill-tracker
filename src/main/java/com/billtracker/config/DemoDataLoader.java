package com.billtracker.config;

import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.analytics.weather.DegreeDays;
import com.billtracker.analytics.weather.WeatherDay;
import com.billtracker.analytics.weather.WeatherDayRepository;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillCharge;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.BillService;
import com.billtracker.bill.BillSource;
import com.billtracker.bill.BillStatus;
import com.billtracker.bill.ChargeCategory;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import com.billtracker.household.Household;
import com.billtracker.household.HouseholdRepository;
import com.billtracker.household.HouseholdService;
import com.billtracker.parsing.BillParser;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserRepository;
import com.billtracker.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeds a demo household (two roommates in Chicago) with two years of bills whose usage
 * follows the real local weather, so the forecast and anomaly models have something
 * realistic to learn from. The latest water bill includes a simulated leak.
 * Runs with the {@code local} profile and with the {@code demo} profile used for the public deployment.
 */
@Component
@Profile({"local", "demo"})
@Order(0)
public class DemoDataLoader implements ApplicationRunner {

    public static final String DEMO_EMAIL = "demo@example.com";
    public static final String DEMO_PASSWORD = "demo-password";
    static final String ROOMMATE_EMAIL = "sam@example.com";

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
    private static final BigDecimal CHICAGO_LAT = new BigDecimal("41.87810");
    private static final BigDecimal CHICAGO_LON = new BigDecimal("-87.62980");

    private final UserRepository users;
    private final UserService userService;
    private final HouseholdService householdService;
    private final HouseholdRepository households;
    private final BillRepository bills;
    private final BillService billService;
    private final WeatherService weather;
    private final WeatherDayRepository weatherDays;
    private final AnomalyService anomalies;
    private final Random random = new Random(42);

    public DemoDataLoader(UserRepository users, UserService userService, HouseholdService householdService,
                          HouseholdRepository households, BillRepository bills, BillService billService,
                          WeatherService weather, WeatherDayRepository weatherDays, AnomalyService anomalies) {
        this.users = users;
        this.userService = userService;
        this.householdService = householdService;
        this.households = households;
        this.bills = bills;
        this.billService = billService;
        this.weather = weather;
        this.weatherDays = weatherDays;
        this.anomalies = anomalies;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmailIgnoreCase(DEMO_EMAIL)) {
            return;
        }
        AppUser demo = userService.register(DEMO_EMAIL, DEMO_PASSWORD, "Jordan");
        Household home = households.findById(demo.getHousehold().getId()).orElseThrow();
        home.setName("Wicker Park apartment");
        home.setCity("Chicago, Illinois, United States");
        home.setLocation(CHICAGO_LAT, CHICAGO_LON);
        home.setOccupants(2);
        households.save(home);
        String invite = householdService.ensureInvite(demo).getInviteCode();
        AppUser sam = userService.register(ROOMMATE_EMAIL, DEMO_PASSWORD, "Sam", invite);

        Map<LocalDate, Double> temps = loadWeather(home);
        LocalDate lastEnd = LocalDate.now().minusDays(12);
        lastEnd = lastEnd.withDayOfMonth(Math.min(20, lastEnd.lengthOfMonth()));
        if (lastEnd.isAfter(LocalDate.now().minusDays(7))) {
            lastEnd = lastEnd.minusMonths(1);
        }

        UtilityAccount electric = billService.account(home, "ComEd", UtilityType.ELECTRIC, "4411");
        UtilityAccount gas = billService.account(home, "Peoples Gas", UtilityType.GAS, "0937");
        UtilityAccount water = billService.account(home, "City of Chicago Water", UtilityType.WATER, "2250");
        UtilityAccount internet = billService.account(home, "Xfinity", UtilityType.INTERNET, "");

        int count = 0;
        for (int i = 23; i >= 0; i--) {
            LocalDate end = lastEnd.minusMonths(i);
            LocalDate start = end.minusMonths(1).plusDays(1);
            DegreeDays dd = WeatherService.observed(temps, start, end).orElse(new DegreeDays(10, 1, 1));
            int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
            double years = (23 - i) / 12.0;
            AppUser uploader = i % 2 == 0 ? demo : sam;
            BillSource source = i % 3 == 0 ? BillSource.EMAIL : BillSource.UPLOAD;

            // Electricity: base load + electric heat assist in winter + strong AC in summer.
            double kwh = days * (13.5 + 0.22 * dd.hddPerDay() + 1.7 * dd.cddPerDay() + noise(0.9));
            double supply = kwh * 0.082 * (1 + 0.05 * years);
            double delivery = kwh * 0.061 * (1 + 0.03 * years);
            save(electric, uploader, source, start, end, kwh, "kWh", List.of(
                    charge("Customer Charge", ChargeCategory.DELIVERY, 15.86),
                    charge("Distribution Facilities Charge", ChargeCategory.DELIVERY, delivery),
                    charge("Electricity Supply Charge", ChargeCategory.SUPPLY, supply),
                    charge("Environmental Cost Recovery Adj", ChargeCategory.FEE, kwh * 0.0016),
                    charge("State and Municipal Taxes", ChargeCategory.TAX, (supply + delivery) * 0.07)));
            count++;

            // Gas: almost entirely heating.
            double therms = days * Math.max(0.2, 0.30 + 0.215 * dd.hddPerDay() + noise(0.12));
            double commodity = therms * 0.39 * (1 + 0.04 * years);
            save(gas, uploader, source, start, end, therms, "therms", List.of(
                    charge("Customer Charge", ChargeCategory.DELIVERY, 33.82),
                    charge("Distribution Charge", ChargeCategory.DELIVERY, therms * 0.33),
                    charge("Gas Charge", ChargeCategory.SUPPLY, commodity),
                    charge("Infrastructure Program Surcharge", ChargeCategory.FEE, 6.10),
                    charge("Gas Use Tax", ChargeCategory.TAX, therms * 0.06)));
            count++;

            // Water: billed every two months; a little more in summer.
            if (i % 2 == 0) {
                LocalDate wStart = end.minusMonths(2).plusDays(1);
                DegreeDays wdd = WeatherService.observed(temps, wStart, end).orElse(dd);
                int wDays = (int) (end.toEpochDay() - wStart.toEpochDay()) + 1;
                double perDay = 2 * 68 + 6 * wdd.cddPerDay() + noise(6);
                if (i == 0) {
                    perDay *= 1.85; // a running toilet: ~120 extra gallons a day
                }
                double gallons = wDays * perDay;
                save(water, uploader, source, wStart, end, gallons, "gallons", List.of(
                        charge("Water Charge", ChargeCategory.SUPPLY, gallons * 0.00572),
                        charge("Sewer Charge", ChargeCategory.DELIVERY, gallons * 0.00572 * 0.87),
                        charge("Water/Sewer Tax", ChargeCategory.TAX, gallons * 0.0012),
                        charge("Meter Fee", ChargeCategory.FEE, 4.00)));
                count++;
            }

            double plan = i > 9 ? 65.00 : 80.00; // promo rate ended
            save(internet, uploader, BillSource.EMAIL, start, end, Double.NaN, null, List.of(
                    charge("Internet plan", ChargeCategory.SUPPLY, plan),
                    charge("xFi Gateway rental", ChargeCategory.FEE, 15.00)));
            count++;
        }
        int alerts = anomalies.scan(home);
        log.info("Seeded demo household with {} bills and {} alert(s); sign in as {} (password in DemoDataLoader)",
                count, alerts, DEMO_EMAIL);
    }

    /** Real weather when Open-Meteo is reachable; otherwise a synthetic Chicago-like year. */
    private Map<LocalDate, Double> loadWeather(Household home) {
        try {
            weather.sync(home);
        } catch (RuntimeException e) {
            log.warn("Weather unavailable ({}); seeding synthetic temperatures", e.getMessage());
            List<WeatherDay> synthetic = new ArrayList<>();
            for (LocalDate d = LocalDate.now().minusYears(4); d.isBefore(LocalDate.now().minusDays(6)); d = d.plusDays(1)) {
                double seasonal = 10.5 - 14 * Math.cos(2 * Math.PI * (d.getDayOfYear() - 20) / 365.0);
                synthetic.add(new WeatherDay(home.locationKey(), d,
                        BigDecimal.valueOf(seasonal + noise(4)).setScale(2, RoundingMode.HALF_UP)));
            }
            weatherDays.saveAll(synthetic);
        }
        return weather.load(home.locationKey(), LocalDate.now().minusYears(5), LocalDate.now());
    }

    private record ChargeSpec(String description, ChargeCategory category, BigDecimal amount) {
    }

    private static ChargeSpec charge(String description, ChargeCategory category, double amount) {
        return new ChargeSpec(description, category, BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP));
    }

    private void save(UtilityAccount account, AppUser uploader, BillSource source, LocalDate start, LocalDate end,
                      double usage, String unit, List<ChargeSpec> specs) {
        Bill bill = new Bill(uploader, account, source);
        bill.setPeriodStart(start);
        bill.setPeriodEnd(end);
        bill.setDueDate(end.plusDays(21));
        if (!Double.isNaN(usage)) {
            bill.setUsageQuantity(BigDecimal.valueOf(usage).setScale(0, RoundingMode.HALF_UP));
            bill.setUsageUnit(unit);
        }
        List<BillCharge> charges = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int line = 1;
        for (ChargeSpec c : specs) {
            charges.add(new BillCharge(bill, line++, c.description(), c.category(), c.amount()));
            total = total.add(c.amount());
        }
        bill.replaceCharges(charges);
        bill.setAmount(total);
        bill.setOriginalFilename(account.getProvider().toLowerCase().replace(' ', '-') + "-" + end + ".pdf");
        bill.setParserVersion(BillParser.VERSION);
        bill.setParseConfidence(new BigDecimal("0.950"));
        bill.setStatus(BillStatus.PARSED);
        bills.save(bill);
    }

    private double noise(double sd) {
        return random.nextGaussian() * sd;
    }
}

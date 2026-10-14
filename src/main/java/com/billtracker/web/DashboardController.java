package com.billtracker.web;

import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertKind;
import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.analytics.forecast.ForecastService;
import com.billtracker.bill.BillService;
import com.billtracker.household.Household;
import com.billtracker.household.HouseholdService;
import com.billtracker.ingest.InboundProperties;
import com.billtracker.trends.TrendService;
import com.billtracker.user.AppUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    private final TrendService trends;
    private final BillService bills;
    private final ForecastService forecasts;
    private final AlertRepository alerts;
    private final HouseholdService households;
    private final InboundProperties inbound;

    public DashboardController(TrendService trends, BillService bills, ForecastService forecasts,
                               AlertRepository alerts, HouseholdService households, InboundProperties inbound) {
        this.trends = trends;
        this.bills = bills;
        this.forecasts = forecasts;
        this.alerts = alerts;
        this.households = households;
        this.inbound = inbound;
    }

    @GetMapping("/")
    public String dashboard(AppUser user, Model model) {
        Household household = user.getHousehold();
        model.addAttribute("user", user);
        model.addAttribute("household", household);
        model.addAttribute("memberCount", households.members(household).size());
        model.addAttribute("report", trends.monthly(household, 12));
        model.addAttribute("forecast", forecasts.forecast(household));
        List<Alert> open = alerts.openAlerts(household.getId());
        model.addAttribute("alerts", open.stream().filter(a -> a.getKind() != AlertKind.DUE_SOON).limit(3).toList());
        model.addAttribute("dueSoon", open.stream().filter(a -> a.getKind() == AlertKind.DUE_SOON).toList());
        model.addAttribute("recent", bills.list(household).stream().limit(6).toList());
        model.addAttribute("needsReview", bills.countNeedingReview(household));
        model.addAttribute("forwardingAddress", inbound.addressFor(user.getInboundToken()));
        return "dashboard";
    }
}

package com.billtracker.web;

import com.billtracker.analytics.anomaly.Alert;
import com.billtracker.analytics.anomaly.AlertRepository;
import com.billtracker.analytics.forecast.ForecastService;
import com.billtracker.household.Household;
import com.billtracker.insights.InsightQueries;
import com.billtracker.trends.TrendService;
import com.billtracker.user.AppUser;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

@Controller
public class InsightsController {

    private final ForecastService forecasts;
    private final AlertRepository alerts;
    private final InsightQueries queries;
    private final TrendService trends;

    public InsightsController(ForecastService forecasts, AlertRepository alerts, InsightQueries queries,
                              TrendService trends) {
        this.forecasts = forecasts;
        this.alerts = alerts;
        this.queries = queries;
        this.trends = trends;
    }

    @GetMapping("/insights")
    public String insights(AppUser user, Model model) {
        Household household = user.getHousehold();
        model.addAttribute("household", household);
        model.addAttribute("forecast", forecasts.forecast(household));
        model.addAttribute("report", trends.monthly(household, 12));
        model.addAttribute("alerts", alerts.openAlerts(household.getId()));
        model.addAttribute("results", queries.run(household, LocalDate.now()));
        return "insights";
    }

    @PostMapping("/alerts/{id}/dismiss")
    @Transactional
    public String dismiss(AppUser user, @PathVariable long id, RedirectAttributes redirect) {
        Alert alert = alerts.findByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(() -> new com.billtracker.bill.BillService.BillNotFoundException(id));
        alert.dismiss();
        alerts.save(alert);
        redirect.addFlashAttribute("flash", "Alert dismissed.");
        return "redirect:/insights#alerts";
    }
}

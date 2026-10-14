package com.billtracker.web;

import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.analytics.weather.WeatherDayRepository;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.household.Household;
import com.billtracker.household.HouseholdService;
import com.billtracker.jobs.JobRunRepository;
import com.billtracker.jobs.JobRunner;
import com.billtracker.parsing.BillParser;
import com.billtracker.user.AppUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/** Background job history and a manual "refresh my analysis" trigger. */
@Controller
public class SystemController {

    private final JobRunRepository runs;
    private final JobRunner runner;
    private final HouseholdService households;
    private final WeatherService weather;
    private final WeatherDayRepository weatherDays;
    private final AnomalyService anomalies;

    public SystemController(JobRunRepository runs, JobRunner runner, HouseholdService households,
                            WeatherService weather, WeatherDayRepository weatherDays, AnomalyService anomalies) {
        this.runs = runs;
        this.runner = runner;
        this.households = households;
        this.weather = weather;
        this.weatherDays = weatherDays;
        this.anomalies = anomalies;
    }

    @GetMapping("/system")
    public String system(AppUser user, Model model) {
        Household h = user.getHousehold();
        model.addAttribute("runs", runs.findTop25ByOrderByStartedAtDesc());
        model.addAttribute("household", h);
        model.addAttribute("weatherFrom", h.hasLocation() ? weatherDays.earliestDate(h.locationKey()) : null);
        model.addAttribute("weatherTo", h.hasLocation() ? weatherDays.latestDate(h.locationKey()) : null);
        model.addAttribute("parserVersion", BillParser.VERSION);
        return "system";
    }

    @PostMapping("/system/refresh")
    public String refresh(AppUser user, RedirectAttributes redirect) {
        Long id = user.getHousehold().getId();
        var run = runner.run("manual-refresh (household " + id + ")", List.of(id), hid -> {
            Household h = households.reload(hid);
            return weather.sync(h) + anomalies.scan(h);
        });
        redirect.addFlashAttribute(run.getStatus().name().equals("OK") ? "flash" : "error",
                "Refresh " + run.getStatus().name().toLowerCase() + ": " + run.getMessage());
        return "redirect:/system";
    }
}

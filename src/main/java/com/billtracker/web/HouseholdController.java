package com.billtracker.web;

import com.billtracker.household.Household;
import com.billtracker.household.HouseholdService;
import com.billtracker.jobs.JobRunner;
import com.billtracker.analytics.anomaly.AnomalyService;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.Optional;

@Controller
public class HouseholdController {

    private final HouseholdService households;
    private final WeatherService weather;
    private final AnomalyService anomalies;
    private final JobRunner jobs;

    public HouseholdController(HouseholdService households, WeatherService weather, AnomalyService anomalies,
                               JobRunner jobs) {
        this.households = households;
        this.weather = weather;
        this.anomalies = anomalies;
        this.jobs = jobs;
    }

    @GetMapping("/household")
    public String household(AppUser user, Model model, HttpServletRequest request) {
        Household h = households.ensureInvite(user);
        model.addAttribute("user", user);
        model.addAttribute("household", h);
        model.addAttribute("members", households.members(h));
        model.addAttribute("inviteLink", ServletUriComponentsBuilder.fromContextPath(request)
                .path("/join/{code}").buildAndExpand(h.getInviteCode()).toUriString());
        return "household";
    }

    @PostMapping("/household")
    public String update(AppUser user, @RequestParam String name, @RequestParam int occupants,
                         @RequestParam(required = false) String city, RedirectAttributes redirect) {
        if (name.isBlank() || occupants < 1 || occupants > 20) {
            redirect.addFlashAttribute("error", "Enter a name and between 1 and 20 people.");
            return "redirect:/household";
        }
        Optional<String> problem = households.updateSettings(user, name, occupants, city);
        problem.ifPresentOrElse(p -> redirect.addFlashAttribute("error", p),
                () -> redirect.addFlashAttribute("flash", "Household saved."));
        if (problem.isEmpty() && city != null && !city.isBlank()) {
            refreshWeatherInBackground(user);
        }
        return "redirect:/household";
    }

    /** Weather history takes a few seconds to download; don't make the user wait for it. */
    private void refreshWeatherInBackground(AppUser user) {
        Long householdId = user.getHousehold().getId();
        Thread.ofVirtual().start(() -> jobs.run("weather-sync (household " + householdId + ")",
                List.of(householdId), id -> {
                    Household h = households.reload(id);
                    return weather.sync(h) + anomalies.scan(h);
                }));
    }

    @PostMapping("/household/invite")
    public String newInvite(AppUser user, RedirectAttributes redirect) {
        households.rotateInvite(user);
        redirect.addFlashAttribute("flash", "New invite link created. The old link no longer works.");
        return "redirect:/household";
    }

    @PostMapping("/household/leave")
    public String leave(AppUser user, RedirectAttributes redirect) {
        try {
            households.leave(user);
            redirect.addFlashAttribute("flash", "You've left. Your new household starts empty.");
        } catch (IllegalStateException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/household";
    }

    /** Invite landing page. Signed-out visitors are sent to sign-up with the code attached. */
    @GetMapping("/join/{code}")
    public String joinPage(@PathVariable String code, Authentication auth, Model model) {
        Optional<Household> target = households.byInvite(code);
        if (target.isEmpty()) {
            model.addAttribute("invalid", true);
            return "join";
        }
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return "redirect:/register?invite=" + code;
        }
        model.addAttribute("target", target.get());
        model.addAttribute("code", code);
        return "join";
    }

    @PostMapping("/join/{code}")
    public String join(AppUser user, @PathVariable String code, RedirectAttributes redirect) {
        try {
            Household h = households.join(user, code);
            redirect.addFlashAttribute("flash", "You've joined " + h.getName() + ".");
            return "redirect:/household";
        } catch (HouseholdService.InvalidInviteException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/";
        }
    }
}

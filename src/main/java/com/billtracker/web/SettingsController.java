package com.billtracker.web;

import com.billtracker.ingest.InboundProperties;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SettingsController {

    private final UserService users;
    private final InboundProperties inbound;

    public SettingsController(UserService users, InboundProperties inbound) {
        this.users = users;
        this.inbound = inbound;
    }

    @GetMapping("/settings")
    public String settings(AppUser user, Model model) {
        model.addAttribute("user", user);
        model.addAttribute("forwardingAddress", inbound.addressFor(user.getInboundToken()));
        return "settings";
    }

    @PostMapping("/settings/rotate-address")
    public String rotate(AppUser user, RedirectAttributes redirect) {
        users.rotateInboundToken(user.getEmail());
        redirect.addFlashAttribute("flash", "New forwarding address created. The old one no longer works.");
        return "redirect:/settings";
    }
}

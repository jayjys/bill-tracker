package com.billtracker.web;

import com.billtracker.household.HouseholdService;
import com.billtracker.user.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Objects;

@Controller
public class AuthController {

    private final UserService userService;
    private final HouseholdService households;

    public AuthController(UserService userService, HouseholdService households) {
        this.userService = userService;
        this.households = households;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registerForm(@ModelAttribute("form") RegistrationForm form,
                               @RequestParam(required = false) String invite, Model model) {
        form.setInvite(invite);
        households.byInvite(invite).ifPresent(h -> model.addAttribute("inviteHousehold", h.getName()));
        return "register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegistrationForm form, BindingResult result,
                           HttpServletRequest request) throws ServletException {
        if (result.hasErrors()) {
            return "register";
        }
        try {
            userService.register(form.getEmail(), form.getPassword(), form.getDisplayName(), form.getInvite());
        } catch (UserService.EmailTakenException e) {
            result.rejectValue("email", "taken", "An account with this email already exists");
            return "register";
        }
        // Sign the new user straight in rather than bouncing them to the login form.
        request.login(form.getEmail().trim(), form.getPassword());
        return "redirect:/";
    }

    public static class RegistrationForm {

        @NotBlank
        @Email
        @Size(max = 255)
        private String email;

        @Size(max = 100)
        private String displayName;

        @Size(min = 8, max = 128, message = "Use at least 8 characters")
        private String password;

        private String confirmPassword;

        /** Invite code when signing up from a roommate's link. */
        private String invite;

        public String getInvite() {
            return invite;
        }

        public void setInvite(String invite) {
            this.invite = invite;
        }

        @AssertTrue(message = "Passwords don't match")
        public boolean isPasswordConfirmed() {
            return Objects.equals(password, confirmPassword);
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirmPassword() {
            return confirmPassword;
        }

        public void setConfirmPassword(String confirmPassword) {
            this.confirmPassword = confirmPassword;
        }
    }
}

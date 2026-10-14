package com.billtracker.user;

import com.billtracker.household.Household;
import com.billtracker.household.HouseholdService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class UserService implements UserDetailsService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final HouseholdService households;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, @Lazy HouseholdService households) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.households = households;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser user = users.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles("USER")
                .build();
    }

    @Transactional
    public AppUser register(String email, String rawPassword, String displayName) {
        return register(email, rawPassword, displayName, null);
    }

    /**
     * Creates the account in the household behind {@code inviteCode} when given (a roommate
     * signing up from an invite link), otherwise in a new household they own.
     */
    @Transactional
    public AppUser register(String email, String rawPassword, String displayName, String inviteCode) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new EmailTakenException(normalized);
        }
        String name = displayName == null || displayName.isBlank() ? null : displayName.trim();
        Optional<Household> invited = households.byInvite(inviteCode);
        Household household = invited.orElseGet(() -> households.createFor(name));
        AppUser user = users.save(new AppUser(normalized, passwordEncoder.encode(rawPassword), name, newToken(),
                household));
        if (invited.isEmpty()) {
            household.setOwner(user);
        } else {
            household.setOccupants(Math.max(household.getOccupants(), (int) users.countByHouseholdId(household.getId())));
        }
        return user;
    }

    @Transactional(readOnly = true)
    public AppUser require(String email) {
        return users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
    }

    /** Invalidates the old forwarding address, e.g. if it leaked and is receiving spam. */
    @Transactional
    public AppUser rotateInboundToken(String email) {
        AppUser user = require(email);
        user.setInboundToken(newToken());
        return users.save(user);
    }

    private static String newToken() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static class EmailTakenException extends RuntimeException {
        public EmailTakenException(String email) {
            super("An account already exists for " + email);
        }
    }
}

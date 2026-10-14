package com.billtracker.household;

import com.billtracker.analytics.weather.WeatherProvider;
import com.billtracker.analytics.weather.WeatherService;
import com.billtracker.bill.Bill;
import com.billtracker.bill.BillRepository;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityAccountRepository;
import com.billtracker.user.AppUser;
import com.billtracker.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class HouseholdService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HouseholdRepository households;
    private final UserRepository users;
    private final UtilityAccountRepository accounts;
    private final BillRepository bills;
    private final WeatherService weather;

    public HouseholdService(HouseholdRepository households, UserRepository users, UtilityAccountRepository accounts,
                            BillRepository bills, WeatherService weather) {
        this.households = households;
        this.users = users;
        this.accounts = accounts;
        this.bills = bills;
        this.weather = weather;
    }

    @Transactional
    public Household createFor(String ownerName) {
        String name = ownerName == null || ownerName.isBlank() ? "My home" : ownerName.trim() + "'s home";
        return households.save(new Household(name));
    }

    @Transactional(readOnly = true)
    public List<AppUser> members(Household household) {
        return users.findByHouseholdIdOrderByCreatedAtAsc(household.getId());
    }

    @Transactional(readOnly = true)
    public Household reload(Long id) {
        return households.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Household> byInvite(String code) {
        return code == null || code.isBlank() ? Optional.empty() : households.findByInviteCode(code.trim());
    }

    /** Invite codes are created on demand and can be rotated to revoke an old link. */
    @Transactional
    public Household rotateInvite(AppUser user) {
        Household h = households.findById(user.getHousehold().getId()).orElseThrow();
        h.setInviteCode(HexFormat.of().formatHex(randomBytes(9)));
        return households.save(h);
    }

    @Transactional
    public Household ensureInvite(AppUser user) {
        Household h = households.findById(user.getHousehold().getId()).orElseThrow();
        return h.getInviteCode() == null ? rotateInvite(user) : h;
    }

    /**
     * Moves the user into the household behind the invite code. If they were the only
     * member of their old household, its bills come with them; matching utility accounts
     * are merged, and a bill both roommates already uploaded is kept once.
     */
    @Transactional
    public Household join(AppUser user, String inviteCode) {
        Household target = byInvite(inviteCode).orElseThrow(() -> new InvalidInviteException());
        AppUser me = users.findById(user.getId()).orElseThrow();
        Household old = me.getHousehold();
        if (Objects.equals(old.getId(), target.getId())) {
            return target;
        }
        boolean soleMember = users.countByHouseholdId(old.getId()) == 1;
        me.setHousehold(target);
        users.save(me);
        if (soleMember) {
            mergeBills(old, target);
            households.delete(old);
        }
        target.setOccupants(Math.max(target.getOccupants(), (int) users.countByHouseholdId(target.getId())));
        return households.save(target);
    }

    private void mergeBills(Household from, Household to) {
        for (UtilityAccount account : accounts.findByHouseholdId(from.getId())) {
            Optional<UtilityAccount> existing = accounts.findByHouseholdIdAndProviderAndUtilityTypeAndAccountLast4(
                    to.getId(), account.getProvider(), account.getUtilityType(), account.getAccountLast4());
            List<Bill> accountBills = bills.findByAccountIdOrderByPeriodEndAsc(account.getId());
            if (existing.isEmpty()) {
                account.setHousehold(to);
                accounts.save(account);
                accountBills.forEach(b -> b.setHousehold(to));
                bills.saveAll(accountBills);
                continue;
            }
            for (Bill b : accountBills) {
                if (b.getContentSha256() != null && bills.existsByHouseholdIdAndContentSha256(to.getId(), b.getContentSha256())) {
                    bills.delete(b);
                } else {
                    b.setAccount(existing.get());
                    bills.save(b);
                }
            }
            bills.flush();
            accounts.delete(account);
        }
    }

    /** Leaving puts you in a fresh household of your own; the bills stay with the old one. */
    @Transactional
    public Household leave(AppUser user) {
        AppUser me = users.findById(user.getId()).orElseThrow();
        if (users.countByHouseholdId(me.getHousehold().getId()) == 1) {
            throw new IllegalStateException("You're the only member of this household");
        }
        Household old = me.getHousehold();
        Household fresh = createFor(me.getDisplayName());
        fresh.setOwner(me);
        me.setHousehold(fresh);
        users.save(me);
        if (old.getOwner() != null && Objects.equals(old.getOwner().getId(), me.getId())) {
            old.setOwner(users.findByHouseholdIdOrderByCreatedAtAsc(old.getId()).getFirst());
            households.save(old);
        }
        return households.save(fresh);
    }

    /**
     * Saves name and occupants, and looks up the city's coordinates for weather.
     * Returns a message when the city couldn't be found or the weather service is down.
     */
    @Transactional
    public Optional<String> updateSettings(AppUser user, String name, int occupants, String city) {
        Household h = households.findById(user.getHousehold().getId()).orElseThrow();
        h.setName(name.trim());
        h.setOccupants(occupants);
        Optional<String> problem = Optional.empty();
        String newCity = city == null ? "" : city.trim();
        if (newCity.isEmpty()) {
            h.setCity(null);
            h.setLocation(null, null);
        } else if (!newCity.equals(h.getCity())) {
            try {
                Optional<WeatherProvider.Place> place = weather.geocode(newCity);
                if (place.isPresent()) {
                    h.setCity(place.get().name());
                    h.setLocation(place.get().latitude(), place.get().longitude());
                } else {
                    problem = Optional.of("Couldn't find \"" + newCity + "\". Try \"City, State\".");
                }
            } catch (WeatherProvider.WeatherUnavailableException e) {
                problem = Optional.of("The weather service is unavailable right now; try saving the city again later.");
            }
        }
        households.save(h);
        return problem;
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    public static class InvalidInviteException extends RuntimeException {
        public InvalidInviteException() {
            super("That invite link is invalid or has been replaced");
        }
    }
}

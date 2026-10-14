package com.billtracker.bill;

import com.billtracker.household.Household;
import com.billtracker.user.AppUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
public class BillService {

    private final BillRepository bills;
    private final UtilityAccountRepository accounts;

    public BillService(BillRepository bills, UtilityAccountRepository accounts) {
        this.bills = bills;
        this.accounts = accounts;
    }

    /** The household's bills, newest statement first. */
    @Transactional(readOnly = true)
    public List<Bill> list(Household household) {
        return bills.findByHouseholdIdOrderByCreatedAtDesc(household.getId()).stream()
                .sorted(Comparator.comparing(Bill::getStatementDate).reversed())
                .toList();
    }

    /**
     * Every lookup is scoped to the caller's household, so nobody can read another
     * household's bill by guessing ids.
     */
    @Transactional(readOnly = true)
    public Bill get(AppUser user, long id) {
        return bills.findWithChargesByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(() -> new BillNotFoundException(id));
    }

    @Transactional
    public Bill create(AppUser user, BillForm form) {
        Bill bill = new Bill(user, account(user.getHousehold(), form.getProvider(), form.getUtilityType(), null),
                BillSource.MANUAL);
        form.applyTo(bill);
        bill.setParseConfidence(BigDecimal.ONE);
        bill.setStatus(BillStatus.CONFIRMED);
        return bills.save(bill);
    }

    @Transactional
    public Bill update(AppUser user, long id, BillForm form) {
        Bill bill = bills.findByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(() -> new BillNotFoundException(id));
        String provider = blankToUnknown(form.getProvider());
        if (!provider.equals(bill.getProvider()) || form.getUtilityType() != bill.getUtilityType()) {
            bill.setAccount(account(user.getHousehold(), provider, form.getUtilityType(), bill.getAccountLast4()));
        }
        form.applyTo(bill);
        bill.setStatus(BillStatus.CONFIRMED);
        bill.setParseWarnings(null);
        return bills.save(bill);
    }

    @Transactional
    public void delete(AppUser user, long id) {
        bills.delete(bills.findByIdAndHouseholdId(id, user.getHousehold().getId())
                .orElseThrow(() -> new BillNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public long countNeedingReview(Household household) {
        return bills.countByHouseholdIdAndStatus(household.getId(), BillStatus.NEEDS_REVIEW);
    }

    /** Find-or-create, so the same provider/utility/account number always maps to one row. */
    @Transactional
    public UtilityAccount account(Household household, String provider, UtilityType type, String last4) {
        String p = blankToUnknown(provider);
        String l = last4 == null ? "" : last4;
        return accounts.findByHouseholdIdAndProviderAndUtilityTypeAndAccountLast4(household.getId(), p, type, l)
                .orElseGet(() -> accounts.save(new UtilityAccount(household, p, type, l)));
    }

    private static String blankToUnknown(String provider) {
        return provider == null || provider.isBlank() ? UtilityAccount.UNKNOWN_PROVIDER : provider.trim();
    }

    public static class BillNotFoundException extends RuntimeException {
        public BillNotFoundException(long id) {
            super("Bill " + id + " not found");
        }
    }
}

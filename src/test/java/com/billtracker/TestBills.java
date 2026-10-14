package com.billtracker;

import com.billtracker.bill.Bill;
import com.billtracker.bill.BillSource;
import com.billtracker.bill.UtilityAccount;
import com.billtracker.bill.UtilityType;
import com.billtracker.household.Household;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/** Builds unsaved bills for unit tests of the analytics code. */
public final class TestBills {

    private static final AtomicLong IDS = new AtomicLong(1000);
    private final Household household = new Household("Test home");

    public UtilityAccount account(String provider, UtilityType type) {
        return new UtilityAccount(household, provider, type, "");
    }

    public static Bill bill(UtilityAccount account, String amount, LocalDate start, LocalDate end, Double usage,
                            String unit) {
        Bill b = new Bill(null, account, BillSource.MANUAL);
        setId(b, IDS.incrementAndGet());
        b.setAmount(amount == null ? null : new BigDecimal(amount));
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        if (usage != null) {
            b.setUsageQuantity(BigDecimal.valueOf(usage));
            b.setUsageUnit(unit);
        }
        return b;
    }

    /** Unsaved bills have no id; analytics code keys some lookups by id, so give them one. */
    private static void setId(Bill bill, long id) {
        try {
            Field field = Bill.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(bill, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

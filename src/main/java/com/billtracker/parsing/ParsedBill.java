package com.billtracker.parsing;

import com.billtracker.bill.UtilityType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One utility's bill read from a statement. A combined electric + gas statement
 * yields two. Any field may be null when the parser could not find it.
 */
public record ParsedBill(
        String provider,
        UtilityType utilityType,
        BigDecimal amount,
        LocalDate dueDate,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal usageQuantity,
        String usageUnit,
        String accountLast4,
        BigDecimal confidence,
        List<ParsedCharge> charges,
        List<String> warnings) {

    /** True when there is enough to chart the bill: a cost and a date to put it on. */
    public boolean isComplete() {
        return amount != null && (periodEnd != null || dueDate != null);
    }
}

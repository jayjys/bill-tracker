package com.billtracker.parsing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sanity checks on parsed values. A bill that fails one is still saved, but with a
 * warning and lower confidence so it lands in the review queue instead of silently
 * skewing the trends.
 */
final class Validator {

    private static final BigDecimal LARGE = new BigDecimal("5000");

    private Validator() {
    }

    static List<String> check(BigDecimal amount, LocalDate due, LocalDate start, LocalDate end,
                              List<ParsedCharge> charges) {
        List<String> warnings = new ArrayList<>();
        if (amount != null) {
            if (amount.signum() <= 0) {
                warnings.add("Amount is zero or negative — possibly a credit balance");
            } else if (amount.compareTo(LARGE) > 0) {
                warnings.add("Unusually large amount $" + amount + " — check it was read correctly");
            }
            if (charges.size() >= 2) {
                BigDecimal sum = charges.stream().map(ParsedCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal tolerance = amount.abs().multiply(new BigDecimal("0.02")).max(BigDecimal.ONE);
                if (sum.subtract(amount).abs().compareTo(tolerance) > 0) {
                    warnings.add("Line items add up to $" + sum + ", not the total of $" + amount);
                }
            }
        }
        if (due != null && end != null && due.isBefore(end)) {
            warnings.add("Due date is before the end of the service period");
        }
        if (end != null && end.isAfter(LocalDate.now().plusDays(60))) {
            warnings.add("Service period ends more than two months from now");
        }
        return warnings;
    }
}

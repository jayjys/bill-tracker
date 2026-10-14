package com.billtracker.analytics.forecast;

import java.math.BigDecimal;
import java.util.List;

/** Next month's expected total across accounts, with an ~80% range and per-person share. */
public record HouseholdForecast(
        List<AccountForecast> accounts,
        List<String> skipped,
        BigDecimal total,
        BigDecimal low,
        BigDecimal high,
        BigDecimal perPerson,
        int occupants,
        boolean weatherAvailable) {

    public boolean isEmpty() {
        return accounts.isEmpty();
    }
}

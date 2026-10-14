package com.billtracker.analytics.forecast;

import com.billtracker.bill.UtilityType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Next bill for one utility account, plus how well the same model did on past bills.
 * {@code backtestMape}/{@code naiveMape} are mean absolute percentage errors of
 * rolling-origin predictions (train on everything before a bill, predict it) for the
 * model and for the "same as last bill, per day" baseline. Null when history is short.
 */
public record AccountForecast(
        Long accountId,
        String label,
        UtilityType utilityType,
        String method,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal predictedUsage,
        String usageUnit,
        BigDecimal predictedAmount,
        BigDecimal low,
        BigDecimal high,
        String explanation,
        Double r2,
        Double backtestMape,
        Double naiveMape,
        int backtestBills,
        int trainingBills) {

    public boolean beatsNaive() {
        return backtestMape != null && naiveMape != null && backtestMape < naiveMape;
    }
}

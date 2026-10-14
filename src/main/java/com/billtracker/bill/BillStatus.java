package com.billtracker.bill;

public enum BillStatus {
    /** Parser found the fields needed to chart the bill. */
    PARSED,
    /** Parser missed the amount or the statement date; the user should fill them in. */
    NEEDS_REVIEW,
    /** A person has checked or edited the values. */
    CONFIRMED
}

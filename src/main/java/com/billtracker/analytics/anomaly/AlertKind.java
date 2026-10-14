package com.billtracker.analytics.anomaly;

public enum AlertKind {
    /** Usage per day far above what weather and history predict. */
    USAGE_SPIKE,
    /** A water usage spike — the most common cause is a leak or running toilet. */
    POSSIBLE_LEAK,
    /** Price per unit jumped, e.g. a rate change, even if usage is normal. */
    RATE_INCREASE,
    /** A provider whose bills used to parse cleanly produced a bill that didn't. */
    PARSER_FORMAT_CHANGE,
    /** Bill due within a few days. */
    DUE_SOON
}

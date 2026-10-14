package com.billtracker.bill;

/** What a line item on a bill pays for. */
public enum ChargeCategory {
    /** Moving energy/water to you: delivery, transmission, distribution, meter/customer charges. */
    DELIVERY("Delivery"),
    /** The energy or water itself: generation, supply, commodity, usage charges. */
    SUPPLY("Supply"),
    TAX("Taxes"),
    FEE("Fees & surcharges"),
    CREDIT("Credits"),
    OTHER("Other");

    private final String label;

    ChargeCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

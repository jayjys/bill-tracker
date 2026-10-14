package com.billtracker.bill;

public enum UtilityType {
    ELECTRIC("Electric", "#e8a33d"),
    GAS("Gas", "#d4553f"),
    WATER("Water", "#3d8fd4"),
    INTERNET("Internet", "#7a5bd1"),
    PHONE("Phone", "#3aa58a"),
    TRASH("Trash", "#8a8f98"),
    OTHER("Other", "#b0b4ba");

    private final String label;
    private final String color;

    UtilityType(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    /** Stable chart color so a utility keeps the same color across every view. */
    public String color() {
        return color;
    }
}

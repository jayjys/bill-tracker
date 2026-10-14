package com.billtracker.bill;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Editable fields of a bill, used for manual entry and for correcting parser output. */
public class BillForm {

    @Size(max = 120)
    private String provider;

    @NotNull
    private UtilityType utilityType = UtilityType.OTHER;

    @NotNull(message = "Enter the amount charged")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate periodStart;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate periodEnd;

    @DecimalMin("0")
    @Digits(integer = 11, fraction = 3)
    private BigDecimal usageQuantity;

    @Size(max = 20)
    private String usageUnit;

    public static BillForm from(Bill bill) {
        BillForm form = new BillForm();
        form.provider = UtilityAccount.UNKNOWN_PROVIDER.equals(bill.getProvider()) ? null : bill.getProvider();
        form.utilityType = bill.getUtilityType();
        form.amount = bill.getAmount();
        form.dueDate = bill.getDueDate();
        form.periodStart = bill.getPeriodStart();
        form.periodEnd = bill.getPeriodEnd();
        form.usageQuantity = plain(bill.getUsageQuantity());
        form.usageUnit = bill.getUsageUnit();
        return form;
    }

    /** Copies the per-bill fields. Provider and utility type belong to the account; see BillService. */
    public void applyTo(Bill bill) {
        bill.setAmount(amount);
        bill.setDueDate(dueDate);
        bill.setPeriodStart(periodStart);
        bill.setPeriodEnd(periodEnd);
        bill.setUsageQuantity(usageQuantity);
        bill.setUsageUnit(blankToNull(usageUnit));
    }

    @AssertTrue(message = "Service period must end after it starts")
    public boolean isPeriodValid() {
        return periodStart == null || periodEnd == null || !periodEnd.isBefore(periodStart);
    }

    @AssertTrue(message = "Enter a due date or a service period end so the bill can be charted")
    public boolean isDated() {
        return dueDate != null || periodEnd != null;
    }

    /** 460.000 → 460, without switching to exponent notation (4.2E+3) for round numbers. */
    private static BigDecimal plain(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public UtilityType getUtilityType() {
        return utilityType;
    }

    public void setUtilityType(UtilityType utilityType) {
        this.utilityType = utilityType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public void setPeriodStart(LocalDate periodStart) {
        this.periodStart = periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public void setPeriodEnd(LocalDate periodEnd) {
        this.periodEnd = periodEnd;
    }

    public BigDecimal getUsageQuantity() {
        return usageQuantity;
    }

    public void setUsageQuantity(BigDecimal usageQuantity) {
        this.usageQuantity = usageQuantity;
    }

    public String getUsageUnit() {
        return usageUnit;
    }

    public void setUsageUnit(String usageUnit) {
        this.usageUnit = usageUnit;
    }
}

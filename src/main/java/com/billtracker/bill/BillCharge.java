package com.billtracker.bill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** A line item on a bill, e.g. "Gas Transmission Charge  $48.17". */
@Entity
@Table(name = "bill_charge")
public class BillCharge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChargeCategory category;

    @Column(nullable = false)
    private BigDecimal amount;

    protected BillCharge() {
    }

    public BillCharge(Bill bill, int lineNo, String description, ChargeCategory category, BigDecimal amount) {
        this.bill = bill;
        this.lineNo = lineNo;
        this.description = description;
        this.category = category;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getDescription() {
        return description;
    }

    public ChargeCategory getCategory() {
        return category;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}

package com.billtracker.analytics.anomaly;

import com.billtracker.bill.Bill;
import com.billtracker.household.Household;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Entity
@Table(name = "alert")
public class Alert {

    public enum Severity { INFO, WARNING, CRITICAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(nullable = false)
    private String title;

    private String detail;

    private BigDecimal observed;

    private BigDecimal expected;

    /** Robust z-score for statistical alerts. */
    private BigDecimal score;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    protected Alert() {
    }

    public Alert(Bill bill, AlertKind kind, Severity severity, String title, String detail) {
        this.household = bill.getHousehold();
        this.bill = bill;
        this.kind = kind;
        this.severity = severity;
        this.title = title;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    public Alert withStats(BigDecimal observed, BigDecimal expected, BigDecimal score) {
        this.observed = observed;
        this.expected = expected;
        this.score = score;
        return this;
    }

    public void dismiss() {
        this.dismissedAt = Instant.now();
    }

    /** A due-date reminder whose due date has passed is no longer "due soon". */
    public boolean isExpiredReminder(LocalDate today) {
        return kind == AlertKind.DUE_SOON && bill != null && bill.getDueDate() != null
                && bill.getDueDate().isBefore(today);
    }

    public Long getId() {
        return id;
    }

    public Bill getBill() {
        return bill;
    }

    public Long getBillId() {
        return bill.getId();
    }

    public AlertKind getKind() {
        return kind;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public BigDecimal getObserved() {
        return observed;
    }

    public BigDecimal getExpected() {
        return expected;
    }

    public BigDecimal getScore() {
        return score;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public LocalDate getCreatedDate() {
        return LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }
}

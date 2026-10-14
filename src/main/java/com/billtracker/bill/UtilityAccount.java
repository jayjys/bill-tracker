package com.billtracker.bill;

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

import java.time.Instant;

/** One service from one provider, e.g. "PG&E gas, account ••8905". Bills hang off accounts. */
@Entity
@Table(name = "utility_account")
public class UtilityAccount {

    public static final String UNKNOWN_PROVIDER = "Unknown";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @Column(nullable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "utility_type", nullable = false)
    private UtilityType utilityType;

    /** Empty string rather than null so the unique constraint treats "no number" as one value. */
    @Column(name = "account_last4", nullable = false)
    private String accountLast4 = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UtilityAccount() {
    }

    public UtilityAccount(Household household, String provider, UtilityType utilityType, String accountLast4) {
        this.household = household;
        this.provider = provider == null || provider.isBlank() ? UNKNOWN_PROVIDER : provider;
        this.utilityType = utilityType;
        this.accountLast4 = accountLast4 == null ? "" : accountLast4;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Household getHousehold() {
        return household;
    }

    public void setHousehold(Household household) {
        this.household = household;
    }

    public String getProvider() {
        return provider;
    }

    public UtilityType getUtilityType() {
        return utilityType;
    }

    public String getAccountLast4() {
        return accountLast4;
    }

    public String getLabel() {
        return provider + " " + utilityType.label().toLowerCase()
                + (accountLast4.isEmpty() ? "" : " ••" + accountLast4);
    }
}

package com.billtracker.bill;

import com.billtracker.household.Household;
import com.billtracker.user.AppUser;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bill")
public class Bill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who uploaded or forwarded it. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser uploadedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    /** Eager: provider and utility type are shown wherever a bill is. */
    @ManyToOne(optional = false)
    @JoinColumn(name = "utility_account_id")
    private UtilityAccount account;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<BillCharge> charges = new ArrayList<>();

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "usage_quantity")
    private BigDecimal usageQuantity;

    @Column(name = "usage_unit")
    private String usageUnit;

    @Column(name = "service_days")
    private Integer serviceDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillSource source;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "content_sha256")
    private String contentSha256;

    @Column(name = "raw_text")
    private String rawText;

    @Column(name = "parser_version", nullable = false)
    private int parserVersion = 1;

    /** Semicolon-separated notes from the parser's self-checks, e.g. charges not adding up. */
    @Column(name = "parse_warnings")
    private String parseWarnings;

    @Column(name = "parse_confidence", nullable = false)
    private BigDecimal parseConfidence = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BillStatus status = BillStatus.NEEDS_REVIEW;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Bill() {
    }

    public Bill(AppUser uploadedBy, UtilityAccount account, BillSource source) {
        this.uploadedBy = uploadedBy;
        this.account = account;
        this.household = account == null ? null : account.getHousehold();
        this.source = source;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
        serviceDays = computeServiceDays();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        serviceDays = computeServiceDays();
    }

    /** Days of service covered, counting both ends (Jul 31 – Aug 29 is 30 days). */
    private Integer computeServiceDays() {
        if (periodStart == null || periodEnd == null) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(periodStart, periodEnd) + 1;
    }

    /** Service days, or a typical 30 when the period is unknown, for per-day normalisation. */
    public int getEffectiveDays() {
        Integer days = computeServiceDays();
        return days == null || days <= 0 ? 30 : days;
    }

    public BigDecimal getCostPerDay() {
        return amount == null ? null : amount.divide(BigDecimal.valueOf(getEffectiveDays()), 2, RoundingMode.HALF_UP);
    }

    /** Equal split between the people living in the household. */
    public BigDecimal getAmountPerPerson() {
        return amount == null ? null
                : amount.divide(BigDecimal.valueOf(Math.max(1, household.getOccupants())), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal getUsagePerDay() {
        return usageQuantity == null ? null
                : usageQuantity.divide(BigDecimal.valueOf(getEffectiveDays()), 3, RoundingMode.HALF_UP);
    }

    public void replaceCharges(List<BillCharge> newCharges) {
        charges.clear();
        charges.addAll(newCharges);
    }

    /**
     * The date a bill is charted under: end of the service period when known,
     * otherwise the due date, otherwise when it was added.
     */
    public LocalDate getStatementDate() {
        if (periodEnd != null) {
            return periodEnd;
        }
        if (dueDate != null) {
            return dueDate;
        }
        return createdAt == null ? LocalDate.now() : LocalDate.ofInstant(createdAt, ZoneOffset.UTC);
    }

    /** Cost per unit of usage (e.g. $/kWh), or null when either side is unknown. */
    public BigDecimal getUnitCost() {
        if (amount == null || usageQuantity == null || usageQuantity.signum() <= 0) {
            return null;
        }
        return amount.divide(usageQuantity, 4, RoundingMode.HALF_UP);
    }

    public Long getId() {
        return id;
    }

    public AppUser getUploadedBy() {
        return uploadedBy;
    }

    public Household getHousehold() {
        return household;
    }

    public void setHousehold(Household household) {
        this.household = household;
    }

    public UtilityAccount getAccount() {
        return account;
    }

    public void setAccount(UtilityAccount account) {
        this.account = account;
        this.household = account.getHousehold();
    }

    public String getProvider() {
        return account.getProvider();
    }

    public UtilityType getUtilityType() {
        return account.getUtilityType();
    }

    public String getAccountLast4() {
        return account.getAccountLast4();
    }

    public List<BillCharge> getCharges() {
        return charges;
    }

    public Integer getServiceDays() {
        return serviceDays;
    }

    public int getParserVersion() {
        return parserVersion;
    }

    public void setParserVersion(int parserVersion) {
        this.parserVersion = parserVersion;
    }

    public String getParseWarnings() {
        return parseWarnings;
    }

    public void setParseWarnings(String parseWarnings) {
        this.parseWarnings = parseWarnings;
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

    public BillSource getSource() {
        return source;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public BigDecimal getParseConfidence() {
        return parseConfidence;
    }

    public void setParseConfidence(BigDecimal parseConfidence) {
        this.parseConfidence = parseConfidence;
    }

    public BillStatus getStatus() {
        return status;
    }

    public void setStatus(BillStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

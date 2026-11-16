package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One premium period on a policy's due schedule (NS-SH04-001). The billed amount is fixed when the instalment is
 * issued and never changes; adjustments and allocations are separate records, so what was billed is always known.
 */
@Entity
@Table(name = "bil_instalment")
public class Instalment {

    public static final String DUE = "DUE";
    public static final String PART_PAID = "PART_PAID";
    public static final String PAID = "PAID";
    /** Not owed: a period with no cover, closed when cover resumed without back premiums (TC-18). */
    public static final String WAIVED = "WAIVED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "billed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal billedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String purpose;

    @Column(nullable = false, length = 20)
    private String status;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Instalment() {
    }

    Instalment(String ref, String policyRef, String customerRef, LocalDate periodStart, LocalDate periodEnd,
            BigDecimal billedAmount, String purpose, String createdBy) {
        this.ref = ref;
        this.policyRef = policyRef;
        this.customerRef = customerRef;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.dueDate = periodStart;
        this.billedAmount = billedAmount;
        this.currency = "BWP";
        this.purpose = purpose;
        this.status = DUE;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void status(String status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public BigDecimal getBilledAmount() {
        return billedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getStatus() {
        return status;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

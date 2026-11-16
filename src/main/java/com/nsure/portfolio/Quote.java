package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One version of a quote (NS-IL01-001): what was priced (inputs), with which rate version, how (the calculation
 * trace), until when it can be accepted, and whether it was. A revised offer is the next version of the same
 * reference; the inputs and trace of every version stay as they were priced (NS-SH03-005).
 */
@Entity
@Table(name = "nb_quote")
public class Quote {

    /** Priced, waiting for the customer's acceptance. */
    public static final String QUOTED = "QUOTED";
    public static final String ACCEPTED = "ACCEPTED";
    /** A later version replaced it before it was accepted. */
    public static final String SUPERSEDED = "SUPERSEDED";

    public static final String RATE_TABLE = "RATE_TABLE";
    public static final String FLAT_TIER = "FLAT_TIER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, length = 20)
    private String ref;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "customer_ref", nullable = false, length = 20)
    private String customerRef;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(nullable = false, length = 100)
    private String product;

    @Column(nullable = false, length = 60)
    private String tier;

    @Column(name = "sum_assured", nullable = false, precision = 19, scale = 2)
    private BigDecimal sumAssured;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal premium;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "pricing_method", nullable = false, length = 20)
    private String pricingMethod;

    @Column(name = "rate_version", nullable = false, length = 60)
    private String rateVersion;

    @Column(name = "priced_on", nullable = false)
    private LocalDate pricedOn;

    @Column(name = "valid_until", nullable = false)
    private LocalDate validUntil;

    @Column(name = "inputs_json", nullable = false, length = 4000)
    private String inputsJson;

    @Column(name = "trace_json", nullable = false, length = 4000)
    private String traceJson;

    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;

    @Column(name = "service_version", nullable = false, length = 40)
    private String serviceVersion;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 2000)
    private String differences;

    @Column(name = "accepted_by", length = 100)
    private String acceptedBy;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "acceptance_note", length = 300)
    private String acceptanceNote;

    @Column(name = "application_ref", length = 20)
    private String applicationRef;

    @Column(name = "policy_ref", length = 20)
    private String policyRef;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Quote() {
    }

    Quote(String ref, int versionNo, CustomerAccount customer, String product, String tier, BigDecimal sumAssured,
            BigDecimal premium, String pricingMethod, String rateVersion, LocalDate pricedOn, LocalDate validUntil,
            String inputsJson, String traceJson, String inputHash, String serviceVersion, String createdBy) {
        this.ref = ref;
        this.versionNo = versionNo;
        this.customerRef = customer.getRef();
        this.customerName = customer.getName();
        this.product = product;
        this.tier = tier;
        this.sumAssured = sumAssured;
        this.premium = premium;
        this.currency = "BWP";
        this.pricingMethod = pricingMethod;
        this.rateVersion = rateVersion;
        this.pricedOn = pricedOn;
        this.validUntil = validUntil;
        this.inputsJson = inputsJson;
        this.traceJson = traceJson;
        this.inputHash = inputHash;
        this.serviceVersion = serviceVersion;
        this.status = QUOTED;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired(LocalDate today) {
        return validUntil.isBefore(today);
    }

    void accept(String actor, String note) {
        this.status = ACCEPTED;
        this.acceptedBy = actor;
        this.acceptedAt = LocalDateTime.now();
        this.acceptanceNote = note;
    }

    void supersede() {
        this.status = SUPERSEDED;
    }

    void differences(String differences) {
        this.differences = differences;
    }

    void linkApplication(String applicationRef) {
        this.applicationRef = applicationRef;
    }

    void linkPolicy(String policyRef) {
        this.policyRef = policyRef;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getProduct() {
        return product;
    }

    public String getTier() {
        return tier;
    }

    public BigDecimal getSumAssured() {
        return sumAssured;
    }

    public BigDecimal getPremium() {
        return premium;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPricingMethod() {
        return pricingMethod;
    }

    public String getRateVersion() {
        return rateVersion;
    }

    public LocalDate getPricedOn() {
        return pricedOn;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getInputsJson() {
        return inputsJson;
    }

    public String getTraceJson() {
        return traceJson;
    }

    public String getInputHash() {
        return inputHash;
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    public String getStatus() {
        return status;
    }

    public String getDifferences() {
        return differences;
    }

    public String getAcceptedBy() {
        return acceptedBy;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public String getAcceptanceNote() {
        return acceptanceNote;
    }

    public String getApplicationRef() {
        return applicationRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

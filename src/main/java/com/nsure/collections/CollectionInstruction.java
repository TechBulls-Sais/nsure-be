package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One debit-order or mobile-money collection of one instalment (NS-SH04-002). Its reference is never reused: a
 * failed collection is tried again under a new instruction.
 *
 * <pre>
 *   PREPARED ─▶ APPROVED ─▶ SUBMITTED ─┬─▶ CONFIRMED ─┬─▶ RETURNED
 *                                      ├─▶ PENDING ───┤   └─▶ REVERSED
 *                                      └─▶ FAILED     └─▶ FAILED
 * </pre>
 * Submitted is not settlement: only a confirmation in the bank's response collects the premium.
 */
@Entity
@Table(name = "col_instruction")
public class CollectionInstruction {

    public static final String PREPARED = "PREPARED";
    public static final String APPROVED = "APPROVED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String FAILED = "FAILED";
    public static final String RETURNED = "RETURNED";
    public static final String REVERSED = "REVERSED";
    public static final String CANCELLED = "CANCELLED";
    /** Still a claim on the instalment: no new instruction may be raised for it. */
    static final Set<String> LIVE = Set.of(PREPARED, APPROVED, SUBMITTED, PENDING, CONFIRMED);
    /** With the bank and not yet answered: money that may still arrive (NS-SH04-004). */
    static final Set<String> AWAITING_BANK = Set.of(SUBMITTED, PENDING);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private CollectionBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mandate_id", nullable = false)
    private CollectionMandate mandate;

    @Column(name = "instalment_ref", nullable = false, length = 20)
    private String instalmentRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "customer_ref", length = 20)
    private String customerRef;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 60)
    private String institution;

    @Column(name = "account_masked", nullable = false, length = 60)
    private String accountMasked;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "provider_reference", unique = true, length = 60)
    private String providerReference;

    @Column(name = "result_code", length = 20)
    private String resultCode;

    @Column(name = "result_text", length = 500)
    private String resultText;

    @Column(name = "receipt_ref", length = 20)
    private String receiptRef;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "reversal_ref", length = 20)
    private String reversalRef;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected CollectionInstruction() {
    }

    CollectionInstruction(String ref, CollectionBatch batch, CollectionMandate mandate, Instalment instalment,
            BigDecimal amount, LocalDate collectionDate) {
        this.ref = ref;
        this.batch = batch;
        this.mandate = mandate;
        this.instalmentRef = instalment.getRef();
        this.policyRef = instalment.getPolicyRef();
        this.customerRef = instalment.getCustomerRef();
        this.channel = mandate.getChannel();
        this.institution = mandate.getInstitution();
        this.accountMasked = mandate.maskedAccount();
        this.amount = amount;
        this.currency = instalment.getCurrency();
        this.periodStart = instalment.getPeriodStart();
        this.periodEnd = instalment.getPeriodEnd();
        this.collectionDate = collectionDate;
        this.status = PREPARED;
    }

    void status(String status) {
        this.status = status;
    }

    void submitted(String providerReference) {
        this.providerReference = providerReference;
        this.status = SUBMITTED;
        this.submittedAt = LocalDateTime.now();
    }

    void answered(String status, String code, String text) {
        this.status = status;
        this.resultCode = code;
        this.resultText = text == null || text.length() <= 500 ? text : text.substring(0, 500);
        this.respondedAt = LocalDateTime.now();
    }

    void applied(String receiptRef, Long transactionId) {
        this.receiptRef = receiptRef;
        this.transactionId = transactionId;
    }

    void returned(String status, String reversalRef, String reason) {
        this.status = status;
        this.reversalRef = reversalRef;
        this.resultText = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
        this.returnedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public CollectionBatch getBatch() {
        return batch;
    }

    public CollectionMandate getMandate() {
        return mandate;
    }

    public String getInstalmentRef() {
        return instalmentRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getChannel() {
        return channel;
    }

    public String getInstitution() {
        return institution;
    }

    public String getAccountMasked() {
        return accountMasked;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getCollectionDate() {
        return collectionDate;
    }

    public String getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResultText() {
        return resultText;
    }

    public String getReceiptRef() {
        return receiptRef;
    }

    public Long getTransactionId() {
        return transactionId;
    }

    public String getReversalRef() {
        return reversalRef;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }
}

package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * An intermediary's agreement, versioned (NS-SH06-001/002): the products and channels it may sell (none listed = all),
 * the commission clawback period, and the bank details commission is paid to. Drafted by one person, activated by
 * another; activating a version supersedes the previous one, which stays on record.
 */
@Entity
@Table(name = "dist_agreement")
public class Agreement {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intermediary_id", nullable = false)
    private Intermediary intermediary;

    @Column(nullable = false)
    private int version;

    @Column(length = 500)
    private String products;

    @Column(length = 100)
    private String channels;

    @Column(name = "clawback_months", nullable = false)
    private int clawbackMonths;

    @Column(name = "bank_name", length = 60)
    private String bankName;

    @Column(name = "bank_account", length = 60)
    private String bankAccount;

    @Column(name = "bank_branch", length = 60)
    private String bankBranch;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    protected Agreement() {
    }

    Agreement(Intermediary intermediary, int version, String products, String channels, int clawbackMonths,
            String bankName, String bankAccount, String bankBranch, LocalDate effectiveFrom, String note, String actor) {
        this.ref = "TMP-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.intermediary = intermediary;
        this.version = version;
        this.products = products;
        this.channels = channels;
        this.clawbackMonths = clawbackMonths;
        this.bankName = bankName;
        this.bankAccount = bankAccount;
        this.bankBranch = bankBranch;
        this.effectiveFrom = effectiveFrom;
        this.status = DRAFT;
        this.note = note;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    void numbered() {
        this.ref = String.format("AGR-%05d", id);
    }

    void activate(String actor) {
        this.status = ACTIVE;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void supersede() {
        this.status = SUPERSEDED;
    }

    /** No products listed means every product. */
    public boolean covers(String product) {
        return products == null || products.isBlank() || productList().stream().anyMatch(p -> p.equalsIgnoreCase(product));
    }

    public List<String> productList() {
        return products == null ? List.of() : Arrays.stream(products.split(",")).map(String::trim)
                .filter(p -> !p.isEmpty()).toList();
    }

    public String maskedAccount() {
        if (bankAccount == null) {
            return null;
        }
        return bankAccount.length() <= 4 ? "****" : "****" + bankAccount.substring(bankAccount.length() - 4);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public Intermediary getIntermediary() {
        return intermediary;
    }

    public int getVersion() {
        return version;
    }

    public String getProducts() {
        return products;
    }

    public String getChannels() {
        return channels;
    }

    public int getClawbackMonths() {
        return clawbackMonths;
    }

    public String getBankName() {
        return bankName;
    }

    public String getBankAccount() {
        return bankAccount;
    }

    public String getBankBranch() {
        return bankBranch;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public String getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }
}

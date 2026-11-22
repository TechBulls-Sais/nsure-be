package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Cancellation of a scheme for non-payment (NS-GL05-004): proposed once the grace period after the oldest unpaid
 * invoice has run out, decided by a second person. On approval members keep cover to the paid-to date (member
 * continuity) and may apply to continue cover individually until {@code continuationUntil}; a scheme that pays first
 * is cured instead.
 */
@Entity
@Table(name = "grp_scheme_lapse")
public class SchemeLapse {

    public static final String PROPOSED = "PROPOSED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String CURED = "CURED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "lapse_status", nullable = false, length = 20)
    private String status;

    @Column(name = "paid_to_date")
    private LocalDate paidToDate;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "grace_ended", nullable = false)
    private LocalDate graceEnded;

    @Column(name = "arrears_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal arrearsAmount;

    @Column(name = "oldest_invoice_ref", length = 20)
    private String oldestInvoiceRef;

    @Column(name = "continuation_until")
    private LocalDate continuationUntil;

    @Column(name = "members_affected", nullable = false)
    private int membersAffected;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(name = "proposed_by", nullable = false, length = 100)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private LocalDateTime proposedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 300)
    private String decisionNote;

    protected SchemeLapse() {
    }

    SchemeLapse(String ref, GroupScheme scheme, LocalDate paidToDate, LocalDate effectiveDate, LocalDate graceEnded,
            BigDecimal arrearsAmount, String oldestInvoiceRef, LocalDate continuationUntil, int membersAffected,
            String note, String proposedBy) {
        this.ref = ref;
        this.scheme = scheme;
        this.status = PROPOSED;
        this.paidToDate = paidToDate;
        this.effectiveDate = effectiveDate;
        this.graceEnded = graceEnded;
        this.arrearsAmount = arrearsAmount;
        this.oldestInvoiceRef = oldestInvoiceRef;
        this.continuationUntil = continuationUntil;
        this.membersAffected = membersAffected;
        this.note = note.length() <= 500 ? note : note.substring(0, 497) + "...";
        this.proposedBy = proposedBy;
        this.proposedAt = LocalDateTime.now();
    }

    void decide(String status, String actor, String note) {
        this.status = status;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
        this.decisionNote = note == null || note.length() <= 300 ? note : note.substring(0, 297) + "...";
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getPaidToDate() {
        return paidToDate;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public LocalDate getGraceEnded() {
        return graceEnded;
    }

    public BigDecimal getArrearsAmount() {
        return arrearsAmount;
    }

    public String getOldestInvoiceRef() {
        return oldestInvoiceRef;
    }

    public LocalDate getContinuationUntil() {
        return continuationUntil;
    }

    public int getMembersAffected() {
        return membersAffected;
    }

    public String getNote() {
        return note;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public LocalDateTime getProposedAt() {
        return proposedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }
}

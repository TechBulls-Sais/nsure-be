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
import java.util.Set;

/**
 * A claim on a member's group cover, recorded against the cover episode it falls in (domain boundary, section 08: a
 * group certificate is not an individual policy). The register links claims to the scheme so that a closure, renewal
 * or retrospective leaver can see the claims still pending or already paid; assessment rules stay with the insurer.
 */
@Entity
@Table(name = "grp_claim")
public class GroupClaim {

    public static final String NOTIFIED = "NOTIFIED";
    public static final String APPROVED = "APPROVED";
    public static final String DECLINED = "DECLINED";
    /** An income benefit being paid monthly until review or cessation (AT-GL05-03). */
    public static final String IN_PAYMENT = "IN_PAYMENT";
    public static final String PAID = "PAID";
    public static final String CEASED = "CEASED";

    /** Claims not yet decided or not yet paid: what a closure or renewal reports as pending. */
    public static final Set<String> PENDING = Set.of(NOTIFIED, APPROVED);
    /** Claims the insurer has accepted liability for. */
    public static final Set<String> ADMITTED = Set.of(APPROVED, IN_PAYMENT, PAID, CEASED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private SchemeMember member;

    @Column(name = "benefit_type", nullable = false, length = 30)
    private String benefitType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "claimed_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal claimedAmount;

    @Column(name = "approved_amount", precision = 15, scale = 2)
    private BigDecimal approvedAmount;

    @Column(name = "claim_status", nullable = false, length = 20)
    private String status;

    @Column(name = "next_review_date")
    private LocalDate nextReviewDate;

    @Column(name = "payment_reference", length = 80)
    private String paymentReference;

    @Column(length = 500)
    private String note;

    @Column(name = "notified_by", nullable = false, length = 100)
    private String notifiedBy;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected GroupClaim() {
    }

    GroupClaim(String ref, SchemeMember member, String benefitType, LocalDate eventDate, BigDecimal claimedAmount,
            String note, String actor) {
        this.ref = ref;
        this.scheme = member.getScheme();
        this.member = member;
        this.benefitType = benefitType;
        this.eventDate = eventDate;
        this.claimedAmount = claimedAmount;
        this.status = NOTIFIED;
        this.note = note;
        this.notifiedBy = actor;
        this.notifiedAt = LocalDateTime.now();
        touch(actor);
    }

    void approve(BigDecimal amount, boolean income, LocalDate firstReview, String note, String actor) {
        this.approvedAmount = amount;
        this.status = income ? IN_PAYMENT : APPROVED;
        this.nextReviewDate = income ? firstReview : null;
        decided(note, actor);
    }

    void decline(String note, String actor) {
        this.status = DECLINED;
        decided(note, actor);
    }

    void paid(String paymentReference, String actor) {
        this.status = PAID;
        this.paymentReference = paymentReference;
        touch(actor);
    }

    void reviewed(LocalDate nextReview, String note, String actor) {
        this.nextReviewDate = nextReview;
        this.note = note;
        touch(actor);
    }

    void cease(String note, String actor) {
        this.status = CEASED;
        this.nextReviewDate = null;
        this.note = note;
        touch(actor);
    }

    private void decided(String note, String actor) {
        this.note = note;
        this.decidedBy = actor;
        this.decidedAt = LocalDateTime.now();
        touch(actor);
    }

    private void touch(String actor) {
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
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

    public SchemeMember getMember() {
        return member;
    }

    public String getBenefitType() {
        return benefitType;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public BigDecimal getClaimedAmount() {
        return claimedAmount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDate getNextReviewDate() {
        return nextReviewDate;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public String getNote() {
        return note;
    }

    public String getNotifiedBy() {
        return notifiedBy;
    }

    public LocalDateTime getNotifiedAt() {
        return notifiedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

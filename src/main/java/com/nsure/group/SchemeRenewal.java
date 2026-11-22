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
 * A scheme renewal (NS-GL05-005): prepared from the expiring year's terms, census and claims experience, negotiated
 * through quotation versions, accepted by the sponsor and approved by a second person, which renews the scheme into
 * the next year on the accepted version's category terms. Quoted, Negotiating, Accepted, Renewed or Withdrawn.
 */
@Entity
@Table(name = "grp_renewal")
public class SchemeRenewal {

    public static final String QUOTED = "QUOTED";
    public static final String NEGOTIATING = "NEGOTIATING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String RENEWED = "RENEWED";
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "renewal_status", nullable = false, length = 20)
    String status;

    @Column(name = "expiring_year", nullable = false)
    int expiringYear;

    @Column(name = "expiring_start", nullable = false)
    LocalDate expiringStart;

    @Column(name = "expiring_end", nullable = false)
    LocalDate expiringEnd;

    @Column(name = "next_start", nullable = false)
    LocalDate nextStart;

    @Column(name = "next_end", nullable = false)
    LocalDate nextEnd;

    @Column(length = 4000)
    String experience;

    @Column(length = 4000)
    String checks;

    @Column(name = "recommended_change", nullable = false, precision = 7, scale = 2)
    BigDecimal recommendedChange;

    @Column(name = "current_version", nullable = false)
    int currentVersion;

    @Column(name = "accepted_version")
    Integer acceptedVersion;

    @Column(name = "sponsor_reference", length = 80)
    String sponsorReference;

    @Column(name = "acceptance_note", length = 300)
    String acceptanceNote;

    @Column(name = "gap_note", length = 300)
    String gapNote;

    @Column(name = "accepted_by", length = 100)
    String acceptedBy;

    @Column(name = "accepted_at")
    LocalDateTime acceptedAt;

    @Column(name = "approved_by", length = 100)
    String approvedBy;

    @Column(name = "approved_at")
    LocalDateTime approvedAt;

    @Column(name = "approval_note", length = 300)
    String approvalNote;

    @Column(name = "created_by", nullable = false, length = 100)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    LocalDateTime createdAt;

    protected SchemeRenewal() {
    }

    SchemeRenewal(String ref, GroupScheme scheme, SchemeYear year, String createdBy) {
        this.ref = ref;
        this.scheme = scheme;
        this.status = QUOTED;
        this.expiringYear = year.getPolicyYear();
        this.expiringStart = year.getStartDate();
        this.expiringEnd = year.getEndDate();
        this.nextStart = year.getEndDate().plusDays(1);
        this.nextEnd = nextStart.plusYears(1).minusDays(1);
        this.recommendedChange = BigDecimal.ZERO.setScale(2);
        this.currentVersion = 0;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
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
}

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
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A scheme year as a record (NS-GL05-006): its number and dates, so past years keep their dates after a renewal and a
 * new year can be checked for overlaps or gaps against the one before.
 */
@Entity
@Table(name = "grp_scheme_year")
public class SchemeYear {

    public static final String CURRENT = "CURRENT";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "policy_year", nullable = false)
    private int policyYear;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "year_status", nullable = false, length = 20)
    private String status;

    @Column(name = "source_ref", length = 40)
    private String sourceRef;

    @Column(name = "gap_note", length = 300)
    private String gapNote;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected SchemeYear() {
    }

    SchemeYear(GroupScheme scheme, int policyYear, LocalDate startDate, LocalDate endDate, String sourceRef,
            String gapNote, String createdBy) {
        this.scheme = scheme;
        this.policyYear = policyYear;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = CURRENT;
        this.sourceRef = sourceRef;
        this.gapNote = gapNote;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    void expire() {
        this.status = EXPIRED;
    }

    public Long getId() {
        return id;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public int getPolicyYear() {
        return policyYear;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getStatus() {
        return status;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getGapNote() {
        return gapNote;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}

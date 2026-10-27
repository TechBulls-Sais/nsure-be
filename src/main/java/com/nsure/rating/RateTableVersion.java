package com.nsure.rating;

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

/**
 * One immutable set of rates. A price or band change never edits a version: it closes the current one
 * (stamps valid_to) and adds the next, so a policy's premium stays traceable to the exact version (R-12).
 */
@Entity
@Table(name = "ins_rate_table_version")
public class RateTableVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rate_table_id", nullable = false)
    private RateTable rateTable;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "change_summary", length = 4000)
    private String changeSummary;

    protected RateTableVersion() {
    }

    RateTableVersion(RateTable rateTable, int versionNo, LocalDate validFrom, String createdBy, String changeSummary) {
        this.rateTable = rateTable;
        this.versionNo = versionNo;
        this.validFrom = validFrom;
        this.createdBy = createdBy;
        this.changeSummary = changeSummary;
    }

    public Long getId() {
        return id;
    }

    public RateTable getRateTable() {
        return rateTable;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public String getSource() {
        return source;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    /** "RT-00001-v2", the value stamped onto a policy priced from this version. */
    public String getLabel() {
        return rateTable.getRef() + "-v" + versionNo;
    }

    void close(LocalDate on) {
        this.validTo = on;
    }
}

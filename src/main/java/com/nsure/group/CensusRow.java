package com.nsure.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One staged census line exactly as submitted (values kept as text, so an unparseable value can be shown back) with
 * its outcome: VALID / INVALID after validation, APPLIED / REJECTED after commit, and the member it created or changed.
 */
@Entity
@Table(name = "grp_census_row")
public class CensusRow {

    public static final String VALID = "VALID";
    public static final String INVALID = "INVALID";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private CensusBatch batch;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "member_key", length = 40)
    private String memberKey;

    @Column(name = "first_name", length = 60)
    private String firstName;

    @Column(name = "last_name", length = 60)
    private String lastName;

    @Column(name = "date_of_birth", length = 20)
    private String dateOfBirth;

    @Column(name = "category_code", length = 20)
    private String categoryCode;

    @Column(length = 30)
    private String salary;

    @Column(name = "row_action", length = 20)
    private String action;

    @Column(name = "effective_date", length = 20)
    private String effectiveDate;

    @Column(name = "row_status", nullable = false, length = 20)
    private String status;

    @Column(name = "error_detail", length = 1000)
    private String error;

    @Column(name = "member_id")
    private Long memberId;

    protected CensusRow() {
    }

    CensusRow(CensusBatch batch, int lineNo, String[] cells) {
        this.batch = batch;
        this.lineNo = lineNo;
        this.memberKey = clip(cells[0], 40);
        this.firstName = clip(cells[1], 60);
        this.lastName = clip(cells[2], 60);
        this.dateOfBirth = clip(cells[3], 20);
        this.categoryCode = clip(cells[4], 20);
        this.salary = clip(cells[5], 30);
        this.action = clip(cells[6], 20);
        this.effectiveDate = clip(cells[7], 20);
    }

    void outcome(String status, String error) {
        this.status = status;
        this.error = error == null || error.length() <= 1000 ? error : error.substring(0, 997) + "...";
    }

    void applied(Long memberId) {
        this.status = APPLIED;
        this.memberId = memberId;
    }

    static String clip(String value, int max) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public CensusBatch getBatch() {
        return batch;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getMemberKey() {
        return memberKey;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public String getSalary() {
        return salary;
    }

    public String getAction() {
        return action;
    }

    public String getEffectiveDate() {
        return effectiveDate;
    }

    public String getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Long getMemberId() {
        return memberId;
    }
}

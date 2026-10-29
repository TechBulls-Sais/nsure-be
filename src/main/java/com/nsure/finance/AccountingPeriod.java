package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** A monthly accounting period: Open → SoftClosed → Closed; reopening needs Finance authority (spec §09). */
@Entity
@Table(name = "fin_period")
public class AccountingPeriod {

    public static final String OPEN = "OPEN";
    public static final String SOFT_CLOSED = "SOFT_CLOSED";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_code", nullable = false, unique = true, length = 7)
    private String code;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 500)
    private String note;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AccountingPeriod() {
    }

    AccountingPeriod(String code, String actor) {
        this.code = code;
        this.status = OPEN;
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    void moveTo(String status, String note, String actor) {
        this.status = status;
        this.note = note;
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    /** System events still post to a soft-closed period; only a closed period redirects them. */
    boolean acceptsPostings() {
        return !CLOSED.equals(status);
    }

    public String getCode() {
        return code;
    }

    public String getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

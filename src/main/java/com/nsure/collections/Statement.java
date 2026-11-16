package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A provider or bank statement for one period, reconciled to receipts (NS-SH04-006). */
@Entity
@Table(name = "bil_statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "period_from", nullable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", nullable = false)
    private LocalDate periodTo;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    protected Statement() {
    }

    Statement(String ref, String provider, LocalDate periodFrom, LocalDate periodTo, String uploadedBy) {
        this.ref = ref;
        this.provider = provider;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getProvider() {
        return provider;
    }

    public LocalDate getPeriodFrom() {
        return periodFrom;
    }

    public LocalDate getPeriodTo() {
        return periodTo;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
}

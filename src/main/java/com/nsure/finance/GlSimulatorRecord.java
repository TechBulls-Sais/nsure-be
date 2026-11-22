package com.nsure.finance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** What the SIMULATED corporate GL received under a batch id: posted once however often it is delivered. */
@Entity
@Table(name = "fin_gl_simulator")
public class GlSimulatorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_ref", nullable = false, unique = true, length = 20)
    private String batchRef;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "gl_reference", length = 60)
    private String glReference;

    @Column(name = "debit_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal debitTotal;

    @Column(name = "credit_total", nullable = false, precision = 19, scale = 2)
    private BigDecimal creditTotal;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private int deliveries;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected GlSimulatorRecord() {
    }

    GlSimulatorRecord(String batchRef, String status, String glReference, BigDecimal debitTotal, BigDecimal creditTotal,
            int lineCount, String reason) {
        this.batchRef = batchRef;
        this.status = status;
        this.glReference = glReference;
        this.debitTotal = debitTotal;
        this.creditTotal = creditTotal;
        this.lineCount = lineCount;
        this.reason = reason;
        this.deliveries = 1;
        this.receivedAt = LocalDateTime.now();
    }

    void delivered() {
        this.deliveries++;
    }

    public String getBatchRef() {
        return batchRef;
    }

    public String getStatus() {
        return status;
    }

    public String getGlReference() {
        return glReference;
    }

    public BigDecimal getDebitTotal() {
        return debitTotal;
    }

    public BigDecimal getCreditTotal() {
        return creditTotal;
    }

    public int getLineCount() {
        return lineCount;
    }

    public String getReason() {
        return reason;
    }

    public int getDeliveries() {
        return deliveries;
    }
}

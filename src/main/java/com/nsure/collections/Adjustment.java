package com.nsure.collections;

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
import java.time.LocalDateTime;

/** A signed correction to what an instalment asks for, with a reason; the original billed amount stays. */
@Entity
@Table(name = "bil_adjustment")
public class Adjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instalment_id", nullable = false)
    private Instalment instalment;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "made_by", nullable = false, length = 100)
    private String madeBy;

    @Column(name = "made_at", nullable = false)
    private LocalDateTime madeAt;

    protected Adjustment() {
    }

    Adjustment(String ref, Instalment instalment, BigDecimal amount, String reason, String madeBy) {
        this.ref = ref;
        this.instalment = instalment;
        this.amount = amount;
        this.reason = reason;
        this.madeBy = madeBy;
        this.madeAt = LocalDateTime.now();
    }

    public String getRef() {
        return ref;
    }

    public Instalment getInstalment() {
        return instalment;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDateTime getMadeAt() {
        return madeAt;
    }
}

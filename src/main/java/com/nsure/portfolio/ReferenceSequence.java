package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The next number for one reference series (POL-, CLM-, INV- …). Taken under a row lock (spec 01 BR-59). */
@Entity
@Table(name = "reference_sequence")
public class ReferenceSequence {

    @Id
    @Column(name = "series", length = 60)
    private String series;

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected ReferenceSequence() {
    }

    ReferenceSequence(String series, long nextValue) {
        this.series = series;
        this.nextValue = nextValue;
    }

    long take() {
        return nextValue++;
    }
}

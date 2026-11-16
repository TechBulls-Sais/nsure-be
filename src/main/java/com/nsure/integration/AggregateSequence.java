package com.nsure.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The last event version issued for one aggregate (NS-API-003). It is locked while an event is published, so
 * version N always commits before N+1 is handed out and a consumer can trust the order.
 */
@Entity
@Table(name = "int_aggregate_sequence")
public class AggregateSequence {

    @Id
    @Column(name = "aggregate_ref", length = 60)
    private String aggregateRef;

    @Column(name = "last_version", nullable = false)
    private long lastVersion;

    protected AggregateSequence() {
    }

    AggregateSequence(String aggregateRef, long lastVersion) {
        this.aggregateRef = aggregateRef;
        this.lastVersion = lastVersion;
    }

    long next() {
        lastVersion++;
        return lastVersion;
    }

    public String getAggregateRef() {
        return aggregateRef;
    }

    public long getLastVersion() {
        return lastVersion;
    }
}
